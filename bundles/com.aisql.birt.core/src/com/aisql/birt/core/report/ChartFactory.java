/*
 * AI BIRT — модель диаграммы BIRT (Chart) для спецификации отчёта.
 *
 * Диаграмма собирается штатным Chart Model API BIRT 4.7 и передаётся
 * расширенному элементу «Chart» (ChartReportItemImpl.setModel); при сборке
 * .rptdesign модель сохраняется в xmlRepresentation. Формат — канонический
 * BIRT, как у конструктора диаграмм.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core.report;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;

import com.aisql.birt.core.spec.ReportSpec;

import org.eclipse.birt.chart.model.Chart;
import org.eclipse.birt.chart.model.ChartWithAxes;
import org.eclipse.birt.chart.model.ChartWithoutAxes;
import org.eclipse.birt.chart.model.attribute.AxisType;
import org.eclipse.birt.chart.model.attribute.DataType;
import org.eclipse.birt.chart.model.attribute.Orientation;
import org.eclipse.birt.chart.model.attribute.impl.ColorDefinitionImpl;
import org.eclipse.birt.chart.model.component.Axis;
import org.eclipse.birt.chart.model.component.Series;
import org.eclipse.birt.chart.model.component.impl.SeriesImpl;
import org.eclipse.birt.chart.model.data.Query;
import org.eclipse.birt.chart.model.data.SeriesDefinition;
import org.eclipse.birt.chart.model.data.SeriesGrouping;
import org.eclipse.birt.chart.model.data.impl.QueryImpl;
import org.eclipse.birt.chart.model.data.impl.SeriesDefinitionImpl;
import org.eclipse.birt.chart.model.data.impl.SeriesGroupingImpl;
import org.eclipse.birt.chart.model.impl.ChartWithAxesImpl;
import org.eclipse.birt.chart.model.impl.ChartWithoutAxesImpl;
import org.eclipse.birt.chart.model.impl.SerializerImpl;
import org.eclipse.birt.chart.model.type.PieSeries;
import org.eclipse.birt.chart.model.type.impl.BarSeriesImpl;
import org.eclipse.birt.chart.model.type.impl.LineSeriesImpl;
import org.eclipse.birt.chart.model.type.impl.PieSeriesImpl;
import org.eclipse.birt.chart.reportitem.ChartReportItemImpl;
import org.eclipse.birt.report.model.api.ExtendedItemHandle;
import org.eclipse.birt.report.model.api.extension.IReportItem;

/** Сборка модели диаграммы BIRT по элементу спецификации {@code chart}. */
final class ChartFactory {

    private static final String FONT = "DejaVu Sans";

    private ChartFactory() {
    }

    /**
     * Кладёт модель диаграммы в расширенный элемент. Штатный путь конструктора
     * BIRT — {@link ChartReportItemImpl#setModel}; если расширение диаграмм
     * почему-то не загружено, остаётся записать XML в свойство напрямую.
     */
    static void attach(ExtendedItemHandle item, ReportSpec.Chart chart) throws Exception {
        Chart model = build(chart);
        IReportItem reportItem = item.getReportItem();
        if (reportItem instanceof ChartReportItemImpl) {
            ((ChartReportItemImpl) reportItem).setModel(model);
        } else {
            item.setProperty("xmlRepresentation", serialize(model));
        }
    }

    /** Модель диаграммы BIRT для расширенного элемента отчёта. */
    static Chart build(ReportSpec.Chart chart) {
        if ("pie".equals(chart.kind())) {
            return pie(chart);
        }
        return withAxes(chart);
    }

    /** Столбчатая и линейная диаграммы (оси категорий и значений). */
    private static ChartWithAxes withAxes(ReportSpec.Chart c) {
        boolean bar = "bar".equals(c.kind());
        ChartWithAxes chart = ChartWithAxesImpl.create();
        chart.setType(bar ? "Bar Chart" : "Line Chart");
        chart.setSubType(bar ? "Side-by-side" : "Standard");
        chart.setOrientation(Orientation.VERTICAL_LITERAL);
        // Легенда нужна только при разбиении на ряды: без series у ряда нет
        // подписи и легенда рисовала бы маркеры без текста.
        chart.getLegend().setVisible(c.series() != null);
        applyTitle(chart, c.title());

        Axis xAxis = chart.getBaseAxes()[0];
        xAxis.setType(AxisType.TEXT_LITERAL);
        // Ось категорий — как в модели конструктора диаграмм BIRT.
        xAxis.setCategoryAxis(true);
        SeriesDefinition sdX = SeriesDefinitionImpl.create();
        applyPalette(sdX);
        xAxis.getSeriesDefinitions().add(sdX);
        // Категории — базовая серия определения оси X (как в конструкторе BIRT).
        Series base = SeriesImpl.create();
        base.getDataDefinition().add(QueryImpl.create(column(c.category())));
        sdX.getSeries().add(base);

        Axis yAxis = chart.getOrthogonalAxes(xAxis, true)[0];
        yAxis.setType(AxisType.LINEAR_LITERAL);
        SeriesDefinition sdY = SeriesDefinitionImpl.create();
        applyPalette(sdY);
        yAxis.getSeriesDefinitions().add(sdY);
        if (c.series() != null) {
            // Разбиение значений на ряды (легенда).
            sdY.setQuery(QueryImpl.create(column(c.series())));
        }
        Series values = bar ? BarSeriesImpl.create() : LineSeriesImpl.create();
        values.getDataDefinition().add(valueQuery(c));
        sdY.getSeries().add(values);
        applyFonts(chart);
        applySize(chart, c);
        return chart;
    }

