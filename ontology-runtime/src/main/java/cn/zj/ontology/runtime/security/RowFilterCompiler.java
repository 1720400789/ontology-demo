package cn.zj.ontology.runtime.security;

import cn.zj.ontology.runtime.query.SqlPredicate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 行过滤模板 → 参数化 SQL 谓词。
 *
 * <p>支持形式：{@code site_id = ${user.siteId}}、{@code a = ${user.x} and b != 'v'}。
 * 只认等值与不等值；遇到不支持的语法直接拒绝（fail closed），不猜。
 */
public final class RowFilterCompiler {

    private static final Pattern COMPARISON =
            Pattern.compile("^([A-Za-z_][\\w.]*)\\s*(==|=|!=|<>)\\s*(.+)$");

    private RowFilterCompiler() {
    }

    public static SqlPredicate compile(String expression, Actor actor) {
        if (expression == null || expression.isBlank()) {
            return new SqlPredicate(null, List.of());
        }

        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();

        for (String term : expression.split("(?i)\\s+and\\s+")) {
            Matcher matcher = COMPARISON.matcher(term.trim());
            if (!matcher.matches()) {
                throw new ForbiddenException("暂不支持的行过滤表达式: " + term);
            }
            String column = afterLastDot(matcher.group(1));
            String operator = matcher.group(2);
            Object value = resolveValue(matcher.group(3).trim(), actor);

            if (!sql.isEmpty()) {
                sql.append(" and ");
            }
            sql.append(column).append("<>".equals(operator) || "!=".equals(operator) ? " <> ?" : " = ?");
            params.add(value);
        }

        return new SqlPredicate(sql.toString(), params);
    }

    private static Object resolveValue(String raw, Actor actor) {
        if (raw.startsWith("${user.") && raw.endsWith("}")) {
            String attribute = raw.substring("${user.".length(), raw.length() - 1);
            String value = actor.attribute(attribute);
            if (value == null) {
                throw new ForbiddenException("缺少用户属性 " + attribute + "，无法应用行过滤");
            }
            return value;
        }
        if ((raw.startsWith("'") && raw.endsWith("'")) || (raw.startsWith("\"") && raw.endsWith("\""))) {
            return raw.substring(1, raw.length() - 1);
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException ignored) {
            return raw;
        }
    }

    private static String afterLastDot(String column) {
        int dot = column.lastIndexOf('.');
        return dot >= 0 ? column.substring(dot + 1) : column;
    }
}
