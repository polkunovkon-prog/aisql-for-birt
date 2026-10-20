/*
 * AI BIRT — тесты спецификации отчёта (формат v0).
 *
 * Проверяется разбор, проверки, сводка и «ответ модели → спецификация».
 * Настоящая база данных не нужна: спецификация — просто JSON.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.tests;

import com.aisql.birt.core.spec.ReportSpec;

import java.util.List;

/** Тесты формата спецификации отчёта v0. */
public final class ReportSpecTest {

    private ReportSpecTest() {
    }

    /** Минимальная корректная спецификация. */
    private static final String MINIMAL =
        "{ \"report\": { \"name\": \"r1\", \"title\": \"Отчёт 1\" },"
        + " \"body\": [ { \"type\": \"label\", \"text\": \"Привет\" } ] }";

    /** Полная спецификация: страница, параметр, источник, набор, таблица. */
    private static String fullSpec() {
        return "{"
            + " \"report\": { \"name\": \"students_test\", \"title\": \"Студенты\","
            + "   \"page\": { \"format\": \"A4\", \"orientation\": \"portrait\","
            + "               \"margins_mm\": { \"top\": 10, \"right\": 10, \"bottom\": 10, \"left\": 15 } } },"
            + " \"parameters\": [ { \"name\": \"house_code\", \"type\": \"string\","
            + "                      \"prompt\": \"Факультет\", \"default\": \"%\" } ],"
            + " \"datasources\": [ { \"name\": \"dsDB\", \"kind\": \"jdbc\","
            + "                        \"driver\": \"org.postgresql.Driver\","
            + "                        \"url\": \"jdbc:postgresql://127.0.0.1:5432/hogwarts\","
            + "                        \"user\": \"agent_user\" } ],"
            + " \"datasets\": [ { \"name\": \"dsStudents\", \"datasource\": \"dsDB\","
            + "                    \"sql\": \"select p.last_name, s.grade_level from students s join people p on p.id = s.person_id where (? = '%' or p.last_name = ?)\","
            + "                    \"params\": [ \"house_code\", \"house_code\" ],"
            + "                    \"columns\": [ { \"name\": \"last_name\", \"type\": \"string\" },"
            + "                                  { \"name\": \"grade_level\", \"type\": \"integer\" } ] } ],"
            + " \"body\": ["
            + "   { \"type\": \"label\", \"text\": \"Студенты\", \"size_pt\": 14, \"bold\": true, \"align\": \"center\" },"
            + "   { \"type\": \"table\", \"dataset\": \"dsStudents\", \"columns\": ["
            + "       { \"header\": \"Фамилия\", \"expr\": \"row[\\\"last_name\\\"]\", \"width_mm\": 50 },"
            + "       { \"header\": \"Курс\", \"expr\": \"row[\\\"grade_level\\\"]\", \"width_mm\": 20, \"align\": \"center\", \"type\": \"integer\" } ] } ]"
            + "}";
    }

