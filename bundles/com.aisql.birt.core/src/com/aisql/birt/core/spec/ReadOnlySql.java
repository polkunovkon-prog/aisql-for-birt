/*
 * AI BIRT — проверка SQL на «только чтение».
 *
 * Спецификация отчёта может содержать только запросы SELECT/WITH: сборка
 * отчёта не должна приводить к изменению данных. Проверка консервативная:
 * лучше отклонить сомнительный запрос с понятным сообщением, чем выполнить
 * его на базе пользователя.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core.spec;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Простая проверка «только чтение» для SQL из спецификации отчёта. */
public final class ReadOnlySql {

    /** Максимальная длина запроса (защита от «полотна»). */
    public static final int MAX_SQL_LENGTH = 20000;

    /** Запрещённые ключевые слова (без учёта регистра, как отдельные слова). */
    private static final Pattern FORBIDDEN = Pattern.compile(
        "(?is)\\b(insert|update|delete|merge|upsert|drop|alter|create|truncate"
        + "|grant|revoke|copy|call|execute|prepare|deallocate|vacuum|analyze"
        + "|reindex|cluster|comment|lock|refresh|into|set|reset|show|listen"
        + "|notify|discard|load|import)\\b");

    private ReadOnlySql() {
    }

    /**
     * Проверяет запрос. Возвращает список замечаний (пустой — запрос допустим).
     */
    public static List<String> check(String sql) {
        List<String> problems = new ArrayList<String>();
        if (sql == null || sql.trim().isEmpty()) {
            problems.add("SQL пустой");
            return problems;
        }
        String s = sql.trim();
        if (s.length() > MAX_SQL_LENGTH) {
            problems.add("SQL длиннее " + MAX_SQL_LENGTH + " символов");
        }
        if (!s.matches("(?is)^(select|with)\\b.*")) {
            problems.add("разрешены только запросы SELECT или WITH"
                + " (запрос начинается с другого слова)");
        }
        // Много операторов: точка с запятой допускается только в самом конце.
        int semi = s.indexOf(';');
        if (semi >= 0) {
            String rest = s.substring(semi + 1).trim();
            if (!rest.isEmpty()) {
                problems.add("нельзя использовать несколько SQL-операторов"
                    + " (точка с запятой не в конце запроса)");
            }
        }
        // Ключевые слова, меняющие данные или состояние сервера.
        Matcher m = FORBIDDEN.matcher(stripQuoted(s));
        if (m.find()) {
            problems.add("в SQL найдено запрещённое слово «" + m.group(1).toLowerCase()
                + "»: допустимы только запросы на чтение (SELECT/WITH)");
        }
        return problems;
    }

    /** Убирает строковые литералы и комментарии — чтобы не ловить слова из текста. */
    private static String stripQuoted(String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'') {
                i = skipQuoted(sql, i, '\'');
                sb.append(" '' ");
            } else if (c == '"') {
                i = skipQuoted(sql, i, '"');
                sb.append(" \"\" ");
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int e = sql.indexOf('\n', i);
                i = e < 0 ? n : e + 1;
                sb.append(' ');
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int e = sql.indexOf("*/", i + 2);
                i = e < 0 ? n : e + 2;
                sb.append(' ');
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static int skipQuoted(String sql, int start, char quote) {
        int i = start + 1;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == quote) {
                if (i + 1 < n && sql.charAt(i + 1) == quote) {
                    i += 2; // экранированная кавычка
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return n;
    }
}
