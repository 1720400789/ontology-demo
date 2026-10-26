package cn.zj.ontology.runtime.query;

import java.util.List;

/** 一段附加的 SQL 谓词与参数，用于权限行过滤。 */
public record SqlPredicate(String sql, List<Object> params) {

    public boolean isEmpty() {
        return sql == null || sql.isBlank();
    }
}
