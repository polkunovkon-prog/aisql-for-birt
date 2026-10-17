#!/usr/bin/env bash
#
# Удаление плагина «ИИ-конструктор отчётов» из Eclipse 4.7.
#
# Запускается обычным пользователем. Удаляет бандлы, их записи из
# bundles.info и сбрасывает кэши Equinox.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ECLIPSE_HOME="${ECLIPSE_HOME:-/home/pka/eclipse}"
LAUNCHER="$ECLIPSE_HOME/eclipse"
CONFIG_DIR="$ECLIPSE_HOME/configuration"
BUNDLES_INFO="$CONFIG_DIR/org.eclipse.equinox.simpleconfigurator/bundles.info"
INSTALL_DIR="$ECLIPSE_HOME/aisql"

die() { echo "ОШИБКА: $*" >&2; exit 1; }
info() { echo; echo "== $*"; }

[ -f "$BUNDLES_INFO" ] || die "не найден $BUNDLES_INFO"

if pgrep -u "$(id -u)" -x eclipse >/dev/null 2>&1; then
    die "Eclipse запущен — закройте его и повторите удаление"
fi

info "1/3 Удаление бандлов"
if [ -d "$INSTALL_DIR" ]; then
    rm -rf "$INSTALL_DIR"
    echo "   удалён каталог $INSTALL_DIR"
else
    echo "   каталог $INSTALL_DIR отсутствует — пропуск"
fi

info "2/3 Удаление записей из bundles.info"
MARKER_RE='^com\.aisql\.birt\.'
if grep -q "$MARKER_RE" "$BUNDLES_INFO"; then
    cp -p "$BUNDLES_INFO" "$BUNDLES_INFO.aisql-backup-$(date +%Y%m%d-%H%M%S)"
    sed -i -e "/$MARKER_RE/d" "$BUNDLES_INFO"
    echo "   записи плагина удалены (копия прежнего файла — …aisql-backup-*)"
else
    echo "   записей плагина нет — пропуск"
fi

info "3/3 Сброс кэшей Equinox"
for rel in org.eclipse.osgi org.eclipse.core.runtime; do
    if [ -d "$CONFIG_DIR/$rel" ]; then
        rm -rf "$CONFIG_DIR/$rel"
        echo "   кэш сброшен: configuration/$rel"
    fi
done

info "Готово"
echo "Пользовательские данные не тронуты: настройки и журнал остаются в ~/.ai-birt/."
