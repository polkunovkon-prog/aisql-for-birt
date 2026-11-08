/*
 * AI BIRT — интроспекция дизайна отчёта: структура → JSON для модели.
 *
 * Обходит слоты открытого ReportDesignHandle (источники, наборы,
 * параметры, тело) и собирает компактное описание: имена, типы элементов,
 * ключевые свойства, SQL наборов, колонки. Строки данных не читаются —
 * в модель уходит только структура (SECURITY.md).
 *
 * Тексты надписей и SQL могут содержать недоверенный контент — вызывающий
 * код оборачивает результат в забор PromptInjectionDefense.fence().
 *
 * Используется: инструментом report_inspect (агент) и наполнением
 * манифеста DesignSession при открытии чужого отчёта.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core.report;

import com.aisql.birt.core.Json;
import com.aisql.birt.core.spec.ReportSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.birt.report.model.api.DataSetHandle;
import org.eclipse.birt.report.model.api.DataSourceHandle;
import org.eclipse.birt.report.model.api.DesignElementHandle;
import org.eclipse.birt.report.model.api.ModuleHandle;
import org.eclipse.birt.report.model.api.OdaDataSetHandle;
import org.eclipse.birt.report.model.api.OdaDataSourceHandle;
import org.eclipse.birt.report.model.api.ReportDesignHandle;
import org.eclipse.birt.report.model.api.ScalarParameterHandle;
import org.eclipse.birt.report.model.api.SlotHandle;

/**
 * Структура дизайна в виде компактного JSON.
 */
public final class ReportIntrospector {

    /** Ограничение на длину SQL в интроспекции (защита от раздувания контекста). */
    private static final int MAX_SQL_CHARS = 4000;
    private static final int MAX_TEXT_CHARS = 300;

    private ReportIntrospector() {
    }

    /** JSON-описание дизайна для модели (только структура, без данных). */
    public static String toJson(ReportDesignHandle design, ReportSpec manifest) {
        Map<String, Object> root = Json.obj();
        root.put("file", safeName(design));
        root.put("body_count", Integer.valueOf(design.getBody().getCount()));

        List<Object> sources = Json.arr();
        SlotHandle dsSlot = design.getDataSources();
        for (int i = 0; i < dsSlot.getCount(); i++) {
            DesignElementHandle h = dsSlot.get(i);
            Map<String, Object> m = Json.obj();
            m.put("name", nz(h.getName()));
            if (h instanceof OdaDataSourceHandle) {
                m.put("driver", nz(String.valueOf(
                    h.getProperty("odaDriverClass"))));
                m.put("url", nz(String.valueOf(h.getProperty("odaURL"))));
                m.put("user", nz(String.valueOf(h.getProperty("odaUser"))));
            } else {
                m.put("kind", h.getDefn().getName());
            }
            sources.add(m);
        }
        root.put("datasources", sources);

        List<Object> sets = Json.arr();
        SlotHandle setSlot = design.getDataSets();
        for (int i = 0; i < setSlot.getCount(); i++) {
            DesignElementHandle h = setSlot.get(i);
            Map<String, Object> m = Json.obj();
            m.put("name", nz(h.getName()));
            if (h instanceof OdaDataSetHandle) {
                OdaDataSetHandle oda = (OdaDataSetHandle) h;
                m.put("datasource", nz(oda.getDataSourceName()));
                String sql = nz(oda.getQueryText());
                m.put("sql", truncate(sql, MAX_SQL_CHARS));
                m.put("columns", columnsOf(oda, manifest));
            } else {
                m.put("kind", h.getDefn().getName());
            }
            sets.add(m);
        }
        root.put("datasets", sets);

        List<Object> params = Json.arr();
        SlotHandle paramSlot = design.getParameters();
        for (int i = 0; i < paramSlot.getCount(); i++) {
            DesignElementHandle h = paramSlot.get(i);
            Map<String, Object> m = Json.obj();
            m.put("name", nz(h.getName()));
            if (h instanceof ScalarParameterHandle) {
                ScalarParameterHandle sp = (ScalarParameterHandle) h;
                m.put("type", nz(sp.getDataType()));
                m.put("prompt", nz(sp.getPromptText()));
            }
            params.add(m);
        }
        root.put("parameters", params);

        List<Object> body = Json.arr();
        SlotHandle bodySlot = design.getBody();
        for (int i = 0; i < bodySlot.getCount(); i++) {
            body.add(describeElement(bodySlot.get(i)));
        }
        root.put("body", body);
        return Json.write(root);
    }

    /** Описание одного элемента тела. */
    private static Map<String, Object> describeElement(DesignElementHandle el) {
        Map<String, Object> m = Json.obj();
        m.put("name", nz(el.getName()));
        String type = el.getDefn().getName();
        m.put("type", nz(type));
        if ("Label".equals(type)) {
            m.put("text", truncate(nz(String.valueOf(
                el.getProperty("text"))), MAX_TEXT_CHARS));
        } else if ("Table".equals(type) || "Grid".equals(type)) {
            SlotHandle columns = el.getSlot(1); // слот column
            if (columns != null) {
                m.put("columns", Integer.valueOf(columns.getCount()));
            }
        }
        return m;
    }