    public static void run() throws Exception {
        T.section("Спецификация отчёта v0");

        // 1. Минимум
        ReportSpec min = ReportSpec.parse(MINIMAL);
        T.eq("r1", min.name(), "имя минимальной спецификации");
        T.eq(1, min.body().size(), "в body один элемент");
        T.eq(0, min.parameters().size(), "параметров нет");
        T.eq(0, min.datasources().size(), "источников нет");

        // 2. Полная
        ReportSpec full = ReportSpec.parse(fullSpec());
        T.eq("students_test", full.name(), "имя полной спецификации");
        T.eq("Студенты", full.title(), "заголовок полной спецификации");
        T.eq(1, full.parameters().size(), "один параметр");
        T.eq("%", full.parameters().get(0).def(), "значение по умолчанию параметра");
        T.eq(1, full.datasources().size(), "один источник");
        T.check(!full.datasources().get(0).hasPassword(), "пароль в спецификации отсутствует");
        T.eq(1, full.datasets().size(), "один набор данных");
        T.eq(2, full.datasets().get(0).params().size(), "два параметра набора");
        T.eq(2, full.datasets().get(0).columns().size(), "две колонки набора");
        T.eq("A4", full.page().format(), "формат страницы A4");

        ReportSpec.Table table = null;
        ReportSpec.Label label = null;
        for (ReportSpec.BodyElement el : full.body()) {
            if (el instanceof ReportSpec.Table) {
                table = (ReportSpec.Table) el;
            } else if (el instanceof ReportSpec.Label) {
                label = (ReportSpec.Label) el;
            }
        }
        T.check(label != null && label.bold(), "надпись жирная");
        T.check(table != null, "таблица найдена");
        T.eq("dsStudents", table.dataset(), "имя набора данных таблицы");
        T.eq(2, table.columns().size(), "две колонки таблицы");
        T.eq("last_name", table.columns().get(0).resultColumn(),
            "колонка результата из row[\"last_name\"]");
        T.eq("integer", table.columns().get(1).type(), "тип второй колонки");
        T.eq("grade_level", table.columns().get(1).resultColumn(),
            "колонка результата из row[\"grade_level\"]");

        // 3. Сводка
        String summary = full.summary();
        T.check(summary.contains("students_test"), "сводка содержит имя отчёта");
        T.check(summary.contains("house_code"), "сводка содержит параметр");
        T.check(summary.contains("dsStudents"), "сводка содержит набор данных");

        // 4. Оборот JSON → объект → JSON
        ReportSpec again = ReportSpec.parse(full.toJson());
        T.eq(full.name(), again.name(), "оборот toJson/parse: имя");
        T.eq(full.body().size(), again.body().size(), "оборот toJson/parse: элементов body");
        T.eq(full.datasets().get(0).sql(), again.datasets().get(0).sql(), "оборот toJson/parse: SQL");

        // 5. Ответ модели: чистый JSON, ограждение, текст вокруг
        T.eq("r1", ReportSpec.fromLlmReply(MINIMAL).name(), "ответ: чистый JSON");
        T.eq("r1", ReportSpec.fromLlmReply("```json\n" + MINIMAL + "\n```").name(),
            "ответ: JSON в ограждении ```json");
        T.eq("r1", ReportSpec.fromLlmReply("Вот спецификация:\n" + MINIMAL + "\nГотово.").name(),
            "ответ: JSON внутри текста");

        // 6. Ошибки
        expectProblems("{\"report\":{\"name\":\"r\",\"title\":\"t\"},"
            + "\"body\":[{\"type\":\"label\",\"text\":\"x\"}],\"лишнее\":1}",
            "неизвестное поле");
        expectProblems("{\"report\":{\"name\":\"r\",\"title\":\"t\"},\"body\":[]}",
            "непустым списком");
        expectProblems("{\"report\":{\"name\":\"имя с пробелом\",\"title\":\"t\"},"
            + "\"body\":[{\"type\":\"label\",\"text\":\"x\"}]}",
            "допустимы латинские буквы");
        expectProblems("{\"report\":{\"name\":\"r\",\"title\":\"t\"},"
            + "\"body\":[{\"type\":\"label\",\"text\":\"x\",\"стиль\":\"red\"}]}",
            "неизвестное поле");
        expectProblems("{\"report\":{\"name\":\"r\",\"title\":\"t\"},"
            + "\"body\":[{\"type\":\"label\",\"text\":\"x\",\"color\":\"red\"}]}",
            "ожидается цвет");
        expectProblems(tableSpec("delete from students", "[]", ""),
            "запрещённое слово");
        expectProblems(tableSpec("select last_name from people where person_id = ?", "[]", ""),
            "знак(ов)");
        expectProblems(tableSpec("select last_name from people where name = ?", "[\"house_code\"]",
            ", \"columns\": [ { \"name\": \"last_name\", \"type\": \"string\" } ]"),
            "колонка «other» не описана");

        // 7. Проверка неверного типа параметра
        expectProblems("{\"report\":{\"name\":\"r\",\"title\":\"t\"},"
            + "\"parameters\":[{\"name\":\"p\",\"type\":\"многострочный\"}],"
            + "\"body\":[{\"type\":\"label\",\"text\":\"x\"}]}",
            "допустимы");

        // 8. Расширения: стили, вычисляемые колонки, группировка
        String extended = "{"
            + " \"report\": { \"name\": \"ext\", \"title\": \"Расширение\" },"
            + " \"datasources\": [ { \"name\": \"dsDB\", \"kind\": \"jdbc\","
            + "   \"driver\": \"org.postgresql.Driver\", \"url\": \"jdbc:postgresql://h/db\","
            + "   \"user\": \"u\" } ],"
            + " \"datasets\": [ { \"name\": \"dsA\", \"datasource\": \"dsDB\","
            + "   \"sql\": \"select last_name, grade_level, house_name from students\","
            + "   \"columns\": [ { \"name\": \"last_name\", \"type\": \"string\" },"
            + "                 { \"name\": \"grade_level\", \"type\": \"integer\" },"
            + "                 { \"name\": \"house_name\", \"type\": \"string\" } ] } ],"
            + " \"body\": ["
            + "   { \"type\": \"label\", \"text\": \"О\", \"italic\": true, \"color\": \"#334455\" },"
            + "   { \"type\": \"table\", \"dataset\": \"dsA\", \"group_by\": \"house_name\","
            + "     \"group_title\": \"Факультет\", \"group_count\": true, \"total_label\": \"Итого по области\", \"columns\": ["
            + "       { \"header\": \"Фамилия\", \"expr\": \"row[\\\"last_name\\\"]\", \"bold\": true,"
            + "         \"background\": \"#EEF2F7\", \"header_background\": \"#DDDDE0\","
            + "         \"color\": \"#112233\" },"
            + "       { \"header\": \"Балл\", \"expr\": \"row[\\\"grade_level\\\"] * 10\","
            + "         \"italic\": true, \"align\": \"right\", \"total\": \"sum\" } ] } ]"
            + "}";
        ReportSpec ext = ReportSpec.parse(extended);
        ReportSpec.Label extLabel = (ReportSpec.Label) ext.body().get(0);
        T.check(extLabel.italic() && "#334455".equals(extLabel.color()),
            "надпись: курсив и цвет");
        ReportSpec.Table extTable = (ReportSpec.Table) ext.body().get(1);
        T.eq("house_name", extTable.groupBy(), "группировка: колонка");
        T.eq("Факультет", extTable.groupTitle(), "группировка: подпись");
        T.check(extTable.groupCount(), "группировка: счётчик включён");
        T.eq("Итого по области", extTable.totalLabel(), "итоги: подпись строки");
        ReportSpec.TableColumn plain = extTable.columns().get(0);
        T.check(plain.bold() && "#EEF2F7".equals(plain.background())
            && "#DDDDE0".equals(plain.headerBackground()) && "#112233".equals(plain.color()),
            "колонка: стили ячеек");
        ReportSpec.TableColumn comp = extTable.columns().get(1);
        T.eq(null, comp.resultColumn(), "вычисляемая колонка: без прямой колонки");
        T.eq(1, comp.refs().size(), "вычисляемая колонка: одна ссылка");
        T.eq("grade_level", comp.refs().get(0), "вычисляемая колонка: ссылка grade_level");
        T.check(comp.italic(), "вычисляемая колонка: курсив");
        T.eq("sum", comp.total(), "итоги: функция sum у колонки");
        String extSummary = ext.summary();
        T.check(extSummary.contains("группировка"), "сводка: группировка упомянута");
        T.check(extSummary.contains("итоги"), "сводка: итоги упомянуты");

        ReportSpec extAgain = ReportSpec.parse(ext.toJson());
        ReportSpec.Table extTable2 = (ReportSpec.Table) extAgain.body().get(1);
        T.eq("house_name", extTable2.groupBy(), "оборот: группировка");
        T.check(extTable2.groupCount(), "оборот: счётчик группы");
        T.check(extTable2.columns().get(1).refs().contains("grade_level"),
            "оборот: ссылки вычисляемой колонки");
        T.eq("sum", extTable2.columns().get(1).total(), "оборот: функция итога");
        T.eq("Итого по области", extTable2.totalLabel(), "оборот: подпись итога");

        // Ошибки расширений
        expectProblems(extended.replace("\"#EEF2F7\"", "\"светло-серый\""),
            "ожидается цвет");
        expectProblems(extended.replace("row[\\\"grade_level\\\"] * 10",
            "row[\\\"grade_level\\\"] ; drop"),
            "недопустимые символы");
        expectProblems(extended.replace("\"group_by\": \"house_name\"",
            "\"group_by\": \"unknown_col\""),
            "не описана");
        expectProblems(extended.replace("row[\\\"grade_level\\\"] * 10",
            "row[\\\"нет_колонки\\\"] * 10"),
            "не описана");
        expectProblems(extended.replace("\"total\": \"sum\"", "\"total\": \"среднее\""),
            "допустимы");

        // 9. Условия (filter, highlight) и колонтитулы страницы
        String conditions = "{"
            + " \"report\": { \"name\": \"cond\", \"title\": \"Условия\","
            + "   \"page\": { \"header\": \"{title} — {page} из {pages}\", \"footer\": \"{date}\","
            + "               \"show_header_on_first\": false } },"
            + " \"datasources\": [ { \"name\": \"dsDB\", \"kind\": \"jdbc\","
            + "   \"driver\": \"org.postgresql.Driver\", \"url\": \"jdbc:postgresql://h/db\","
            + "   \"user\": \"u\" } ],"
            + " \"datasets\": [ { \"name\": \"dsA\", \"datasource\": \"dsDB\","
            + "   \"sql\": \"select last_name, grade_level from students\","
            + "   \"columns\": [ { \"name\": \"last_name\", \"type\": \"string\" },"
            + "                 { \"name\": \"grade_level\", \"type\": \"integer\" } ] } ],"
            + " \"body\": ["
            + "   { \"type\": \"table\", \"dataset\": \"dsA\", \"filter\": \"row[\\\"grade_level\\\"] >= 5\","
            + "     \"columns\": ["
            + "       { \"header\": \"Фамилия\", \"expr\": \"row[\\\"last_name\\\"]\" },"
            + "       { \"header\": \"Курс\", \"expr\": \"row[\\\"grade_level\\\"]\","
            + "         \"highlight\": [ { \"when\": \"row[\\\"grade_level\\\"] < 3\","
            + "                           \"color\": \"#B00020\", \"background\": \"#FFD5D5\", \"bold\": true } ] } ] } ]"
            + "}";
        ReportSpec cond = ReportSpec.parse(conditions);
        T.check(cond.page().hasHeader() && cond.page().header().contains("{page}"),
            "колонтитул: верхний шаблон принят");
        T.check(cond.page().hasFooter() && "{date}".equals(cond.page().footer()),
            "колонтитул: нижний шаблон принят");
        T.check(!cond.page().showHeaderOnFirst(), "колонтитул: отключение на первой странице");
        ReportSpec.Table condTable = (ReportSpec.Table) cond.body().get(0);
        T.eq("row[\"grade_level\"] >= 5", condTable.filter(), "условие: фильтр таблицы");
        ReportSpec.TableColumn condCol = condTable.columns().get(1);
        T.eq(1, condCol.highlights().size(), "условие: одно правило оформления");
        ReportSpec.Highlight condRule = condCol.highlights().get(0);
        T.eq("row[\"grade_level\"] < 3", condRule.when(), "условие: выражение правила");
        T.check("#FFD5D5".equals(condRule.background()) && condRule.bold()
            && "#B00020".equals(condRule.color()), "условие: фон, цвет и жирный шрифт");
        T.eq(1, condRule.refs().size(), "условие: ссылки правила");
        String condSummary = cond.summary();
        T.check(condSummary.contains("отбор строк"), "сводка: условие отбора");
        T.check(condSummary.contains("условий оформления"), "сводка: условное оформление");
        T.check(condSummary.contains("Колонтитулы"), "сводка: колонтитулы");

        ReportSpec condAgain = ReportSpec.parse(cond.toJson());
        ReportSpec.Table condTable2 = (ReportSpec.Table) condAgain.body().get(0);
        T.eq("row[\"grade_level\"] >= 5", condTable2.filter(), "оборот: фильтр таблицы");
        T.eq(1, condTable2.columns().get(1).highlights().size(), "оборот: правила оформления");
        T.eq("row[\"grade_level\"] < 3",
            condTable2.columns().get(1).highlights().get(0).when(), "оборот: выражение правила");
        T.check(!condAgain.page().showHeaderOnFirst(), "оборот: флаг первой страницы");
        T.check("{date}".equals(condAgain.page().footer()), "оборот: нижний колонтитул");

        // Ошибки условий и колонтитулов
        expectProblems(conditions.replace("row[\\\"grade_level\\\"] >= 5",
            "row[\\\"grade_level\\\"] >= 5; drop"), "недопустимые символы");
        expectProblems(conditions.replace("row[\\\"grade_level\\\"] >= 5", "1 == 1"),
            "нужно условие с row");
        expectProblems(conditions.replace("row[\\\"grade_level\\\"] < 3",
            "row[\\\"нет_колонки\\\"] < 3"), "не описана");
        expectProblems(conditions.replace("\"bold\": true", "\"жирный\": true"),
            "неизвестное поле");
        expectProblems(conditions.replace(
            "\"color\": \"#B00020\", \"background\": \"#FFD5D5\", \"bold\": true",
            "\"italic\": false"), "хотя бы одно оформление");

        // 10. Сортировка и многоуровневые группировки
        String grouped = "{"
            + " \"report\": { \"name\": \"grp\", \"title\": \"Группы\" },"
            + " \"datasources\": [ { \"name\": \"dsDB\", \"kind\": \"jdbc\","
            + "   \"driver\": \"org.postgresql.Driver\", \"url\": \"jdbc:postgresql://h/db\","
            + "   \"user\": \"u\" } ],"
            + " \"datasets\": [ { \"name\": \"dsA\", \"datasource\": \"dsDB\","
            + "   \"sql\": \"select last_name, house_name, grade_level from students\","
            + "   \"columns\": [ { \"name\": \"last_name\", \"type\": \"string\" },"
            + "                 { \"name\": \"house_name\", \"type\": \"string\" },"
            + "                 { \"name\": \"grade_level\", \"type\": \"integer\" } ] } ],"
            + " \"body\": ["
            + "   { \"type\": \"table\", \"dataset\": \"dsA\","
            + "     \"groups\": [ { \"expr\": \"row[\\\"house_name\\\"]\", \"title\": \"Факультет\", \"count\": true },"
            + "                 { \"expr\": \"grade_level\", \"title\": \"Курс\" } ],"
            + "     \"sort\": [ { \"expr\": \"row[\\\"house_name\\\"]\", \"direction\": \"asc\" },"
            + "               { \"expr\": \"row[\\\"last_name\\\"]\", \"direction\": \"desc\" } ],"
            + "     \"columns\": [ { \"header\": \"Фамилия\", \"expr\": \"row[\\\"last_name\\\"]\" },"
            + "                  { \"header\": \"Курс\", \"expr\": \"row[\\\"grade_level\\\"]\" } ] } ]"
            + "}";
        ReportSpec grp = ReportSpec.parse(grouped);
        ReportSpec.Table grpTable = (ReportSpec.Table) grp.body().get(0);
        T.eq(2, grpTable.groups().size(), "группы: два уровня");
        T.eq("house_name", grpTable.groups().get(0).column(), "группы: первый уровень");
        T.eq("Факультет", grpTable.groups().get(0).title(), "группы: подпись первого уровня");
        T.check(grpTable.groups().get(0).count(), "группы: счётчик первого уровня");
        T.eq("grade_level", grpTable.groups().get(1).column(), "группы: второй уровень");
        T.check(!grpTable.groups().get(1).count(), "группы: счётчик второго уровня выключен");
        T.eq("house_name", grpTable.groupBy(), "группы: совместимость groupBy()");
        T.eq(2, grpTable.sort().size(), "сортировка: два ключа");
        T.eq("row[\"house_name\"]", grpTable.sort().get(0).expr(), "сортировка: первый ключ");
        T.check(!grpTable.sort().get(0).descending(), "сортировка: первый по возрастанию");
        T.check(grpTable.sort().get(1).descending(), "сортировка: второй по убыванию");
        T.check(grp.summary().contains("сортировка"), "сводка: сортировка упомянута");
        ReportSpec grpAgain = ReportSpec.parse(grp.toJson());
        T.eq(2, ((ReportSpec.Table) grpAgain.body().get(0)).groups().size(),
            "оборот: уровни группировки");
        T.eq(2, ((ReportSpec.Table) grpAgain.body().get(0)).sort().size(),
            "оборот: ключи сортировки");

        // Старый одиночный group_by остаётся валидным (форма проверяется
        // отдельной спецификацией, а не заменой подстроки: экранирование кавычек
        // в литерале легко разойтись — так тест проверяет именно старую форму).
        String legacy = legacySpec();
        T.check(!legacy.equals(grouped), "старая форма group_by подготовлена");
        ReportSpec.Table legacyTable = (ReportSpec.Table) ReportSpec.parse(legacy).body().get(0);
        T.eq(1, legacyTable.groups().size(), "group_by строкой: один уровень");
        T.eq("house_name", legacyTable.groupBy(), "group_by строкой: колонка");
        T.check(legacyTable.groupCount(), "group_by строкой: счётчик");

        // Сортировка одиночным объектом.
        String singleSort = singleSortSpec();
        T.check(!singleSort.equals(grouped), "одиночная сортировка подготовлена");
        ReportSpec.Table singleTable = (ReportSpec.Table) ReportSpec.parse(singleSort).body().get(0);
        T.eq(1, singleTable.sort().size(), "сортировка: одиночный объект");
        T.check(singleTable.sort().get(0).descending(), "сортировка: направление desc");

        // Ошибки сортировки и группировок.
        expectProblems(grouped.replace("\"direction\": \"asc\"", "\"direction\": \"вверх\""),
            "допустимы");
        expectProblems(grouped.replace("\"direction\": \"asc\"", "\"direction\": \"asc\", \"x\": 1"),
            "неизвестное поле");
        expectProblems(grouped.replace("\"expr\": \"row[\\\"last_name\\\"]\", \"direction\": \"desc\"",
            "\"expr\": \"row[\\\"нет_колонки\\\"]\", \"direction\": \"desc\""), "не описана");
        expectProblems(grouped.replace("\"title\": \"Курс\"", "\"title\": \"Курс\", \"стиль\": 1"),
            "неизвестное поле");
        expectProblems(grouped.replace("\"expr\": \"grade_level\"", "\"expr\": \"house_name\""),
            "уже используется");
        expectProblems(grouped.replace("\"groups\":", "\"group_by\": \"house_name\", \"groups\":"),
            "нельзя задавать вместе");

        // 11. Список (list)
        listSpecTests();
    }

