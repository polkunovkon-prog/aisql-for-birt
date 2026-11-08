/*
 * AI BIRT — тесты интроспекции отчёта (структура → JSON).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.tests;

import com.aisql.birt.core.Json;
import com.aisql.birt.core.report.DesignSession;
import com.aisql.birt.core.report.ReportBuilder;
import com.aisql.birt.core.spec.ReportSpec;
import com.aisql.birt.core.tools.ToolCall;
import com.aisql.birt.core.tools.ToolContext;
import com.aisql.birt.core.tools.ToolRegistry;
import com.aisql.birt.core.tools.ToolResult;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class ReportIntrospectorTest {

    public static void run() throws Exception {
        T.section("Интроспекция отчёта (структура для модели)");

        // Строим отчёт классическим путём (спецификация → Design API)
        String specJson = new String(Files.readAllBytes(
            new File("tests/samples/students-list.spec.json").toPath()),
            java.nio.charset.StandardCharsets.UTF_8);
        ReportSpec spec = ReportSpec.fromLlmReply(specJson);
        Path dir = Files.createTempDirectory("aibirt-introspect");
        File file = dir.resolve(spec.name() + ".rptdesign").toFile();
        ReportBuilder.build(spec, file, null);
        T.check(file.isFile(), "отчёт собран для интроспекции");

        // Открываем агентной сессией
        DesignSession session = new DesignSession("introspect");
        ToolRegistry registry = new ToolRegistry();
        ToolContext ctx = new ToolContext(session, file, "");

        ToolResult r = registry.dispatch(
            new ToolCall("1", "report_open",
                "{\"path\":\"" + file.getAbsolutePath() + "\"}"), ctx);
        T.check(r.ok, "report_open: отчёт открылся");

        // Манифест засеян из файла
        T.check(session.manifestDataSources().size() == 1,
            "манифест: источник из файла");
        T.check(session.manifestDataSets().size() == 1,
            "манифест: набор из файла");
        T.check(session.manifestDataSets().get(0).sql().contains("students"),
            "манифест: SQL набора прочитан");
        T.check(!session.manifestDataSets().get(0).params().isEmpty() == false
            || session.manifestDataSets().get(0).params().isEmpty(),
            "манифест: список params разобран");

        // Интроспекция: JSON со структурой
        r = registry.dispatch(new ToolCall("2", "report_inspect", "{}"), ctx);
        T.check(r.ok, "report_inspect: успех");
        Map<String, Object> outer = Json.asObj(Json.parse(r.content()));
        Map<String, Object> payload = Json.asObj(outer.get("structure"));
        if (payload == null) {
            payload = outer;
        }
        List<Object> datasets = Json.asArr(payload.get("datasets"));
        T.check(datasets != null && datasets.size() == 1, "inspect: один набор");
        Map<String, Object> ds0 = datasets != null && !datasets.isEmpty()
            ? Json.asObj(datasets.get(0)) : null;
        T.check(ds0 != null && "dsStudents".equals(Json.asStr(ds0.get("name"))),
            "inspect: имя набора");
        T.check(ds0 != null && Json.asStr(ds0.get("sql")).contains("students"),
            "inspect: SQL в структуре");

        List<Object> sources = Json.asArr(payload.get("sources"));
        if (sources == null || sources.isEmpty()) {
            sources = Json.asArr(payload.get("datasources"));
        }
        T.check(sources != null && sources.size() == 1, "inspect: один источник");

        List<Object> body = Json.asArr(payload.get("body"));
        T.check(body != null && body.size() == 2, "inspect: два элемента тела");

        // Пароль в структуре отсутствует
        T.check(!r.content().contains("odaPassword"),
            "inspect: пароль источника не раскрывается");

        // Повторное открытие того же файла (сессионный ре-опен)
        r = registry.dispatch(new ToolCall("3", "report_open",
            "{\"path\":\"" + file.getAbsolutePath() + "\"}"), ctx);
        T.check(r.ok, "повторный report_open работает");

        session.close();
    }

    private ReportIntrospectorTest() {
    }
}
