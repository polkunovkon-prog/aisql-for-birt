/*
 * AI BIRT — сборка .rptdesign из проверенной спецификации (Design API BIRT).
 *
 * Отчёт собирается программно, штатным Design API — тем же способом, каким
 * это делает конструктор BIRT: элементы, свойства, стили, источник данных,
 * набор данных, параметры. Файл получается канонического формата BIRT 4.7
 * (никаких «самодельных» XML-шаблонов).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core.report;

import com.aisql.birt.core.spec.ReportSpec;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ibm.icu.util.ULocale;

import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.birt.report.model.api.CachedMetaDataHandle;
import org.eclipse.birt.report.model.api.CellHandle;
import org.eclipse.birt.report.model.api.ColumnHandle;
import org.eclipse.birt.report.model.api.DataItemHandle;
import org.eclipse.birt.report.model.api.DesignConfig;
import org.eclipse.birt.report.model.api.DesignElementHandle;
import org.eclipse.birt.report.model.api.DesignEngine;
import org.eclipse.birt.report.model.api.ElementFactory;
import org.eclipse.birt.report.model.api.LabelHandle;
import org.eclipse.birt.report.model.api.OdaDataSetHandle;
import org.eclipse.birt.report.model.api.OdaDataSourceHandle;
import org.eclipse.birt.report.model.api.PropertyHandle;
import org.eclipse.birt.report.model.api.ReportDesignHandle;
import org.eclipse.birt.report.model.api.RowHandle;
import org.eclipse.birt.report.model.api.ScalarParameterHandle;
import org.eclipse.birt.report.model.api.SessionHandle;
import org.eclipse.birt.report.model.api.SimpleMasterPageHandle;
import org.eclipse.birt.report.model.api.SlotHandle;
import org.eclipse.birt.report.model.api.StructureFactory;
import org.eclipse.birt.report.model.api.TableGroupHandle;
import org.eclipse.birt.report.model.api.TableHandle;
import org.eclipse.birt.report.model.api.elements.DesignChoiceConstants;
import org.eclipse.birt.report.model.api.elements.structures.CachedMetaData;
import org.eclipse.birt.report.model.api.elements.structures.ColumnHint;
import org.eclipse.birt.report.model.api.elements.structures.ComputedColumn;
import org.eclipse.birt.report.model.api.elements.structures.OdaDataSetParameter;
import org.eclipse.birt.report.model.api.elements.structures.ResultSetColumn;

/** Собирает файл .rptdesign из спецификации отчёта. */
public final class ReportBuilder {

    /** Ошибка сборки отчёта (сообщение — на русском, пригодно для панели чата). */
    public static class ReportBuildException extends Exception {
        private static final long serialVersionUID = 1L;

        public ReportBuildException(String message) {
            super(message);
        }

        public ReportBuildException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final String ODA_JDBC_DATA_SOURCE = "org.eclipse.birt.report.data.oda.jdbc";
    private static final String ODA_JDBC_DATA_SET = "org.eclipse.birt.report.data.oda.jdbc.JdbcSelectDataSet";

    /**
     * Шрифт всех текстовых элементов отчёта. Задаётся явно, чтобы PDF-эмиттер
     * BIRT встраивал в файл настоящий TrueType-шрифт (Identity-H). Если шрифт
     * не указан, движок подставляет базовые PDF-шрифты (Times/Helvetica),
     * которые не встраиваются и не содержат кириллицы — русский текст в PDF
     * пропадает (проверено на BIRT 4.7: остаются только цифры и рамки).
     * DejaVu Sans есть в типовых поставках Linux и виден JVM через fontconfig.
     */
    private static final String FONT_FAMILY = "DejaVu Sans";

    private static final Map<String, String> PAGE_SIZES = new HashMap<String, String>();
    private static final Map<String, String> PARAM_TYPES = new HashMap<String, String>();

    static {
        PAGE_SIZES.put("A4", DesignChoiceConstants.PAGE_SIZE_A4);
        PAGE_SIZES.put("A3", DesignChoiceConstants.PAGE_SIZE_A3);
        PAGE_SIZES.put("A5", DesignChoiceConstants.PAGE_SIZE_A5);
        PAGE_SIZES.put("Letter", DesignChoiceConstants.PAGE_SIZE_US_LETTER);
        PAGE_SIZES.put("Legal", DesignChoiceConstants.PAGE_SIZE_US_LEGAL);
        PAGE_SIZES.put("Ledger", DesignChoiceConstants.PAGE_SIZE_US_LEDGER);

        PARAM_TYPES.put("string", DesignChoiceConstants.PARAM_TYPE_STRING);
        PARAM_TYPES.put("integer", DesignChoiceConstants.PARAM_TYPE_INTEGER);
        PARAM_TYPES.put("decimal", DesignChoiceConstants.PARAM_TYPE_DECIMAL);
        PARAM_TYPES.put("date", DesignChoiceConstants.PARAM_TYPE_DATE);
        PARAM_TYPES.put("datetime", DesignChoiceConstants.PARAM_TYPE_DATETIME);
        PARAM_TYPES.put("boolean", DesignChoiceConstants.PARAM_TYPE_BOOLEAN);
    }

