/*
 * AI BIRT — один вызов инструмента модели.
 *
 * Идентифицируется идентификатором протокола (id), именем инструмента
 * и аргументами в виде строки JSON. Аргументы разбираются строгим
 * парсером Json плагина уже внутри обработчика инструмента — неизвестные
 * поля запрещены, как и в спецификации v0.
 *
 * Поддерживается и текстовый протокол для локальных серверов без
 * штатного tool calling: модель пишет построчно
 *     TOOL add_table {"dataset":"ds1","columns":[...]}
 * Строки без префикса TOOL игнорируются (модель может писать пояснения).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core.tools;

import com.aisql.birt.core.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Вызов инструмента: id + имя + аргументы (JSON-строка). */
public final class ToolCall {

    /** Префикс строки текстового протокола вызовов. */
    public static final String TEXT_PREFIX = "TOOL";

    private final String id;
    private final String name;
    private final String argsJson;

    public ToolCall(String id, String name, String argsJson) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name.trim();
        this.argsJson = argsJson == null ? "{}" : argsJson;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /** Аргументы как строка JSON (объект). */
    public String getArgsJson() {
        return argsJson;
    }

    /** Разобранные аргументы (LinkedHashMap); при ошибке — null. */
    public java.util.Map<String, Object> parsedArgs() {
        try {
            Object o = Json.parse(argsJson);
            return Json.asObj(o);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return name + "(" + argsJson + ")";
    }

    // ------------------------------------------------------------------
    // Текстовый протокол
    // ------------------------------------------------------------------

    /**
     * Разбирает текстовый ответ модели на вызовы инструментов (строки
     * «TOOL имя {json}»). Пустой JSON допустим. Возвращает список вызовов
     * (возможно, пустой); malformed-строки пропускаются молча — самокоррекция
     * произойдёт, когда модель не увидит результата вызова.
     *
     * @param text полный текст ответа модели
     */
    public static List<ToolCall> parseTextProtocol(String text) {
        List<ToolCall> calls = new ArrayList<ToolCall>();
        if (text == null || text.isEmpty()) {
            return calls;
        }
        int seq = 0;
        for (String rawLine : text.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            String upper = line.toUpperCase(Locale.ROOT);
            if (!upper.startsWith(TEXT_PREFIX + " ") && !upper.startsWith(TEXT_PREFIX + "\t")) {
                continue;
            }
            String rest = line.substring(TEXT_PREFIX.length()).trim();
            int brace = rest.indexOf('{');
            String name = brace < 0 ? rest : rest.substring(0, brace).trim();
            String args = "{}";
            if (brace >= 0) {
                // берём до последней «}» строки: JSON-объект без переносов
                int last = rest.lastIndexOf('}');
                if (last > brace) {
                    args = rest.substring(brace, last + 1);
                }
            }
            if (name.isEmpty() || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                continue;
            }
            try {
                Object parsed = Json.parse(args);
                if (Json.asObj(parsed) == null) {
                    continue;
                }
            } catch (RuntimeException e) {
                continue;
            }
            seq++;
            calls.add(new ToolCall("txt-" + seq, name, args));
        }
        return calls;
    }
}
