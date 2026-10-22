# ADR-0001: формат спецификации отчёта v0

- **Статус:** принято (18.10.2026), реализовано в M2-MVP; расширено обратно
  совместимо в 0.3.0–0.7.0
- **Контекст:** плагин «ИИ-конструктор отчётов» для BIRT 4.7

## Проблема

Модель должна превращать текстовое ТЗ в файл отчёта `.rptdesign`. Вариант
«пусть модель пишет XML отчёта напрямую» плох: формат BIRT огромен, модель
ошибается в XML, а ошибки проявляются только при открытии файла (пользователь
видит «сломанный отчёт» вместо понятного сообщения).

## Решение

Между моделью и BIRT вводится **компактная JSON-спецификация отчёта (v0)**:

1. модель возвращает **только JSON** (никакого текста вокруг);
2. клиент **строго проверяет** спецификацию (неизвестные поля — ошибка) и
   показывает понятные причины отказа по-русски;
3. из спецификации клиент собирает `.rptdesign` **штатным Design API BIRT**
   (`org.eclipse.birt.report.model`) — файл сразу совместим с дизайнером;
4. SQL в спецификации — только **чтение** (`SELECT`/`WITH`), проверяется
   консервативно (запрещённые слова, многооператорность, длина);
5. **пароль базы** в спецификацию не попадает: его спрашивают отдельным
   диалогом в момент сборки и сохраняют в отчёте в скрытом виде (как делает
   сам дизайнер BIRT).

Реализация: `spec/ReportSpec.java` (разбор и проверки), `spec/SpecPrompts.java`
(инструкция для модели), `spec/ReadOnlySql.java` (проверка SQL),
`report/ReportBuilder.java` (сборка через Design API).

## Схема v0

Корневые поля: `report` (обязательно), `body` (обязательно), `parameters`,
`datasources`, `datasets` — по желанию. Других полей быть не должно.

| Поле | Тип | Значение |
|---|---|---|
| `report.name` | string | имя файла отчёта: латиница/цифры/`_`, с буквы, до 60 символов |
| `report.title` | string | заголовок, до 300 символов |
| `report.page.format` | string | `A4`, `A3`, `A5`, `Letter`, `Legal`, `Ledger` (можно не задавать) |
| `report.page.orientation` | string | `portrait` (по умолчанию) или `landscape` |
| `report.page.margins_mm` | object | `top`, `right`, `bottom`, `left` — числа 5–100 |
| `report.page.header`, `footer` | string | колонтитулы с `{page}`, `{pages}`, `{title}`, `{date}` (0.7.0) |
| `report.page.show_header_on_first`, `show_footer_on_last` | boolean | показ на первой/последней странице |
| `parameters[].name` | string | имя параметра (латиница/цифры/`_`) |
| `parameters[].type` | string | `string`, `integer`, `decimal`, `date`, `datetime`, `boolean` |
| `parameters[].prompt` | string | подпись в диалоге параметров |
| `parameters[].default` | string | значение по умолчанию (строкой) |
| `datasources[].name` | string | имя источника (обычно `ds...`) |
| `datasources[].kind` | string | только `jdbc` |
| `datasources[].driver` | string | класс JDBC-драйвера, например `org.postgresql.Driver` |
| `datasources[].url` | string | начинается с `jdbc:` |
| `datasources[].user` | string | пользователь базы |
| `datasources[].password` | string | допустимо, но модель его не присылает (см. выше) |
| `datasets[].name` | string | имя набора (обычно `ds...`) |
| `datasets[].datasource` | string | имя источника из `datasources` |
| `datasets[].sql` | string | один запрос `SELECT`/`WITH`; `?` — параметры, порядок = `params` |
| `datasets[].params[]` | string[] | имя параметра отчёта на каждый `?` (по порядку) |
| `datasets[].columns[]` | array | `{name, type}` — колонки результата (`string`, `integer`, `decimal`, `date`, …) |
| `body[].type` | string | `label`, `table`, `list`, `grid`, `image` или `chart` |
| `label.text` | string | текст надписи (до 500 символов) |
| `label.size_pt` | number | размер шрифта, 6–72 |
| `label.bold` | boolean | жирный |
| `label.align` | string | `left`, `center`, `right` |
| `table.dataset` | string | имя набора данных |
| `table.columns[].header` | string | текст в шапке |
| `table.columns[].expr` | string | `row["колонка"]` (колонка из `datasets[].columns`) или простое выражение BIRT |
| `table.columns[].width_mm` | number | ширина, 5–300 мм |
| `table.columns[].align` | string | выравнивание ячейки |
| `table.columns[].type` | string | тип данных колонки (подсказка для отчёта) |
| `table.groups[]` | array | до 5 уровней: `expr`, `title`, `count` (0.6.0) |
| `table.sort` | object/array | `expr` + `direction` (`asc`/`desc`) — один или несколько ключей (0.6.0) |
| `table.highlight[]` | array | `when`, `color`, `background`, `bold`, `italic` (0.7.0) |
| `table.filter` | string | условие строки — JS-выражение (`row["…"] >= 0`), оператор `is-true` (0.7.0) |
| `list` | object | `dataset`, `columns`, `header` (шапка), `footer`, `groups`, `sort`, `total_label` (0.7.0) |
| `grid` | object | `column_widths_mm`, `rows[].cells[]` (`type`/`text`/`expr`/`dataset`/`col_span`/`row_span`); вложенные элементы (0.5.0) |
| `image` | object | `path` (файл PNG) или `base64`, `width_mm`, `height_mm` (0.5.0) |
| `chart` | object | `kind` (`bar`/`line`/`pie`), `dataset`, `category`, `value`, `series`, `aggregate`, `title`, `width_mm`, `height_mm` (0.7.0) |

