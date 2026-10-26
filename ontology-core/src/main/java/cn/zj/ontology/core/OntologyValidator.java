package cn.zj.ontology.core;

import cn.zj.ontology.core.model.OntologyDefinition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 发布前校验。目标不是做完整 JSON Schema，而是挡住"存进去以后一定出问题"的定义：
 * 空字段、重复编码、悬空引用、主键不存在、补偿动作缺失。
 */
public final class OntologyValidator {

    private static final Set<String> PROPERTY_TYPES = Set.of(
            "STRING", "TEXT", "INT", "LONG", "DECIMAL", "BOOLEAN",
            "DATE", "TIMESTAMP", "ENUM", "REF", "JSON");

    private static final Set<String> PERMISSION_VERBS = Set.of(
            "READ", "CREATE", "UPDATE", "DELETE", "PROPOSE", "EXECUTE");

    private static final Set<String> CARDINALITIES = Set.of(
            "ONE_TO_ONE", "ONE_TO_MANY", "MANY_TO_ONE", "MANY_TO_MANY");

    private OntologyValidator() {
    }

    public static void validate(OntologyDefinition def) {
        List<String> problems = new ArrayList<>();

        if (def == null) {
            throw new OntologyValidationException(List.of("文件为空或无法解析"));
        }
        if (isBlank(def.apiVersion())) {
            problems.add("apiVersion 不能为空");
        }
        if (def.metadata() == null || isBlank(def.metadata().code())) {
            problems.add("metadata.code 不能为空");
            throw new OntologyValidationException(problems);
        }
        if (def.spec() == null) {
            problems.add("spec 不能为空");
            throw new OntologyValidationException(problems);
        }

        String domainCode = def.metadata().code();
        Set<String> objectCodes = new HashSet<>();

        List<OntologyDefinition.ObjectType> objectTypes =
                def.spec().objectTypes() == null ? List.of() : def.spec().objectTypes();

        if (objectTypes.isEmpty()) {
            problems.add("spec.objectTypes 不能为空");
        }

        for (OntologyDefinition.ObjectType ot : objectTypes) {
            String where = "对象 " + ot.code();
            if (isBlank(ot.code())) {
                problems.add("存在 code 为空的对象");
                continue;
            }
            if (!objectCodes.add(ot.code())) {
                problems.add("对象 code 重复: " + ot.code());
            }
            if (isBlank(ot.name())) {
                problems.add(where + " 缺少 name");
            }
            if (isBlank(ot.primaryKey())) {
                problems.add(where + " 缺少 primaryKey");
            }

            List<OntologyDefinition.Property> props =
                    ot.properties() == null ? List.of() : ot.properties();
            if (props.isEmpty()) {
                problems.add(where + " 没有任何属性");
            }
            Set<String> propCodes = new HashSet<>();
            for (OntologyDefinition.Property p : props) {
                if (isBlank(p.code())) {
                    problems.add(where + " 存在 code 为空的属性");
                    continue;
                }
                if (!propCodes.add(p.code())) {
                    problems.add(where + " 属性 code 重复: " + p.code());
                }
                String type = p.type() == null ? "" : p.type().toUpperCase(Locale.ROOT);
                if (!PROPERTY_TYPES.contains(type)) {
                    problems.add(where + "." + p.code() + " 类型非法: " + p.type());
                }
                if (isBlank(p.owner())) {
                    problems.add(where + "." + p.code() + " 缺少 owner（没有 owner 不允许发布）");
                }
                if ("REF".equals(type)) {
                    Object ref = p.typeConfig() == null ? null : p.typeConfig().get("ref");
                    if (ref == null) {
                        problems.add(where + "." + p.code() + " 是 REF 但 typeConfig.ref 为空");
                    } else if (!objectCodes.contains(stripDomain(String.valueOf(ref), domainCode))
                            && !objectTypes.stream().anyMatch(o -> String.valueOf(ref).endsWith("." + o.code()))) {
                        problems.add(where + "." + p.code() + " 引用了不存在的对象: " + ref);
                    }
                }
            }
            if (!isBlank(ot.primaryKey()) && !propCodes.contains(ot.primaryKey())) {
                problems.add(where + " 的 primaryKey '" + ot.primaryKey() + "' 不在属性列表中");
            }
            if (ot.binding() == null || isBlank(ot.binding().sourceRef())) {
                problems.add(where + " 缺少 binding.sourceRef");
            }
        }

        List<OntologyDefinition.Relation> relations =
                def.spec().relations() == null ? List.of() : def.spec().relations();
        Set<String> relationCodes = new HashSet<>();
        for (OntologyDefinition.Relation r : relations) {
            if (isBlank(r.code()) || !relationCodes.add(r.code())) {
                problems.add("关系 code 为空或重复: " + r.code());
                continue;
            }
            if (!objectCodes.contains(stripDomain(r.from(), domainCode))) {
                problems.add("关系 " + r.code() + " 的 from 不存在: " + r.from());
            }
            if (!objectCodes.contains(stripDomain(r.to(), domainCode))) {
                problems.add("关系 " + r.code() + " 的 to 不存在: " + r.to());
            }
            if (r.cardinality() == null || !CARDINALITIES.contains(r.cardinality())) {
                problems.add("关系 " + r.code() + " 的 cardinality 非法: " + r.cardinality());
            }
            if (r.binding() == null || isBlank(r.binding().joinKind())) {
                problems.add("关系 " + r.code() + " 缺少 binding.joinKind");
            }
        }

        Set<String> actionCodes = new HashSet<>();
        List<OntologyDefinition.Action> actions =
                def.spec().actions() == null ? List.of() : def.spec().actions();
        for (OntologyDefinition.Action a : actions) {
            if (isBlank(a.code()) || !actionCodes.add(a.code())) {
                problems.add("动作 code 为空或重复: " + a.code());
                continue;
            }
            if (!objectCodes.contains(stripDomain(a.target(), domainCode))) {
                problems.add("动作 " + a.code() + " 的 target 不存在: " + a.target());
            }
            if (Boolean.TRUE.equals(a.compensable()) && isBlank(a.compensateAction())) {
                problems.add("动作 " + a.code() + " 声明可补偿但未指定 compensateAction");
            }
            if (a.paramSchema() == null) {
                problems.add("动作 " + a.code() + " 缺少 paramSchema");
            }
        }
        for (OntologyDefinition.Action a : actions) {
            if (!isBlank(a.compensateAction()) && !actionCodes.contains(a.compensateAction())) {
                problems.add("动作 " + a.code() + " 的补偿动作不存在: " + a.compensateAction());
            }
        }

        Set<String> functionCodes = new HashSet<>();
        List<OntologyDefinition.Function> functions =
                def.spec().functions() == null ? List.of() : def.spec().functions();
        for (OntologyDefinition.Function f : functions) {
            if (isBlank(f.code()) || !functionCodes.add(f.code())) {
                problems.add("函数 code 为空或重复: " + f.code());
            }
            if (isBlank(f.implKey())) {
                problems.add("函数 " + f.code() + " 缺少 implKey");
            }
            if (f.inputSchema() == null || f.outputSchema() == null) {
                problems.add("函数 " + f.code() + " 缺少 inputSchema 或 outputSchema");
            }
        }

        List<OntologyDefinition.Permission> permissions =
                def.spec().permissionPolicies() == null ? List.of() : def.spec().permissionPolicies();
        for (OntologyDefinition.Permission p : permissions) {
            String target = p.objectType();
            boolean all = target == null || target.isBlank() || "*".equals(target);
            if (!all && !objectCodes.contains(stripDomain(target, domainCode))) {
                problems.add("权限策略引用了不存在的对象: " + target);
            }
            if (p.action() == null || !PERMISSION_VERBS.contains(p.action())) {
                problems.add("权限策略的 action 非法: " + p.action());
            }
            if (!"ALLOW".equals(p.effect()) && !"DENY".equals(p.effect())) {
                problems.add("权限策略的 effect 非法: " + p.effect());
            }
        }

        if (!problems.isEmpty()) {
            throw new OntologyValidationException(problems);
        }
    }

    /** 把 maint.equipment 变成 equipment；没有域前缀时原样返回。 */
    private static String stripDomain(String ref, String domainCode) {
        if (Objects.equals(ref, domainCode)) {
            return ref;
        }
        String prefix = domainCode + ".";
        return ref != null && ref.startsWith(prefix) ? ref.substring(prefix.length()) : ref;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
