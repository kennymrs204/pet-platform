package com.petplatform.common.query;

/**
 * LIKE 关键词归一化：把用户输入变成「包含匹配」的**字面量**部分。
 *
 * <p>SQL 侧必须与 {@code ESCAPE '!'} 配套使用——不依赖 MySQL 的反斜杠默认转义，
 * 也不依赖 {@code NO_BACKSLASH_ESCAPES} 这类会话级 sql_mode。
 *
 * <p>不做转义时，用户输入的 {@code %} 会变成「匹配任意串」、{@code _} 会变成「匹配任意单字」：
 * 那不是搜索，是把用户输入当成 LIKE 模式语法执行（结果集被放大，且与用户看到的关键词不符）。
 */
public final class LikePattern {

    /** LIKE 的转义符；本身也要转义，否则 {@code !!} 与 {@code !%} 的语义会互相解释。 */
    public static final char ESCAPE_CHAR = '!';

    private LikePattern() {
    }

    /** 转义 {@code %} {@code _} 与转义符本身；null/空归一为空串（是否发起查询由调用方决定）。 */
    public static String literal(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == ESCAPE_CHAR || c == '%' || c == '_') {
                sb.append(ESCAPE_CHAR);
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
