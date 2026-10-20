/*
 * AI BIRT — различия двух спецификаций отчёта.
 *
 * При правке отчёта модель возвращает полную обновлённую спецификацию;
 * пользователь перед применением видит список изменений «было → стало»
 * (заголовок, параметры, наборы данных, колонки, стили, группировка).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core.spec;

import java.util.ArrayList;
import java.util.List;

/** Человекочитаемые различия между двумя спецификациями (для панели чата). */
public final class SpecDiff {

    private SpecDiff() {
    }

    /** Список строк «что изменилось»; пустой список — спецификации совпадают. */
    public static List<String> describe(ReportSpec before, ReportSpec after) {
        List<String> out = new ArrayList<String>();
        if (before == null || after == null) {
            return out;
        }
        if (!eq(before.title(), after.title())) {
            out.add("Заголовок: «" + before.title() + "» → «" + after.title() + "»");
        }
        if (!eq(before.name(), after.name())) {
            out.add("Имя отчёта: " + before.name() + " → " + after.name());
        }
        diffPage(before.page(), after.page(), out);
        diffList(out, "Параметр", paramNames(before), paramNames(after));
        diffList(out, "Источник данных", dsNames(before), dsNames(after));
        diffList(out, "Набор данных", dataSetNames(before), dataSetNames(after));
        diffDataSets(before, after, out);
        diffBody(before.body(), after.body(), out);
        return out;
    }

    // ------------------------------------------------------------------

    private static void diffPage(ReportSpec.Page a, ReportSpec.Page b, List<String> out) {
        if (a == null || b == null) {
            if (a != b) {
                out.add(a == null ? "Добавлены параметры страницы" : "Удалены параметры страницы");
            }
            return;
        }
        if (!eq(a.format(), b.format())) {
            out.add("Формат страницы: " + a.format() + " → " + b.format());
        }
        if (!eq(a.orientation(), b.orientation())) {
            out.add("Ориентация: " + orientRu(a.orientation()) + " → " + orientRu(b.orientation()));
        }
    }

    private static void diffDataSets(ReportSpec before, ReportSpec after, List<String> out) {
        for (ReportSpec.DataSet b : after.datasets()) {
            ReportSpec.DataSet a = findDataSet(before, b.name());
            if (a == null) {
                continue;
            }
            if (!eq(a.sql(), b.sql())) {
                out.add("Набор «" + b.name() + "»: изменён SQL-запрос");
            }
            if (!a.params().equals(b.params())) {
                out.add("Набор «" + b.name() + "»: параметры запроса: "
                    + fmtList(a.params()) + " → " + fmtList(b.params()));
            }
            if (!eq(colNames(a), colNames(b))) {
                out.add("Набор «" + b.name() + "»: колонки: "
                    + fmtList(colNames(a)) + " → " + fmtList(colNames(b)));
            }
        }
    }

    private static void diffBody(List<ReportSpec.BodyElement> a, List<ReportSpec.BodyElement> b,
            List<String> out) {
        int common = Math.min(a.size(), b.size());
        for (int i = 0; i < common; i++) {
            diffElement(a.get(i), b.get(i), i + 1, out);
        }
        if (b.size() > a.size()) {
            out.add("Добавлено элементов в тело отчёта: " + (b.size() - a.size()));
        } else if (a.size() > b.size()) {
            out.add("Удалено элементов из тела отчёта: " + (a.size() - b.size()));
        }
    }

    private static void diffElement(ReportSpec.BodyElement a, ReportSpec.BodyElement b,
            int index, List<String> out) {
        if (a instanceof ReportSpec.Label && b instanceof ReportSpec.Label) {
            ReportSpec.Label la = (ReportSpec.Label) a;
            ReportSpec.Label lb = (ReportSpec.Label) b;
            if (!eq(la.text(), lb.text())) {
                out.add("Надпись: «" + ellipsis(la.text()) + "» → «" + ellipsis(lb.text()) + "»");
            }
            List<String> style = new ArrayList<String>();
            if (!eq(la.sizePt(), lb.sizePt())) {
                style.add("размер");
            }
            if (la.bold() != lb.bold()) {
                style.add("жирность");
            }
            if (la.italic() != lb.italic()) {
                style.add("курсив");
            }
            if (!eq(la.color(), lb.color())) {
                style.add("цвет");
            }
            if (!eq(la.align(), lb.align())) {
                style.add("выравнивание");
            }
            if (!style.isEmpty()) {
                out.add("Надпись «" + ellipsis(lb.text()) + "»: изменено ("
                    + fmtList(style) + ")");
            }
        } else if (a instanceof ReportSpec.Table && b instanceof ReportSpec.Table) {
            diffTable((ReportSpec.Table) a, (ReportSpec.Table) b, out);
        } else if (!a.type().equals(b.type())) {
            out.add("Элемент " + index + ": " + typeRu(a.type()) + " → " + typeRu(b.type()));
        }
    }

