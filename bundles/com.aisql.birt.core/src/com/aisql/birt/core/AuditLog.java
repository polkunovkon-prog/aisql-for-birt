/*
 * AI BIRT — журнал аудита (JSONL).
 *
 * Каждая строка файла ~/.ai-birt/ai-birt-audit.jsonl — самостоятельный
 * JSON-объект: время, пользователь, функция, решение, результат, время
 * выполнения, модель, текст ошибки. Журнал ведётся только при включённой
 * настройке audit.enabled и никогда не мешает основной работе: ошибки
 * записи игнорируются.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

public final class AuditLog {

    private AuditLog() {
    }

    /**
     * Дописывает запись в журнал.
     *
     * @param function имя функции (например, «chat»)
     * @param decision решение пользователя/плагина (например, «sent», «error»)
     * @param outcome  итог (OK/ERROR)
     * @param ms       длительность операции, миллисекунд (-1 — неизвестно)
     * @param model    модель, к которой шло обращение (может быть null)
     * @param error    текст ошибки (может быть null)
     * @param detail   подробности (может быть null)
     */
    public static void append(String function, String decision, String outcome,
                              long ms, String model, String error, String detail) {
        if (!BirtSettings.auditEnabled()) {
            return;
        }
        Map<String, Object> entry = Json.obj();
        entry.put("ts", nowIso());
        entry.put("user", System.getProperty("user.name", ""));
        entry.put("app", AiBirtCore.VERSION);
        entry.put("function", function);
        entry.put("decision", decision);
        entry.put("outcome", outcome);
        entry.put("ms", Long.valueOf(ms));
        if (model != null && !model.isEmpty()) {
            entry.put("model", model);
        }
        if (error != null && !error.isEmpty()) {
            entry.put("error", error);
        }
        if (detail != null && !detail.isEmpty()) {
            entry.put("detail", detail);
        }
        String line = Json.write(entry);
        try {
            Path file = BirtSettings.auditFile();
            Files.createDirectories(BirtSettings.configDir());
            OpenOption[] options = {
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE};
            Writer w = new OutputStreamWriter(Files.newOutputStream(file, options), StandardCharsets.UTF_8);
            try {
                w.write(line);
                w.write('\n');
            } finally {
                w.close();
            }
        } catch (IOException ignore) {
            // журнал аудита не должен ломать основную работу плагина
        }
    }

    /** Текущее время в формате ISO-8601 со смещением зоны. */
    public static String nowIso() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").format(new Date());
    }
}
