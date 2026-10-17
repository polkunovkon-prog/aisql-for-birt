#!/usr/bin/env bash
#
# Установка плагина «ИИ-конструктор отчётов» в Eclipse 4.7 (с BIRT 4.7).
#
# Запускается ОБЫЧНЫМ пользователем — права root не нужны, Eclipse стоит
# в домашнем каталоге. Что делает скрипт:
#   1) проверяет окружение и что Eclipse закрыт (работающий экземпляр
#      перезаписал бы кэши при выходе);
#   2) копирует собранные бандлы в <Eclipse>/aisql/plugins;
#   3) регистрирует их в configuration/…/bundles.info: записи плагина
#      перезаписываются, чужие строки не трогаются (перед правкой — копия);
#   4) сбрасывает кэши Equinox (иначе переустановка другой версии
#      подхватила бы старые данные бандлов);
#   5) проверяет загрузку плагина headless-запуском под xvfb: ищет в журнале
#      рабочей области строку «AI BIRT UI plugin started».
#
# Переменные: ECLIPSE_HOME (по умолчанию /home/pka/eclipse).
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ECLIPSE_HOME="${ECLIPSE_HOME:-/home/pka/eclipse}"
LAUNCHER="$ECLIPSE_HOME/eclipse"
CONFIG_DIR="$ECLIPSE_HOME/configuration"
BUNDLES_INFO="$CONFIG_DIR/org.eclipse.equinox.simpleconfigurator/bundles.info"
INSTALL_DIR="$ECLIPSE_HOME/aisql"
DIST_BUNDLES="$ROOT_DIR/dist/bundles"
MARKER_TEXT="AI BIRT UI plugin started"
VERIFY_DIR="/tmp/opencode/aibirt-verify"
VERIFY_WS="$VERIFY_DIR/workspace"
VERIFY_LOG="$VERIFY_DIR/stdout.log"
ECLIPSE_PID=""

die() { echo "ОШИБКА: $*" >&2; exit 1; }
info() { echo; echo "== $*"; }

[ -x "$LAUNCHER" ] || die "не найден запускаемый файл Eclipse: $LAUNCHER (задайте ECLIPSE_HOME)"
[ -f "$BUNDLES_INFO" ] || die "не найден $BUNDLES_INFO"
[ -w "$ECLIPSE_HOME" ] || die "нет прав на запись в $ECLIPSE_HOME"

JARS=("$DIST_BUNDLES"/com.aisql.birt.*.jar)
[ -e "${JARS[0]}" ] || die "не найдены собранные бандлы в $DIST_BUNDLES (сначала выполните ./releng/build.sh)"

# Имя процесса у Eclipse-лаунчера — «eclipse» (comm), поэтому проверяем
# точно по имени, а не grep-ом по командной строке: так не бывает ложных
# срабатываний на собственную строку запуска скрипта.
if pgrep -u "$(id -u)" -x eclipse >/dev/null 2>&1; then
    die "Eclipse запущен — закройте его и повторите установку (работающий экземпляр перезапишет кэши при выходе)"
fi

# ---------------------------------------------------------------------------
info "1/4 Установка бандлов"
mkdir -p "$INSTALL_DIR/plugins"
rm -f "$INSTALL_DIR/plugins"/com.aisql.birt.*.jar
cp -f "${JARS[@]}" "$INSTALL_DIR/plugins/"
chmod 644 "$INSTALL_DIR/plugins"/com.aisql.birt.*.jar
ls -l "$INSTALL_DIR/plugins"

# ---------------------------------------------------------------------------
info "2/4 Регистрация бандлов (bundles.info)"
# Прежние записи плагина удаляются: при смене версии они указывали бы на
# старые jar-ы, и Equinox загрузил бы прежний бандл. Строки других бандлов
# не трогаются. Запуск UI-бандла — «started=true» (нужен для маркера
# в журнале и для немедленного появления панели чата).
MARKER_RE='^com\.aisql\.birt\.'
new_entries="$(mktemp)"
trap 'rm -f "$new_entries"' EXIT
for jar in "$INSTALL_DIR/plugins"/com.aisql.birt.*.jar; do
    name="$(basename "$jar")"
    sym="${name%%_[0-9]*}"
    ver="$(echo "$name" | sed -E 's/^.*_([0-9][^_]*[0-9])\.jar$/\1/')"
    started="false"
    [ "$sym" = "com.aisql.birt.ui" ] && started="true"
    printf '%s,%s,file:%s,4,%s\n' "$sym" "$ver" "$jar" "$started" >> "$new_entries"
done

if diff -q <(grep "$MARKER_RE" "$BUNDLES_INFO" || true) "$new_entries" >/dev/null 2>&1; then
    echo "   записи актуальны — изменения не требуются"
