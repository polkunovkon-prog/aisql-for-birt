/*
 * AI BIRT — тесты мини-JSON (разбор, запись, вспомогательные методы).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.tests;

import com.aisql.birt.core.Json;

import java.util.List;
import java.util.Map;

public final class JsonTest {

    private JsonTest() {
    }

    static void run() {
        T.section("Json: разбор");
        Object root = Json.parse(
            "{\"a\":1,\"b\":[true,null,\"x\\ny\",2.5,-3],\"c\":{\"d\":\"\\u041f\"}}");
        T.eq(Long.valueOf(1), Json.at(root, "a"), "целое число");
        T.eq(Boolean.TRUE, Json.at(root, "b", 0), "true");
        T.eq(null, Json.at(root, "b", 1), "null в массиве");
        T.eq("x\ny", Json.at(root, "b", 2), "escape \\n в строке");
        T.eq(Double.valueOf(2.5), Json.at(root, "b", 3), "дробное число");
        T.eq(Long.valueOf(-3), Json.at(root, "b", 4), "отрицательное число");
        T.eq("П", Json.at(root, "c", "d"), "\\u-escape (кириллица)");
        T.eq(null, Json.at(root, "нет-такого"), "несуществующий ключ");
        T.eq(null, Json.at(root, "b", 99), "индекс за пределами массива");
        T.eq(null, Json.at(root, "a", "b"), "путь через не-объект");

        T.eq(Long.valueOf(1), Json.at(Json.parse("{\"x\":1}"), "x"), "верхний уровень");
        T.eq(Boolean.TRUE, Json.parse("true"), "литерал true");
        T.eq(null, Json.parse("null"), "литерал null");
        T.eq(Double.valueOf(1.5e3), Json.parse("1.5e3"), "экспонента");

        T.section("Json: ошибки разбора");
        expectJsonError("{a:1}", "имя поля без кавычек");
        expectJsonError("{\"a\":}", "значение отсутствует");
        expectJsonError("[1,2", "массив не закрыт");
        expectJsonError("\"abc", "строка не закрыта");
        expectJsonError("{\"a\":1} лишнее", "мусор после JSON");
        expectJsonError("{\"a\":\"\\x\"}", "неизвестная escape-последовательность");
        expectJsonError("truthy", "незавершённый литерал");
        expectJsonError("", "пустой вход");

        T.section("Json: запись");
        Map<String, Object> obj = Json.obj();
        obj.put("a", Long.valueOf(1));
        List<Object> arr = Json.arr();
        arr.add(Boolean.TRUE);
        arr.add(null);
        arr.add("т");
        obj.put("b", arr);
        String written = Json.write(obj);
        T.eq("{\"a\":1,\"b\":[true,null,\"\u0442\"]}", written, "компактная запись с порядком полей");
        Object back = Json.parse(written);
        T.eq(Long.valueOf(1), Json.at(back, "a"), "roundtrip: число");
        T.eq("т", Json.at(back, "b", 2), "roundtrip: кириллица");
        T.eq(null, Json.at(back, "b", 1), "roundtrip: null");

        T.eq("\"a\\\"b\\\\c\"", Json.write("a\"b\\c"), "экранирование кавычек и слэшей");
        T.eq("\"a\\nb\"", Json.write("a\nb"), "экранирование перевода строки");
        String control = Json.write("x\u0001y");
        T.eq("\"x\\u0001y\"", control, "экранирование управляющего символа");
        T.eq("2.5", Json.write(Double.valueOf(2.5)), "дробное без .0-хвоста");
        T.eq("3", Json.write(Double.valueOf(3.0)), "целое double — без точки");
        T.eq("null", Json.write(Double.valueOf(Double.NaN)), "NaN → null");
        T.eq("\"текст\"", Json.write("текст"), "строка с кириллицей (UTF-16 без escape)");

        T.section("Json: доступ и приведение типов");
        T.eq(5L, Json.asLong(Long.valueOf(5), -1), "asLong из Long");
        T.eq(-1L, Json.asLong("5", -1), "asLong строки → значение по умолчанию");
        T.eq(1.5, Json.asDouble(Double.valueOf(1.5), 0), "asDouble");
        T.eq(Boolean.TRUE, Boolean.valueOf(Json.asBool(Boolean.TRUE, false)), "asBool");
        T.check(Json.asObj(Json.parse("{}")) != null, "asObj объекта");
        T.check(Json.asArr(Json.parse("[]")) != null, "asArr массива");
        T.eq(null, Json.asStr(Long.valueOf(1)), "asStr не-строки");
    }

    private static void expectJsonError(String json, String message) {
        try {
            Json.parse(json);
            T.check(false, message + " — ошибка не обнаружена: " + json);
        } catch (Json.JsonException e) {
            T.check(true, message);
        }
    }
}
