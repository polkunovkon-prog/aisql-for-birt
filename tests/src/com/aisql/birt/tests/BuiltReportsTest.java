/*
 * AI BIRT — тесты кэша «собранный отчёт → спецификация» (BuiltReports).
 *
 * Кэш позволяет править отчёт по замечанию и после перезапуска Eclipse:
 * спецификация хранится в ~/.ai-birt/reports-built.json и привязывается
 * к файлу по пути, времени изменения и размеру.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.tests;

import com.aisql.birt.core.report.BuiltReports;
import com.aisql.birt.core.spec.ReportSpec;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Тесты кэша спецификаций собранных отчётов. */
public final class BuiltReportsTest {

    private BuiltReportsTest() {
    }

    public static void run() throws Exception {
        T.section("Кэш «отчёт → спецификация» (правка между сессиями)");

        Path dir = Files.createTempDirectory("aibirt-built");
        File report = new File(dir.toFile(), "r.rptdesign");
        Files.write(report.toPath(), "<report/>".getBytes(StandardCharsets.UTF_8));

        String json = "{ \"report\": { \"name\": \"r\", \"title\": \"Кэш\" },"
            + " \"body\": [ { \"type\": \"label\", \"text\": \"Привет\" } ] }";
        ReportSpec spec = ReportSpec.parse(json);

        T.eq(null, BuiltReports.lookup(report), "до запоминания: спецификации нет");

        BuiltReports.remember(report, spec);
        ReportSpec found = BuiltReports.lookup(report);
        T.check(found != null, "спецификация найдена после запоминания");
        if (found != null) {
            T.eq("Кэш", found.title(), "кэш: заголовок сохранился");
            T.eq(1, found.body().size(), "кэш: тело отчёта сохранилось");
        }

        // Изменение файла (например, вручную в конструкторе) — кэш устаревает.
        Files.write(report.toPath(),
            "<report>изменено вручную</report>".getBytes(StandardCharsets.UTF_8));
        T.eq(null, BuiltReports.lookup(report),
            "изменённый вручную файл не считается знакомым");

        // Повторное запоминание и forget.
        BuiltReports.remember(report, spec);
        T.check(BuiltReports.lookup(report) != null, "повторное запоминание работает");
        BuiltReports.forget(report);
        T.eq(null, BuiltReports.lookup(report), "после forget спецификация не находится");

        T.eq(BuiltReports.FILE_NAME, BuiltReports.cacheFile().getName(),
            "файл кэша в каталоге настроек");

        // Отсутствующий файл отчёта не должен ломать кэш.
        BuiltReports.remember(new File(dir.toFile(), "нет.rptdesign"), spec);
        T.eq(null, BuiltReports.lookup(new File(dir.toFile(), "нет.rptdesign")),
            "запоминание отсутствующего файла игнорируется");
    }
}
