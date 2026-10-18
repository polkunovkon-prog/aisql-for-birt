/*
 * AI BIRT — схема базы данных (только метаданные).
 *
 * В ИИ-модель уходят лишь имена таблиц и колонок — никаких строк данных.
 * Это соответствует правилу безопасности плагина (см. docs/SECURITY.md):
 * модель получает структуру, но не содержимое базы.
 *
 * Чтение — через штатный JDBC DatabaseMetaData, драйвером из комплекта
 * плагина (PostgreSQL 42.7.3, поддержка SCRAM-SHA-256).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Чтение схемы базы данных (имена таблиц/колонок) для контекста модели. */
public final class DbSchema {

    /** Пределы по умолчанию — чтобы текст не разрастался. */
    public static final int DEFAULT_MAX_TABLES = 60;
    public static final int DEFAULT_MAX_COLUMNS = 40;

    private DbSchema() {
    }

    /** Колонка таблицы. */
    public static final class Column {
        private final String name;
        private final String type;

        public Column(String name, String type) {
            this.name = name;
            this.type = type;
        }

        public String name() { return name; }
        public String type() { return type; }
    }

    /** Таблица или представление. */
    public static final class Table implements Comparable<Table> {
        private final String schema;
        private final String name;
        private final String type;
        private final List<Column> columns;

        public Table(String schema, String name, String type, List<Column> columns) {
            this.schema = schema == null ? "" : schema;
            this.name = name;
            this.type = type;
            this.columns = Collections.unmodifiableList(columns);
        }

        public String schema() { return schema; }
        public String name() { return name; }
        public String type() { return type; }
        public List<Column> columns() { return columns; }

        @Override
        public int compareTo(Table other) {
            int c = schema.compareTo(other.schema);
            return c != 0 ? c : name.compareTo(other.name);
        }
    }

    /** Вся прочитанная схема. */
    public static final class Schema {
        private final String database;
        private final String product;
        private final List<Table> tables;

        public Schema(String database, String product, List<Table> tables) {
            this.database = database == null ? "" : database;
            this.product = product == null ? "" : product;
            this.tables = Collections.unmodifiableList(tables);
        }

        public String database() { return database; }
        public String product() { return product; }
        public List<Table> tables() { return tables; }
    }

    /** Читает схему: таблицы, представления и их колонки (без данных). */
    public static Schema read(String url, String user, String password) throws Exception {
        Connection connection = PgJdbcConnectionFactory.open(url, user, password);
        try {
            DatabaseMetaData md = connection.getMetaData();
            String product = safe(md.getDatabaseProductName()) + " " + firstToken(safe(md.getDatabaseProductVersion()));
            String database;
            try {
                database = connection.getCatalog();
            } catch (Throwable t) {
                database = null;
            }
            List<Table> tables = new ArrayList<Table>();
            ResultSet rs = md.getTables(null, null, "%", new String[] {"TABLE", "VIEW"});
            try {
                while (rs.next()) {
                    String schemaName = safe(rs.getString("TABLE_SCHEM"));
                    if (isSystemSchema(schemaName)) {
                        continue;
                    }
                    String name = safe(rs.getString("TABLE_NAME"));
                    if (name.isEmpty()) {
                        continue;
                    }
                    String type = "VIEW".equalsIgnoreCase(safe(rs.getString("TABLE_TYPE")))
                        ? "view" : "table";
                    List<Column> columns = readColumns(md, schemaName, name);
                    if (!columns.isEmpty()) {
                        tables.add(new Table(schemaName, name, type, columns));
                    }
                }
            } finally {
                rs.close();
            }
            Collections.sort(tables);
            return new Schema(database, product.trim(), tables);
        } finally {
            try {
                connection.close();
            } catch (Throwable ignored) {
                // закрытие соединения не критично
            }
        }
    }

    /** Читает схему и сразу формирует текст для модели. */
    public static String readText(String url, String user, String password) throws Exception {
        return toText(read(url, user, password), DEFAULT_MAX_TABLES, DEFAULT_MAX_COLUMNS);
    }

    /** Текстовая форма схемы (компактно, с ограничениями по объёму). */
    public static String toText(Schema schema, int maxTables, int maxColumns) {
        StringBuilder sb = new StringBuilder();
        sb.append("Схема БД (только метаданные — без данных):\n");
        sb.append("СУБД: ").append(schema.product());
        if (!schema.database().isEmpty()) {
            sb.append(", база ").append(schema.database());
        }
        sb.append('\n');
        int total = schema.tables().size();
        int shown = Math.min(total, maxTables);
        sb.append("Таблиц и представлений: ").append(total);
        if (shown < total) {
            sb.append(" (показаны первые ").append(shown).append(")");
        }
        sb.append('\n');
        for (int i = 0; i < shown; i++) {
            Table t = schema.tables().get(i);
            sb.append(t.schema().isEmpty() ? t.name() : t.schema() + "." + t.name());
            if ("view".equals(t.type())) {
                sb.append(" (view)");
            }
            sb.append('(');
            int cols = Math.min(t.columns().size(), maxColumns);
            for (int j = 0; j < cols; j++) {
                if (j > 0) {
                    sb.append(", ");
                }
                Column c = t.columns().get(j);
                sb.append(c.name()).append(' ').append(c.type());
            }
            if (t.columns().size() > cols) {
                sb.append(", … ещё ").append(t.columns().size() - cols);
            }
            sb.append(")\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------

    private static List<Column> readColumns(DatabaseMetaData md, String schema, String table) {
        List<Column> columns = new ArrayList<Column>();
        try {
            ResultSet rs = md.getColumns(null, schema.isEmpty() ? null : schema, table, "%");
            try {
                while (rs.next()) {
                    String name = safe(rs.getString("COLUMN_NAME"));
                    if (name.isEmpty()) {
                        continue;
                    }
                    String type = safe(rs.getString("TYPE_NAME")).trim().toLowerCase();
                    columns.add(new Column(name, type));
                }
            } finally {
                rs.close();
            }
        } catch (Throwable ignored) {
            // колонки отдельной таблицы не критичны
        }
        return columns;
    }

    private static boolean isSystemSchema(String schema) {
        if (schema == null || schema.isEmpty()) {
            return false;
        }
        String s = schema.toLowerCase();
        return "pg_catalog".equals(s) || "information_schema".equals(s)
            || s.startsWith("pg_toast");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String firstToken(String s) {
        int space = s.indexOf(' ');
        return space < 0 ? s : s.substring(0, space);
    }
}