else
    cp -p "$BUNDLES_INFO" "$BUNDLES_INFO.aisql-backup-$(date +%Y%m%d-%H%M%S)"
    echo "   копия прежнего bundles.info сохранена (…aisql-backup-*)"
    sed -i -e "/$MARKER_RE/d" "$BUNDLES_INFO"
    while IFS= read -r entry; do
        printf '%s\n' "$entry" >> "$BUNDLES_INFO"
        echo "   + ${entry%%,*}"
    done < "$new_entries"
fi
grep -q '^com\.aisql\.birt\.ui,' "$BUNDLES_INFO" || die "не удалось зарегистрировать бандлы в bundles.info"

# ---------------------------------------------------------------------------
info "3/4 Сброс кэшей Equinox"
# Equinox кэширует манифесты бандлов (configuration/org.eclipse.osgi) и модель
# реестра расширений (configuration/org.eclipse.core.runtime). При совпадении
# пути и версии старые данные переиспользуются, даже если jar заменён, —
# каталоги служебные, пересоздаются при запуске, удаляем всегда.
for rel in org.eclipse.osgi org.eclipse.core.runtime; do
    if [ -d "$CONFIG_DIR/$rel" ]; then
        rm -rf "$CONFIG_DIR/$rel"
        echo "   кэш сброшен: configuration/$rel"
    fi
done
# Каскадная конфигурация (~/.eclipse/…) существует, только если каталог
# установки был недоступен для записи; подчищаем на всякий случай.
shopt -s nullglob
for cfgdir in "$HOME"/.eclipse/*/*/configuration; do
    [ -d "$cfgdir" ] || continue
    for rel in org.eclipse.osgi org.eclipse.core.runtime; do
        if [ -d "$cfgdir/$rel" ]; then
            rm -rf "$cfgdir/$rel"
            echo "   кэш пользователя сброшен: $cfgdir/$rel"
        fi
    done
done
shopt -u nullglob

# ---------------------------------------------------------------------------
info "4/4 Проверка загрузки плагина (headless-запуск под xvfb)"
command -v xvfb-run >/dev/null 2>&1 || die "не найден xvfb-run (sudo apt install xvfb)"

stop_eclipse() {
    [ -n "$ECLIPSE_PID" ] || return 0
    kill -TERM -- "-$ECLIPSE_PID" 2>/dev/null || true
    sleep 3
    kill -KILL -- "-$ECLIPSE_PID" 2>/dev/null || true
    wait "$ECLIPSE_PID" 2>/dev/null || true
    ECLIPSE_PID=""
}

rm -rf "$VERIFY_DIR"
mkdir -p "$VERIFY_WS"
echo "   запуск Eclipse (до 180 с)…"
setsid xvfb-run -a "$LAUNCHER" -data "$VERIFY_WS" -nosplash -consoleLog > "$VERIFY_LOG" 2>&1 &
ECLIPSE_PID=$!

found=0
waited=0
while [ "$waited" -lt 180 ]; do
    if grep -rqs "$MARKER_TEXT" "$VERIFY_WS/.metadata" 2>/dev/null; then
        found=1
        break
    fi
    if ! kill -0 "$ECLIPSE_PID" 2>/dev/null; then
        break
    fi
    sleep 3
    waited=$((waited + 3))
done
stop_eclipse

if [ "$found" = "1" ]; then
    echo "   плагин загружен: найдено «$MARKER_TEXT»"
else
    echo "   ПРЕДУПРЕЖДЕНИЕ: маркер «$MARKER_TEXT» не найден."
    echo "   Это не обязательно ошибка (запуск в xvfb зависит от окружения),"
    echo "   но стоит проверить журнал. Последние строки $VERIFY_WS/.metadata/.log:"
    LOGFILE="$(find "$VERIFY_WS/.metadata" -name '*.log' 2>/dev/null | head -1 || true)"
    if [ -n "${LOGFILE:-}" ] && [ -f "$LOGFILE" ]; then
        tail -20 "$LOGFILE" || true
    else
        echo "   (журнал рабочей области не найден; вывод запуска: $VERIFY_LOG)"
    fi
fi

# ---------------------------------------------------------------------------
info "Готово"
cat <<'EOF'
Плагин установлен. Что дальше:

  1. Запустите Eclipse (/home/pka/eclipse/eclipse).
  2. Откройте панель: Window → Show View → ИИ для BIRT → «ИИ-конструктор отчётов»
     (в перспективе BIRT «Отчёт» панель добавляется рядом с Outline).
  3. Настройки подключения: Window → Preferences → «ИИ-конструктор отчётов (BIRT)»
     (файл настроек ~/.ai-birt/ai-birt.properties, журнал ~/.ai-birt/ai-birt-audit.jsonl).

Удаление: ./releng/uninstall.sh
EOF
