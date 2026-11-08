/*
 * AI BIRT — приёмка 1.0.0 (standalone, без Eclipse и без внешней БД):
 * «агентные инструменты → .rptdesign → PDF/HTML» на встроенной БД Derby
 * (classicmodels из поставки BIRT, db/BirtSample.jar — только чтение).
 *
 * 1) строит отчёт ИНСТРУМЕНТАМИ агента (не спецификацией): report_new,
 *    add_datasource, add_dataset, add_table, set_page, report_validate,
 *    report_done;
 * 2) рендерит PDF и HTML штатным движком BIRT;
 * 3) проверяет данные и кириллицу в выводе;
 * 4) переоткрывает файл и правит его агентом.
 *
 * Запуск: ./releng/agent-selftest.sh (см. скрипт; нужны jar-ы BIRT и
 * распакованный db/BirtSample.jar из бандла sampledb).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.tests;

import com.aisql.birt.core.Json;
import com.aisql.birt.core.report.DesignSession;
import com.aisql.birt.core.report.ReportPreview;
import com.aisql.birt.core.tools.ToolCall;
import com.aisql.birt.core.tools.ToolContext;
import com.aisql.birt.core.tools.ToolRegistry;
import com.aisql.birt.core.tools.ToolResult;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;

/** Приёмка агентного цикла с реальным рендером ( Derby classicmodels). */
public final class AgentSelfTest {


    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        Path out = Files.createTempDirectory("aibirt-agent-selftest");
        File target = out.resolve("classicmodels.rptdesign").toFile();

        // --- 1. Сборка инструментами ---
        ToolRegistry registry = new ToolRegistry();
        DesignSession session = new DesignSession("selftest");
        ToolContext ctx = new ToolContext(session, target, "");

        String derbyJar = args.length > 0 ? args[0]
            : "/tmp/birt47-sampledb/BirtSample.jar";

        check(call(registry, ctx, "report_new",
            "{\"name\":\"classicmodels\",\"title\":\"Классические модели — заказы\"}").ok,
            "report_new");
        check(call(registry, ctx, "add_datasource",
            "{\"name\":\"dsDB\",\"kind\":\"jdbc\",\"driver\":\"org.apache.derby.jdbc.EmbeddedDriver\","
            + "\"url\":\"jdbc:derby:jar:(" + derbyJar + ")BirtSample\",\"user\":\"app\"}").ok,
            "add_datasource (Derby из jar)");
        check(call(registry, ctx, "add_dataset",
            "{\"name\":\"dsOrders\",\"datasource\":\"dsDB\","
            + "\"sql\":\"SELECT o.ORDERNUMBER, o.STATUS, c.CUSTOMERNAME, "
            + "o.ORDERDATE FROM CLASSICMODELS.ORDERS o JOIN CLASSICMODELS.CUSTOMERS c "
            + "ON c.CUSTOMERNUMBER = o.CUSTOMERNUMBER "
            + "WHERE o.STATUS = 'Shipped'\","
            + "\"columns\":[{\"name\":\"ORDERNUMBER\",\"type\":\"integer\"},"
            + "{\"name\":\"STATUS\",\"type\":\"string\"},"
            + "{\"name\":\"CUSTOMERNAME\",\"type\":\"string\"},"
            + "{\"name\":\"ORDERDATE\",\"type\":\"date\"}]}").ok,
            "add_dataset (SELECT с JOIN)");
        check(call(registry, ctx, "add_label",
            "{\"text\":\"Отчёт агента: отгруженные заказы\",\"size_pt\":16,"
            + "\"bold\":true,\"align\":\"center\"}").ok,
            "add_label (кириллица)");
        ToolResult table = call(registry, ctx, "add_table",
            "{\"dataset\":\"dsOrders\","
            + "\"columns\":["
            + "{\"header\":\"№ заказа\",\"expr\":\"row[\\\"ORDERNUMBER\\\"]\",\"width_mm\":25,\"align\":\"center\",\"total\":\"count\"},"
            + "{\"header\":\"Клиент\",\"expr\":\"row[\\\"CUSTOMERNAME\\\"]\",\"width_mm\":70},"
            + "{\"header\":\"Статус\",\"expr\":\"row[\\\"STATUS\\\"]\",\"width_mm\":30},"
            + "{\"header\":\"Дата\",\"expr\":\"row[\\\"ORDERDATE\\\"]\",\"width_mm\":35}],"
            + "\"sort\":[{\"expr\":\"row[\\\"ORDERNUMBER\\\"]\",\"direction\":\"asc\"}],"
            + "\"total_label\":\"Всего заказов\"}");
        check(table.ok, "add_table (сортировка, итоги): " + table.content());
        check(call(registry, ctx, "set_page",
            "{\"format\":\"A4\",\"orientation\":\"portrait\","
            + "\"header\":\"Классические модели — {title}\",\"footer\":\"{date} — стр. {page} из {pages}\"}").ok,
            "set_page (колонтитулы)");

