# ТЗ для плагина BIRT «ИИ-конструктор отчётов»

Готовый промпт для плагина `aisql-for-birt` 0.7.0. Плагин должен собрать один
`.rptdesign` с **двумя JDBC-источниками**, девятью наборами данных, семью
таблицами и пятью диаграммами.

## Как использовать

1. Сначала выполнить `TZ-DBEAVER.md` на двух подключениях и получить SQL
   `HW-1`…`CD-6`.
2. Вставить эти SQL в конец раздела «Промпт» (место `<<<SQL>>>`), сохранив метки.
3. Вставить весь раздел «Промпт» в AI-чат плагина BIRT, указать путь к
   `.rptdesign` и пароль пользователя `agent_user` при сборке.
4. Если DBeaver не дал SQL, вставить проверенные файлы из `sql/` в том же
   порядке. Они протестированы на текущих базах.

Пароль в промпт и спецификацию не входит. В текущей версии плагина диалог
сборки спрашивает один пароль на все источники; поэтому оба источника
используют `agent_user` (локальная приёмка). Подробности — в `README.md`.

---

## Промпт

РОЛЬ
Ты — конструктор отчётов Eclipse BIRT 4.7 в плагине «ИИ-конструктор отчётов».
Собери одну полную JSON-спецификацию отчёта по требованиям ниже. Верни только
JSON (без Markdown-объяснений), если плагин не просит иначе.

ГЛАВНЫЕ ИНВАРИАНТЫ
1. Два JDBC-источника PostgreSQL в одном отчёте: hogwarts и cadia.
2. Девять наборов данных. SQL уже написан и проверен; используй его дословно,
   не переписывай, не оптимизируй, не добавляй комментарии и точку с запятой.
3. Пароли в спецификацию не включай. Поля password быть не должно.
4. Не добавляй персональные данные (ФИО, телефоны, адреса, email). Только
   агрегаты, коды, названия факультетов, подразделений и позиций снабжения.
5. Параметры отчёта: house_code и regiment_code, тип string, значения по
   умолчанию «%». В SQL каждый «?» должен получить имя из params по порядку.
6. Диаграммы: только bar, line, pie. Для pie не задавай series. Размеры в мм.
   Ширина диаграмм 250 мм (кроме pie — 190 мм), высота 90–105 мм.
7. Таблицы: используй filter для отбора строк и highlight внутри колонки для
   условного оформления. Группировка задаётся массивом groups с полем expr.
8. Grid используй только как статическую полосу-заголовок с ячейками label.
   Ячейки data в grid не используй: у grid нет dataset.
9. Не добавляй изображения, списки, произвольные extended item, crosstab,
   subreport и event scripts.
10. Если какого-то SQL нет во входных данных — остановись и перечисли
    отсутствующие блоки, не придумывай SQL.

ИСТОЧНИКИ ДАННЫХ
- ds_hogwarts: kind jdbc, driver org.postgresql.Driver,
  url jdbc:postgresql://127.0.0.1:5432/hogwarts, user agent_user.
- ds_cadia: kind jdbc, driver org.postgresql.Driver,
  url jdbc:postgresql://127.0.0.1:5432/cadia, user agent_user.
Оба без password.

ПАРАМЕТРЫ
- house_code: string, prompt «Код факультета (H-GRA, H-SLY, H-HUF, H-RAV; % —
  все)», default «%».
- regiment_code: string, prompt «Код полка (CAD-8; % — все)», default «%».

НАБОРЫ ДАННЫХ
Для каждого набора: имя, источник, метка SQL, params (точное число «?»), колонки
с типами.

1) ds_hw_kpi ← ds_hogwarts, SQL HW-1, params [].
   students_total integer, students_active integer, employees_total integer,
   subjects_total integer, exams_total integer, avg_exam_score decimal,
   excellent_pct decimal, library_loans integer, club_members integer.

2) ds_hw_houses ← ds_hogwarts, SQL HW-2,
   params ["house_code", "house_code"].
   house_code string, house_name string, students_total integer,
   students_active integer, avg_exam_score decimal, excellent_pct decimal,
   completed_pct decimal, club_members integer, library_loans integer,
   score_rank integer, overall_avg_score decimal.

