/*
 * AI BIRT — набор инструментов агентного режима (ADR-0003).
 *
 * 17 инструментов, по одному классу-обработчику на каждый. Все мутации
 * идут через DesignSession.exec() (транзакция CommandStack, откат при
 * ошибке); все аргументы проходят строгий разбор фрагментов ReportSpec
 * (unknown-поля запрещены, SQL только SELECT/WITH, перекрёстные ссылки
 * по манифесту сессии).
 *
 * Инструменты не знают про модель и протокол: имя+описание+схема
 * (для tools[] OpenAI) и исполнение над ToolContext.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core.tools;

import com.aisql.birt.core.BirtSettings;
import com.aisql.birt.core.Json;
import com.aisql.birt.core.jdbc.DbSchema;
import com.aisql.birt.core.report.ReportBuilder;
import com.aisql.birt.core.report.ReportIntrospector;
import com.aisql.birt.core.spec.ReportSpec;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.birt.report.model.api.CachedMetaDataHandle;
import org.eclipse.birt.report.model.api.CellHandle;
import org.eclipse.birt.report.model.api.DesignElementHandle;
import org.eclipse.birt.report.model.api.ElementFactory;
import org.eclipse.birt.report.model.api.OdaDataSetHandle;
import org.eclipse.birt.report.model.api.OdaDataSourceHandle;
import org.eclipse.birt.report.model.api.ReportDesignHandle;
import org.eclipse.birt.report.model.api.RowHandle;
import org.eclipse.birt.report.model.api.ScalarParameterHandle;
import org.eclipse.birt.report.model.api.SlotHandle;
import org.eclipse.birt.report.model.api.SimpleMasterPageHandle;
import org.eclipse.birt.report.model.api.TableHandle;
import org.eclipse.birt.report.model.api.elements.DesignChoiceConstants;
import org.eclipse.birt.report.model.api.elements.structures.CachedMetaData;
import org.eclipse.birt.report.model.api.elements.structures.ColumnHint;
import org.eclipse.birt.report.model.api.elements.structures.OdaDataSetParameter;
import org.eclipse.birt.report.model.api.elements.structures.ResultSetColumn;
import org.eclipse.birt.report.model.api.StructureFactory;

/** Все инструменты агента. */
final class ReportTools {

    private final List<ReportTool> all = new ArrayList<ReportTool>();

    ReportTools() {
        all.add(new ReportNewTool());
        all.add(new ReportOpenTool());
        all.add(new ReportInspectTool());
        all.add(new ReportValidateTool());
        all.add(new ReportDoneTool());
        all.add(new AddDataSourceTool());
        all.add(new AddParameterTool());
        all.add(new AddDataSetTool());
        all.add(new SetPageTool());
        all.add(new AddLabelTool());
        all.add(new AddTableTool());
        all.add(new AddGridTool());
        all.add(new AddImageTool());
        all.add(new AddChartTool());
        all.add(new AddListTool());
        all.add(new RemoveElementTool());
        all.add(new DbSchemaTool());
    }

    List<ReportTool> tools() {
        return all;
    }

    // ==================================================================
    // Вспомогательное
    // ==================================================================

    private static final String ODA_JDBC_DATA_SOURCE = "org.eclipse.birt.report.data.oda.jdbc";
    private static final String ODA_JDBC_DATA_SET =
        "org.eclipse.birt.report.data.oda.jdbc.JdbcSelectDataSet";

    private static final Map<String, String> PARAM_TYPES =
        new LinkedHashMap<String, String>();

    static {
        PARAM_TYPES.put("string", DesignChoiceConstants.PARAM_TYPE_STRING);
        PARAM_TYPES.put("integer", DesignChoiceConstants.PARAM_TYPE_INTEGER);
        PARAM_TYPES.put("decimal", DesignChoiceConstants.PARAM_TYPE_DECIMAL);
        PARAM_TYPES.put("date", DesignChoiceConstants.PARAM_TYPE_DATE);
        PARAM_TYPES.put("datetime", DesignChoiceConstants.PARAM_TYPE_DATETIME);
        PARAM_TYPES.put("boolean", DesignChoiceConstants.PARAM_TYPE_BOOLEAN);
    }

    /** Схема строкового свойства. */
    private static Map<String, Object> str() {
        Map<String, Object> m = Json.obj();
        m.put("type", "string");
        return m;
    }

