/*
 * AI BIRT — тесты журнала аудита: цепочка хэшей, seq, syslog.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.tests;

import com.aisql.birt.core.AuditLog;
import com.aisql.birt.core.BirtSettings;
import com.aisql.birt.core.Json;
import com.aisql.birt.core.security.AuditSyslog;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public final class AuditChainTest {

    public static void run() throws Exception {
        T.section("Журнал аудита: цепочка хэшей и syslog");

        // Чистый каталог аудита
        Files.deleteIfExists(BirtSettings.auditFile());
        Properties p = new Properties();
        p.setProperty(BirtSettings.KEY_AUDIT_ENABLED, "true");
        BirtSettings.save(p);

        // --- Поля записи ---
        AuditLog.append("test", "ok", "OK", 42, "model-x", null, "детали");
        List<String> lines = Files.readAllLines(BirtSettings.auditFile(),
            java.nio.charset.StandardCharsets.UTF_8);
        T.check(lines.size() == 1, "первая запись создана");
        Map<String, Object> e1 = Json.asObj(Json.parse(lines.get(0)));
        Object seq1 = e1.get("seq");
        T.check(seq1 instanceof Number && ((Number) seq1).longValue() == 1,
            "seq=1 у первой записи");
        String prev1 = Json.asStr(e1.get("prev"));
        T.check(prev1 != null && prev1.matches("0{64}"),
            "prev первой записи — 64 нуля");
        String hash1 = Json.asStr(e1.get("hash"));
        T.check(hash1 != null && hash1.matches("[0-9a-f]{64}"), "hash — SHA-256 hex");
        T.check(Json.asStr(e1.get("host")) != null, "поле host заполнено");
        T.check(Json.asStr(e1.get("session")) != null, "поле session заполнено");
        T.check(Json.asStr(e1.get("app")).contains("1.0.0"), "поле app с версией");

        // --- Вторая запись ссылается на первую ---
        AuditLog.append("test", "ok", "OK", 7, "model-x", "ошибка-текст", null);
        lines = Files.readAllLines(BirtSettings.auditFile(),
            java.nio.charset.StandardCharsets.UTF_8);
        Map<String, Object> e2 = Json.asObj(Json.parse(lines.get(1)));
        T.check(Json.asStr(e2.get("prev")).equals(hash1),
            "prev второй записи = hash первой");

        // --- Целостность: журнал корректен ---
        AuditLog.VerifyResult v = AuditLog.verifyChain();
        T.check(v.ok, "verifyChain: журнал из 2 записей целостен: " + v.describe());
        T.check(v.entries == 2, "verifyChain: посчитаны 2 записи");

        // --- Порча записи обнаруживается ---
        lines.set(1, lines.get(1).replace("\"OK\"", "\"OK\""));
        // заменим значение решения — hash перестанет сходиться
        String broken = lines.get(1).replace("\"decision\":\"ok\"", "\"decision\":\"HACKED\"");
        lines.set(1, broken);
        Files.write(BirtSettings.auditFile(), lines,
            java.nio.charset.StandardCharsets.UTF_8);
        v = AuditLog.verifyChain();
        T.check(!v.ok, "verifyChain: правка записи обнаружена");
        T.check(v.firstBroken == 2, "повреждена запись №2");

        // --- Удаление записи обнаруживается (seq перескакивает) ---
        Files.deleteIfExists(BirtSettings.auditFile());
        for (int i = 0; i < 3; i++) {
            AuditLog.append("test", "ok", "OK", i, null, null, "i=" + i);
        }
        lines = Files.readAllLines(BirtSettings.auditFile(),
            java.nio.charset.StandardCharsets.UTF_8);
        lines.remove(1); // удаляем вторую
        Files.write(BirtSettings.auditFile(), lines,
            java.nio.charset.StandardCharsets.UTF_8);
        v = AuditLog.verifyChain();
        T.check(!v.ok, "verifyChain: удаление записи обнаружено");

        // --- Аудит выключить нельзя, если политика закрепила ---
        Files.deleteIfExists(BirtSettings.auditFile());
        p.setProperty(BirtSettings.KEY_AUDIT_ENABLED, "false");
        BirtSettings.save(p);
        AuditLog.append("test", "ok", "OK", 1, null, null, null);
        T.check(!Files.exists(BirtSettings.auditFile()),
            "аудит выключен настройкой: записи не ведутся");
        Files.deleteIfExists(BirtSettings.auditFile());

        // --- Формат syslog ---
        String msg = AuditSyslog.format(6, "AIBIRT",
            "[ai-birt@1 test=\"1\"]", "тестовое сообщение");
        T.check(msg.startsWith("<134>1 "), "syslog: PRI=134 (local0.info)");
        T.check(msg.contains(" ai-birt "), "syslog: app-name по умолчанию");
        T.check(msg.contains("[ai-birt@1 test=\"1\"]"), "syslog: structured-data на месте");
        T.check(msg.trim().endsWith("тестовое сообщение"), "syslog: MSG в конце");

        String sanitized = AuditSyslog.sanitize("плохое\"имя]с символами и пробелами", "-");
        T.check(!sanitized.contains("\"") && !sanitized.contains("]"),
            "syslog: sanitize убирает опасные символы");

        // --- Реальная отправка UDP на локальный приёмник ---
        DatagramSocket receiver = new DatagramSocket(0);
        try {
            int port = receiver.getLocalPort();
            receiver.setSoTimeout(5000);
            p.setProperty("audit.syslog.enabled", "true");
            p.setProperty("audit.syslog.host", "127.0.0.1");
            p.setProperty("audit.syslog.port", String.valueOf(port));
            p.setProperty(BirtSettings.KEY_AUDIT_ENABLED, "true");
            BirtSettings.save(p);

            Files.deleteIfExists(BirtSettings.auditFile());
            AuditLog.append("test", "ok", "ERROR", 5, null, "ошибка", "syslog-тест");

            byte[] buf = new byte[4096];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            receiver.receive(packet);
            String received = new String(packet.getData(), 0, packet.getLength(),
                java.nio.charset.StandardCharsets.UTF_8);
            T.check(received.contains("AIBIRT"), "syslog UDP: сообщение дошло на приёмник");
            T.check(received.contains("ERROR"), "syslog UDP: исход ERROR в сообщении");
        } catch (java.net.SocketTimeoutException e) {
            T.check(false, "syslog UDP: сообщение не получено за 5 с");
        } finally {
            receiver.close();
        }

        // Выключаем syslog и чистим
        p.setProperty("audit.syslog.enabled", "false");
        p.setProperty(BirtSettings.KEY_AUDIT_ENABLED, "true");
        BirtSettings.save(p);
        Files.deleteIfExists(BirtSettings.auditFile());
    }

    private AuditChainTest() {
    }
}
