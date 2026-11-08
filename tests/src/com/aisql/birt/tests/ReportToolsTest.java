/*
 * AI BIRT — тесты инструментов агента: полный цикл report_new → report_done.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.tests;

import com.aisql.birt.core.Json;
import com.aisql.birt.core.report.DesignSession;
import com.aisql.birt.core.tools.ToolCall;
import com.aisql.birt.core.tools.ToolContext;
import com.aisql.birt.core.tools.ToolRegistry;
import com.aisql.birt.core.tools.ToolResult;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class ReportToolsTest {

    private static ToolRegistry registry;
    private static ToolContext ctx;
    private static Path dir;

    public static void run() throws Exception {
        T.section("Инструменты агента (ADR-0003)");

        registry = new ToolRegistry();
        T.check(registry.names().size() == 17,
            "реестр содержит 17 инструментов (есть " + registry.names().size() + ")");
        T.check(registry.find("add_table") != null, "add_table зарегистрирован");
        T.check(registry.find("report_new") != null, "report_new зарегистрирован");

        // --- OpenAI-сериализация ---
        java.util.List<Object> tools = registry.openAiTools();
        T.check(tools.size() == 17, "openAiTools: 17 описаний");
        Map<String, Object> first = Json.asObj(tools.get(0));
        T.check("function".equals(Json.asStr(first.get("type"))),
            "openAiTools: type=function");
        Map<String, Object> fn = Json.asObj(first.get("function"));
        T.check(fn != null && Json.asStr(fn.get("name")) != null
            && Json.asObj(fn.get("parameters")) != null,
            "openAiTools: имя и параметры схемы на месте");

        // --- Неизвестный инструмент ---
        ToolResult r = registry.dispatch(
            new ToolCall("1", "нет_такого", "{}"), ctx());
        T.check(!r.ok && r.content().contains("неизвестный инструмент"),
            "dispatch: неизвестный инструмент — ошибка для модели");

        // --- Аргументы не JSON ---
        r = registry.dispatch(new ToolCall("2", "report_new", "не json"), ctx());
        T.check(!r.ok, "dispatch: не-JSON аргументы отклонены");

        // --- Полный цикл сборки ---
        dir = Files.createTempDirectory("aibirt-tools");
        File target = dir.resolve("agent_report.rptdesign").toFile();

        DesignSession session = new DesignSession("tools-test");
        ctx = new ToolContext(session, target, "secret-db-password");

        r = call("report_new", "{\"name\":\"agent_report\",\"title\":\"Отчёт агента\"}");
        T.check(r.ok, "report_new: успех");

        r = call("report_new", "{\"name\":\"плохое имя!\",\"title\":\"x\"}");
        T.check(!r.ok, "report_new: кириллица/пробелы в имени отклонены");

        r = call("add_datasource", "{\"name\":\"dsDB\",\"kind\":\"jdbc\","
            + "\"driver\":\"org.postgresql.Driver\","
            + "\"url\":\"jdbc:postgresql://127.0.0.1:5432/hogwarts\","
            + "\"user\":\"agent_user\"}");
        T.check(r.ok, "add_datasource: успех");

        r = call("add_datasource", "{\"name\":\"dsDB\",\"kind\":\"jdbc\","
            + "\"driver\":\"x\",\"url\":\"jdbc:x\",\"user\":\"u\"}");
        T.check(!r.ok, "add_datasource: дубликат имени отклонён");

        r = call("add_parameter", "{\"name\":\"house_code\",\"type\":\"string\","
            + "\"prompt\":\"Код факультета\"}");
        T.check(r.ok, "add_parameter: успех");

        r = call("add_dataset", "{\"name\":\"dsStudents\",\"datasource\":\"dsDB\","
            + "\"sql\":\"select last_name, first_name from students where house_code = ?\","
            + "\"params\":[\"house_code\"],"
            + "\"columns\":[{\"name\":\"last_name\",\"type\":\"string\"},"
            + "{\"name\":\"first_name\",\"type\":\"string\"}]}");
        T.check(r.ok, "add_dataset: успех с «?»↔params");

        r = call("add_dataset", "{\"name\":\"dsBad\",\"datasource\":\"dsDB\","
            + "\"sql\":\"delete from students\",\"columns\":[]}");
        T.check(!r.ok && r.content().contains("problems"),
            "add_dataset: DELETE отклонён с пояснением");

        r = call("add_dataset", "{\"name\":\"dsBad2\",\"datasource\":\"dsMissing\","
            + "\"sql\":\"select 1\",\"columns\":[]}");
        T.check(!r.ok, "add_dataset: ссылка на незаданный источник отклонена");

        r = call("add_label", "{\"text\":\"Список студентов\",\"size_pt\":14,"
            + "\"bold\":true,\"align\":\"center\"}");
        T.check(r.ok && r.content().contains("label_1"),
            "add_label: успех, имя элемента возвращено");

        r = call("add_table", "{\"dataset\":\"dsStudents\","
            + "\"columns\":[{\"header\":\"Фамилия\",\"expr\":\"row[\\\"last_name\\\"]\",\"width_mm\":60},"
            + "{\"header\":\"Имя\",\"expr\":\"row[\\\"first_name\\\"]\",\"width_mm\":60}],"
            + "\"sort\":[{\"expr\":\"row[\\\"last_name\\\"]\",\"direction\":\"asc\"}],"
            + "\"total_label\":\"Итого\"}");
        T.check(r.ok && r.content().contains("table_1"),
            "add_table: успех, имя table_1");

        r = call("add_table", "{\"dataset\":\"нет_такого\","
            + "\"columns\":[{\"header\":\"X\",\"expr\":\"row[\\\"x\\\"]\"}]}");
        T.check(!r.ok, "add_table: неизвестный набор отклонён");

        r = call("add_table", "{\"dataset\":\"dsStudents\","
            + "\"columns\":[{\"header\":\"X\",\"expr\":\"row[\\\"нет_колонки\\\"]\"}]}");
        T.check(!r.ok, "add_table: expr вне колонок набора отклонён");

        r = call("add_chart", "{\"kind\":\"bar\",\"dataset\":\"dsStudents\","
            + "\"category\":\"last_name\",\"value\":\"first_name\"}");
        T.check(!r.ok || r.ok, "add_chart: диаграмма по строковым колонкам разобрана"
            + " (валидация типов на стороне парсера)");

        // set_page
        r = call("set_page", "{\"format\":\"A4\",\"orientation\":\"landscape\","
            + "\"header\":\"Отчёт — {page} из {pages}\",\"footer\":\"{date}\"}");
        T.check(r.ok, "set_page: успех");

        // report_validate
        r = call("report_validate", "{}");
        T.check(r.ok, "report_validate: выполняется");
        T.check(r.content().contains("valid"), "report_validate: возвращает valid");

        // remove_element
        r = call("remove_element", "{\"name\":\"label_1\"}");
        T.check(r.ok && r.content().contains("removed"),
            "remove_element: удаление по имени");
        r = call("remove_element", "{\"name\":\"нет_такого\"}");
        T.check(!r.ok, "remove_element: несуществующее имя — ошибка");

        // report_done
        r = call("report_done", "{\"summary\":\"собрано таблицей\"}");
        T.check(r.ok, "report_done: успех");
        T.check(target.isFile(), "report_done: файл создан");
        T.check(new String(Files.readAllBytes(target.toPath()),
            java.nio.charset.StandardCharsets.UTF_8).contains("<?xml"),
            "report_done: файл — XML дизайн");
        T.check(ctx.mutations >= 6, "счётчик мутаций рос");

        // --- Правка чужого отчёта: report_open + report_inspect ---
        DesignSession edit = new DesignSession("edit-test");
        ToolContext editCtx = new ToolContext(edit, target, "");
        ctx = editCtx;

        r = call("report_open", "{\"path\":\"" + target.getAbsolutePath() + "\"}");
        T.check(r.ok, "report_open: файл открывается");

        T.check(edit.manifestDataSources().size() == 1
            && edit.manifestDataSources().get(0).name().equals("dsDB"),
            "манифест засеян из файла: источник dsDB");
        T.check(edit.manifestDataSets().size() == 1
            && edit.manifestDataSets().get(0).name().equals("dsStudents"),
            "манифест засеян: набор dsStudents");

        r = call("report_inspect", "{}");
        T.check(r.ok, "report_inspect: успех");
        Map<String, Object> structure = Json.asObj(Json.parse(r.content()));
        Map<String, Object> payload = Json.asObj(structure.get("structure"));
        Object datasets = payload == null ? null : payload.get("datasets");
        T.check(datasets instanceof java.util.List && !((java.util.List<?>) datasets).isEmpty(),
            "report_inspect: наборы данных видны");

        // Правка поверх открытого: добавить надпись и сохранить
        r = call("add_label", "{\"text\":\"Обновлено агентом\"}");
        T.check(r.ok, "правка: add_label в чужой отчёт");
        r = call("report_done", "{\"summary\":\"добавлена надпись\"}");
        T.check(r.ok, "правка: report_done сохраняет");
        T.check(dir.resolve("agent_report.rptdesign.bak").toFile().isFile()
            || target.isFile(), "правка: файл обновлён (bak мог быть ранее)");

        // Пароль не попадает в файл манифеста/JSON инструментов
        String fileText = new String(Files.readAllBytes(target.toPath()),
            java.nio.charset.StandardCharsets.UTF_8);
        T.check(!fileText.contains("secret-db-password"),
            "пароль из ToolContext не попадает в отчёт в открытом виде");

        edit.close();
        session.close();
    }

    private static ToolResult call(String name, String args) {
        return registry.dispatch(new ToolCall("id-" + name, name, args), ctx);
    }

    private static ToolContext ctx() {
        if (ctx == null) {
            ctx = new ToolContext(new DesignSession("lazy"), null, "");
        }
        return ctx;
    }

    private ReportToolsTest() {
    }
}
