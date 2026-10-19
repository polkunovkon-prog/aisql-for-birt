/*
 * AI BIRT — тесты сборки .rptdesign из спецификации (Design API BIRT).
 *
 * Проверяют, что файл создаётся и повторно открывается моделью BIRT:
 * страница, параметры, источник данных с паролем, набор с «?»-параметрами,
 * кэш метаданных, привязки столбцов таблицы и ячейки.
 *
 * База данных не нужна: сборка файла подключения не выполняет.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.tests;

import com.aisql.birt.core.report.ReportBuilder;
import com.aisql.birt.core.spec.ReportSpec;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Iterator;

import com.ibm.icu.util.ULocale;

import org.eclipse.birt.report.model.api.AggregationArgumentHandle;
import org.eclipse.birt.report.model.api.CellHandle;
import org.eclipse.birt.report.model.api.ComputedColumnHandle;
import org.eclipse.birt.report.model.api.DataItemHandle;
import org.eclipse.birt.report.model.api.DesignConfig;
import org.eclipse.birt.report.model.api.DesignEngine;
import org.eclipse.birt.report.model.api.LabelHandle;
import org.eclipse.birt.report.model.api.MasterPageHandle;
import org.eclipse.birt.report.model.api.OdaDataSetHandle;
import org.eclipse.birt.report.model.api.OdaDataSourceHandle;
import org.eclipse.birt.report.model.api.ReportDesignHandle;
import org.eclipse.birt.report.model.api.RowHandle;
import org.eclipse.birt.report.model.api.ScalarParameterHandle;
import org.eclipse.birt.report.model.api.SessionHandle;
import org.eclipse.birt.report.model.api.TableGroupHandle;
import org.eclipse.birt.report.model.api.TableHandle;

/** Тесты сборки отчёта из спецификации. */
public final class ReportBuilderTest {

    private ReportBuilderTest() {
    }

    private static String specJson() {
        return "{"
            + " \"report\": { \"name\": \"students_build\", \"title\": \"Сборка\","
            + "   \"page\": { \"format\": \"A4\", \"orientation\": \"portrait\","
            + "               \"margins_mm\": { \"top\": 10, \"right\": 10, \"bottom\": 10, \"left\": 15 } } },"
            + " \"parameters\": [ { \"name\": \"house_code\", \"type\": \"string\","
            + "                      \"prompt\": \"Факультет\", \"default\": \"%\" } ],"
            + " \"datasources\": [ { \"name\": \"dsDB\", \"kind\": \"jdbc\","
            + "                        \"driver\": \"org.postgresql.Driver\","
            + "                        \"url\": \"jdbc:postgresql://127.0.0.1:5432/hogwarts\","
            + "                        \"user\": \"agent_user\" } ],"
            + " \"datasets\": [ { \"name\": \"dsStudents\", \"datasource\": \"dsDB\","
            + "                    \"sql\": \"select last_name, grade_level from students where (? = '%' or last_name = ?)\","
            + "                    \"params\": [ \"house_code\", \"house_code\" ],"
            + "                    \"columns\": [ { \"name\": \"last_name\", \"type\": \"string\" },"
            + "                                  { \"name\": \"grade_level\", \"type\": \"integer\" } ] } ],"
            + " \"body\": ["
            + "   { \"type\": \"label\", \"text\": \"Студенты\", \"size_pt\": 14, \"bold\": true, \"align\": \"center\" },"
            + "   { \"type\": \"table\", \"dataset\": \"dsStudents\", \"columns\": ["
            + "       { \"header\": \"Фамилия\", \"expr\": \"row[\\\"last_name\\\"]\", \"width_mm\": 50 },"
            + "       { \"header\": \"Курс\", \"expr\": \"row[\\\"grade_level\\\"]\", \"width_mm\": 20, \"align\": \"center\" } ] } ]"
            + "}";
    }

    /** Цвет «#RRGGBB» → значение RGB, как его возвращает модель BIRT. */
    private static Integer rgb(String hex) {
        return Integer.valueOf(Integer.parseInt(hex.substring(1), 16));
    }

