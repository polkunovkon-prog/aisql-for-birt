#!/usr/bin/env bash
#
# Приёмка M2 (standalone, без Eclipse): «спецификация → .rptdesign → PDF/HTML».
#
# 1) запускает ReportSelfTest на JDK 8 с classpath из releng/birt-classpath.sh;
# 2) собирает отчёты из спецификаций (по умолчанию три: базовая; с группировкой,
#    стилями и вычисляемой колонкой; с условиями отбора/оформления и
#    колонтитулами);
# 3) рендерит PDF и HTML штатным движком BIRT;
# 4) проверяет, что в HTML и PDF есть настоящие данные из базы;
# 5) читает схему БД (только метаданные) инструментом DbSchemaTool.
#
# Пароль базы берётся из переменной AIBIRT_TEST_DB_PASSWORD (в файлы не пишется).
#
# Использование:
#   AIBIRT_TEST_DB_PASSWORD='...' ./releng/m2-selftest.sh [каталог-вывода]
#
# Переменные: SPEC (одна спецификация вместо набора), EXPECT (подстроки данных),
#             JAVA, ECLIPSE_HOME, DB_URL, DB_USER.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="${JAVA:-/home/pka/opt/jdk8u504-b01/bin/java}"
OUT="${1:-/tmp/opencode/aibirt-m2-selftest}"
EXPECT="${EXPECT:-Гриффиндор}"
DB_URL="${DB_URL:-jdbc:postgresql://127.0.0.1:5432/hogwarts}"
DB_USER="${DB_USER:-agent_user}"

if [ -n "${SPEC:-}" ]; then
    SPECS=("$SPEC")
else
    SPECS=(
        "$ROOT/tests/samples/students-by-house.spec.json"
        "$ROOT/tests/samples/students-by-house-groups.spec.json"
        "$ROOT/tests/samples/students-conditions.spec.json"
        "$ROOT/tests/samples/students-sort-groups.spec.json"
    )
fi

if [ -z "${AIBIRT_TEST_DB_PASSWORD:-}" ]; then
    echo "ОШИБКА: не задан пароль базы (переменная AIBIRT_TEST_DB_PASSWORD)" >&2
    exit 2
fi

echo "== Приёмка M2: спецификация → .rptdesign → рендер (без Eclipse) =="
"$ROOT/releng/build.sh" >/dev/null 2>&1
CP="$ROOT/build/com.aisql.birt.core/classes:$("$ROOT/releng/birt-classpath.sh")"
rm -rf "$OUT"
mkdir -p "$OUT"