    private static void diffTable(ReportSpec.Table a, ReportSpec.Table b, List<String> out) {
        String where = "Таблица (" + b.dataset() + ")";
        if (!eq(a.dataset(), b.dataset())) {
            out.add(where + ": набор данных: " + a.dataset() + " → " + b.dataset());
        }
        String groupsA = groupsText(a);
        String groupsB = groupsText(b);
        if (!eq(groupsA, groupsB)) {
            out.add(where + ": группировка: " + groupsA + " → " + groupsB);
        }
        String sortA = sortText(a);
        String sortB = sortText(b);
        if (!eq(sortA, sortB)) {
            out.add(where + ": сортировка: " + sortA + " → " + sortB);
        }
        int common = Math.min(a.columns().size(), b.columns().size());
        for (int i = 0; i < common; i++) {
            diffColumn((ReportSpec.TableColumn) a.columns().get(i),
                (ReportSpec.TableColumn) b.columns().get(i), where, out);
        }
        if (b.columns().size() > a.columns().size()) {
            List<String> added = new ArrayList<String>();
            for (int i = a.columns().size(); i < b.columns().size(); i++) {
                added.add(b.columns().get(i).header());
            }
            out.add(where + ": добавлены колонки: " + fmtList(added));
        } else if (a.columns().size() > b.columns().size()) {
            List<String> removed = new ArrayList<String>();
            for (int i = b.columns().size(); i < a.columns().size(); i++) {
                removed.add(a.columns().get(i).header());
            }
            out.add(where + ": удалены колонки: " + fmtList(removed));
        }
    }

    /** Текст уровней группировки для различий («нет» или «A по col → B по col»). */
    private static String groupsText(ReportSpec.Table t) {
        if (!t.hasGroups()) {
            return "нет";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.groups().size(); i++) {
            ReportSpec.GroupLevel g = t.groups().get(i);
            if (i > 0) {
                sb.append(" → ");
            }
            sb.append('«').append(g.title()).append("» по ").append(g.column());
            if (g.count()) {
                sb.append(" (со счётчиком)");
            }
        }
        return sb.toString();
    }

    /** Текст ключей сортировки для различий («нет» или «col asc, col2 desc»). */
    private static String sortText(ReportSpec.Table t) {
        if (t.sort().isEmpty()) {
            return "нет";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.sort().size(); i++) {
            ReportSpec.SortKey s = t.sort().get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(s.expr()).append(' ').append(s.direction());
        }
        return sb.toString();
    }

    private static void diffColumn(ReportSpec.TableColumn a, ReportSpec.TableColumn b,
            String where, List<String> out) {
        List<String> changed = new ArrayList<String>();
        if (!eq(a.expr(), b.expr())) {
            changed.add("выражение «" + a.expr() + "» → «" + b.expr() + "»");
        }
        if (!eq(a.header(), b.header())) {
            changed.add("шапка «" + a.header() + "» → «" + b.header() + "»");
        }
        if (!eq(a.widthMm(), b.widthMm())) {
            changed.add("ширина");
        }
        if (!eq(a.align(), b.align())) {
            changed.add("выравнивание");
        }
        if (!eq(a.type(), b.type())) {
            changed.add("тип");
        }
        if (a.bold() != b.bold()) {
            changed.add("жирность");
        }
        if (a.italic() != b.italic()) {
            changed.add("курсив");
        }
        if (!eq(a.color(), b.color())) {
            changed.add("цвет текста");
        }
        if (!eq(a.background(), b.background())) {
            changed.add("фон ячеек");
        }
        if (!eq(a.headerBackground(), b.headerBackground())) {
            changed.add("фон шапки");
        }
        if (!changed.isEmpty()) {
            out.add(where + ", колонка «" + b.header() + "»: изменено ("
                + fmtList(changed) + ")");
        }
    }

    // ------------------------------------------------------------------

    private static void diffList(List<String> out, String what,
            List<String> before, List<String> after) {
        List<String> added = new ArrayList<String>(after);
        added.removeAll(before);
        List<String> removed = new ArrayList<String>(before);
        removed.removeAll(after);
        if (!added.isEmpty()) {
            out.add(what + ": добавлено — " + fmtList(added));
        }
        if (!removed.isEmpty()) {
            out.add(what + ": удалено — " + fmtList(removed));
        }
    }

    private static ReportSpec.DataSet findDataSet(ReportSpec spec, String name) {
        for (ReportSpec.DataSet d : spec.datasets()) {
            if (d.name().equals(name)) {
                return d;
            }
        }
        return null;
    }

    private static List<String> paramNames(ReportSpec spec) {
        List<String> names = new ArrayList<String>();
        for (ReportSpec.Parameter p : spec.parameters()) {
            names.add(p.name());
        }
        return names;
    }

    private static List<String> dsNames(ReportSpec spec) {
        List<String> names = new ArrayList<String>();
        for (ReportSpec.DataSource d : spec.datasources()) {
            names.add(d.name());
        }
        return names;
    }

    private static List<String> dataSetNames(ReportSpec spec) {
        List<String> names = new ArrayList<String>();
        for (ReportSpec.DataSet d : spec.datasets()) {
            names.add(d.name());
        }
        return names;
    }

    private static List<String> colNames(ReportSpec.DataSet ds) {
        List<String> names = new ArrayList<String>();
        for (ReportSpec.DatasetColumn c : ds.columns()) {
            names.add(c.name());
        }
        return names;
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String text(String s) {
        return s == null ? "нет" : s;
    }

    private static String orientRu(String orientation) {
        return "landscape".equals(orientation) ? "альбомная" : "книжная";
    }

    private static String typeRu(String type) {
        return "table".equals(type) ? "таблица" : "надпись";
    }

    private static String fmtList(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static String ellipsis(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 40 ? s : s.substring(0, 39) + "…";
    }
}
