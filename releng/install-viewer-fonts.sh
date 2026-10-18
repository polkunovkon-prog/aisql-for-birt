#!/usr/bin/env bash
#
# Подключение системных шрифтов DejaVu к PDF-движку BIRT для просмотрщика
# отчётов Eclipse (Run → View Report).
#
# Зачем: конструктор задаёт в отчётах шрифт «DejaVu Sans», но просмотрщик
# Eclipse создаёт движок сам и не принимает конфигурацию шрифтов плагина
# (EngineConfig.setFontConfig). Движок BIRT сам регистрирует каталог
# <java-home>/lib/fonts — положим туда симлинки на системные DejaVu.
# См. docs/TESTING.md, «Известные особенности» (кириллица в PDF).
#
# Скрипт идемпотентен: повторный запуск ничего не ломает.
# Перезапуск Eclipse после подключения обязателен.
#
set -euo pipefail

ECLIPSE_HOME="${ECLIPSE_HOME:-/home/pka/eclipse}"
DEFAULT_VM="/home/pka/opt/jdk8u504-b01/bin/java"
SRC="/usr/share/fonts/truetype/dejavu"

[ -d "$ECLIPSE_HOME" ] || { echo "ОШИБКА: нет каталога Eclipse: $ECLIPSE_HOME" >&2; exit 2; }
[ -d "$SRC" ] || { echo "ОШИБКА: нет шрифтов $SRC (sudo apt install fonts-dejavu-core)" >&2; exit 2; }

# JVM берём из eclipse.ini (-vm) или используем значение по умолчанию
VM="$DEFAULT_VM"
if [ -f "$ECLIPSE_HOME/eclipse.ini" ]; then
    FROM_INI="$(sed -n '/^-vm$/{n;p;}' "$ECLIPSE_HOME/eclipse.ini" | head -1 | tr -d '\r')"
    [ -n "${FROM_INI:-}" ] && VM="$FROM_INI"
fi

JAVA_HOME_DIR="$(cd "$(dirname "$VM")/.." && pwd)"
if [ -d "$JAVA_HOME_DIR/jre" ]; then
    FONTS="$JAVA_HOME_DIR/jre/lib/fonts"   # Java 8
else
    FONTS="$JAVA_HOME_DIR/lib/fonts"       # Java 9+
fi

mkdir -p "$FONTS"
for f in DejaVuSans.ttf DejaVuSans-Bold.ttf DejaVuSans-Oblique.ttf DejaVuSans-BoldOblique.ttf; do
    ln -sf "$SRC/$f" "$FONTS/$f"
done

echo "JVM Eclipse:  $VM"
echo "Шрифты:       $FONTS  →  $SRC"
echo "Готово. Перезапустите Eclipse, чтобы просмотрщик подхватил шрифты."