    private ReportBuilder() {
    }

    /**
     * Собирает отчёт по спецификации и сохраняет в {@code target}.
     *
     * @param spec             проверенная спецификация
     * @param target           будущий файл .rptdesign
     * @param passwordOverride пароль источника данных (если в спецификации нет);
     *                         пустая строка равнозначна «не задан»
     */
    public static void build(ReportSpec spec, File target, String passwordOverride)
            throws ReportBuildException {
        if (spec == null) {
            throw new ReportBuildException("спецификация пуста");
        }
        if (target == null) {
            throw new ReportBuildException("не задан файл для сохранения");
        }
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new ReportBuildException("не удалось создать каталог " + dir.getAbsolutePath());
        }

        SessionHandle session = null;
        try {
            session = new DesignEngine(new DesignConfig())
                .newSessionHandle(ULocale.ENGLISH);
            ReportDesignHandle design = session.createDesign();
            ElementFactory factory = design.getElementFactory();

            applyPage(design, factory, spec);
            applyDataSources(design, factory, spec, passwordOverride);
            applyParameters(design, factory, spec);
            applyDataSets(design, factory, spec);
            applyBody(design, factory, spec);

            design.setFileName(target.getAbsolutePath());
            design.save();
        } catch (ReportBuildException e) {
            throw e;
        } catch (Throwable t) {
            throw new ReportBuildException("не удалось собрать отчёт: " + describe(t), t);
        } finally {
            if (session != null) {
                try {
                    session.closeAll(false);
                } catch (Throwable ignored) {
                    // закрытие сессии не критично для результата
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Чтение готового файла
    // ------------------------------------------------------------------

    /**
     * Читает пароль источника данных из готового .rptdesign. BIRT хранит
     * пароль в скрытом виде; модель возвращает уже расшифрованное значение.
     * Нужен при правке отчёта, чтобы не спрашивать пароль заново.
     *
     * @return пароль или null, если прочитать не удалось
     */
    public static String readPassword(File designFile) {
        if (designFile == null || !designFile.isFile()) {
            return null;
        }
        SessionHandle session = null;
        try {
            session = new DesignEngine(new DesignConfig())
                .newSessionHandle(ULocale.ENGLISH);
            ReportDesignHandle handle = session.openDesign(designFile.getAbsolutePath());
            SlotHandle sources = handle.getDataSources();
            for (int i = 0; i < sources.getCount(); i++) {
                DesignElementHandle h = sources.get(i);
                if (h instanceof OdaDataSourceHandle) {
                    Object pw = h.getProperty("odaPassword");
                    if (pw instanceof String && !((String) pw).isEmpty()) {
                        return (String) pw;
                    }
                }
            }
        } catch (Throwable ignored) {
            // пароль недоступен — вызывающий код спросит его заново
        } finally {
            if (session != null) {
                try {
                    session.closeAll(false);
                } catch (Throwable ignored) {
                    // закрытие сессии не критично
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Страница
    // ------------------------------------------------------------------
    private static void applyPage(ReportDesignHandle design, ElementFactory factory,
            ReportSpec spec) throws Exception {
        if (spec.page() == null) {
            return;
        }
        SimpleMasterPageHandle page = factory.newSimpleMasterPage("masterPage");
        String size = PAGE_SIZES.get(spec.page().format());
        if (size != null) {
            page.setPageType(size);
        }
        if ("landscape".equals(spec.page().orientation())) {
            page.setOrientation(DesignChoiceConstants.PAGE_ORIENTATION_LANDSCAPE);
        } else {
            page.setOrientation(DesignChoiceConstants.PAGE_ORIENTATION_PORTRAIT);
        }
        if (spec.page().hasMargins()) {
            setMargin(page.getTopMargin(), spec.page().marginMm(0));
            setMargin(page.getRightMargin(), spec.page().marginMm(1));
            setMargin(page.getBottomMargin(), spec.page().marginMm(2));
            setMargin(page.getLeftMargin(), spec.page().marginMm(3));
        }
        design.getMasterPages().add(page);
    }

    private static void setMargin(org.eclipse.birt.report.model.api.DimensionHandle handle,
            Double mm) throws Exception {
        if (handle != null && mm != null) {
            handle.setStringValue(fmtMm(mm) + "mm");
        }
    }

    // ------------------------------------------------------------------
    // Источники данных
    // ------------------------------------------------------------------

    private static void applyDataSources(ReportDesignHandle design, ElementFactory factory,
            ReportSpec spec, String passwordOverride) throws Exception {
        for (ReportSpec.DataSource src : spec.datasources()) {
            OdaDataSourceHandle ds = factory.newOdaDataSource(src.name(), ODA_JDBC_DATA_SOURCE);
            ds.setProperty("odaDriverClass", src.driver());
            ds.setProperty("odaURL", src.url());
            ds.setProperty("odaUser", src.user());
            String password = src.hasPassword() ? src.password() : passwordOverride;
            if (password != null && !password.isEmpty()) {
                ds.setProperty("odaPassword", password);
            }
            design.getDataSources().add(ds);
        }
    }

    // ------------------------------------------------------------------
    // Параметры
    // ------------------------------------------------------------------

    private static void applyParameters(ReportDesignHandle design, ElementFactory factory,
            ReportSpec spec) throws Exception {
        for (ReportSpec.Parameter p : spec.parameters()) {
            ScalarParameterHandle param = factory.newScalarParameter(p.name());
            String type = PARAM_TYPES.get(p.type());
            param.setDataType(type == null ? DesignChoiceConstants.PARAM_TYPE_STRING : type);
            if (p.prompt() != null && !p.prompt().isEmpty()) {
                try {
                    param.setPromptText(p.prompt());
                } catch (Throwable ignored) {
                    // подпись не критична
                }
            }
            if (p.def() != null) {
                param.setDefaultValue(p.def());
            }
            design.getParameters().add(param);
        }
    }

    // ------------------------------------------------------------------
    // Наборы данных
    // ------------------------------------------------------------------

    private static void applyDataSets(ReportDesignHandle design, ElementFactory factory,
            ReportSpec spec) throws Exception {
        for (ReportSpec.DataSet dataSet : spec.datasets()) {
            OdaDataSetHandle q = factory.newOdaDataSet(dataSet.name(), ODA_JDBC_DATA_SET);
            design.getDataSets().add(q);
            q.setDataSource(dataSet.datasource());
            q.setQueryText(dataSet.sql());

            // Параметры набора: по одному на каждый «?» в SQL, по порядку.
            int position = 0;
            for (String paramName : dataSet.params()) {
                position++;
                String paramType = paramType(spec, paramName);
                OdaDataSetParameter dsp = StructureFactory.createOdaDataSetParameter();
                dsp.setName("param_" + position);
                dsp.setParamName(paramName);
                dsp.setNativeName("");
                dsp.setPosition(Integer.valueOf(position));
                dsp.setDataType(paramType);
                dsp.setIsInput(true);
                dsp.setIsOutput(false);
                q.getPropertyHandle("parameters").addItem(dsp);
            }

            // Кэшированные метаданные и подсказки по колонкам.
            if (!dataSet.columns().isEmpty()) {
                CachedMetaData cached = StructureFactory.createCachedMetaData();
                q.setCachedMetaData(cached);
                CachedMetaDataHandle cm = q.getCachedMetaDataHandle();
                int i = 0;
                for (ReportSpec.DatasetColumn col : dataSet.columns()) {
                    i++;
                    ResultSetColumn rsc = StructureFactory.createResultSetColumn();
                    rsc.setPosition(Integer.valueOf(i));
                    rsc.setColumnName(col.name());
                    rsc.setDataType(col.type());
                    cm.getResultSet().addItem(rsc);

                    ColumnHint hint = StructureFactory.createColumnHint();
                    hint.setProperty("columnName", col.name());
                    hint.setProperty("analysis", columnAnalysis(col.type()));
                    hint.setProperty("displayName", col.name());
                    hint.setProperty("heading", col.name());
                    q.getPropertyHandle("columnHints").addItem(hint);
                }
            }
        }
    }

    private static String columnAnalysis(String type) {
        return "integer".equals(type) || "decimal".equals(type) ? "measure" : "dimension";
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

    // ------------------------------------------------------------------
    // Содержимое
    // ------------------------------------------------------------------

    private static void applyBody(ReportDesignHandle design, ElementFactory factory,
            ReportSpec spec) throws Exception {
        int labelIndex = 0;
        int tableIndex = 0;
        for (ReportSpec.BodyElement el : spec.body()) {
            if (el instanceof ReportSpec.Label) {
                labelIndex++;
                design.getBody().add(createLabel(factory, (ReportSpec.Label) el,
                    "label_" + labelIndex));
            } else if (el instanceof ReportSpec.Table) {
                tableIndex++;
                design.getBody().add(createTable(design, factory, spec,
                    (ReportSpec.Table) el, "table_" + tableIndex));
            }
        }
    }

    private static LabelHandle createLabel(ElementFactory factory, ReportSpec.Label el, String name)
            throws Exception {
        LabelHandle label = factory.newLabel(name);
        label.setText(el.text());
        label.setProperty("fontFamily", FONT_FAMILY);
        if (el.sizePt() != null) {
            label.setProperty("fontSize", fmtMm(el.sizePt()) + "pt");
        }
        if (el.bold()) {
            label.setProperty("fontWeight", DesignChoiceConstants.FONT_WEIGHT_BOLD);
        }
        if (el.italic()) {
            label.setProperty("fontStyle", DesignChoiceConstants.FONT_STYLE_ITALIC);
        }
        if (el.color() != null) {
            label.setProperty("color", el.color());
        }
        if (el.align() != null) {
            label.setProperty("textAlign", el.align());
        }
        label.setProperty("marginTop", "2mm");
        label.setProperty("marginBottom", "2mm");
        return label;
    }

    private static TableHandle createTable(ReportDesignHandle design, ElementFactory factory,
            ReportSpec spec, ReportSpec.Table el, String name) throws Exception {
        List<ReportSpec.TableColumn> cols = el.columns();
        TableHandle table = factory.newTableItem(name, cols.size());
        for (ReportSpec.DataSet ds : spec.datasets()) {
            if (ds.name().equals(el.dataset())) {
                table.setDataSet(dataSetHandle(design, ds.name()));
                break;
            }
        }

        // Убираем пустую строку-подвал: остаются шапка и строка данных.
        if (table.getFooter().getCount() > 0) {
            table.getFooter().get(0).drop();
        }

        // Привязки столбцов таблицы (boundDataColumns): связывают столбец
        // набора данных с выражением строки. Без них ячейки-данные выходят
        // пустыми, хотя запрос выполняется (проверено на BIRT 4.7).
        // Для вычисляемых колонок привязка создаётся по каждой ссылке row["..."].
        PropertyHandle bound = table.getPropertyHandle("boundDataColumns");
        Set<String> boundNames = new LinkedHashSet<String>();
        for (ReportSpec.TableColumn col : cols) {
            boundNames.addAll(col.refs());
        }
        if (el.groupBy() != null) {
            boundNames.add(el.groupBy());
        }
        if (bound != null) {
            for (String ref : boundNames) {
                ComputedColumn cc = new ComputedColumn();
                cc.setName(ref);
                cc.setDisplayName(ref);
                cc.setExpression("dataSetRow[\"" + ref + "\"]");
                String type = resultColumnType(spec, el, ref);
                if (type != null) {
                    cc.setDataType(type);
                }
                bound.addItem(cc);
            }
        }

        RowHandle header = (RowHandle) table.getHeader().get(0);
        RowHandle detail = (RowHandle) table.getDetail().get(0);
        for (int i = 0; i < cols.size(); i++) {
            ReportSpec.TableColumn col = cols.get(i);

            LabelHandle head = factory.newLabel(name + "_h" + (i + 1));
            head.setText(col.header());
            head.setProperty("fontFamily", FONT_FAMILY);
            head.setProperty("fontWeight", DesignChoiceConstants.FONT_WEIGHT_BOLD);
            head.setProperty("textAlign", col.align() == null ? "left" : col.align());
            CellHandle hc = (CellHandle) header.getCells().get(i);
            if (col.headerBackground() != null) {
                hc.setProperty("backgroundColor", col.headerBackground());
            }
            hc.getContent().add(head);

            DataItemHandle item = factory.newDataItem(name + "_d" + (i + 1));
            item.setProperty("fontFamily", FONT_FAMILY);
            if (col.resultColumn() != null) {
                item.setResultSetColumn(col.resultColumn());
            } else {
                item.setValueExpr(col.expr());
            }
            if (col.align() != null) {
                item.setProperty("textAlign", col.align());
            }
            if (col.bold()) {
                item.setProperty("fontWeight", DesignChoiceConstants.FONT_WEIGHT_BOLD);
            }
            if (col.italic()) {
                item.setProperty("fontStyle", DesignChoiceConstants.FONT_STYLE_ITALIC);
            }
            if (col.color() != null) {
                item.setProperty("color", col.color());
            }
            CellHandle dc = (CellHandle) detail.getCells().get(i);
            if (col.background() != null) {
                dc.setProperty("backgroundColor", col.background());
            }
            dc.getContent().add(item);

            if (col.widthMm() != null) {
                ((ColumnHandle) table.getColumns().get(i)).getWidth()
                    .setStringValue(fmtMm(col.widthMm()) + "mm");
            }
        }

        if (el.groupBy() != null) {
            addGroup(factory, table, el, name, cols.size());
        }
        return table;
    }

    /** Группировка строк: заголовок группы со значением ключа и (опционально) счётчик. */
    private static void addGroup(ElementFactory factory, TableHandle table,
            ReportSpec.Table el, String name, int columnCount) throws Exception {
        TableGroupHandle group = factory.newTableGroup();
        group.setKeyExpr("row[\"" + el.groupBy() + "\"]");
        table.getGroups().add(group);

        RowHandle header = factory.newTableRow(1);
        CellHandle cell = (CellHandle) header.getCells().get(0);
        cell.setColumnSpan(columnCount);
        cell.setProperty("backgroundColor", "#eef2f7");

        LabelHandle caption = factory.newLabel(name + "_gt");
        caption.setText(el.groupTitle() + ": ");
        caption.setProperty("fontFamily", FONT_FAMILY);
        caption.setProperty("fontWeight", DesignChoiceConstants.FONT_WEIGHT_BOLD);
        cell.getContent().add(caption);

        DataItemHandle key = factory.newDataItem(name + "_gk");
        key.setProperty("fontFamily", FONT_FAMILY);
        key.setProperty("fontWeight", DesignChoiceConstants.FONT_WEIGHT_BOLD);
        key.setResultSetColumn(el.groupBy());
        cell.getContent().add(key);
        group.getHeader().add(header);

        if (el.groupCount()) {
            RowHandle footer = factory.newTableRow(1);
            CellHandle fcell = (CellHandle) footer.getCells().get(0);
            fcell.setColumnSpan(columnCount);
            LabelHandle total = factory.newLabel(name + "_gc");
            total.setText("Всего строк в группе: ");
            total.setProperty("fontFamily", FONT_FAMILY);
            fcell.getContent().add(total);
            DataItemHandle count = factory.newDataItem(name + "_gcnt");
            count.setProperty("fontFamily", FONT_FAMILY);
            count.setValueExpr("Total.count()");
            fcell.getContent().add(count);
            group.getFooter().add(footer);
        }
    }

    /** Тип столбца набора данных по его имени (для привязки столбцов таблицы). */
    private static String resultColumnType(ReportSpec spec, ReportSpec.Table el, String column) {
        for (ReportSpec.DataSet ds : spec.datasets()) {
            if (ds.name().equals(el.dataset())) {
                for (ReportSpec.DatasetColumn c : ds.columns()) {
                    if (c.name().equals(column)) {
                        return c.type();
                    }
                }
            }
        }
        return null;
    }

    private static OdaDataSetHandle dataSetHandle(ReportDesignHandle design, String name) {
        SlotHandle sets = design.getDataSets();
        for (int i = 0; i < sets.getCount(); i++) {
            DesignElementHandle h = sets.get(i);
            if (h instanceof OdaDataSetHandle && name.equals(h.getName())) {
                return (OdaDataSetHandle) h;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Мелочи
    // ------------------------------------------------------------------

    private static String fmtMm(Double d) {
        if (d.doubleValue() == Math.rint(d.doubleValue())) {
            return String.valueOf((long) d.doubleValue());
        }
        return String.valueOf(d);
    }

    /**
     * Собирает понятное описание ошибки. Модель BIRT в отдельных случаях
     * не может построить локализованное сообщение вне Eclipse, поэтому
     * текст защищён от каскадных исключений.
     */
    private static String describe(Throwable t) {
        String msg;
        try {
            msg = t.getMessage();
        } catch (Throwable e) {
            msg = null;
        }
        if (msg == null || msg.isEmpty()) {
            try {
                msg = t.toString();
            } catch (Throwable e) {
                msg = t.getClass().getName();
            }
        }
        StringBuilder sb = new StringBuilder(msg);
        Throwable cause = t.getCause();
        int depth = 0;
        while (cause != null && depth < 3) {
            String cm;
            try {
                cm = cause.getMessage();
            } catch (Throwable e) {
                cm = null;
            }
            if (cm != null && !cm.isEmpty() && sb.indexOf(cm) < 0) {
                sb.append(" (").append(cm).append(')');
            }
            cause = cause.getCause();
            depth++;
        }
        return sb.toString();
    }
}
