/*
 * AI BIRT — headless-приложение Eclipse для самопроверки M2.
 *
 * Запускается внутри Eclipse с BIRT (OSGi-путь):
 *   eclipse -application com.aisql.birt.ui.aibirtSelftest -nosplash -consoleLog \
 *           /путь/к/спецификации.json /каталог/вывода
 *
 * Проверяет весь конвейер «спецификация → .rptdesign → PDF/HTML» штатным
 * движком BIRT и завершает Eclipse с кодом 0 (успех) или 1 (ошибка).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.ui;

import com.aisql.birt.core.report.ReportSelfTest;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;

/** Headless-приложение самопроверки (спецификация → отчёт → рендер). */
public class AiBirtSelftestApplication implements IApplication {

    @Override
    public Object start(IApplicationContext context) throws Exception {
        Object raw = context.getArguments().get(IApplicationContext.APPLICATION_ARGS);
        String[] args = raw instanceof String[] ? (String[]) raw : new String[0];
        System.out.println("SELFTEST(ECLIPSE): старт, аргументов: " + args.length);
        int code;
        try {
            code = ReportSelfTest.run(args, System.out);
        } catch (Throwable t) {
            System.out.println("SELFTEST(ECLIPSE): ERROR " + t);
            t.printStackTrace(System.out);
            code = 2;
        }
        System.out.println("SELFTEST(ECLIPSE): код выхода " + code);
        // Headless-приёмка: движок диаграмм оставляет не-daemon потоки,
        // из-за которых Eclipse не завершается сам. Это тестовое приложение,
        // поэтому завершаем процесс явно (отчёты и журналы уже закрыты).
        System.out.flush();
        System.exit(code);
        return Integer.valueOf(code);
    }

    @Override
    public void stop() {
        // Действий при остановке не требуется.
    }
}
