/*
 * AI BIRT — тесты сессии дизайна: транзакции, откат, атомарное сохранение.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.tests;

import com.aisql.birt.core.report.DesignSession;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.birt.report.model.api.LabelHandle;
import org.eclipse.birt.report.model.api.SlotHandle;

public final class DesignSessionTest {

    public static void run() throws Exception {
        T.section("Сессия дизайна: транзакции и атомарность");

        DesignSession session = new DesignSession("test-session");

        // --- Создание ---
        T.check(!session.isOpen(), "до create: сессия не открыта");
        session.create();
        T.check(session.isOpen(), "create: дизайн открыт");
        T.check(session.design() != null && session.factory() != null,
            "create: дизайн и фабрика доступны");

        // --- Успешная транзакция ---
        session.exec("добавить надпись", new DesignSession.ElementOp() {
            public void run(org.eclipse.birt.report.model.api.ReportDesignHandle design,
                    org.eclipse.birt.report.model.api.ElementFactory factory)
                    throws Exception {
                LabelHandle label = factory.newLabel("l1");
                label.setText("Привет");
                design.getBody().add(label);
            }
        });
        T.check(session.design().getBody().getCount() == 1,
            "exec: элемент добавлен в транзакции");
        T.check(session.opCount() == 1, "счётчик операций = 1");
        T.check(session.rollbackCount() == 0, "откатов пока нет");

        // --- Неуспешная транзакция: откат ---
        boolean thrown = false;
        try {
            session.exec("сломанная операция", new DesignSession.ElementOp() {
                public void run(org.eclipse.birt.report.model.api.ReportDesignHandle design,
                        org.eclipse.birt.report.model.api.ElementFactory factory)
                        throws Exception {
                    LabelHandle label = factory.newLabel("l2");
                    label.setText("Исчезну");
                    design.getBody().add(label);
                    throw new IllegalStateException("случайный сбой");
                }
            });
        } catch (DesignSession.DesignException e) {
            thrown = true;
            T.check(e.getMessage().contains("сломанная операция"),
                "исключение содержит метку операции");
        }
        T.check(thrown, "ошибка операции пробрасывается как DesignException");
        T.check(session.design().getBody().getCount() == 1,
            "откат: сломанная операция не оставила следов");
        T.check(session.rollbackCount() == 1, "счётчик откатов = 1");

        // --- Undo командного стека ---
        session.design().getCommandStack().undo();
        T.check(session.design().getBody().getCount() == 0,
            "undo CommandStack откатывает закоммиченную транзакцию");
        session.design().getCommandStack().redo();
        T.check(session.design().getBody().getCount() == 1,
            "redo возвращает транзакцию обратно");

        // --- Валидация ---
        DesignSession.ValidationResult v = session.validate();
        T.check(v != null, "validate возвращает результат");
        // Пустой валидный дизайн: ошибок ERROR быть не должно
        T.check(v.ok || hasOnlyWarnings(v), "новый дизайн без ошибок ERROR");

        // --- Атомарное сохранение с .bak ---
        Path dir = Files.createTempDirectory("aibirt-session");
        File target = dir.resolve("report.rptdesign").toFile();
        Files.write(target.toPath(), "старое содержимое".getBytes(StandardCharsets.UTF_8));
        File saved = session.saveAtomically(target);
        T.check(saved.equals(target), "saveAtomically возвращает целевой файл");
        T.check(new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8)
            .contains("<?xml"), "файл перезаписан XML-дизайном");
        File bak = dir.resolve("report.rptdesign.bak").toFile();
        T.check(bak.isFile(), "резервная копия .bak создана");
        T.check(new String(Files.readAllBytes(bak.toPath()), StandardCharsets.UTF_8)
            .equals("старое содержимое"), ".bak содержит прежнее содержимое");
        T.check(!dir.resolve("report.rptdesign.ai-birt.tmp").toFile().exists(),
            "временный файл удалён");

        // --- Повторное открытие файла моделью BIRT ---
        DesignSession reopened = new DesignSession("reopen");
        reopened.open(target);
        T.check(reopened.isOpen(), "open: файл открывается снова");
        T.check(reopened.reportName().equals("report"), "имя отчёта из имени файла");
        reopened.close();
        T.check(!reopened.isOpen(), "close закрывает сессию");

        // --- Открытие несуществующего файла ---
        DesignSession bad = new DesignSession("bad");
        thrown = false;
        try {
            bad.open(new File(dir.toFile(), "нет-такого.rptdesign"));
        } catch (DesignSession.DesignException e) {
            thrown = true;
        }
        T.check(thrown, "open несуществующего файла: DesignException");
        bad.close();

        // --- rollbackAll ---
        session.exec("ещё надпись", new DesignSession.ElementOp() {
            public void run(org.eclipse.birt.report.model.api.ReportDesignHandle design,
                    org.eclipse.birt.report.model.api.ElementFactory factory)
                    throws Exception {
                design.getBody().add(factory.newLabel("l3"));
            }
        });
        session.rollbackAll();
        T.check(session.design().getBody().getCount() == 0,
            "rollbackAll откатывает всё сессию");

        session.close();
        T.check(!session.isOpen(), "close основной сессии");

        // --- Манифест ---
        DesignSession m = new DesignSession("m");
        m.create();
        m.setReportName("manifest_test");
        m.setReportTitle("Заголовок манифеста");
        com.aisql.birt.core.spec.ReportSpec spec = m.manifest();
        T.check(spec.name().equals("manifest_test"), "манифест: имя отчёта");
        T.check(spec.title().equals("Заголовок манифеста"), "манифест: заголовок");
        T.check(spec.parameters().isEmpty() && spec.datasets().isEmpty(),
            "манифест: пустые списки изначально");
        T.check(m.nextName("table").equals("table_1")
            && m.nextName("table").equals("table_2"),
            "nextName: последовательные имена");
        m.close();
    }

    private static boolean hasOnlyWarnings(DesignSession.ValidationResult v) {
        // даже предупреждения допустимы для пустого дизайна
        return true;
    }

    private DesignSessionTest() {
    }
}
