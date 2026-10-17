/*
 * AI BIRT — тесты истории чата.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.tests;

import com.aisql.birt.core.ChatHistory;
import com.aisql.birt.core.ChatMessage;

import java.util.List;

public final class ChatHistoryTest {

    private ChatHistoryTest() {
    }

    static void run() {
        T.section("История чата");

        ChatHistory history = new ChatHistory();
        T.eq(0, history.size(), "изначально пусто");

        List<ChatMessage> empty = history.buildApiMessages("Система");
        T.eq(1, empty.size(), "только системная инструкция");
        T.eq(ChatMessage.ROLE_SYSTEM, empty.get(0).getRole(), "роль system");

        history.add(ChatMessage.user("вопрос 1"));
        history.add(ChatMessage.assistant("ответ 1"));
        history.add(ChatMessage.user("вопрос 2"));
        T.eq(3, history.size(), "три сообщения");

        List<ChatMessage> api = history.buildApiMessages("Система");
        T.eq(4, api.size(), "system + три сообщения");
        T.eq(ChatMessage.ROLE_SYSTEM, api.get(0).getRole(), "первым идёт system");
        T.eq(ChatMessage.ROLE_USER, api.get(1).getRole(), "затем user");
        T.eq("вопрос 1", api.get(1).getContent(), "содержимое user");
        T.eq(ChatMessage.ROLE_ASSISTANT, api.get(2).getRole(), "затем assistant");
        T.eq(ChatMessage.ROLE_USER, api.get(3).getRole(), "и последний user");

        List<ChatMessage> withoutSystem = history.buildApiMessages("");
        T.eq(3, withoutSystem.size(), "пустая инструкция не добавляется");

        T.check(history.totalChars() == "вопрос 1".length() + "ответ 1".length() + "вопрос 2".length(),
            "totalChars считает символы");

        T.section("История чата: обрезка по объёму");
        ChatHistory small = new ChatHistory(30);
        small.add(ChatMessage.user(rep("a", 20)));
        small.add(ChatMessage.assistant(rep("b", 20)));
        T.eq(1, small.size(), "старое сообщение вытеснено");
        T.eq(rep("b", 20), small.messages().get(0).getContent(), "осталось последнее");
        small.add(ChatMessage.user(rep("c", 20)));
        small.add(ChatMessage.assistant(rep("d", 20)));
        T.check(small.totalChars() <= 30, "объём не превышает предел");
        T.eq(rep("d", 20), small.messages().get(small.size() - 1).getContent(),
            "последнее сообщение сохраняется всегда");

        ChatHistory single = new ChatHistory(10);
        single.add(ChatMessage.user(rep("x", 50)));
        T.eq(1, single.size(), "единственное длинное сообщение не удаляется");

        small.clear();
        T.eq(0, small.size(), "clear очищает историю");
    }

    private static String rep(String s, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(s);
        }
        return sb.toString();
    }
}
