/*
 * AI BIRT — тесты текста схемы БД (DbSchema.toText).
 *
 * Настоящая база не нужна: проверяется формат текста, который уходит
 * в ИИ-модель, — только имена таблиц и колонок, без строк данных.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.tests;

import com.aisql.birt.core.jdbc.DbSchema;

import java.util.Arrays;
import java.util.List;

/** Тесты текстовой формы схемы базы данных. */
public final class DbSchemaTest {

    private DbSchemaTest() {
    }

    public static void run() throws Exception {
        T.section("Схема БД (метаданные: текст для модели)");

        List<DbSchema.Column> studentsColumns = Arrays.asList(
            new DbSchema.Column("id", "int4"),
            new DbSchema.Column("last_name", "varchar"),
            new DbSchema.Column("house_name", "varchar"));
        List<DbSchema.Table> tables = Arrays.asList(
            new DbSchema.Table("public", "students", "table", studentsColumns),
            new DbSchema.Table("public", "v_students", "view", studentsColumns),
            new DbSchema.Table("public", "houses", "table",
                Arrays.asList(new DbSchema.Column("code", "bpchar"))));
        DbSchema.Schema schema = new DbSchema.Schema("hogwarts", "PostgreSQL 14.5", tables);

        String text = DbSchema.toText(schema,
            DbSchema.DEFAULT_MAX_TABLES, DbSchema.DEFAULT_MAX_COLUMNS);
        T.check(text.contains("только метаданные"), "текст: пометка «только метаданные»");
        T.check(text.contains("PostgreSQL 14.5"), "текст: СУБД и версия");
        T.check(text.contains("база hogwarts"), "текст: имя базы");
        T.check(text.contains("Таблиц и представлений: 3"), "текст: число объектов");
        T.check(text.contains("public.students(id int4, last_name varchar"),
            "текст: колонки таблицы");
        T.check(text.contains("public.v_students (view)("), "текст: представление помечено");

        // Ограничения объёма: число таблиц и колонок.
        String limited = DbSchema.toText(schema, 2, 1);
        T.check(limited.contains("показаны первые 2"), "ограничение: число таблиц");
        T.check(limited.contains("… ещё 2"), "ограничение: усечение списка колонок");
        T.check(!limited.contains("houses"), "ограничение: лишние таблицы не показаны");

        // Данные не попадают в текст по построению: строки не передаются.
        T.check(!text.contains("Гарри") && !text.contains("select"),
            "текст: ни строк данных, ни запросов");
    }
}
