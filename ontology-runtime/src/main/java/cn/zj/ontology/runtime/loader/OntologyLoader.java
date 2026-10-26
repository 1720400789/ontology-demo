package cn.zj.ontology.runtime.loader;

import cn.zj.ontology.core.CanonicalJson;
import cn.zj.ontology.core.Naming;
import cn.zj.ontology.core.OntologyValidator;
import cn.zj.ontology.core.model.OntologyDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 把 YAML 定义写入元模型。
 *
 * <p>W1 策略：
 * <ul>
 *   <li>域级内容哈希不变 → 整体跳过，重启不重复写；</li>
 *   <li>对象类型按自身哈希判断，变了才发新版本，历史版本保留；</li>
 *   <li>关系、函数、动作、权限按域整体重建（它们没有版本化，见 docs/00-plan.md 债务表）。</li>
 * </ul>
 */
@Service
public class OntologyLoader {

    private static final Logger log = LoggerFactory.getLogger(OntologyLoader.class);

    private static final DateTimeFormatter RELEASE_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd.HHmmss");

    /**
     * 加载器版本。持久化格式或换算规则变了就 +1，域级哈希随之变化，下次启动自动重载。
     * loader-v2：关系绑定配置统一成 snake_case（from_column/to_column）。
     */
    private static final String LOADER_VERSION = "loader-v2";

    private final JdbcTemplate jdbc;

    public OntologyLoader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public LoadResult load(OntologyDefinition def) {
        OntologyValidator.validate(def);

        String domainCode = def.metadata().code();
        String domainHash = CanonicalJson.sha256(CanonicalJson.of(def) + LOADER_VERSION);

        UUID domainId = upsertDomain(def);
        String existingHash = latestReleaseHash(domainId);
        if (domainHash.equals(existingHash)) {
            log.info("本体 {} 无变化（hash={}），跳过写入", domainCode, shortHash(domainHash));
            return LoadResult.skipped(domainCode);
        }

        UUID connectorId = upsertConnector(domainId, def.spec().connector());

        Map<String, UUID> objectIds = new HashMap<>();
        int newVersions = 0;
        for (OntologyDefinition.ObjectType ot : def.spec().objectTypes()) {
            UUID objectTypeId = upsertObjectType(domainId, ot);
            objectIds.put(ot.code(), objectTypeId);
            if (publishIfChanged(objectTypeId, connectorId, ot)) {
                newVersions++;
            }
        }

        int relations = replaceRelations(domainId, connectorId, def.spec().relations(), objectIds);
        int functions = replaceFunctions(domainId, def.spec().functions());
        int actions = replaceActions(domainId, def.spec().actions(), objectIds);
        int permissions = replacePermissions(domainId, def.spec().permissionPolicies(), objectIds);

        String releaseVersion = RELEASE_FORMAT.format(OffsetDateTime.now());
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("hash", domainHash);
        manifest.put("source", def.metadata().name());
        manifest.put("objectTypes", objectIds.size());
        manifest.put("relations", relations);
        manifest.put("functions", functions);
        manifest.put("actions", actions);
        manifest.put("permissions", permissions);
        jdbc.update("""
                insert into ont_meta.ontology_release
                    (id, domain_id, version, status, manifest, released_by, released_at)
                values (?, ?, ?, 'PUBLISHED', ?::jsonb, 'ontology-loader', now())
                """, UUID.randomUUID(), domainId, releaseVersion, json(manifest));

        log.info("本体 {} 写入完成：对象 {} 个（新版本 {} 个）、关系 {}、函数 {}、动作 {}、权限 {}，发布 {}",
                domainCode, objectIds.size(), newVersions, relations, functions, actions, permissions, releaseVersion);

        return new LoadResult(domainCode, false, objectIds.size(), newVersions,
                relations, functions, actions, permissions, releaseVersion);
    }

    // ---------------------------------------------------------------- domain

    private UUID upsertDomain(OntologyDefinition def) {
        String code = def.metadata().code();
        List<UUID> ids = jdbc.query("select id from ont_meta.domain where code = ?",
                (rs, i) -> UUID.fromString(rs.getString(1)), code);
        if (!ids.isEmpty()) {
            UUID id = ids.get(0);
            jdbc.update("update ont_meta.domain set name = ?, description = ? where id = ?",
                    def.metadata().name(), def.metadata().description(), id);
            return id;
        }
        UUID id = UUID.randomUUID();
        jdbc.update("insert into ont_meta.domain (id, code, name, description) values (?, ?, ?, ?)",
                id, code, def.metadata().name(), def.metadata().description());
        return id;
    }

