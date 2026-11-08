/*
 * AI BIRT — контракт инструмента агентного режима.
 *
 * Каждый инструмент: имя, описание для модели, JSON-Schema параметров
 * (unknown-поля запрещены на уровне разбора) и исполнение над
 * ToolContext. Инструмент НЕ решает сам, можно ли его выполнять:
 * диспетчеризация проходит через ToolRegistry, где действует политика
 * предприятия (fail-closed) и лимиты.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core.tools;

import java.util.List;
import java.util.Map;

/** Один инструмент агента. */
public interface ReportTool {

    /** Имя (латиница/подчёркивание, стабильно для протокола). */
    String name();

    /** Описание для модели (русский). */
    String description();

    /**
     * JSON Schema свойств аргументов: имя свойства → схема
     * ({"type":"string"}, {"type":"number"}, …).
     */
    Map<String, Object> properties();

    /** Обязательные свойства. */
    List<String> required();

    /** Выполнить инструмент. */
    ToolResult execute(Map<String, Object> args, ToolContext ctx);
}
