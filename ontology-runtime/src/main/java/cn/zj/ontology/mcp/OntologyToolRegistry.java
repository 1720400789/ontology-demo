package cn.zj.ontology.mcp;

import cn.zj.ontology.runtime.action.ActionGateway;
import cn.zj.ontology.runtime.function.FunctionEngine;
import cn.zj.ontology.runtime.query.AuthorizedObjectService;
import cn.zj.ontology.runtime.security.Actor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 由元模型生成工具集：
 * <ul>
 *   <li>每个对象类型 → query_xxx / get_xxx</li>
 *   <li>每个已发布函数 → fn_xxx</li>
 *   <li>每个已发布动作（补偿动作除外）→ action_xxx</li>
 * </ul>
 * Agent 新增能力不需要改 Java，只要本体发布新版本。
 */
@Service
public class OntologyToolRegistry {

    private final JdbcTemplate jdbc;
    private final AuthorizedObjectService objects;
    private final FunctionEngine functions;
    private final ActionGateway actions;
    private final ObjectMapper json = JsonMapper.builder().build();

    public OntologyToolRegistry(JdbcTemplate jdbc, AuthorizedObjectService objects,
                                FunctionEngine functions, ActionGateway actions) {
        this.jdbc = jdbc;
        this.objects = objects;
        this.functions = functions;
        this.actions = actions;
    }

    public List<ToolDefinition> tools(String domain) {
        List<ToolDefinition> tools = new ArrayList<>();

        record ObjectType(String code, String name) {
        }
        List<ObjectType> objectTypes = jdbc.query("""
                        select ot.code, ot.name from ont_meta.object_type ot
                        join ont_meta.domain d on d.id = ot.domain_id
                        join ont_meta.object_type_version v
                             on v.object_type_id = ot.id and v.status = 'PUBLISHED'
                        where d.code = ? order by ot.code
                        """,
                (rs, i) -> new ObjectType(rs.getString(1), rs.getString(2)), domain);

        for (ObjectType type : objectTypes) {
            tools.add(queryTool(domain, type.code(), type.name()));
            tools.add(getTool(domain, type.code(), type.name()));
        }

        record FunctionRow(String code, String name, String description, String inputSchema) {
        }
        List<FunctionRow> functionRows = jdbc.query("""
                        select f.code, f.name, f.description, f.input_schema::text
                        from ont_meta.function_def f
                        join ont_meta.domain d on d.id = f.domain_id
                        where d.code = ? and f.status = 'PUBLISHED' order by f.code
                        """,
                (rs, i) -> new FunctionRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                domain);

        for (FunctionRow f : functionRows) {
            String toolName = "fn_" + snake(f.code());
            tools.add(new ToolDefinition(toolName,
                    "只读计算：" + f.name() + (f.description() == null ? "" : "。" + f.description()),
                    parse(f.inputSchema()),
                    (args, actor) -> functions.invoke(domain, f.code(), args)));
        }

        record ActionRow(String code, String name, String description, String paramSchema) {
        }
        List<ActionRow> actionRows = jdbc.query("""
                        select a.code, a.name, a.description, a.param_schema::text
                        from ont_meta.action_def a
                        join ont_meta.domain d on d.id = a.domain_id
                        where d.code = ? and a.status = 'PUBLISHED' order by a.code
                        """,
                (rs, i) -> new ActionRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                domain);

        Set<String> compensationCodes = jdbc.queryForList("""
                select compensate_action_code from ont_meta.action_def
                where compensate_action_code is not null
                """, String.class).stream().collect(Collectors.toSet());

        for (ActionRow a : actionRows) {
            if (compensationCodes.contains(a.code())) {
                continue; // 补偿动作只由网关回滚时调用，不暴露给 Agent
            }
            Map<String, Object> schema = mergeActionSchema(parse(a.paramSchema()));
            tools.add(new ToolDefinition("action_" + snake(a.code()),
                    "提议动作：" + a.name() + (a.description() == null ? "" : "。" + a.description())
                            + "。只提议不执行，需人工或主管审批。",
                    schema,
                    (args, actor) -> {
                        String targetId = String.valueOf(args.get("targetId"));
                        Map<String, Object> params = new LinkedHashMap<>(args);
                        params.remove("targetId");
                        return actions.propose(actor, domain, a.code(), targetId, params);
                    }));
        }

        return tools;
    }

    private ToolDefinition queryTool(String domain, String code, String name) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "filters", Map.of("type", "object", "description", "按属性等值过滤，如 {\"criticality\":\"A\"}"),
                        "fields", Map.of("type", "string", "description", "逗号分隔的属性列表"),
                        "limit", Map.of("type", "integer", "description", "最多返回条数，默认 20")));
        return new ToolDefinition("query_" + snake(code),
                "查询" + name + "列表。只返回当前调用者有权看到的数据。",
                schema,
                (args, actor) -> {
                    Map<String, String> filters = new LinkedHashMap<>();
                    if (args.get("filters") instanceof Map<?, ?> raw) {
                        raw.forEach((k, v) -> filters.put(String.valueOf(k), String.valueOf(v)));
                    }
                    Integer limit = args.get("limit") instanceof Number n ? n.intValue() : 20;
                    String fields = args.get("fields") == null ? null : String.valueOf(args.get("fields"));
                    return objects.list(actor, domain, code, filters, fields, limit, 0);
                });
    }

    private ToolDefinition getTool(String domain, String code, String name) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "required", List.of("id"),
                "properties", Map.of("id", Map.of("type", "string", "description", name + "主键")));
        return new ToolDefinition("get_" + snake(code),
                "按主键查询单个" + name + "。",
                schema,
                (args, actor) -> objects.get(actor, domain, code, String.valueOf(args.get("id"))));
    }

    /** 动作工具把 targetId 和参数平铺成一个 schema，Agent 一次填完。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeActionSchema(Map<String, Object> paramSchema) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("targetId", Map.of("type", "string", "description", "动作作用对象的主键"));
        if (paramSchema.get("properties") instanceof Map<?, ?> params) {
            params.forEach((k, v) -> properties.put(String.valueOf(k), v));
        }
        List<String> required = new ArrayList<>();
        required.add("targetId");
        if (paramSchema.get("required") instanceof List<?> rawRequired) {
            rawRequired.forEach(r -> required.add(String.valueOf(r)));
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", required);
        schema.put("properties", properties);
        return schema;
    }

    private String snake(String camel) {
        StringBuilder sb = new StringBuilder();
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) {
                if (!sb.isEmpty()) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String text) {
        return text == null || text.isBlank() ? Map.of() : json.readValue(text, Map.class);
    }
}
