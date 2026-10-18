/*
 * AI BIRT — тесты спецификации отчёта (формат v0).
 *
 * Проверяется разбор, проверки, сводка и «ответ модели → спецификация».
 * Настоящая база данных не нужна: спецификация — просто JSON.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.tests;

import com.aisql.birt.core.spec.ReportSpec;

import java.util.List;

/** Тесты формата спецификации отчёта v0. */
public final class ReportSpecTest {

    private ReportSpecTest() {
    }

    /** Минимальная корректная спецификация. */
    private static final String MINIMAL =
        "{ \"report\": { \"name\": \"r1\", \"title\": \"Отчёт 1\" },"
        + " \"body\": [ { \"type\": \"label\", \"text\": \"Привет\" } ] }";

    /** Полная спецификация: страница, параметр, источник, набор, таблица. */
    private static String fullSpec() {
        return "{"
            + " \"report\": { \"name\": \"students_test\", \"title\": \"Студенты\","
            + "   \"page\": { \"format\": \"A4\", \"orientation\": \"portrait\","
            + "               \"margins_mm\": { \"top\": 10, \"right\": 10, \"bottom\": 10, \"left\": 15 } } },"
            + " \"parameters\": [ { \"name\": \"house_code\", \"type\": \"string\","
            + "                      \"prompt\": \"Факультет\", \"default\": \"%\" } ],"
            + " \"datasources\": [ { \"name\": \"dsDB\", \"kind\": \"jdbc\","
            + "                        \"driver\": \"org.postgresql.Driver\","
            + "                        \"url\": \"jdbc:postgresql://127.0.0.1:5432/hogwarts\","
            + "                        \"user\": \"agent_user\" } ],"
            + " \"datasets\": [ { \"name\": \"dsStudents\", \"datasource\": \"dsDB\","
            + "                    \"sql\": \"select p.last_name, s.grade_level from students s join people p on p.id = s.person_id where (? = '%' or p.last_name = ?)\","
            + "                    \"params\": [ \"house_code\", \"house_code\" ],"
            + "                    \"columns\": [ { \"name\": \"last_name\", \"type\": \"string\" },"
            + "                                  { \"name\": \"grade_level\", \"type\": \"integer\" } ] } ],"
            + " \"body\": ["
            + "   { \"type\": \"label\", \"text\": \"Студенты\", \"size_pt\": 14, \"bold\": true, \"align\": \"center\" },"
            + "   { \"type\": \"table\", \"dataset\": \"dsStudents\", \"columns\": ["
            + "       { \"header\": \"Фамилия\", \"expr\": \"row[\\\"last_name\\\"]\", \"width_mm\": 50 },"
            + "       { \"header\": \"Курс\", \"expr\": \"row[\\\"grade_level\\\"]\", \"width_mm\": 20, \"align\": \"center\", \"type\": \"integer\" } ] } ]"
            + "}";
    }

