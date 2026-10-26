package cn.zj.ontology.runtime.action;

import cn.zj.ontology.runtime.query.ObjectQueryService;
import cn.zj.ontology.runtime.security.Actor;
import cn.zj.ontology.runtime.security.ForbiddenException;
import cn.zj.ontology.runtime.security.PermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 动作网关：唯一允许写业务数据的入口。
 * propose 落 pending_action，execute 才真正执行；幂等靠 idempotency_key 唯一约束，
 * 越权靠 permission_policy，每一步都写 audit_log。
 */
@Service
public class ActionGateway {

    private static final Logger log = LoggerFactory.getLogger(ActionGateway.class);

    private final JdbcTemplate jdbc;
    private final ObjectQueryService objects;
    private final PermissionService permissions;
    private final Map<String, ActionExecutor> executors;
    private final ObjectMapper json = JsonMapper.builder().build();

    public ActionGateway(JdbcTemplate jdbc, ObjectQueryService objects,
                         PermissionService permissions, List<ActionExecutor> executors) {
        this.jdbc = jdbc;
        this.objects = objects;
        this.permissions = permissions;
        this.executors = executors.stream()
                .collect(Collectors.toUnmodifiableMap(ActionExecutor::actionCode, e -> e));
    }

    @Transactional
    public PendingView propose(Actor actor, String domain, String actionCode,
                               String targetId, Map<String, Object> params) {
        ActionDef def = loadDef(domain, actionCode);
        require(domain, actor, def.targetCode(), "PROPOSE", actionCode);

        Map<String, Object> safeParams = params == null ? Map.of() : params;
        validateParams(def, safeParams);

        String idempotencyKey = IdempotencyKeys.evaluate(def.idempotencyKeyExpr(), targetId, safeParams);
        List<PendingRow> existing = findByKey(idempotencyKey);
        if (!existing.isEmpty()) {
            log.info("动作 {} 幂等命中，返回已有记录 {}", actionCode, existing.get(0).id());
            return view(existing.get(0));
        }

        Map<String, Object> target = objects.get(domain, def.targetCode(), targetId);
        requirePrecondition(def, target, safeParams);

        boolean autoApprove = "NONE".equals(approvalMode(def));
        UUID id = UUID.randomUUID();
        jdbc.update("""
                        insert into ont_rt.pending_action
                            (id, idempotency_key, action_code, action_version, target_object_type,
                             target_object_id, params, status, proposed_by, proposed_at, approved_by, approved_at)
                        values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, now(), ?, case when ? then now() end)
                        """,
                id, idempotencyKey, actionCode, def.version(), def.targetCode(), targetId,
                toJson(safeParams), autoApprove ? "APPROVED" : "PROPOSED",
                actor.id() + "@" + actor.type(), autoApprove ? "auto" : null, autoApprove);

        audit(actor, "PROPOSE", def.targetCode(), targetId, actionCode, def.version(), safeParams,
                Map.of("status", autoApprove ? "APPROVED" : "PROPOSED"));
        return findById(id);
    }

    @Transactional
    public PendingView approve(Actor actor, String domain, String pendingId) {
        PendingRow row = requirePending(pendingId);
        if (!"PROPOSED".equals(row.status())) {
            throw ActionException.conflict("只有 PROPOSED 状态可以审批，当前为 " + row.status());
        }
        ActionDef def = loadDef(domain, row.actionCode());
        require(domain, actor, def.targetCode(), "EXECUTE", row.actionCode());

        jdbc.update("update ont_rt.pending_action set status = 'APPROVED', approved_by = ?, approved_at = now() "
                + "where id = ?", actor.id() + "@" + actor.type(), row.id());
        audit(actor, "APPROVE", def.targetCode(), row.targetObjectId(), row.actionCode(),
                def.version(), row.params(), Map.of("status", "APPROVED"));
        return findById(row.id());
    }

