/*
 * AI BIRT — тесты текстового протокола вызовов и подсказок по ошибкам BIRT.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.tests;

import com.aisql.birt.core.report.BirtErrorHints;
import com.aisql.birt.core.tools.ToolCall;

import java.util.List;

public final class ToolProtocolTest {

    public static void run() {
        T.section("Текстовый протокол TOOL и подсказки BIRT");

        // --- Разбор TOOL-строк ---
        List<ToolCall> calls = ToolCall.parseTextProtocol(
            "Сейчас добавлю таблицу.\n"
            + "TOOL add_table {\"dataset\":\"ds1\",\"columns\":[{\"header\":\"X\"}]}\n"
            + "Пояснение между вызовами.\n"
            + "TOOL add_label {\"text\":\"Привет\"}\n");
        T.check(calls.size() == 2, "разбор: два вызова из четырёх строк");
        T.check(calls.get(0).getName().equals("add_table"), "первый вызов: имя");
        T.check(calls.get(0).getArgsJson().contains("ds1"), "аргументы первого");
        T.check(calls.get(1).getName().equals("add_label"), "второй вызов: имя");
        T.check(!calls.get(0).getId().isEmpty(), "id назначен");

        // TOOL без аргументов: допустимо, аргументы = {}
        calls = ToolCall.parseTextProtocol("TOOL report_validate");
        T.check(calls.size() == 1 && calls.get(0).getArgsJson().equals("{}"),
            "TOOL без {} допустим: аргументы = {}");

        // Некорректный JSON в аргументах
        calls = ToolCall.parseTextProtocol("TOOL add_label {текст не json}");
        T.check(calls.size() == 0, "битый JSON — вызов пропущен");

        // Имя не по формату
        calls = ToolCall.parseTextProtocol("TOOL 123плохое {}");
        T.check(calls.size() == 0, "плохое имя инструмента — пропуск");

        // Русский префикс не распознаётся (протокол латинский)
        calls = ToolCall.parseTextProtocol("ИНСТРУМЕНТ add_label {}");
        T.check(calls.size() == 0, "другой префикс не распознаётся");

        // parsedArgs
        ToolCall c = new ToolCall("x", "add_label", "{\"text\":\"ok\"}");
        java.util.Map<String, Object> args = c.parsedArgs();
        T.check(args != null && "ok".equals(args.get("text")), "parsedArgs разбирает JSON");
        T.check(new ToolCall("x", "y", "не json").parsedArgs() == null,
            "parsedArgs: null при битом JSON");

        // --- Подсказки BirtErrorHints ---
        String hint = BirtErrorHints.hint(
            "Column binding \"col1\" has been applied to a report item that...");
        T.check(hint != null && hint.contains("колонк"),
            "подсказка для column binding");
        T.check(BirtErrorHints.hint("Cannot connect to database") != null,
            "подсказка для cannot connect");
        T.check(BirtErrorHints.hint("Something completely different") == null,
            "неизвестной ошибке — нет подсказки (null)");
        T.check(BirtErrorHints.hint(null) == null, "null — без подсказки");
        T.check(BirtErrorHints.allHints("column binding и data set отсутствуют").size() == 2,
            "allHints находит обе подсказки");
    }

    private ToolProtocolTest() {
    }
}
