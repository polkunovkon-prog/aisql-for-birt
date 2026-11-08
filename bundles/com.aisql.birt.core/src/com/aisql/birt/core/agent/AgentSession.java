/*
 * AI BIRT — агентная сессия: цикл «модель ⇄ инструменты» (ADR-0003).
 *
 * Порядок шагов:
 *   1) ТЗ пользователя: проверка на инъекции (strict — отказ),
 *      маскирование ПДн, забор (fence);
 *   2) цикл до maxIterations (политика может сузить):
 *        запрос к модели с tools[];
 *        вызовы (штатные tool_calls или текстовый протокол TOOL …);
 *        каждый вызов — через ToolRegistry.dispatch (политика, лимиты);
 *        результат — сообщение role:"tool" обратно модели;
 *        report_done завершает цикл;
 *   3) итог: текст модели для чата + сохранённый файл (если report_done).
 *
 * Каждый шаг журналируется (AuditLog с цепочкой хэшей и syslog).
 *
 * Для офлайн-тестов LLM отделена интерфейсом AgentLlm.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core.agent;

import com.aisql.birt.core.AuditLog;
import com.aisql.birt.core.BirtSettings;
import com.aisql.birt.core.ChatMessage;
import com.aisql.birt.core.Json;
import com.aisql.birt.core.LlmClient;
import com.aisql.birt.core.security.BirtEnterprisePolicy;
import com.aisql.birt.core.security.PiiDetector;
import com.aisql.birt.core.security.PromptInjectionDefense;
import com.aisql.birt.core.tools.ToolCall;
import com.aisql.birt.core.tools.ToolContext;
import com.aisql.birt.core.tools.ToolRegistry;
import com.aisql.birt.core.tools.ToolResult;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Агентный цикл сборки/правки отчёта.
 */
public final class AgentSession {

    /** Абстракция LLM для тестов (production — LlmClientAdapter). */
    public interface AgentLlm {

        LlmClient.LlmResponse chat(List<ChatMessage> messages, List<Object> tools)
            throws LlmClient.LlmException;
    }

    /** Адаптер продакшн-клиента к AgentLlm. */
    public static final class LlmClientAdapter implements AgentLlm {

        private final LlmClient client;
        private final boolean sendTools;

        public LlmClientAdapter(LlmClient client, boolean sendTools) {
            this.client = client;
            this.sendTools = sendTools;
        }

        public LlmClient.LlmResponse chat(List<ChatMessage> messages, List<Object> tools)
                throws LlmClient.LlmException {
            return client.chatWithTools(messages, tools,
                BirtSettings.temperature(), BirtSettings.maxTokens(), sendTools);
        }
    }

    /** Итог агентного запуска. */
    public static final class AgentResult {

        /** Финальный текст модели для пользователя. */
        public final String message;
        /** Сохранённый файл (report_done) или null. */
        public final File savedFile;
        /** Число итераций цикла. */
        public final int iterations;
        /** Число вызовов инструментов. */
        public final int toolCalls;
        /** Число ошибок инструментов. */
        public final int toolErrors;
        /** Отказ (инъекция/политика/лимит) — текст для пользователя. */
        public final String refusal;

        AgentResult(String message, File savedFile, int iterations,
                int toolCalls, int toolErrors, String refusal) {
            this.message = message;
            this.savedFile = savedFile;
            this.iterations = iterations;
            this.toolCalls = toolCalls;
            this.toolErrors = toolErrors;
            this.refusal = refusal;
        }

        public boolean ok() {
            return refusal == null;
        }
    }

    private final AgentLlm llm;
    private final ToolRegistry registry;
    private final ToolContext ctx;
    private final int maxIterations;
    private final boolean piiMasking;

    public AgentSession(AgentLlm llm, ToolRegistry registry, ToolContext ctx) {
        this.llm = llm;
        this.registry = registry;
        this.ctx = ctx;
        this.maxIterations = BirtEnterprisePolicy.maxIterations(
            BirtSettings.agentMaxIterations());
        this.piiMasking = BirtEnterprisePolicy.piiMaskingEnabled();
    }