    private static void listSpecTests() throws Exception {
        String list = "{"
            + " \"report\": { \"name\": \"lst\", \"title\": \"Список\" },"
            + " \"datasources\": [ { \"name\": \"dsDB\", \"kind\": \"jdbc\","
            + "   \"driver\": \"org.postgresql.Driver\", \"url\": \"jdbc:postgresql://h/db\","
            + "   \"user\": \"u\" } ],"
            + " \"datasets\": [ { \"name\": \"dsA\", \"datasource\": \"dsDB\","
            + "   \"sql\": \"select last_name, first_name, house_name, grade_level from students\","
            + "   \"columns\": [ { \"name\": \"last_name\", \"type\": \"string\" },"
            + "                 { \"name\": \"first_name\", \"type\": \"string\" },"
            + "                 { \"name\": \"house_name\", \"type\": \"string\" },"
            + "                 { \"name\": \"grade_level\", \"type\": \"integer\" } ] } ],"
            + " \"body\": ["
            + "   { \"type\": \"list\", \"dataset\": \"dsA\", \"header\": true,"
            + "     \"footer\": [ { \"type\": \"label\", \"text\": \"Конец списка\", \"bold\": true } ],"
            + "     \"groups\": [ { \"expr\": \"row[\\\"house_name\\\"]\", \"title\": \"Факультет\", \"count\": true } ],"
            + "     \"sort\": [ { \"expr\": \"row[\\\"last_name\\\"]\", \"direction\": \"desc\" } ],"
            + "     \"columns\": ["
            + "       { \"header\": \"Фамилия\", \"expr\": \"row[\\\"last_name\\\"]\", \"width_mm\": 50, \"bold\": true },"
            + "       { \"header\": \"Имя\", \"expr\": \"row[\\\"first_name\\\"]\", \"width_mm\": 40 },"
            + "       { \"header\": \"Курс\", \"expr\": \"row[\\\"grade_level\\\"]\", \"align\": \"center\", \"total\": \"count\" } ] } ]"
            + "}";
        ReportSpec ls = ReportSpec.parse(list);
        ReportSpec.ListElement le = (ReportSpec.ListElement) ls.body().get(0);
        T.eq("dsA", le.dataset(), "список: набор данных");
        T.eq(3, le.columns().size(), "список: три колонки шаблона");
        T.eq(50.0, le.columns().get(0).widthMm(), "список: ширина колонки");
        T.check(le.columns().get(0).bold(), "список: стиль колонки");
        T.eq("count", le.columns().get(2).total(), "список: итог колонки");
        T.check(le.hasHeader(), "список: шапка из columns[].header");
        T.eq(3, le.header().size(), "список: три элемента шапки");
        T.eq("Фамилия", le.header().get(0).text(), "список: текст шапки");
        T.check(le.hasFooter(), "список: подвал");
        T.eq("Конец списка", le.footer().get(0).text(), "список: текст подвала");
        T.eq(1, le.groups().size(), "список: один уровень группировки");
        T.eq("house_name", le.groups().get(0).column(), "список: колонка группировки");
        T.check(le.groups().get(0).count(), "список: счётчик группы");
        T.eq(1, le.sort().size(), "список: ключ сортировки");
        T.check(le.sort().get(0).descending(), "список: сортировка по убыванию");
        T.eq("Всего", le.totalLabel() == null ? "Всего" : le.totalLabel(),
            "список: подпись итогов (по умолчанию)");
        String sum = ls.summary();
        T.check(sum.contains("список"), "сводка: список упомянут");
        T.check(sum.contains("группировка"), "сводка списка: группировка");
        T.check(sum.contains("сортировка"), "сводка списка: сортировка");
        ReportSpec lsAgain = ReportSpec.parse(ls.toJson());
        ReportSpec.ListElement le2 = (ReportSpec.ListElement) lsAgain.body().get(0);
        T.eq(3, le2.columns().size(), "оборот списка: колонки");
        T.eq(1, le2.groups().size(), "оборот списка: группы");
        T.eq(1, le2.sort().size(), "оборот списка: сортировка");
        T.eq(3, le2.header().size(), "оборот списка: шапка");
        T.eq("count", le2.columns().get(2).total(), "оборот списка: итог");

        // Ошибки списка
        expectProblems(list.replace("\"header\": true", "\"header\": true, \"стиль\": 1"),
            "неизвестное поле");
        expectProblems(list.replace("\"dataset\": \"dsA\"", "\"dataset\": \"dsNope\""),
            "набор данных не описан");
        expectProblems(list.replace("row[\\\"first_name\\\"]", "row[\\\"нет_колонки\\\"]"),
            "не описана");
        expectProblems(list.replace("\"width_mm\": 50", "\"width_mm\": -5"),
            "допустимо 5–300");
        expectProblems(list.replace("\"expr\": \"row[\\\"house_name\\\"]\", \"title\"",
            "\"expr\": \"\", \"title\""), "не может быть пустым");
        expectProblems(list.replace("\"groups\": [", "\"groups\": [ ], \"x\": ["),
            "неизвестное поле");
    }

