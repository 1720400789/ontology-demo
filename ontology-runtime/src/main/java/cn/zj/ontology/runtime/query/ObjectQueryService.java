package cn.zj.ontology.runtime.query;

import cn.zj.ontology.core.Naming;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 通用对象查询：完全由元模型驱动，不为每个对象写一个 Controller。
 *
 * <p>W1 只做「按属性等值过滤 + 分页 + 字段裁剪」；关系遍历见 {@link RelationQueryService}。
 */
@Service
public class ObjectQueryService {

    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_LIMIT = 20;

    private final JdbcTemplate jdbc;

    public ObjectQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ObjectPage list(String domainCode, String typeCode, Map<String, String> filters,
                           String fields, Integer limit, Integer offset) {
        return list(domainCode, typeCode, filters, fields, limit, offset, null);
    }

    public ObjectPage list(String domainCode, String typeCode, Map<String, String> filters,
                           String fields, Integer limit, Integer offset, SqlPredicate extra) {
        ObjectTypeMeta meta = meta(domainCode, typeCode);

        int safeLimit = clamp(limit == null ? DEFAULT_LIMIT : limit, 1, MAX_LIMIT);
        int safeOffset = Math.max(0, offset == null ? 0 : offset);

        List<PropertyMeta> selected = selectProperties(meta, fields);
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>();

        if (meta.binding().baseFilter() != null && !meta.binding().baseFilter().isBlank()) {
            where.append("(").append(meta.binding().baseFilter()).append(")");
        }
        if (extra != null && !extra.isEmpty()) {
            if (!where.isEmpty()) {
                where.append(" and ");
            }
            where.append("(").append(extra.sql()).append(")");
            params.addAll(extra.params());
        }
        for (Map.Entry<String, String> e : filters.entrySet()) {
            PropertyMeta p = meta.property(e.getKey());
            if (p == null) {
                throw ObjectQueryException.badRequest(
                        "对象 " + typeCode + " 没有属性 '" + e.getKey() + "'，可用属性：" + meta.propertyCodes());
            }
            if (!where.isEmpty()) {
                where.append(" and ");
            }
            // 统一转文本比较，避免 varchar 与 int/timestamp 的类型推断问题；演示阶段可接受索引损失
            where.append(p.column()).append("::text = ?");
            params.add(e.getValue());
        }

        String whereSql = where.isEmpty() ? "" : " where " + where;
        String countSql = "select count(*) from " + meta.binding().sourceRef() + whereSql;
        Long total = jdbc.queryForObject(countSql, Long.class, params.toArray());

        String sql = "select " + RowSupport.selectList(selected) + " from " + meta.binding().sourceRef()
                + whereSql
                + " order by " + meta.binding().pkColumn()
                + " limit ? offset ?";

        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(safeLimit);
        pageParams.add(safeOffset);

        List<Map<String, Object>> items = jdbc.query(sql, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            int i = 1;
            for (PropertyMeta p : selected) {
                row.put(p.code(), RowSupport.normalize(rs.getObject(i++), p.dataType()));
            }
            return row;
        }, pageParams.toArray());