    @Transactional
    public PendingView reject(Actor actor, String domain, String pendingId) {
        PendingRow row = requirePending(pendingId);
        if (!"PROPOSED".equals(row.status())) {
            throw ActionException.conflict("只有 PROPOSED 状态可以驳回，当前为 " + row.status());
        }
        ActionDef def = loadDef(domain, row.actionCode());
        require(domain, actor, def.targetCode(), "EXECUTE", row.actionCode());

        jdbc.update("update ont_rt.pending_action set status = 'REJECTED', approved_by = ?, approved_at = now() "
                + "where id = ?", actor.id() + "@" + actor.type(), row.id());
        audit(actor, "REJECT", def.targetCode(), row.targetObjectId(), row.actionCode(),
                def.version(), row.params(), Map.of("status", "REJECTED"));
        return findById(row.id());
    }

    @Transactional
    public PendingView execute(Actor actor, String domain, String pendingId) {
        PendingRow row = requirePending(pendingId);
        if ("EXECUTED".equals(row.status())) {
            return findById(row.id());
        }
        if (!"APPROVED".equals(row.status())) {
            throw ActionException.conflict("动作尚未审批，当前为 " + row.status());
        }

        ActionDef def = loadDef(domain, row.actionCode());
        require(domain, actor, def.targetCode(), "EXECUTE", row.actionCode());

        Map<String, Object> target = objects.get(domain, row.targetObjectType(), row.targetObjectId());
        requirePrecondition(def, target, row.params());

        ActionExecutor executor = executors.get(row.actionCode());
        if (executor == null) {
            throw ActionException.notImplemented("动作 " + row.actionCode() + " 已声明，但执行器尚未实现");
        }

        Map<String, Object> result;
        try {
            result = executor.execute(target, row.params(), jdbc);
        } catch (RuntimeException e) {
            jdbc.update("update ont_rt.pending_action set status = 'FAILED', error = ? where id = ?",
                    e.getMessage(), row.id());
            audit(actor, "EXECUTE", def.targetCode(), row.targetObjectId(), row.actionCode(),
                    def.version(), row.params(), Map.of("error", String.valueOf(e.getMessage())));
            throw ActionException.internal("动作执行失败: " + e.getMessage(), e);
        }

        jdbc.update("update ont_rt.pending_action set status = 'EXECUTED', result = ?::jsonb, executed_at = now() "
                + "where id = ?", toJson(result), row.id());
        audit(actor, "EXECUTE", def.targetCode(), row.targetObjectId(), row.actionCode(),
                def.version(), row.params(), result);
        return findById(row.id());
    }

    public List<PendingView> pending(String domain, String status) {
        List<UUID> ids = jdbc.query("""
                        select p.id from ont_rt.pending_action p
                        join ont_meta.action_def a on a.code = p.action_code
                        join ont_meta.domain d on d.id = a.domain_id
                        where d.code = ? and (?::text is null or p.status = ?::text)
                        order by p.proposed_at desc limit 100
                        """,
                (rs, i) -> UUID.fromString(rs.getString(1)), domain, status, status);
        List<PendingView> views = new ArrayList<>();
        for (UUID id : ids) {
            views.add(findById(id));
        }
        return views;
    }

    private PendingView findById(UUID id) {
        return view(requirePending(id.toString()));
    }

    private List<PendingRow> findByKey(String idempotencyKey) {
        return jdbc.query("""
                        select id, action_code, action_version, target_object_type, target_object_id,
                               params::text, status, proposed_by, approved_by, result::text, error
                        from ont_rt.pending_action where idempotency_key = ?
                        """, (rs, i) -> mapRow(rs), idempotencyKey);
    }

    private PendingRow requirePending(String pendingId) {
        UUID id;
        try {
            id = UUID.fromString(pendingId);
        } catch (IllegalArgumentException e) {
            throw ActionException.badRequest("非法的 pending id: " + pendingId);
        }
        List<PendingRow> rows = jdbc.query("""
                        select id, action_code, action_version, target_object_type, target_object_id,
                               params::text, status, proposed_by, approved_by, result::text, error
                        from ont_rt.pending_action where id = ?
                        """, (rs, i) -> mapRow(rs), id);
        if (rows.isEmpty()) {
            throw ActionException.notFound("动作记录不存在: " + pendingId);
        }
        return rows.get(0);
    }

