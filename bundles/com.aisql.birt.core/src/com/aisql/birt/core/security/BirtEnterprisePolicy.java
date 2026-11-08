/*
 * AI BIRT — административная политика предприятия (enterprise policy).
 *
 * Позволяет ИТ-отделу закрепить ограничения, которые пользователь не может
 * ослабить настройками плагина:
 *   injection           — режим защиты от инъекций: off|flag|strict;
 *   tools.disabled      — список инструментов через запятую или «*» (все);
 *   agent.editExisting  — разрешить ли правку чужих отчётов (true/false);
 *   agent.maxIterations — жёсткий потолок итераций агентного цикла;
 *   pii.masking         — маскирование ПДн (true/false);
 *   audit.syslog.*      — параметры выгрузки журнала в SIEM;
 *   audit.lock          — запрет выключать журнал аудита.
 *
 * Расположение файла: путь из системного свойства -Daibirt.enterprise.policy
 * (приоритет) либо ai-birt-enterprise.properties в каталоге настроек
 * (~/.ai-birt). Права на файл — 0600 у администратора.
 *
 * FAIL-CLOSED: если файл политики задан, но не читается или отсутствует —
 * все механизмы работают в самом строгом режиме: инструменты отключены,
 * правка чужих отчётов запрещена, маскирование и аудит включены. Ошибка
 * чтения попадает в лог Eclipse.
 *
 * Политика только СУЖАЕТ полномочия: с пользовательскими настройками
 * значения пересекаются по более строгому.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core.security;

import com.aisql.birt.core.BirtSettings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Административная политика: fail-closed, только сужает полномочия.
 * Все методы статические — политика читается с кэшем по mtime/размеру.
 */
public final class BirtEnterprisePolicy {

    public static final String POLICY_PROPERTY = "aibirt.enterprise.policy";
    public static final String POLICY_FILE = "ai-birt-enterprise.properties";

    public static final String KEY_INJECTION = "injection";
    public static final String KEY_TOOLS_DISABLED = "tools.disabled";
    public static final String KEY_EDIT_EXISTING = "agent.editExisting";
    public static final String KEY_MAX_ITERATIONS = "agent.maxIterations";
    public static final String KEY_PII_MASKING = "pii.masking";
    public static final String KEY_AUDIT_SYSLOG_ENABLED = "audit.syslog.enabled";
    public static final String KEY_AUDIT_SYSLOG_HOST = "audit.syslog.host";
    public static final String KEY_AUDIT_SYSLOG_PORT = "audit.syslog.port";
    public static final String KEY_AUDIT_SYSLOG_APP = "audit.syslog.app";
    public static final String KEY_AUDIT_LOCK = "audit.lock";

    public static final int DEFAULT_MAX_ITERATIONS = 24;

    private static final Logger LOG = Logger.getLogger(BirtEnterprisePolicy.class.getName());

    /** Снимок политики. Пустой файл = политика есть, но без ограничений. */
    public static final class Snapshot {

        /** Файл политики существует и прочитан. */
        public final boolean active;
        /** Файл задан, но не читается — работаем в самом строгом режиме. */
        public final boolean broken;
        final Properties props;

        Snapshot(boolean active, boolean broken, Properties props) {
            this.active = active;
            this.broken = broken;
            this.props = props;
        }

        public String raw(String key) {
            String v = props.getProperty(key);
            return v == null ? null : v.trim();
        }

        String rawLower(String key) {
            String v = raw(key);
            return v == null ? null : v.toLowerCase(Locale.ROOT);
        }

        public Boolean bool(String key) {
            String v = rawLower(key);
            if (v == null) {
                return null;
            }
            if ("true".equals(v) || "1".equals(v) || "да".equals(v)) {
                return Boolean.TRUE;
            }
            if ("false".equals(v) || "0".equals(v) || "нет".equals(v)) {
                return Boolean.FALSE;
            }
            return null;
        }
    }

    private static final Object LOCK = new Object();
    private static Snapshot cached;
    private static long cachedMtime = -1;
    private static long cachedSize = -1;

    private BirtEnterprisePolicy() {
    }

    /** Текущий снимок политики (кэшируется по mtime/размеру файла). */
    public static Snapshot get() {
        Path file = policyFile();
        synchronized (LOCK) {
            boolean exists = file != null && Files.isRegularFile(file);
            long mtime = 0;
            long size = 0;
            if (exists) {
                try {
                    mtime = Files.getLastModifiedTime(file).toMillis();
                    size = Files.size(file);
                } catch (IOException e) {
                    exists = false;
                }
            }
            if (cached != null && cachedMtime == mtime && cachedSize == size && !changedFlag(cached)) {
                return cached;
            }
            if (file == null) {
                cached = new Snapshot(false, false, new Properties());
                cachedMtime = -1;
                cachedSize = -1;
                return cached;
            }
            boolean forced = file.toString().equals(String.valueOf(System.getProperty(POLICY_PROPERTY)));
            if (!exists) {
                if (forced) {
                    LOG.warning("Политика " + file + " задана, но не найдена: "
                        + "работаем в самом строгом режиме (fail-closed)");
                    cached = new Snapshot(true, true, strictProps());
                } else {
                    cached = new Snapshot(false, false, new Properties());
                }
                cachedMtime = -1;
                cachedSize = -1;
                return cached;
            }
            Properties p = new Properties();
            boolean broken = false;
            try {
                InputStream in = Files.newInputStream(file);
                try {
                    p.load(in);
                } finally {
                    in.close();
                }
            } catch (IOException e) {
                broken = true;
                LOG.log(Level.WARNING, "Не удалось прочитать политику " + file
                    + ": работаем в самом строгом режиме (fail-closed)", e);
                p = strictProps();
            }
            cached = new Snapshot(true, broken, p);
            cachedMtime = mtime;
            cachedSize = size;
            return cached;
        }
    }

