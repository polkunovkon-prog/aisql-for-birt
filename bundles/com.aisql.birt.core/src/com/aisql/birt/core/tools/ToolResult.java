/*
 * AI BIRT — результат выполнения инструмента.
 *
 * Возвращается модели в сообщении role:"tool": JSON-строка с признаком
 * успеха и полезной нагрузкой либо списком проблем. Ошибки — это
 * инструментальные ошибки (паттерн MCP isError): модель читает причину
 * и корректирует следующий вызов, а не роняет весь цикл.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core.tools;

import com.aisql.birt.core.Json;

import java.util.List;
import java.util.Map;

/** Результат инструмента для модели. */
public final class ToolResult {

    public final boolean ok;
    private final Map<String, Object> payload;

    private ToolResult(boolean ok, Map<String, Object> payload) {
        this.ok = ok;
        this.payload = payload;
    }

    public static ToolResult ok(Map<String, Object> payload) {
        Map<String, Object> m = Json.obj();
        m.put("ok", Boolean.TRUE);
        if (payload != null) {
            m.putAll(payload);
        }
        return new ToolResult(true, m);
    }

    public static ToolResult ok(String key, Object value) {
        Map<String, Object> m = Json.obj();
        m.put("ok", Boolean.TRUE);
        m.put(key, value);
        return new ToolResult(true, m);
    }

    public static ToolResult error(String message) {
        Map<String, Object> m = Json.obj();
        m.put("ok", Boolean.FALSE);
        m.put("error", message);
        return new ToolResult(false, m);
    }

    public static ToolResult error(String message, List<String> problems) {
        Map<String, Object> m = Json.obj();
        m.put("ok", Boolean.FALSE);
        m.put("error", message);
        if (problems != null && !problems.isEmpty()) {
            m.put("problems", problems);
        }
        return new ToolResult(false, m);
    }

    /** JSON для сообщения role:"tool". */
    public String content() {
        return Json.write(payload);
    }

    /** Краткое описание для прогресса UI. */
    public String summary() {
        if (ok) {
            Object name = payload.get("name");
            return name != null ? String.valueOf(name) : "ок";
        }
        Object error = payload.get("error");
        return "ошибка: " + (error == null ? "?" : String.valueOf(error));
    }
}
