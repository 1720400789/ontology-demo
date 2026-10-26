package cn.zj.ontology.runtime.eval;

import cn.zj.ontology.runtime.query.ObjectQueryException;
import cn.zj.ontology.runtime.query.ObjectQueryService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 评测跑批（W3 最小版）：只校验 {@code expect.objects} 里的对象确实存在。
 * 需要 Agent 参与才能判定的用例（mustRefuse / mustAskClarify）标记为 SKIPPED，不假装通过。
 */
@Service
public class EvalRunner {

    private final JdbcTemplate jdbc;
    private final ObjectQueryService objects;
    private final ObjectMapper json = JsonMapper.builder().build();

    public EvalRunner(JdbcTemplate jdbc, ObjectQueryService objects) {
        this.jdbc = jdbc;
        this.objects = objects;
    }

    public Report run(String domainCode) {
        List<CaseRow> cases = jdbc.query("""
                        select e.code, e.kind, e.question, e.expect::text
                        from ont_eval.eval_case e
                        join ont_meta.domain d on d.id = e.domain_id
                        where d.code = ? and e.active
                        order by e.code
                        """,
                (rs, i) -> new CaseRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        parse(rs.getString(4))),
                domainCode);

        List<CaseResult> results = new ArrayList<>();
        for (CaseRow c : cases) {
            results.add(evaluate(domainCode, c));
        }

        int passed = (int) results.stream().filter(r -> "PASS".equals(r.status())).count();
        int failed = (int) results.stream().filter(r -> "FAIL".equals(r.status())).count();
        int skipped = (int) results.stream().filter(r -> "SKIPPED".equals(r.status())).count();
        return new Report(results.size(), passed, failed, skipped, results);
    }

    private CaseResult evaluate(String domainCode, CaseRow c) {
        Object objectsExpect = c.expect().get("objects");
        if (!(objectsExpect instanceof List<?> expected) || expected.isEmpty()) {
            return new CaseResult(c.code(), c.kind(), c.question(), "SKIPPED",
                    "需要 Agent 参与才能判定（" + c.expect().keySet() + "）");
        }
        for (Object ref : expected) {
            String[] parts = String.valueOf(ref).split(":", 2);
            if (parts.length != 2) {
                return new CaseResult(c.code(), c.kind(), c.question(), "FAIL", "期望格式应为 type:id：" + ref);
            }
            // 期望里写的是全名 maint.equipment，查询接口要的是 equipment
            String type = parts[0].contains(".") ? parts[0].substring(parts[0].lastIndexOf('.') + 1) : parts[0];
            try {
                objects.get(domainCode, type, parts[1]);
            } catch (ObjectQueryException e) {
                return new CaseResult(c.code(), c.kind(), c.question(), "FAIL", e.getMessage());
            }
        }
        return new CaseResult(c.code(), c.kind(), c.question(), "PASS",
                "对象存在：" + expected.size() + " 个");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String text) {
        return text == null ? Map.of() : json.readValue(text, Map.class);
    }

    private record CaseRow(String code, String kind, String question, Map<String, Object> expect) {
    }

    public record CaseResult(String code, String kind, String question, String status, String detail) {
    }

    public record Report(int total, int passed, int failed, int skipped, List<CaseResult> cases) {
    }
}