3) ds_hw_results ← ds_hogwarts, SQL HW-3,
   params ["house_code", "house_code"].
   house_code string, house_name string, grade_level integer,
   grade_label string, score integer, cnt integer.

4) ds_cd_kpi ← ds_cadia, SQL CD-1, params [].
   personnel_total integer, personnel_active integer, officers_pct decimal,
   casualties_total integer, equipment_total integer,
   equipment_operational_pct decimal, requested_qty integer,
   delivered_qty integer, supply_delivered_pct decimal.

5) ds_cd_ranks ← ds_cadia, SQL CD-2, params [].
   rank_category_ru string, personnel_total integer, active_total integer,
   killed_total integer, wounded_total integer, avg_service_years decimal,
   share_pct decimal, category_order integer.

6) ds_cd_units ← ds_cadia, SQL CD-3, params [].
   unit_type_ru string, unit_code string, unit_name string,
   personnel_count integer, level_order integer.

7) ds_cd_equipment ← ds_cadia, SQL CD-4, params [].
   equipment_type_ru string, status_ru string, cnt integer,
   share_in_type_pct decimal.

8) ds_cd_orders ← ds_cadia, SQL CD-5,
   params ["regiment_code", "regiment_code"].
   year_label string, order_type_ru string, cnt integer.

9) ds_cd_supply ← ds_cadia, SQL CD-6,
   params ["regiment_code", "regiment_code"].
   item_code string, item_name string, requested_qty integer,
   delivered_qty integer, rejected_qty integer, fill_pct decimal.

СТРАНИЦА
A4, landscape, поля [10, 10, 12, 10] мм.
header: «Два мира в цифрах · {title} · стр. {page} из {pages}»
footer: «Сформировано: {date} · источники: hogwarts, cadia»
show_header_on_first: false, show_footer_on_last: true.
Название отчёта (title): «Два мира в цифрах: Хогвартс и Кадиа».
name (латиницей): two_worlds_report.

ТЕЛО ОТЧЁТА (строго в этом порядке)
1. label: «Два мира в цифрах», 18 pt, bold, color #1f3864, align center.
2. label: «Хогвартс и Кадиа: академическая и военная аналитика», 11 pt,
   italic, color #555555, align center.
3. label: «Отчёт построен по двум независимым базам PostgreSQL. Данные не
   смешиваются: каждый блок выполняется на своём источнике.», 8.5 pt,
   color #444444, align center.
4. grid g_hw: 1 колонка 277 мм, 1 строка, 1 ячейка:
   label «I. ХОГВАРТС · АКАДЕМИЧЕСКАЯ АНАЛИТИКА», bold, font_pt 13,
   color #ffffff, background #1f3864, align left.
5. label: «Ключевые показатели», 10 pt, bold, color #1f3864.
6. table t_hw_kpi, dataset ds_hw_kpi. 9 колонок, без итогов. Шапка
   #d9e2f3, жирная. Колонки (header, expr, width_mm, align, type):
   «Студентов» row["students_total"] 24 right integer;
   «Активных» row["students_active"] 20 right integer;
   «Сотрудников» row["employees_total"] 22 right integer;
   «Дисциплин» row["subjects_total"] 20 right integer;
   «Экзаменов» row["exams_total"] 18 right integer;
   «Средний балл» row["avg_exam_score"] 24 right decimal;
   «Отлично, %» row["excellent_pct"] 22 right decimal;
   «Выдач книг» row["library_loans"] 24 right integer;
   «В клубах» row["club_members"] 20 right integer.
7. label: «Факультеты», 10 pt, bold, color #1f3864.
8. table t_hw_houses, dataset ds_hw_houses. Колонки:
   «Факультет» row["house_name"] 34 left string;
   «Студентов» row["students_total"] 18 right integer total sum;
   «Активных» row["students_active"] 18 right integer total sum;
   «Средний балл» row["avg_exam_score"] 20 right decimal total avg;
   «Отлично, %» row["excellent_pct"] 22 right decimal total avg;
   «Завершили, %» row["completed_pct"] 24 right decimal total avg;
   «В клубах» row["club_members"] 18 right integer total sum;
   «Выдач книг» row["library_loans"] 20 right integer total sum;
   «Место» row["score_rank"] 14 right integer.
   total_label: «Итого».
   sort: row["score_rank"] asc, row["house_name"] asc.
   highlight колонки «Средний балл»:
   when row["avg_exam_score"] < row["overall_avg_score"],
   color #c00000, background #fde9e9, bold true.
