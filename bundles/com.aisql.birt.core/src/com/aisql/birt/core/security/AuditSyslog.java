/*
 * AI BIRT — выгрузка событий аудита в syslog (SIEM).
 *
 * Сообщения в стиле RFC 5424 передаются по UDP:
 *   <PRI>VERSION TIMESTAMP HOSTNAME APP-NAME PROCID MSGID [STRUCTURED-DATA] MSG
 *
 * PRI рассчитывается из facility (по умолчанию 16 — local0) и severity
 * ( informational для обычных событий, warning для отказов и блокировок).
 * STRUCTURED-DATA содержит машиночитаемые поля (session, function, outcome,
 * decision), MSG — краткое человекочитаемое описание. Секреты в выгрузку
 * не попадают (вызывающий код передаёт только безопасные поля).
 *
 * Доставка — best-effort: ошибка отправки не ломает основную работу,
 * попадает в лог Eclipse. Для защищённого транспорта syslog по TLS
 * (RFC 5425) используйте локальный релей (rsyslog) — плагин отдаёт
 * сообщение на localhost.
 *
 * Настройки: audit.syslog.enabled/host/port/app (могут быть закреплены
 * политикой предприятия).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core.security;

import com.aisql.birt.core.BirtSettings;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Отправка событий аудита в syslog по UDP (RFC 5424-подобный формат).
 */
public final class AuditSyslog {

    public static final int DEFAULT_PORT = 514;
    public static final String DEFAULT_APP = "ai-birt";
    /** facility local0 (16) × 8 + severity informational (6) = 134. */
    private static final int FACILITY = 16;

    private static final Logger LOG = Logger.getLogger(AuditSyslog.class.getName());

    private AuditSyslog() {
    }

    // ----------------------------------------------------------------------
    //  Настройки (политика приоритетна)
    // ----------------------------------------------------------------------

    public static boolean enabled() {
        BirtEnterprisePolicy.Snapshot p = BirtEnterprisePolicy.get();
        String v = p.raw(BirtEnterprisePolicy.KEY_AUDIT_SYSLOG_ENABLED);
        if (v != null) {
            return "true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim());
        }
        return Boolean.parseBoolean(BirtSettings.value("audit.syslog.enabled", "false").trim());
    }

    public static String host() {
        BirtEnterprisePolicy.Snapshot p = BirtEnterprisePolicy.get();
        String v = p.raw(BirtEnterprisePolicy.KEY_AUDIT_SYSLOG_HOST);
        if (v != null && !v.isEmpty()) {
            return v;
        }
        return BirtSettings.value("audit.syslog.host", "").trim();
    }

    public static int port() {
        BirtEnterprisePolicy.Snapshot p = BirtEnterprisePolicy.get();
        String v = p.raw(BirtEnterprisePolicy.KEY_AUDIT_SYSLOG_PORT);
        if (v != null) {
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException ignore) {
                // fallback на пользовательскую настройку
            }
        }
        try {
            return Integer.parseInt(BirtSettings.value("audit.syslog.port",
                String.valueOf(DEFAULT_PORT)).trim());
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }
    }

    public static String app() {
        BirtEnterprisePolicy.Snapshot p = BirtEnterprisePolicy.get();
        String v = p.raw(BirtEnterprisePolicy.KEY_AUDIT_SYSLOG_APP);
        if (v != null && !v.isEmpty()) {
            return v;
        }
        return BirtSettings.value("audit.syslog.app", DEFAULT_APP).trim();
    }

    // ----------------------------------------------------------------------
    //  Отправка
    // ----------------------------------------------------------------------

    /**
     * Отправляет событие в syslog. Никогда не бросает исключений.
     *
     * @param severity 6=info, 4=warning, 2=critical (см. RFC 5424)
     * @param msgId    идентификатор типа события (ASCII, например TOOL)
     * @param sd       машиночитаемые поля «ключ=значение» (уже безопасные)
     * @param message  человекочитаемое сообщение (секреты исключены)
     */
    public static void send(int severity, String msgId, String sd, String message) {
        if (!enabled()) {
            return;
        }
        String hostName = host();
        if (hostName.isEmpty()) {
            return;
        }
        try {
            String packet = format(severity, msgId, sd, message);
            byte[] bytes = packet.getBytes(StandardCharsets.UTF_8);
            InetAddress address = InetAddress.getByName(hostName);
            DatagramSocket socket = new DatagramSocket();
            try {
                socket.send(new DatagramPacket(bytes, bytes.length, address, port()));
            } finally {
                socket.close();
            }
        } catch (IOException | RuntimeException e) {
            // доставка best-effort: ошибка не должна ломать основную работу
            LOG.log(Level.WARNING, "syslog: не удалось отправить событие на "
                + hostName + ":" + port() + " — " + e.getMessage(), e);
        }
    }

    /** Собирает сообщение RFC 5424-подобного формата. */
    public static String format(int severity, String msgId, String sd, String message) {
        int pri = FACILITY * 8 + severity;
        String ts = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").format(new Date());
        String host = safeHost();
        String app = sanitize(app(), "-");
        String procid = procId();
        String structured = sd == null || sd.trim().isEmpty() ? "-" : sd;
        String msg = message == null ? "-" : message.replace('\n', ' ');
        return "<" + pri + ">1 " + ts + " " + host + " " + app + " " + procid + " "
            + (msgId == null ? "-" : sanitize(msgId, "-")) + " " + structured + " " + msg;
    }

    /** PID процесса (Java 8 совместимо; при сбое — прочерк). */
    private static String procId() {
        try {
            String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            int at = name.indexOf('@');
            if (at > 0) {
                return name.substring(0, at);
            }
        } catch (RuntimeException ignore) {
            // прочерк
        }
        return "-";
    }

    private static String safeHost() {
        try {
            String h = InetAddress.getLocalHost().getHostName();
            if (h != null && !h.isEmpty()) {
                return sanitize(h, "localhost");
            }
        } catch (IOException ignore) {
            // остаётся localhost
        }
        return "localhost";
    }

    /** Значение для HEADER/SD: без пробелов, «"», «\» и «]». */
    public static String sanitize(String value, String fallback) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) {
            return fallback;
        }
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length() && sb.length() < 32; i++) {
            char c = v.charAt(i);
            if (c == '"' || c == '\\' || c == ']' || c < 0x21 || c > 0x7e) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        return sb.length() == 0 ? fallback : sb.toString();
    }
}
