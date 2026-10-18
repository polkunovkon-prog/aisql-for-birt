#!/usr/bin/env bash
#
# Приёмка M2 (standalone, без Eclipse): «спецификация → .rptdesign → PDF/HTML».
#
# 1) запускает ReportSelfTest на JDK 8 с classpath из releng/birt-classpath.sh;
# 2) собирает отчёт из спецификации (по умолчанию — тестовая база hogwarts);
# 3) рендерит PDF и HTML штатным движком BIRT;
# 4) проверяет, что в HTML есть настоящие данные из базы.
#
# Пароль базы берётся из переменной AIBIRT_TEST_DB_PASSWORD (в файлы не пишется).
#
# Использование:
#   AIBIRT_TEST_DB_PASSWORD='...' ./releng/m2-selftest.sh [каталог-вывода]
#
# Переменные: SPEC (спецификация), EXPECT (подстроки данных), JAVA, ECLIPSE_HOME.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="${JAVA:-/home/pka/opt/jdk8u504-b01/bin/java}"
SPEC="${SPEC:-$ROOT/tests/samples/students-by-house.spec.json}"
OUT="${1:-/tmp/opencode/aibirt-m2-selftest}"
EXPECT="${EXPECT:-Гриффиндор}"

if [ -z "${AIBIRT_TEST_DB_PASSWORD:-}" ]; then
    echo "ОШИБКА: не задан пароль базы (переменная AIBIRT_TEST_DB_PASSWORD)" >&2
    exit 2
fi
if [ ! -f "$SPEC" ]; then
    echo "ОШИБКА: нет файла спецификации: $SPEC" >&2
    exit 2
fi

echo "== Приёмка M2: спецификация → .rptdesign → рендер (без Eclipse) =="
"$ROOT/releng/build.sh" >/dev/null 2>&1
CP="$("$ROOT/releng/birt-classpath.sh")"
rm -rf "$OUT"
mkdir -p "$OUT"
echo "Спецификация: $SPEC"
echo "Каталог:      $OUT"
echo

set +e
AIBIRT_TEST_DB_PASSWORD="$AIBIRT_TEST_DB_PASSWORD" "$JAVA" \
    -cp "$ROOT/build/com.aisql.birt.core/classes:$CP" \
    com.aisql.birt.core.report.ReportSelfTest "$SPEC" "$OUT" | tee "$OUT/selftest.log"
CODE=${PIPESTATUS[0]}
set -e
if [ "$CODE" -ne 0 ]; then
    echo "ОШИБКА: ReportSelfTest завершился с кодом $CODE" >&2
    exit 1
fi

RPT="$(grep -o 'BUILD OK file=[^ ]*' "$OUT/selftest.log" | head -1 | cut -d= -f2)"
NAME="${RPT%.rptdesign}"
HTML="$OUT/$NAME.html"
PDF="$OUT/$NAME.pdf"
if [ ! -s "$HTML" ]; then
    echo "ОШИБКА: нет HTML-рендера $HTML" >&2
    exit 1
fi
if [ ! -s "$PDF" ]; then
    echo "ОШИБКА: нет PDF-рендера $PDF" >&2
    exit 1
fi

ROWS="$(grep -c '<tr valign="top" align="left">' "$HTML" || true)"
echo "Строк данных в HTML: $ROWS"
if [ "$ROWS" -lt 1 ]; then
    echo "ОШИБКА: в HTML нет строк данных (запрос не вернул записи?)" >&2
    exit 1
fi

for token in $EXPECT; do
    if grep -q "$token" "$HTML"; then
        echo "Данные в HTML: найдено «$token»"
    else
        echo "ОШИБКА: в HTML нет «$token»" >&2
        exit 1
    fi
done

if command -v pdfinfo >/dev/null 2>&1; then
    PAGES="$(pdfinfo "$PDF" | awk '/^Pages:/ {print $2}')"
    echo "Страниц в PDF: ${PAGES:-?}"
    if [ -z "${PAGES:-}" ] || [ "$PAGES" -lt 1 ]; then
        echo "ОШИБКА: PDF без страниц" >&2
        exit 1
    fi
fi

echo
echo "== Приёмка M2 пройдена =="
