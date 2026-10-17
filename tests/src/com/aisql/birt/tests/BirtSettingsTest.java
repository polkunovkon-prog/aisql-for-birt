/*
 * AI BIRT — тесты настроек (файл ~/.ai-birt/ai-birt.properties).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.tests;

import com.aisql.birt.core.BirtSettings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public final class BirtSettingsTest {

    private BirtSettingsTest() {
    }

    static void run() throws Exception {
        T.section("Настройки: значения по умолчанию и файл");

        Path expectedDir = Paths.get(System.getProperty(BirtSettings.CONFIG_DIR_PROPERTY));
        T.eq(expectedDir.toAbsolutePath(), BirtSettings.configDir(), "каталог настроек из системного свойства");

        Properties defaults = BirtSettings.defaults();
        T.eq(8, defaults.size(), "число значений по умолчанию");
        T.eq(BirtSettings.DEFAULT_BASE_URL, defaults.getProperty(BirtSettings.KEY_BASE_URL), "базовый адрес по умолчанию");
        T.eq(BirtSettings.DEFAULT_MODEL, defaults.getProperty(BirtSettings.KEY_MODEL), "модель по умолчанию");
        T.eq("", defaults.getProperty(BirtSettings.KEY_TOKEN), "ключ по умолчанию пуст");
        T.eq("true", defaults.getProperty(BirtSettings.KEY_AUDIT_ENABLED), "аудит включён по умолчанию");

        Path file = BirtSettings.settingsFile();
        T.check(!Files.exists(file), "до первого запуска файла нет");

        BirtSettings.ensureFile();
        T.check(Files.isRegularFile(file), "ensureFile создаёт файл");
        T.eq(BirtSettings.DEFAULT_BASE_URL, BirtSettings.baseUrl(), "адрес из созданного файла");
        T.eq(BirtSettings.DEFAULT_MODEL, BirtSettings.model(), "модель из созданного файла");
        T.eq(BirtSettings.DEFAULT_TEMPERATURE, Double.valueOf(BirtSettings.temperature()), "температура");
        T.eq(BirtSettings.DEFAULT_TIMEOUT_SEC, BirtSettings.timeoutSec(), "таймаут");
        T.eq(0, BirtSettings.maxTokens(), "ограничение ответа");
        T.check(BirtSettings.auditEnabled(), "аудит включён");
        T.check(BirtSettings.systemPrompt().contains("BIRT"), "системная инструкция заполнена");

        // Повторный вызов не перезаписывает существующий файл.
        Properties custom = BirtSettings.defaults();
        custom.setProperty(BirtSettings.KEY_MODEL, "моя-модель");
        BirtSettings.save(custom);
        BirtSettings.ensureFile();
        T.eq("моя-модель", BirtSettings.model(), "ensureFile не перезаписывает файл");

        T.section("Настройки: запись и чтение");
        Properties p = BirtSettings.defaults();
        p.setProperty(BirtSettings.KEY_TOKEN, "секрет-123");
        p.setProperty(BirtSettings.KEY_TEMPERATURE, "0.7");
        p.setProperty(BirtSettings.KEY_MAX_TOKENS, "512");
        p.setProperty(BirtSettings.KEY_SYSTEM_PROMPT, "Строка 1\nСтрока 2\tс табуляцией \\ и слэшем");
        p.setProperty("my.custom.key", "пользовательское");
        BirtSettings.save(p);

        T.eq("секрет-123", BirtSettings.token(), "ключ сохранён");
        T.eq(0.7, Double.valueOf(BirtSettings.temperature()), "температура сохранена");
        T.eq(512, BirtSettings.maxTokens(), "maxTokens сохранён");
        T.eq("Строка 1\nСтрока 2\tс табуляцией \\ и слэшем", BirtSettings.systemPrompt(),
            "многострочная инструкция сохранена без искажений");
        T.eq("пользовательское", BirtSettings.load().getProperty("my.custom.key"),
            "незнакомые ключи не теряются");

        String raw = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        // Комментарии-подсказки могут быть на русском, а строки «ключ=значение»
        // обязаны быть ASCII: не-ASCII значения кодируются escape-последовательностями
        // вида uXXXX, поэтому файл читается при любой локальной кодировке.
        boolean valuesAsciiOnly = true;
        for (String line : raw.split("\n")) {
            if (line.startsWith("#")) {
                continue;
            }
            for (int i = 0; i < line.length(); i++) {
                if (line.charAt(i) > 0x7e) {
                    valuesAsciiOnly = false;
                    break;
                }
            }
        }
        T.check(valuesAsciiOnly, "строки «ключ=значение» — ASCII (кириллица в escape-последовательностях)");
        T.check(raw.contains("\\u0421\\u0442\\u0440\\u043e\\u043a\\u0430"), "кириллица закодирована escape-последовательностями");
        T.check(raw.contains("# llm.baseUrl"), "в файле есть подсказки-комментарии");

        T.section("Настройки: устойчивость к ошибкам");
        Properties broken = BirtSettings.defaults();
        broken.setProperty(BirtSettings.KEY_TEMPERATURE, "не-число");
        broken.setProperty(BirtSettings.KEY_TIMEOUT, "abc");
        broken.setProperty(BirtSettings.KEY_MAX_TOKENS, "-5");
        BirtSettings.save(broken);
        T.eq(BirtSettings.DEFAULT_TEMPERATURE, Double.valueOf(BirtSettings.temperature()),
            "некорректная температура → значение по умолчанию");
        T.eq(BirtSettings.DEFAULT_TIMEOUT_SEC, BirtSettings.timeoutSec(),
            "некорректный таймаут → значение по умолчанию");
        T.eq(0, BirtSettings.maxTokens(), "отрицательный maxTokens → 0");

        Properties flags = BirtSettings.defaults();
        flags.setProperty(BirtSettings.KEY_AUDIT_ENABLED, "нет");
        BirtSettings.save(flags);
        T.check(!BirtSettings.auditEnabled(), "«нет» → аудит выключен");
        flags.setProperty(BirtSettings.KEY_AUDIT_ENABLED, "1");
        BirtSettings.save(flags);
        T.check(BirtSettings.auditEnabled(), "«1» → аудит включён");
        flags.setProperty(BirtSettings.KEY_AUDIT_ENABLED, "false");
        BirtSettings.save(flags);
        T.check(!BirtSettings.auditEnabled(), "«false» → аудит выключен");

        // Возвращаем значения по умолчанию для последующих тестов.
        BirtSettings.save(BirtSettings.defaults());
    }
}