    private String latestReleaseHash(UUID domainId) {
        List<String> hashes = jdbc.query(
                "select manifest->>'hash' from ont_meta.ontology_release where domain_id = ? and status = 'PUBLISHED' "
                        + "order by released_at desc nulls last limit 1",
                (rs, i) -> rs.getString(1), domainId);
        return hashes.isEmpty() ? null : hashes.get(0);
    }

    private UUID upsertConnector(UUID domainId, OntologyDefinition.Connector connector) {
        if (connector == null) {
            throw new IllegalStateException("缺少 spec.connector");
        }
        List<UUID> ids = jdbc.query("select id from ont_meta.connector where domain_id = ? and code = ?",
                (rs, i) -> UUID.fromString(rs.getString(1)), domainId, connector.code());
        String kind = connector.kind() == null ? "JDBC" : connector.kind();
        String config = json(connector.config() == null ? Map.of() : connector.config());
        if (!ids.isEmpty()) {
            UUID id = ids.get(0);
            jdbc.update("update ont_meta.connector set kind = ?, config = ?::jsonb, secret_ref = ? where id = ?",
                    kind, config, connector.secretRef(), id);
            return id;
        }
        UUID id = UUID.randomUUID();
        jdbc.update("insert into ont_meta.connector (id, domain_id, code, kind, config, secret_ref) "
                        + "values (?, ?, ?, ?, ?::jsonb, ?)",
                id, domainId, connector.code(), kind, config, connector.secretRef());
        return id;
    }

    // ----------------------------------------------------------- object types

    private UUID upsertObjectType(UUID domainId, OntologyDefinition.ObjectType ot) {
        List<UUID> ids = jdbc.query("select id from ont_meta.object_type where domain_id = ? and code = ?",
                (rs, i) -> UUID.fromString(rs.getString(1)), domainId, ot.code());
        if (!ids.isEmpty()) {
            UUID id = ids.get(0);
            jdbc.update("update ont_meta.object_type set name = ?, description = ?, owner = ? where id = ?",
                    ot.name(), ot.description(), ownerOf(ot.owner()), id);
            return id;
        }
        UUID id = UUID.randomUUID();
        jdbc.update("insert into ont_meta.object_type (id, domain_id, code, name, description, owner) "
                        + "values (?, ?, ?, ?, ?, ?)",
                id, domainId, ot.code(), ot.name(), ot.description(), ownerOf(ot.owner()));
        return id;
    }

    private boolean publishIfChanged(UUID objectTypeId, UUID connectorId, OntologyDefinition.ObjectType ot) {
        String hash = CanonicalJson.sha256(ot);
        List<PublishedVersion> current = jdbc.query("""
                        select id, schema_hash from ont_meta.object_type_version
                        where object_type_id = ? and status = 'PUBLISHED'
                        """,
                (rs, i) -> new PublishedVersion(UUID.fromString(rs.getString(1)), rs.getString(2)), objectTypeId);

        if (!current.isEmpty() && hash.equals(current.get(0).schemaHash())) {
            return false;
        }

        Integer maxVersion = jdbc.queryForObject(
                "select coalesce(max(version), 0) from ont_meta.object_type_version where object_type_id = ?",
                Integer.class, objectTypeId);
        int nextVersion = (maxVersion == null ? 0 : maxVersion) + 1;

        UUID versionId = UUID.randomUUID();
        jdbc.update("""
                        insert into ont_meta.object_type_version
                            (id, object_type_id, version, status, primary_key_prop, title_prop, schema_hash)
                        values (?, ?, ?, 'DRAFT', ?, ?, ?)
                        """,
                versionId, objectTypeId, nextVersion, ot.primaryKey(), ot.titleProperty(), hash);

        insertProperties(versionId, ot.properties());
        insertBinding(versionId, connectorId, ot);

        jdbc.update("update ont_meta.object_type_version set status = 'DEPRECATED' "
                + "where object_type_id = ? and status = 'PUBLISHED'", objectTypeId);
        jdbc.update("update ont_meta.object_type_version set status = 'PUBLISHED', released_at = now() where id = ?",
                versionId);
        log.info("对象 {} 发布新版本 v{}（hash={}）", ot.code(), nextVersion, shortHash(hash));
        return true;
    }

