package cn.zj.ontology.runtime.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限求值：主体 × 对象 × 动作 → 允许/拒绝，附带行过滤与字段掩码。
 * 优先级数值小的先命中；同优先级 DENY 压过 ALLOW。
 */
@Service
public class PermissionService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = JsonMapper.builder().build();

    public PermissionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Decision check(String domainCode, Actor actor, String objectType, String verb) {
        List<Policy> policies = jdbc.query("""
                        select p.subject_type, p.subject_id, p.effect, p.row_filter_expr,
                               p.field_mask::text, p.priority, ot.code
                        from ont_meta.permission_policy p
                        join ont_meta.domain d on d.id = p.domain_id
                        left join ont_meta.object_type ot on ot.id = p.object_type_id
                        where d.code = ? and p.action = ?
                        """,
                (rs, i) -> new Policy(
                        rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), parseMask(rs.getString(5)), rs.getInt(6), rs.getString(7)),
                domainCode, verb);

        List<Policy> matched = policies.stream()
                .filter(p -> p.objectCode() == null || p.objectCode().equals(objectType))
                .filter(p -> matchesSubject(p, actor))
                .sorted(Comparator.comparingInt(Policy::priority)
                        .thenComparing(p -> "DENY".equals(p.effect()) ? 0 : 1))
                .toList();

        if (matched.isEmpty()) {
            return Decision.denied("没有匹配的 " + verb + " 策略");
        }
        Policy first = matched.get(0);
        if (!"ALLOW".equals(first.effect())) {
            return Decision.denied("显式拒绝：" + first.subjectType() + ":" + first.subjectId());
        }
        return new Decision(true, null, first.rowFilter(), first.fieldMask());
    }

    private boolean matchesSubject(Policy policy, Actor actor) {
        return switch (policy.subjectType()) {
            case "ROLE" -> actor.hasRole(policy.subjectId());
            case "USER", "AGENT", "SERVICE" ->
                    policy.subjectType().equals(actor.type()) && policy.subjectId().equals(actor.id());
            default -> false;
        };
    }

    @SuppressWarnings("unchecked")
    private List<String> parseMask(String text) {
        if (text == null || text.isBlank() || "[]".equals(text)) {
            return List.of();
        }
        List<Object> raw = json.readValue(text, List.class);
        return raw.stream().map(String::valueOf).toList();
    }

    public record Decision(boolean allowed, String reason, String rowFilter, List<String> fieldMask) {

        static Decision denied(String reason) {
            return new Decision(false, reason, null, List.of());
        }

        public Set<String> maskedFields() {
            return Set.copyOf(fieldMask);
        }
    }

    private record Policy(String subjectType, String subjectId, String effect, String rowFilter,
                          List<String> fieldMask, int priority, String objectCode) {
    }
}