    /** Спецификация с расширениями: стили, вычисляемая колонка, группировка. */
    private static String extendedSpecJson() {
        return "{"
            + " \"report\": { \"name\": \"students_ext\", \"title\": \"Расширения\" },"
            + " \"datasources\": [ { \"name\": \"dsDB\", \"kind\": \"jdbc\","
            + "                        \"driver\": \"org.postgresql.Driver\","
            + "                        \"url\": \"jdbc:postgresql://127.0.0.1:5432/hogwarts\","
            + "                        \"user\": \"agent_user\" } ],"
            + " \"datasets\": [ { \"name\": \"dsStudents\", \"datasource\": \"dsDB\","
            + "                    \"sql\": \"select last_name, grade_level, house_name from students\","
            + "                    \"columns\": [ { \"name\": \"last_name\", \"type\": \"string\" },"
            + "                                  { \"name\": \"grade_level\", \"type\": \"integer\" },"
            + "                                  { \"name\": \"house_name\", \"type\": \"string\" } ] } ],"
            + " \"body\": ["
            + "   { \"type\": \"label\", \"text\": \"О\", \"italic\": true, \"color\": \"#334455\" },"
            + "   { \"type\": \"table\", \"dataset\": \"dsStudents\", \"group_by\": \"house_name\","
            + "     \"group_title\": \"Факультет\", \"group_count\": true, \"total_label\": \"Итого\", \"columns\": ["
            + "       { \"header\": \"Фамилия\", \"expr\": \"row[\\\"last_name\\\"]\", \"bold\": true,"
            + "         \"background\": \"#EEF2F7\", \"header_background\": \"#DDDDE0\" },"
            + "       { \"header\": \"Балл\", \"expr\": \"row[\\\"grade_level\\\"] * 10\","
            + "         \"italic\": true, \"align\": \"right\", \"total\": \"sum\" } ] } ]"
            + "}";
    }

