/*
 * AI BIRT — тесты детекции и маскирования ПДн.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.tests;

import com.aisql.birt.core.security.PiiDetector;

public final class PiiDetectorTest {

    public static void run() {
        T.section("Маскирование ПДн (детектор)");

        // Email
        PiiDetector.MaskResult r = PiiDetector.maskText(
            "Почта директора: ivanov@example.ru, телефон ниже");
        T.check(r.masked == 1 && r.text.contains("***@***"),
            "email маскируется в тексте");
        T.check(r.text.contains("директора"), "текст вокруг email не портится");

        // Телефон РФ
        r = PiiDetector.maskText("Звонить +7 912 345-67-89 по будням");
        T.check(r.masked >= 1 && !r.text.contains("912 345"),
            "телефон РФ маскируется");
        // E.164
        r = PiiDetector.maskText("Контакт: +79123456789");
        T.check(r.masked >= 1, "телефон E.164 маскируется");
        // Короткий номер не телефон
        r = PiiDetector.maskText("Код 123456 в приложении");
        T.check(r.masked == 0, "шестизначный код не трогается");

        // Карта (валидная по Луну: 4111111111111111)
        r = PiiDetector.maskText("Оплата картой 4111 1111 1111 1111 прошла");
        T.check(r.masked == 1 && !r.text.contains("4111"),
            "номер карты маскируется");
        // 16 цифр НЕ по Луну — не карта
        r = PiiDetector.maskText("Счёт 1234567812345678 оплачен");
        T.check(r.masked == 0, "16 цифр без Луна не трогаются");

        // ИНН 10 (валидный: 7707083893 — Сбербанк)
        r = PiiDetector.maskText("ИНН организации 7707083893, реквизиты ниже");
        T.check(r.masked == 1, "ИНН-10 маскируется");
        // невалидный ИНН
        r = PiiDetector.maskText("ИНН 1234567890 указан неверно");
        T.check(r.masked == 0, "невалидный ИНН не трогается");

        // СНИЛС (валидный: 112-233-445 95)
        r = PiiDetector.maskText("СНИЛС 11223344595");
        T.check(r.masked == 1, "СНИЛС маскируется");

        // Смешанный текст
        r = PiiDetector.maskText("Письмо на petr.petrov@corp.example и карта 4111111111111111");
        T.check(r.masked == 2, "email + карта в одном тексте: две замены");

        // Обычный текст без изменений
        String plain = "Отчёт по продажам за октябрь 2026 года, склад №7";
        r = PiiDetector.maskText(plain);
        T.check(r.masked == 0 && r.text.equals(plain), "обычный текст не меняется");

        // null и короткие значения
        T.check(PiiDetector.maskText(null).masked == 0, "null без изменений");
        T.check(PiiDetector.maskText("коротко").masked == 0, "короткий текст без изменений");

        // isPii
        T.check(PiiDetector.isPii("user@example.com"), "isPii: email");
        T.check(PiiDetector.isPii("+7 912 345 67 89"), "isPii: телефон");
        T.check(!PiiDetector.isPii("Обычный текст"), "isPii: обычный текст");
        T.check(PiiDetector.luhnValid("4111111111111111"), "Лун: валидная карта");
        T.check(!PiiDetector.luhnValid("4111111111111112"), "Лун: изменённая карта");
    }

    private PiiDetectorTest() {
    }
}
