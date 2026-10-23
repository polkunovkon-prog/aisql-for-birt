# ТЗ для DBeaver AI Query Plugin

Готовый промпт для встроенного AI Chat DBeaver CE. Плагин должен **написать и
выполнить** аналитические SQL, но не менять данные и схему. Отчёт BIRT потом
использует эти SQL как есть.

## Как использовать

1. Подключиться в DBeaver к `hogwarts`, открыть AI Chat, вставить раздел
   «Промпт: Hogwarts» целиком.
2. Дождаться выполнения `HW-1`…`HW-3`; сохранить SQL из блоков `### HW-n`.
3. Переключиться на `cadia`, вставить раздел «Промпт: Cadia»; сохранить
   `CD-1`…`CD-6`.
4. Если модель ошиблась, сверить с `sql/` и использовать проверенный вариант.

Пароли и строки подключения в промпт не входят: DBeaver уже подключён.

---

## Промпт: Hogwarts

````text
РОЛЬ
Ты — аналитик PostgreSQL в DBeaver AI Chat. Работаешь только на активном
подключении к базе hogwarts. Твоя задача — написать, выполнить и отдать готовые
SELECT-запросы для BIRT-отчёта. Это не DDL/DML и не изменение данных.

ЖЁСТКИЕ ПРАВИЛА
1. Только SELECT/WITH. Запрещены INSERT, UPDATE, DELETE, CREATE, ALTER, DROP,
   TRUNCATE, GRANT, вызовы функций, меняющих данные.
2. Каждый запрос обязательно выполни через db_runQuery. Если ошибка — исправь и
   выполни снова. Не отдавай непроверенный SQL.
3. Финальный SQL — PostgreSQL, без точки с запятой, без комментариев, без psql-
   метакоманд и без Markdown внутри SQL.
4. Параметры BIRT — это ровно символы «?» в порядке появления. Не заменяй их на
   :name, $1, литералы или функции. Для проверки можно выполнить отдельную
   копию с литералами, но в финальном блоке должны остаться «?».
5. Не выбирай персональные данные (ФИО, адреса, телефоны, email). Все запросы —
   агрегированные. Исключение: названия факультетов и предметов.
6. Имена и типы колонок — строго по контракту. Лишние колонки не добавляй.
   Порядок колонок соблюдай. Пустые значения заменяй на 0/0.0 там, где указано.
7. Сортировка должна быть детерминированной.
8. После каждого задания выведи блок ровно такого вида:

### HW-1
```sql
<финальный SQL>
```

В блоке — только SQL, без пояснений. Между заданиями можно кратко написать
фактическое число строк.

СХЕМА (public)
- students(id, house_id, grade_level, is_active)
- houses(id, code, name)
- exam_results(exam_id, student_id, grade, score, date, is_active)
- enrollments(student_id, offering_id, status, is_active)
- club_members(student_id, club_id, is_active)
- library_records(student_id, book_id, is_active)
- employees(id, is_active)
- subjects(id, name)
- exams(id, is_active)

ЗАДАНИЕ HW-1 — KPI Хогвартса
Одна строка, ровно 9 колонок:
students_total integer, students_active integer, employees_total integer,
subjects_total integer, exams_total integer, avg_exam_score decimal,
excellent_pct decimal, library_loans integer, club_members integer.

Смысл:
- students_total — все студенты;
- students_active — students.is_active;
- employees_total — активные сотрудники;
- subjects_total — все дисциплины;
- exams_total — активные экзамены;
- avg_exam_score — средний exam_results.score по активным результатам,
  округлить до 1 знака;
- excellent_pct — доля grade = 'Отлично' среди активных результатов, в процентах,
  округлить до 1 знака;
- library_loans — все записи library_records;
- club_members — активные club_members.
Ожидание: 1 строка, 900, 900, 58, 12, 18, 64.9, 20.0, 2000, 150.

ЗАДАНИЕ HW-2 — факультеты
Одна строка на факультет. Параметр фильтра: код факультета; «%» означает все.
В SQL должно быть ровно два «?»: первый и второй — один и тот же параметр
house_code. Условие: (? = '%' or h.code = ?).
Колонки (11):
house_code string, house_name string, students_total integer,
students_active integer, avg_exam_score decimal, excellent_pct decimal,
completed_pct decimal, club_members integer, library_loans integer,
score_rank integer, overall_avg_score decimal.

