#!/usr/bin/env bash
#
# Печатает classpath для standalone-запуска компонентов BIRT
# (сборка отчёта и рендер PDF/HTML без запуска Eclipse).
#
# Использование:
#   CP="bundles/…:$(./releng/birt-classpath.sh)"
#
# Каталог Eclipse определяется переменной ECLIPSE_HOME (по умолчанию
# $HOME/eclipse). В classpath входит ВСЁ содержимое каталога plugins
# (wildcard «dir/*» — сам jar-файл каталога разворачивает, это штатная
# возможность java/javac; снимает ограничение на длину одного аргумента
# при больших установках, например BIRT 4.25 с 1600+ бандлами), плюс:
#   - вложенный oda-jdbc.jar каталогного бандла ODA (BIRT 4.7);
#   - lib/*.jar, извлечённые из бандла движка (Tidy и др., BIRT 4.7);
#   - современный драйвер PostgreSQL из состава плагина.
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ECLIPSE_HOME="${ECLIPSE_HOME:-$HOME/eclipse}"
P="$ECLIPSE_HOME/plugins"

if [ ! -d "$P" ]; then
    echo "ОШИБКА: не найден каталог плагинов Eclipse: $P" >&2
    exit 1
fi

CP="$P/*"

# Каталогные бандлы (BIRT 4.7): их plugin.xml регистрирует ODA-расширения,
# а классы лежат во вложенных jar. В wildcard каталоги не попадают — добавляем
# явно. В BIRT 4.9+ это обычные jar-бандлы и wildcard их уже покрывает.
for d in \
    "$P"/org.eclipse.birt.report.data.oda.jdbc_* \
    "$P"/org.eclipse.birt.report.engine.fonts_* \
    "$P"/org.w3c.sac_*; do
    if [ -d "$d" ]; then
        CP="$CP:$d"
    fi
done

# Вложенный oda-jdbc.jar каталогного бандла ODA (BIRT 4.7).
ODA_DIR="$(ls -d "$P"/org.eclipse.birt.report.data.oda.jdbc_* 2>/dev/null | head -1 || true)"
if [ -n "$ODA_DIR" ] && [ -d "$ODA_DIR" ] && [ -f "$ODA_DIR/oda-jdbc.jar" ]; then
    CP="$CP:$ODA_DIR/oda-jdbc.jar"
fi

# Вложенные библиотеки движка BIRT (lib/*.jar внутри бандла): в Eclipse они
# видны через Bundle-ClassPath, в standalone-запуске их нужно распаковать.
# Tidy.jar нужен для HTML-элементов текста (<value-of> в колонтитулах и т.п.).
ENGINE_JAR="$(find "$P" -maxdepth 1 -name 'org.eclipse.birt.report.engine_*.jar' 2>/dev/null | sort -V | tail -1 || true)"
EMBED_DIR="$ROOT/build/birt-embed"
if [ -n "$ENGINE_JAR" ] && [ -f "$ENGINE_JAR" ]; then
    if [ ! -f "$EMBED_DIR/.done" ]; then
        mkdir -p "$EMBED_DIR"
        unzip -o -j "$ENGINE_JAR" 'lib/*.jar' -d "$EMBED_DIR" >/dev/null 2>&1 || true
        touch "$EMBED_DIR/.done"
    fi
    for j in "$EMBED_DIR"/*.jar; do
        if [ -f "$j" ]; then
            CP="$CP:$j"
        fi
    done
fi

# Современный драйвер PostgreSQL из состава плагина.
PG_JAR="$ROOT/bundles/com.aisql.birt.core/lib/postgresql-42.7.3.jar"
if [ -f "$PG_JAR" ]; then
    CP="$CP:$PG_JAR"
fi

echo -n "$CP"
