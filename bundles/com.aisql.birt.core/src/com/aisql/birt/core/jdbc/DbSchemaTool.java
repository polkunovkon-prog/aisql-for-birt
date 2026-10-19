/*
 * AI BIRT — консольный инструмент «схема БД» (для скриптов приёмки).
 *
 * Печатает текст схемы базы данных (только метаданные — без данных).
 * Пароль берётся из переменной окружения AIBIRT_TEST_DB_PASSWORD или
 * системного свойства aibirt.test.dbPassword (в командной строке пароль
 * не передаётся, чтобы не попадал в историю).
 *
 * Использование:
 *   java … com.aisql.birt.core.jdbc.DbSchemaTool [url] [пользователь] [драйвер]
 *
 * Драйвер необязателен: по умолчанию PostgreSQL (драйвер из комплекта
 * плагина), для других СУБД укажите класс драйвера, например
 * com.mysql.cj.jdbc.Driver или org.h2.Driver.
 *
 * Коды возврата: 0 — успех, 1 — ошибка подключения/чтения.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core.jdbc;

/** Печать схемы БД (метаданные) для приёмочных скриптов. */
public final class DbSchemaTool {

    private DbSchemaTool() {
    }

    public static void main(String[] args) {
        String url = args.length > 0 ? args[0] : "jdbc:postgresql://127.0.0.1:5432/hogwarts";
        String user = args.length > 1 ? args[1] : "agent_user";
        String driver = args.length > 2 ? args[2] : "";
        String password = System.getenv("AIBIRT_TEST_DB_PASSWORD");
        if (password == null) {
            password = System.getProperty("aibirt.test.dbPassword", "");
        }
        int code = 0;
        try {
            System.out.println(DbSchema.readText(driver, url, user, password));
        } catch (Throwable t) {
            System.out.println("ОШИБКА чтения схемы: " + t.getMessage());
            code = 1;
        }
        System.out.flush();
        System.exit(code);
    }
}