    /**
     * Запускает агентный цикл по ТЗ. Блокирующий вызов — из фонового потока.
     */
    public AgentResult run(String tz) {
        long started = System.currentTimeMillis();

        // 1. Защита входа: инъекции + ПДн + забор.
        PromptInjectionDefense.Report injection = PromptInjectionDefense.detect(tz);
        if (PromptInjectionDefense.isStrict() && injection.hijack) {
            AuditLog.append("agent", "deny", "DENY", -1, null, null,
                "инъекция в ТЗ: " + injection.describe() + " (режим strict)");
            return new AgentResult(null, null, 0, 0, 0,
                "Обнаружены признаки промпт-инъекции в задании ("
                    + injection.describe() + "). Выполнение заблокировано"
                    + " (режим strict). Уточните задание.");
        }
        if (injection.found && PromptInjectionDefense.isEnabled()) {
            AuditLog.append("agent", "flag", "OK", -1, null, null,
                "признаки инъекции в ТЗ: " + injection.describe());
        }
        String safeTz = tz == null ? "" : tz;
        int masked = 0;
        if (piiMasking) {
            PiiDetector.MaskResult r = PiiDetector.maskText(safeTz);
            safeTz = r.text;
            masked = r.masked;
        }
        if (masked > 0) {
            AuditLog.append("agent", "mask", "OK", -1, null, null,
                "замаскировано ПДн в ТЗ: " + masked);
        }
        String fenced = PromptInjectionDefense.fence("техзадание пользователя", safeTz);

        // 2. Цикл.
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(AgentPrompts.systemInstruction()));
        messages.add(ChatMessage.user(AgentPrompts.userMessage(fenced)));

        List<Object> tools = registry.openAiTools();
        int iterations = 0;
        int calls = 0;
        int errors = 0;
        File saved = null;

        while (iterations < maxIterations) {
            iterations++;
            LlmClient.LlmResponse resp;
            try {
                resp = llm.chat(messages, tools);
            } catch (LlmClient.LlmException e) {
                AuditLog.append("agent", "error", "ERROR",
                    System.currentTimeMillis() - started, null,
                    e.getMessage(), "итерация " + iterations);
                return new AgentResult(null, saved, iterations, calls, errors,
                    "Ошибка ИИ-сервера: " + e.getMessage());
            }

            List<ToolCall> toolCalls = resp.allToolCalls();
            if (toolCalls.isEmpty()) {
                // Модель закончила текстом.
                AuditLog.append("agent", "done", "OK",
                    System.currentTimeMillis() - started, null, null,
                    "итераций " + iterations + ", вызовов " + calls
                        + ", ошибок " + errors);
                return new AgentResult(resp.content, saved, iterations, calls,
                    errors, null);
            }

            messages.add(ChatMessage.assistantWithTools(resp.content,
                resp.toolCalls));
            boolean done = false;
            for (ToolCall call : toolCalls) {
                calls++;
                long t0 = System.currentTimeMillis();
                ToolResult result = registry.dispatch(call, ctx);
                long ms = System.currentTimeMillis() - t0;
                if (!result.ok) {
                    errors++;
                }
                AuditLog.append("tool." + call.getName(),
                    result.ok ? "ok" : "error",
                    result.ok ? "OK" : "ERROR", ms, null,
                    result.ok ? null : result.summary(),
                    "итерация " + iterations);
                messages.add(ChatMessage.toolResult(call.getId(), result.content()));
                if (ctx.listener != null) {
                    try {
                        ctx.listener.onTool(call.getName(), result, calls, -1);
                    } catch (RuntimeException ignore) {
                        // прогресс не должен ломать цикл
                    }
                }
                if ("report_done".equals(call.getName()) && result.ok) {
                    done = true;
                    Map<String, Object> payload = parseJson(result.content());
                    Object file = payload.get("file");
                    if (file instanceof String) {
                        saved = new File((String) file);
                    }
                }
            }
            if (done) {
                AuditLog.append("agent", "done", "OK",
                    System.currentTimeMillis() - started, null, null,
                    "итераций " + iterations + ", вызовов " + calls
                        + ", ошибок " + errors);
                String message = resp.content == null || resp.content.trim().isEmpty()
                    ? "Отчёт собран." : resp.content.trim();
                return new AgentResult(message, saved, iterations, calls,
                    errors, null);
            }
        }

        // Лимит итераций: если дизайн открыт и что-то сделано — сохраняем
        // как есть (best effort), пользователь решит, что с этим делать.
        if (ctx.session.isOpen() && ctx.session.opCount() > 0 && ctx.targetFile != null) {
            try {
                saved = ctx.session.saveAtomically(ctx.targetFile);
            } catch (Exception e) {
                AuditLog.append("agent", "error", "ERROR", -1, null,
                    e.getMessage(), "сохранение после лимита итераций");
            }
        }
        AuditLog.append("agent", "limit", "ERROR",
            System.currentTimeMillis() - started, null, null,
            "превышен лимит итераций (" + maxIterations + ")");
        return new AgentResult("Достигнут лимит итераций (" + maxIterations
            + "). Сделано вызовов: " + calls + " (ошибок " + errors
            + "). Промежуточный результат сохранён — проверьте отчёт"
            + (saved == null ? " вручную" : ": " + saved.getAbsolutePath()) + ".",
            saved, iterations, calls, errors, null);
    }

    private static Map<String, Object> parseJson(String content) {
        try {
            return Json.asObj(Json.parse(content));
        } catch (RuntimeException e) {
            return Json.obj();
        }
    }
}
