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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ibm.icu.util.ULocale;

import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.birt.report.model.api.AutoTextHandle;
import org.eclipse.birt.report.model.api.CachedMetaDataHandle;
import org.eclipse.birt.report.model.api.CellHandle;
import org.eclipse.birt.report.model.api.ColumnHandle;
import org.eclipse.birt.report.model.api.DataItemHandle;
import org.eclipse.birt.report.model.api.DesignConfig;
import org.eclipse.birt.report.model.api.DesignElementHandle;
import org.eclipse.birt.report.model.api.DesignEngine;
import org.eclipse.birt.report.model.api.ElementFactory;
import org.eclipse.birt.report.model.api.Expression;
import org.eclipse.birt.report.model.api.ExpressionType;
import org.eclipse.birt.report.model.api.GridHandle;
import org.eclipse.birt.report.model.api.ImageHandle;
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
import org.eclipse.birt.report.model.api.TextItemHandle;
import org.eclipse.birt.report.model.api.elements.DesignChoiceConstants;
import org.eclipse.birt.report.model.api.elements.structures.AggregationArgument;
import org.eclipse.birt.report.model.api.elements.structures.CachedMetaData;
import org.eclipse.birt.report.model.api.elements.structures.ColumnHint;
import org.eclipse.birt.report.model.api.elements.structures.ComputedColumn;
import org.eclipse.birt.report.model.api.elements.structures.EmbeddedImage;
import org.eclipse.birt.report.model.api.elements.structures.FilterCondition;
import org.eclipse.birt.report.model.api.elements.structures.HighlightRule;
import org.eclipse.birt.report.model.api.elements.structures.OdaDataSetParameter;
import org.eclipse.birt.report.model.api.elements.structures.ResultSetColumn;
import org.eclipse.birt.report.model.api.elements.structures.SortKey;

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
     * <p>Ограничение BIRT 4.7: пароль с символами вне ASCII возвращается
     * с потерей таких символов (каждый становится «?»).</p>
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
        if (spec.page().hasHeader()) {
            addPageBand(factory, page.getPageHeader(), spec.page().header(),
                spec.title(), "pageHeader");
        }
        if (spec.page().hasFooter()) {
            addPageBand(factory, page.getPageFooter(), spec.page().footer(),
                spec.title(), "pageFooter");
        }
        if (!spec.page().showHeaderOnFirst()) {
            page.setProperty("showHeaderOnFirst", Boolean.FALSE);
        }
        if (!spec.page().showFooterOnLast()) {
            page.setProperty("showFooterOnLast", Boolean.FALSE);
        }
    }

    /**
     * Колонтитул страницы. Слот BIRT держит ровно один элемент, поэтому
     * шаблон с подстановками {page}/{pages}/{title}/{date} собирается в один
     * ряд сетки (grid) из надписей и auto-text — так делает и конструктор BIRT.
     * {date} рисуется текстовым элементом с HTML-выражением new Date().
     */
    private static void addPageBand(ElementFactory factory, SlotHandle slot,
            String template, String reportTitle, String name) throws Exception {
        List<String> parts = new ArrayList<String>();
        List<String> kinds = new ArrayList<String>();
        StringBuilder text = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            if (template.startsWith("{page}", i)) {
                addPart(parts, kinds, text, "page");
                i += 6;
            } else if (template.startsWith("{pages}", i)) {
                addPart(parts, kinds, text, "pages");
                i += 7;
            } else if (template.startsWith("{title}", i)) {
                addPart(parts, kinds, text, "title");
                i += 7;
            } else if (template.startsWith("{date}", i)) {
                addPart(parts, kinds, text, "date");
                i += 6;
            } else {
                text.append(template.charAt(i));
                i++;
            }
        }
        addPart(parts, kinds, text, null);

        if (parts.isEmpty()) {
            return;
        }
        // Одна надпись без подстановок — сразу в слот, без обёртки-сетки.
        if (parts.size() == 1 && "text".equals(kinds.get(0))) {
            slot.add(createPageLabel(factory, name + "_l", parts.get(0)));
            return;
        }
        GridHandle grid = factory.newGridItem(name + "Grid", parts.size(), 1);
        RowHandle row = (RowHandle) grid.getRows().get(0);
        for (int k = 0; k < parts.size(); k++) {
            String kind = kinds.get(k);
            String value = parts.get(k);
            CellHandle cell = (CellHandle) row.getCells().get(k);
            if (value == null || value.isEmpty()) {
                continue;
            }
            if ("page".equals(kind)) {
                AutoTextHandle at = factory.newAutoText(name + "_pg");
                at.setAutoTextType(DesignChoiceConstants.AUTO_TEXT_PAGE_NUMBER);
                at.setProperty("fontFamily", FONT_FAMILY);
                cell.getContent().add(at);
            } else if ("pages".equals(kind)) {
                AutoTextHandle at = factory.newAutoText(name + "_pgs");
                at.setAutoTextType(DesignChoiceConstants.AUTO_TEXT_TOTAL_PAGE);
                at.setProperty("fontFamily", FONT_FAMILY);
                cell.getContent().add(at);
            } else if ("date".equals(kind)) {
                TextItemHandle item = factory.newTextItem(name + "_dt");
                item.setContentType(DesignChoiceConstants.TEXT_CONTENT_TYPE_HTML);
                item.setContent("<value-of>new Date()</value-of>");
                item.setProperty("fontFamily", FONT_FAMILY);
                cell.getContent().add(item);
            } else {
                String labelText = "title".equals(kind) ? reportTitle : value;
                cell.getContent().add(createPageLabel(factory, name + "_l" + k, labelText));
            }
        }
        slot.add(grid);
    }

    private static void addPart(List<String> parts, List<String> kinds,
            StringBuilder text, String kind) {
        if (text.length() > 0) {
            parts.add(text.toString());
            kinds.add("text");
            text.setLength(0);
        }
        if (kind != null) {
            parts.add(kind);
            kinds.add(kind);
        }
    }

    private static LabelHandle createPageLabel(ElementFactory factory, String name, String text)
            throws Exception {
        LabelHandle label = factory.newLabel(name);
        label.setText(text);
        label.setProperty("fontFamily", FONT_FAMILY);
        return label;
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
        int gridIndex = 0;
        int imageIndex = 0;
        for (ReportSpec.BodyElement el : spec.body()) {
            if (el instanceof ReportSpec.Label) {
                labelIndex++;
                design.getBody().add(createLabel(factory, (ReportSpec.Label) el,
                    "label_" + labelIndex));
            } else if (el instanceof ReportSpec.Table) {
                tableIndex++;
                design.getBody().add(createTable(design, factory, spec,
                    (ReportSpec.Table) el, "table_" + tableIndex));
            } else if (el instanceof ReportSpec.Grid) {
                gridIndex++;
                design.getBody().add(createGrid(factory, (ReportSpec.Grid) el,
                    "grid_" + gridIndex));
            } else if (el instanceof ReportSpec.Image) {
                imageIndex++;
                design.getBody().add(createImage(design, factory, (ReportSpec.Image) el,
                    "image_" + imageIndex));
            }
        }
    }

    /** Сетка grid: строки ячеек со спанами. */
    private static GridHandle createGrid(ElementFactory factory, ReportSpec.Grid el, String name)
            throws Exception {
        List<Double> widths = el.columnWidthsMm();
        int cols = widths.size();
        GridHandle grid = factory.newGridItem(name, cols, el.rows().size());
        for (int c = 0; c < cols; c++) {
            ColumnHandle column = (ColumnHandle) grid.getColumns().get(c);
            column.getWidth().setAbsolute(widths.get(c).doubleValue());
        }
        for (int r = 0; r < el.rows().size(); r++) {
            RowHandle row = (RowHandle) grid.getRows().get(r);
            List<ReportSpec.Cell> cells = el.rows().get(r);
            // A spanning cell covers several grid columns; newGridItem created a
            // full set of cells, so drop the covered placeholders from the end.
            for (int c = row.getCells().getCount() - 1; c >= cells.size(); c--) {
                row.getCells().drop(c);
            }
            for (int c = 0; c < cells.size(); c++) {
                ReportSpec.Cell cell = cells.get(c);
                CellHandle hc = (CellHandle) row.getCells().get(c);
                if (cell.colSpan() != null && cell.colSpan() > 1) {
                    hc.setColumnSpan(cell.colSpan());
                }
                if (cell.rowSpan() != null && cell.rowSpan() > 1) {
                    hc.setRowSpan(cell.rowSpan());
                }
                if (cell.background() != null) {
                    hc.setProperty("backgroundColor", cell.background());
                }
                addCellContent(factory, hc, cell, name + "_r" + (r + 1) + "_c" + (c + 1));
            }
        }
        return grid;
    }

    /** Кладёт содержимое в ячейку grid (надпись/поле/HTML-текст). */
    private static void addCellContent(ElementFactory factory, CellHandle hc,
            ReportSpec.Cell cell, String name) throws Exception {
        if ("data".equals(cell.type())) {
            DataItemHandle item = factory.newDataItem(name + "_data");
            item.setProperty("fontFamily", FONT_FAMILY);
            if (cell.align() != null) {
                item.setProperty("textAlign", cell.align());
            }
            item.setResultSetColumn(cell.expr());
            hc.getContent().add(item);
        } else if ("text".equals(cell.type())) {
            TextItemHandle item = factory.newTextItem(name + "_text");
            item.setContentType(DesignChoiceConstants.TEXT_CONTENT_TYPE_HTML);
            item.setContent("<value-of>" + cell.expr() + "</value-of>");
            item.setProperty("fontFamily", FONT_FAMILY);
            hc.getContent().add(item);
        } else {
            LabelHandle label = factory.newLabel(name + "_lbl");
            label.setText(cell.text() == null ? "" : cell.text());
            label.setProperty("fontFamily", FONT_FAMILY);
            if (cell.fontPt() != null) {
                label.setProperty("fontSize", fmtMm(cell.fontPt()) + "pt");
            }
            if (cell.bold()) {
                label.setProperty("fontWeight", DesignChoiceConstants.FONT_WEIGHT_BOLD);
            }
            if (cell.italic()) {
                label.setProperty("fontStyle", DesignChoiceConstants.FONT_STYLE_ITALIC);
            }
            if (cell.color() != null) {
                label.setProperty("color", cell.color());
            }
            if (cell.align() != null) {
                label.setProperty("textAlign", cell.align());
            }
            hc.getContent().add(label);
        }
    }

    /** Изображение: встроенный PNG (base64) или импорт из файла. */
    private static ImageHandle createImage(ReportDesignHandle design,
            ElementFactory factory, ReportSpec.Image el, String name)
            throws Exception {
        ImageHandle handle = factory.newImage(name);
        byte[] png;
        if (el.base64Png() != null) {
            png = java.util.Base64.getDecoder().decode(el.base64Png());
        } else {
            File f = new File(el.path());
            if (!f.isFile()) {
                throw new ReportBuildException(
                    "изображение: файл не найден — " + f.getAbsolutePath());
            }
            png = java.nio.file.Files.readAllBytes(f.toPath());
        }
        // Embedded images are design-level structures; the image item refers to
        // one by name. handle.getEmbeddedImage() is null until that link exists.
        EmbeddedImage image = StructureFactory.createEmbeddedImage();
        image.setName(name + ".png");
        image.setType(DesignChoiceConstants.IMAGE_TYPE_IMAGE_PNG);
        image.setData(png);
        design.addImage(image);
        handle.setSource(DesignChoiceConstants.IMAGE_REF_TYPE_EMBED);
        handle.setImageName(name + ".png");
        if (el.widthMm() != null) {
            handle.setWidth(String.valueOf(el.widthMm()) + "mm");
        }
        if (el.heightMm() != null) {
            handle.setHeight(String.valueOf(el.heightMm()) + "mm");
        }
        return handle;
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
            for (ReportSpec.Highlight h : col.highlights()) {
                boundNames.addAll(h.refs());
            }
        }
        if (el.filter() != null) {
            boundNames.addAll(ReportSpec.exprRefs(el.filter()));
        }
        for (ReportSpec.GroupLevel g : el.groups()) {
            boundNames.add(g.column());
        }
        for (ReportSpec.SortKey s : el.sort()) {
            boundNames.addAll(ReportSpec.exprRefs(s.expr()));
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

        // Вычисляемые колонки: выражение храним в отдельной вычисляемой
        // колонке таблицы, а элемент данных привязываем к её имени — так
        // делает дизайнер BIRT. Запись выражения прямо в resultSetColumn
        // опасна: при сохранении модель «разрешает» такие элементы по
        // совпадению выражений с другими колонками и может перенаправить
        // ячейку (проверено: агрегат с тем же выражением перехватывал
        // привязку колонки данных).
        java.util.Map<Integer, String> computedBindings = new HashMap<Integer, String>();
        if (bound != null) {
            for (int i = 0; i < cols.size(); i++) {
                ReportSpec.TableColumn col = cols.get(i);
                if (col.resultColumn() != null) {
                    continue;
                }
                String binding = name + "_c" + (i + 1);
                int suffix = 1;
                while (table.findColumnBinding(binding) != null) {
                    suffix++;
                    binding = name + "_c" + (i + 1) + "_" + suffix;
                }
                ComputedColumn cc = new ComputedColumn();
                cc.setName(binding);
                cc.setDisplayName(binding);
                cc.setExpression(col.expr());
                if (col.type() != null) {
                    cc.setDataType(col.type());
                }
                bound.addItem(cc);
                computedBindings.put(Integer.valueOf(i), binding);
            }
        }

        // Условие отбора строк (filter таблицы): выражение-предикат из
        // спецификации проверяется оператором «истина». Ссылки row["..."]
        // разрешаются по привязкам таблицы (boundDataColumns выше).
        if (el.filter() != null) {
            FilterCondition fc = new FilterCondition();
            fc.setExpr(el.filter());
            fc.setOperator(DesignChoiceConstants.FILTER_OPERATOR_TRUE);
            table.getPropertyHandle("filter").addItem(fc);
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
                String binding = computedBindings.get(Integer.valueOf(i));
                if (binding != null) {
                    item.setResultSetColumn(binding);
                } else {
                    item.setValueExpr(col.expr());
                }
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

            // Условное форматирование строк: правило на элементе данных
            // (цвет/начертание текста) и копия на ячейке, чтобы фон
            // окрашивался по всей ячейке, как в отчётах конструктора BIRT.
            for (ReportSpec.Highlight h : col.highlights()) {
                PropertyHandle itemRules = item.getPropertyHandle("highlightRules");
                if (itemRules != null) {
                    itemRules.addItem(createHighlightRule(h));
                }
                if (h.background() != null) {
                    PropertyHandle cellRules = dc.getPropertyHandle("highlightRules");
                    if (cellRules != null) {
                        cellRules.addItem(createHighlightRule(h));
                    }
                }
            }

            if (col.widthMm() != null) {
                ((ColumnHandle) table.getColumns().get(i)).getWidth()
                    .setStringValue(fmtMm(col.widthMm()) + "mm");
            }
        }

        for (int g = 0; g < el.groups().size(); g++) {
            addGroup(factory, table, el, el.groups().get(g), name + "_g" + (g + 1),
                cols.size());
        }
        if (!el.sort().isEmpty()) {
            PropertyHandle sortHandle = table.getPropertyHandle("sort");
            if (sortHandle != null) {
                for (ReportSpec.SortKey sk : el.sort()) {
                    SortKey key = new SortKey();
                    key.setKey(sk.expr());
                    key.setDirection(sk.descending()
                        ? DesignChoiceConstants.SORT_DIRECTION_DESC
                        : DesignChoiceConstants.SORT_DIRECTION_ASC);
                    sortHandle.addItem(key);
                }
            }
        }

        // Итоговая строка (подвал таблицы). Агрегат живёт в вычисляемой
        // колонке таблицы (aggregateFunction + аргумент Expression), а
        // элемент данных подвала привязывается к этой колонке — так же,
        // как это делает дизайнер BIRT («Result Set Column» = имя колонки).
        boolean hasTotal = false;
        for (ReportSpec.TableColumn col : cols) {
            if (col.total() != null) {
                hasTotal = true;
                break;
            }
        }
        if (hasTotal) {
            String labelText = el.totalLabel() == null ? "Итого" : el.totalLabel();
            int labelIndex = -1;
            if (!labelText.isEmpty()) {
                for (int i = 0; i < cols.size(); i++) {
                    if (cols.get(i).total() == null) {
                        labelIndex = i;
                        break;
                    }
                }
            }
            RowHandle footer = factory.newTableRow(cols.size());
            for (int i = 0; i < cols.size(); i++) {
                ReportSpec.TableColumn col = cols.get(i);
                CellHandle fc = (CellHandle) footer.getCells().get(i);
                if (col.total() != null) {
                    String binding = name + "_t" + (i + 1);
                    ComputedColumn cc = new ComputedColumn();
                    cc.setName(binding);
                    cc.setDisplayName(binding);
                    cc.setAggregateFunction(aggregateFunction(col.total()));
                    String type = aggregateType(col.total(), spec, el, col);
                    if (type != null) {
                        cc.setDataType(type);
                    }
                    // Аргумент агрегата — ссылка на привязку самой колонки
                    // (плоская колонка или вычисляемая): row["имя_привязки"].
                    String ownBinding = col.resultColumn();
                    if (ownBinding == null) {
                        ownBinding = computedBindings.get(Integer.valueOf(i));
                    }
                    String argExpr = ownBinding == null
                        ? col.expr() : "row[\"" + ownBinding + "\"]";
                    AggregationArgument arg = new AggregationArgument();
                    arg.setName("Expression");
                    arg.setExpressionProperty(AggregationArgument.VALUE_MEMBER,
                        new Expression(argExpr, ExpressionType.JAVASCRIPT));
                    cc.addArgument(arg);
                    if (bound != null) {
                        bound.addItem(cc);
                    }
                    DataItemHandle item = factory.newDataItem(name + "_tf" + (i + 1));
                    item.setProperty("fontFamily", FONT_FAMILY);
                    item.setProperty("fontWeight", DesignChoiceConstants.FONT_WEIGHT_BOLD);
                    if (col.align() != null) {
                        item.setProperty("textAlign", col.align());
                    }
                    item.setResultSetColumn(binding);
                    fc.getContent().add(item);
                } else if (i == labelIndex) {
                    LabelHandle lab = factory.newLabel(name + "_tl");
                    lab.setText(labelText);
                    lab.setProperty("fontFamily", FONT_FAMILY);
                    lab.setProperty("fontWeight", DesignChoiceConstants.FONT_WEIGHT_BOLD);
                    lab.setProperty("textAlign", col.align() == null ? "left" : col.align());
                    fc.getContent().add(lab);
                }
            }
            table.getFooter().add(footer);
        }
        return table;
    }

    /** Правило условного оформления (highlight rule) из спецификации. */
    private static HighlightRule createHighlightRule(ReportSpec.Highlight h)
            throws Exception {
        HighlightRule rule = new HighlightRule();
        rule.setTestExpression(h.when());
        // Правило с истинным условием: operator=is-true + выражение testExpr
        // (так же хранит правила конструктор BIRT: без operator правило
        // движком не применяется).
        rule.setOperator(DesignChoiceConstants.FILTER_OPERATOR_TRUE);
        if (h.background() != null) {
            rule.setProperty(HighlightRule.BACKGROUND_COLOR_MEMBER, h.background());
        }
        if (h.color() != null) {
            rule.setProperty(HighlightRule.COLOR_MEMBER, h.color());
        }
        if (h.bold()) {
            rule.setProperty(HighlightRule.FONT_WEIGHT_MEMBER,
                DesignChoiceConstants.FONT_WEIGHT_BOLD);
        }
        if (h.italic()) {
            rule.setProperty(HighlightRule.FONT_STYLE_MEMBER,
                DesignChoiceConstants.FONT_STYLE_ITALIC);
        }
        return rule;
    }

    /** Функция агрегата BIRT по имени итога из спецификации. */
    private static String aggregateFunction(String total) {
        if ("count".equals(total)) {
            return DesignChoiceConstants.AGGREGATION_FUNCTION_COUNT;
        }
        if ("avg".equals(total)) {
            return DesignChoiceConstants.AGGREGATION_FUNCTION_AVERAGE;
        }
        if ("min".equals(total)) {
            return DesignChoiceConstants.AGGREGATION_FUNCTION_MIN;
        }
        if ("max".equals(total)) {
            return DesignChoiceConstants.AGGREGATION_FUNCTION_MAX;
        }
        return DesignChoiceConstants.AGGREGATION_FUNCTION_SUM;
    }

    /** Тип вычисляемой колонки для итога (для format-правил движка). */
    private static String aggregateType(String total, ReportSpec spec,
            ReportSpec.Table el, ReportSpec.TableColumn col) {
        if ("count".equals(total)) {
            return "integer";
        }
        if ("sum".equals(total) || "avg".equals(total)) {
            return "decimal";
        }
        if (col.type() != null) {
            return col.type();
        }
        String ref = col.resultColumn();
        if (ref == null && col.refs().size() == 1) {
            ref = col.refs().get(0);
        }
        String type = ref == null ? null : resultColumnType(spec, el, ref);
        return type == null ? "string" : type;
    }

    /** Группировка строк: заголовок группы со значением ключа и (опционально) счётчик. */
    private static void addGroup(ElementFactory factory, TableHandle table,
            ReportSpec.Table el, ReportSpec.GroupLevel level, String name,
            int columnCount) throws Exception {
        TableGroupHandle group = factory.newTableGroup();
        group.setName(name + "_grp");
        group.setKeyExpr("row[\"" + level.column() + "\"]");
        table.getGroups().add(group);

        RowHandle header = factory.newTableRow(1);
        CellHandle cell = (CellHandle) header.getCells().get(0);
        cell.setColumnSpan(columnCount);
        cell.setProperty("backgroundColor", "#eef2f7");

        LabelHandle caption = factory.newLabel(name + "_gt");
        caption.setText(level.title() + ": ");
        caption.setProperty("fontFamily", FONT_FAMILY);
        caption.setProperty("fontWeight", DesignChoiceConstants.FONT_WEIGHT_BOLD);
        cell.getContent().add(caption);

        DataItemHandle key = factory.newDataItem(name + "_gk");
        key.setProperty("fontFamily", FONT_FAMILY);
        key.setProperty("fontWeight", DesignChoiceConstants.FONT_WEIGHT_BOLD);
        key.setResultSetColumn(level.column());
        cell.getContent().add(key);
        group.getHeader().add(header);

        if (level.count()) {
            // Счётчик строк группы. В BIRT у элемента данных resultSetColumn —
            // это имя привязки, а не выражение: «Total.count()» напрямую движок
            // отвергает («Invalid bound column name»). Поэтому создаём у таблицы
            // вычисляемую колонку с выражением Total.count() и привязываем
            // элемент данных к ней.
            String countName = name + "_gcnt";
            int suffix = 1;
            while (table.findColumnBinding(countName) != null) {
                suffix++;
                countName = name + "_gcnt" + suffix;
            }
            ComputedColumn countColumn = new ComputedColumn();
            countColumn.setName(countName);
            countColumn.setDisplayName(countName);
            countColumn.setExpression("Total.count()");
            // Агрегат считается по строкам группы, а не по всей таблице:
            // без aggregateOn Total.count() возвращает число всех строк.
            countColumn.setAggregateOn(group.getName());
            countColumn.setDataType(DesignChoiceConstants.COLUMN_DATA_TYPE_INTEGER);
            PropertyHandle countBindings = table.getPropertyHandle("boundDataColumns");
            if (countBindings != null) {
                countBindings.addItem(countColumn);
            }

            RowHandle footer = factory.newTableRow(1);
            CellHandle fcell = (CellHandle) footer.getCells().get(0);
            fcell.setColumnSpan(columnCount);
            LabelHandle total = factory.newLabel(name + "_gc");
            total.setText("Всего строк в группе: ");
            total.setProperty("fontFamily", FONT_FAMILY);
            fcell.getContent().add(total);
            DataItemHandle count = factory.newDataItem(name + "_gcnt_item");
            count.setProperty("fontFamily", FONT_FAMILY);
            count.setResultSetColumn(countName);
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
