/*
 * AI BIRT — автотест «спецификация → .rptdesign → PDF/HTML».
 *
 * Собирает отчёт из файла спецификации, рендерит его штатным Report Engine
 * (PDF и HTML) и проверяет журнал движка на ошибки. Используется скриптами
 * приёмки (releng/m2-selftest.sh, headless-запуск Eclipse) и при разработке.
 *
 * Коды возврата: 0 — успех, 1 — ошибка проверки, 2 — неожиданная ошибка.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core.report;

import com.aisql.birt.core.spec.ReportSpec;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.logging.Level;

import org.eclipse.birt.report.engine.api.IReportEngine;

/** Автотест полного цикла: спецификация → отчёт → PDF/HTML. */
public final class ReportSelfTest {

    private ReportSelfTest() {
    }

    public static void main(String[] args) {
        int code;
        try {
            code = run(args, System.out);
        } catch (Throwable t) {
            System.out.println("SELFTEST: ERROR " + t);
            t.printStackTrace(System.out);
            code = 2;
        }
        System.out.flush();
        System.exit(code);
    }

    /**
     * @param args args[0] — файл спецификации JSON; args[1] — каталог вывода
     *             (по умолчанию рядом со спецификацией)
     */
    public static int run(String[] args, PrintStream out) {
        if (args.length < 1) {
            out.println("SELFTEST: ERROR нужен путь к файлу спецификации");
            return 2;
        }
        File specFile = new File(args[0]);
        if (!specFile.isFile()) {
            out.println("SELFTEST: ERROR файл спецификации не найден: " + specFile);
            return 2;
        }
        File outDir = args.length >= 2 ? new File(args[1]) : specFile.getParentFile();
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            out.println("SELFTEST: ERROR не удалось создать каталог " + outDir);
            return 2;
        }
        out.println("SELFTEST: START spec=" + specFile + " out=" + outDir);

        // 1. Спецификация
        ReportSpec spec;
        try {
            spec = ReportSpec.parse(readUtf8(specFile));
        } catch (Throwable t) {
            out.println("SELFTEST: ERROR спецификация не принята: " + t.getMessage());
            return 1;
        }
        out.println("SELFTEST: SPEC OK name=" + spec.name()
            + " params=" + spec.parameters().size()
            + " datasources=" + spec.datasources().size()
            + " datasets=" + spec.datasets().size()
            + " body=" + spec.body().size());

        // 2. Сборка отчёта
        File rpt = new File(outDir, spec.name() + ".rptdesign");
        try {
            ReportBuilder.build(spec, rpt, passwordOverride(spec));
        } catch (Throwable t) {
            out.println("SELFTEST: ERROR сборка отчёта: " + t.getMessage());
            return 1;
        }
        out.println("SELFTEST: BUILD OK file=" + rpt.getName()
            + " bytes=" + rpt.length());

        // 3. Рендер PDF и HTML
        IReportEngine engine = null;
        try {
            String levelName = System.getProperty("aibirt.test.logLevel");
            Level level = levelName == null || levelName.isEmpty()
                ? Level.WARNING : Level.parse(levelName);

            // Движок с конфигурацией шрифтов PDF (кириллица): без неё
            // в PDF нет русского текста — движок берёт невстраиваемый
            // Times-Roman (см. PdfFonts).
            engine = ReportPreview.createEngine(outDir, level);
            if (PdfFonts.customConfigUrl() != null) {
                out.println("SELFTEST: FONTS OK (DejaVu Sans)");
            } else {
                out.println("SELFTEST: WARN ресурс конфигурации шрифтов не найден");
            }

            File pdf = new File(outDir, spec.name() + ".pdf");
            ReportPreview.render(engine, rpt, spec, null, "pdf", pdf);
            out.println("SELFTEST: RENDER PDF OK bytes=" + pdf.length());

            File html = new File(outDir, spec.name() + ".html");
            ReportPreview.render(engine, rpt, spec, null, "html", html);
            out.println("SELFTEST: RENDER HTML OK bytes=" + html.length());
        } catch (Throwable t) {
            out.println("SELFTEST: ERROR рендер: " + describe(t));
            return 1;
        } finally {
            ReportPreview.destroy(engine);
        }

        // 4. Проверка журнала движка на ошибки уровня SEVERE
        int severe = countSevere(outDir);
        out.println("SELFTEST: LOG SEVERE=" + severe);
        if (severe > 0) {
            out.println("SELFTEST: ERROR в журнале движка есть ошибки (см. *.log)");
            return 1;
        }

        out.println("SELFTEST: RESULT OK");
        return 0;
    }

    // ------------------------------------------------------------------

    /** Пароль: из спецификации, иначе системное свойство/переменная окружения. */
    private static String passwordOverride(ReportSpec spec) {
        for (ReportSpec.DataSource d : spec.datasources()) {
            if (d.hasPassword()) {
                return d.password();
            }
        }
        String p = System.getProperty("aibirt.test.dbPassword");
        if (p == null) {
            p = System.getenv("AIBIRT_TEST_DB_PASSWORD");
        }
        return p == null ? "" : p;
    }

    /**
     * Считает строки SEVERE в журналах движка (ReportEngine_*.log) каталога.
     * Прочие *.log (например, лог самого приёмочного скрипта) не считаются.
     */
    private static int countSevere(File dir) {
        int count = 0;
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        for (File f : files) {
            String name = f.getName();
            if (!f.isFile() || !name.startsWith("ReportEngine_") || !name.endsWith(".log")) {
                continue;
            }
            BufferedReader r = null;
            try {
                r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.contains("SEVERE")) {
                        count++;
                    }
                }
            } catch (Exception ignored) {
                // журнал может быть занят — пропускаем
            } finally {
                if (r != null) {
                    try {
                        r.close();
                    } catch (Exception ignored) {
                        // ничего
                    }
                }
            }
        }
        return count;
    }

    private static String readUtf8(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(
            new FileInputStream(file), "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } finally {
            r.close();
        }
        return sb.toString();
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isEmpty()) {
            msg = t.getClass().getName();
        }
        Throwable cause = t.getCause();
        int depth = 0;
        while (cause != null && depth < 3) {
            String cm = cause.getMessage();
            if (cm != null && !cm.isEmpty()) {
                msg += " (" + cm + ")";
            }
            cause = cause.getCause();
            depth++;
        }
        return msg;
    }
}