    /** Сломанный снимок не кэшируется по mtime — перечитываем каждый раз. */
    private static boolean changedFlag(Snapshot s) {
        return s.broken;
    }

    /** Каталог политики переопределён свойством (для страницы настроек). */
    public static boolean forcedByProperty() {
        String v = System.getProperty(POLICY_PROPERTY);
        return v != null && !v.trim().isEmpty();
    }

    /** Путь к файлу политики: свойство → каталог настроек (null, если каталог не определён). */
    public static Path policyFile() {
        String forced = System.getProperty(POLICY_PROPERTY);
        if (forced != null && !forced.trim().isEmpty()) {
            return Paths.get(forced.trim()).toAbsolutePath();
        }
        return BirtSettings.configDir().resolve(POLICY_FILE);
    }

    /** Свойства «самого строгого режима» для fail-closed. */
    private static Properties strictProps() {
        Properties p = new Properties();
        p.setProperty(KEY_TOOLS_DISABLED, "*");
        p.setProperty(KEY_EDIT_EXISTING, "false");
        p.setProperty(KEY_PII_MASKING, "true");
        p.setProperty(KEY_AUDIT_LOCK, "true");
        return p;
    }

    // ----------------------------------------------------------------------
    //  Интерпретация (политика ∩ пользовательские настройки, по строгому)
    // ----------------------------------------------------------------------

    /** Режим защиты от инъекций из политики или null (не задан). */
    public static String injectionMode() {
        String v = get().rawLower(KEY_INJECTION);
        if (v == null) {
            return null;
        }
        if (PromptInjectionDefense.MODE_STRICT.equals(v)
            || PromptInjectionDefense.MODE_OFF.equals(v)) {
            return v;
        }
        return PromptInjectionDefense.MODE_FLAG;
    }

    /** Отключён ли инструмент политикой (fail-closed: сломанная политика = все отключены). */
    public static boolean toolDisabled(String toolName) {
        Snapshot s = get();
        if (s.broken) {
            return true;
        }
        if (!s.active) {
            return false;
        }
        String list = s.raw(KEY_TOOLS_DISABLED);
        if (list == null || list.isEmpty()) {
            return false;
        }
        if ("*".equals(list.trim())) {
            return true;
        }
        for (String item : list.split(",")) {
            if (item.trim().equals(toolName)) {
                return true;
            }
        }
        return false;
    }

    /** Разрешена ли правка чужих (существующих) отчётов. */
    public static boolean editExistingAllowed() {
        Boolean v = get().bool(KEY_EDIT_EXISTING);
        if (v != null) {
            return v.booleanValue();
        }
        // по умолчанию — разрешено (главный сценарий enterprise);
        // сломанная политика запрещает
        return !get().broken;
    }

    /** Действующий потолок итераций агента (минимум из настройки и политики). */
    public static int maxIterations(int userValue) {
        Snapshot s = get();
        int ceiling = Integer.MAX_VALUE;
        if (s.broken) {
            ceiling = 1;
        } else {
            String raw = s.raw(KEY_MAX_ITERATIONS);
            if (raw != null) {
                try {
                    ceiling = Integer.parseInt(raw);
                } catch (NumberFormatException ignore) {
                    ceiling = 1;
                }
            }
        }
        int v = userValue > 0 ? userValue : DEFAULT_MAX_ITERATIONS;
        return Math.min(v, ceiling);
    }

    /**
     * Включено ли маскирование ПДн. Политика true → включено безусловно;
     * политика false/не задана → решает пользователь (по умолчанию включено).
     */
    public static boolean piiMaskingEnabled() {
        Boolean v = get().bool(KEY_PII_MASKING);
        if (v != null && v.booleanValue()) {
            return true;
        }
        String user = BirtSettings.value("security.piiMasking", "true").trim();
        return "true".equalsIgnoreCase(user) || "1".equals(user);
    }

    /** Запрет выключать аудит. */
    public static boolean auditLocked() {
        Boolean v = get().bool(KEY_AUDIT_LOCK);
        return v != null ? v.booleanValue() : get().broken;
    }

    /** Строка для страницы настроек: описание активной политики. */
    public static String describe() {
        Snapshot s = get();
        if (!s.active) {
            return "не задана";
        }
        if (s.broken) {
            return "задана, но не читается — режим fail-closed (самые строгие ограничения)";
        }
        return "активна: " + policyFile().getFileName();
    }
}
