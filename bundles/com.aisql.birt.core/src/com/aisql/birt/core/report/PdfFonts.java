/*
 * AI BIRT — шрифты PDF-эмиттера BIRT.
 *
 * Зачем: без явной конфигурации PDF-эмиттер использует невстраиваемые
 * базовые PDF-шрифты (Times/Helvetica), в которых нет кириллицы, —
 * русский текст в PDF пропадает (проверено на BIRT 4.7).
 *
 * Конфигурация fontsConfig-ai-birt.xml (ресурс рядом с этим классом)
 * указывает движку каталоги системных TrueType-шрифтов с кириллицей
 * (DejaVu, Liberation); движок встраивает их в PDF (Identity-H).
 *
 * Как подключается: штатным API EngineConfig.setFontConfig(URL) —
 * см. applyTo(EngineConfig). Важно: настройка хранится статически, но
 * каждый создаваемый ReportEngine переустанавливает её из своего
 * EngineConfig (при пустом значении — сбрасывает). Поэтому конфигурацию
 * нужно передавать каждому движку явно. Просмотрщик отчётов Eclipse
 * (Run → View Report) создаёт движок сам; для него шрифты должны лежать
 * в <java-home>/lib/fonts — их движок регистрирует автоматически
 * (см. docs/TESTING.md, «Известные особенности»).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core.report;

import java.net.URL;

import org.eclipse.birt.report.engine.api.EngineConfig;

/** Подключение конфигурации шрифтов PDF-движка (кириллица в PDF). */
public final class PdfFonts {

    private PdfFonts() {
    }

    /** URL встроенной конфигурации шрифтов (ресурс рядом с этим классом). */
    public static URL customConfigUrl() {
        return PdfFonts.class.getResource("fontsConfig-ai-birt.xml");
    }

    /**
     * Передаёт конфигурацию шрифтов движку отчётов
     * ({@code EngineConfig.setFontConfig}).
     *
     * @return {@code true}, если конфигурация подключена
     */
    public static boolean applyTo(EngineConfig config) {
        URL url = customConfigUrl();
        if (url == null || config == null) {
            return false;
        }
        config.setFontConfig(url);
        return true;
    }
}