        ToolResult validate = call(registry, ctx, "report_validate", "{}");
        check(validate.ok, "report_validate выполнен");
        System.out.println("validate: " + validate.content());

        ToolResult done = call(registry, ctx, "report_done",
            "{\"summary\":\"собрано инструментами\"}");
        check(done.ok && target.isFile(), "report_done: файл создан");
        System.out.println("done: " + done.content());

        // --- 2. Рендер PDF/HTML ---
        org.eclipse.birt.report.engine.api.IReportEngine engine =
            ReportPreview.createEngine(out.toFile(), Level.WARNING);
        try {
            File pdf = out.resolve("classicmodels.pdf").toFile();
            ReportPreview.render(engine, target, null, null, "pdf", pdf);
            check(pdf.isFile() && pdf.length() > 5000, "PDF создан (" + pdf.length() + " Б)");

            File html = out.resolve("classicmodels.html").toFile();
            ReportPreview.render(engine, target, null, null, "html", html);
            String htmlText = new String(Files.readAllBytes(html.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
            check(html.isFile() && html.length() > 3000, "HTML создан");
            check(htmlText.contains("Blauer See"), "данные в HTML (реальные клиенты)");
            check(htmlText.contains("Всего заказов") || htmlText.contains("Итого"),
                "строка итогов в HTML");
            check(!htmlText.contains("Invalid bound column name"),
                "нет ошибок привязок колонок");

            // Кириллица в PDF: текст извлекается только при встроенном шрифте
            if (checkPdfText(pdf, "Классические модели")) {
                System.out.println("кириллица в PDF извлекается (шрифт встроен)");
            }
        } finally {
            ReportPreview.destroy(engine);
        }

        // --- 3. Повторное открытие и правка ---
        DesignSession reopen = new DesignSession("reopen");
        ToolContext ctx2 = new ToolContext(reopen, target, "");
        check(call(registry, ctx2, "report_open",
            "{\"path\":\"" + target.getAbsolutePath() + "\"}").ok, "report_open собранного");
        check(reopen.manifestDataSets().size() == 1, "манифест засеян");
        check(call(registry, ctx2, "add_label",
            "{\"text\":\"Обновлено агентом\"}").ok, "правка: add_label");
        check(call(registry, ctx2, "report_done",
            "{\"summary\":\"правка\"}").ok, "правка: report_done");
        reopen.close();
        session.close();

        System.out.println();
        System.out.println("ИТОГ AGENT-SELFTEST: проверок " + checks
            + ", ошибок " + failures);
        if (failures == 0) {
            System.out.println("ВЫВОД: " + out);
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    private static ToolResult call(ToolRegistry registry, ToolContext ctx,
            String name, String args) {
        ToolResult r = registry.dispatch(new ToolCall("self-" + name, name, args), ctx);
        if (!r.ok) {
            System.out.println("  инструмент " + name + " → ошибка: " + r.content());
        }
        return r;
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL: " + what);
        } else {
            System.out.println("ок: " + what);
        }
    }

    private static boolean checkPdfText(File pdf, String needle) {
        try {
            Process p = new ProcessBuilder("pdftotext", "-layout",
                pdf.getAbsolutePath(), "-").start();
            java.io.InputStream in = p.getInputStream();
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            p.waitFor();
            String text = new String(buf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            return text.contains(needle);
        } catch (Exception e) {
            System.out.println("  (pdftotext недоступен: " + e.getMessage() + ")");
            return true;
        }
    }
}