    /** Колонки набора: из кэша метаданных BIRT, иначе — из манифеста. */
    private static List<Object> columnsOf(OdaDataSetHandle oda, ReportSpec manifest) {
        List<Object> cols = Json.arr();
        try {
            if (oda.getCachedMetaDataHandle() != null
                && oda.getCachedMetaDataHandle().getResultSet() != null) {
                Object listValue = oda.getCachedMetaDataHandle().getResultSet().getListValue();
                if (listValue instanceof List) {
                    for (Object c : (List<?>) listValue) {
                        if (c instanceof org.eclipse.birt.report.model.api.elements.structures.ResultSetColumn) {
                            org.eclipse.birt.report.model.api.elements.structures.ResultSetColumn rsc =
                                (org.eclipse.birt.report.model.api.elements.structures.ResultSetColumn) c;
                            Object name = rsc.getLocalProperty(oda.getRoot().getModule(),
                                org.eclipse.birt.report.model.api.elements.structures.ResultSetColumn.NAME_MEMBER);
                            if (name == null || String.valueOf(name).isEmpty()) {
                                continue;
                            }
                            Map<String, Object> m = Json.obj();
                            m.put("name", String.valueOf(name));
                            Object type = rsc.getLocalProperty(oda.getRoot().getModule(),
                                org.eclipse.birt.report.model.api.elements.structures.ResultSetColumn.DATA_TYPE_MEMBER);
                            if (type != null) {
                                m.put("type", mapColumnType(String.valueOf(type)));
                            }
                            cols.add(m);
                        }
                    }
                }
                return cols;
            }
        } catch (Throwable ignored) {
            // кэш недоступен — ниже пробуем манифест
        }
        if (manifest != null) {
            for (ReportSpec.DataSet ds : manifest.datasets()) {
                if (ds.name().equals(oda.getName())) {
                    for (ReportSpec.DatasetColumn c : ds.columns()) {
                        Map<String, Object> m = Json.obj();
                        m.put("name", c.name());
                        m.put("type", c.type());
                        cols.add(m);
                    }
                    return cols;
                }
            }
        }
        return cols;
    }

    // ------------------------------------------------------------------
    // Наполнение манифеста DesignSession из дизайна
    // ------------------------------------------------------------------

    /**
     * Читает структуру дизайна в манифест сессии: параметры, источники
     * (без пароля), наборы (SQL + колонки из кэша). Порядок важен: наборы
     * ссылаются на параметры и источники, поэтому они засеваются последними.
     * Элементы тела в манифест не переносятся: правка чужих отчётов идёт
     * через remove_element + добавление новых элементов (ADR-0003).
     */
    public static void seed(ReportDesignHandle design, DesignSession session) {
        // 1) параметры отчёта
        SlotHandle paramSlot = design.getParameters();
        for (int i = 0; i < paramSlot.getCount(); i++) {
            DesignElementHandle h = paramSlot.get(i);
            if (h instanceof ScalarParameterHandle && h.getName() != null) {
                Map<String, Object> m = Json.obj();
                m.put("name", h.getName());
                m.put("type", mapParamType((ScalarParameterHandle) h));
                String prompt = ((ScalarParameterHandle) h).getPromptText();
                if (prompt != null && !prompt.isEmpty()) {
                    m.put("prompt", prompt);
                }
                try {
                    ReportSpec.Parameter p = ReportSpec.parseParameterFragment(m);
                    if (p != null) {
                        session.addManifestParameter(p);
                    }
                } catch (ReportSpec.SpecException ignore) {
                    // параметр с экзотическим типом — пропускаем
                }
            }
        }
        // 2) источники данных (без пароля)
        SlotHandle dsSlot = design.getDataSources();
        for (int i = 0; i < dsSlot.getCount(); i++) {
            DesignElementHandle h = dsSlot.get(i);
            if (h instanceof OdaDataSourceHandle && h.getName() != null) {
                try {
                    ReportSpec.DataSource src = ReportSpec.parseDataSourceFragment(seedSourceMap(
                        (OdaDataSourceHandle) h));
                    if (src != null) {
                        session.addManifestDataSource(src);
                    }
                } catch (ReportSpec.SpecException ignore) {
                    // источник с экзотическими параметрами — пропускаем
                }
            }
        }
        // 3) наборы данных (ссылаются на источники и параметры)
        SlotHandle setSlot = design.getDataSets();
        for (int i = 0; i < setSlot.getCount(); i++) {
            DesignElementHandle h = setSlot.get(i);
            if (h instanceof OdaDataSetHandle && h.getName() != null) {
                Map<String, Object> m = seedSetMap(design, (OdaDataSetHandle) h);
                try {
                    ReportSpec.DataSet ds = ReportSpec.parseDataSetFragment(m,
                        session.manifestDataSources(), session.manifestParameters());
                    if (ds != null) {
                        session.addManifestDataSet(ds);
                    }
                } catch (ReportSpec.SpecException e) {
                    // чужой набор не прошёл наши правила (например, не SELECT) —
                    // оставляем его в покое: правку он не получит, но и чинить
                    // существующее не наша задача; пропускаем молча
                }
            }
        }
    }

