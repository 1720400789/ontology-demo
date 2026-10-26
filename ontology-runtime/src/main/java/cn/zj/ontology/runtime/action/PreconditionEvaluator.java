package cn.zj.ontology.runtime.action;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 前置条件求值器（W3 子集）：支持 a.b == 'x'、a >= 1、a in ('x','y')，用 and 连接。
 * 左右两侧都可以是对象属性或参数；函数调用不在支持范围，遇到即拒绝。
 */
public final class PreconditionEvaluator {

    private static final Pattern COMPARISON =
            Pattern.compile("^([A-Za-z_][\\w.]*)\\s*(==|!=|>=|<=|>|<)\\s*(.+)$");
    private static final Pattern IN =
            Pattern.compile("^([A-Za-z_][\\w.]*)\\s+in\\s+\\((.+)\\)$");
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][\\w.]*$");

    private PreconditionEvaluator() {
    }

    public static boolean evaluate(String expression, Map<String, Object> context) {
        if (expression == null || expression.isBlank()) {
            return true;
        }
        if (expression.contains("function(")) {
            throw ActionException.badRequest("前置条件暂不支持函数调用，拒绝执行: " + expression);
        }
        for (String term : expression.split("(?i)\\s+and\\s+")) {
            if (!evaluateTerm(term.trim(), context)) {
                return false;
            }
        }
        return true;
    }

    private static boolean evaluateTerm(String term, Map<String, Object> context) {
        Matcher in = IN.matcher(term);
        if (in.matches()) {
            Object left = resolve(in.group(1), context);
            return splitValues(in.group(2)).contains(String.valueOf(left));
        }

        Matcher comparison = COMPARISON.matcher(term);
        if (!comparison.matches()) {
            throw ActionException.badRequest("无法解析的前置条件: " + term);
        }

        Object left = resolve(comparison.group(1), context);
        Object right = value(comparison.group(3).trim(), context);
        String operator = comparison.group(2);

        if (left == null) {
            return "!=".equals(operator);
        }
        if (left instanceof Number && right instanceof Number) {
            int cmp = new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString()));
            return switch (operator) {
                case "==" -> cmp == 0;
                case "!=" -> cmp != 0;
                case ">" -> cmp > 0;
                case ">=" -> cmp >= 0;
                case "<" -> cmp < 0;
                case "<=" -> cmp <= 0;
                default -> false;
            };
        }

        String l = String.valueOf(left);
        String r = String.valueOf(right);
        int cmp = l.compareTo(r);
        return switch (operator) {
            case "==" -> l.equals(r);
            case "!=" -> !l.equals(r);
            case ">" -> cmp > 0;
            case ">=" -> cmp >= 0;
            case "<" -> cmp < 0;
            case "<=" -> cmp <= 0;
            default -> false;
        };
    }

    /** 变量形如 equipment.status 或 qty；对象由调用方按类型 code 放进 context。 */
    private static Object resolve(String path, Map<String, Object> context) {
        int dot = path.indexOf('.');
        if (dot < 0) {
            if (!context.containsKey(path)) {
                throw ActionException.badRequest("前置条件引用了未知参数: " + path);
            }
            return context.get(path);
        }
        String type = path.substring(0, dot);
        String property = path.substring(dot + 1);
        Object object = context.get(type);
        if (object instanceof Map<?, ?> map) {
            return map.get(property);
        }
        throw ActionException.badRequest("前置条件缺少对象上下文: " + type);
    }

    /** 右侧：带引号是字面量，数字/布尔是字面量，标识符先按变量解析（解析不到再当字面量）。 */
    private static Object value(String raw, Map<String, Object> context) {
        if (raw.startsWith("'") && raw.endsWith("'") && raw.length() >= 2) {
            return raw.substring(1, raw.length() - 1);
        }
        if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) {
            return Boolean.parseBoolean(raw);
        }
        if (IDENTIFIER.matcher(raw).matches()) {
            try {
                return resolve(raw, context);
            } catch (ActionException ignored) {
                return raw;
            }
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException ignored) {
            return raw;
        }
    }

    private static List<String> splitValues(String raw) {
        List<String> values = new ArrayList<>();
        for (String part : raw.split(",")) {
            String value = part.trim();
            if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            values.add(value);
        }
        return values;
    }
}
