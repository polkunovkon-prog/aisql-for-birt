/*
 * AI BIRT — журнал аудита (JSONL) с защитой от подделки.
 *
 * Каждая строка файла ~/.ai-birt/ai-birt-audit.jsonl — самостоятельный
 * JSON-объект: время, хост, пользователь, функция, решение, результат,
 * длительность, модель, текст ошибки — и поля доказуемости:
 *   seq   — монотонный номер записи в файле;
 *   prev  — SHA-256 предыдущей записи (для первой — 64 нуля);
 *   hash  — SHA-256 конкатенации prev и полезной нагрузки записи.
 *
 * Цепочка позволяет обнаружить удаление/правку записей постфактум:
 * {@link #verifyChain()} перечитывает журнал и пересчитывает хэши.
 *
 * Дополнительно события выгружаются в syslog (SIEM) — см. AuditSyslog.
 *
 * Журнал ведётся только при включённой настройке audit.enabled (политика
 * предприятия может запретить выключение) и никогда не мешает основной
 * работе: ошибки записи игнорируются.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core;

import com.aisql.birt.core.security.AuditSyslog;
import com.aisql.birt.core.security.BirtEnterprisePolicy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

public final class AuditLog {

    /** Идентификатор текущей сессии Eclipse (для группировки записей). */
    private static final String SESSION = newSessionId();

    private AuditLog() {
    }

    /** Стабильный идентификатор сессии (для аудита и syslog). */
    public static String sessionId() {
        return SESSION;
    }

    private static String newSessionId() {
        return Long.toHexString(System.currentTimeMillis() & 0xFFFFFFFFFL)
            + "-" + Long.toHexString((long) (Math.random() * 0xFFFFFFFFL));
    }

    // ------------------------------------------------------------------
    // Запись события
    // ------------------------------------------------------------------

    /**
     * Дописывает запись в журнал и выгружает в syslog.
     *
     * @param function имя функции (например, «chat», «tool.add_table»)
     * @param decision решение (например, «sent», «ok», «deny», «error»)
     * @param outcome  итог (OK/ERROR)
     * @param ms       длительность, миллисекунд (-1 — неизвестно)
     * @param model    модель (может быть null)
     * @param error    текст ошибки (может быть null)
     * @param detail   подробности (может быть null; без секретов)
     */
    public static void append(String function, String decision, String outcome,
                              long ms, String model, String error, String detail) {
        append(function, decision, outcome, ms, model, error, detail, true);
    }

    /**
     * То же с управлением syslog-выгрузкой (используется тестами и
     * внутренними записями, чтобы не дублировать события).
     */
    public static void append(String function, String decision, String outcome,
                              long ms, String model, String error, String detail,
                              boolean withSyslog) {
        if (!auditEnabled()) {
            return;
        }
        long seq = nextSeq();
        String ts = nowIso();
        Map<String, Object> payload = Json.obj();
        payload.put("ts", ts);
        payload.put("host", hostName());
        payload.put("user", System.getProperty("user.name", ""));
        payload.put("app", AiBirtCore.VERSION);
        payload.put("session", SESSION);
        payload.put("function", function);
        payload.put("decision", decision);
        payload.put("outcome", outcome);
        payload.put("ms", Long.valueOf(ms));
        if (model != null && !model.isEmpty()) {
            payload.put("model", model);
        }
        if (error != null && !error.isEmpty()) {
            payload.put("error", error);
        }
        if (detail != null && !detail.isEmpty()) {
            payload.put("detail", detail);
        }
        String payloadJson = Json.write(payload);

        String prev = readLastHash();
        String hash = sha256(prev + "|" + seq + "|" + payloadJson);

        Map<String, Object> entry = Json.obj();
        entry.put("seq", Long.valueOf(seq));
        entry.put("prev", prev);
        entry.put("hash", hash);
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            entry.put(e.getKey(), e.getValue());
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
        if (withSyslog) {
            int severity = isError(outcome) ? 4 : 6;
            String sd = "[ai-birt@1 session=\"" + SESSION + "\" function=\""
                + AuditSyslog.sanitize(function, "-") + "\" outcome=\""
                + AuditSyslog.sanitize(outcome, "-") + "\" seq=\"" + seq + "\"]";
            String message = function + ": " + decision + " (" + outcome + ")";
            AuditSyslog.send(severity, "AIBIRT", sd, message);
        }
    }

    private static boolean isError(String outcome) {
        return outcome != null && (outcome.contains("ERROR") || outcome.contains("DENY")
            || outcome.contains("deny") || outcome.equals("отказ"));
    }

    /** Аудит включён (настройка ∩ политика: политика может закрепить). */
    public static boolean auditEnabled() {
        if (BirtEnterprisePolicy.auditLocked()) {
            return true;
        }
        return BirtSettings.auditEnabled();
    }

    // ------------------------------------------------------------------
    // Цепочка хэшей
    // ------------------------------------------------------------------

    /** Результат проверки целостности журнала. */
    public static final class VerifyResult {

        /** Журнал целостен (или пуст). */
        public final boolean ok;
        /** Всего записей. */
        public final long entries;
        /** Первая повреждённая запись (1-based) или 0. */
        public final long firstBroken;
        /** Причина (для чата). */
        public final String reason;

        VerifyResult(boolean ok, long entries, long firstBroken, String reason) {
            this.ok = ok;
            this.entries = entries;
            this.firstBroken = firstBroken;
            this.reason = reason;
        }

        public String describe() {
            if (ok) {
                return "Целостность журнала подтверждена: " + entries + " записей, цепочка хэшей непрерывна.";
            }
            return "Нарушение целостности журнала: " + reason
                + " (первая проблемная запись №" + firstBroken + " из " + entries + ").";
        }
    }

    /**
     * Перечитывает журнал и проверяет цепочку: монотонность seq,
     * связность prev и пересчёт hash. Для больших журналов читает файл
     * потоково.
     */
    public static VerifyResult verifyChain() {
        Path file = BirtSettings.auditFile();
        if (!Files.isRegularFile(file)) {
            return new VerifyResult(true, 0, 0, "");
        }
        String prev = zeros();
        long seq = 0;
        long lineNo = 0;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                lineNo++;
                if (line.trim().isEmpty()) {
                    continue;
                }
                Object parsed;
                try {
                    parsed = Json.parse(line);
                } catch (Json.JsonException e) {
                    return new VerifyResult(false, lineNo, lineNo,
                        "запись не является JSON");
                }
                Map<String, Object> m = Json.asObj(parsed);
                if (m == null) {
                    return new VerifyResult(false, lineNo, lineNo, "запись не объект");
                }
                Long s = null;
                Object seqRaw = m.get("seq");
                if (seqRaw instanceof Number) {
                    s = Long.valueOf(((Number) seqRaw).longValue());
                }
                String p = Json.asStr(m.get("prev"));
                String h = Json.asStr(m.get("hash"));
                if (s == null || p == null || h == null) {
                    return new VerifyResult(false, lineNo, lineNo,
                        "нет полей seq/prev/hash");
                }
                if (s != seq + 1) {
                    return new VerifyResult(false, lineNo, lineNo,
                        "нарушена монотонность seq (ожидался " + (seq + 1) + ", найден " + s + ")");
                }
                if (!prev.equals(p)) {
                    return new VerifyResult(false, lineNo, lineNo,
                        "prev не совпадает с hash предыдущей записи (возможна правка или удаление)");
                }
                Map<String, Object> payload = Json.obj();
                for (Map.Entry<String, Object> e : m.entrySet()) {
                    if (!"seq".equals(e.getKey()) && !"prev".equals(e.getKey())
                        && !"hash".equals(e.getKey())) {
                        payload.put(e.getKey(), e.getValue());
                    }
                }
                String expected = sha256(p + "|" + s + "|" + Json.write(payload));
                if (!expected.equals(h)) {
                    return new VerifyResult(false, lineNo, lineNo,
                        "hash не сходится с пересчётом (запись изменена)");
                }
                seq = s;
                prev = h;
            }
        } catch (IOException e) {
            return new VerifyResult(false, lineNo, lineNo, "ошибка чтения: " + e.getMessage());
        }
        return new VerifyResult(true, seq, 0, "");
    }

    /** Последний hash из журнала (для продолжения цепочки). */
    private static String readLastHash() {
        Path file = BirtSettings.auditFile();
        if (!Files.isRegularFile(file)) {
            return zeros();
        }
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String last = null;
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    last = line;
                }
            }
            if (last == null) {
                return zeros();
            }
            Object parsed = Json.parse(last);
            Map<String, Object> m = Json.asObj(parsed);
            String h = m == null ? null : Json.asStr(m.get("hash"));
            return h != null ? h : zeros();
        } catch (Exception e) {
            // битая строка/чтение: продолжаем цепочку от нулей — verifyChain
            // обнаружит разрыв и сообщит
            return zeros();
        }
    }

    private static long nextSeq() {
        Path file = BirtSettings.auditFile();
        if (!Files.isRegularFile(file)) {
            return 1;
        }
        long count = 0;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    count++;
                }
            }
        } catch (IOException e) {
            return 1;
        }
        return count + 1;
    }

    private static String zeros() {
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < 64; i++) {
            sb.append('0');
        }
        return sb.toString();
    }

    private static String hostName() {
        try {
            String h = java.net.InetAddress.getLocalHost().getHostName();
            if (h != null && !h.isEmpty()) {
                return h;
            }
        } catch (IOException ignore) {
            // имя недоступно
        }
        return "localhost";
    }

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 обязан быть в любой JVM
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }

    /** Текущее время в формате ISO-8601 со смещением зоны. */
    public static String nowIso() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").format(new Date());
    }
}
