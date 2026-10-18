/*
 * AI BIRT — связь «собранный отчёт → его спецификация».
 *
 * Каждый успешно собранный плагином отчёт запоминается в
 * ~/.ai-birt/reports-built.json: путь, время изменения, размер и
 * JSON-спецификация. Это позволяет править отчёт по замечанию и после
 * перезапуска Eclipse: спецификация находится по файлу, если он не был
 * изменён вручную (время и размер совпадают). Ошибки кэша не должны
 * мешать работе — они игнорируются.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core.report;

import com.aisql.birt.core.BirtSettings;
import com.aisql.birt.core.Json;
import com.aisql.birt.core.spec.ReportSpec;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Кэш спецификаций собранных отчётов (для правки по замечанию). */
public final class BuiltReports {

    /** Имя файла кэша в каталоге настроек (~/.ai-birt). */
    public static final String FILE_NAME = "reports-built.json";

    private static final Object LOCK = new Object();

    private BuiltReports() {
    }

    /** Запоминает спецификацию, из которой собран файл отчёта. */
    public static void remember(File report, ReportSpec spec) {
        if (report == null || spec == null || !report.isFile()) {
            return;
        }
        String path = canonical(report);
        if (path == null) {
            return;
        }
        Object specObject;
        try {
            specObject = Json.parse(spec.toJson());
        } catch (Throwable t) {
            return;
        }
        synchronized (LOCK) {
            Map<String, Object> reports = loadAll();
            prune(reports);
            Map<String, Object> entry = Json.obj();
            entry.put("mtime", Long.valueOf(report.lastModified()));
            entry.put("size", Long.valueOf(report.length()));
            entry.put("spec", specObject);
            reports.put(path, entry);
            store(reports);
        }
    }

    /**
     * Возвращает спецификацию файла, если он собран плагином и с тех пор
     * не изменялся (совпадают время изменения и размер). Иначе null.
     */
    public static ReportSpec lookup(File report) {
        if (report == null || !report.isFile()) {
            return null;
        }
        String path = canonical(report);
        if (path == null) {
            return null;
        }
        synchronized (LOCK) {
            Map<String, Object> entry = Json.asObj(loadAll().get(path));
            if (entry == null) {
                return null;
            }
            if (asLong(entry.get("mtime")) != report.lastModified()
                    || asLong(entry.get("size")) != report.length()) {
                return null;
            }
            Object specObject = entry.get("spec");
            if (specObject == null) {
                return null;
            }
            try {
                return ReportSpec.parse(Json.write(specObject));
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /** Забывает файл отчёта (если он удалён или больше не нужен). */
    public static void forget(File report) {
        if (report == null) {
            return;
        }
        String path = canonical(report);
        if (path == null) {
            return;
        }
        synchronized (LOCK) {
            Map<String, Object> reports = loadAll();
            if (reports.remove(path) != null) {
                store(reports);
            }
        }
    }

    /** Файл кэша (для диагностики и настроек). */
    public static File cacheFile() {
        return BirtSettings.configDir().resolve(FILE_NAME).toFile();
    }

    // ------------------------------------------------------------------

    private static Map<String, Object> loadAll() {
        Path file = BirtSettings.configDir().resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return Json.obj();
        }
        try {
            Object root = Json.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            Map<String, Object> map = Json.asObj(root);
            if (map == null) {
                return Json.obj();
            }
            Map<String, Object> reports = Json.asObj(map.get("reports"));
            return reports == null ? Json.obj() : reports;
        } catch (Throwable t) {
            return Json.obj();
        }
    }

    private static void store(Map<String, Object> reports) {
        try {
            Path dir = BirtSettings.configDir();
            Files.createDirectories(dir);
            Map<String, Object> root = Json.obj();
            root.put("reports", reports);
            Path file = dir.resolve(FILE_NAME);
            Path tmp = dir.resolve(FILE_NAME + ".tmp");
            Writer w = new OutputStreamWriter(Files.newOutputStream(tmp), StandardCharsets.UTF_8);
            try {
                w.write(Json.write(root));
            } finally {
                w.close();
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable ignored) {
            // кэш не должен ломать сборку отчёта
        }
    }

    /** Убирает записи об отсутствующих и изменённых файлах. */
    private static void prune(Map<String, Object> reports) {
        List<String> remove = new ArrayList<String>();
        for (Map.Entry<String, Object> e : reports.entrySet()) {
            File f = new File(e.getKey());
            Map<String, Object> entry = Json.asObj(e.getValue());
            if (!f.isFile() || entry == null
                    || asLong(entry.get("mtime")) != f.lastModified()
                    || asLong(entry.get("size")) != f.length()) {
                remove.add(e.getKey());
            }
        }
        for (String key : remove) {
            reports.remove(key);
        }
    }

    private static long asLong(Object o) {
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        return -1;
    }

    private static String canonical(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }
}
