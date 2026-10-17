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
        Map<String, Object> request = Json.obj();
        request.put("model", model);
        List<Object> msgs = Json.arr();
        for (ChatMessage m : messages) {
            Map<String, Object> item = Json.obj();
            item.put("role", m.getRole());
            item.put("content", m.getContent());
            msgs.add(item);
        }
        request.put("messages", msgs);
        request.put("temperature", Double.valueOf(temperature));
        request.put("stream", Boolean.FALSE);
        if (maxTokens > 0) {
            request.put("max_tokens", Integer.valueOf(maxTokens));
        }

        String response = request("POST", "/chat/completions", Json.write(request));
        Object root = parseJson(response);
        Object content = Json.at(root, "choices", Integer.valueOf(0), "message", "content");
        if (content == null) {
            // Некоторые серверы отвечают в формате legacy completions.
            content = Json.at(root, "choices", Integer.valueOf(0), "text");
        }
        String text = textOf(content);
        if (text == null || text.trim().isEmpty()) {
            String finish = Json.asStr(Json.at(root, "choices", Integer.valueOf(0), "finish_reason"));
            if (finish != null) {
                throw new LlmException("Модель не вернула текст (finish_reason=" + finish
                    + "). Попробуйте переформулировать запрос или увеличить llm.maxTokens.");
            }
            throw new LlmException("Не удалось разобрать ответ модели: " + snippet(response));
        }
        return text.trim();
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
