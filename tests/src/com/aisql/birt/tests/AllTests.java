/*
 * AI BIRT — запуск всех офлайн-тестов.
 *
 * Каталог настроек подменяется системным свойством aibirt.config.dir,
 * чтобы тесты не трогали рабочие ~/.ai-birt.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.tests;

import com.aisql.birt.core.AiBirtCore;
import com.aisql.birt.core.BirtSettings;

import java.nio.file.Files;
import java.nio.file.Path;

public final class AllTests {

    private AllTests() {
    }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("aibirt-tests");
        Path config = temp.resolve("config");
        System.setProperty(BirtSettings.CONFIG_DIR_PROPERTY, config.toString());
        System.out.println("AI BIRT — офлайн-тесты, версия " + AiBirtCore.VERSION);
        System.out.println("Каталог настроек тестов: " + config);
        System.out.println("JVM: " + System.getProperty("java.version")
            + " (" + System.getProperty("java.vendor") + ")");
        System.out.println();

        JsonTest.run();
        BirtSettingsTest.run();
        ChatHistoryTest.run();
        AuditLogTest.run();
        LlmClientTest.run();
        ReportSpecTest.run();
        ReportBuilderTest.run();

        int code = T.summary();
        System.out.println(code == 0 ? "ИТОГ: все проверки пройдены." : "ИТОГ: есть ошибки.");
        System.exit(code);
    }
}