    private static Map<String, Object> num() {
        Map<String, Object> m = Json.obj();
        m.put("type", "number");
        return m;
    }

    private static Map<String, Object> bool() {
        Map<String, Object> m = Json.obj();
        m.put("type", "boolean");
        return m;
    }

    private static Map<String, Object> arr() {
        Map<String, Object> m = Json.obj();
        m.put("type", "array");
        return m;
    }

    private static Map<String, Object> obj() {
        Map<String, Object> m = Json.obj();
        m.put("type", "object");
        return m;
    }

    private static List<String> req(String... names) {
        return Arrays.asList(names);
    }

    private static ToolResult problems(ReportSpec.SpecException e) {
        return ToolResult.error(e.getMessage(), e.problems());
    }

    // ==================================================================
    // Сессия
    // ==================================================================

    /** report_new: новый пустой дизайн. */
    private static final class ReportNewTool implements ReportTool {

        public String name() {
            return "report_new";
        }

        public String description() {
            return "Начать новый отчёт: создаёт пустый дизайн. Вызывается первым."
                + " Аргументы: name — имя файла (латиница/цифры/_),"
                + " title — заголовок отчёта.";
        }

        public Map<String, Object> properties() {
            Map<String, Object> p = Json.obj();
            p.put("name", str());
            p.put("title", str());
            return p;
        }

        public List<String> required() {
            return req("name", "title");
        }

