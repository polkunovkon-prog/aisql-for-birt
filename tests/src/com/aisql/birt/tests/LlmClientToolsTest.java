/*
 * AI BIRT — тесты tool calling в LlmClient (заглушечный HTTP-сервер):
 * отправка tools, разбор tool_calls, возврат role:"tool".
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class LlmClientToolsTest {

    private static HttpServer server;
    private static String baseUrl;
    private static final AtomicReference<String> responseBody =
        new AtomicReference<String>("{}");
    private static final AtomicReference<String> lastBody = new AtomicReference<String>();

    private LlmClientToolsTest() {
    }

    static void run() throws Exception {
        T.section("Клиент ИИ-сервера: tool calling");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", new HttpHandler() {
            public void handle(HttpExchange ex) throws IOException {
                lastBody.set(read(ex.getRequestBody()));
                byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, body.length);
                OutputStream out = ex.getResponseBody();
                try {
                    out.write(body);
                } finally {
                    out.close();
                }
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        try {
            LlmClient client = new LlmClient(baseUrl, "tool-model", "", 30);

            // --- Ответ с tool_calls ---
            responseBody.set("{\"choices\":[{\"finish_reason\":\"tool_calls\","
                + "\"message\":{\"role\":\"assistant\",\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"add_table\","
                + "\"arguments\":\"{\\\"dataset\\\":\\\"ds1\\\"}\"}}]}}]}");
            List<Object> tools = new ArrayList<Object>();
            Map0 fn = new Map0();
            fn.put("name", "add_table");
            tools.add(fn);

            List<ChatMessage> messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("задание"));

            LlmClient.LlmResponse r = client.chatWithTools(messages, tools, 0.3, 0, true);
            T.check(r.hasToolCalls(), "tool_calls разобраны");
            T.check(r.toolCalls.get(0).getName().equals("add_table"), "имя инструмента");
            T.check(r.toolCalls.get(0).getId().equals("call-1"), "id вызова");
            T.check(r.toolCalls.get(0).getArgsJson().contains("ds1"), "аргументы (JSON-строка)");
            T.check("tool_calls".equals(r.finishReason), "finish_reason=tool_calls");

            // В запросе были tools и tool_choice
            Object sent = Json.parse(lastBody.get());
            T.check(Json.asArr(Json.at(sent, "tools")) != null
                && Json.asArr(Json.at(sent, "tools")).size() == 1,
                "tools переданы в запросе");
            T.eq("auto", Json.asStr(Json.at(sent, "tool_choice")), "tool_choice=auto");

            // --- Возврат результата инструментом ---
            messages.add(ChatMessage.assistantWithTools(null, r.toolCalls));
            messages.add(ChatMessage.toolResult("call-1", "{\"ok\":true,\"name\":\"table_1\"}"));
            responseBody.set("{\"choices\":[{\"finish_reason\":\"stop\","
                + "\"message\":{\"content\":\"Готово\"}}]}");
            String reply = client.chat(messages, 0.3, 0);
            T.eq("Готово", reply, "финальный текст после результатов инструментов");

            Object sent2 = Json.parse(lastBody.get());
            List<Object> arr = Json.asArr(Json.at(sent2, "messages"));
            T.check(arr.size() == 4, "история: 4 сообщения");
            Map<String, Object> toolMsg = Json.asObj(arr.get(3));
            T.eq("tool", Json.asStr(toolMsg.get("role")), "role=tool");
            T.eq("call-1", Json.asStr(toolMsg.get("tool_call_id")), "tool_call_id в сообщении");
            Map<String, Object> asstMsg = Json.asObj(arr.get(2));
            T.check(Json.asArr(Json.asStr(asstMsg.get("tool_calls")) == null
                ? null : asstMsg.get("tool_calls")) != null
                || Json.asArr(asstMsg.get("tool_calls")) != null,
                "assistant-сообщение содержит tool_calls");

            // --- sendTools=false: tools не передаются ---
            responseBody.set("{\"choices\":[{\"finish_reason\":\"stop\","
                + "\"message\":{\"content\":\"Текст без вызовов\"}}]}");
            client.chatWithTools(messages, tools, 0.3, 0, false);
            Object sent3 = Json.parse(lastBody.get());
            T.check(Json.at(sent3, "tools") == null, "sendTools=false: tools не передаются");

            // --- Текстовый протокол из content ---
            LlmClient.LlmResponse r2 = LlmClient.LlmResponse.of(
                "TOOL add_label {\"text\":\"x\"}", new ArrayList<com.aisql.birt.core.tools.ToolCall>(), "stop");
            T.check(r2.allToolCalls().size() == 1, "allToolCalls: текстовый протокол распознан");
            T.check(r2.hasToolCalls() == false, "hasToolCalls false при текстовом ответе");

            // --- Пустой ответ модели при tool_calls без content ---
            responseBody.set("{\"choices\":[{\"finish_reason\":\"tool_calls\","
                + "\"message\":{\"tool_calls\":[{\"id\":\"c2\",\"type\":\"function\","
                + "\"function\":{\"name\":\"report_validate\",\"arguments\":\"{}\"}}]}}]}");
            LlmClient.LlmResponse r3 = client.chatWithTools(
                new ArrayList<ChatMessage>(), tools, 0.3, 0, true);
            T.check(r3.hasToolCalls() && r3.toolCalls.get(0).getArgsJson().equals("{}"),
                "пустые аргументы заменяются на {}");
        } finally {
            server.stop(0);
        }
    }

    /** Простейшая карта для сборки tools в тесте. */
    private static final class Map0 extends java.util.HashMap<String, Object> {
    }

    private static String read(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, n);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }
}
