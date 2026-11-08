#!/usr/bin/env bash
#
# Загружает jar-ы BIRT для сборки/тестов плагина без установленного Eclipse.
# Используется CI (и разработчиком, если Eclipse не установлен).
#
# Использование:
#   ./releng/fetch-birt.sh <целевой-каталог> <4.7|4.25>
#
#   4.7  — бандлы BIRT 4.7 + платформа Eclipse Oxygen из архива
#          simultaneous release (archive.eclipse.org), Java 8;
#   4.25 — полный p2-репозиторий BIRT 4.25 (download.eclipse.org),
#          Java 21.
#
# Каталог получается совместимым с ECLIPSE_HOME: jar-ы в <цель>/plugins.
#
set -euo pipefail

DEST="${1:?укажите целевой каталог}"
VERSION="${2:?укажите версию: 4.7 или 4.25}"

PLUGINS="$DEST/plugins"
mkdir -p "$PLUGINS"

# --- Скачивание с кэшированием (не качаем то, что уже есть) ---
fetch() {
    local url="$1"
    local file="$2"
    if [ -s "$file" ]; then
        return 0
    fi
    echo "  скачиваю $(basename "$file")…"
    curl -sL --retry 5 --retry-delay 3 --max-time 600 -o "$file.part" "$url"
    mv "$file.part" "$file"
}

