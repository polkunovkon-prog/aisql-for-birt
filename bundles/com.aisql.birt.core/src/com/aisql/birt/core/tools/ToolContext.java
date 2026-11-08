/*
 * AI BIRT — контекст выполнения инструментов.
 *
 * Держит DesignSession (дизайн + транзакции), целевой файл, пароль БД
 * (введённый пользователем; модели не передаётся), счётчики защиты
 * и приёмник прогресса для UI.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core.tools;

import com.aisql.birt.core.report.DesignSession;

import java.io.File;

/** Контекст, в котором исполняются инструменты агентной сессии. */
public final class ToolContext {

    public interface ProgressListener {

        /** Событие: инструмент выполнен. */
        void onTool(String toolName, ToolResult result, int index, int total);
    }

    /** Сессия дизайна (обязательна для большинства инструментов). */
    public final DesignSession session;

    /** Куда сохранять результат (report_done); может быть null до выбора. */
    public File targetFile;

    /** Пароль БД от пользователя (в модель не уходит; может быть пуст). */
    public String dbPassword;

    /** Максимум операций мутации в сессии (защита от «шторма» вызовов). */
    public int maxMutations = 200;

    /** Счётчик мутаций (инкрементируется диспетчером). */
    public int mutations;

    /** Приёмник прогресса (UI); может быть null. */
    public ProgressListener listener;

    public ToolContext(DesignSession session, File targetFile, String dbPassword) {
        this.session = session;
        this.targetFile = targetFile;
        this.dbPassword = dbPassword == null ? "" : dbPassword;
    }

    /** Регистрирует мутацию; true — лимит не превышен. */
    public boolean mutationAllowed() {
        return mutations < maxMutations;
    }
}