    private void insertProperties(UUID versionId, List<OntologyDefinition.Property> properties) {
        int order = 10;
        for (OntologyDefinition.Property p : properties) {
            jdbc.update("""
                            insert into ont_meta.property_def
                                (id, object_type_version_id, code, name, data_type, type_config, source_expr,
                                 required, pii, owner, description, sort_order)
                            values (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
                            """,
                    UUID.randomUUID(), versionId, p.code(), p.name(),
                    p.type() == null ? "STRING" : p.type(),
                    json(p.typeConfig() == null ? Map.of() : p.typeConfig()),
                    p.sourceExpr(),
                    Boolean.TRUE.equals(p.required()),
                    Boolean.TRUE.equals(p.pii()),
                    ownerOf(p.owner()),
                    p.description(),
                    p.sortOrder() == null ? order : p.sortOrder());
            order += 10;
        }
    }

    private void insertBinding(UUID versionId, UUID connectorId, OntologyDefinition.ObjectType ot) {
        OntologyDefinition.Binding b = ot.binding();
        String pkColumn = Naming.columnOf(ot.primaryKey(), b.propertyMapping());
        Map<String, String> pkMapping = Map.of(ot.primaryKey(), pkColumn);
        jdbc.update("""
                        insert into ont_meta.binding_def
                            (id, object_type_version_id, connector_id, source_kind, source_ref,
                             pk_mapping, property_mapping, base_filter, sync_mode, freshness_sla_sec)
                        values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)
                        """,
                UUID.randomUUID(), versionId, connectorId,
                b.sourceKind() == null ? "TABLE" : b.sourceKind(),
                b.sourceRef(),
                json(pkMapping),
                json(b.propertyMapping() == null ? Map.of() : b.propertyMapping()),
                b.baseFilter(),
                b.syncMode() == null ? "VIRTUAL" : b.syncMode(),
                b.freshnessSlaSec() == null ? 60 : b.freshnessSlaSec());
    }

    // -------------------------------------------------------------- relations

