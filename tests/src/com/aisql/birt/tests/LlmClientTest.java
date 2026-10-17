/*
 * AI BIRT — тесты клиента ИИ-сервера на заглушечном HTTP-сервере
 * (com.sun.net.httpserver): успешные ответы, ошибки HTTP, разбор ответа,
 * заголовки запроса. Настоящий сервер не требуется.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.tests;

import com.aisql.birt.core.ChatMessage;
import com.aisql.birt.core.Json;
import com.aisql.birt.core.LlmClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class LlmClientTest {

    private static HttpServer server;
    private static String baseUrl;

    private static final AtomicInteger responseCode = new AtomicInteger(200);
    private static final AtomicReference<String> responseBody = new AtomicReference<String>("{}");
    private static final AtomicReference<String> lastBody = new AtomicReference<String>();
    private static final AtomicReference<String> lastAuth = new AtomicReference<String>();
    private static final AtomicReference<String> lastContentType = new AtomicReference<String>();

    private LlmClientTest() {
    }

    static void run() throws Exception {
        T.section("Клиент ИИ-сервера: успешный ответ");
        startServer();
        try {
            LlmClient client = new LlmClient(baseUrl, "test-model", "token-123", 30);

            response(200, "{\"choices\":[{\"message\":{\"content\":\"Привет!\"}}]}");
            List<ChatMessage> messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("Ты — помощник."));
            messages.add(ChatMessage.user("Как дела?"));
            String reply = client.chat(messages, 0.5, 0);
            T.eq("Привет!", reply, "текст ответа");

            Object sent = Json.parse(lastBody.get());
            T.eq("test-model", Json.asStr(Json.at(sent, "model")), "модель в запросе");
            T.eq(Boolean.FALSE, Json.at(sent, "stream"), "stream=false");
            T.eq(Double.valueOf(0.5), Json.at(sent, "temperature"), "температура в запросе");
            T.eq(null, Json.at(sent, "max_tokens"), "max_tokens не передаётся при 0");
            T.eq(2, Json.asArr(Json.at(sent, "messages")).size(), "число сообщений");
            T.eq("system", Json.asStr(Json.at(sent, "messages", 0, "role")), "роль первого сообщения");
            T.eq("Как дела?", Json.asStr(Json.at(sent, "messages", 1, "content")), "текст второго сообщения");
            T.eq("Bearer token-123", lastAuth.get(), "заголовок Authorization");
            T.check(lastContentType.get() != null && lastContentType.get().contains("application/json"),
                "заголовок Content-Type");

            // maxTokens передаётся, когда задан.
            response(200, "{\"choices\":[{\"message\":{\"content\":\"ок\"}}]}");
            client.chat(messages, 0.5, 512);
            Object sent2 = Json.parse(lastBody.get());
            T.eq(512L, Json.asLong(Json.at(sent2, "max_tokens"), -1), "max_tokens передаётся");

            T.section("Клиент ИИ-сервера: варианты ответа");
            response(200, "{\"choices\":[{\"message\":{\"content\":["
                + "{\"type\":\"text\",\"text\":\"часть1\"},{\"type\":\"text\",\"text\":\"часть2\"}]}}]}");
            T.eq("часть1часть2", client.chat(messages, 0.5, 0), "content как массив частей");

            response(200, "{\"choices\":[{\"text\":\"legacy-ответ\"}]}");
            T.eq("legacy-ответ", client.chat(messages, 0.5, 0), "legacy-поле text");

            response(200, "{\"choices\":[{\"message\":{\"content\":\"\"},\"finish_reason\":\"length\"}]}");
            expectFailure(client, messages, 0.5,
                "finish_reason=length", "пустой ответ с указанием причины");

            response(200, "{\"choices\":[]}");
            expectFailure(client, messages, 0.5,
                "Не удалось разобрать ответ модели", "пустой список choices");

            T.section("Клиент ИИ-сервера: ошибки");
            response(401, "{\"error\":{\"message\":\"Not authenticated\"}}");
            LlmClient.LlmException e401 = failedCall(client, messages, 0.5);
            T.check(e401.getMessage().contains("401"), "код 401 в сообщении");
            T.check(e401.getMessage().contains("Not authenticated"), "текст сервера в сообщении");
            T.check(e401.getMessage().contains("llm.token"), "подсказка про ключ");

            response(404, "not found");
            LlmClient.LlmException e404 = failedCall(client, messages, 0.5);
            T.check(e404.getMessage().contains("404"), "код 404 в сообщении");
            T.check(e404.getMessage().contains("llm.baseUrl"), "подсказка про адрес");

            response(500, "{\"error\":{\"message\":\"boom\"}}");
            LlmClient.LlmException e500 = failedCall(client, messages, 0.5);
            T.check(e500.getMessage().contains("HTTP 500"), "код 500 в сообщении");
            T.check(e500.getMessage().contains("boom"), "текст сервера в сообщении 500");

            response(200, "<html>это не JSON</html>");
            expectFailure(client, messages, 0.5,
                "не является JSON", "ответ сервера не JSON");

            T.section("Клиент ИИ-сервера: список моделей");
            response(200, "{\"data\":[{\"id\":\"m1\"},{\"id\":\"m2\"},{\"object\":\"model\"}]}");
            List<String> models = client.listModels();
            T.eq(2, models.size(), "две модели");
            T.eq("m1", models.get(0), "первая модель");
            T.eq("m2", models.get(1), "вторая модель");

            response(200, "{\"data\":[]}");
            try {
                client.listModels();
                T.check(false, "пустой список моделей — ошибка не обнаружена");
            } catch (LlmClient.LlmException e) {
                T.check(e.getMessage().contains("не вернул список моделей"), "пустой список моделей — ошибка");
            }

            T.section("Клиент ИИ-сервера: недоступный сервер");
            int freePort;
            ServerSocket socket = new ServerSocket(0);
            try {
                freePort = socket.getLocalPort();
            } finally {
                socket.close();
            }
            LlmClient dead = new LlmClient("http://127.0.0.1:" + freePort + "/v1", "m", "", 5);
            try {
                dead.chat(messages, 0.5, 0);
                T.check(false, "недоступный сервер — ошибка не обнаружена");
            } catch (LlmClient.LlmException e) {
                T.check(e.getMessage().contains("Не удалось подключиться"), "понятное сообщение о недоступности");
            }

            // Завершающий слэш в адресе не мешает.
            LlmClient trailing = new LlmClient(baseUrl + "/", "test-model", "token-123", 30);
            response(200, "{\"choices\":[{\"message\":{\"content\":\"слэш ок\"}}]}");
            T.eq("слэш ок", trailing.chat(messages, 0.5, 0), "адрес с завершающим слэшем");
        } finally {
            stopServer();
        }
    }

    // ------------------------------------------------------------------

    private static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpHandler handler = new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                lastBody.set(readAll(exchange.getRequestBody()));
                lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(responseCode.get(), bytes.length);
                OutputStream out = exchange.getResponseBody();
                try {
                    out.write(bytes);
                } finally {
                    out.close();
                }
            }
        };
        server.createContext("/v1/chat/completions", handler);
        server.createContext("/v1/models", handler);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private static void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private static void response(int code, String body) {
        responseCode.set(code);
        responseBody.set(body);
    }

    private static LlmClient.LlmException failedCall(LlmClient client, List<ChatMessage> messages, double temperature) {
        try {
            client.chat(messages, temperature, 0);
            T.check(false, "ожидалась ошибка, но запрос прошёл");
            return new LlmClient.LlmException("нет ошибки");
        } catch (LlmClient.LlmException e) {
            return e;
        }
    }

    private static void expectFailure(LlmClient client, List<ChatMessage> messages,
                                      double temperature, String expectedText, String message) {
        LlmClient.LlmException e = failedCall(client, messages, temperature);
        T.check(e.getMessage().contains(expectedText),
            message + " (в сообщении ожидалось «" + expectedText + "», получено: " + e.getMessage() + ")");
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, n);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}
