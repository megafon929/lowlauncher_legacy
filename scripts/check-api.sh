#!/usr/bin/env bash
# Проверка компиляции против указанного android.jar (рекомендуется platforms/android-25, Android 7.1):
# вызовы API новее jar дадут ошибку компиляции. Вызовы API 16+ в коде стоят под проверкой SDK_INT.
# Использование: scripts/check-api.sh /путь/к/platforms/android-25/android.jar
set -euo pipefail
JAR="${1:?укажите путь к android.jar уровня API 25}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mktemp -d)"
mkdir -p "$OUT/gen/com/m929/lowlauncher" "$OUT/classes"
{
  echo "package com.m929.lowlauncher;"
  echo "public final class R { public static final class string {"
  i=1
  for n in $(grep -o 'name="[a-z_]*"' "$ROOT/app/src/main/res/values/strings.xml" | cut -d'"' -f2); do
    echo "  public static final int $n = 0x7f0a00$(printf '%02x' $i);"; i=$((i+1))
  done
  echo "} }"
} > "$OUT/gen/com/m929/lowlauncher/R.java"
javac -nowarn -source 8 -target 8 -Xlint:-options -bootclasspath "$JAR" -d "$OUT/classes" \
  "$ROOT"/app/src/main/java/com/m929/lowlauncher/*.java "$OUT/gen/com/m929/lowlauncher/R.java"
echo "OK: $(ls "$OUT/classes/com/m929/lowlauncher" | wc -l) классов скомпилировано против $JAR"
rm -rf "$OUT"
