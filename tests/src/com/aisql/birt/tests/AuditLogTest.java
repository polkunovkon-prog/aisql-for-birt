/*
 * AI BIRT — тесты журнала аудита (JSONL).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.tests;

import com.aisql.birt.core.AiBirtCore;
import com.aisql.birt.core.AuditLog;
import com.aisql.birt.core.BirtSettings;
import com.aisql.birt.core.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class AuditLogTest {

    private AuditLogTest() {
    }

    static void run() throws Exception {
        T.section("Журнал аудита");

        // Настройки по умолчанию: аудит включён.
        BirtSettings.save(BirtSettings.defaults());
        Path file = BirtSettings.auditFile();
        if (Files.exists(file)) {
            Files.delete(file);
        }

        AuditLog.append("chat", "answered", "OK", 123, "model-x", null, "вопрос=10 симв.");
        T.check(Files.isRegularFile(file), "запись создаёт файл журнала");

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        T.eq(1, lines.size(), "одна строка после одной записи");

        Object entry = Json.parse(lines.get(0));
        T.eq("chat", Json.asStr(Json.at(entry, "function")), "поле function");
        T.eq("answered", Json.asStr(Json.at(entry, "decision")), "поле decision");
        T.eq("OK", Json.asStr(Json.at(entry, "outcome")), "поле outcome");
        T.eq(123L, Json.asLong(Json.at(entry, "ms"), -1), "поле ms");
        T.eq("model-x", Json.asStr(Json.at(entry, "model")), "поле model");
        T.eq("вопрос=10 симв.", Json.asStr(Json.at(entry, "detail")), "поле detail");
        T.eq(AiBirtCore.VERSION, Json.asStr(Json.at(entry, "app")), "поле app");
        T.eq(System.getProperty("user.name"), Json.asStr(Json.at(entry, "user")), "поле user");
        T.check(Json.asStr(Json.at(entry, "ts")) != null
            && Json.asStr(Json.at(entry, "ts")).contains("T"), "поле ts в формате ISO-8601");
        T.eq(null, Json.asStr(Json.at(entry, "error")), "ошибки в успешной записи нет");

        AuditLog.append("chat", "error", "ERROR", 7, "model-x", "Сервер вернул ошибку HTTP 401", null);
        lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        T.eq(2, lines.size(), "журнал дописывается построчно");
        Object errorEntry = Json.parse(lines.get(1));
        T.eq("Сервер вернул ошибку HTTP 401", Json.asStr(Json.at(errorEntry, "error")), "текст ошибки сохранён");

        // Отключённый аудит не пишет ничего.
        java.util.Properties off = BirtSettings.defaults();
        off.setProperty(BirtSettings.KEY_AUDIT_ENABLED, "false");
        BirtSettings.save(off);
        AuditLog.append("chat", "answered", "OK", 5, "model-x", null, null);
        lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        T.eq(2, lines.size(), "при выключенном аудите новых строк нет");

        // Возвращаем аудит для остальных тестов.
        BirtSettings.save(BirtSettings.defaults());
        T.check(BirtSettings.auditEnabled(), "аудит снова включён");
    }
}
