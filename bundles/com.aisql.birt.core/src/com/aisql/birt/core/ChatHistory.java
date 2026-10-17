/*
 * AI BIRT — история чата с ограничением объёма.
 *
 * Содержимое истории отправляется модели при каждом запросе, поэтому она
 * обрезается по суммарному числу символов: самые старые сообщения
 * отбрасываются, последнее сообщение сохраняется всегда.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core;

import java.util.ArrayList;
import java.util.List;

public final class ChatHistory {

    /** Разумный предел: у типовых локальных моделей контекст заметно больше. */
    public static final int DEFAULT_MAX_CHARS = 100000;

    private final int maxChars;
    private final List<ChatMessage> messages = new ArrayList<ChatMessage>();

    public ChatHistory() {
        this(DEFAULT_MAX_CHARS);
    }

    public ChatHistory(int maxChars) {
        this.maxChars = maxChars > 0 ? maxChars : DEFAULT_MAX_CHARS;
    }

    public synchronized void add(ChatMessage message) {
        if (message == null) {
            return;
        }
        messages.add(message);
        trim();
    }

    public synchronized void clear() {
        messages.clear();
    }

    public synchronized int size() {
        return messages.size();
    }

    public synchronized int totalChars() {
        int total = 0;
        for (ChatMessage m : messages) {
            total += m.getContent().length();
        }
        return total;
    }

    /** Копия сообщений (без системной инструкции). */
    public synchronized List<ChatMessage> messages() {
        return new ArrayList<ChatMessage>(messages);
    }

    /** Готовый список для API: [system, …история]. */
    public synchronized List<ChatMessage> buildApiMessages(String systemPrompt) {
        List<ChatMessage> out = new ArrayList<ChatMessage>(messages.size() + 1);
        if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
            out.add(ChatMessage.system(systemPrompt.trim()));
        }
        out.addAll(messages);
        return out;
    }

    private void trim() {
        int total = 0;
        for (ChatMessage m : messages) {
            total += m.getContent().length();
        }
        while (total > maxChars && messages.size() > 1) {
            ChatMessage removed = messages.remove(0);
            total -= removed.getContent().length();
        }
    }
}
