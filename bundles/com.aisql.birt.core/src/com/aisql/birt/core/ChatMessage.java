/*
 * AI BIRT — сообщение чата (роль + содержимое + вызовы инструментов).
 *
 * Расширяет формат OpenAI Chat Completions: помимо обычных сообщений
 * (system/user/assistant) поддерживается роль «tool» с tool_call_id
 * и ответ ассистента со списком tool_calls. Для обычного чата
 * используются те же классы — дополнительные поля просто пусты.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core;

import com.aisql.birt.core.tools.ToolCall;

import java.util.ArrayList;
import java.util.List;

/** Сообщение диалога с моделью (совместимо с Chat Completions). */
public final class ChatMessage {

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL = "tool";

    private final String role;
    private final String content;
    /** Для роли tool: идентификатор вызова, к которому относится результат. */
    private final String toolCallId;
    /** Для роли assistant: вызовы инструментов, запрошенные моделью. */
    private final List<ToolCall> toolCalls;

    public ChatMessage(String role, String content) {
        this(role, content, null, null);
    }

    private ChatMessage(String role, String content, String toolCallId, List<ToolCall> toolCalls) {
        this.role = role == null ? ROLE_USER : role;
        this.content = content == null ? "" : content;
        this.toolCallId = toolCallId;
        this.toolCalls = toolCalls == null
            ? new ArrayList<ToolCall>() : new ArrayList<ToolCall>(toolCalls);
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

    /** Ответ ассистента с вызовами инструментов (content может быть пустым). */
    public static ChatMessage assistantWithTools(String content, List<ToolCall> calls) {
        return new ChatMessage(ROLE_ASSISTANT, content, null, calls);
    }

    /** Результат инструмента для возврата модели. */
    public static ChatMessage toolResult(String toolCallId, String content) {
        return new ChatMessage(ROLE_TOOL, content, toolCallId, null);
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    @Override
    public String toString() {
        return role + ": " + content;
    }
}
