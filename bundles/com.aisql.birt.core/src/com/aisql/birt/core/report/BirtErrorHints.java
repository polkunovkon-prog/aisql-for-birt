/*
 * AI BIRT — подсказки по типовым ошибкам BIRT.
 *
 * Семантические ошибки дизайн-модели и рантайма BIRT часто сформулированы
 * технически; каждая известная ошибка получает «следующий шаг» — короткую
 * рекомендацию на русском, которую понимает и пользователь, и модель
 * (уходит в результат инструмента report_validate и в чат).
 *
 * Правила — по префиксу/подстроке (без учёта регистра). Порядок важен:
 * сначала частные правила.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Подсказки «что делать» для типовых ошибок BIRT.
 */
public final class BirtErrorHints {

    private static final class Rule {

        final String match;
        final String hint;

        Rule(String match, String hint) {
            this.match = match.toLowerCase(Locale.ROOT);
            this.hint = hint;
        }
    }

    private static final Rule[] RULES = {
        new Rule("column binding", "привязка колонки не найдена: проверьте, что имя колонки есть в columns набора данных и что в ячейке используется row[\"та же колонка\"]"),
        new Rule("invalid bound column name", "имя колонки в data-элементе не совпадает с колонками набора: перечислите колонки набора инструментом report_inspect и используйте точное имя"),
        new Rule("data set", "набор данных не найден: сначала add_dataset, затем элементы, которые его используют"),
        new Rule("data source", "источник данных не найден: сначала add_datasource, затем add_dataset"),
        new Rule("duplicate name", "имя уже занято: у каждого элемента свой уникальный name"),
        new Rule("sql", "проверьте синтаксис SELECT/WITH и алиасы колонок в datasets[].sql"),
        new Rule("oda driver class", "JDBC-драйвер не найден в Eclipse: установите драйвер или используйте доступный класс драйвера"),
        new Rule("cannot connect", "нет соединения с базой: проверьте url/user источника; пароль вводится при сборке, а не в модели"),
        new Rule("parameter", "параметр не найден: сначала add_parameter, затем ссылайтесь на него в params набора"),
        new Rule("page master", "мастер-страница: инструмент set_page создаёт её один раз"),
        new Rule("expression", "ошибка выражения: допустимы row[\"колонка\"], арифметика и сравнения; без «;» и функций"),
        new Rule("chart", "диаграмма: category/value/series должны быть колонками набора этого же элемента"),
        new Rule("group", "группа таблицы: expr должен ссылаться на колонку набора (row[\"…\"])"),
        new Rule("highlight", "подсветка: when — выражение-предикат над колонками набора (например row[\"оценка\"] < 40)"),
        new Rule("filter", "фильтр: условие строки — предикат над колонками набора; строковые значения сравнивайте через =="),
        new Rule("style", "стиль: цвета только #RRGGBB, размеры — числа"),
        new Rule("table item", "таблица: колонки описываются массивом columns; expr каждой — row[\"колонка\"]"),
    };

    private BirtErrorHints() {
    }

    /** Подсказка для текста ошибки или null. */
    public static String hint(String errorText) {
        if (errorText == null || errorText.isEmpty()) {
            return null;
        }
        String lower = errorText.toLowerCase(Locale.ROOT);
        for (Rule r : RULES) {
            if (lower.contains(r.match)) {
                return r.hint;
            }
        }
        return null;
    }

    /** Все подсказки, подходящие тексту (для тестов). */
    public static List<String> allHints(String errorText) {
        List<String> out = new ArrayList<String>();
        if (errorText == null) {
            return out;
        }
        String lower = errorText.toLowerCase(Locale.ROOT);
        for (Rule r : RULES) {
            if (lower.contains(r.match)) {
                out.add(r.hint);
            }
        }
        return out;
    }
}
