/*
 * AI BIRT — тесты различий спецификаций (правка отчёта по замечанию).
 *
 * Проверяют, что панель показывает понятный список «что изменится»
 * до применения правки: заголовок, SQL, колонки, стили, группировка.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.tests;

import com.aisql.birt.core.spec.ReportSpec;
import com.aisql.birt.core.spec.SpecDiff;

import java.util.List;

/** Тесты SpecDiff: «было → стало» для правки отчёта. */
public final class SpecDiffTest {

    private SpecDiffTest() {
    }

    private static String spec(String title, String labelText, String extraColumn) {
        return "{"
            + " \"report\": { \"name\": \"r\", \"title\": \"" + title + "\" },"
            + " \"datasources\": [ { \"name\": \"dsDB\", \"kind\": \"jdbc\","
            + "   \"driver\": \"org.postgresql.Driver\", \"url\": \"jdbc:postgresql://h/db\","
            + "   \"user\": \"u\" } ],"
            + " \"datasets\": [ { \"name\": \"dsA\", \"datasource\": \"dsDB\","
            + "   \"sql\": \"select last_name, grade_level from students\","
            + "   \"columns\": [ { \"name\": \"last_name\", \"type\": \"string\" },"
            + "                 { \"name\": \"grade_level\", \"type\": \"integer\" } ] } ],"
            + " \"body\": ["
            + "   { \"type\": \"label\", \"text\": \"" + labelText + "\" },"
            + "   { \"type\": \"table\", \"dataset\": \"dsA\", \"columns\": ["
            + "       { \"header\": \"Фамилия\", \"expr\": \"row[\\\"last_name\\\"]\" }"
            + extraColumn + " ] } ]"
            + "}";
    }

    public static void run() throws Exception {
        T.section("Различия спецификаций (правка отчёта)");

        ReportSpec a = ReportSpec.parse(spec("Отчёт", "Текст", ""));
        List<String> same = SpecDiff.describe(a, a);
        T.eq(0, same.size(), "одинаковые спецификации: различий нет");

        // Заголовок, текст надписи, добавленная колонка
        ReportSpec b = ReportSpec.parse(spec("Отчёт 2", "Текст 2",
            ", { \"header\": \"Курс\", \"expr\": \"row[\\\"grade_level\\\"]\" }"));
        List<String> diff = SpecDiff.describe(a, b);
        T.check(diff.size() >= 3, "различия найдены: " + diff.size());
        T.check(contains(diff, "Заголовок"), "различия: заголовок отчёта");
        T.check(contains(diff, "Надпись"), "различия: текст надписи");
        T.check(contains(diff, "добавлены колонки") && contains(diff, "Курс"),
            "различия: добавлена колонка «Курс»");

        // Изменение SQL набора данных
        ReportSpec c = ReportSpec.parse(spec("Отчёт", "Текст", "").replace(
            "select last_name, grade_level from students",
            "select last_name, grade_level, house_name from students"));
        List<String> sqlDiff = SpecDiff.describe(a, c);
        T.check(contains(sqlDiff, "SQL"), "различия: изменён SQL набора");
        T.check(!contains(sqlDiff, "Заголовок"), "различия: неизменённые поля не попадают");

        // Группировка (добавлена)
        ReportSpec g = ReportSpec.parse(spec("Отчёт", "Текст", "")
            .replace("\"dataset\": \"dsA\",",
                "\"dataset\": \"dsA\", \"group_by\": \"grade_level\","));
        List<String> groupDiff = SpecDiff.describe(a, g);
        T.check(contains(groupDiff, "группировка"), "различия: добавлена группировка");

        // Удалённая колонка
        ReportSpec d = ReportSpec.parse(spec("Отчёт", "Текст", "")
            .replace("       { \"header\": \"Фамилия\", \"expr\": \"row[\\\"last_name\\\"]\" }",
                "       { \"header\": \"Другое\", \"expr\": \"row[\\\"last_name\\\"]\" }"));
        List<String> renamed = SpecDiff.describe(a, d);
        T.check(contains(renamed, "шапка «Фамилия» → «Другое»"),
            "различия: переименование колонки");
    }

    private static boolean contains(List<String> lines, String fragment) {
        for (String line : lines) {
            if (line != null && line.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}
