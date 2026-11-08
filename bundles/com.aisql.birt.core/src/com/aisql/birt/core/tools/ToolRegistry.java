/*
 * AI BIRT — реестр и диспетчер инструментов агентного режима.
 *
 * Держит полный список инструментов (ReportTools), сериализует их
 * в формат OpenAI (tools) и диспетчеризирует вызовы с политикой
 * предприятия: отключённые политикой инструменты (tools.disabled,
 * fail-closed при сломанной политике) возвращают ошибку без
 * исполнения; unknown-инструмент — тоже ошибка для модели.
 *
 * Complete mediation (OWASP LLM06): НИКАКОЙ вызов не исполняется мимо
 * dispatch().
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core.tools;

import com.aisql.birt.core.AuditLog;
import com.aisql.birt.core.Json;
import com.aisql.birt.core.security.BirtEnterprisePolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Реестр инструментов + диспетчеризация с проверками. */
public final class ToolRegistry {

    private final Map<String, ReportTool> tools = new LinkedHashMap<String, ReportTool>();

    public ToolRegistry() {
        registerAll(new com.aisql.birt.core.tools.ReportTools());
    }

    private void registerAll(ReportTools all) {
        for (ReportTool t : all.tools()) {
            tools.put(t.name(), t);
        }
    }

    /** Список имён инструментов. */
    public List<String> names() {
        return new ArrayList<String>(tools.keySet());
    }

    public ReportTool find(String name) {
        return name == null ? null : tools.get(name);
    }

    /**
     * Описания инструментов в формате OpenAI tools. Инструменты,
     * отключённые политикой, в список не попадают (модель их не видит).
     */
    public List<Object> openAiTools() {
        List<Object> out = Json.arr();
        for (ReportTool t : tools.values()) {
            if (BirtEnterprisePolicy.toolDisabled(t.name())) {
                continue;
            }
            Map<String, Object> fn = Json.obj();
            fn.put("name", t.name());
            fn.put("description", t.description());
            Map<String, Object> schema = Json.obj();
            schema.put("type", "object");
            Map<String, Object> props = t.properties();
            if (props != null && !props.isEmpty()) {
                schema.put("properties", props);
            }
            List<String> required = t.required();
            if (required != null && !required.isEmpty()) {
                schema.put("required", required);
            }
            fn.put("parameters", schema);
            Map<String, Object> item = Json.obj();
            item.put("type", "function");
            item.put("function", fn);
            out.add(item);
        }
        return out;
    }

    /**
     * Исполняет вызов инструмента с полным набором проверок.
     * Ошибки возвращаются моделью как ToolResult (isError), не исключением.
     */
    public ToolResult dispatch(ToolCall call, ToolContext ctx) {
        if (call == null) {
            return ToolResult.error("пустой вызов инструмента");
        }
        ReportTool tool = tools.get(call.getName());
        if (tool == null) {
            return ToolResult.error("неизвестный инструмент «" + call.getName()
                + "»; доступные: " + String.join(", ", enabledNames()));
        }
        if (BirtEnterprisePolicy.toolDisabled(call.getName())) {
            AuditLog.append("tool." + call.getName(), "deny", "DENY", -1, null, null,
                "инструмент отключён политикой предприятия (fail-closed)");
            return ToolResult.error("инструмент «" + call.getName()
                + "» отключён политикой предприятия. Обратитесь к администратору.");
        }
        Map<String, Object> args = call.parsedArgs();
        if (args == null) {
            return ToolResult.error("аргументы не являются JSON-объектом: "
                + call.getArgsJson());
        }
        try {
            ToolResult r = tool.execute(args, ctx);
            if (r == null) {
                r = ToolResult.error("инструмент не вернул результат");
            }
            return r;
        } catch (RuntimeException e) {
            return ToolResult.error("внутренняя ошибка инструмента: " + safeMsg(e));
        }
    }

    private List<String> enabledNames() {
        List<String> out = new ArrayList<String>();
        for (ReportTool t : tools.values()) {
            if (!BirtEnterprisePolicy.toolDisabled(t.name())) {
                out.add(t.name());
            }
        }
        return out;
    }

    private static String safeMsg(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