    /** Спецификация с таблицей: SQL, params и доп. поля колонок задаются извне. */
    private static String tableSpec(String sql, String params, String extraColumn) {
        String expr = "row[\\\"other\\\"]";
        return "{"
            + " \"report\": { \"name\": \"r\", \"title\": \"t\" },"
            + " \"parameters\": [ { \"name\": \"house_code\", \"type\": \"string\" } ],"
            + " \"datasources\": [ { \"name\": \"dsDB\", \"kind\": \"jdbc\","
            + "   \"driver\": \"org.postgresql.Driver\", \"url\": \"jdbc:postgresql://h/db\","
            + "   \"user\": \"u\" } ],"
            + " \"datasets\": [ { \"name\": \"dsA\", \"datasource\": \"dsDB\","
            + "   \"sql\": \"" + sql + "\", \"params\": " + params
            + extraColumn + " } ],"
            + " \"body\": [ { \"type\": \"table\", \"dataset\": \"dsA\", \"columns\": ["
            + "   { \"header\": \"H\", \"expr\": \"" + expr + "\" } ] } ]"
            + "}";
    }

    /** Старая форма: один уровень через group_by строкой. */
    private static String legacySpec() {
        return "{"
            + " \"report\": { \"name\": \"grp\", \"title\": \"Группы\" },"
            + " \"datasources\": [ { \"name\": \"dsDB\", \"kind\": \"jdbc\","
            + "   \"driver\": \"org.postgresql.Driver\", \"url\": \"jdbc:postgresql://h/db\","
            + "   \"user\": \"u\" } ],"
            + " \"datasets\": [ { \"name\": \"dsA\", \"datasource\": \"dsDB\","
            + "   \"sql\": \"select last_name, house_name from students\","
            + "   \"columns\": [ { \"name\": \"last_name\", \"type\": \"string\" },"
            + "                 { \"name\": \"house_name\", \"type\": \"string\" } ] } ],"
            + " \"body\": ["
            + "   { \"type\": \"table\", \"dataset\": \"dsA\","
            + "     \"group_by\": \"house_name\", \"group_title\": \"Факультет\", \"group_count\": true,"
            + "     \"total_label\": \"Всего\","
            + "     \"columns\": [ { \"header\": \"Фамилия\", \"expr\": \"row[\\\"last_name\\\"]\" } ] } ]"
            + "}";
    }

