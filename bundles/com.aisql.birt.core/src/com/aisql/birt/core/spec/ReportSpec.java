/*
 * AI BIRT — спецификация отчёта (формат v0).
 *
 * Модель выдаёт компактный JSON (см. docs/ADR-0001), клиент его строго
 * разбирает и проверяет: неизвестные поля и несовместимые значения —
 * понятная ошибка вместо «сломанного файла». Из проверенной спецификации
 * собирается .rptdesign через Design API BIRT (см. com.aisql.birt.core.report).
 *
 * Расширения v0 (обратно совместимые, все ключи необязательные):
 *   label: italic, color;
 *   table: group_by, group_title, group_count (группировка строк),
 *   total_label (подпись строки итогов);
 *   колонка таблицы: bold, italic, color, background, header_background,
 *   total (sum/count/avg/min/max — итог в подвале таблицы),
 *   expr-вычисление из колонок набора (row["..."]).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core.spec;

import com.aisql.birt.core.Json;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Разобранная и проверенная спецификация отчёта. */
public final class ReportSpec {

    /** Пределы — защита от «фантазий» модели. */
    public static final int MAX_BODY_ELEMENTS = 60;
    public static final int MAX_TABLE_COLUMNS = 30;
    public static final int MAX_HIGHLIGHTS = 10;
    /** Максимум уровней вложенной группировки таблицы. */
    public static final int MAX_GROUP_LEVELS = 5;
    /** Максимум ключей сортировки таблицы. */
    public static final int MAX_SORT_KEYS = 10;
    public static final int MAX_PARAMETERS = 20;
    public static final int MAX_DATASETS = 20;
    public static final int MAX_DATASOURCES = 5;
    public static final int MAX_NAME_LENGTH = 60;

    /** Допустимые типы значений. */
    public static final List<String> TYPES = Collections.unmodifiableList(
        Arrays.asList("string", "integer", "decimal", "date", "datetime", "boolean"));
    /** Допустимые форматы страницы (см. DesignChoiceConstants.PAGE_SIZE_*). */
    public static final List<String> PAGE_FORMATS = Collections.unmodifiableList(
        Arrays.asList("A4", "A3", "A5", "Letter", "Legal", "Ledger"));
    /** Выравнивание текста. */
    public static final List<String> ALIGNS = Collections.unmodifiableList(
        Arrays.asList("left", "center", "right"));
    /** Функции итога (подвал таблицы). */
    public static final List<String> TOTALS = Collections.unmodifiableList(
        Arrays.asList("sum", "count", "avg", "min", "max"));
    /** Направления сортировки. */
    public static final List<String> SORT_DIRECTIONS = Collections.unmodifiableList(
        Arrays.asList("asc", "desc"));

