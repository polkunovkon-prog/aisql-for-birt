/*
 * AI BIRT — предпросмотр отчёта: рендер готового .rptdesign без Eclipse.
 *
 * Используется кнопкой «Просмотр…» в панели чата (PDF открывается
 * системным просмотрщиком) и автотестом ReportSelfTest («приёмка M2»).
 * Движок создаётся штатным ReportEngine; конфигурация шрифтов PDF
 * передаётся через EngineConfig.setFontConfig (см. PdfFonts) — без неё
 * в PDF теряется кириллица.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core.report;

import com.aisql.birt.core.spec.ReportSpec;

import java.io.File;
import java.util.Map;
import java.util.logging.Level;

import org.eclipse.birt.core.framework.Platform;
import org.eclipse.birt.core.framework.PlatformConfig;
import org.eclipse.birt.report.engine.api.EngineConfig;
import org.eclipse.birt.report.engine.api.HTMLRenderOption;
import org.eclipse.birt.report.engine.api.IReportEngine;
import org.eclipse.birt.report.engine.api.IReportRunnable;
import org.eclipse.birt.report.engine.api.IRenderOption;
import org.eclipse.birt.report.engine.api.IRunAndRenderTask;
import org.eclipse.birt.report.engine.api.PDFRenderOption;
import org.eclipse.birt.report.engine.api.ReportEngine;

/** Рендер отчёта в PDF/HTML штатным движком BIRT (предпросмотр и автотест). */
public final class ReportPreview {

    private ReportPreview() {
    }

    /**
     * Создаёт движок отчётов с конфигурацией шрифтов PDF.
     *
     * @param logDir каталог журналов движка (может быть null — журнал без файла)
     * @param level  уровень журнала (null — WARNING)
     */
    public static IReportEngine createEngine(File logDir, Level level) {
        try {
            Platform.startup(new PlatformConfig());
        } catch (Throwable ignored) {
            // платформа уже запущена (внутри Eclipse) — это нормально
        }
        EngineConfig config = new EngineConfig();
        if (logDir != null) {
            config.setLogConfig(logDir.getAbsolutePath(),
                level == null ? Level.WARNING : level);
        }
        PdfFonts.applyTo(config);
        return new ReportEngine(config);
    }

    /** Останавливает движок (ошибки не считаются критичными). */
    public static void destroy(IReportEngine engine) {
        if (engine != null) {
            try {
                engine.destroy();
            } catch (Throwable ignored) {
                // остановка движка не критична
            }
        }
    }

    /**
     * Выполняет отчёт и сохраняет результат.
     *
     * @param engine    движок из {@link #createEngine(File, Level)}
     * @param designFile готовый файл .rptdesign
     * @param spec      спецификация (значения параметров по умолчанию)
     * @param overrides значения параметров от пользователя (могут быть null)
     * @param format    «pdf» или «html»
     * @param out       файл результата
     */
    public static void render(IReportEngine engine, File designFile, ReportSpec spec,
            Map<String, String> overrides, String format, File out)
            throws ReportBuilder.ReportBuildException {
        if (engine == null || designFile == null || out == null) {
            throw new ReportBuilder.ReportBuildException("предпросмотр: не заданы движок или файл");
        }
        IRunAndRenderTask task = null;
        try {
            IReportRunnable design = engine.openReportDesign(designFile.getAbsolutePath());
            task = engine.createRunAndRenderTask(design);
            if (spec != null) {
                for (ReportSpec.Parameter p : spec.parameters()) {
                    String value = overrides == null ? null : overrides.get(p.name());
                    if (value == null) {
                        value = p.def();
                    }
                    if (value != null) {
                        task.setParameterValue(p.name(), convert(p.type(), value));
                    }
                }
            }
            if (!task.validateParameters()) {
                throw new ReportBuilder.ReportBuildException(
                    "движок отклонил параметры отчёта (validateParameters=false)");
            }
            IRenderOption options;
            if ("html".equals(format)) {
                HTMLRenderOption html = new HTMLRenderOption();
                html.setEmbeddable(false);
                options = html;
            } else {
                options = new PDFRenderOption();
            }
            options.setOutputFileName(out.getAbsolutePath());
            options.setOutputFormat(format);
            task.setRenderOption(options);
            task.run();
        } catch (ReportBuilder.ReportBuildException e) {
            throw e;
        } catch (Throwable t) {
            throw new ReportBuilder.ReportBuildException(
                "не удалось выполнить отчёт: " + describe(t), t);
        } finally {
            if (task != null) {
                try {
                    task.close();
                } catch (Throwable ignored) {
                    // закрытие задачи не критично
                }
            }
        }
    }

    /**
     * Приводит текстовое значение параметра к типу отчёта: движок BIRT
     * не принимает строку там, где объявлено число (validateParameters
     * возвращает false). Текст с неверным числом отдаём как есть —
     * движок сообщит об ошибке понятнее.
     */
    private static Object convert(String type, String value) {
        if (value == null) {
            return null;
        }
        try {
            if ("integer".equals(type)) {
                return Integer.valueOf(value.trim());
            }
            if ("decimal".equals(type)) {
                return Double.valueOf(value.trim().replace(',', '.'));
            }
            if ("boolean".equals(type)) {
                return Boolean.valueOf(value.trim());
            }
        } catch (NumberFormatException e) {
            return value;
        }
        return value;
    }

    private static String describe(Throwable t) {        String msg = t.getMessage();
        if (msg == null || msg.isEmpty()) {
            msg = t.getClass().getName();
        }
        Throwable cause = t.getCause();
        int depth = 0;
        while (cause != null && depth < 3) {
            String cm = cause.getMessage();
            if (cm != null && !cm.isEmpty() && msg.indexOf(cm) < 0) {
                msg += " (" + cm + ")";
            }
            cause = cause.getCause();
            depth++;
        }
        return msg;
    }
}
