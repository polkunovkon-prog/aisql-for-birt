#!/usr/bin/env bash
#
# Печатает classpath для standalone-запуска компонентов BIRT 4.7
# (сборка отчёта и рендер PDF/HTML без запуска Eclipse).
#
# Использование:
#   CP="bundles/…:$(./releng/birt-classpath.sh)"
#
# Каталог Eclipse определяется переменной ECLIPSE_HOME (по умолчанию
# /home/pka/eclipse). В комплект classpath входит современный драйвер
# PostgreSQL из состава плагина (lib/postgresql-42.7.3.jar).
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ECLIPSE_HOME="${ECLIPSE_HOME:-/home/pka/eclipse}"
P="$ECLIPSE_HOME/plugins"

if [ ! -d "$P" ]; then
    echo "ОШИБКА: не найден каталог плагинов Eclipse: $P" >&2
    exit 1
fi

# Берём самую новую версию для каждого шаблона (sort -V).
pick() {
    local pattern="$1"
    local files
    files=$(find "$P" -maxdepth 1 -name "$pattern" 2>/dev/null | sort -V || true)
    if [ -n "$files" ]; then
        echo "$files" | tail -1
    fi
}

JARS=(
    # BIRT: дизайн-модель, движок отчётов, эмиттеры
    'org.eclipse.birt.report.model_*.jar'
    'org.eclipse.birt.core_*.jar'
    'org.eclipse.birt.core.script.function_*.jar'
    'org.eclipse.birt.report.engine_*.jar'
    'org.eclipse.birt.report.engine.emitter.pdf_*.jar'
    'org.eclipse.birt.report.engine.emitter.html_*.jar'
    'org.eclipse.birt.report.engine.emitter.config_*.jar'
    'org.eclipse.birt.report.engine.emitter.config.pdf_*.jar'
    'org.eclipse.birt.report.engine.emitter.config.html_*.jar'
    'org.eclipse.birt.report.engine.script.javascript_*.jar'
    # BIRT: данные и адаптер
    'org.eclipse.birt.data_*.jar'
    'org.eclipse.birt.data.aggregation_*.jar'
    'org.eclipse.birt.report.data.adapter_*.jar'
    # DTP ODA (мост ODA ↔ JDBC-ODA)
    'org.eclipse.datatools.connectivity.oda_*.jar'
    'org.eclipse.datatools.connectivity.oda.design_*.jar'
    'org.eclipse.datatools.connectivity.oda.consumer_*.jar'
    'org.eclipse.datatools.connectivity.oda.profile_*.jar'
    # Eclipse-фундамент (нужен загрузчику BIRT-платформы)
    'org.eclipse.core.runtime_*.jar'
    'org.eclipse.core.jobs_*.jar'
    'org.eclipse.core.contenttype_*.jar'
    'org.eclipse.equinox.common_*.jar'
    'org.eclipse.equinox.registry_*.jar'
    'org.eclipse.equinox.preferences_*.jar'
    'org.eclipse.equinox.app_*.jar'
    'org.eclipse.osgi_*.jar'
    # Скрипты и шрифты/Batik (стили, PDF, HTML)
    'org.mozilla.javascript_*.jar'
    'com.lowagie.text_*.jar'
    'org.apache.xerces_*.jar'
    'org.apache.xml.serializer_*.jar'
    'org.apache.batik.css_*.jar'
    'org.apache.batik.util_*.jar'
    'org.apache.batik.parser_*.jar'
    'org.apache.batik.dom_*.jar'
    'org.apache.batik.dom.svg_*.jar'
    'org.apache.batik.ext.awt_*.jar'
    'org.w3c.css.sac_*.jar'
    'com.ibm.icu_*.jar'
    'org.apache.commons.codec_*.jar'
)

CP=""
for pattern in "${JARS[@]}"; do
    jar="$(pick "$pattern")"
    if [ -n "$jar" ]; then
        CP="$CP$jar:"
    fi
done

# Бандлы-каталоги (содержат plugin.xml и вложенные jar-ы).
for d in \
    "$P"/org.eclipse.birt.report.data.oda.jdbc_* \
    "$P"/org.eclipse.birt.report.engine.fonts_* \
    "$P"/org.w3c.sac_*; do
    if [ -d "$d" ]; then
        CP="$CP$d:"
    fi
done
ODA_JDBC_JAR="$(ls "$P"/org.eclipse.birt.report.data.oda.jdbc_*/oda-jdbc.jar 2>/dev/null | head -1 || true)"
if [ -n "$ODA_JDBC_JAR" ]; then
    CP="$CP$ODA_JDBC_JAR:"
fi

# Современный драйвер PostgreSQL из состава плагина.
PG_JAR="$ROOT/bundles/com.aisql.birt.core/lib/postgresql-42.7.3.jar"
if [ -f "$PG_JAR" ]; then
    CP="$CP$PG_JAR:"
fi

echo -n "$CP"