    public static void run() throws Exception {
        T.section("Сборка .rptdesign из спецификации");

        ReportSpec spec = ReportSpec.parse(specJson());
        File dir = Files.createTempDirectory("aibirt-build").toFile();
        File target = new File(dir, spec.name() + ".rptdesign");
        ReportBuilder.build(spec, target, "parol-dlya-testa");

        T.check(target.isFile(), "файл отчёта создан: " + target.getName());
        T.check(target.length() > 1000, "размер файла отчёта больше 1 КБ");

        String xml = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
        T.check(xml.contains("org.eclipse.birt.report.data.oda.jdbc.JdbcSelectDataSet"),
            "набор данных — JDBC-набор BIRT");
        T.check(xml.contains("cachedMetaData"), "кэш метаданных записан");
        T.check(xml.contains("boundDataColumns"), "привязки столбцов таблицы записаны");
        T.check(xml.contains("dataSetRow[\"last_name\"]"),
            "выражение привязки использует dataSetRow");
        T.check(xml.contains("encrypted-property name=\"odaPassword\""),
            "пароль сохранён в источнике данных в скрытом виде");
        T.check(xml.contains("<property name=\"dataSet\">dsStudents</property>"),
            "таблица связана с набором данных");

        // Повторное открытие модели: то, что сохранил конструктор, читается BIRT.
        SessionHandle session = new DesignEngine(new DesignConfig())
            .newSessionHandle(ULocale.ENGLISH);
        try {
            ReportDesignHandle design = session.openDesign(target.getAbsolutePath());
            T.eq(1, design.getMasterPages().getCount(), "одна мастер-страница");
            MasterPageHandle page = (MasterPageHandle) design.getMasterPages().get(0);
            T.eq("a4", page.getPageType(), "формат страницы A4");
            T.eq("portrait", page.getOrientation(), "ориентация книжная");

            ScalarParameterHandle param =
                (ScalarParameterHandle) design.getParameters().get(0);
            T.eq("house_code", param.getName(), "имя параметра отчёта");
            T.eq("%", param.getDefaultValue(), "значение по умолчанию");
            T.eq("Факультет", param.getPromptText(), "подпись параметра");

            OdaDataSourceHandle ds = (OdaDataSourceHandle) design.getDataSources().get(0);
            T.eq("jdbc:postgresql://127.0.0.1:5432/hogwarts",
                ds.getProperty("odaURL"), "строка подключения");
            T.eq("agent_user", ds.getProperty("odaUser"), "пользователь базы");
            T.eq("org.postgresql.Driver",
                ds.getProperty("odaDriverClass"), "класс драйвера JDBC");
            T.check(ds.getProperty("odaPassword") != null, "пароль в источнике данных");

            OdaDataSetHandle dataSet = (OdaDataSetHandle) design.getDataSets().get(0);
            T.check(dataSet.getQueryText().startsWith("select"), "текст SQL сохранён");
            T.eq(2, dataSet.getPropertyHandle("parameters").getListValue().size(),
                "два параметра набора («?» в SQL)");

            LabelHandle label = (LabelHandle) design.getBody().get(0);
            T.eq("Студенты", label.getText(), "надпись заголовка");

            TableHandle table = (TableHandle) design.getBody().get(1);
            T.eq(2, table.getColumns().getCount(), "две колонки таблицы");
            RowHandle detail = (RowHandle) table.getDetail().get(0);
            CellHandle cell = (CellHandle) detail.getCells().get(0);
            DataItemHandle item = (DataItemHandle) cell.getContent().get(0);
            T.eq("last_name", item.getResultSetColumn(),
                "ячейка данных привязана к колонке набора");
        } finally {
            session.closeAll(false);
        }

        // Повторная сборка в тот же файл должна перезаписывать без ошибок.
        ReportBuilder.build(spec, target, "parol-dlya-testa");
        T.check(target.isFile() && target.length() > 1000, "повторная сборка в тот же файл");

        // --- Расширения: стили, вычисляемая колонка, группировка ---
        ReportSpec ext = ReportSpec.parse(extendedSpecJson());
        File extTarget = new File(dir, ext.name() + ".rptdesign");
        ReportBuilder.build(ext, extTarget, "parol-dlya-testa");
        T.check(extTarget.isFile(), "файл отчёта с расширениями создан");

        String extXml = new String(Files.readAllBytes(extTarget.toPath()),
            StandardCharsets.UTF_8);
        T.check(extXml.contains("italic"), "стиль: курсив записан в файл");
        T.check(extXml.contains("#EEF2F7"), "стиль: фон ячеек записан в файл");
        T.check(extXml.contains("Total.count()"), "группировка: счётчик строк записан в файл");
        T.check(extXml.contains("aggregateFunction"), "итоги: агрегат записан в файл");

        SessionHandle extSession = new DesignEngine(new DesignConfig())
            .newSessionHandle(ULocale.ENGLISH);
        try {
            ReportDesignHandle design = extSession.openDesign(extTarget.getAbsolutePath());

            LabelHandle extLabel = (LabelHandle) design.getBody().get(0);
            T.eq("italic", extLabel.getProperty("fontStyle"), "надпись: курсив");
            T.eq(rgb("#334455"), extLabel.getProperty("color"), "надпись: цвет текста");

            TableHandle extTable = (TableHandle) design.getBody().get(1);
            T.eq(1, extTable.getGroups().getCount(), "группировка: одна группа");
            TableGroupHandle group = (TableGroupHandle) extTable.getGroups().get(0);
            T.eq("row[\"house_name\"]", group.getKeyExpr(), "группировка: ключ группы");
            T.eq(1, group.getHeader().getCount(), "группировка: заголовок группы");
            RowHandle groupHeader = (RowHandle) group.getHeader().get(0);
            CellHandle groupCell = (CellHandle) groupHeader.getCells().get(0);
            T.eq(2, groupCell.getColumnSpan(), "заголовок группы: объединение колонок");
            T.eq(1, group.getFooter().getCount(), "группировка: подвал со счётчиком");
            RowHandle groupFooter = (RowHandle) group.getFooter().get(0);
            CellHandle footerCell = (CellHandle) groupFooter.getCells().get(0);
            DataItemHandle countItem = (DataItemHandle) footerCell.getContent().get(1);
            String countColumn = countItem.getResultSetColumn();
            ComputedColumnHandle countBinding = extTable.findColumnBinding(countColumn);
            T.check(countBinding != null
                && "Total.count()".equals(countBinding.getExpression())
                && group.getName().equals(countBinding.getAggregateOn()),
                "счётчик группы: вычисляемая колонка Total.count() по группе");

            T.check(extTable.getPropertyHandle("boundDataColumns").getListValue().size() >= 3,
                "привязки: колонки данных, вычисления и группировки");

            RowHandle extHeader = (RowHandle) extTable.getHeader().get(0);
            CellHandle headCell = (CellHandle) extHeader.getCells().get(0);
            T.eq(rgb("#DDDDE0"), headCell.getProperty("backgroundColor"), "шапка: фон");

            RowHandle extDetail = (RowHandle) extTable.getDetail().get(0);
            CellHandle dataCell = (CellHandle) extDetail.getCells().get(0);
            T.eq(rgb("#EEF2F7"), dataCell.getProperty("backgroundColor"), "ячейка данных: фон");
            DataItemHandle dataItem = (DataItemHandle) dataCell.getContent().get(0);
            T.eq("bold", dataItem.getProperty("fontWeight"), "ячейка данных: жирный шрифт");

            CellHandle computedCell = (CellHandle) extDetail.getCells().get(1);
            DataItemHandle computed = (DataItemHandle) computedCell.getContent().get(0);
            String computedBinding = computed.getResultSetColumn();
            ComputedColumnHandle computedColumn =
                computedBinding == null ? null : extTable.findColumnBinding(computedBinding);
            T.check(computedColumn != null
                && computedColumn.getExpression() != null
                && computedColumn.getExpression().contains("grade_level"),
                "вычисляемая колонка: привязка к вычисляемой колонке таблицы");

            // Итоги: строка в подвале таблицы (подпись + агрегат по колонке).
            T.eq(1, extTable.getFooter().getCount(), "итоги: подвал таблицы");
            RowHandle totalsRow = (RowHandle) extTable.getFooter().get(0);
            CellHandle totalsLabelCell = (CellHandle) totalsRow.getCells().get(0);
            LabelHandle totalsLabel = (LabelHandle) totalsLabelCell.getContent().get(0);
            T.eq("Итого", totalsLabel.getText(), "итоги: подпись в свободной колонке");
            CellHandle totalsCell = (CellHandle) totalsRow.getCells().get(1);
            DataItemHandle totalsItem = (DataItemHandle) totalsCell.getContent().get(0);
            ComputedColumnHandle totalsBinding =
                extTable.findColumnBinding(totalsItem.getResultSetColumn());
            T.check(totalsBinding != null
                && "sum".equals(totalsBinding.getAggregateFunction()),
                "итоги: агрегат sum по колонке");
            String totalsArg = null;
            Iterator<?> args = totalsBinding == null ? null : totalsBinding.argumentsIterator();
            if (args != null && args.hasNext()) {
                AggregationArgumentHandle arg = (AggregationArgumentHandle) args.next();
                totalsArg = arg.getValue();
            }
            T.check(totalsArg != null && computedBinding != null
                && totalsArg.contains(computedBinding),
                "итоги: аргумент агрегата — ссылка на привязку колонки");
        } finally {
            extSession.closeAll(false);
        }

        // Пароль читается из готового файла (для правки без повторного ввода).
        T.eq("parol-dlya-testa", ReportBuilder.readPassword(target),
            "чтение пароля из файла отчёта");
        T.eq(null, ReportBuilder.readPassword(new File(dir, "нет-такого.rptdesign")),
            "чтение пароля: отсутствующий файл");

        // Пустая спецификация не принимается.
        try {
            ReportBuilder.build(null, target, "");
            T.check(false, "сборка без спецификации должна падать");
        } catch (ReportBuilder.ReportBuildException e) {
            T.check(true, "сборка без спецификации падает с понятной ошибкой");
        }
    }
}