        public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
            String name = Json.asStr(args.get("name"));
            String title = Json.asStr(args.get("title"));
            if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_]{0,59}")) {
                return ToolResult.error("name: латиница/цифры/_, начало — буква, до 60 символов");
            }
            try {
                ctx.session.create();
            } catch (com.aisql.birt.core.report.DesignSession.DesignException e) {
                return ToolResult.error(e.getMessage());
            }
            ctx.session.setReportName(name);
            ctx.session.setReportTitle(title);
            return ToolResult.ok("name", name);
        }
    }

    /** report_open: открыть существующий .rptdesign для правки. */
    private static final class ReportOpenTool implements ReportTool {

        public String name() {
            return "report_open";
        }

        public String description() {
            return "Открыть существующий файл отчёта .rptdesign для правки."
                + " После открытия вызови report_inspect, чтобы узнать структуру.";
        }

        public Map<String, Object> properties() {
            Map<String, Object> p = Json.obj();
            p.put("path", str());
            return p;
        }

        public List<String> required() {
            return req("path");
        }

        public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
            if (!com.aisql.birt.core.security.BirtEnterprisePolicy.editExistingAllowed()) {
                return ToolResult.error("правка существующих отчётов запрещена"
                    + " политикой предприятия");
            }
            String path = Json.asStr(args.get("path"));
            File f = new File(path == null ? "" : path);
            if (!f.isFile()) {
                return ToolResult.error("файл не найден: " + path);
            }
            if (!f.getName().toLowerCase().endsWith(".rptdesign")) {
                return ToolResult.error("ожидается файл .rptdesign");
            }
            try {
                ctx.session.open(f);
            } catch (com.aisql.birt.core.report.DesignSession.DesignException e) {
                return ToolResult.error(e.getMessage());
            }
            if (ctx.targetFile == null) {
                ctx.targetFile = f;
            }
            Map<String, Object> payload = Json.obj();
            payload.put("file", f.getAbsolutePath());
            payload.put("hint", "вызови report_inspect, чтобы увидеть структуру отчёта");
            return ToolResult.ok(payload);
        }
    }

    /** report_inspect: структура отчёта (JSON). */
    private static final class ReportInspectTool implements ReportTool {

        public String name() {
            return "report_inspect";
        }

        public String description() {
            return "Показать структуру открытого отчёта: источники, наборы данных"
                + " (SQL и колонки), параметры, элементы тела. Только метаданные,"
                + " без строк данных.";
        }

        public Map<String, Object> properties() {
            return Json.obj();
        }

        public List<String> required() {
            return Collections.<String>emptyList();
        }

        public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
            if (!ctx.session.isOpen()) {
                return ToolResult.error("дизайн не открыт");
            }
            return ToolResult.ok("structure", Json.parse(ctx.session.inspectJson()));
        }
    }

    /** report_validate: семантические проверки BIRT + подсказки. */
    private static final class ReportValidateTool implements ReportTool {

        public String name() {
            return "report_validate";
        }

        public String description() {
            return "Проверить отчёт семантическими проверками BIRT: ошибки"
                + " дизайн-модели с подсказками. Вызывай перед report_done.";
        }

        public Map<String, Object> properties() {
            return Json.obj();
        }

        public List<String> required() {
            return Collections.<String>emptyList();
        }

        public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
            if (!ctx.session.isOpen()) {
                return ToolResult.error("дизайн не открыт");
            }
            com.aisql.birt.core.report.DesignSession.ValidationResult v =
                ctx.session.validate();
            Map<String, Object> payload = Json.obj();
            payload.put("valid", Boolean.valueOf(v.ok));
            payload.put("problems", v.problems);
            return ToolResult.ok(payload);
        }
    }

    /** report_done: завершить работу, сохранить отчёт. */
    private static final class ReportDoneTool implements ReportTool {

        public String name() {
            return "report_done";
        }

        public String description() {
            return "Завершить сборку/правку: сохраняет отчёт и возвращает путь."
                + " summary — краткое описание сделанного для пользователя.";
        }

        public Map<String, Object> properties() {
            Map<String, Object> p = Json.obj();
            p.put("summary", str());
            return p;
        }

        public List<String> required() {
            return req("summary");
        }

        public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
            if (!ctx.session.isOpen()) {
                return ToolResult.error("дизайн не открыт");
            }
            if (ctx.targetFile == null) {
                String name = ctx.session.reportName();
                ctx.targetFile = new File(System.getProperty("java.io.tmpdir",
                    "."), name + ".rptdesign");
            }
            try {
                File saved = ctx.session.saveAtomically(ctx.targetFile);
                Map<String, Object> payload = Json.obj();
                payload.put("file", saved.getAbsolutePath());
                payload.put("summary", Json.asStr(args.get("summary")));
                payload.put("operations", Integer.valueOf(ctx.session.opCount()));
                return ToolResult.ok(payload);
            } catch (com.aisql.birt.core.report.DesignSession.DesignException e) {
                return ToolResult.error(e.getMessage());
            }
        }
    }

    // ==================================================================
    // Данные
    // ==================================================================

    /** add_datasource: JDBC-источник (без пароля — его вводит пользователь). */
    private static final class AddDataSourceTool implements ReportTool {

        public String name() {
            return "add_datasource";
        }

        public String description() {
            return "Добавить JDBC-источник данных. Пароль НЕ передавай — его"
                + " вводит пользователь. kind всегда jdbc.";
        }

        public Map<String, Object> properties() {
            Map<String, Object> p = Json.obj();
            p.put("name", str());
            p.put("kind", str());
            p.put("driver", str());
            p.put("url", str());
            p.put("user", str());
            return p;
        }

        public List<String> required() {
            return req("name", "kind", "driver", "url", "user");
        }

        public ToolResult execute(final Map<String, Object> args, ToolContext ctx) {
            if (!requireMutation(ctx)) {
                return ToolResult.error("превышен лимит операций сессии");
            }
            if (!ctx.session.isOpen()) {
                return ToolResult.error("дизайн не открыт");
            }
            ReportSpec.DataSource src;
            try {
                src = ReportSpec.parseDataSourceFragment(args);
            } catch (ReportSpec.SpecException e) {
                return problems(e);
            }
            if (findDataSource(ctx, src.name()) != null) {
                return ToolResult.error("источник «" + src.name() + "» уже есть");
            }
            final ReportSpec.DataSource fSrc = src;
            try {
                ctx.session.exec("add_datasource " + src.name(),
                    new com.aisql.birt.core.report.DesignSession.ElementOp() {
                        public void run(ReportDesignHandle design, ElementFactory factory)
                                throws Exception {
                            OdaDataSourceHandle ds =
                                factory.newOdaDataSource(fSrc.name(), ODA_JDBC_DATA_SOURCE);
                            ds.setProperty("odaDriverClass", fSrc.driver());
                            ds.setProperty("odaURL", fSrc.url());
                            ds.setProperty("odaUser", fSrc.user());
                            String password = fSrc.hasPassword()
                                ? fSrc.password() : ctx.dbPassword;
                            if (password != null && !password.isEmpty()) {
                                ds.setProperty("odaPassword", password);
                            }
                            design.getDataSources().add(ds);
                        }
                    });
            } catch (com.aisql.birt.core.report.DesignSession.DesignException e) {
                return ToolResult.error(e.getMessage());
            }
            ctx.session.addManifestDataSource(src);
            ctx.mutations++;
            return ToolResult.ok("name", src.name());
        }
    }

    /** add_parameter: параметр отчёта. */
    private static final class AddParameterTool implements ReportTool {

        public String name() {
            return "add_parameter";
        }

        public String description() {
            return "Добавить параметр отчёта (для фильтров, ввода пользователя)."
                + " type: string|integer|decimal|date|datetime|boolean.";
        }

        public Map<String, Object> properties() {
            Map<String, Object> p = Json.obj();
            p.put("name", str());
            p.put("type", str());
            p.put("prompt", str());
            p.put("default", str());
            return p;
        }

        public List<String> required() {
            return req("name", "type");
        }

        public ToolResult execute(final Map<String, Object> args, ToolContext ctx) {
            if (!requireMutation(ctx)) {
                return ToolResult.error("превышен лимит операций сессии");
            }
            if (!ctx.session.isOpen()) {
                return ToolResult.error("дизайн не открыт");
            }
            ReportSpec.Parameter param;
            try {
                param = ReportSpec.parseParameterFragment(args);
            } catch (ReportSpec.SpecException e) {
                return problems(e);
            }
            if (findParameter(ctx, param.name()) != null) {
                return ToolResult.error("параметр «" + param.name() + "» уже есть");
            }
            final ReportSpec.Parameter fParam = param;
            try {
                ctx.session.exec("add_parameter " + param.name(),
                    new com.aisql.birt.core.report.DesignSession.ElementOp() {
                        public void run(ReportDesignHandle design, ElementFactory factory)
                                throws Exception {
                            ScalarParameterHandle p =
                                factory.newScalarParameter(fParam.name());
                            String type = PARAM_TYPES.get(fParam.type());
                            p.setDataType(type == null
                                ? DesignChoiceConstants.PARAM_TYPE_STRING : type);
                            if (fParam.prompt() != null && !fParam.prompt().isEmpty()) {
                                try {
                                    p.setPromptText(fParam.prompt());
                                } catch (Throwable ignored) {
                                    // подпись не критична
                                }
                            }
                            if (fParam.def() != null) {
                                p.setDefaultValue(fParam.def());
                            }
                            design.getParameters().add(p);
                        }
                    });
            } catch (com.aisql.birt.core.report.DesignSession.DesignException e) {
                return ToolResult.error(e.getMessage());
            }
            ctx.session.addManifestParameter(param);
            ctx.mutations++;
            return ToolResult.ok("name", param.name());
        }
    }

    /** add_dataset: набор данных (SELECT/WITH + колонки + привязка «?»). */
    private static final class AddDataSetTool implements ReportTool {

        public String name() {
            return "add_dataset";
        }

        public String description() {
            return "Добавить набор данных: SQL (только SELECT или WITH, «?» вместо"
                + " литералов), params — имена параметров по одному на каждый «?»"
                + " (по порядку), columns — колонки результата {name,type}.";
        }

        public Map<String, Object> properties() {
            Map<String, Object> p = Json.obj();
            p.put("name", str());
            p.put("datasource", str());
            p.put("sql", str());
            p.put("params", arr());
            p.put("columns", arr());
            return p;
        }

        public List<String> required() {
            return req("name", "datasource", "sql");
        }

        public ToolResult execute(final Map<String, Object> args, ToolContext ctx) {
            if (!requireMutation(ctx)) {
                return ToolResult.error("превышен лимит операций сессии");
            }
            if (!ctx.session.isOpen()) {
                return ToolResult.error("дизайн не открыт");
            }
            ReportSpec.DataSet set;
            try {
                set = ReportSpec.parseDataSetFragment(args,
                    ctx.session.manifestDataSources(), ctx.session.manifestParameters());
            } catch (ReportSpec.SpecException e) {
                return problems(e);
            }
            if (findDataSet(ctx, set.name()) != null) {
                return ToolResult.error("набор «" + set.name() + "» уже есть");
            }
            final ReportSpec.DataSet fSet = set;
            final ReportSpec manifest = ctx.session.manifest();
            try {
                ctx.session.exec("add_dataset " + set.name(),
                    new com.aisql.birt.core.report.DesignSession.ElementOp() {
                        public void run(ReportDesignHandle design, ElementFactory factory)
                                throws Exception {
                            OdaDataSetHandle q =
                                factory.newOdaDataSet(fSet.name(), ODA_JDBC_DATA_SET);
                            design.getDataSets().add(q);
                            q.setDataSource(fSet.datasource());
                            q.setQueryText(fSet.sql());

                            int position = 0;
                            for (String paramName : fSet.params()) {
                                position++;
                                OdaDataSetParameter dsp =
                                    StructureFactory.createOdaDataSetParameter();
                                dsp.setName("param_" + position);
                                dsp.setParamName(paramName);
                                dsp.setNativeName("");
                                dsp.setPosition(Integer.valueOf(position));
                                dsp.setDataType(paramType(manifest, paramName));
                                dsp.setIsInput(true);
                                dsp.setIsOutput(false);
                                q.getPropertyHandle("parameters").addItem(dsp);
                            }

                            if (!fSet.columns().isEmpty()) {
                                CachedMetaData cached =
                                    StructureFactory.createCachedMetaData();
                                q.setCachedMetaData(cached);
                                CachedMetaDataHandle cm = q.getCachedMetaDataHandle();
                                int i = 0;
                                for (ReportSpec.DatasetColumn col : fSet.columns()) {
                                    i++;
                                    ResultSetColumn rsc =
                                        StructureFactory.createResultSetColumn();
                                    rsc.setPosition(Integer.valueOf(i));
                                    rsc.setColumnName(col.name());
                                    rsc.setDataType(col.type());
                                    cm.getResultSet().addItem(rsc);

                                    ColumnHint hint = StructureFactory.createColumnHint();
                                    hint.setProperty("columnName", col.name());
                                    hint.setProperty("analysis",
                                        "integer".equals(col.type())
                                            || "decimal".equals(col.type())
                                            ? "measure" : "dimension");
                                    hint.setProperty("displayName", col.name());
                                    hint.setProperty("heading", col.name());
                                    q.getPropertyHandle("columnHints").addItem(hint);
                                }
                            }
                        }
                    });
            } catch (com.aisql.birt.core.report.DesignSession.DesignException e) {
                return ToolResult.error(e.getMessage());
            }
            ctx.session.addManifestDataSet(set);
            ctx.mutations++;
            return ToolResult.ok("name", set.name());
        }
    }

    /** set_page: страница (формат, поля, колонтитулы). */
    private static final class SetPageTool implements ReportTool {

        public String name() {
            return "set_page";
        }

        public String description() {
            return "Настроить страницу отчёта: format (A4/A3/A5/Letter/Legal/Ledger),"
                + " orientation (portrait|landscape), margins_mm"
                + " {top,right,bottom,left}, header/footer с подстановками"
                + " {page} {pages} {title} {date}.";
        }

        public Map<String, Object> properties() {
            Map<String, Object> p = Json.obj();
            p.put("format", str());
            p.put("orientation", str());
            p.put("margins_mm", obj());
            p.put("header", str());
            p.put("footer", str());
            p.put("show_header_on_first", bool());
            p.put("show_footer_on_last", bool());
            return p;
        }

        public List<String> required() {
            return Collections.<String>emptyList();
        }

        public ToolResult execute(final Map<String, Object> args, ToolContext ctx) {
            if (!requireMutation(ctx)) {
                return ToolResult.error("превышен лимит операций сессии");
            }
            if (!ctx.session.isOpen()) {
                return ToolResult.error("дизайн не открыт");
            }
            ReportSpec.Page page;
            try {
                page = ReportSpec.parsePageFragment(args);
            } catch (ReportSpec.SpecException e) {
                return problems(e);
            }
            final ReportSpec spec = ReportSpec.compose(
                ctx.session.reportName(), ctx.session.reportTitle(), page,
                ctx.session.manifestParameters(), ctx.session.manifestDataSources(),
                ctx.session.manifestDataSets(),
                new ArrayList<ReportSpec.BodyElement>());
            try {
                ctx.session.exec("set_page",
                    new com.aisql.birt.core.report.DesignSession.ElementOp() {
                        public void run(ReportDesignHandle design, ElementFactory factory)
                                throws Exception {
                            // повторная настройка страницы: убрать прежнюю мастер-страницу
                            SlotHandle pages = design.getMasterPages();
                            while (pages.getCount() > 0) {
                                pages.drop(0);
                            }
                            ReportBuilder.applyPage(design, factory, spec);
                        }
                    });
            } catch (com.aisql.birt.core.report.DesignSession.DesignException e) {
                return ToolResult.error(e.getMessage());
            }
            ctx.session.setManifestPage(page);
            ctx.mutations++;
            return ToolResult.ok("page", page.format() == null ? "A4" : page.format());
        }
    }

    // ==================================================================
    // Элементы тела
    // ==================================================================

    /** add_label: надпись. */
    private static final class AddLabelTool implements ReportTool {

        public String name() {
            return "add_label";
        }

        public String description() {
            return "Добавить надпись: текст, size_pt, bold, italic, color (#RRGGBB),"
                + " align (left|center|right).";
        }

        public Map<String, Object> properties() {
            Map<String, Object> p = Json.obj();
            p.put("type", str());
            p.put("text", str());
            p.put("size_pt", num());
            p.put("bold", bool());
            p.put("italic", bool());
            p.put("color", str());
            p.put("align", str());
            return p;
        }

        public List<String> required() {
            return req("type", "text");
        }

        public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
            return addBodyElement("label", args, ctx);
        }
    }

    /** add_table: таблица (колонки, группы, сортировка, итоги, подсветка, фильтр). */
    private static final class AddTableTool implements ReportTool {

        public String name() {
            return "add_table";
        }

        public String description() {
            return "Добавить таблицу по набору данных: dataset, columns[]"
                + " {header, expr, width_mm, align, total, highlight[]}, groups[],"
                + " sort[], total_label, filter.";
        }

        public Map<String, Object> properties() {
            Map<String, Object> p = Json.obj();
            p.put("type", str());
            p.put("dataset", str());
            p.put("columns", arr());
            p.put("group_by", str());
            p.put("group_title", str());
            p.put("group_count", bool());
            p.put("groups", arr());
            p.put("sort", arr());
            p.put("total_label", str());
            p.put("filter", str());
            return p;
        }

        public List<String> required() {
            return req("type", "dataset", "columns");
        }

        public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
            return addBodyElement("table", args, ctx);
        }
    }

    /** add_grid: сетка. */
    private static final class AddGridTool implements ReportTool {

        public String name() {
            return "add_grid";
        }

        public String description() {
            return "Добавить сетку: column_widths_mm[], rows[].cells[]"
                + " {type: text|data|label, text/expr/dataset, col_span, row_span}.";
        }

        public Map<String, Object> properties() {
            Map<String, Object> p = Json.obj();
            p.put("type", str());
            p.put("column_widths_mm", arr());
            p.put("rows", arr());
            return p;
        }

        public List<String> required() {
            return req("type", "column_widths_mm", "rows");
        }

        public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
            return addBodyElement("grid", args, ctx);
        }
    }

    /** add_image: изображение. */
    private static final class AddImageTool implements ReportTool {

        public String name() {
            return "add_image";
        }

        public String description() {
            return "Добавить изображение PNG: path к файлу или base64,"
                + " width_mm, height_mm.";
        }

        public Map<String, Object> properties() {
            Map<String, Object> p = Json.obj();
            p.put("type", str());
            p.put("path", str());
            p.put("base64", str());
            p.put("width_mm", num());
            p.put("height_mm", num());
            return p;
        }

        public List<String> required() {
            return req("type");
        }

        public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
            return addBodyElement("image", args, ctx);
        }
    }

    /** add_chart: диаграмма. */
    private static final class AddChartTool implements ReportTool {

        public String name() {
            return "add_chart";
        }

        public String description() {
            return "Добавить диаграмму: kind (bar|line|pie), dataset, category,"
                + " value, series, aggregate (sum|count|avg|min|max), title,"
                + " width_mm, height_mm.";
        }

        public Map<String, Object> properties() {
            Map<String, Object> p = Json.obj();
            p.put("type", str());
            p.put("kind", str());
            p.put("dataset", str());
            p.put("title", str());
            p.put("category", str());
            p.put("value", str());
            p.put("series", str());
            p.put("aggregate", str());
            p.put("width_mm", num());
            p.put("height_mm", num());
            return p;
        }

        public List<String> required() {
            return req("type", "kind", "dataset");
        }

        public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
            return addBodyElement("chart", args, ctx);
        }
    }

    /** add_list: список. */
    private static final class AddListTool implements ReportTool {

        public String name() {
            return "add_list";
        }

        public String description() {
            return "Добавить список по набору данных: dataset, columns[],"
                + " header, footer, groups[], sort[], total_label.";
        }

        public Map<String, Object> properties() {
            Map<String, Object> p = Json.obj();
            p.put("type", str());
            p.put("dataset", str());
            p.put("columns", arr());
            p.put("header", bool());
            p.put("footer", bool());
            p.put("groups", arr());
            p.put("sort", arr());
            p.put("total_label", str());
            return p;
        }

        public List<String> required() {
            return req("type", "dataset", "columns");
        }

        public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
            return addBodyElement("list", args, ctx);
        }
    }

    /** remove_element: удалить элемент по имени. */
    private static final class RemoveElementTool implements ReportTool {

        public String name() {
            return "remove_element";
        }

        public String description() {
            return "Удалить элемент из тела отчёта по его имени (см."
                + " report_inspect). Необратимо — используй осознанно.";
        }

        public Map<String, Object> properties() {
            Map<String, Object> p = Json.obj();
            p.put("name", str());
            return p;
        }

        public List<String> required() {
            return req("name");
        }

        public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
            if (!requireMutation(ctx)) {
                return ToolResult.error("превышен лимит операций сессии");
            }
            if (!ctx.session.isOpen()) {
                return ToolResult.error("дизайн не открыт");
            }
            final String name = Json.asStr(args.get("name"));
            if (name == null || name.isEmpty()) {
                return ToolResult.error("name обязателен");
            }
            final DesignElementHandle el = ctx.session.findElement(name);
            if (el == null) {
                return ToolResult.error("элемент «" + name + "» не найден;"
                    + " вызови report_inspect для списка имён");
            }
            try {
                ctx.session.exec("remove_element " + name,
                    new com.aisql.birt.core.report.DesignSession.ElementOp() {
                        public void run(ReportDesignHandle design, ElementFactory factory)
                                throws Exception {
                            el.drop();
                        }
                    });
            } catch (com.aisql.birt.core.report.DesignSession.DesignException e) {
                return ToolResult.error(e.getMessage());
            }
            ctx.mutations++;
            return ToolResult.ok("removed", name);
        }
    }

    /** db_schema: метаданные подключённой базы (имена таблиц/колонок). */
    private static final class DbSchemaTool implements ReportTool {

        public String name() {
            return "db_schema";
        }

        public String description() {
            return "Прочитать схему БД из настроек плагина: только имена таблиц"
                + " и колонок (метаданные), без строк данных. Используй, чтобы"
                + " узнавать реальные имена таблиц/колонок для SQL.";
        }

        public Map<String, Object> properties() {
            Map<String, Object> p = Json.obj();
            p.put("filter", str());
            return p;
        }

        public List<String> required() {
            return Collections.<String>emptyList();
        }

        public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
            String url = BirtSettings.dbUrl();
            String user = BirtSettings.dbUser();
            if (url == null || url.isEmpty() || !url.startsWith("jdbc:")) {
                return ToolResult.error("настройки БД не заданы (db.url в"
                    + " ai-birt.properties); попроси пользователя настроить"
                    + " подключение");
            }
            String password = ctx.dbPassword;
            if (password == null || password.isEmpty()) {
                password = BirtSettings.dbPassword();
            }
            try {
                DbSchema.Schema schema = DbSchema.read(url, user, password);
                String filter = Json.asStr(args.get("filter"));
                String text = DbSchema.toText(schema,
                    DbSchema.DEFAULT_MAX_TABLES, DbSchema.DEFAULT_MAX_COLUMNS);
                if (filter != null && !filter.trim().isEmpty()) {
                    text = filterSchema(text, filter.trim());
                }
                return ToolResult.ok("schema", text);
            } catch (Exception e) {
                return ToolResult.error("не удалось прочитать схему: " + e.getMessage()
                    + "; проверьте настройки подключения к базе");
            }
        }

        private String filterSchema(String text, String filter) {
            StringBuilder sb = new StringBuilder();
            String[] lines = text.split("\n");
            for (String line : lines) {
                if (line.toLowerCase().contains(filter.toLowerCase())) {
                    sb.append(line).append('\n');
                }
            }
            return sb.length() == 0 ? text : sb.toString();
        }
    }

    // ==================================================================
    // Общий путь добавления элемента тела
    // ==================================================================

    private static boolean requireMutation(ToolContext ctx) {
        return ctx.mutationAllowed();
    }

    /**
     * Общий обработчик add_*: строгий разбор фрагмента (с перекрёстными
     * ссылками по манифесту), транзакционное добавление в тело, регистрация
     * имени элемента.
     */
    private static ToolResult addBodyElement(String expectedType,
            Map<String, Object> args, ToolContext ctx) {
        if (!requireMutation(ctx)) {
            return ToolResult.error("превышен лимит операций сессии");
        }
        if (!ctx.session.isOpen()) {
            return ToolResult.error("дизайн не открыт");
        }
        Map<String, Object> withType = args;
        if (Json.asStr(withType.get("type")) == null) {
            withType = new LinkedHashMap<String, Object>(args);
            withType.put("type", expectedType);
        }
        String actualType = Json.asStr(withType.get("type"));
        if (!expectedType.equals(actualType)) {
            return ToolResult.error("этот инструмент добавляет «" + expectedType
                + "», а передан type «" + actualType + "»");
        }
        ReportSpec.BodyElement el;
        try {
            el = ReportSpec.parseBodyFragment(withType, ctx.session.manifestDataSets());
        } catch (ReportSpec.SpecException e) {
            return problems(e);
        }
        final String name = ctx.session.nextName(expectedType);
        final ReportSpec.BodyElement fEl = el;
        final ReportSpec manifest = ctx.session.manifest();
        try {
            ctx.session.exec("add_" + expectedType + " " + name,
                new com.aisql.birt.core.report.DesignSession.ElementOp() {
                    public void run(ReportDesignHandle design, ElementFactory factory)
                            throws Exception {
                        if (fEl instanceof ReportSpec.Label) {
                            design.getBody().add(ReportBuilder.createLabel(
                                factory, (ReportSpec.Label) fEl, name));
                        } else if (fEl instanceof ReportSpec.Table) {
                            design.getBody().add(ReportBuilder.createTable(
                                design, factory, manifest,
                                (ReportSpec.Table) fEl, name));
                        } else if (fEl instanceof ReportSpec.Grid) {
                            design.getBody().add(ReportBuilder.createGrid(
                                factory, (ReportSpec.Grid) fEl, name));
                        } else if (fEl instanceof ReportSpec.Image) {
                            design.getBody().add(ReportBuilder.createImage(
                                design, factory, (ReportSpec.Image) fEl, name));
                        } else if (fEl instanceof ReportSpec.ListElement) {
                            design.getBody().add(ReportBuilder.createList(
                                design, factory, manifest,
                                (ReportSpec.ListElement) fEl, name));
                        } else if (fEl instanceof ReportSpec.Chart) {
                            design.getBody().add(ReportBuilder.createChart(
                                design, factory, manifest,
                                (ReportSpec.Chart) fEl, name));
                        } else {
                            throw new IllegalStateException("неизвестный тип элемента");
                        }
                    }
                });
        } catch (com.aisql.birt.core.report.DesignSession.DesignException e) {
            return ToolResult.error(e.getMessage());
        }
        ctx.session.addManifestBody(el, name);
        ctx.mutations++;
        return ToolResult.ok("name", name);
    }

    // ==================================================================
    // Поиск по манифесту
    // ==================================================================

    private static ReportSpec.DataSource findDataSource(ToolContext ctx, String name) {
        for (ReportSpec.DataSource ds : ctx.session.manifestDataSources()) {
            if (ds.name().equals(name)) {
                return ds;
            }
        }
        return null;
    }

    private static ReportSpec.DataSet findDataSet(ToolContext ctx, String name) {
        for (ReportSpec.DataSet ds : ctx.session.manifestDataSets()) {
            if (ds.name().equals(name)) {
                return ds;
            }
        }
        return null;
    }

    private static ReportSpec.Parameter findParameter(ToolContext ctx, String name) {
        for (ReportSpec.Parameter p : ctx.session.manifestParameters()) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    private static String paramType(ReportSpec spec, String paramName) {
        for (ReportSpec.Parameter p : spec.parameters()) {
            if (p.name().equals(paramName)) {
                String type = PARAM_TYPES.get(p.type());
                return type == null ? DesignChoiceConstants.PARAM_TYPE_STRING : type;
            }
        }
        return DesignChoiceConstants.PARAM_TYPE_STRING;
    }
}