# =====================================================================
if [ "$VERSION" = "4.7" ]; then
    BASE="https://archive.eclipse.org/releases/oxygen/201706281000"
    # Список идентификаторов бандлов: BIRT 4.7 (модель, движок, эмиттеры,
    # диаграммы, данные) + DTP ODA + EMF + фундамент Eclipse + платформа
    # для компиляции UI-бандла + Rhino/iText/Batik/ICU/Xerces.
    IDS=(
        org.eclipse.birt.report.model
        org.eclipse.birt.core
        org.eclipse.birt.core.script.function
        org.eclipse.birt.report.engine
        org.eclipse.birt.report.engine.emitter.pdf
        org.eclipse.birt.report.engine.emitter.html
        org.eclipse.birt.report.engine.emitter.config
        org.eclipse.birt.report.engine.emitter.config.pdf
        org.eclipse.birt.report.engine.emitter.config.html
        org.eclipse.birt.report.engine.emitter.postscript
        org.eclipse.birt.report.engine.emitter.ppt
        org.eclipse.birt.report.engine.script.javascript
        org.eclipse.birt.chart.device.extension
        org.eclipse.birt.chart.device.svg
        org.eclipse.birt.chart.device.pdf
        org.eclipse.birt.chart.engine
        org.eclipse.birt.chart.engine.extension
        org.eclipse.birt.chart.reportitem
        org.eclipse.birt.report.item.crosstab.core
        org.eclipse.birt.data
        org.eclipse.birt.data.aggregation
        org.eclipse.birt.report.data.adapter
        org.eclipse.birt.report.data.oda.jdbc
        org.eclipse.birt.report.engine.fonts
        org.eclipse.datatools.connectivity.oda
        org.eclipse.datatools.connectivity.oda.design
        org.eclipse.datatools.connectivity.oda.consumer
        org.eclipse.datatools.connectivity.oda.profile
        org.eclipse.emf.common
        org.eclipse.emf.ecore
        org.eclipse.emf.ecore.xmi
        org.eclipse.core.runtime
        org.eclipse.core.jobs
        org.eclipse.core.contenttype
        org.eclipse.equinox.common
        org.eclipse.equinox.registry
        org.eclipse.equinox.preferences
        org.eclipse.equinox.app
        org.eclipse.osgi
        org.mozilla.javascript
        com.lowagie.text
        org.apache.xerces
        org.apache.xml.serializer
        org.apache.batik.css
        org.apache.batik.util
        org.apache.batik.parser
        org.apache.batik.dom
        org.apache.batik.dom.svg
        org.apache.batik.ext.awt
        org.w3c.css.sac
        com.ibm.icu
        org.apache.commons.codec
        org.w3c.sac
        org.w3c.dom.events
        org.w3c.dom.smil
        org.eclipse.jface
        org.eclipse.jface.databinding
        org.eclipse.swt
        org.eclipse.swt.gtk.linux.x86_64
        org.eclipse.ui
        org.eclipse.ui.workbench
        org.eclipse.ui.ide
        org.eclipse.ui.views
        org.eclipse.core.resources
        org.eclipse.core.filesystem
        org.eclipse.core.commands
        org.eclipse.core.expressions
        org.eclipse.equinox.bidi
        org.eclipse.equinox.security
        org.eclipse.e4.core.contexts
        org.eclipse.e4.core.di
        org.eclipse.e4.core.services
        org.eclipse.e4.ui.workbench
        org.eclipse.e4.ui.model.workbench
        org.eclipse.e4.ui.services
        org.eclipse.e4.ui.css.core
        org.eclipse.e4.ui.css.swt
        org.eclipse.e4.ui.widgets
        org.eclipse.e4.ui.di
        org.eclipse.wst.common.emf
        org.apache.derby
        org.eclipse.birt.report.data.oda.sampledb
    )
    echo "== BIRT 4.7 (Eclipse Oxygen): ${#IDS[@]} бандлов =="
    # Индекс версий из artifacts.jar репозитория
    ART="$DEST/artifacts-oxygen.jar"
    fetch "$BASE/artifacts.jar" "$ART"
    for id in "${IDS[@]}"; do
        entry=$(python3 - "$id" "$ART" <<'PYEOF'
import sys, re, zipfile
wanted, art = sys.argv[1], sys.argv[2]
z = zipfile.ZipFile(art)
xml = z.read('artifacts.xml').decode('utf-8', 'replace')
best = None
for m in re.finditer(r"<artifact classifier='osgi.bundle' id='([^']+)' version='([^']+)'", xml):
    aid, ver = m.group(1), m.group(2)
    if aid == wanted and not aid.endswith('.source'):
        if best is None or ver > best:
            best = ver
if best:
    print(f"{wanted}_{best}.jar")
PYEOF
)
        if [ -z "$entry" ]; then
            echo "  нет в репозитории: $id" >&2
            continue
        fi
        fetch "$BASE/plugins/$entry" "$PLUGINS/$entry"
    done
    # Каталогные бандлы (plugin.xml + вложенные jar) распаковываем.
    for id in org.eclipse.birt.report.data.oda.jdbc org.eclipse.birt.report.engine.fonts org.w3c.sac; do
        jarfile=$(ls "$PLUGINS/${id}"_*.jar 2>/dev/null | head -1 || true)
        if [ -n "$jarfile" ]; then
            d="${jarfile%.jar}"
            if [ ! -d "$d" ]; then
                mkdir -p "$d"
                (cd "$d" && unzip -oq "$jarfile" && rm -f "$jarfile")
                echo "  распакован: $id"
            fi
        fi
    done
    echo "Готово: $PLUGINS ($(ls "$PLUGINS" | wc -l) записей)"

# =====================================================================
elif [ "$VERSION" = "4.25" ]; then
    ZIP_URL="https://download.eclipse.org/birt/updates/release/4.25.0/BIRT-Updates-4.25.0.zip"
    ZIP="$DEST/birt-4.25.0.zip"
    echo "== BIRT 4.25 (полный p2-репозиторий) =="
    fetch "$ZIP_URL" "$ZIP"
    if [ ! -d "$PLUGINS" ] || [ "$(ls "$PLUGINS" 2>/dev/null | wc -l)" -lt 100 ]; then
        echo "  распаковываю p2-репозиторий…"
        unzip -oq "$ZIP" -d "$DEST"
    fi
    echo "Готово: $PLUGINS ($(ls "$PLUGINS" | wc -l) записей)"

else
    echo "ОШИБКА: неизвестная версия $VERSION (поддерживаются 4.7 и 4.25)" >&2
    exit 2
fi