9. chart ch_hw_bar: bar, ds_hw_results, title «Средний балл по факультетам»,
   category row["house_name"], value row["score"], aggregate avg,
   width_mm 250, height_mm 90. series не задавать.
10. chart ch_hw_line: line, ds_hw_results, title «Средний балл по курсам»,
    category row["grade_level"], series row["house_name"], value row["score"],
    aggregate avg, width_mm 250, height_mm 100.
11. chart ch_hw_pie: pie, ds_hw_results, title «Распределение оценок»,
    category row["grade_label"], value row["cnt"], aggregate sum,
    width_mm 190, height_mm 105. series не задавать.
12. grid g_cd: 1 колонка 277 мм, 1 строка, 1 ячейка:
    label «II. КАДИА · ВОЕННАЯ АНАЛИТИКА», bold, font_pt 13,
    color #ffffff, background #7b1e1e, align left.
13. label: «Ключевые показатели», 10 pt, bold, color #7b1e1e.
14. table t_cd_kpi, dataset ds_cd_kpi. 9 колонок, без итогов:
    «Военных» row["personnel_total"] 24 right integer;
    «Активных» row["personnel_active"] 20 right integer;
    «Офицеров, %» row["officers_pct"] 22 right decimal;
    «Потерь» row["casualties_total"] 20 right integer;
    «Техники» row["equipment_total"] 22 right integer;
    «Исправной, %» row["equipment_operational_pct"] 24 right decimal;
    «Запрошено» row["requested_qty"] 28 right integer;
    «Поставлено» row["delivered_qty"] 24 right integer;
    «Поставки, %» row["supply_delivered_pct"] 26 right decimal.
15. label: «Личный состав по категориям», 10 pt, bold, color #7b1e1e.
16. table t_cd_ranks, dataset ds_cd_ranks. Колонки:
    «Категория» row["rank_category_ru"] 40 left string;
    «Всего» row["personnel_total"] 22 right integer total sum;
    «Активных» row["active_total"] 18 right integer total sum;
    «Погибших» row["killed_total"] 18 right integer total sum;
    «Раненых» row["wounded_total"] 18 right integer total sum;
    «Выслуга, лет» row["avg_service_years"] 24 right decimal total avg;
    «Доля, %» row["share_pct"] 20 right decimal total sum.
    total_label: «Итого».
    sort: row["category_order"] asc.
    highlight колонки «Погибших»: when row["killed_total"] > 0,
    color #c00000, background #fde9e9, bold true.
17. label: «Командная структура», 10 pt, bold, color #7b1e1e.
18. table t_cd_units, dataset ds_cd_units. Колонки:
    «Уровень» row["unit_type_ru"] 30 left string;
    «Код» row["unit_code"] 28 left string;
    «Наименование» row["unit_name"] 80 left string;
    «Личный состав» row["personnel_count"] 30 right integer.
    filter: row["personnel_count"] > 0.
    sort: row["level_order"] asc, row["unit_code"] asc.
    Итогов не задавать: уровни пересекаются, сумма задвоит людей.
19. label: «Парк техники», 10 pt, bold, color #7b1e1e.
20. table t_cd_equipment, dataset ds_cd_equipment. 3 колонки:
    «Состояние» row["status_ru"] 50 left string;
    «Единиц» row["cnt"] 30 right integer total sum;
    «Доля в типе, %» row["share_in_type_pct"] 34 right decimal.
    groups: [{"expr":"row[\"equipment_type_ru\"]","title":"Тип","count":true}].
    total_label: «Итого».
    sort: row["status_ru"] asc.
21. chart ch_cd_equipment: bar, ds_cd_equipment,
    title «Состояние парка по типам», category row["equipment_type_ru"],
    series row["status_ru"], value row["cnt"], aggregate sum,
    width_mm 250, height_mm 100.
22. label: «Приказы по годам», 10 pt, bold, color #7b1e1e.
23. chart ch_cd_orders: line, ds_cd_orders,
    title «Приказы по годам и типам», category row["year_label"],
    series row["order_type_ru"], value row["cnt"], aggregate sum,
    width_mm 250, height_mm 100.
