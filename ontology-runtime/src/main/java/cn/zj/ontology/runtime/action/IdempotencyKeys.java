package cn.zj.ontology.runtime.action;

import java.time.LocalDate;
import java.util.Map;

/**
 * 幂等键求值：支持 {@code a + ":" + b + ":" + today()} 这种拼接表达式。
 * 表达式为空时退化为「目标 + 参数哈希」，保证同一请求重复提交只生效一次。
 */
public final class IdempotencyKeys {

    private IdempotencyKeys() {
    }

    public static String evaluate(String expression, String targetId, Map<String, Object> params) {
        if (expression == null || expression.isBlank()) {
            return targetId + ":" + params.hashCode();
        }
        StringBuilder key = new StringBuilder();
        for (String token : expression.split("\\+")) {
            String t = token.trim();
            if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) {
                key.append(t, 1, t.length() - 1);
            } else if (t.startsWith("'") && t.endsWith("'") && t.length() >= 2) {
                key.append(t, 1, t.length() - 1);
            } else if ("today()".equals(t)) {
                key.append(LocalDate.now());
            } else if (params.containsKey(t)) {
                key.append(params.get(t));
            } else {
                key.append(t);
            }
        }
        return key.toString();
    }
}
