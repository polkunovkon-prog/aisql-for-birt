#!/usr/bin/env bash
#
# Сборка бандлов плагина «ИИ-конструктор отчётов» (BIRT 4.7) без Maven/Tycho:
# компиляция javac против jar-ов установленного Eclipse 4.7 и упаковка в jar.
#
# Использование:
#   ./releng/build.sh                        # автоопределение /home/pka/eclipse
#   ECLIPSE_HOME=/opt/eclipse ./releng/build.sh
#
# Компилятор по умолчанию — Temurin 8 (Eclipse 4.7 работает только на Java 8,
# и бандлы собираются под Java 8).
#
# Результат:
#   build/                          — классы бандлов
#   dist/bundles/*.jar              — артефакты для установки
#   dist/SHA256SUMS, dist/BUILD-INFO.txt
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ECLIPSE_HOME="${ECLIPSE_HOME:-/home/pka/eclipse}"
PLUGINS_DIR="${PLUGINS_DIR:-$ECLIPSE_HOME/plugins}"
JAVAC="${JAVAC:-/home/pka/opt/jdk8u504-b01/bin/javac}"
JAR="${JAR:-/home/pka/opt/jdk8u504-b01/bin/jar}"

OUT="$ROOT/build"
DIST="$ROOT/dist"

# Версия берётся из манифеста ядра — единый источник истины.
VERSION="$(sed -n 's/^Bundle-Version: //p' "$ROOT/bundles/com.aisql.birt.core/META-INF/MANIFEST.MF" | tr -d '\r')"
[ -n "$VERSION" ] || { echo "ОШИБКА: не удалось определить версию из манифеста" >&2; exit 1; }

if [ ! -d "$PLUGINS_DIR" ]; then
    echo "ОШИБКА: не найден каталог плагинов Eclipse: $PLUGINS_DIR" >&2
    echo "Укажите ECLIPSE_HOME или PLUGINS_DIR." >&2
    exit 1
fi
if [ ! -x "$JAVAC" ]; then
    echo "ОШИБКА: не найден компилятор $JAVAC (ожидается JDK 8)." >&2
    exit 1
fi

echo "== Сборка AI BIRT v$VERSION =="
echo "Eclipse: $ECLIPSE_HOME"
echo "JDK:     $("$JAVAC" -version 2>&1)"

# Classpath: все jar-ы плагинов Eclipse.
CP="$(find "$PLUGINS_DIR" -maxdepth 1 -name '*.jar' | sort | tr '\n' ':')"
DEPS_CP=""

rm -rf "$OUT"
mkdir -p "$OUT" "$DIST"
rm -rf "$DIST/bundles"
mkdir -p "$DIST/bundles"

BUILT_JARS=()

for BUNDLE in com.aisql.birt.core com.aisql.birt.ui; do
    SRC="$ROOT/bundles/$BUNDLE"
    if [ ! -d "$SRC/src" ]; then
        echo "== Пропускаю $BUNDLE (нет исходников) =="
        continue
    fi
    CLASSES="$OUT/$BUNDLE/classes"
    JARROOT="$OUT/$BUNDLE/jarroot"
    mkdir -p "$CLASSES" "$JARROOT"

    echo "== Компиляция $BUNDLE =="
    find "$SRC/src" -name '*.java' | sort > "$OUT/$BUNDLE/sources.txt"
    if [ ! -s "$OUT/$BUNDLE/sources.txt" ]; then
        echo "== Пропускаю $BUNDLE (нет исходников) =="
        continue
    fi
    "$JAVAC" -source 8 -target 8 -encoding UTF-8 -Xlint:-options \
        -cp "$CP$DEPS_CP" -d "$CLASSES" "@$OUT/$BUNDLE/sources.txt"
    DEPS_CP="$DEPS_CP:$CLASSES"

    # Ресурсы из src (properties, png и т.п.) — с сохранением структуры пакетов.
    (cd "$SRC/src" && find . -type f ! -name '*.java' -print0 \
        | while IFS= read -r -d '' f; do
              mkdir -p "$CLASSES/$(dirname "$f")"
              cp "$f" "$CLASSES/$f"
          done)

    # Сборка jar: классы+ресурсы + plugin.xml + OSGI-INF + icons + MANIFEST.
    (cd "$CLASSES" && "$JAR" cf "$OUT/$BUNDLE/tmp.jar" .)
    cp "$OUT/$BUNDLE/tmp.jar" "$JARROOT/bundle.jar"
    (cd "$JARROOT" && "$JAR" xf bundle.jar && rm bundle.jar)
    cp "$SRC/plugin.xml" "$JARROOT/plugin.xml" 2>/dev/null || true
    for d in OSGI-INF icons schema; do
        if [ -d "$SRC/$d" ]; then
            cp -r "$SRC/$d" "$JARROOT/"
        fi
    done

    JARFILE="$OUT/${BUNDLE}_${VERSION}.jar"
    (cd "$JARROOT" && "$JAR" cfm "$JARFILE" "$SRC/META-INF/MANIFEST.MF" .)
    echo "   -> $JARFILE"
    BUILT_JARS+=("$JARFILE")
    cp "$JARFILE" "$DIST/bundles/"
done

if [ ${#BUILT_JARS[@]} -eq 0 ]; then
    echo "ОШИБКА: ничего не собрано." >&2
    exit 1
fi

(cd "$DIST" && sha256sum bundles/*.jar > SHA256SUMS)

# Информация о сборке.
{
    echo "AI BIRT — ИИ-конструктор отчётов для Eclipse BIRT 4.7"
    echo "Версия плагина: $VERSION"
    echo "Дата сборки:    $(date '+%Y-%m-%d %H:%M:%S %z')"
    echo "JDK (javac):    $("$JAVAC" -version 2>&1)"
    echo "Eclipse:        $ECLIPSE_HOME"
    echo "Бандлы:"
    for jar in "$DIST/bundles/"*.jar; do
        echo "  $(basename "$jar")"
    done
    echo
    echo "Контрольные суммы: SHA256SUMS"
} > "$DIST/BUILD-INFO.txt"

echo ""
echo "== Готово =="
ls -l "$DIST/bundles/"
echo "Контрольные суммы: $DIST/SHA256SUMS"
