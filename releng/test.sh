#!/usr/bin/env bash
#
# Офлайн-тесты плагина «ИИ-конструктор отчётов» (BIRT):
#   - сборка бандлов (releng/build.sh);
#   - компиляция и запуск тестов на JDK 8.
#
# Тесты не обращаются к настоящему ИИ-серверу: LlmClient проверяется
# на локальном заглушечном HTTP-сервере (com.sun.net.httpserver).
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVAC="${JAVAC:-/home/pka/opt/jdk8u504-b01/bin/javac}"
JAVA="${JAVA:-/home/pka/opt/jdk8u504-b01/bin/java}"

echo "== Сборка бандлов =="
"$ROOT/releng/build.sh" | tail -5

# Classpath: классы ядра + библиотеки BIRT (модель, ICU и т.д.) для
# тестов сборки отчёта. Набор печатает releng/birt-classpath.sh.
BIRT_CP="$("$ROOT/releng/birt-classpath.sh")"
CP="$ROOT/build/com.aisql.birt.core/classes:$BIRT_CP"
TEST_OUT="$ROOT/build/tests"
rm -rf "$TEST_OUT"
mkdir -p "$TEST_OUT"
find "$ROOT/tests/src" -name '*.java' | sort > "$ROOT/build/test-sources.txt"

echo
echo "== Компиляция тестов ($("$JAVAC" -version 2>&1)) =="
"$JAVAC" -source 8 -target 8 -encoding UTF-8 -Xlint:-options \
    -cp "$CP" -d "$TEST_OUT" "@$ROOT/build/test-sources.txt"

echo "== Запуск тестов =="
"$JAVA" -cp "$TEST_OUT:$CP" com.aisql.birt.tests.AllTests