Ограничения-количества: источников ≤ 5, наборов ≤ 20, параметров ≤ 20,
элементов `body` ≤ 60, колонок таблицы ≤ 30, длина SQL ≤ 20 000 символов.

## Пример (используется в приёмке на базе hogwarts)

Файл `tests/samples/students-by-house.spec.json`: параметр `house_code`,
JDBC-источник на `jdbc:postgresql://127.0.0.1:5432/hogwarts`, один набор
с двумя `?`, таблица из четырёх колонок.

## Проверки клиента

- структура: обязательные поля, типы, отсутствие неизвестных ключей;
- имена: ASCII-латиница, без пробелов, до 60 символов; повторы имён запрещены;
- ссылочная целостность: `datasets[].datasource` существует, `params`
  и `table.dataset` ссылаются на описанные сущности;
- соответствие `?` в SQL и списка `params` (число и порядок);
- колонка из `row["..."]` должна быть описана в `datasets[].columns`;
- SQL: `SELECT`/`WITH`, без запрещённых слов (INSERT/UPDATE/DELETE/DDL и т. п.),
  без нескольких операторов;
- при любой ошибке показывается список причин по-русски, файл не создаётся.

## Отклонённые варианты

- **Модель генерирует `.rptdesign` (XML) напрямую** — хрупко, ошибки видны
  только в дизайнере, нет осмысленных сообщений об ошибках.
- **Скрипты BIRT (JavaScript) в отчёте** — исполнение произвольного кода из
  ответа модели: небезопасно.
- **Собственный бинарный формат** — нечитаем глазами, усложняет отладку и
  не даёт пользователю возможности поправить спецификацию вручную.

## Последствия

- словарь выразительных средств вырос обратно совместимо внутри v0:
  стили, вычисляемые колонки и итоги (0.3.0–0.4.0), grid и изображения
  (0.5.0), сортировка и до 5 уровней группировки (0.6.0), list, условия,
  колонтитулы, любая СУБД и диаграммы (0.7.0)
  (`docs/ADR-0002-edit-and-extensions.md`);
- спецификацию можно посмотреть и сохранить (она маскирует пароль как `***`);
- данные из БД и пароль в модель не передаются: модель получает только текст
  ТЗ (в M3 — ещё и метаданные схемы), а строки данных читает движок BIRT при
  рендере (`docs/SECURITY.md`);
- `.rptdesign` собирается штатным API: файл открывается дизайнером BIRT 4.7
  и не требует патчей ни BIRT, ни Eclipse;
- приёмка M2: `tests/samples/students-by-house.spec.json` →
  `releng/m2-selftest.sh` (сборка и рендер PDF/HTML с проверкой данных).
