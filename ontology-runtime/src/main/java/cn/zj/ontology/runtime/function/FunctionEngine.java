package cn.zj.ontology.runtime.function;

import cn.zj.ontology.runtime.query.ObjectQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 函数引擎：按 {@code function_def.impl_key} 找到 Spring Bean 并执行。
 *
 * <p>只读是结构性保证：{@link FunctionContext} 不暴露任何写接口。超时用虚拟线程 + Future 控制，
 * 超时后中断执行线程。
 */
@Service
public class FunctionEngine {

    private static final Logger log = LoggerFactory.getLogger(FunctionEngine.class);

    private final JdbcTemplate jdbc;
    private final ObjectQueryService objects;
    private final Map<String, OntologyFunction> registry;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ObjectMapper json = JsonMapper.builder().build();

    public FunctionEngine(JdbcTemplate jdbc, ObjectQueryService objects, List<OntologyFunction> functions) {
        this.jdbc = jdbc;
        this.objects = objects;
        Map<String, OntologyFunction> map = new HashMap<>();
        for (OntologyFunction f : functions) {
            OntologyFunction previous = map.put(f.implKey(), f);
            if (previous != null) {
                throw new IllegalStateException("函数 implKey 重复: " + f.implKey());
            }
        }
        this.registry = Map.copyOf(map);
    }

    public FunctionResult invoke(String domainCode, String code, Map<String, Object> args) {
        FunctionDef def = load(domainCode, code);
        OntologyFunction function = registry.get(def.implKey());
        if (function == null) {
            throw FunctionException.notImplemented(
                    "函数 " + code + " 已在本体声明，但实现 " + def.implKey() + " 尚未提供");
        }

        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        validate(def, safeArgs);

        long start = System.nanoTime();
        Future<Map<String, Object>> future = executor.submit(
                () -> function.apply(new DefaultContext(objects, domainCode), safeArgs));
        try {
            Map<String, Object> result = future.get(def.timeoutMs(), TimeUnit.MILLISECONDS);
            long latency = (System.nanoTime() - start) / 1_000_000;
            log.debug("函数 {} 执行完成，耗时 {}ms", code, latency);
            return new FunctionResult(code, def.version(), latency, result);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw FunctionException.timeout("函数 " + code + " 超时（" + def.timeoutMs() + "ms）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw FunctionException.internal("函数调用被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw FunctionException.internal("函数执行失败: " + cause.getMessage(), cause);
        }
    }

    public List<FunctionSummary> list(String domainCode) {
        return jdbc.query("""
                        select f.code, f.name, f.description, f.version, f.input_schema::text, f.timeout_ms
                        from ont_meta.function_def f
                        join ont_meta.domain d on d.id = f.domain_id
                        where d.code = ? and f.status = 'PUBLISHED'
                        order by f.code
                        """,
                (rs, i) -> new FunctionSummary(
                        rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getInt(4), parseSchema(rs.getString(5)), rs.getInt(6)),
                domainCode);
    }

    private FunctionDef load(String domainCode, String code) {
        List<FunctionDef> rows = jdbc.query("""
                        select f.code, f.name, f.description, f.impl_key, f.version, f.timeout_ms,
                               f.input_schema::text, f.output_schema::text
                        from ont_meta.function_def f
                        join ont_meta.domain d on d.id = f.domain_id
                        where d.code = ? and f.code = ? and f.status = 'PUBLISHED'
                        """,
                (rs, i) -> new FunctionDef(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5), rs.getInt(6),
                        parseSchema(rs.getString(7)), parseSchema(rs.getString(8))),
                domainCode, code);

        if (rows.isEmpty()) {
            throw FunctionException.notFound("未找到已发布的函数: " + domainCode + "." + code);
        }
        return rows.get(0);
    }

    /**
     * 轻量校验：必填 + 顶层类型。完整 JSON Schema 校验（嵌套、格式、枚举）留到 W3，
     * 现在不引第三方校验库，避免又踩版本坑。
     */
    private void validate(FunctionDef def, Map<String, Object> args) {
        Map<String, Object> schema = def.inputSchema();

        if (schema.get("required") instanceof List<?> required) {
            for (Object field : required) {
                if (args.get(String.valueOf(field)) == null) {
                    throw FunctionException.badRequest("缺少必填参数: " + field);
                }
            }
        }

        if (schema.get("properties") instanceof Map<?, ?> properties) {
            for (Map.Entry<?, ?> entry : properties.entrySet()) {
                Object value = args.get(String.valueOf(entry.getKey()));
                if (value == null) {
                    continue;
                }
                if (entry.getValue() instanceof Map<?, ?> spec && spec.get("type") instanceof String type) {
                    if (!typeMatches(type, value)) {
                        throw FunctionException.badRequest(
                                "参数 " + entry.getKey() + " 类型应为 " + type + "，实际为 "
                                        + value.getClass().getSimpleName());
                    }
                }
            }
        }
    }

    private boolean typeMatches(String type, Object value) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Integer || value instanceof Long;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            default -> true;
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSchema(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        return json.readValue(text, Map.class);
    }

    public record FunctionResult(String function, int version, long latencyMs, Map<String, Object> result) {
    }

    public record FunctionSummary(String code, String name, String description, int version,
                                  Map<String, Object> inputSchema, int timeoutMs) {
    }

    private record FunctionDef(String code, String name, String description, String implKey,
                               int version, int timeoutMs,
                               Map<String, Object> inputSchema, Map<String, Object> outputSchema) {
    }

    private record DefaultContext(ObjectQueryService objects, String domain) implements FunctionContext {

        @Override
        public Map<String, Object> get(String objectType, String id) {
            return objects.get(domain, objectType, id);
        }

        @Override
        public List<Map<String, Object>> query(String objectType, Map<String, String> filters) {
            return new ArrayList<>(objects.list(domain, objectType, filters, null, 200, 0).items());
        }
    }
}