    public static void run() throws Exception {
        T.section("Спецификация отчёта v0");

        // 1. Минимум
        ReportSpec min = ReportSpec.parse(MINIMAL);
        T.eq("r1", min.name(), "имя минимальной спецификации");
        T.eq(1, min.body().size(), "в body один элемент");
        T.eq(0, min.parameters().size(), "параметров нет");
        T.eq(0, min.datasources().size(), "источников нет");

        // 2. Полная
        ReportSpec full = ReportSpec.parse(fullSpec());
        T.eq("students_test", full.name(), "имя полной спецификации");
        T.eq("Студенты", full.title(), "заголовок полной спецификации");
        T.eq(1, full.parameters().size(), "один параметр");
        T.eq("%", full.parameters().get(0).def(), "значение по умолчанию параметра");
        T.eq(1, full.datasources().size(), "один источник");
        T.check(!full.datasources().get(0).hasPassword(), "пароль в спецификации отсутствует");
        T.eq(1, full.datasets().size(), "один набор данных");
        T.eq(2, full.datasets().get(0).params().size(), "два параметра набора");
        T.eq(2, full.datasets().get(0).columns().size(), "две колонки набора");
        T.eq("A4", full.page().format(), "формат страницы A4");

        ReportSpec.Table table = null;
        ReportSpec.Label label = null;
        for (ReportSpec.BodyElement el : full.body()) {
            if (el instanceof ReportSpec.Table) {
                table = (ReportSpec.Table) el;
            } else if (el instanceof ReportSpec.Label) {
                label = (ReportSpec.Label) el;
            }
        }
        T.check(label != null && label.bold(), "надпись жирная");
        T.check(table != null, "таблица найдена");
        T.eq("dsStudents", table.dataset(), "имя набора данных таблицы");
        T.eq(2, table.columns().size(), "две колонки таблицы");
        T.eq("last_name", table.columns().get(0).resultColumn(),
            "колонка результата из row[\"last_name\"]");
        T.eq("integer", table.columns().get(1).type(), "тип второй колонки");
        T.eq("grade_level", table.columns().get(1).resultColumn(),
            "колонка результата из row[\"grade_level\"]");

        // 3. Сводка
        String summary = full.summary();
        T.check(summary.contains("students_test"), "сводка содержит имя отчёта");
        T.check(summary.contains("house_code"), "сводка содержит параметр");
        T.check(summary.contains("dsStudents"), "сводка содержит набор данных");

        // 4. Оборот JSON → объект → JSON
        ReportSpec again = ReportSpec.parse(full.toJson());
        T.eq(full.name(), again.name(), "оборот toJson/parse: имя");
        T.eq(full.body().size(), again.body().size(), "оборот toJson/parse: элементов body");
        T.eq(full.datasets().get(0).sql(), again.datasets().get(0).sql(), "оборот toJson/parse: SQL");

        // 5. Ответ модели: чистый JSON, ограждение, текст вокруг
        T.eq("r1", ReportSpec.fromLlmReply(MINIMAL).name(), "ответ: чистый JSON");
        T.eq("r1", ReportSpec.fromLlmReply("```json\n" + MINIMAL + "\n```").name(),
            "ответ: JSON в ограждении ```json");
        T.eq("r1", ReportSpec.fromLlmReply("Вот спецификация:\n" + MINIMAL + "\nГотово.").name(),
            "ответ: JSON внутри текста");

        // 6. Ошибки
        expectProblems("{\"report\":{\"name\":\"r\",\"title\":\"t\"},"
            + "\"body\":[{\"type\":\"label\",\"text\":\"x\"}],\"лишнее\":1}",
            "неизвестное поле");
        expectProblems("{\"report\":{\"name\":\"r\",\"title\":\"t\"},\"body\":[]}",
            "непустым списком");
        expectProblems("{\"report\":{\"name\":\"имя с пробелом\",\"title\":\"t\"},"
            + "\"body\":[{\"type\":\"label\",\"text\":\"x\"}]}",
            "допустимы латинские буквы");
        expectProblems("{\"report\":{\"name\":\"r\",\"title\":\"t\"},"
            + "\"body\":[{\"type\":\"label\",\"text\":\"x\",\"color\":\"red\"}]}",
            "неизвестное поле");
        expectProblems(tableSpec("delete from students", "[]", ""),
            "запрещённое слово");
        expectProblems(tableSpec("select last_name from people where person_id = ?", "[]", ""),
            "знак(ов)");
        expectProblems(tableSpec("select last_name from people where name = ?", "[\"house_code\"]",
            ", \"columns\": [ { \"name\": \"last_name\", \"type\": \"string\" } ]"),
            "колонка «other» не описана");

        // 7. Проверка неверного типа параметра
        expectProblems("{\"report\":{\"name\":\"r\",\"title\":\"t\"},"
            + "\"parameters\":[{\"name\":\"p\",\"type\":\"многострочный\"}],"
            + "\"body\":[{\"type\":\"label\",\"text\":\"x\"}]}",
            "допустимы");
    }

    /** Спецификация с таблицей: SQL, params и доп. поля колонок задаются извне. */
    private static String tableSpec(String sql, String params, String extraColumn) {
        String expr = "row[\\\"other\\\"]";
        return "{"
            + " \"report\": { \"name\": \"r\", \"title\": \"t\" },"
            + " \"parameters\": [ { \"name\": \"house_code\", \"type\": \"string\" } ],"
            + " \"datasources\": [ { \"name\": \"dsDB\", \"kind\": \"jdbc\","
            + "   \"driver\": \"org.postgresql.Driver\", \"url\": \"jdbc:postgresql://h/db\","
            + "   \"user\": \"u\" } ],"
            + " \"datasets\": [ { \"name\": \"dsA\", \"datasource\": \"dsDB\","
            + "   \"sql\": \"" + sql + "\", \"params\": " + params
            + extraColumn + " } ],"
            + " \"body\": [ { \"type\": \"table\", \"dataset\": \"dsA\", \"columns\": ["
            + "   { \"header\": \"H\", \"expr\": \"" + expr + "\" } ] } ]"
            + "}";
    }

    private static void expectProblems(String json, String fragment) {
        try {
            ReportSpec.parse(json);
            T.check(false, "ожидалась ошибка спецификации («" + fragment + "»)");
        } catch (ReportSpec.SpecException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            boolean ok = message.contains(fragment);
            if (!ok) {
                List<String> problems = e.problems();
                for (String p : problems) {
                    if (p != null && p.contains(fragment)) {
                        ok = true;
                        break;
                    }
                }
            }
            T.check(ok, "ошибка спецификации содержит «" + fragment + "», получено: " + message);
        }
    }
}