    /** Круговая диаграмма (без осей). */
    private static ChartWithoutAxes pie(ReportSpec.Chart c) {
        ChartWithoutAxes chart = ChartWithoutAxesImpl.create();
        chart.setType("Pie Chart");
        chart.setSubType("Standard");
        applyTitle(chart, c.title());

        SeriesDefinition sd = SeriesDefinitionImpl.create();
        applyPalette(sd);
        chart.getSeriesDefinitions().add(sd);
        // Круговая: вложенное определение рядов — значения (PieSeries),
        // в базовом остаётся серия категорий. Такую структуру ожидает
        // генератор запросов BIRT (getSeriesDefinitions().get(0)).
        SeriesDefinition sdValues = SeriesDefinitionImpl.create();
        applyPalette(sdValues);
        sd.getSeriesDefinitions().add(sdValues);
        PieSeries values = (PieSeries) PieSeriesImpl.create();
        values.getDataDefinition().add(valueQuery(c));
        sdValues.getSeries().add(values);
        Series base = SeriesImpl.create();
        base.getDataDefinition().add(QueryImpl.create(column(c.category())));
        sd.getSeries().add(base);
        applyFonts(chart);
        applySize(chart, c);
        return chart;
    }

    /** Выражение значения с агрегатом (Sum/Average/Min/Max/Count). */
    private static Query valueQuery(ReportSpec.Chart c) {
        Query q = QueryImpl.create(column(c.value()));
        SeriesGrouping g = SeriesGroupingImpl.create();
        // Тип и агрегат — как в конструкторе BIRT; enabled оставляем по
        // умолчанию (это штатная модель группировки значения).
        g.setGroupType(DataType.TEXT_LITERAL);
        g.setAggregateExpression(aggregateName(c.aggregate()));
        q.setGrouping(g);
        return q;
    }

    private static String aggregateName(String aggregate) {
        if ("count".equals(aggregate)) {
            return "Count";
        }
        if ("avg".equals(aggregate)) {
            return "Average";
        }
        if ("min".equals(aggregate)) {
            return "Min";
        }
        if ("max".equals(aggregate)) {
            return "Max";
        }
        return "Sum";
    }

    /** Выражение привязки колонки (row["..."], как в конструкторе BIRT). */
    private static String column(String name) {
        return "row[\"" + name + "\"]";
    }

    /** Шрифт диаграммы: без него кириллица в PDF/SVG выходит «кракозябрами». */
    private static void applyFonts(Chart chart) {
        chart.getTitle().getLabel().getCaption().getFont().setName(FONT);
        chart.getLegend().getText().getFont().setName(FONT);
        if (chart instanceof ChartWithAxes) {
            for (Axis ax : ((ChartWithAxes) chart).getAxes()) {
                applyAxisFonts(ax);
            }
        } else if (chart instanceof ChartWithoutAxes) {
            for (SeriesDefinition sd : ((ChartWithoutAxes) chart).getSeriesDefinitions()) {
                applySeriesFonts(sd);
            }
        }
    }

    private static void applyAxisFonts(Axis axis) {
        axis.getLabel().getCaption().getFont().setName(FONT);
        axis.getTitle().getCaption().getFont().setName(FONT);
        for (SeriesDefinition sd : axis.getSeriesDefinitions()) {
            applySeriesFonts(sd);
        }
    }

    private static void applySeriesFonts(SeriesDefinition sd) {
        for (Series s : sd.getSeries()) {
            s.getLabel().getCaption().getFont().setName(FONT);
        }
        for (SeriesDefinition child : sd.getSeriesDefinitions()) {
            applySeriesFonts(child);
        }
    }

    /** Размер блока диаграммы в точках: движок рисует в этих границах. */
    private static void applySize(Chart chart, ReportSpec.Chart c) {
        double widthMm = c.widthMm() == null ? 160.0 : c.widthMm().doubleValue();
        double heightMm = c.heightMm() == null ? 90.0 : c.heightMm().doubleValue();
        chart.getBlock().getBounds().set(0, 0,
            widthMm * 72.0 / 25.4, heightMm * 72.0 / 25.4);
    }

    /** Палитра рядов: конструктор BIRT всегда заполняет её цветами. */
    private static void applyPalette(SeriesDefinition sd) {
        int[][] colors = { { 79, 129, 189 }, { 192, 80, 77 }, { 155, 187, 89 },
            { 128, 100, 162 }, { 75, 172, 198 }, { 247, 150, 70 } };
        for (int[] rgb : colors) {
            sd.getSeriesPalette().getEntries().add(
                ColorDefinitionImpl.create(rgb[0], rgb[1], rgb[2]));
        }
    }

    private static void applyTitle(Chart chart, String title) {
        if (title != null && !title.isEmpty()) {
            chart.getTitle().getLabel().getCaption().setValue(title);
            chart.getTitle().getLabel().setVisible(true);
        }
    }

    private static String serialize(Chart chart) throws Exception {
        ByteArrayOutputStream out = SerializerImpl.instance().asXml(chart, true);
        return new String(out.toByteArray(), Charset.forName("UTF-8"));
    }
}