    private static Map<String, Object> seedSourceMap(OdaDataSourceHandle h) {
        Map<String, Object> m = Json.obj();
        m.put("name", h.getName());
        m.put("kind", "jdbc");
        m.put("driver", stringProp(h, "odaDriverClass"));
        m.put("url", stringProp(h, "odaURL"));
        m.put("user", stringProp(h, "odaUser"));
        return m;
    }

    private static Map<String, Object> seedSetMap(ReportDesignHandle design, OdaDataSetHandle h) {
        Map<String, Object> m = Json.obj();
        m.put("name", h.getName());
        m.put("datasource", h.getDataSourceName() == null ? "" : h.getDataSourceName());
        String sql = h.getQueryText();
        if (sql != null && !sql.trim().isEmpty()) {
            m.put("sql", sql);
        } else {
            m.put("sql", "SELECT 1");
        }
        // Параметры набора: по одному на каждый «?» — читаем обратно из дизайна.
        List<Object> params = Json.arr();
        try {
            Object raw = h.getPropertyHandle("parameters").getListValue();
            if (raw instanceof List) {
                for (Object p : (List<?>) raw) {
                    if (p instanceof org.eclipse.birt.report.model.api.elements.structures.OdaDataSetParameter) {
                        String name = ((org.eclipse.birt.report.model.api.elements.structures.OdaDataSetParameter) p)
                            .getParamName();
                        if (name != null && !name.isEmpty()) {
                            params.add(name);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // параметры недоступны — набор просто не получит правок
        }
        if (!params.isEmpty()) {
            m.put("params", params);
        }
        List<Object> cols = Json.arr();
        try {
            if (h.getCachedMetaDataHandle() != null
                && h.getCachedMetaDataHandle().getResultSet() != null) {
                Object listValue = h.getCachedMetaDataHandle().getResultSet().getListValue();
                if (listValue instanceof List) {
                    for (Object c : (List<?>) listValue) {
                        if (c instanceof org.eclipse.birt.report.model.api.elements.structures.ResultSetColumn) {
                            org.eclipse.birt.report.model.api.elements.structures.ResultSetColumn rsc =
                                (org.eclipse.birt.report.model.api.elements.structures.ResultSetColumn) c;
                            Object name = rsc.getLocalProperty(design.getModule(),
                                org.eclipse.birt.report.model.api.elements.structures.ResultSetColumn.NAME_MEMBER);
                            Object type = rsc.getLocalProperty(design.getModule(),
                                org.eclipse.birt.report.model.api.elements.structures.ResultSetColumn.DATA_TYPE_MEMBER);
                            if (name == null || String.valueOf(name).isEmpty()) {
                                continue;
                            }
                            Map<String, Object> cm = Json.obj();
                            cm.put("name", String.valueOf(name));
                            if (type != null) {
                                cm.put("type", mapColumnType(String.valueOf(type)));
                            }
                            cols.add(cm);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // кэш метаданных недоступен
        }
        m.put("columns", cols);
        return m;
    }

    private static String mapParamType(ScalarParameterHandle sp) {
        String t = sp.getDataType();
        if (t == null) {
            return "string";
        }
        if (t.contains("integer") || t.contains("Integer")) {
            return "integer";
        }
        if (t.contains("decimal") || t.contains("Decimal") || t.contains("float")) {
            return "decimal";
        }
        if (t.contains("Date") && t.contains("Time")) {
            return "datetime";
        }
        if (t.contains("Date")) {
            return "date";
        }
        if (t.contains("Boolean") || t.contains("boolean")) {
            return "boolean";
        }
        return "string";
    }

    private static String mapColumnType(String birtType) {
        String t = birtType.toLowerCase();
        if (t.contains("integer") || t.contains("int") || t.contains("smallint")
            || t.contains("bigint")) {
            return "integer";
        }
        if (t.contains("decimal") || t.contains("numeric") || t.contains("double")
            || t.contains("float") || t.contains("real") || t.contains("money")) {
            return "decimal";
        }
        if (t.contains("timestamp")) {
            return "datetime";
        }
        if (t.contains("date")) {
            return "date";
        }
        return "string";
    }

    private static String stringProp(DesignElementHandle h, String prop) {
        try {
            Object v = h.getProperty(prop);
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable e) {
            return "";
        }
    }

    private static String safeName(ReportDesignHandle design) {
        String fn = design.getFileName();
        return fn == null ? "(новый)" : fn;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }
}
