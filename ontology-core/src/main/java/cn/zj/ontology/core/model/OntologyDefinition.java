package cn.zj.ontology.core.model;

import java.util.List;
import java.util.Map;

/**
 * 本体定义（YAML 的内存模型）。字段名与 ontology.yaml 的键一一对应。
 * 全部使用包装类型，缺省值在 Loader 里补，避免 YAML 少写字段就崩。
 */
public record OntologyDefinition(
        String apiVersion,
        String kind,
        Metadata metadata,
        Spec spec) {

    public record Metadata(String code, String name, String owner, String description) {
    }

    public record Spec(
            Connector connector,
            List<ObjectType> objectTypes,
            List<Relation> relations,
            List<Function> functions,
            List<Action> actions,
            List<Permission> permissionPolicies) {
    }

    public record Connector(String code, String kind, Map<String, Object> config, String secretRef) {
    }

    public record ObjectType(
            String code,
            String name,
            String primaryKey,
            String titleProperty,
            String owner,
            String description,
            Binding binding,
            List<Property> properties) {
    }

    public record Property(
            String code,
            String name,
            String type,
            Map<String, Object> typeConfig,
            String sourceExpr,
            Boolean required,
            Boolean pii,
            String owner,
            String description,
            Integer sortOrder) {
    }

    public record Binding(
            String sourceKind,
            String sourceRef,
            String syncMode,
            String baseFilter,
            Map<String, String> propertyMapping,
            Integer freshnessSlaSec) {
    }

    public record Relation(
            String code,
            String name,
            String from,
            String to,
            String cardinality,
            String inverse,
            RelationBinding binding) {
    }

    public record RelationBinding(String joinKind, String connector, Map<String, Object> config) {
    }

    public record Function(
            String code,
            String name,
            String implKey,
            String owner,
            Integer timeoutMs,
            String description,
            Boolean deterministic,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema) {
    }

    public record Action(
            String code,
            String name,
            String owner,
            String target,
            Boolean idempotent,
            String idempotencyKeyExpr,
            Boolean compensable,
            String compensateAction,
            List<String> sideEffects,
            Map<String, Object> paramSchema,
            String preconditionExpr,
            Map<String, Object> approvalPolicy) {
    }

    public record Permission(
            String subjectType,
            String subjectId,
            String objectType,
            String action,
            String effect,
            Integer priority,
            String rowFilter,
            List<String> fieldMask) {
    }
}
