/*
 * AI BIRT — тесты enterprise-политики.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.tests;

import com.aisql.birt.core.BirtSettings;
import com.aisql.birt.core.security.BirtEnterprisePolicy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class EnterprisePolicyTest {

    public static void run() throws Exception {
        T.section("Enterprise-политика (fail-closed)");

        Path config = BirtSettings.configDir();
        Path policy = config.resolve(BirtEnterprisePolicy.POLICY_FILE);

        // --- Без политики: всё разрешено ---
        Files.deleteIfExists(policy);
        BirtEnterprisePolicy.Snapshot s = BirtEnterprisePolicy.get();
        T.check(!s.active, "без файла политики: не активна");
        T.check(!BirtEnterprisePolicy.toolDisabled("add_table"),
            "без политики инструменты доступны");
        T.check(BirtEnterprisePolicy.editExistingAllowed(),
            "без политики правка чужих разрешена");
        T.check(!BirtEnterprisePolicy.auditLocked(), "без политики аудит не закреплён");

        // --- Политика с ограничениями ---
        StringBuilder body = new StringBuilder();
        body.append("# тестовая политика\n");
        body.append("injection=strict\n");
        body.append("tools.disabled=remove_element, db_schema\n");
        body.append("agent.editExisting=false\n");
        body.append("agent.maxIterations=5\n");
        body.append("pii.masking=true\n");
        body.append("audit.lock=true\n");
        Files.write(policy, body.toString().getBytes(StandardCharsets.UTF_8));

        T.check(BirtEnterprisePolicy.get().active, "файл политики: активна");
        T.check(BirtEnterprisePolicy.injectionMode().equals("strict"),
            "политика задаёт injection=strict");
        T.check(BirtEnterprisePolicy.toolDisabled("remove_element"),
            "политика отключает remove_element");
        T.check(BirtEnterprisePolicy.toolDisabled("db_schema"),
            "политика отключает db_schema");
        T.check(!BirtEnterprisePolicy.toolDisabled("add_table"),
            "add_table не в списке отключенных");
        T.check(!BirtEnterprisePolicy.editExistingAllowed(),
            "политика запрещает правку чужих");
        T.check(BirtEnterprisePolicy.maxIterations(100) == 5,
            "политика сужает лимит итераций до 5");
        T.check(BirtEnterprisePolicy.maxIterations(3) == 3,
            "пользовательский лимит ниже — он и действует");
        T.check(BirtEnterprisePolicy.piiMaskingEnabled(),
            "политика закрепляет маскирование");
        T.check(BirtEnterprisePolicy.auditLocked(), "политика закрепляет аудит");

        // tools.disabled = *
        Files.write(policy, "tools.disabled=*\n".getBytes(StandardCharsets.UTF_8));
        T.check(BirtEnterprisePolicy.toolDisabled("add_table")
            && BirtEnterprisePolicy.toolDisabled("report_new"),
            "tools.disabled=* отключает всё");

        // --- Сломанная политика: fail-closed ---
        Files.write(policy, "это не properties !!! = = =".getBytes(StandardCharsets.UTF_8));
        // сломанный файл всё же частично парсится Properties; проверяем главный сценарий:
        System.setProperty(BirtEnterprisePolicy.POLICY_PROPERTY,
            config.resolve("no-such-policy.properties").toString());
        T.check(BirtEnterprisePolicy.get().broken,
            "отсутствующий файл (через свойство): broken=true");
        T.check(BirtEnterprisePolicy.toolDisabled("add_table"),
            "fail-closed: инструменты отключены");
        T.check(!BirtEnterprisePolicy.editExistingAllowed(),
            "fail-closed: правка запрещена");
        System.clearProperty(BirtEnterprisePolicy.POLICY_PROPERTY);

        // --- Пользовательская настройка не расширяет политику ---
        Files.write(policy, "pii.masking=true\n".getBytes(StandardCharsets.UTF_8));
        Properties user = new Properties();
        user.setProperty(BirtSettings.KEY_PII_MASKING, "false");
        BirtSettings.save(user);
        T.check(BirtEnterprisePolicy.piiMaskingEnabled(),
            "пользователь не может выключить маскирование при политике true");

        Files.deleteIfExists(policy);
        user.setProperty(BirtSettings.KEY_PII_MASKING, "true");
        BirtSettings.save(user);
    }

    private EnterprisePolicyTest() {
    }
}
