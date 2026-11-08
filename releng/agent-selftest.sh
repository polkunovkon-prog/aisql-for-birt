#!/usr/bin/env bash
#
# Приёмка агентного цикла (1.0.0): «инструменты → .rptdesign → PDF/HTML»
# на встроенной БД Derby (classicmodels) — без Eclipse, без внешней БД,
# без ИИ-сервера (инструменты вызываются напрямую, как это сделала бы модель).
#
# Использование:
#   ./releng/agent-selftest.sh            # BIRT из ECLIPSE_HOME (по умолчанию /home/pka/eclipse)
#   ECLIPSE_HOME=/path/to/birt425 JAVA=/path/to/jdk21/bin/java \
#       DERBY_JAR=/path/to/BirtSample.jar ./releng/agent-selftest.sh
#
# Требования:
#   - собранные бандлы (releng/build.sh);
#   - jar-ы BIRT (ECLIPSE_HOME/plugins);
#   - BirtSample.jar — база classicmodels из бандла
#     org.eclipse.birt.report.data.oda.sampledb (db/BirtSample.jar);
#     если не указан, скрипт пробует распаковать из установленного бандла.
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ECLIPSE_HOME="${ECLIPSE_HOME:-/home/pka/eclipse}"
JAVA="${JAVA:-/home/pka/opt/jdk8u504-b01/bin/java}"
JAVAC="${JAVAC:-$(dirname "$JAVA")/javac}"
JAR_BIN="${JAR:-$(dirname "$JAVA")/jar}"
export JAR="$JAR_BIN"
DERBY_JAR="${DERBY_JAR:-}"

"$ROOT/releng/build.sh" > /dev/null

# База classicmodels: из аргумента или из бандла sampledb
if [ -z "$DERBY_JAR" ]; then
    for cand in "$ECLIPSE_HOME"/plugins/org.eclipse.birt.report.data.oda.sampledb_*; do
        if [ -f "$cand/db/BirtSample.jar" ]; then
            DERBY_JAR="$ROOT/build/birt-sampledb/BirtSample.jar"
            mkdir -p "$(dirname "$DERBY_JAR")"
            cp "$cand/db/BirtSample.jar" "$DERBY_JAR"
            break
        fi
    done
fi
if [ -z "$DERBY_JAR" ] || [ ! -f "$DERBY_JAR" ]; then
    echo "ОШИБКА: не найден BirtSample.jar (база classicmodels)." >&2
    echo "Укажите DERBY_JAR=/путь/к/BirtSample.jar или установите бандл" >&2
    echo "org.eclipse.birt.report.data.oda.sampledb в $ECLIPSE_HOME/plugins." >&2
    exit 2
fi

BIRT_CP="$("$ROOT/releng/birt-classpath.sh")"
CP="$ROOT/build/com.aisql.birt.core/classes:$BIRT_CP"

echo "== Приёмка агентного цикла: инструменты → отчёт → рендер =="
echo "Eclipse/BIRT: $ECLIPSE_HOME"
echo "Java:         $("$JAVA" -version 2>&1 | head -1)"
echo "База:         $DERBY_JAR"
echo

TEST_OUT="$ROOT/build/agent-selftest"
rm -rf "$TEST_OUT"
mkdir -p "$TEST_OUT"
"$JAVAC" -source 8 -target 8 -encoding UTF-8 -Xlint:-options \
    -cp "$CP" -d "$TEST_OUT" "$ROOT/tests/src/com/aisql/birt/tests/AgentSelfTest.java"

"$JAVA" -cp "$TEST_OUT:$CP" com.aisql.birt.tests.AgentSelfTest "$DERBY_JAR"