    private PendingRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PendingRow(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getInt(3),
                rs.getString(4), rs.getString(5), parseMap(rs.getString(6)), rs.getString(7),
                rs.getString(8), rs.getString(9), parseMap(rs.getString(10)), rs.getString(11));
    }

    private void require(String domain, Actor actor, String objectType, String verb, String actionCode) {
        PermissionService.Decision decision = permissions.check(domain, actor, objectType, verb);
        if (!decision.allowed()) {
            throw new ForbiddenException("无权 " + verb + " " + actionCode + "：" + decision.reason());
        }
    }

    private void requirePrecondition(ActionDef def, Map<String, Object> target, Map<String, Object> params) {
        Map<String, Object> context = new HashMap<>(params);
        context.put(def.targetCode(), target);
        boolean ok = PreconditionEvaluator.evaluate(def.precondition(), context);
        if (!ok) {
            throw ActionException.conflict("前置条件不满足: " + def.precondition());
        }
    }

    private void validateParams(ActionDef def, Map<String, Object> params) {
        Map<String, Object> schema = def.paramSchema();
        if (schema.get("required") instanceof List<?> required) {
            for (Object field : required) {
                if (params.get(String.valueOf(field)) == null) {
                    throw ActionException.badRequest("缺少必填参数: " + field);
                }
            }
        }
        if (schema.get("properties") instanceof Map<?, ?> properties) {
            for (Map.Entry<?, ?> entry : properties.entrySet()) {
                Object value = params.get(String.valueOf(entry.getKey()));
                if (value != null && entry.getValue() instanceof Map<?, ?> spec
                        && spec.get("type") instanceof String type && !typeMatches(type, value)) {
                    throw ActionException.badRequest("参数 " + entry.getKey() + " 类型应为 " + type);
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

    private String approvalMode(ActionDef def) {
        Object mode = def.approvalPolicy().get("mode");
        return mode == null ? "ROLE" : String.valueOf(mode);
    }

    private ActionDef loadDef(String domain, String code) {
        List<ActionDef> defs = jdbc.query("""
                        select a.code, a.version, ot.code, a.param_schema::text, a.precondition_expr,
                               a.approval_policy::text, a.idempotency_key_expr
                        from ont_meta.action_def a
                        join ont_meta.domain d on d.id = a.domain_id
                        join ont_meta.object_type ot on ot.id = a.target_object_type_id
                        where d.code = ? and a.code = ? and a.status = 'PUBLISHED'
                        order by a.version desc limit 1
                        """,
                (rs, i) -> new ActionDef(rs.getString(1), rs.getInt(2), rs.getString(3),
                        parseMap(rs.getString(4)), rs.getString(5), parseMap(rs.getString(6)),
                        rs.getString(7)),
                domain, code);
        if (defs.isEmpty()) {
            throw ActionException.notFound("未找到已发布的动作: " + domain + "." + code);
        }
        return defs.get(0);
    }

    private void audit(Actor actor, String operation, String objectType, String objectId,
                       String actionCode, int version, Map<String, Object> params, Map<String, Object> result) {
        jdbc.update("""
                        insert into ont_rt.audit_log
                            (trace_id, actor_id, actor_type, operation, object_type_code, object_id,
                             action_code, definition_version, params, result_summary, latency_ms)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, 0)
                        """,
                UUID.randomUUID(), actor.id(), actor.type(), operation, objectType, objectId,
                actionCode, version, toJson(params), toJson(result));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMap(String text) {
        return text == null || text.isBlank() ? Map.of() : json.readValue(text, Map.class);
    }

    private String toJson(Object value) {
        return json.writeValueAsString(value);
    }

    private PendingView view(PendingRow row) {
        return new PendingView(row.id().toString(), row.actionCode(), row.actionVersion(), row.status(),
                row.targetObjectType(), row.targetObjectId(), row.params(), row.proposedBy(),
                row.approvedBy(), row.result(), row.error());
    }

    public record PendingView(String id, String action, int version, String status,
                              String targetType, String targetId, Map<String, Object> params,
                              String proposedBy, String approvedBy, Map<String, Object> result,
                              String error) {
    }

    private record PendingRow(UUID id, String actionCode, int actionVersion, String targetObjectType,
                              String targetObjectId, Map<String, Object> params, String status,
                              String proposedBy, String approvedBy, Map<String, Object> result,
                              String error) {
    }

    private record ActionDef(String code, int version, String targetCode, Map<String, Object> paramSchema,
                             String precondition, Map<String, Object> approvalPolicy,
                             String idempotencyKeyExpr) {
    }
}
