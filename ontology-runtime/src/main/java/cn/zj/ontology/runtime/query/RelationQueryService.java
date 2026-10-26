package cn.zj.ontology.runtime.query;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 关系遍历：正反两个方向都支持，先用两条简单查询实现，不引入图数据库。
 *
 * <p>关系绑定里 {@code from_column} 属于 from 端对象的表，{@code to_column} 属于 to 端。
 * 正向遍历取 from_column 的值去匹配 to_column；反向则相反。
 */
@Service
public class RelationQueryService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final JdbcTemplate jdbc;
    private final ObjectQueryService objects;

    public RelationQueryService(JdbcTemplate jdbc, ObjectQueryService objects) {
        this.jdbc = jdbc;
        this.objects = objects;
    }

    public RelationResult traverse(String domainCode, String startType, String startId,
                                   String relationCode, Integer limit) {
        RelationRow relation = loadRelation(domainCode, relationCode);

        boolean forward = relation.fromCode().equals(startType);
        if (!forward && !relation.toCode().equals(startType)) {
            throw ObjectQueryException.badRequest(
                    "关系 " + relationCode + " 连接 " + relation.fromCode() + " 与 "
                            + relation.toCode() + "，与起始类型 " + startType + " 无关");
        }

        String targetType = forward ? relation.toCode() : relation.fromCode();
        String keyColumn = forward ? relation.fromColumn() : relation.toColumn();
        String matchColumn = forward ? relation.toColumn() : relation.fromColumn();

        ObjectQueryService.ObjectTypeMeta start = objects.meta(domainCode, startType);
        ObjectQueryService.ObjectTypeMeta target = objects.meta(domainCode, targetType);

        String keySql = "select " + keyColumn + "::text from " + start.binding().sourceRef()
                + objects.whereBase(start)
                + " and " + start.binding().pkColumn() + "::text = ? limit 1";
        List<String> keys = jdbc.queryForList(keySql, String.class, startId);
        if (keys.isEmpty()) {
            throw ObjectQueryException.notFound("对象 " + startType + " 不存在: " + startId);
        }
        String key = keys.get(0);

        int safeLimit = Math.max(1, Math.min(MAX_LIMIT, limit == null ? DEFAULT_LIMIT : limit));
        if (key == null || key.isBlank()) {
            return new RelationResult(relationCode, forward ? "FORWARD" : "REVERSE",
                    startType, startId, targetType, 0, List.of());
        }

        String sql = "select " + RowSupport.selectList(target.properties()) + " from "
                + target.binding().sourceRef()
                + objects.whereBase(target)
                + " and " + matchColumn + "::text = ?"
                + " order by " + target.binding().pkColumn()
                + " limit ?";

        List<Map<String, Object>> items = jdbc.query(sql, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            int i = 1;
            for (ObjectQueryService.PropertyMeta p : target.properties()) {
                row.put(p.code(), RowSupport.normalize(rs.getObject(i++), p.dataType()));
            }
            return row;
        }, key, safeLimit);

        return new RelationResult(relationCode, forward ? "FORWARD" : "REVERSE",
                startType, startId, targetType, items.size(), items);
    }

    private RelationRow loadRelation(String domainCode, String relationCode) {
        List<RelationRow> rows = jdbc.query("""
                        select r.code, r.name, r.cardinality, rf.code, rt.code,
                               rb.config->>'from_column', rb.config->>'to_column'
                        from ont_meta.relation_def r
                        join ont_meta.domain d on d.id = r.domain_id
                        join ont_meta.object_type rf on rf.id = r.from_object_type_id
                        join ont_meta.object_type rt on rt.id = r.to_object_type_id
                        join ont_meta.relation_binding rb on rb.relation_def_id = r.id
                        where d.code = ? and r.code = ?
                        """,
                (rs, i) -> new RelationRow(
                        rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7)),
                domainCode, relationCode);

        if (rows.isEmpty()) {
            throw ObjectQueryException.notFound("未找到关系: " + domainCode + "." + relationCode);
        }
        RelationRow row = rows.get(0);
        if (row.fromColumn() == null || row.toColumn() == null) {
            throw ObjectQueryException.badRequest(
                    "关系 " + relationCode + " 的绑定缺少 from_column/to_column，暂不支持 JOIN_TABLE");
        }
        return row;
    }

    public record RelationResult(String relation, String direction, String startType, String startId,
                                 String targetType, int count, List<Map<String, Object>> items) {
    }

    private record RelationRow(String code, String name, String cardinality, String fromCode, String toCode,
                               String fromColumn, String toColumn) {
    }
}