Смысл:
- students_total/active — по факультету;
- avg_exam_score — средний score активных exam_results, 1 знак;
- excellent_pct — доля 'Отлично' среди активных результатов, 1 знак;
- completed_pct — доля enrollments.status = 'completed' среди всех зачислений
  факультета, 1 знак;
- club_members — активные участники клубов;
- library_loans — записи библиотеки;
- score_rank — rank() по avg_exam_score desc (nulls last);
- overall_avg_score — среднее avg_exam_score по всем строкам результата
  (оконная функция avg(...) over ()), 1 знак. Нужно для подсветки «ниже среднего».
Не допускай размножения строк из-за join: каждый показатель агрегируй в отдельном
CTE и присоединяй по house_id. Сортировка: score_rank, house_name.
Ожидание при '%': 4 строки. При 'H-GRA': 1 строка.

ЗАДАНИЕ HW-3 — результаты экзаменов для диаграмм
Одна строка на результат активного экзамена. Параметр house_code, ровно два «?».
Колонки (6):
house_code string, house_name string, grade_level integer,
grade_label string, score integer, cnt integer.
cnt — константа 1. Сортировка: house_name, grade_level, score.
Ожидание при '%': 1908 строк. При 'H-GRA': только Гриффиндор.
Эти данные нужны для трёх диаграмм: средний балл по факультетам, средний балл по
курсам с сериями-факультетами и распределение оценок.
````

---

## Промпт: Cadia

````text
РОЛЬ
Ты — аналитик PostgreSQL в DBeaver AI Chat. Работаешь только на активном
подключении к базе cadia. Нужно написать, выполнить и отдать готовые SELECT-
запросы для BIRT-отчёта. Данные не меняются.

ЖЁСТКИЕ ПРАВИЛА
1. Только SELECT/WITH. Никаких DDL/DML, GRANT, функций с побочными эффектами.
2. Каждый запрос выполни через db_runQuery; при ошибке исправь и выполни снова.
3. Финальный SQL — PostgreSQL, без «;», комментариев, psql-метакоманд и Markdown
   внутри SQL.
4. Параметры BIRT — ровно «?» в порядке появления; не заменяй на :name/$1.
   Для проверки можно выполнить копию с литералами, но в финале оставь «?».
5. Не выбирай ФИО, номера жетонов и прочие персональные данные. Только агрегаты,
   коды и названия подразделений/позиций.
6. Колонки и типы — строго по контракту, порядок соблюдай. Лишнего не добавляй.
7. Сортировка детерминированная.
8. После задания выведи блок:

### CD-1
```sql
<финальный SQL>
```

СХЕМА (public)
- personnel(id, rank_id, squad_id, status, is_officer, enlist_date)
- ranks(id, grade, category)
- armies(id, code, name); corps(id, army_id, code, name);
  divisions(id, corps_id, code, name); brigades(id, division_id, code, name);
  regiments(id, brigade_id, code, name); battalions(id, regiment_id, code, name);
  companies(id, battalion_id, regiment_id, code, name);
  platoons(id, company_id, code, name); squads(id, platoon_id, code, name)
- equipment(id, equipment_type, status)
- orders(id, order_date, order_type, regiment_id)
- supply_requests(id, regiment_id, request_date, item_code, qty, status)
- supply_norms(id, item_code, item_name)
- casualties(id, casualty_date, category)

Иерархия личного состава:
personnel.squad_id → squads.platoon_id → platoons.company_id →
companies.battalion_id → battalions.regiment_id → regiments.brigade_id →
brigades.division_id → divisions.corps_id → corps.army_id → armies.
В assignments есть только дежурства/командировки; для численности подчинённых
используй именно иерархию personnel.squad_id, а не count по assignments.

ЗАДАНИЕ CD-1 — KPI Кадиа
Одна строка, 9 колонок:
personnel_total integer, personnel_active integer, officers_pct decimal,
casualties_total integer, equipment_total integer,
equipment_operational_pct decimal, requested_qty integer,
delivered_qty integer, supply_delivered_pct decimal.
Смысл:
- personnel_total — все; personnel_active — status='active';
- officers_pct — доля is_officer, проценты, 1 знак;
- casualties_total — все строки casualties;
- equipment_total — все; equipment_operational_pct — доля status='operational',
  проценты, 1 знак;
- requested_qty — sum(qty) по supply_requests;
- delivered_qty — sum(qty) по status='delivered';
- supply_delivered_pct — delivered_qty / requested_qty * 100, 1 знак.
Ожидание: 1 строка; 12395, 12196, 3.4, 39, 2400, 20.0, 2094960, 525400, 25.1.

