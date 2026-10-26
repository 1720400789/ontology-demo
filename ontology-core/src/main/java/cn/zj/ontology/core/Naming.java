package cn.zj.ontology.core;

import java.util.Map;

/** 属性 code 与物理列名之间的换算规则，Loader 与查询服务共用，避免两边不一致。 */
public final class Naming {

    private Naming() {
    }

    /** equipmentId → equipment_id；已含下划线或全大写的原样返回。 */
    public static String camelToSnake(String camel) {
        if (camel == null || camel.isEmpty()) {
            return camel;
        }
        StringBuilder sb = new StringBuilder(camel.length() + 4);
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && camel.charAt(i - 1) != '_') {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 显式映射优先，否则驼峰转下划线。 */
    public static String columnOf(String propertyCode, Map<String, String> overrides) {
        if (overrides != null && overrides.containsKey(propertyCode)) {
            return overrides.get(propertyCode);
        }
        return camelToSnake(propertyCode);
    }
}