check_spec() {
    local spec="$1"
    local name code rpt base html pdf rows token pages pdftxt
    name="$(basename "$spec")"
    echo
    echo "--- Спецификация: $name"
    if [ ! -f "$spec" ]; then
        echo "ОШИБКА: нет файла спецификации: $spec" >&2
        exit 2
    fi
    set +e
    AIBIRT_TEST_DB_PASSWORD="$AIBIRT_TEST_DB_PASSWORD" "$JAVA" \
        -cp "$CP" \
        com.aisql.birt.core.report.ReportSelfTest "$spec" "$OUT" \
        | tee "$OUT/$name.log"
    code=${PIPESTATUS[0]}
    set -e
    if [ "$code" -ne 0 ]; then
        echo "ОШИБКА: ReportSelfTest завершился с кодом $code ($name)" >&2
        exit 1
    fi

    rpt="$(grep -o 'BUILD OK file=[^ ]*' "$OUT/$name.log" | head -1 | cut -d= -f2)"
    base="$(basename "$rpt")"
    base="${base%.rptdesign}"
    html="$OUT/$base.html"
    pdf="$OUT/$base.pdf"
    if [ ! -s "$html" ]; then
        echo "ОШИБКА: нет HTML-рендера $html" >&2
        exit 1
    fi
    if [ ! -s "$pdf" ]; then
        echo "ОШИБКА: нет PDF-рендера $pdf" >&2
        exit 1
    fi

    rows="$(grep -c '<tr valign="top" align="left">' "$html" || true)"
    echo "Строк данных в HTML: $rows"
    if [ "$rows" -lt 1 ]; then
        echo "ОШИБКА: в HTML нет строк данных (запрос не вернул записи?) ($name)" >&2
        exit 1
    fi
    if grep -q 'Invalid bound column name' "$html"; then
        echo "ОШИБКА: в HTML есть ошибки привязок колонок ($name)" >&2
        exit 1
    fi

    # Спецификация с условиями: подсветка ячеек, отбор строк, колонтитулы.
    case "$name" in
    students-conditions.spec.json)
        if grep -q 'rgb(255, 213, 213)' "$html"; then
            echo "Условия: условное оформление ячеек найдено в HTML"
        else
            echo "ОШИБКА: в HTML нет условного оформления (rgb(255,213,213)) ($name)" >&2
            exit 1
        fi
        if grep -q 'Слизерин' "$html"; then
            echo "ОШИБКА: фильтр таблицы не отсеял строки (найден «Слизерин») ($name)" >&2
            exit 1
        fi
        if grep -q 'Дата печати' "$html"; then
            echo "Колонтитулы: нижний колонтитул с датой найден в HTML"
        else
            echo "ОШИБКА: в HTML нет нижнего колонтитула ($name)" >&2
            exit 1
        fi
        ;;
    esac

    for token in $EXPECT; do
        if grep -q "$token" "$html"; then
            echo "Данные в HTML: найдено «$token»"
        else
            echo "ОШИБКА: в HTML нет «$token» ($name)" >&2
            exit 1
        fi
    done

    if command -v pdfinfo >/dev/null 2>&1; then
        pages="$(pdfinfo "$pdf" | awk '/^Pages:/ {print $2}')"
        echo "Страниц в PDF: ${pages:-?}"
        if [ -z "${pages:-}" ] || [ "$pages" -lt 1 ]; then
            echo "ОШИБКА: PDF без страниц ($name)" >&2
            exit 1
        fi
    fi

    # Текст в PDF: кириллица должна извлекаться — значит, в PDF встроен
    # настоящий шрифт с кириллицей (DejaVu Sans), а не базовый невстраиваемый
    # Times-Roman, в котором русский текст теряется.
    if command -v pdftotext >/dev/null 2>&1; then
        pdftxt="$(pdftotext -layout "$pdf" - 2>/dev/null || true)"
        for token in $EXPECT; do
            if printf '%s' "$pdftxt" | grep -q "$token"; then
                echo "Данные в PDF: найдено «$token»"
            else
                echo "ОШИБКА: в PDF нет «$token» (шрифт не встроен, кириллица теряется?) ($name)" >&2
                exit 1
            fi
        done
        if command -v pdffonts >/dev/null 2>&1; then
            if pdffonts "$pdf" | tail -n +3 | grep -q 'yes'; then
                echo "PDF: есть встроенные шрифты"
            else
                echo "ПРЕДУПРЕЖДЕНИЕ: в PDF нет встроенных шрифтов" >&2
            fi
        fi
    fi
}

for spec in "${SPECS[@]}"; do
    check_spec "$spec"
done

# Схема БД: в текст уходят только имена таблиц и колонок — без данных.
echo
echo "--- Схема БД (метаданные)"
AIBIRT_TEST_DB_PASSWORD="$AIBIRT_TEST_DB_PASSWORD" "$JAVA" \
    -cp "$CP" com.aisql.birt.core.jdbc.DbSchemaTool "$DB_URL" "$DB_USER" \
    | tee "$OUT/schema.txt"
if grep -q 'students' "$OUT/schema.txt"; then
    echo "Схема: таблица students найдена"
else
    echo "ОШИБКА: в тексте схемы нет таблицы students" >&2
    exit 1
fi
if grep -qiwE 'select|insert|update|delete|drop' "$OUT/schema.txt"; then
    echo "ОШИБКА: в тексте схемы есть SQL-запросы (должны быть только метаданные)" >&2
    exit 1
fi

echo
echo "== Приёмка M2 пройдена =="