    private int replaceRelations(UUID domainId, UUID connectorId,
                                 List<OntologyDefinition.Relation> relations,
                                 Map<String, UUID> objectIds) {
        jdbc.update("delete from ont_meta.relation_binding where relation_def_id in "
                + "(select id from ont_meta.relation_def where domain_id = ?)", domainId);
        jdbc.update("delete from ont_meta.relation_def where domain_id = ?", domainId);

        if (relations == null) {
            return 0;
        }
        int count = 0;
        for (OntologyDefinition.Relation r : relations) {
            UUID fromId = objectIds.get(stripDomain(r.from()));
            UUID toId = objectIds.get(stripDomain(r.to()));
            if (fromId == null || toId == null) {
                log.warn("关系 {} 的端点未在本次定义中找到，跳过", r.code());
                continue;
            }
            UUID relationId = UUID.randomUUID();
            jdbc.update("""
                            insert into ont_meta.relation_def
                                (id, domain_id, code, name, from_object_type_id, to_object_type_id,
                                 cardinality, inverse_code, description)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    relationId, domainId, r.code(), r.name(), fromId, toId,
                    r.cardinality(), r.inverse(), null);

            OntologyDefinition.RelationBinding rb = r.binding();
            if (rb != null) {
                jdbc.update("""
                                insert into ont_meta.relation_binding
                                    (id, relation_def_id, connector_id, join_kind, config)
                                values (?, ?, ?, ?, ?::jsonb)
                                """,
                        UUID.randomUUID(), relationId, connectorId, rb.joinKind(),
                        json(normalizeJoinConfig(rb.config())));
            }
            count++;
        }
        return count;
    }

    // -------------------------------------------------------------- functions

    private int replaceFunctions(UUID domainId, List<OntologyDefinition.Function> functions) {
        jdbc.update("delete from ont_meta.function_def where domain_id = ?", domainId);
        if (functions == null) {
            return 0;
        }
        for (OntologyDefinition.Function f : functions) {
            jdbc.update("""
                            insert into ont_meta.function_def
                                (id, domain_id, code, name, description, impl_key, input_schema, output_schema,
                                 returns_object_type_id, timeout_ms, deterministic, version, status, owner)
                            values (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, null, ?, ?, 1, 'PUBLISHED', ?)
                            """,
                    UUID.randomUUID(), domainId, f.code(), f.name(), f.description(), f.implKey(),
                    json(f.inputSchema()), json(f.outputSchema()),
                    f.timeoutMs() == null ? 3000 : f.timeoutMs(),
                    f.deterministic() == null || f.deterministic(),
                    ownerOf(f.owner()));
        }
        return functions.size();
    }

    // ---------------------------------------------------------------- actions

    private int replaceActions(UUID domainId, List<OntologyDefinition.Action> actions,
                               Map<String, UUID> objectIds) {
        jdbc.update("delete from ont_meta.action_def where domain_id = ?", domainId);
        if (actions == null) {
            return 0;
        }
        int count = 0;
        for (OntologyDefinition.Action a : actions) {
            UUID targetId = objectIds.get(stripDomain(a.target()));
            if (targetId == null) {
                log.warn("动作 {} 的目标对象 {} 不存在，跳过", a.code(), a.target());
                continue;
            }
            jdbc.update("""
                            insert into ont_meta.action_def
                                (id, domain_id, code, name, description, target_object_type_id, param_schema,
                                 precondition_expr, approval_policy, idempotent, idempotency_key_expr,
                                 compensable, compensate_action_code, side_effects, version, status, owner)
                            values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb, 1, 'PUBLISHED', ?)
                            """,
                    UUID.randomUUID(), domainId, a.code(), a.name(), null, targetId,
                    json(a.paramSchema()),
                    a.preconditionExpr(),
                    json(a.approvalPolicy() == null ? Map.of("mode", "NONE") : a.approvalPolicy()),
                    a.idempotent() == null || a.idempotent(),
                    a.idempotencyKeyExpr(),
                    Boolean.TRUE.equals(a.compensable()),
                    a.compensateAction(),
                    json(a.sideEffects() == null ? List.of() : a.sideEffects()),
                    ownerOf(a.owner()));
            count++;
        }
        return count;
    }

    // ------------------------------------------------------------- permissions

    private int replacePermissions(UUID domainId, List<OntologyDefinition.Permission> permissions,
                                   Map<String, UUID> objectIds) {
        jdbc.update("delete from ont_meta.permission_policy where domain_id = ?", domainId);
        if (permissions == null) {
            return 0;
        }
        for (OntologyDefinition.Permission p : permissions) {
            UUID objectTypeId = null;
            if (p.objectType() != null && !p.objectType().isBlank() && !"*".equals(p.objectType())) {
                objectTypeId = objectIds.get(stripDomain(p.objectType()));
            }
            jdbc.update("""
                            insert into ont_meta.permission_policy
                                (id, domain_id, subject_type, subject_id, object_type_id, action, effect,
                                 row_filter_expr, field_mask, priority)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                            """,
                    UUID.randomUUID(), domainId, p.subjectType(), p.subjectId(), objectTypeId,
                    p.action(), p.effect(), p.rowFilter(),
                    json(p.fieldMask() == null ? List.of() : p.fieldMask()),
                    p.priority() == null ? 100 : p.priority());
        }
        return permissions.size();
    }

    // ------------------------------------------------------------------ utils

    /** YAML 里写驼峰（fromColumn），入库统一成下划线，查询层只认一种写法。 */
    private Map<String, Object> normalizeJoinConfig(Map<String, Object> raw) {
        Map<String, Object> config = new LinkedHashMap<>();
        if (raw != null) {
            config.putAll(raw);
        }
        rename(config, "fromColumn", "from_column");
        rename(config, "toColumn", "to_column");
        rename(config, "joinTable", "join_table");
        return config;
    }

    private void rename(Map<String, Object> map, String from, String to) {
        Object value = map.remove(from);
        if (value != null && !map.containsKey(to)) {
            map.put(to, value);
        }
    }

    private String stripDomain(String ref) {
        if (ref == null) {
            return "";
        }
        int dot = ref.indexOf('.');
        return dot >= 0 ? ref.substring(dot + 1) : ref;
    }

    private String ownerOf(String owner) {
        return owner == null || owner.isBlank() ? "unknown" : owner;
    }

    private String json(Object value) {
        return CanonicalJson.of(value);
    }

    private String shortHash(String hash) {
        return hash == null ? "" : hash.substring(0, Math.min(8, hash.length()));
    }

    private record PublishedVersion(UUID id, String schemaHash) {
    }

    public record LoadResult(
            String domain,
            boolean skipped,
            int objectTypes,
            int newVersions,
            int relations,
            int functions,
            int actions,
            int permissions,
            String releaseVersion) {

        static LoadResult skipped(String domain) {
            return new LoadResult(domain, true, 0, 0, 0, 0, 0, 0, null);
        }
    }
}
