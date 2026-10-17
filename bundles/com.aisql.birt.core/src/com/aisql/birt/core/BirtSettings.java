/*
 * AI BIRT — настройки плагина.
 *
 * Файл настроек: ~/.ai-birt/ai-birt.properties (каталог можно переопределить
 * системным свойством "aibirt.config.dir" — используется тестами).
 *
 * Файл написан так, чтобы его можно было править любым редактором: не-ASCII
 * символы сохраняются unicode-escape-последовательностями (uXXXX), поэтому
 * кодировка файла на диске не важна. Настройки перечитываются при каждом
 * обращении, если файл изменился (защита по времени изменения и размеру).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

public final class BirtSettings {

    /** Системное свойство для подмены каталога настроек (тесты). */
    public static final String CONFIG_DIR_PROPERTY = "aibirt.config.dir";

    public static final String SETTINGS_FILE = "ai-birt.properties";
    public static final String AUDIT_FILE = "ai-birt-audit.jsonl";

    public static final String KEY_BASE_URL = "llm.baseUrl";
    public static final String KEY_MODEL = "llm.model";
    public static final String KEY_TOKEN = "llm.token";
    public static final String KEY_TEMPERATURE = "llm.temperature";
    public static final String KEY_TIMEOUT = "llm.timeoutSec";
    public static final String KEY_MAX_TOKENS = "llm.maxTokens";
    public static final String KEY_SYSTEM_PROMPT = "chat.systemPrompt";
    public static final String KEY_AUDIT_ENABLED = "audit.enabled";

    public static final String DEFAULT_BASE_URL = "http://192.168.100.152:8888/v1";
    public static final String DEFAULT_MODEL = "ornith-ai/Ornith-1.5-35B-A3B-GGUF";
    public static final String DEFAULT_TOKEN = "";
    public static final double DEFAULT_TEMPERATURE = 0.3;
    public static final int DEFAULT_TIMEOUT_SEC = 900;
    public static final int DEFAULT_MAX_TOKENS = 0;
    public static final boolean DEFAULT_AUDIT_ENABLED = true;

    public static final String DEFAULT_SYSTEM_PROMPT =
        "Ты — ИИ-помощник по проектированию отчётов BIRT (Eclipse BIRT 4.7). "
        + "Отвечай кратко и по делу, на русском языке. Если вопрос про элементы "
        + "отчёта (источники данных, наборы данных, параметры, таблицы, графики), "
        + "отвечай так, как это делается в штатном конструкторе Eclipse BIRT.";

    private static final List<String> KEY_ORDER = Arrays.asList(
        KEY_BASE_URL, KEY_MODEL, KEY_TOKEN, KEY_TEMPERATURE,
        KEY_TIMEOUT, KEY_MAX_TOKENS, KEY_AUDIT_ENABLED, KEY_SYSTEM_PROMPT);

    private static final Object LOCK = new Object();
    private static Properties cache;
    private static long cacheMtime = -1;
    private static long cacheSize = -1;

    private BirtSettings() {
    }

    /** Каталог настроек: ~/.ai-birt (или значение свойства aibirt.config.dir). */
    public static Path configDir() {
        String override = System.getProperty(CONFIG_DIR_PROPERTY);
        if (override != null && !override.trim().isEmpty()) {
            return Paths.get(override.trim()).toAbsolutePath();
        }
        String home = System.getProperty("user.home", ".");
        return Paths.get(home, ".ai-birt").toAbsolutePath();
    }

    public static Path settingsFile() {
        return configDir().resolve(SETTINGS_FILE);
    }

    public static Path auditFile() {
        return configDir().resolve(AUDIT_FILE);
    }

    /** Значения по умолчанию. */
    public static Properties defaults() {
        Properties p = new Properties();
        p.setProperty(KEY_BASE_URL, DEFAULT_BASE_URL);
        p.setProperty(KEY_MODEL, DEFAULT_MODEL);
        p.setProperty(KEY_TOKEN, DEFAULT_TOKEN);
        p.setProperty(KEY_TEMPERATURE, String.valueOf(DEFAULT_TEMPERATURE));
        p.setProperty(KEY_TIMEOUT, String.valueOf(DEFAULT_TIMEOUT_SEC));
        p.setProperty(KEY_MAX_TOKENS, String.valueOf(DEFAULT_MAX_TOKENS));
        p.setProperty(KEY_AUDIT_ENABLED, String.valueOf(DEFAULT_AUDIT_ENABLED));
        p.setProperty(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT);
        return p;
    }

    /**
     * Текущие настройки из файла (без значений по умолчанию для отсутствующих
     * ключей). Кэшируется: файл перечитывается только при изменениях.
     * Возвращённый объект нельзя изменять — используйте {@link #save(Properties)}.
     */
    public static Properties load() {
        Path file = settingsFile();
        synchronized (LOCK) {
            try {
                if (!Files.isRegularFile(file)) {
                    cache = new Properties();
                    cacheMtime = -1;
                    cacheSize = -1;
                    return cache;
                }
                long mtime = Files.getLastModifiedTime(file).toMillis();
                long size = Files.size(file);
                if (cache != null && mtime == cacheMtime && size == cacheSize) {
                    return cache;
                }
                Properties p = new Properties();
                InputStream in = Files.newInputStream(file);
                try {
                    p.load(in);
                } finally {
                    in.close();
                }
                cache = p;
                cacheMtime = mtime;
                cacheSize = size;
                return cache;
            } catch (IOException e) {
                Properties p = new Properties();
                cache = p;
                cacheMtime = -1;
                cacheSize = -1;
                return p;
            }
        }
    }

    // ------------------------------------------------------------------
    // Типизированные значения (значение из файла, иначе — по умолчанию)
    // ------------------------------------------------------------------

    public static String baseUrl() {
        return load().getProperty(KEY_BASE_URL, DEFAULT_BASE_URL).trim();
    }

    public static String model() {
        return load().getProperty(KEY_MODEL, DEFAULT_MODEL).trim();
    }

    public static String token() {
        return load().getProperty(KEY_TOKEN, DEFAULT_TOKEN).trim();
    }

    public static double temperature() {
        return parseDouble(load().getProperty(KEY_TEMPERATURE), DEFAULT_TEMPERATURE);
    }

    public static int timeoutSec() {
        return parseInt(load().getProperty(KEY_TIMEOUT), DEFAULT_TIMEOUT_SEC);
    }

    /** 0 — не передавать ограничение длины ответа. */
    public static int maxTokens() {
        int v = parseInt(load().getProperty(KEY_MAX_TOKENS), DEFAULT_MAX_TOKENS);
        return v < 0 ? 0 : v;
    }

    public static String systemPrompt() {
        return load().getProperty(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT);
    }

    public static boolean auditEnabled() {
        String v = load().getProperty(KEY_AUDIT_ENABLED);
        if (v == null) {
            return DEFAULT_AUDIT_ENABLED;
        }
        return "true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim()) || "да".equalsIgnoreCase(v.trim());
    }

    private static double parseDouble(String v, double def) {
        if (v == null) {
            return def;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int parseInt(String v, int def) {
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ------------------------------------------------------------------
    // Запись
    // ------------------------------------------------------------------

    /** Создаёт файл настроек со значениями по умолчанию, если его нет. */
    public static void ensureFile() throws IOException {
        Path file = settingsFile();
        if (Files.exists(file)) {
            return;
        }
        save(defaults());
    }

    /** Сохраняет настройки (файл перезаписывается целиком). */
    public static void save(Properties p) throws IOException {
        Path file = settingsFile();
        Files.createDirectories(configDir());

        StringBuilder sb = new StringBuilder();
        sb.append("# Настройки плагина «ИИ-конструктор отчётов» (BIRT 4.7)\n");
        sb.append("# Изменения подхватываются на лету — перезапуск Eclipse не нужен.\n");
        sb.append("#\n");
        sb.append("# llm.baseUrl       — адрес OpenAI-совместимого сервера (оканчивается на /v1)\n");
        sb.append("# llm.model         — идентификатор модели\n");
        sb.append("# llm.token         — ключ доступа; пусто — запросы без заголовка Authorization\n");
        sb.append("# llm.temperature   — температура генерации (0…2)\n");
        sb.append("# llm.timeoutSec    — таймаут одного запроса, секунд\n");
        sb.append("# llm.maxTokens     — ограничение длины ответа, 0 — не передавать\n");
        sb.append("# chat.systemPrompt — системная инструкция чата\n");
        sb.append("# audit.enabled     — вести журнал аудита в ai-birt-audit.jsonl (true/false)\n");
        sb.append("\n");

        Set<String> ordered = new HashSet<String>(KEY_ORDER);
        for (String key : KEY_ORDER) {
            String value = p.getProperty(key);
            if (value != null) {
                appendLine(sb, key, value);
            }
        }
        // Пользовательские/будущие ключи не теряем, пишем после основных.
        for (String key : new TreeSet<String>(p.stringPropertyNames())) {
            if (!ordered.contains(key)) {
                appendLine(sb, key, p.getProperty(key, ""));
            }
        }

        Writer w = new OutputStreamWriter(Files.newOutputStream(file), StandardCharsets.UTF_8);
        try {
            w.write(sb.toString());
        } finally {
            w.close();
        }
        synchronized (LOCK) {
            cache = null;
            cacheMtime = -1;
            cacheSize = -1;
        }
    }

    private static void appendLine(StringBuilder sb, String key, String value) {
        sb.append(key).append('=').append(escapeValue(value)).append('\n');
    }

    /** Экранирование в формате java.util.Properties (файл остаётся ASCII-совместимым). */
    private static String escapeValue(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20 || c > 0x7e) {
                        sb.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int p = hex.length(); p < 4; p++) {
                            sb.append('0');
                        }
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