        return new ObjectPage(domainCode, typeCode, meta.version(), total == null ? 0 : total,
                safeLimit, safeOffset, items);
    }

    public Map<String, Object> get(String domainCode, String typeCode, String id) {
        return get(domainCode, typeCode, id, null);
    }

    public Map<String, Object> get(String domainCode, String typeCode, String id, SqlPredicate extra) {
        ObjectTypeMeta meta = meta(domainCode, typeCode);
        List<PropertyMeta> properties = meta.properties();

        StringBuilder where = new StringBuilder(whereBase(meta));
        List<Object> params = new ArrayList<>();
        if (extra != null && !extra.isEmpty()) {
            where.append(" and (").append(extra.sql()).append(")");
            params.addAll(extra.params());
        }
        where.append(" and ").append(meta.binding().pkColumn()).append("::text = ?");
        params.add(id);

        String sql = "select " + RowSupport.selectList(properties) + " from " + meta.binding().sourceRef()
                + where + " limit 1";

        List<Map<String, Object>> rows = jdbc.query(sql, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            int i = 1;
            for (PropertyMeta p : properties) {
                row.put(p.code(), RowSupport.normalize(rs.getObject(i++), p.dataType()));
            }
            return row;
        }, params.toArray());

        if (rows.isEmpty()) {
            throw ObjectQueryException.notFound("对象 " + typeCode + " 不存在: " + id);
        }
        return rows.get(0);
    }

    // ------------------------------------------------------------------ meta

    public ObjectTypeMeta meta(String domainCode, String typeCode) {
        List<ObjectTypeRow> rows = jdbc.query("""
                        select ot.id, ot.code, ot.name, otv.id, otv.version, otv.primary_key_prop, otv.title_prop
                        from ont_meta.object_type ot
                        join ont_meta.domain d on d.id = ot.domain_id
                        join ont_meta.object_type_version otv
                             on otv.object_type_id = ot.id and otv.status = 'PUBLISHED'
                        where d.code = ? and ot.code = ?
                        """,
                (rs, i) -> new ObjectTypeRow(
                        UUID.fromString(rs.getString(1)),
                        rs.getString(2),
                        rs.getString(3),
                        UUID.fromString(rs.getString(4)),
                        rs.getInt(5),
                        rs.getString(6),
                        rs.getString(7)),
                domainCode, typeCode);

        if (rows.isEmpty()) {
            throw ObjectQueryException.notFound("未找到已发布的对象类型: " + domainCode + "." + typeCode);
        }
        ObjectTypeRow row = rows.get(0);

        List<BindingRow> bindings = jdbc.query("""
                        select source_ref, base_filter, property_mapping::text
                        from ont_meta.binding_def where object_type_version_id = ?
                        """,
                (rs, i) -> new BindingRow(
                        rs.getString(1), rs.getString(2), RowSupport.parseStringMap(rs.getString(3))),
                row.versionId());

        if (bindings.isEmpty()) {
            throw ObjectQueryException.notFound(
                    "对象类型缺少数据绑定: " + domainCode + "." + typeCode);
        }
        BindingRow binding = bindings.get(0);
        String pkColumn = Naming.columnOf(row.primaryKeyProp(), binding.propertyMapping());

        List<PropertyMeta> properties = jdbc.query("""
                        select code, name, data_type, type_config::text, source_expr
                        from ont_meta.property_def
                        where object_type_version_id = ?
                        order by sort_order, code
                        """,
                (rs, i) -> new PropertyMeta(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        RowSupport.parseMap(rs.getString(4)),
                        rs.getString(5),
                        binding.propertyMapping().getOrDefault(
                                rs.getString(1), Naming.camelToSnake(rs.getString(1)))),
                row.versionId());

        return new ObjectTypeMeta(row.objectTypeId(), row.code(), row.name(), row.versionId(),
                row.version(), row.primaryKeyProp(), row.titleProp(), properties,
                new BindingMeta(binding.sourceRef(), binding.baseFilter(),
                        binding.propertyMapping(), pkColumn));
    }

    String whereBase(ObjectTypeMeta meta) {
        String filter = meta.binding().baseFilter();
        return (filter == null || filter.isBlank()) ? " where 1=1" : " where (" + filter + ")";
    }

    private List<PropertyMeta> selectProperties(ObjectTypeMeta meta, String fields) {
        if (fields == null || fields.isBlank()) {
            return meta.properties();
        }
        Set<String> wanted = Set.of(fields.split(","));
        List<PropertyMeta> selected = new ArrayList<>();
        for (PropertyMeta p : meta.properties()) {
            if (wanted.contains(p.code()) || p.code().equals(meta.primaryKeyProp())) {
                selected.add(p);
            }
        }
        boolean onlyPk = selected.stream().allMatch(p -> p.code().equals(meta.primaryKeyProp()));
        if (onlyPk && !wanted.contains(meta.primaryKeyProp())) {
            throw ObjectQueryException.badRequest(
                    "fields 中没有合法属性，可用属性：" + meta.propertyCodes());
        }
        return selected;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ------------------------------------------------------------------ types

    public record ObjectPage(String domain, String type, int version, long total,
                             int limit, int offset, List<Map<String, Object>> items) {
    }

    public record PropertyMeta(String code, String name, String dataType,
                               Map<String, Object> typeConfig, String sourceExpr, String column) {
    }

    public record BindingMeta(String sourceRef, String baseFilter, Map<String, String> propertyMapping,
                              String pkColumn) {
    }

    public record ObjectTypeMeta(UUID objectTypeId, String code, String name, UUID versionId, int version,
                                 String primaryKeyProp, String titleProp, List<PropertyMeta> properties,
                                 BindingMeta binding) {
        PropertyMeta property(String code) {
            return properties.stream().filter(p -> p.code().equals(code)).findFirst().orElse(null);
        }

        String propertyCodes() {
            return properties.stream().map(PropertyMeta::code).toList().toString();
        }
    }

    private record ObjectTypeRow(UUID objectTypeId, String code, String name, UUID versionId,
                                 int version, String primaryKeyProp, String titleProp) {
    }

    private record BindingRow(String sourceRef, String baseFilter, Map<String, String> propertyMapping) {
    }
}
