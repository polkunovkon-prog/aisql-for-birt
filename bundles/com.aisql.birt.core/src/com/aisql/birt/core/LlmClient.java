/*
 * AI BIRT — клиент OpenAI-совместимого сервера (Chat Completions).
 *
 * Реализован на HttpURLConnection из состава Java 8: никаких внешних
 * библиотек, бандл ядра работает в Eclipse 4.7 без дополнительных
 * зависимостей.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core;

import com.aisql.birt.core.tools.ToolCall;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class LlmClient {

    /** Ошибка обращения к ИИ-серверу (с текстом, понятным пользователю). */
    public static class LlmException extends Exception {

        private static final long serialVersionUID = 1L;

        public LlmException(String message) {
            super(message);
        }

        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int DEFAULT_TIMEOUT_SEC = 120;
    private static final int MAX_SNIPPET = 300;

    private final String baseUrl;
    private final String model;
    private final String token;
    private final int timeoutSec;

    public LlmClient(String baseUrl, String model, String token, int timeoutSec) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.model = model == null ? "" : model.trim();
        this.token = token == null ? "" : token.trim();
        this.timeoutSec = timeoutSec > 0 ? timeoutSec : DEFAULT_TIMEOUT_SEC;
    }

    /** Клиент по текущим настройкам плагина (~/.ai-birt/ai-birt.properties). */
    public static LlmClient fromSettings() {
        return new LlmClient(
            BirtSettings.baseUrl(),
            BirtSettings.model(),
            BirtSettings.token(),
            BirtSettings.timeoutSec());
    }

    public String getModel() {
        return model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    // ------------------------------------------------------------------
    // Запросы
    // ------------------------------------------------------------------

    /**
     * Отправляет историю сообщений и возвращает текст ответа модели.
     * Блокирующий вызов — запускайте в фоновом потоке.
     */
    public String chat(List<ChatMessage> messages, double temperature, int maxTokens) throws LlmException {
        LlmResponse r = chatInternal(messages, null, temperature, maxTokens);
        if (r.toolCalls != null && !r.toolCalls.isEmpty()) {
            // текстовый канал без tools: модель всё равно прислала вызовы —
            // не теряем их, но и не выполняем здесь (это компетенция агента)
            return r.content == null || r.content.isEmpty()
                ? "" : r.content;
        }
        if (r.content == null || r.content.trim().isEmpty()) {
            throw emptyResponse(r.finishReason);
        }
        return r.content.trim();
    }

    /**
     * Агентный запрос: с описаниями инструментов. Поддерживает оба канала
     * вызова: штатный tool calling (в ответе tool_calls) и текстовый
     * протокол (строки «TOOL …» в содержимом).
     *
     * @param tools список описаний инструментов (формат OpenAI tools)
     * @param sendTools false — не передавать tools в запрос (текстовый
     *                  протокол; для серверов без поддержки)
     */
    public LlmResponse chatWithTools(List<ChatMessage> messages, List<Object> tools,
                                     double temperature, int maxTokens, boolean sendTools)
            throws LlmException {
        return chatInternal(messages, sendTools ? tools : null, temperature, maxTokens);
    }

    /** Ответ модели: текст, вызовы инструментов и причина завершения. */
    public static final class LlmResponse {

        /** Текст ответа (может быть пустым, если модель зовёт инструменты). */
        public final String content;
        /** Вызовы инструментов из tool_calls (может быть пустым). */
        public final List<ToolCall> toolCalls;
        /** Причина завершения: stop | tool_calls | length | … (может быть null). */
        public final String finishReason;

        LlmResponse(String content, List<ToolCall> toolCalls, String finishReason) {
            this.content = content;
            this.toolCalls = toolCalls == null
                ? new ArrayList<ToolCall>() : toolCalls;
            this.finishReason = finishReason;
        }

        /** Создание ответа вручную (для тестов и актёров). */
        public static LlmResponse of(String content, List<ToolCall> toolCalls,
                String finishReason) {
            return new LlmResponse(content, toolCalls, finishReason);
        }

        public boolean hasToolCalls() {
            return !toolCalls.isEmpty();
        }

        /** Вызовы из текстового протокола, если штатных нет. */
        public List<ToolCall> allToolCalls() {
            if (hasToolCalls()) {
                return toolCalls;
            }
            return ToolCall.parseTextProtocol(content);
        }
    }

    private LlmException emptyResponse(String finishReason) {
        if (finishReason != null && !finishReason.isEmpty()) {
            return new LlmException("Модель не вернула текст (finish_reason=" + finishReason
                + "). Попробуйте переформулировать запрос или увеличить llm.maxTokens.");
        }
        return new LlmException("Не удалось разобрать ответ модели: "
            + "(пустой ответ)");
    }

    private LlmResponse chatInternal(List<ChatMessage> messages, List<Object> tools,
                                     double temperature, int maxTokens) throws LlmException {
        Map<String, Object> request = Json.obj();
        request.put("model", model);
        List<Object> msgs = Json.arr();
        for (ChatMessage m : messages) {
            msgs.add(serializeMessage(m));
        }
        request.put("messages", msgs);
        request.put("temperature", Double.valueOf(temperature));
        request.put("stream", Boolean.FALSE);
        if (tools != null && !tools.isEmpty()) {
            request.put("tools", tools);
            request.put("tool_choice", "auto");
        }
        if (maxTokens > 0) {
            request.put("max_tokens", Integer.valueOf(maxTokens));
        }

        String response = request("POST", "/chat/completions", Json.write(request));
        Object root = parseJson(response);
        Object messageObj = Json.at(root, "choices", Integer.valueOf(0), "message");
        String finish = Json.asStr(Json.at(root, "choices", Integer.valueOf(0), "finish_reason"));
        String text = textOf(messageObj == null ? null
            : Json.at(messageObj, "content"));
        if (text == null) {
            // Некоторые серверы отвечают в формате legacy completions.
            text = textOf(Json.at(root, "choices", Integer.valueOf(0), "text"));
        }

        List<ToolCall> calls = new ArrayList<ToolCall>();
        Object rawCalls = messageObj == null ? null : Json.at(messageObj, "tool_calls");
        List<Object> arr = Json.asArr(rawCalls);
        if (arr != null) {
            for (Object item : arr) {
                String id = Json.asStr(Json.at(item, "id"));
                String name = Json.asStr(Json.at(item, "function", "name"));
                String args = Json.asStr(Json.at(item, "function", "arguments"));
                if (name != null && !name.isEmpty()) {
                    calls.add(new ToolCall(id, name,
                        args == null || args.isEmpty() ? "{}" : args));
                }
            }
        }
        return new LlmResponse(text, calls, finish);
    }

    /** Сериализует сообщение с учётом роли tool и tool_calls ассистента. */
    private Object serializeMessage(ChatMessage m) {
        Map<String, Object> item = Json.obj();
        item.put("role", m.getRole());
        if (ChatMessage.ROLE_TOOL.equals(m.getRole())) {
            item.put("tool_call_id", m.getToolCallId() == null ? "" : m.getToolCallId());
            item.put("content", m.getContent());
            return item;
        }
        if (ChatMessage.ROLE_ASSISTANT.equals(m.getRole()) && !m.getToolCalls().isEmpty()) {
            List<Object> rawCalls = Json.arr();
            for (ToolCall c : m.getToolCalls()) {
                Map<String, Object> fn = Json.obj();
                fn.put("name", c.getName());
                fn.put("arguments", c.getArgsJson());
                Map<String, Object> call = Json.obj();
                call.put("id", c.getId());
                call.put("type", "function");
                call.put("function", fn);
                rawCalls.add(call);
            }
            item.put("tool_calls", rawCalls);
            if (m.getContent() != null && !m.getContent().isEmpty()) {
                item.put("content", m.getContent());
            }
            return item;
        }
        item.put("content", m.getContent());
        return item;
    }

    /** Список моделей сервера (GET /models) — для кнопки «Проверить соединение». */
    public List<String> listModels() throws LlmException {
        String response = request("GET", "/models", null);
        Object root = parseJson(response);
        List<Object> data = Json.asArr(Json.at(root, "data"));
        List<String> ids = new ArrayList<String>();
        if (data != null) {
            for (Object item : data) {
                String id = Json.asStr(Json.at(item, "id"));
                if (id != null && !id.isEmpty()) {
                    ids.add(id);
                }
            }
        }
        if (ids.isEmpty()) {
            throw new LlmException("Сервер не вернул список моделей: " + snippet(response));
        }
        return ids;
    }

    // ------------------------------------------------------------------
    // Внутреннее
    // ------------------------------------------------------------------

    private static String textOf(Object content) {
        if (content instanceof String) {
            return (String) content;
        }
        // content может быть массивом частей: [{"type":"text","text":"…"}]
        List<Object> parts = Json.asArr(content);
        if (parts == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Object part : parts) {
            String t = Json.asStr(Json.at(part, "text"));
            if (t != null) {
                sb.append(t);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private Object parseJson(String response) throws LlmException {
        try {
            return Json.parse(response);
        } catch (Json.JsonException e) {
            throw new LlmException("Ответ сервера не является JSON: " + snippet(response));
        }
    }

    private String request(String method, String path, String body) throws LlmException {
        String url = joinUrl(baseUrl, path);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(timeoutMs());
            conn.setRequestProperty("Accept", "application/json");
            if (!token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                // ВАЖНО: без setFixedLengthStreamingMode/setChunkedStreamingMode.
                // В JDK 8 потоковая отправка тела ломает чтение тела ответа
                // ошибки 401 (getErrorStream возвращает null — проверено на
                // JDK 8u504); буферизованный режим работает корректно, а тела
                // запросов у плагина небольшие.
                OutputStream out = conn.getOutputStream();
                try {
                    out.write(bytes);
                } finally {
                    out.close();
                }
            }
            int code = conn.getResponseCode();
            String text = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code != 200) {
                throw new LlmException(httpError(code, text));
            }
            return text;
        } catch (LlmException e) {
            throw e;
        } catch (UnknownHostException e) {
            throw new LlmException("Не найден сервер «" + hostOf(url) + "» — проверьте адрес llm.baseUrl в настройках плагина.", e);
        } catch (SocketTimeoutException e) {
            throw new LlmException("Сервер не ответил за " + timeoutSec + " с (llm.timeoutSec). "
                + "Повторите позже или увеличьте таймаут.", e);
        } catch (ConnectException e) {
            throw new LlmException("Не удалось подключиться к «" + hostOf(url) + "»: " + e.getMessage()
                + ". Проверьте, что ИИ-сервер запущен и доступен.", e);
        } catch (IOException e) {
            throw new LlmException("Ошибка связи с ИИ-сервером: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String httpError(int code, String text) {
        String serverMessage = null;
        try {
            serverMessage = Json.asStr(Json.at(Json.parse(text), "error", "message"));
        } catch (RuntimeException ignore) {
            // тело не JSON — покажем фрагмент как есть
        }
        if (serverMessage == null || serverMessage.isEmpty()) {
            serverMessage = snippet(text);
        }
        String hint = "";
        if (code == 401 || code == 403) {
            hint = " Проверьте ключ доступа llm.token в настройках плагина.";
        } else if (code == 404) {
            hint = " Проверьте адрес llm.baseUrl и идентификатор llm.model.";
        } else if (code >= 500) {
            hint = " Ошибка на стороне ИИ-сервера — повторите запрос позже.";
        }
        return "Сервер вернул ошибку HTTP " + code + ": " + serverMessage + hint;
    }

    private int timeoutMs() {
        long ms = ((long) timeoutSec) * 1000L;
        if (ms > Integer.MAX_VALUE) {
            ms = Integer.MAX_VALUE;
        }
        return (int) ms;
    }

    private static String joinUrl(String base, String path) {
        String b = base;
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        String p = path.startsWith("/") ? path : "/" + path;
        return b + p;
    }

    private static String hostOf(String url) {
        try {
            String host = new URI(url).getHost();
            if (host != null) {
                return host;
            }
        } catch (Exception ignore) {
            // покажем URL целиком
        }
        return url;
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, n);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    /** Короткий однострочный фрагмент ответа для сообщений об ошибках. */
    private static String snippet(String text) {
        if (text == null) {
            return "(пустой ответ)";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        if (flat.length() > MAX_SNIPPET) {
            return flat.substring(0, MAX_SNIPPET) + "…";
        }
        return flat.isEmpty() ? "(пустой ответ)" : flat;
    }
}