    private static final Pattern NAME_RE = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,59}");
    private static final Pattern COLUMN_EXPR_RE = Pattern.compile(
        "^\\s*(?:row|dataSetRow)\\s*\\[\\s*[\"']([^\"']+)[\"']\\s*]\\s*$");
    /** Ссылки на колонки набора данных внутри выражения: row["..."]. */
    private static final Pattern EXPR_REF_RE = Pattern.compile(
        "(?:row|dataSetRow)\\s*\\[\\s*[\"']([^\"']+)[\"']\\s*]");
    /** Простое выражение BIRT: буквы, цифры, пробелы, арифметика, row["..."]; без «;». */
    private static final Pattern SIMPLE_EXPR_RE = Pattern.compile(
        "[\\p{L}\\p{N}_ \"'\\[\\]()., +\\-*/%<>=!&|?:#]+");
    /** Цвет в формате #RRGGBB. */
    private static final Pattern COLOR_RE = Pattern.compile("#[0-9A-Fa-f]{6}");

    /** Ошибка разбора/проверки спецификации (список пояснений на русском). */
    public static class SpecException extends Exception {
        private static final long serialVersionUID = 1L;
        private final List<String> problems;

        public SpecException(String message) {
            this(message, Collections.<String>emptyList());
        }

        public SpecException(String message, List<String> problems) {
            super(message + (problems.isEmpty() ? "" : ": " + join(problems)));
            this.problems = Collections.unmodifiableList(new ArrayList<String>(problems));
        }

        public List<String> problems() {
            return problems;
        }
    }

    // ------------------------------------------------------------------
    // Модель данных спецификации
    // ------------------------------------------------------------------

    /** Параметры страницы. */
    public static final class Page {
        private final String format;
        private final String orientation;
        /** Поля в миллиметрах: top, right, bottom, left; null — не задано. */
        private final Double[] marginsMm;
        /**
         * Шаблон верхнего колонтитула; поддерживаются подстановки
         * {page} — номер страницы, {pages} — всего страниц,
         * {title} — заголовок отчёта, {date} — дата печати; null — нет.
         */
        private final String header;
        /** Шаблон нижнего колонтитула (те же подстановки); null — нет. */
        private final String footer;
        private final boolean showHeaderOnFirst;
        private final boolean showFooterOnLast;

        Page(String format, String orientation, Double[] marginsMm,
                String header, String footer,
                boolean showHeaderOnFirst, boolean showFooterOnLast) {
            this.format = format;
            this.orientation = orientation;
            this.marginsMm = marginsMm;
            this.header = header;
            this.footer = footer;
            this.showHeaderOnFirst = showHeaderOnFirst;
            this.showFooterOnLast = showFooterOnLast;
        }

        public String format() { return format; }
        public String orientation() { return orientation; }
        public Double marginMm(int i) { return marginsMm == null ? null : marginsMm[i]; }
        public boolean hasMargins() { return marginsMm != null; }
        public String header() { return header; }
        public String footer() { return footer; }
        public boolean hasHeader() { return header != null && !header.isEmpty(); }
        public boolean hasFooter() { return footer != null && !footer.isEmpty(); }
        public boolean showHeaderOnFirst() { return showHeaderOnFirst; }
        public boolean showFooterOnLast() { return showFooterOnLast; }
    }

    /** Параметр отчёта. */
    public static final class Parameter {
        private final String name;
        private final String type;
        private final String prompt;
        private final String def;

        Parameter(String name, String type, String prompt, String def) {
            this.name = name;
            this.type = type;
            this.prompt = prompt;
            this.def = def;
        }

        public String name() { return name; }
        public String type() { return type; }
        public String prompt() { return prompt; }
        public String def() { return def; }
    }

    /** Источник данных (JDBC). */
    public static final class DataSource {
        private final String name;
        private final String driver;
        private final String url;
        private final String user;
        private final String password;

        DataSource(String name, String driver, String url, String user, String password) {
            this.name = name;
            this.driver = driver;
            this.url = url;
            this.user = user;
            this.password = password;
        }

        public String name() { return name; }
        public String driver() { return driver; }
        public String url() { return url; }
        public String user() { return user; }
        public String password() { return password; }
        public boolean hasPassword() { return password != null && !password.isEmpty(); }
    }

    /** Колонка результата набора данных (для подсказок BIRT). */
    public static final class DatasetColumn {
        private final String name;
        private final String type;

        DatasetColumn(String name, String type) {
            this.name = name;
            this.type = type;
        }

        public String name() { return name; }
        public String type() { return type; }
    }

    /** Набор данных. */
    public static final class DataSet {
        private final String name;
        private final String datasource;
        private final String sql;
        /** Имена параметров отчёта — по одному на каждый «?» в SQL (по порядку). */
        private final List<String> params;
        private final List<DatasetColumn> columns;

        DataSet(String name, String datasource, String sql,
                List<String> params, List<DatasetColumn> columns) {
            this.name = name;
            this.datasource = datasource;
            this.sql = sql;
            this.params = Collections.unmodifiableList(params);
            this.columns = Collections.unmodifiableList(columns);
        }

        public String name() { return name; }
        public String datasource() { return datasource; }
        public String sql() { return sql; }
        public List<String> params() { return params; }
        public List<DatasetColumn> columns() { return columns; }
    }

    /** Элемент тела отчёта. */
    public abstract static class BodyElement {
        public abstract String type();
    }

    /** Надпись (статический текст). */
    public static final class Label extends BodyElement {
        private final String text;
        private final Double sizePt;
        private final boolean bold;
        private final boolean italic;
        private final String color;
        private final String align;

        Label(String text, Double sizePt, boolean bold, boolean italic,
                String color, String align) {
            this.text = text;
            this.sizePt = sizePt;
            this.bold = bold;
            this.italic = italic;
            this.color = color;
            this.align = align;
        }

        public String text() { return text; }
        public Double sizePt() { return sizePt; }
        public boolean bold() { return bold; }
        public boolean italic() { return italic; }
        public String color() { return color; }
        public String align() { return align; }

        @Override public String type() { return "label"; }
    }

    /**
     * Условное форматирование колонки: если выражение {@code when} истинно,
     * к ячейке строки применяются цвет/фон/начертание.
     */
    public static final class Highlight {
        private final String when;
        private final String color;
        private final String background;
        private final boolean bold;
        private final boolean italic;
        /** Ссылки row["..."] в условии (для привязок колонок таблицы). */
        private final List<String> refs;

        Highlight(String when, String color, String background, boolean bold,
                boolean italic, List<String> refs) {
            this.when = when;
            this.color = color;
            this.background = background;
            this.bold = bold;
            this.italic = italic;
            this.refs = Collections.unmodifiableList(refs);
        }

        public String when() { return when; }
        public String color() { return color; }
        public String background() { return background; }
        public boolean bold() { return bold; }
        public boolean italic() { return italic; }
        public List<String> refs() { return refs; }
    }

    /** Колонка таблицы. */
    public static final class TableColumn {
        private final String header;
        private final String expr;
        private final Double widthMm;
        private final String align;
        private final String type;
        private final boolean bold;
        private final boolean italic;
        private final String color;
        private final String background;
        private final String headerBackground;
        /** Имя колонки набора данных, если выражение вида row["..."]; иначе null. */
        private final String resultColumn;
        /** Все ссылки row["..."] в выражении (для вычисляемых колонок). */
        private final List<String> refs;
        /** Функция итога в подвале таблицы (sum/count/avg/min/max); null — нет. */
        private final String total;
        /** Условия условного форматирования ячеек колонки. */
        private final List<Highlight> highlights;

        TableColumn(String header, String expr, Double widthMm, String align, String type,
                boolean bold, boolean italic, String color, String background,
                String headerBackground, String resultColumn, List<String> refs,
                String total, List<Highlight> highlights) {
            this.header = header;
            this.expr = expr;
            this.widthMm = widthMm;
            this.align = align;
            this.type = type;
            this.bold = bold;
            this.italic = italic;
            this.color = color;
            this.background = background;
            this.headerBackground = headerBackground;
            this.resultColumn = resultColumn;
            this.refs = Collections.unmodifiableList(refs);
            this.total = total;
            this.highlights = Collections.unmodifiableList(highlights);
        }

        public String header() { return header; }
        public String expr() { return expr; }
        public Double widthMm() { return widthMm; }
        public String align() { return align; }
        public String type() { return type; }
        public boolean bold() { return bold; }
        public boolean italic() { return italic; }
        public String color() { return color; }
        public String background() { return background; }
        public String headerBackground() { return headerBackground; }
        public String resultColumn() { return resultColumn; }
        public List<String> refs() { return refs; }
        public String total() { return total; }
        public List<Highlight> highlights() { return highlights; }
    }

    /** Уровень группировки таблицы: колонка набора, подпись и счётчик строк. */
    public static final class GroupLevel {
        private final String column;
        private final String title;
        private final boolean count;

        GroupLevel(String column, String title, boolean count) {
            this.column = column;
            this.title = title;
            this.count = count;
        }

        public String column() { return column; }
        public String title() { return title; }
        public boolean count() { return count; }
    }

    /** Ключ сортировки: выражение row["..."] и направление asc/desc. */
    public static final class SortKey {
        private final String expr;
        private final String direction;

        SortKey(String expr, String direction) {
            this.expr = expr;
            this.direction = direction;
        }

        public String expr() { return expr; }
        public String direction() { return direction; }
        public boolean descending() { return "desc".equals(direction); }
    }

    /** Таблица с данными. */
    public static final class Table extends BodyElement {
        private final String dataset;
        private final List<TableColumn> columns;
        /** Колонка набора данных для группировки строк (первый уровень); null — без группировки. */
        private final String groupBy;
        /** Подпись заголовка группы (по умолчанию «Группа»). */
        private final String groupTitle;
        /** Показывать в подвале группы количество строк. */
        private final boolean groupCount;
        /** Уровни группировки по порядку (вложенность); пусто — без группировки. */
        private final List<GroupLevel> groups;
        /** Ключи сортировки таблицы (по порядку применения); пусто — без сортировки. */
        private final List<SortKey> sort;
        /** Подпись строки итогов (по умолчанию «Итого»); null — без подписи. */
        private final String totalLabel;
        /** Условие отбора строк (row["..."] ...); null — все строки. */
        private final String filter;

        Table(String dataset, List<TableColumn> columns,
                List<GroupLevel> groups, String totalLabel,
                String filter, List<SortKey> sort) {
            this.dataset = dataset;
            this.columns = Collections.unmodifiableList(columns);
            this.groups = Collections.unmodifiableList(groups);
            this.totalLabel = totalLabel;
            this.filter = filter;
            this.sort = Collections.unmodifiableList(sort);
            GroupLevel first = groups.isEmpty() ? null : groups.get(0);
            this.groupBy = first == null ? null : first.column();
            this.groupTitle = first == null ? "Группа" : first.title();
            this.groupCount = first != null && first.count();
        }

        public String dataset() { return dataset; }
        public List<TableColumn> columns() { return columns; }
        /** Первый уровень группировки (совместимость с v0); null — без группировки. */
        public String groupBy() { return groupBy; }
        public String groupTitle() { return groupTitle; }
        public boolean groupCount() { return groupCount; }
        /** Все уровни группировки по порядку (вложенность). */
        public List<GroupLevel> groups() { return groups; }
        public boolean hasGroups() { return !groups.isEmpty(); }
        /** Ключи сортировки таблицы (по порядку применения). */
        public List<SortKey> sort() { return sort; }
        public String totalLabel() { return totalLabel; }
        public String filter() { return filter; }

        @Override public String type() { return "table"; }
    }

    /** Ячейка grid/таблицы: надпись, поле данных или HTML-текст. */
    public static final class Cell {
        private final String type;
        private final String text;
        private final String expr;
        private final String dataset;
        private final String align;
        private final String background;
        private final String color;
        private final boolean bold;
        private final boolean italic;
        private final Double fontPt;
        private final Integer colSpan;
        private final Integer rowSpan;

        Cell(String type, String text, String expr, String dataset, String align,
                String background, String color, boolean bold, boolean italic,
                Double fontPt, Integer colSpan, Integer rowSpan) {
            this.type = type;
            this.text = text;
            this.expr = expr;
            this.dataset = dataset;
            this.align = align;
            this.background = background;
            this.color = color;
            this.bold = bold;
            this.italic = italic;
            this.fontPt = fontPt;
            this.colSpan = colSpan;
            this.rowSpan = rowSpan;
        }

        public String type() { return type; }
        public String text() { return text; }
        public String expr() { return expr; }
        public String dataset() { return dataset; }
        public String align() { return align; }
        public String background() { return background; }
        public String color() { return color; }
        public boolean bold() { return bold; }
        public boolean italic() { return italic; }
        public Double fontPt() { return fontPt; }
        public Integer colSpan() { return colSpan; }
        public Integer rowSpan() { return rowSpan; }
    }

    /** Сетка (grid) — бланковая вёрстка: строки ячеек со спанами. */
    public static final class Grid extends BodyElement {
        private final List<Double> columnWidthsMm;
        private final List<List<Cell>> rows;

        Grid(List<Double> columnWidthsMm, List<List<Cell>> rows) {
            this.columnWidthsMm = Collections.unmodifiableList(columnWidthsMm);
            this.rows = Collections.unmodifiableList(rows);
        }

        public List<Double> columnWidthsMm() { return columnWidthsMm; }
        public List<List<Cell>> rows() { return rows; }

        @Override public String type() { return "grid"; }
    }

    /** Изображение (встроенный PNG в base64 или путь к файлу). */
    public static final class Image extends BodyElement {
        private final String base64Png;
        private final String path;
        private final Double widthMm;
        private final Double heightMm;
        private final String align;

        Image(String base64Png, String path, Double widthMm, Double heightMm, String align) {
            this.base64Png = base64Png;
            this.path = path;
            this.widthMm = widthMm;
            this.heightMm = heightMm;
            this.align = align;
        }

        public String base64Png() { return base64Png; }
        public String path() { return path; }
        public Double widthMm() { return widthMm; }
        public Double heightMm() { return heightMm; }
        public String align() { return align; }

        @Override public String type() { return "image"; }
    }

    // ------------------------------------------------------------------
    // Экземпляр
    // ------------------------------------------------------------------

    private final String name;
    private final String title;
    private final Page page;
    private final List<Parameter> parameters;
    private final List<DataSource> datasources;
    private final List<DataSet> datasets;
    private final List<BodyElement> body;

    private ReportSpec(String name, String title, Page page,
            List<Parameter> parameters, List<DataSource> datasources,
            List<DataSet> datasets, List<BodyElement> body) {
        this.name = name;
        this.title = title;
        this.page = page;
        this.parameters = Collections.unmodifiableList(parameters);
        this.datasources = Collections.unmodifiableList(datasources);
        this.datasets = Collections.unmodifiableList(datasets);
        this.body = Collections.unmodifiableList(body);
    }

    public String name() { return name; }
    public String title() { return title; }
    public Page page() { return page; }
    public List<Parameter> parameters() { return parameters; }
    public List<DataSource> datasources() { return datasources; }
    public List<DataSet> datasets() { return datasets; }
    public List<BodyElement> body() { return body; }

    /** Пароль источника данных, если задан ровно один источник; иначе null. */
    public DataSource singleDataSource() {
        return datasources.size() == 1 ? datasources.get(0) : null;
    }

    // ------------------------------------------------------------------
    // Разбор
    // ------------------------------------------------------------------

    /** Разбирает JSON-спецификацию. Бросает SpecException с понятными ошибками. */
    public static ReportSpec parse(String jsonText) throws SpecException {
        Object root;
        try {
            root = Json.parse(jsonText);
        } catch (RuntimeException e) {
            throw new SpecException("не удалось разобрать JSON: " + e.getMessage());
        }
        Map<String, Object> map = Json.asObj(root);
        if (map == null) {
            throw new SpecException("спецификация должна быть JSON-объектом");
        }
        return parseObject(map);
    }

    /**
     * Извлекает и разбирает спецификацию из ответа модели: поддерживаются
     * ответ «чистым JSON», JSON в ```-блоке и текст с JSON внутри (по первой
     * сбалансированной скобке). Бросает SpecException с понятными ошибками.
     */
    public static ReportSpec fromLlmReply(String reply) throws SpecException {
        if (reply == null || reply.trim().isEmpty()) {
            throw new SpecException("модель вернула пустой ответ");
        }
        String json = extractJson(reply.trim());
        if (json == null) {
            throw new SpecException("в ответе модели не найден JSON-объект");
        }
        return parse(json);
    }

    /** Ищет в тексте первый сбалансированный JSON-объект. Возвращает null, если нет. */
    static String extractJson(String text) {
        String t = text;
        // Убираем ограждение ```...``` (с указанием языка или без).
        int fence = t.indexOf("```");
        if (fence >= 0) {
            int body = t.indexOf('\n', fence + 3);
            int end = t.indexOf("```", fence + 3);
            if (body >= 0 && end > body) {
                t = t.substring(body + 1, end);
            }
        }
        int start = t.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = start; i < t.length(); i++) {
            char c = t.charAt(i);
            if (inStr) {
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return t.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Проверка
    // ------------------------------------------------------------------

    private static ReportSpec parseObject(Map<String, Object> map) throws SpecException {
        List<String> problems = new ArrayList<String>();
        checkUnknown(map, problems, "спецификация",
            "report", "parameters", "datasources", "datasets", "body");

        // --- report ---
        Map<String, Object> report = objField(map, "report", true, problems, "спецификация");
        String name = null;
        String title = null;
        Page page = null;
        if (report != null) {
            checkUnknown(report, problems, "report", "name", "title", "page");
            name = strField(report, "name", false, problems, "report");
            title = strField(report, "title", false, problems, "report");
            if (name == null || name.isEmpty()) {
                problems.add("report.name обязателен (имя файла отчёта)");
            } else if (!matchesName(name)) {
                problems.add("report.name «" + name + "»: допустимы латинские буквы,"
                    + " цифры и «_», начало — с буквы, до " + MAX_NAME_LENGTH + " символов");
            }
            if (title != null && title.length() > 300) {
                problems.add("report.title длиннее 300 символов");
            }
            Object pageRaw = report.get("page");
            if (pageRaw != null) {
                page = parsePage(Json.asObj(pageRaw), problems);
            }
        }
        if (name == null || name.isEmpty()) {
            name = "report";
        }
        if (title == null || title.isEmpty()) {
            title = name;
        }

        // --- parameters ---
        List<Parameter> parameters = new ArrayList<Parameter>();
        List<Object> paramsRaw = arrField(map, "parameters", problems, "спецификация");
        if (paramsRaw != null) {
            if (paramsRaw.size() > MAX_PARAMETERS) {
                problems.add("параметров больше " + MAX_PARAMETERS);
            }
            int i = 0;
            for (Object o : paramsRaw) {
                i++;
                Map<String, Object> p = Json.asObj(o);
                if (p == null) {
                    problems.add("parameters[" + i + "] должен быть объектом");
                    continue;
                }
                checkUnknown(p, problems, "parameters[" + i + "]", "name", "type", "prompt", "default");
                String pn = strField(p, "name", true, problems, "parameters[" + i + "]");
                String pt = strField(p, "type", true, problems, "parameters[" + i + "]");
                String pp = strField(p, "prompt", false, problems, "parameters[" + i + "]");
                Object defRaw = p.get("default");
                if (pn != null && !matchesName(pn)) {
                    problems.add("parameters[" + i + "].name «" + pn + "»: недопустимое имя");
                    pn = null;
                }
                if (pt != null && !TYPES.contains(pt)) {
                    problems.add("parameters[" + i + "].type «" + pt + "»: допустимы "
                        + TYPES);
                    pt = null;
                }
                if (defRaw != null && !(defRaw instanceof String)) {
                    problems.add("parameters[" + i + "].default должен быть строкой");
                }
                if (pn != null && pt != null) {
                    for (Parameter prev : parameters) {
                        if (prev.name().equals(pn)) {
                            problems.add("parameters: имя «" + pn + "» повторяется");
                        }
                    }
                    parameters.add(new Parameter(pn, pt, pp,
                        defRaw == null ? null : String.valueOf(defRaw)));
                }
            }
        }

        // --- datasources ---
        List<DataSource> datasources = new ArrayList<DataSource>();
        List<Object> dsRaw = arrField(map, "datasources", problems, "спецификация");
        if (dsRaw != null) {
            if (dsRaw.size() > MAX_DATASOURCES) {
                problems.add("источников данных больше " + MAX_DATASOURCES);
            }
            int i = 0;
            for (Object o : dsRaw) {
                i++;
                Map<String, Object> p = Json.asObj(o);
                if (p == null) {
                    problems.add("datasources[" + i + "] должен быть объектом");
                    continue;
                }
                checkUnknown(p, problems, "datasources[" + i + "]",
                    "name", "kind", "driver", "url", "user", "password");
                String dn = strField(p, "name", true, problems, "datasources[" + i + "]");
                String kind = strField(p, "kind", true, problems, "datasources[" + i + "]");
                String driver = strField(p, "driver", true, problems, "datasources[" + i + "]");
                String url = strField(p, "url", true, problems, "datasources[" + i + "]");
                String user = strField(p, "user", true, problems, "datasources[" + i + "]");
                String pw = strField(p, "password", false, problems, "datasources[" + i + "]");
                if (kind != null && !"jdbc".equals(kind)) {
                    problems.add("datasources[" + i + "].kind «" + kind
                        + "»: поддерживается только «jdbc»");
                }
                if (dn != null && !matchesName(dn)) {
                    problems.add("datasources[" + i + "].name «" + dn + "»: недопустимое имя");
                    dn = null;
                }
                if (url != null && !url.startsWith("jdbc:")) {
                    problems.add("datasources[" + i + "].url должен начинаться с «jdbc:»");
                }
                if (dn != null && driver != null && url != null && user != null) {
                    datasources.add(new DataSource(dn, driver, url, user, pw));
                }
            }
        }

        // --- datasets ---
        List<DataSet> datasets = new ArrayList<DataSet>();
        List<Object> dataRaw = arrField(map, "datasets", problems, "спецификация");
        if (dataRaw != null) {
            if (dataRaw.size() > MAX_DATASETS) {
                problems.add("наборов данных больше " + MAX_DATASETS);
            }
            int i = 0;
            for (Object o : dataRaw) {
                i++;
                Map<String, Object> p = Json.asObj(o);
                if (p == null) {
                    problems.add("datasets[" + i + "] должен быть объектом");
                    continue;
                }
                checkUnknown(p, problems, "datasets[" + i + "]",
                    "name", "datasource", "sql", "params", "columns");
                String dn_ = strField(p, "name", true, problems, "datasets[" + i + "]");
                String dsrc = strField(p, "datasource", true, problems, "datasets[" + i + "]");
                String sql = strField(p, "sql", true, problems, "datasets[" + i + "]");
                if (dn_ != null && !matchesName(dn_)) {
                    problems.add("datasets[" + i + "].name «" + dn_ + "»: недопустимое имя");
                    dn_ = null;
                }
                if (dsrc != null && findDataSource(datasources, dsrc) == null) {
                    problems.add("datasets[" + i + "].datasource «" + dsrc
                        + "»: источник данных не описан");
                }
                List<String> dParams = new ArrayList<String>();
                if (sql != null) {
                    List<String> sqlProblems = ReadOnlySql.check(sql);
                    for (String s : sqlProblems) {
                        problems.add("datasets[" + i + "].sql: " + s);
                    }
                    int marks = countParamMarks(sql);
                    List<Object> prmRaw = arrField(p, "params", problems, "datasets[" + i + "]");
                    if (prmRaw == null) {
                        if (marks > 0) {
                            problems.add("datasets[" + i + "]: в SQL " + marks
                                + " знак(ов) «?», но список params пуст");
                        }
                    } else {
                        if (prmRaw.size() != marks) {
                            problems.add("datasets[" + i + "]: в SQL " + marks
                                + " знак(ов) «?», а в params — " + prmRaw.size());
                        }
                        int j = 0;
                        for (Object po : prmRaw) {
                            j++;
                            String pname = Json.asStr(po);
                            if (pname == null) {
                                problems.add("datasets[" + i + "].params[" + j
                                    + "] должен быть строкой (имя параметра отчёта)");
                                continue;
                            }
                            if (findParameter(parameters, pname) == null) {
                                problems.add("datasets[" + i + "].params[" + j + "] «" + pname
                                    + "»: параметр отчёта не описан");
                            }
                            dParams.add(pname);
                        }
                    }
                }
                List<DatasetColumn> dCols = new ArrayList<DatasetColumn>();
                List<Object> colRaw = arrField(p, "columns", problems, "datasets[" + i + "]");
                if (colRaw != null) {
                    int j = 0;
                    for (Object co : colRaw) {
                        j++;
                        Map<String, Object> c = Json.asObj(co);
                        if (c == null) {
                            problems.add("datasets[" + i + "].columns[" + j
                                + "] должен быть объектом");
                            continue;
                        }
                        checkUnknown(c, problems, "datasets[" + i + "].columns[" + j + "]",
                            "name", "type");
                        String cn = strField(c, "name", true, problems,
                            "datasets[" + i + "].columns[" + j + "]");
                        String ct = strField(c, "type", false, problems,
                            "datasets[" + i + "].columns[" + j + "]");
                        if (ct != null && !TYPES.contains(ct)) {
                            problems.add("datasets[" + i + "].columns[" + j + "].type «" + ct
                                + "»: допустимы " + TYPES);
                            ct = null;
                        }
                        if (cn != null) {
                            dCols.add(new DatasetColumn(cn, ct == null ? "string" : ct));
                        }
                    }
                }
                if (dn_ != null && dsrc != null && sql != null) {
                    for (DataSet prev : datasets) {
                        if (prev.name().equals(dn_)) {
                            problems.add("datasets: имя «" + dn_ + "» повторяется");
                        }
                    }
                    datasets.add(new DataSet(dn_, dsrc, sql, dParams, dCols));
                }
            }
        }

        // --- body ---
        List<BodyElement> body = new ArrayList<BodyElement>();
        List<Object> bodyRaw = arrField(map, "body", problems, "спецификация");
        if (bodyRaw == null || bodyRaw.isEmpty()) {
            problems.add("body: должен быть непустым списком элементов");
        } else {
            if (bodyRaw.size() > MAX_BODY_ELEMENTS) {
                problems.add("элементов в body больше " + MAX_BODY_ELEMENTS);
            }
            int i = 0;
            for (Object o : bodyRaw) {
                i++;
                Map<String, Object> e = Json.asObj(o);
                if (e == null) {
                    problems.add("body[" + i + "] должен быть объектом");
                    continue;
                }
                String type = Json.asStr(e.get("type"));
                if ("label".equals(type)) {
                    BodyElement el = parseLabel(e, i, problems);
                    if (el != null) {
                        body.add(el);
                    }
                } else if ("table".equals(type)) {
                    BodyElement el = parseTable(e, i, problems, datasets);
                    if (el != null) {
                        body.add(el);
                    }
                } else if ("grid".equals(type)) {
                    BodyElement el = parseGrid(e, i, problems, datasets);
                    if (el != null) {
                        body.add(el);
                    }
                } else if ("image".equals(type)) {
                    BodyElement el = parseImage(e, i, problems);
                    if (el != null) {
                        body.add(el);
                    }
                } else {
                    problems.add("body[" + i + "].type «" + type
                        + "»: на этом этапе поддерживаются «label», «table», «grid» и «image»");
                }
            }
        }

        if (!problems.isEmpty()) {
            throw new SpecException("спецификация не принята", problems);
        }
        return new ReportSpec(name, title, page, parameters, datasources, datasets, body);
    }

    private static Page parsePage(Map<String, Object> p, List<String> problems) {
        if (p == null) {
            problems.add("report.page должен быть объектом");
            return null;
        }
        checkUnknown(p, problems, "report.page", "format", "orientation", "margins_mm",
            "header", "footer", "show_header_on_first", "show_footer_on_last");
        String format = strField(p, "format", false, problems, "report.page");
        String orientation = strField(p, "orientation", false, problems, "report.page");
        String header = strField(p, "header", false, problems, "report.page");
        if (header != null && header.trim().isEmpty()) {
            header = null;
        }
        if (header != null && header.length() > 200) {
            problems.add("report.page.header длиннее 200 символов");
            header = null;
        }
        String footer = strField(p, "footer", false, problems, "report.page");
        if (footer != null && footer.trim().isEmpty()) {
            footer = null;
        }
        if (footer != null && footer.length() > 200) {
            problems.add("report.page.footer длиннее 200 символов");
            footer = null;
        }
        boolean showHeaderOnFirst = Json.asBool(p.get("show_header_on_first"), true);
        boolean showFooterOnLast = Json.asBool(p.get("show_footer_on_last"), true);
        if (format != null && !PAGE_FORMATS.contains(format)) {
            problems.add("report.page.format «" + format + "»: допустимы " + PAGE_FORMATS);
            format = null;
        }
        if (orientation != null && !"portrait".equals(orientation)
                && !"landscape".equals(orientation)) {
            problems.add("report.page.orientation «" + orientation
                + "»: допустимы «portrait» и «landscape»");
            orientation = null;
        }
        Double[] margins = null;
        Object mRaw = p.get("margins_mm");
        if (mRaw != null) {
            Map<String, Object> m = Json.asObj(mRaw);
            if (m == null) {
                problems.add("report.page.margins_mm должен быть объектом");
            } else {
                checkUnknown(m, problems, "report.page.margins_mm",
                    "top", "right", "bottom", "left");
                margins = new Double[4];
                String[] keys = {"top", "right", "bottom", "left"};
                for (int i = 0; i < 4; i++) {
                    Object v = m.get(keys[i]);
                    if (v == null) {
                        continue;
                    }
                    if (!(v instanceof Number)) {
                        problems.add("report.page.margins_mm." + keys[i] + " должен быть числом");
                        continue;
                    }
                    double d = ((Number) v).doubleValue();
                    if (d < 0 || d > 100) {
                        problems.add("report.page.margins_mm." + keys[i]
                            + ": допустимо 0–100 мм");
                        continue;
                    }
                    margins[i] = Double.valueOf(d);
                }
            }
        }
        return new Page(format == null ? "A4" : format,
            orientation == null ? "portrait" : orientation, margins,
            header, footer, showHeaderOnFirst, showFooterOnLast);
    }

    private static BodyElement parseLabel(Map<String, Object> e, int i, List<String> problems) {
        checkUnknown(e, problems, "body[" + i + "]",
            "type", "text", "size_pt", "bold", "italic", "color", "align");
        String text = strField(e, "text", true, problems, "body[" + i + "]");
        if (text != null && text.length() > 500) {
            problems.add("body[" + i + "].text длиннее 500 символов");
        }
        Double size = null;
        Object sRaw = e.get("size_pt");
        if (sRaw != null) {
            if (!(sRaw instanceof Number)) {
                problems.add("body[" + i + "].size_pt должен быть числом");
            } else {
                double d = ((Number) sRaw).doubleValue();
                if (d < 6 || d > 72) {
                    problems.add("body[" + i + "].size_pt: допустимо 6–72");
                } else {
                    size = Double.valueOf(d);
                }
            }
        }
        boolean bold = Json.asBool(e.get("bold"), false);
        boolean italic = Json.asBool(e.get("italic"), false);
        String color = colorField(e, "color", problems, "body[" + i + "]");
        String align = strField(e, "align", false, problems, "body[" + i + "]");
        if (align != null && !ALIGNS.contains(align)) {
            problems.add("body[" + i + "].align «" + align + "»: допустимы " + ALIGNS);
            align = null;
        }
        if (text == null) {
            return null;
        }
        return new Label(text, size, bold, italic, color, align);
    }

    /** Разбор grid: column_widths_mm + rows[].cells[]. */
    private static BodyElement parseGrid(Map<String, Object> e, int i,
            List<String> problems, List<DataSet> datasets) {
        checkUnknown(e, problems, "body[" + i + "]",
            "type", "column_widths_mm", "rows");
        List<Object> widthsRaw = arrField(e, "column_widths_mm", problems, "body[" + i + "]");
        if (widthsRaw == null || widthsRaw.isEmpty()) {
            problems.add("body[" + i + "] («grid»): нужен непустой список column_widths_mm");
        } else if (widthsRaw.size() > MAX_TABLE_COLUMNS) {
            problems.add("body[" + i + "]: колонок grid больше " + MAX_TABLE_COLUMNS);
        }
        List<Double> widths = new ArrayList<Double>();
        if (widthsRaw != null) {
            for (Object w : widthsRaw) {
                if (!(w instanceof Number)) {
                    problems.add("body[" + i + "].column_widths_mm должен быть массивом чисел (мм)");
                    continue;
                }
                double d = ((Number) w).doubleValue();
                if (d < 5 || d > 300) {
                    problems.add("body[" + i + "].column_widths_mm: допустимо 5–300 мм");
                    continue;
                }
                widths.add(Double.valueOf(d));
            }
        }
        List<Object> rowsRaw = arrField(e, "rows", problems, "body[" + i + "]");
        if (rowsRaw == null || rowsRaw.isEmpty()) {
            problems.add("body[" + i + "] («grid»): нужен непустой список rows");
        } else if (rowsRaw.size() > MAX_BODY_ELEMENTS) {
            problems.add("body[" + i + "]: строк grid больше " + MAX_BODY_ELEMENTS);
        }
        List<List<Cell>> rows = new ArrayList<List<Cell>>();
        if (rowsRaw != null) {
            int r = 0;
            for (Object ro : rowsRaw) {
                r++;
                Map<String, Object> row = Json.asObj(ro);
                if (row == null) {
                    problems.add("body[" + i + "].rows[" + r + "] должен быть объектом");
                    continue;
                }
                checkUnknown(row, problems, "body[" + i + "].rows[" + r + "]",
                    "cells");
                List<Object> cellsRaw = arrField(row, "cells", problems,
                    "body[" + i + "].rows[" + r + "]");
                if (cellsRaw == null || cellsRaw.isEmpty()) {
                    problems.add("body[" + i + "].rows[" + r + "]: нужен непустой список cells");
                    continue;
                }
                if (cellsRaw.size() > MAX_TABLE_COLUMNS) {
                    problems.add("body[" + i + "].rows[" + r
                        + "]: ячеек больше " + MAX_TABLE_COLUMNS);
                }
                List<Cell> cells = new ArrayList<Cell>();
                int c = 0;
                for (Object co : cellsRaw) {
                    c++;
                    String cw = "body[" + i + "].rows[" + r + "].cells[" + c + "]";
                    Map<String, Object> cell = Json.asObj(co);
                    if (cell == null) {
                        problems.add(cw + " должен быть объектом");
                        continue;
                    }
                    Cell parsed = parseCell(cell, cw, problems, datasets);
                    if (parsed != null) {
                        cells.add(parsed);
                    }
                }
                if (widths != null && !widths.isEmpty()) {
                    int spanSum = 0;
                    for (Cell cell : cells) {
                        spanSum += cell.colSpan() == null ? 1 : cell.colSpan().intValue();
                    }
                    if (spanSum != widths.size()) {
                        problems.add("body[" + i + "].rows[" + r
                            + "]: сумма col_span ячеек (" + spanSum
                            + ") не совпадает с числом колонок (" + widths.size() + ")");
                    }
                }
                rows.add(cells);
            }
        }
        if (widths == null || widths.isEmpty() || rows == null || rows.isEmpty()) {
            return null;
        }
        return new Grid(widths, rows);
    }

    /** Разбор ячейки grid: {type, text, expr, dataset, ...}. */
    private static Cell parseCell(Map<String, Object> c, String where,
            List<String> problems, List<DataSet> datasets) {
        checkUnknown(c, problems, where,
            "type", "text", "expr", "dataset", "align", "background",
            "color", "bold", "italic", "font_pt", "col_span", "row_span");
        String type = strField(c, "type", false, problems, where);
        if (type != null && !"label".equals(type) && !"data".equals(type)
                && !"text".equals(type)) {
            problems.add(where + ".type «" + type + "»: допустимы «label», «data», «text»");
            type = null;
        }
        String text = strField(c, "text", false, problems, where);
        String expr = strField(c, "expr", false, problems, where);
        String dataset = strField(c, "dataset", false, problems, where);
        if (expr != null) {
            if (dataset != null && findDataSet(datasets, dataset) == null) {
                problems.add(where + ".dataset «" + dataset + "»: набор данных не описан");
            }
            if (!SIMPLE_EXPR_RE.matcher(expr).matches()) {
                problems.add(where + ".expr: недопустимые символы — нужно простое "
                    + "выражение BIRT (арифметика и row[\"колонка\"], без «;»)");
                expr = null;
            }
        }
        String align = strField(c, "align", false, problems, where);
        if (align != null && !ALIGNS.contains(align)) {
            problems.add(where + ".align «" + align + "»: допустимы " + ALIGNS);
            align = null;
        }
        Double font = null;
        Object fRaw = c.get("font_pt");
        if (fRaw != null) {
            if (!(fRaw instanceof Number)) {
                problems.add(where + ".font_pt должен быть числом");
            } else {
                double d = ((Number) fRaw).doubleValue();
                if (d < 6 || d > 72) {
                    problems.add(where + ".font_pt: допустимо 6–72");
                } else {
                    font = Double.valueOf(d);
                }
            }
        }
        Integer colSpan = intField(c, "col_span", where, problems, 1, 10);
        Integer rowSpan = intField(c, "row_span", where, problems, 1, 50);
        return new Cell(type == null ? "label" : type, text, expr, dataset, align,
            colorField(c, "background", problems, where), colorField(c, "color", problems, where),
            Json.asBool(c.get("bold"), false), Json.asBool(c.get("italic"), false),
            font, colSpan, rowSpan);
    }

    /** Разбор image: {base64, path, width_mm, height_mm, align}. */
    private static BodyElement parseImage(Map<String, Object> e, int i, List<String> problems) {
        checkUnknown(e, problems, "body[" + i + "]",
            "type", "base64", "path", "width_mm", "height_mm", "align");
        String base64 = strField(e, "base64", false, problems, "body[" + i + "]");
        String path = strField(e, "path", false, problems, "body[" + i + "]");
        if (base64 == null && path == null) {
            problems.add("body[" + i + "] («image»): нужен base64 или path");
        }
        if (base64 != null && path != null) {
            problems.add("body[" + i + "] («image»): base64 и path нельзя задавать вместе");
        }
        if (base64 != null && !base64.matches("^[A-Za-z0-9+/=]{64,}$")) {
            problems.add("body[" + i + "].base64: ожидаются данные PNG в base64 (минимум 1 КБ)");
        }
        Double width = null;
        Object wRaw = e.get("width_mm");
        if (wRaw != null) {
            if (!(wRaw instanceof Number)) {
                problems.add("body[" + i + "].width_mm должен быть числом");
            } else {
                double d = ((Number) wRaw).doubleValue();
                if (d < 5 || d > 300) {
                    problems.add("body[" + i + "].width_mm: допустимо 5–300 мм");
                } else {
                    width = Double.valueOf(d);
                }
            }
        }
        Double height = null;
        Object hRaw = e.get("height_mm");
        if (hRaw != null) {
            if (!(hRaw instanceof Number)) {
                problems.add("body[" + i + "].height_mm должен быть числом");
            } else {
                double d = ((Number) hRaw).doubleValue();
                if (d < 5 || d > 300) {
                    problems.add("body[" + i + "].height_mm: допустимо 5–300 мм");
                } else {
                    height = Double.valueOf(d);
                }
            }
        }
        String align = strField(e, "align", false, problems, "body[" + i + "]");
        if (align != null && !ALIGNS.contains(align)) {
            problems.add("body[" + i + "].align «" + align + "»: допустимы " + ALIGNS);
            align = null;
        }
        if (width == null && height == null) {
            problems.add("body[" + i + "] («image»): нужна ширина или высота в мм");
        }
        if (base64 == null && path == null) {
            return null;
        }
        return new Image(base64, path, width, height, align);
    }

    /** Целое поле с проверкой диапазона. */
    private static Integer intField(Map<String, Object> map, String key, String where,
            List<String> problems, int min, int max) {
        Object o = map.get(key);
        if (o == null) {
            return null;
        }
        if (!(o instanceof Number)) {
            problems.add(where + "." + key + " должен быть числом");
            return null;
        }
        int v = ((Number) o).intValue();
        if (v < min || v > max) {
            problems.add(where + "." + key + ": допустимо " + min + "–" + max);
            return null;
        }
        return Integer.valueOf(v);
    }

    private static BodyElement parseTable(Map<String, Object> e, int i,
            List<String> problems, List<DataSet> datasets) {
        checkUnknown(e, problems, "body[" + i + "]",
            "type", "dataset", "columns", "group_by", "group_title", "group_count",
            "groups", "sort", "total_label", "filter");
        String dataset = strField(e, "dataset", true, problems, "body[" + i + "]");
        DataSet ds = dataset == null ? null : findDataSet(datasets, dataset);
        if (dataset != null && ds == null) {
            problems.add("body[" + i + "].dataset «" + dataset + "»: набор данных не описан");
        }
        String totalLabel = strField(e, "total_label", false, problems, "body[" + i + "]");
        if (totalLabel != null && totalLabel.length() > 200) {
            problems.add("body[" + i + "].total_label длиннее 200 символов");
            totalLabel = null;
        }
        String groupBy = strField(e, "group_by", false, problems, "body[" + i + "]");
        String groupTitle = strField(e, "group_title", false, problems, "body[" + i + "]");
        boolean groupCount = Json.asBool(e.get("group_count"), false);
        if (groupBy != null && !matchesName(groupBy)) {
            problems.add("body[" + i + "].group_by «" + groupBy + "»: недопустимое имя");
            groupBy = null;
        }
        if (groupBy != null && ds != null && !ds.columns().isEmpty()
                && findDatasetColumn(ds, groupBy) == null) {
            problems.add("body[" + i + "].group_by: колонка «" + groupBy
                + "» не описана в datasets." + dataset + ".columns");
        }
        if (groupTitle != null && groupTitle.length() > 200) {
            problems.add("body[" + i + "].group_title длиннее 200 символов");
        }
        List<GroupLevel> groups = parseGroups(e, i, problems, ds, dataset,
            groupBy, groupTitle, groupCount);
        List<SortKey> sort = parseSort(e, i, problems, ds, dataset);
        String filter = strField(e, "filter", false, problems, "body[" + i + "]");
        if (filter != null) {
            if (!SIMPLE_EXPR_RE.matcher(filter).matches()) {
                problems.add("body[" + i + "].filter: недопустимые символы — нужно простое "
                    + "выражение BIRT (сравнения, row[\"колонка\"], без «;»)");
                filter = null;
            } else if (exprRefs(filter).isEmpty()) {
                problems.add("body[" + i + "].filter: нужно условие с row[\"колонка\"]");
                filter = null;
            } else {
                for (String ref : exprRefs(filter)) {
                    if (ds != null && !ds.columns().isEmpty()
                            && findDatasetColumn(ds, ref) == null) {
                        problems.add("body[" + i + "].filter: колонка «" + ref
                            + "» не описана в datasets." + dataset + ".columns");
                    }
                }
            }
        }
        if (groupTitle == null) {
            groupTitle = "Группа";
        }
        List<Object> colsRaw = arrField(e, "columns", problems, "body[" + i + "]");
        List<TableColumn> cols = new ArrayList<TableColumn>();
        if (colsRaw == null || colsRaw.isEmpty()) {
            problems.add("body[" + i + "] («table»): нужен непустой список columns");
        } else {
            if (colsRaw.size() > MAX_TABLE_COLUMNS) {
                problems.add("body[" + i + "]: колонок больше " + MAX_TABLE_COLUMNS);
            }
            int j = 0;
            for (Object co : colsRaw) {
                j++;
                String where = "body[" + i + "].columns[" + j + "]";
                Map<String, Object> c = Json.asObj(co);
                if (c == null) {
                    problems.add(where + " должен быть объектом");
                    continue;
                }
                checkUnknown(c, problems, where,
                    "header", "expr", "width_mm", "align", "type",
                    "bold", "italic", "color", "background", "header_background",
                    "total", "highlight");
                String header = strField(c, "header", true, problems, where);
                String expr = strField(c, "expr", true, problems, where);
                Double width = null;
                Object wRaw = c.get("width_mm");
                if (wRaw != null) {
                    if (!(wRaw instanceof Number)) {
                        problems.add(where + ".width_mm должен быть числом");
                    } else {
                        double d = ((Number) wRaw).doubleValue();
                        if (d < 5 || d > 300) {
                            problems.add(where + ".width_mm: допустимо 5–300 мм");
                        } else {
                            width = Double.valueOf(d);
                        }
                    }
                }
                String align = strField(c, "align", false, problems, where);
                if (align != null && !ALIGNS.contains(align)) {
                    problems.add(where + ".align «" + align + "»: допустимы " + ALIGNS);
                    align = null;
                }
                String type = strField(c, "type", false, problems, where);
                if (type != null && !TYPES.contains(type)) {
                    problems.add(where + ".type «" + type + "»: допустимы " + TYPES);
                    type = null;
                }
                boolean bold = Json.asBool(c.get("bold"), false);
                boolean italic = Json.asBool(c.get("italic"), false);
                String color = colorField(c, "color", problems, where);
                String background = colorField(c, "background", problems, where);
                String headerBackground = colorField(c, "header_background", problems, where);
                String total = strField(c, "total", false, problems, where);
                if (total != null) {
                    total = total.toLowerCase(Locale.ROOT);
                    if (!TOTALS.contains(total)) {
                        problems.add(where + ".total «" + total + "»: допустимы " + TOTALS);
                        total = null;
                    }
                }
                List<Highlight> highlights = new ArrayList<Highlight>();
                List<Object> hlRaw = arrField(c, "highlight", problems, where);
                if (hlRaw != null) {
                    if (hlRaw.size() > MAX_HIGHLIGHTS) {
                        problems.add(where + ".highlight: условий больше " + MAX_HIGHLIGHTS);
                    }
                    int k = 0;
                    for (Object ho : hlRaw) {
                        k++;
                        String hw = where + ".highlight[" + k + "]";
                        Map<String, Object> h = Json.asObj(ho);
                        if (h == null) {
                            problems.add(hw + " должен быть объектом");
                            continue;
                        }
                        checkUnknown(h, problems, hw,
                            "when", "color", "background", "bold", "italic");
                        String when = strField(h, "when", true, problems, hw);
                        String hColor = colorField(h, "color", problems, hw);
                        String hBackground = colorField(h, "background", problems, hw);
                        boolean hBold = Json.asBool(h.get("bold"), false);
                        boolean hItalic = Json.asBool(h.get("italic"), false);
                        if (when == null) {
                            continue;
                        }
                        if (!SIMPLE_EXPR_RE.matcher(when).matches()) {
                            problems.add(hw + ".when: недопустимые символы — нужно простое "
                                + "выражение BIRT (сравнения, row[\"колонка\"], без «;»)");
                            continue;
                        }
                        List<String> hRefs = exprRefs(when);
                        if (hRefs.isEmpty()) {
                            problems.add(hw + ".when: нужно сравнение с row[\"колонка\"]");
                        }
                        for (String ref : hRefs) {
                            if (ds != null && !ds.columns().isEmpty()
                                    && findDatasetColumn(ds, ref) == null) {
                                problems.add(hw + ".when: колонка «" + ref
                                    + "» не описана в datasets." + dataset + ".columns");
                            }
                        }
                        if (hColor == null && hBackground == null && !hBold && !hItalic) {
                            problems.add(hw + ": задайте хотя бы одно оформление "
                                + "(color/background/bold/italic)");
                        }
                        highlights.add(new Highlight(when, hColor, hBackground,
                            hBold, hItalic, hRefs));
                    }
                }
                if (header == null || expr == null) {
                    continue;
                }
                String resultCol = null;
                List<String> refs = new ArrayList<String>();
                Matcher m = COLUMN_EXPR_RE.matcher(expr);
                if (m.matches()) {
                    resultCol = m.group(1);
                    refs.add(resultCol);
                } else if (!SIMPLE_EXPR_RE.matcher(expr).matches()) {
                    problems.add(where + ".expr: недопустимые символы — нужно простое "
                        + "выражение BIRT (арифметика и row[\"колонка\"], без «;»)");
                } else {
                    Matcher rm = EXPR_REF_RE.matcher(expr);
                    while (rm.find()) {
                        if (!refs.contains(rm.group(1))) {
                            refs.add(rm.group(1));
                        }
                    }
                }
                for (String ref : refs) {
                    if (ds != null && !ds.columns().isEmpty()
                            && findDatasetColumn(ds, ref) == null) {
                        problems.add(where + ".expr: колонка «" + ref
                            + "» не описана в datasets." + dataset + ".columns");
                    }
                }
                cols.add(new TableColumn(header, expr, width, align, type,
                    bold, italic, color, background, headerBackground, resultCol, refs,
                    total, highlights));
            }
        }
        if (dataset == null || cols.isEmpty()) {
            return null;
        }
        boolean hasTotal = false;
        for (TableColumn col : cols) {
            if (col.total() != null) {
                hasTotal = true;
                break;
            }
        }
        if (!hasTotal) {
            totalLabel = null;
        }
        return new Table(dataset, cols, groups, totalLabel, filter, sort);
    }

    /**
     * Уровни группировки: старый одиночный group_by (+group_title/group_count)
     * и новый массив groups по порядку вложенности. Формы не смешиваются.
     */
    private static List<GroupLevel> parseGroups(Map<String, Object> e, int i,
            List<String> problems, DataSet ds, String dataset,
            String groupBy, String groupTitle, boolean groupCount) {
        String where = "body[" + i + "].groups";
        List<GroupLevel> groups = new ArrayList<GroupLevel>();
        Object raw = e.get("groups");
        if (raw == null) {
            if (groupBy != null) {
                groups.add(new GroupLevel(groupBy,
                    groupTitle == null ? "Группа" : groupTitle, groupCount));
            }
            return groups;
        }
        if (groupBy != null) {
            problems.add("body[" + i + "]: group_by и groups нельзя задавать вместе");
            return groups;
        }
        List<Object> list = Json.asArr(raw);
        if (list == null) {
            problems.add(where + " должен быть массивом уровней группировки");
            return groups;
        }
        if (list.isEmpty()) {
            problems.add(where + ": нужен хотя бы один уровень группировки");
            return groups;
        }
        if (list.size() > MAX_GROUP_LEVELS) {
            problems.add(where + ": уровней больше " + MAX_GROUP_LEVELS);
        }
        int k = 0;
        for (Object o : list) {
            k++;
            String w = where + "[" + k + "]";
            Map<String, Object> g = Json.asObj(o);
            if (g == null) {
                problems.add(w + " должен быть объектом {expr, title, count}");
                continue;
            }
            checkUnknown(g, problems, w, "expr", "title", "count");
            String expr = strField(g, "expr", true, problems, w);
            String title = strField(g, "title", false, problems, w);
            boolean count = Json.asBool(g.get("count"), false);
            if (title != null && title.length() > 200) {
                problems.add(w + ".title длиннее 200 символов");
                title = null;
            }
            if (expr == null) {
                continue;
            }
            String column = groupColumn(expr, w, problems);
            if (column == null) {
                continue;
            }
            if (ds != null && !ds.columns().isEmpty()
                    && findDatasetColumn(ds, column) == null) {
                problems.add(w + ".expr: колонка «" + column
                    + "» не описана в datasets." + dataset + ".columns");
            }
            for (GroupLevel prev : groups) {
                if (prev.column().equals(column)) {
                    problems.add(w + ".expr: колонка «" + column
                        + "» уже используется в другом уровне группировки");
                }
            }
            groups.add(new GroupLevel(column, title == null ? "Группа" : title, count));
        }
        return groups;
    }

    /** Колонка из выражения группировки: row["col"] или просто имя колонки. */
    private static String groupColumn(String expr, String where, List<String> problems) {
        Matcher m = COLUMN_EXPR_RE.matcher(expr);
        if (m.matches()) {
            String col = m.group(1);
            return matchesName(col) ? col : null;
        }
        if (matchesName(expr)) {
            return expr;
        }
        problems.add(where + ".expr: нужно имя колонки набора или row[\"колонка\"]");
        return null;
    }

    /** Ключи сортировки: массив или одиночный объект {expr, direction}. */
    private static List<SortKey> parseSort(Map<String, Object> e, int i,
            List<String> problems, DataSet ds, String dataset) {
        String where = "body[" + i + "].sort";
        List<SortKey> keys = new ArrayList<SortKey>();
        Object raw = e.get("sort");
        if (raw == null) {
            return keys;
        }
        List<Object> list = Json.asArr(raw);
        if (list == null) {
            if (Json.asObj(raw) == null) {
                problems.add(where + " должен быть объектом {expr, direction}"
                    + " или массивом таких объектов");
                return keys;
            }
            list = Json.arr();
            list.add(raw);
        }
        if (list.isEmpty()) {
            problems.add(where + ": пустой список ключей сортировки");
            return keys;
        }
        if (list.size() > MAX_SORT_KEYS) {
            problems.add(where + ": ключей сортировки больше " + MAX_SORT_KEYS);
        }
        int k = 0;
        for (Object o : list) {
            k++;
            String w = list.size() == 1 && Json.asObj(raw) != null
                ? where : where + "[" + k + "]";
            Map<String, Object> s = Json.asObj(o);
            if (s == null) {
                problems.add(w + " должен быть объектом {expr, direction}");
                continue;
            }
            checkUnknown(s, problems, w, "expr", "direction");
            String expr = strField(s, "expr", true, problems, w);
            String dir = strField(s, "direction", false, problems, w);
            if (dir != null) {
                dir = dir.toLowerCase(Locale.ROOT);
                if (!SORT_DIRECTIONS.contains(dir)) {
                    problems.add(w + ".direction «" + dir + "»: допустимы " + SORT_DIRECTIONS);
                    dir = null;
                }
            }
            if (expr == null) {
                continue;
            }
            if (!SIMPLE_EXPR_RE.matcher(expr).matches() || exprRefs(expr).isEmpty()) {
                problems.add(w + ".expr: нужно простое выражение с row[\"колонка\"]");
                continue;
            }
            for (String ref : exprRefs(expr)) {
                if (ds != null && !ds.columns().isEmpty()
                        && findDatasetColumn(ds, ref) == null) {
                    problems.add(w + ".expr: колонка «" + ref
                        + "» не описана в datasets." + dataset + ".columns");
                }
            }
            keys.add(new SortKey(expr, dir == null ? "asc" : dir));
        }
        return keys;
    }

    // ------------------------------------------------------------------
    // Вспомогательные
    // ------------------------------------------------------------------

    /** Ссылки row["..."]/dataSetRow["..."] в простом выражении (в порядке появления). */
    public static List<String> exprRefs(String expr) {
        List<String> refs = new ArrayList<String>();
        if (expr == null) {
            return refs;
        }
        Matcher rm = EXPR_REF_RE.matcher(expr);
        while (rm.find()) {
            if (!refs.contains(rm.group(1))) {
                refs.add(rm.group(1));
            }
        }
        return refs;
    }

    private static void checkUnknown(Map<String, Object> map, List<String> problems,
            String where, String... allowed) {
        for (String key : map.keySet()) {
            boolean ok = false;
            for (String a : allowed) {
                if (a.equals(key)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                problems.add(where + ": неизвестное поле «" + key + "»");
            }
        }
    }

    private static Map<String, Object> objField(Map<String, Object> map, String key,
            boolean required, List<String> problems, String where) {
        Object o = map.get(key);
        if (o == null) {
            if (required) {
                problems.add(where + ": отсутствует обязательный объект «" + key + "»");
            }
            return null;
        }
        Map<String, Object> m = Json.asObj(o);
        if (m == null) {
            problems.add(where + "." + key + " должен быть объектом");
        }
        return m;
    }

    private static List<Object> arrField(Map<String, Object> map, String key,
            List<String> problems, String where) {
        Object o = map.get(key);
        if (o == null) {
            return null;
        }
        List<Object> l = Json.asArr(o);
        if (l == null) {
            problems.add(where + "." + key + " должен быть массивом");
        }
        return l;
    }

    private static String strField(Map<String, Object> map, String key, boolean required,
            List<String> problems, String where) {
        Object o = map.get(key);
        if (o == null) {
            if (required) {
                problems.add(where + ": отсутствует обязательное поле «" + key + "»");
            }
            return null;
        }
        String s = Json.asStr(o);
        if (s == null) {
            problems.add(where + "." + key + " должен быть строкой");
            return null;
        }
        if (required && s.trim().isEmpty()) {
            problems.add(where + "." + key + " не может быть пустым");
            return null;
        }
        return s;
    }

    /** Необязательный цвет вида #RRGGBB. */
    private static String colorField(Map<String, Object> map, String key,
            List<String> problems, String where) {
        Object o = map.get(key);
        if (o == null) {
            return null;
        }
        String s = Json.asStr(o);
        if (s == null) {
            problems.add(where + "." + key + " должен быть строкой");
            return null;
        }
        if (!COLOR_RE.matcher(s.trim()).matches()) {
            problems.add(where + "." + key + " «" + s + "»: ожидается цвет вида «#RRGGBB»");
            return null;
        }
        return s.trim();
    }

    static boolean matchesName(String name) {
        return name != null && NAME_RE.matcher(name).matches();
    }

    /** Считает «?» вне строковых литералов и комментариев. */
    static int countParamMarks(String sql) {
        int count = 0;
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                char q = c;
                i++;
                while (i < n) {
                    char d = sql.charAt(i);
                    if (d == q) {
                        if (i + 1 < n && sql.charAt(i + 1) == q) {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int e = sql.indexOf('\n', i);
                i = e < 0 ? n : e + 1;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int e = sql.indexOf("*/", i + 2);
                i = e < 0 ? n : e + 2;
            } else {
                if (c == '?') {
                    count++;
                }
                i++;
            }
        }
        return count;
    }

    private static Parameter findParameter(List<Parameter> list, String name) {
        for (Parameter p : list) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    private static DataSource findDataSource(List<DataSource> list, String name) {
        for (DataSource d : list) {
            if (d.name().equals(name)) {
                return d;
            }
        }
        return null;
    }

    private static DataSet findDataSet(List<DataSet> list, String name) {
        for (DataSet d : list) {
            if (d.name().equals(name)) {
                return d;
            }
        }
        return null;
    }

    private static DatasetColumn findDatasetColumn(DataSet ds, String name) {
        for (DatasetColumn c : ds.columns()) {
            if (c.name().equals(name)) {
                return c;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Представление
    // ------------------------------------------------------------------

    /** Канонический JSON спецификации (для журнала/повторного показа). */
    public String toJson() {
        Map<String, Object> root = Json.obj();
        Map<String, Object> report = Json.obj();
        report.put("name", name);
        report.put("title", title);
        if (page != null) {
            Map<String, Object> pg = Json.obj();
            pg.put("format", page.format());
            pg.put("orientation", page.orientation());
            if (page.hasMargins()) {
                Map<String, Object> m = Json.obj();
                String[] keys = {"top", "right", "bottom", "left"};
                for (int i = 0; i < 4; i++) {
                    if (page.marginMm(i) != null) {
                        m.put(keys[i], page.marginMm(i));
                    }
                }
                pg.put("margins_mm", m);
            }
            if (page.hasHeader()) {
                pg.put("header", page.header());
            }
            if (page.hasFooter()) {
                pg.put("footer", page.footer());
            }
            if (!page.showHeaderOnFirst()) {
                pg.put("show_header_on_first", Boolean.FALSE);
            }
            if (!page.showFooterOnLast()) {
                pg.put("show_footer_on_last", Boolean.FALSE);
            }
            report.put("page", pg);
        }
        root.put("report", report);

        if (!parameters.isEmpty()) {
            List<Object> ps = Json.arr();
            for (Parameter p : parameters) {
                Map<String, Object> pm = Json.obj();
                pm.put("name", p.name());
                pm.put("type", p.type());
                if (p.prompt() != null) {
                    pm.put("prompt", p.prompt());
                }
                if (p.def() != null) {
                    pm.put("default", p.def());
                }
                ps.add(pm);
            }
            root.put("parameters", ps);
        }
        if (!datasources.isEmpty()) {
            List<Object> ds = Json.arr();
            for (DataSource d : datasources) {
                Map<String, Object> dm = Json.obj();
                dm.put("name", d.name());
                dm.put("kind", "jdbc");
                dm.put("driver", d.driver());
                dm.put("url", d.url());
                dm.put("user", d.user());
                if (d.hasPassword()) {
                    dm.put("password", "***");
                }
                ds.add(dm);
            }
            root.put("datasources", ds);
        }
        if (!datasets.isEmpty()) {
            List<Object> dts = Json.arr();
            for (DataSet d : datasets) {
                Map<String, Object> dm = Json.obj();
                dm.put("name", d.name());
                dm.put("datasource", d.datasource());
                dm.put("sql", d.sql());
                if (!d.params().isEmpty()) {
                    dm.put("params", new ArrayList<Object>(d.params()));
                }
                if (!d.columns().isEmpty()) {
                    List<Object> cs = Json.arr();
                    for (DatasetColumn c : d.columns()) {
                        Map<String, Object> cm = Json.obj();
                        cm.put("name", c.name());
                        cm.put("type", c.type());
                        cs.add(cm);
                    }
                    dm.put("columns", cs);
                }
                dts.add(dm);
            }
            root.put("datasets", dts);
        }
        List<Object> bs = Json.arr();
        for (BodyElement el : body) {
            if (el instanceof Label) {
                Label l = (Label) el;
                Map<String, Object> lm = Json.obj();
                lm.put("type", "label");
                lm.put("text", l.text());
                if (l.sizePt() != null) {
                    lm.put("size_pt", l.sizePt());
                }
                if (l.bold()) {
                    lm.put("bold", Boolean.TRUE);
                }
                if (l.italic()) {
                    lm.put("italic", Boolean.TRUE);
                }
                if (l.color() != null) {
                    lm.put("color", l.color());
                }
                if (l.align() != null) {
                    lm.put("align", l.align());
                }
                bs.add(lm);
            } else if (el instanceof Table) {
                Table t = (Table) el;
                Map<String, Object> tm = Json.obj();
                tm.put("type", "table");
                tm.put("dataset", t.dataset());
                if (t.hasGroups()) {
                    if (t.groups().size() == 1) {
                        GroupLevel only = t.groups().get(0);
                        tm.put("group_by", only.column());
                        if (!"Группа".equals(only.title())) {
                            tm.put("group_title", only.title());
                        }
                        if (only.count()) {
                            tm.put("group_count", Boolean.TRUE);
                        }
                    } else {
                        List<Object> gs = Json.arr();
                        for (GroupLevel g : t.groups()) {
                            Map<String, Object> gm = Json.obj();
                            gm.put("expr", "row[\"" + g.column() + "\"]");
                            if (!"Группа".equals(g.title())) {
                                gm.put("title", g.title());
                            }
                            if (g.count()) {
                                gm.put("count", Boolean.TRUE);
                            }
                            gs.add(gm);
                        }
                        tm.put("groups", gs);
                    }
                }
                if (!t.sort().isEmpty()) {
                    List<Object> ss = Json.arr();
                    for (SortKey s : t.sort()) {
                        Map<String, Object> sm = Json.obj();
                        sm.put("expr", s.expr());
                        sm.put("direction", s.direction());
                        ss.add(sm);
                    }
                    tm.put("sort", ss);
                }
                if (t.totalLabel() != null && !"Итого".equals(t.totalLabel())) {
                    tm.put("total_label", t.totalLabel());
                }
                if (t.filter() != null) {
                    tm.put("filter", t.filter());
                }
                List<Object> cs = Json.arr();
                for (TableColumn c : t.columns()) {
                    Map<String, Object> cm = Json.obj();
                    cm.put("header", c.header());
                    cm.put("expr", c.expr());
                    if (c.widthMm() != null) {
                        cm.put("width_mm", c.widthMm());
                    }
                    if (c.align() != null) {
                        cm.put("align", c.align());
                    }
                    if (c.type() != null) {
                        cm.put("type", c.type());
                    }
                    if (c.bold()) {
                        cm.put("bold", Boolean.TRUE);
                    }
                    if (c.italic()) {
                        cm.put("italic", Boolean.TRUE);
                    }
                    if (c.color() != null) {
                        cm.put("color", c.color());
                    }
                    if (c.background() != null) {
                        cm.put("background", c.background());
                    }
                    if (c.headerBackground() != null) {
                        cm.put("header_background", c.headerBackground());
                    }
                    if (c.total() != null) {
                        cm.put("total", c.total());
                    }
                    if (!c.highlights().isEmpty()) {
                        List<Object> hs = Json.arr();
                        for (Highlight h : c.highlights()) {
                            Map<String, Object> hm = Json.obj();
                            hm.put("when", h.when());
                            if (h.color() != null) {
                                hm.put("color", h.color());
                            }
                            if (h.background() != null) {
                                hm.put("background", h.background());
                            }
                            if (h.bold()) {
                                hm.put("bold", Boolean.TRUE);
                            }
                            if (h.italic()) {
                                hm.put("italic", Boolean.TRUE);
                            }
                            hs.add(hm);
                        }
                        cm.put("highlight", hs);
                    }
                    cs.add(cm);
                }
                tm.put("columns", cs);
                bs.add(tm);
            } else if (el instanceof Grid) {
                Grid g = (Grid) el;
                Map<String, Object> gm = Json.obj();
                gm.put("type", "grid");
                List<Object> w = Json.arr();
                for (Double d : g.columnWidthsMm()) {
                    w.add(d);
                }
                gm.put("column_widths_mm", w);
                List<Object> rs = Json.arr();
                for (List<Cell> row : g.rows()) {
                    Map<String, Object> rm = Json.obj();
                    List<Object> cs = Json.arr();
                    for (Cell cell : row) {
                        cs.add(cellToJson(cell));
                    }
                    rm.put("cells", cs);
                    rs.add(rm);
                }
                gm.put("rows", rs);
                bs.add(gm);
            } else if (el instanceof Image) {
                Image im = (Image) el;
                Map<String, Object> im2 = Json.obj();
                im2.put("type", "image");
                if (im.base64Png() != null) {
                    im2.put("base64", im.base64Png());
                } else {
                    im2.put("path", im.path());
                }
                if (im.widthMm() != null) {
                    im2.put("width_mm", im.widthMm());
                }
                if (im.heightMm() != null) {
                    im2.put("height_mm", im.heightMm());
                }
                bs.add(im2);
            }
        }
        root.put("body", bs);
        return Json.write(root);
    }

    /** Превращает ячейку grid в карту для канонического JSON. */
    private static Map<String, Object> cellToJson(Cell cell) {
        Map<String, Object> m = Json.obj();
        if (!"label".equals(cell.type())) {
            m.put("type", cell.type());
        }
        m.put("text", cell.text() == null ? "" : cell.text());
        if (cell.expr() != null) {
            m.put("expr", cell.expr());
        }
        if (cell.dataset() != null) {
            m.put("dataset", cell.dataset());
        }
        if (cell.align() != null) {
            m.put("align", cell.align());
        }
        if (cell.background() != null) {
            m.put("background", cell.background());
        }
        if (cell.color() != null) {
            m.put("color", cell.color());
        }
        if (cell.bold()) {
            m.put("bold", Boolean.TRUE);
        }
        if (cell.italic()) {
            m.put("italic", Boolean.TRUE);
        }
        if (cell.fontPt() != null) {
            m.put("font_pt", cell.fontPt());
        }
        if (cell.colSpan() != null && cell.colSpan() > 1) {
            m.put("col_span", cell.colSpan());
        }
        if (cell.rowSpan() != null && cell.rowSpan() > 1) {
            m.put("row_span", cell.rowSpan());
        }
        return m;
    }

    /** Краткое человекочитаемое описание (для панели чата). */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Отчёт: ").append(title);
        sb.append(" (файл ").append(name).append(".rptdesign)\n");
        if (page != null) {
            sb.append("Страница: ").append(page.format()).append(", ")
                .append("portrait".equals(page.orientation()) ? "книжная" : "альбомная");
            if (page.hasMargins()) {
                sb.append(", поля (мм): ").append(fmt(page.marginMm(0))).append('/')
                    .append(fmt(page.marginMm(1))).append('/').append(fmt(page.marginMm(2)))
                    .append('/').append(fmt(page.marginMm(3)));
            }
            sb.append('\n');
        }
        if (page != null && (page.hasHeader() || page.hasFooter())) {
            sb.append("Колонтитулы: ");
            if (page.hasHeader()) {
                sb.append("верх «").append(ellipsis(page.header(), 40)).append('»');
            }
            if (page.hasFooter()) {
                if (page.hasHeader()) {
                    sb.append(", ");
                }
                sb.append("низ «").append(ellipsis(page.footer(), 40)).append('»');
            }
            sb.append('\n');
        }
        if (!parameters.isEmpty()) {
            sb.append("Параметры: ");
            for (int i = 0; i < parameters.size(); i++) {
                Parameter p = parameters.get(i);
                if (i > 0) {
                    sb.append("; ");
                }
                sb.append(p.name()).append(" (").append(typeRu(p.type())).append(')');
                if (p.def() != null) {
                    sb.append(", по умолчанию «").append(p.def()).append('»');
                }
                if (p.prompt() != null) {
                    sb.append(" — ").append(p.prompt());
                }
            }
            sb.append('\n');
        }
        for (DataSource d : datasources) {
            sb.append("Источник: ").append(d.name()).append(" → ")
                .append(d.driver()).append(", ").append(d.url())
                .append(", пользователь ").append(d.user());
            if (!d.hasPassword()) {
                sb.append(" (пароль не задан — будет запрошен)");
            }
            sb.append('\n');
        }
        for (DataSet d : datasets) {
            sb.append("Набор данных: ").append(d.name()).append(" → ")
                .append(d.datasource()).append(", параметров: ")
                .append(d.params().size());
            if (!d.columns().isEmpty()) {
                sb.append(", колонок: ").append(d.columns().size());
            }
            sb.append('\n');
        }
        sb.append("Содержимое: ");
        for (int i = 0; i < body.size(); i++) {
            BodyElement el = body.get(i);
            if (i > 0) {
                sb.append("; ");
            }
            if (el instanceof Label) {
                sb.append("надпись «").append(ellipsis(((Label) el).text(), 40)).append('»');
            } else if (el instanceof Table) {
                Table t = (Table) el;
                sb.append("таблица ").append(t.columns().size()).append(" кол.");
                sb.append(" (").append(t.dataset()).append("): ");
                for (int j = 0; j < t.columns().size(); j++) {
                    if (j > 0) {
                        sb.append(", ");
                    }
                    sb.append(t.columns().get(j).header());
                }
                if (t.hasGroups()) {
                    sb.append("; группировка: ");
                    for (int j = 0; j < t.groups().size(); j++) {
                        GroupLevel g = t.groups().get(j);
                        if (j > 0) {
                            sb.append(" → ");
                        }
                        sb.append("«").append(g.title()).append("» по ").append(g.column());
                        if (g.count()) {
                            sb.append(" (со счётчиком)");
                        }
                    }
                }
                if (!t.sort().isEmpty()) {
                    sb.append("; сортировка: ");
                    for (int j = 0; j < t.sort().size(); j++) {
                        SortKey s = t.sort().get(j);
                        if (j > 0) {
                            sb.append(", ");
                        }
                        sb.append(s.expr()).append(' ').append(s.direction());
                    }
                }
                if (t.filter() != null) {
                    sb.append("; отбор строк: ").append(ellipsis(t.filter(), 40));
                }
                int totals = 0;
                int highlights = 0;
                for (int j = 0; j < t.columns().size(); j++) {
                    if (t.columns().get(j).total() != null) {
                        totals++;
                    }
                    highlights += t.columns().get(j).highlights().size();
                }
                if (totals > 0) {
                    sb.append("; итоги: ").append(totals);
                }
                if (highlights > 0) {
                    sb.append("; условий оформления: ").append(highlights);
                }
            }
        }
        return sb.toString();
    }

    private static String typeRu(String type) {
        if ("string".equals(type)) {
            return "строка";
        }
        if ("integer".equals(type)) {
            return "целое";
        }
        if ("decimal".equals(type)) {
            return "число";
        }
        if ("date".equals(type)) {
            return "дата";
        }
        if ("datetime".equals(type)) {
            return "дата и время";
        }
        if ("boolean".equals(type)) {
            return "логический";
        }
        return type;
    }

    private static String fmt(Double d) {
        if (d == null) {
            return "—";
        }
        if (d.doubleValue() == Math.rint(d.doubleValue())) {
            return String.valueOf((long) d.doubleValue());
        }
        return String.valueOf(d);
    }

    private static String ellipsis(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    /** Для тестов: карта «поле → значение» верхнего уровня. */
    Map<String, Object> rawMap() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("name", name);
        return m;
    }
}
