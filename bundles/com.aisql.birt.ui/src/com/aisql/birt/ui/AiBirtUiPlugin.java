/*
 * AI BIRT — активатор UI-бандла.
 *
 * При старте пишет в журнал Eclipse строку-маркер «AI BIRT UI plugin
 * started, version …» — по ней установочный скрипт (releng/install.sh)
 * проверяет загрузку плагина, и она же помогает в диагностике.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.ui;

import com.aisql.birt.core.AiBirtCore;
import com.aisql.birt.core.BirtSettings;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

/** UI-бандл плагина «ИИ-конструктор отчётов» (BIRT). */
public class AiBirtUiPlugin extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "com.aisql.birt.ui";

    private static volatile AiBirtUiPlugin instance;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
        logInfo(AiBirtCore.START_MARKER + ", version " + version()
            + " (настройки: " + BirtSettings.settingsFile() + ")");
        try {
            // Файл настроек со значениями по умолчанию — при первом запуске,
            // чтобы пользователю было что править вручную.
            BirtSettings.ensureFile();
        } catch (Throwable t) {
            logWarn("Не удалось создать файл настроек " + BirtSettings.settingsFile(), t);
        }
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        instance = null;
        super.stop(context);
    }

    public static AiBirtUiPlugin getInstance() {
        return instance;
    }

    /** Версия бандла. */
    public static String version() {
        try {
            return Platform.getBundle(PLUGIN_ID).getVersion().toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ------------------------------------------------------------------
    // Журнал платформы (workspace/.metadata/.log)
    // ------------------------------------------------------------------

    public static void logInfo(String message) {
        AiBirtUiPlugin plugin = instance;
        if (plugin != null) {
            plugin.getLog().log(new Status(IStatus.INFO, PLUGIN_ID, message));
        } else {
            // Вызов вне OSGi (например, из тестов) — пишем в stdout.
            System.out.println(PLUGIN_ID + ": " + message);
        }
    }

    public static void logWarn(String message, Throwable error) {
        AiBirtUiPlugin plugin = instance;
        if (plugin != null) {
            plugin.getLog().log(new Status(IStatus.WARNING, PLUGIN_ID, message, error));
        } else {
            System.out.println(PLUGIN_ID + ": " + message
                + (error == null ? "" : " — " + error));
        }
    }
}
