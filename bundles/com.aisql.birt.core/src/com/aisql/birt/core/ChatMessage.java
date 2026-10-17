/*
 * AI BIRT — сообщение чата (роль + содержимое).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core;

/** Неизменяемое сообщение чата в формате OpenAI Chat Completions. */
public final class ChatMessage {

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    private final String role;
    private final String content;

    public ChatMessage(String role, String content) {
        this.role = role == null ? ROLE_USER : role;
        this.content = content == null ? "" : content;
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(ROLE_SYSTEM, content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(ROLE_USER, content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(ROLE_ASSISTANT, content);
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    @Override
    public String toString() {
        return role + ": " + content;
    }
}
