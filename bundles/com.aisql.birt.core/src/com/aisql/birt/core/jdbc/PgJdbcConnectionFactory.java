/*
 * AI BIRT — подключение PostgreSQL собственным драйвером из комплекта плагина.
 *
 * Штатный драйвер PostgreSQL в составе BIRT 4.7 (drivers/postgresql-42.0.0.jar)
 * не поддерживает SCRAM-SHA-256 (тип аутентификации 10) — а современный
 * PostgreSQL 14+ использует его по умолчанию. Поэтому плагин регистрирует в
 * точке расширения BIRT «org.eclipse.birt.report.data.oda.jdbc.driverinfo»
 * собственную фабрику соединений, которая берёт современный драйвер
 * (lib/postgresql-42.7.3.jar в бандле) и не зависит от каталога drivers/ BIRT.
 *
 * BIRT при этом не патчится: точка расширения — публичный механизм.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core.jdbc;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

import org.eclipse.birt.report.data.oda.jdbc.IConnectionFactory;

/** Фабрика соединений PostgreSQL для JDBC-ODA моста BIRT. */
public class PgJdbcConnectionFactory implements IConnectionFactory {

    /** Драйвер из комплекта плагина (Bundle-ClassPath: lib/postgresql-42.7.3.jar). */
    private static final String PG_DRIVER_CLASS = "org.postgresql.Driver";

    @Override
    public Connection getConnection(String driverClass, String url, Properties props)
            throws SQLException {
        Throwable last = null;

        // 1. Предпочтительно — драйвер из нашего бандла: он поддерживает
        //    SCRAM-SHA-256 и актуальные версии PostgreSQL.
        try {
            Class<?> cls = Class.forName(PG_DRIVER_CLASS, true,
                PgJdbcConnectionFactory.class.getClassLoader());
            Object instance = cls.newInstance();
            if (instance instanceof Driver) {
                Connection connection = ((Driver) instance).connect(url, props);
                if (connection != null) {
                    return connection;
                }
            }
        } catch (Throwable t) {
            last = t;
        }

        // 2. Запасной путь — драйвер, доступный через DriverManager
        //    (например, если комплектный jar почему-то недоступен).
        try {
            Connection connection = DriverManager.getConnection(url, props);
            if (connection != null) {
                return connection;
            }
        } catch (Throwable t) {
            if (last == null) {
                last = t;
            }
        }

        throw new SQLException("Не удалось подключиться к базе данных «" + url
            + "» драйвером " + PG_DRIVER_CLASS
            + (last == null ? "" : ": " + last.getMessage()), last);
    }
}