    /** Сортировка задана одиночным объектом, а не массивом. */
    private static String singleSortSpec() {
        return "{"
            + " \"report\": { \"name\": \"srt\", \"title\": \"Сортировка\" },"
            + " \"datasources\": [ { \"name\": \"dsDB\", \"kind\": \"jdbc\","
            + "   \"driver\": \"org.postgresql.Driver\", \"url\": \"jdbc:postgresql://h/db\","
            + "   \"user\": \"u\" } ],"
            + " \"datasets\": [ { \"name\": \"dsA\", \"datasource\": \"dsDB\","
            + "   \"sql\": \"select last_name from students\","
            + "   \"columns\": [ { \"name\": \"last_name\", \"type\": \"string\" } ] } ],"
            + " \"body\": ["
            + "   { \"type\": \"table\", \"dataset\": \"dsA\","
            + "     \"sort\": { \"expr\": \"row[\\\"last_name\\\"]\", \"direction\": \"desc\" },"
            + "     \"columns\": [ { \"header\": \"Фамилия\", \"expr\": \"row[\\\"last_name\\\"]\" } ] } ]"
            + "}";
    }

    private static void expectProblems(String json, String fragment) {
        try {
            ReportSpec.parse(json);
            T.check(false, "ожидалась ошибка спецификации («" + fragment + "»)");
        } catch (ReportSpec.SpecException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            boolean ok = message.contains(fragment);
            if (!ok) {
                List<String> problems = e.problems();
                for (String p : problems) {
                    if (p != null && p.contains(fragment)) {
                        ok = true;
                        break;
                    }
                }
            }
            T.check(ok, "ошибка спецификации содержит «" + fragment + "», получено: " + message);
        }
    }
}