ЗАДАНИЕ CD-2 — категории званий
Одна строка на ranks.category. Колонки (8):
rank_category_ru string, personnel_total integer, active_total integer,
killed_total integer, wounded_total integer, avg_service_years decimal,
share_pct decimal, category_order integer.
- rank_category_ru: Солдаты / Сержанты / Офицеры / Старшие офицеры / Генералитет;
- personnel_total — количество людей в категории;
- active_total — status='active'; killed_total — 'killed'; wounded_total — 'wounded';
- avg_service_years — средняя выслуга на дату «as of», 1 знак. Даты в базе
  фантастические (годы ~1000–1026), поэтому НЕ используй current_date.
  as_of = max(casualties.casualty_date); выслуга = age(as_of, enlist_date);
- share_pct — доля категории, проценты, 1 знак;
- category_order — min(ranks.grade) для сортировки.
Ожидание: 5 строк; доли 80.7/15.9/2.7/0.4/0.3; средняя выслуга положительная.

ЗАДАНИЕ CD-3 — командная структура с rollup
Одна строка на командный уровень: армия, корпус, дивизия, бригада, полк,
батальон. Колонки (5):
unit_type_ru string, unit_code string, unit_name string,
personnel_count integer, level_order integer.
- unit_type_ru: Армия/Корпус/Дивизия/Бригада/Полк/Батальон;
- personnel_count — ВСЕ подчинённые, а не только штаб уровня. Построй CTE
  personnel → squads → … → armies и посчитай count для каждого уровня;
- level_order — 1..6 для сортировки.
Ожидание: 13 строк (1 армия, 1 корпус, 2 дивизии, 4 бригады, 1 полк,
4 батальона). Нулевые подразделения оставь в SQL — отчёт отфильтрует их сам.
Проверь, что сумма по батальонам = 12288 (люди с squad_id).

ЗАДАНИЕ CD-4 — техника по типам и статусам
Одна строка на equipment_type × status. Колонки (4):
equipment_type_ru string, status_ru string, cnt integer,
share_in_type_pct decimal.
- equipment_type_ru: Оружие / Техника / Тяжёлое вооружение;
- status_ru: Исправно / Повреждено / Уничтожено / На обслуживании / Списано;
- cnt — count(*); share_in_type_pct — доля статуса внутри типа, 1 знак.
Ожидание: 15 строк (3 типа × 5 статусов), сумма cnt = 2400.

ЗАДАНИЕ CD-5 — приказы по годам
Одна строка на год × тип приказа. Параметр regiment_code, ровно два «?»:
(? = '%' or r.code = ?). Колонки (3):
year_label string, order_type_ru string, cnt integer.
- year_label — extract(year from order_date)::text;
- order_type_ru: Боевые / Дисциплинарные / Кадровые / Снабжение / Переводы;
- cnt — count(*).
Сортировка: year_label, order_type_ru.
Ожидание: 60 строк (12 лет × 5 типов). При 'CAD-8' тоже 60.

ЗАДАНИЕ CD-6 — снабжение по позициям
Одна строка на item_code. Параметр regiment_code, ровно два «?».
Колонки (6):
item_code string, item_name string, requested_qty integer,
delivered_qty integer, rejected_qty integer, fill_pct decimal.
- item_name — из supply_norms по item_code (fallback — item_code);
- requested_qty — sum(qty);
- delivered_qty — sum(qty) filter status='delivered';
- rejected_qty — sum(qty) filter status='rejected';
- fill_pct — delivered_qty / requested_qty * 100, 1 знак.
Сортировка: fill_pct asc, item_code.
Ожидание: 11 строк. При 'CAD-8' тоже 11.
````

---

## Критерии приёмки SQL

* Все 9 запросов выполнены через `db_runQuery` без ошибок.
* В финальных блоках нет `;`, комментариев, `:param`, `$1`, литералов вместо
  `?`; число `?` совпадает с контрактом.
* Нет DDL/DML и персональных данных.
* Числа строк: 1, 4, 1908, 1, 5, 13, 15, 60, 11.
* Для `hw-houses` при `H-GRA` — 1 строка, при `%` — 4.
* Для `cd-orders` и `cd-supply` при `%` и `CAD-8` — 60 и 11.
* Имена/типы колонок совпадают с контрактом; сортировка устойчивая.