24. label: «Снабжение», 10 pt, bold, color #7b1e1e.
25. table t_cd_supply, dataset ds_cd_supply. Колонки:
    «Код» row["item_code"] 20 left string;
    «Позиция» row["item_name"] 75 left string;
    «Запрошено» row["requested_qty"] 26 right integer total sum;
    «Поставлено» row["delivered_qty"] 24 right integer total sum;
    «Отклонено» row["rejected_qty"] 24 right integer total sum;
    «Поставки, %» row["fill_pct"] 22 right decimal total avg.
    total_label: «Итого».
    sort: row["fill_pct"] asc, row["item_code"] asc.
    highlight колонки «Поставки, %»: when row["fill_pct"] < 25,
    color #c00000, background #fde9e9, bold true.
26. label: «Методика: агрегаты по двум независимым источникам; SQL подготовлен
    DBeaver AI, отчёт собран BIRT AI. Персональные данные не выгружаются.»,
    8 pt, italic, color #666666, align left.

ВХОДНЫЕ SQL
Вставь сюда вывод DBeaver (или файлы из sql/) с метками. Соответствие:
HW-1 → ds_hw_kpi; HW-2 → ds_hw_houses; HW-3 → ds_hw_results;
CD-1 → ds_cd_kpi; CD-2 → ds_cd_ranks; CD-3 → ds_cd_units;
CD-4 → ds_cd_equipment; CD-5 → ds_cd_orders; CD-6 → ds_cd_supply.

<<<SQL>>>

ПРОВЕРКА ПЕРЕД ОТВЕТОМ
- В спецификации ровно 2 datasources, 9 datasets, 2 parameters.
- У ds_hw_houses, ds_hw_results, ds_cd_orders, ds_cd_supply в params по два
  одинаковых имени; число «?» в SQL совпадает.
- В SQL нет «;», комментариев, :param, $1.
- Все 9 SQL присутствуют; ни один не переписан.
- В таблицах нет ссылок на колонки, которых нет в контракте.
- В pie нет series; у всех chart есть width_mm и height_mm.
- В спецификации нет password и персональных данных.
- В body 26 элементов, из них 5 chart, 7 table, 2 grid.

---

## Приёмка после сборки

Ожидается `.rptdesign` с двумя connection profile и девятью data sets.
При сборке ввести пароль `agent_user`; отчёт должен открыться в BIRT 4.7.

Проверить:

* `SEVERE=0` в логе рендера; PDF и HTML создаются.
* При `house_code=%`, `regiment_code=%`: таблица факультетов — 4 строки,
  категорий званий — 5, командная структура — 9 (после фильтра), техника —
  15, снабжение — 11.
* Пять диаграмм непустые: 3 в блоке Хогвартса и 2 в блоке Кадиа.
* При `house_code=H-GRA`: таблица факультетов — 1 строка, диаграммы Хогвартса
  показывают только Гриффиндор.
* При `regiment_code=CAD-8`: приказы и снабжение непустые.
* В PDF есть верхний/нижний колонтитулы с номерами страниц и датой.
* Нет персональных данных и паролей в `.rptdesign`.

Ориентировочный объём: 8–11 страниц A4 landscape.

## Если DBeaver-модель ошиблась

Проверенные SQL лежат в `sql/`:

| Блок | Файл | Строк |
|---|---|---|
| HW-1 | `sql/hw-kpi.sql` | 1 |
| HW-2 | `sql/hw-houses.sql` | 4 |
| HW-3 | `sql/hw-results.sql` | 1908 |
| CD-1 | `sql/cd-kpi.sql` | 1 |
| CD-2 | `sql/cd-ranks.sql` | 5 |
| CD-3 | `sql/cd-units.sql` | 13 (9 ненулевых) |
| CD-4 | `sql/cd-equipment.sql` | 15 |
| CD-5 | `sql/cd-orders.sql` | 60 |
| CD-6 | `sql/cd-supply.sql` | 11 |

SQL используют `?` для BIRT; для ручной проверки в psql замените `?` на `%`
или код. Пароли в файлах отсутствуют.
