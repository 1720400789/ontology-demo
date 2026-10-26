package cn.zj.ontology.runtime.query;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JDBC 行到 JSON 值的转换，以及 select 列表拼装。对象查询与关系遍历共用。 */
final class RowSupport {

    static final ObjectMapper JSON = JsonMapper.builder().build();

    private RowSupport() {
    }

    static String selectList(List<ObjectQueryService.PropertyMeta> properties) {
        StringBuilder sb = new StringBuilder();
        for (ObjectQueryService.PropertyMeta p : properties) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            String expr = p.column();
            if ("JSON".equalsIgnoreCase(p.dataType())) {
                expr = expr + "::text";
            }
            sb.append(expr).append(" as \"").append(p.code()).append('"');
        }
        return sb.toString();
    }

    static Object normalize(Object value, String dataType) {
        if (value == null) {
            return null;
        }
        if ("JSON".equalsIgnoreCase(dataType)) {
            return parseJson(value.toString());
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime().toString();
        }
        if (value instanceof Date d) {
            return d.toLocalDate().toString();
        }
        if (value instanceof BigDecimal bd) {
            BigDecimal stripped = bd.stripTrailingZeros();
            // 110.00 去零后是 1.1E+2，整数部分用 BigInteger 输出，避免科学计数法
            return stripped.scale() <= 0 ? stripped.toBigIntegerExact() : stripped;
        }
        return value;
    }

    static Object parseJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return JSON.readValue(text, Object.class);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseMap(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        return JSON.readValue(text, Map.class);
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> parseStringMap(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        Map<String, Object> raw = JSON.readValue(text, Map.class);
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((k, v) -> result.put(k, String.valueOf(v)));
        return result;
    }
}
