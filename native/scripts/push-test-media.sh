#!/usr/bin/env bash
#
# Кладёт медиафайлы для NativeProbeTest на устройство.
#
# С Android 11 прямое чтение из /sdcard требует MANAGE_EXTERNAL_STORAGE, которого
# у тестового APK нет. Вместо этого файлы идут в приложение-специфичный внешний
# каталог: /sdcard/Android/data/<packageId>/files/dddtest/. Туда adb push пишет
# свободно, и приложение читает его без разрешений.
#
# Использование:
#   bash push-test-media.sh                   — залить все файлы из test-media/
#   bash push-test-media.sh --check           — проверить, что нужные файлы уже есть
#   SERIAL=<серийник> bash push-test-media.sh — использовать другое устройство
#
# По умолчанию — Pixel 6 (24081FDF600AK4).

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MEDIA_DIR="$(cd "$HERE/../../test-media" && pwd)"

SDK="${ANDROID_SDK_ROOT:-F:/CODEX/android-toolchain/sdk}"
ADB="${ADB:-$SDK/platform-tools/adb.exe}"
SERIAL="${SERIAL:-24081FDF600AK4}"
PACKAGE="${PACKAGE:-top.rootu.dddplayer}"

# Android-сторонний путь назначения.
DEV_DIR="/sdcard/Android/data/$PACKAGE/files/dddtest"

die() { echo "ОШИБКА: $*" >&2; exit 1; }

[ -x "$ADB" ] || die "adb не найден: $ADB"
[ -d "$MEDIA_DIR" ] || die "нет test-media/: $MEDIA_DIR"

# Git Bash (MSYS) переписывает аргументы, начинающиеся со '/', в пути Windows.
# Отключаем только точечно для adb.
adb_() { MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "$ADB" -s "$SERIAL" "$@"; }
win()  { cygpath -m "$1"; }

adb_ get-state >/dev/null 2>&1 || die "устройство $SERIAL недоступно ($("$ADB" devices | tr '\n' ' '))"

# Файлы, которые требует NativeProbeTest. Файлы без теста тоже заливаются, но
# тесты их пропустят через assumeTrue.
REQUIRED=(
    hdr10tags-both.mkv      # HDR10 + hdrStaticInfo — основной тест шага 3
    buck480p30.mp4          # SDR Big Buck Bunny — буфер, seek, backpressure
    dovi-p7.mp4             # Dolby Vision профиль 7 (dvvC на enhancement layer)
    dovi-p84.mov            # видео + AAC — модель дорожек (buck480p30 без звука)
    spherical.mkv           # 360° equirect — тест геометрии
)

# ─── режим --check ─────────────────────────────────────────────────────────────
if [[ "${1:-}" == "--check" ]]; then
    echo ">>> проверяем наличие файлов на $SERIAL"
    missing=0
    for f in "${REQUIRED[@]}"; do
        result=$(adb_ shell "test -f '$DEV_DIR/$f' && echo yes || echo no" 2>/dev/null | tr -d '\r')
        if [[ "$result" == "yes" ]]; then
            printf "  %-30s OK\n" "$f"
        else
            printf "  %-30s ОТСУТСТВУЕТ\n" "$f"
            missing=$((missing+1))
        fi
    done
    if [[ $missing -gt 0 ]]; then
        echo "Запустите push-test-media.sh без --check, чтобы залить недостающие файлы."
        exit 1
    fi
    echo "Все обязательные файлы на месте."
    exit 0
fi

# ─── заливка ───────────────────────────────────────────────────────────────────
echo ">>> устройство"
adb_ shell 'echo "  $(getprop ro.product.model) / Android $(getprop ro.build.version.release)"'

echo ">>> проверка установки $PACKAGE"
if ! adb_ shell pm path "$PACKAGE" >/dev/null 2>&1; then
    die "приложение $PACKAGE не установлено — сначала запустите gradle installDebug"
fi

echo ">>> создаём каталог $DEV_DIR"
adb_ shell "mkdir -p '$DEV_DIR'"

echo ">>> заливаем файлы из $MEDIA_DIR"
count=0
total=0
for f in "$MEDIA_DIR"/*.{mkv,mp4,mov,webm,hevc}; do
    [[ -f "$f" ]] || continue
    name="$(basename "$f")"
    size=$(stat -c%s "$f" 2>/dev/null || stat -f%z "$f")
    total=$((total+size))
    printf "  %-30s %5d КБ  " "$name" $((size/1024))
    adb_ push "$(win "$f")" "$DEV_DIR/$name" >/dev/null 2>&1 && echo "OK" || echo "ОШИБКА"
    count=$((count+1))
done
echo "  Итого: $count файл(ов), $((total/1024)) КБ"

# Каталог, созданный из adb, принадлежит shell:ext_data_rw с режимом 0770, и
# приложение — владелец `files/`, но НЕ этого подкаталога — не может в него даже
# войти. Файлы тогда невидимы, тест молча пропускается через assumeTrue и выглядит
# как успешный. chmod на эмулированном томе работает, чем и пользуемся.
echo ">>> открываем доступ приложению"
adb_ shell "chmod 777 '$DEV_DIR' && chmod 666 '$DEV_DIR'/* 2>/dev/null; ls -ld '$DEV_DIR'" | sed 's/^/  /'

echo ">>> проверка обязательных файлов"
all_ok=true
for f in "${REQUIRED[@]}"; do
    result=$(adb_ shell "test -f '$DEV_DIR/$f' && echo yes || echo no" 2>/dev/null | tr -d '\r')
    if [[ "$result" != "yes" ]]; then
        echo "  ОТСУТСТВУЕТ: $f"
        all_ok=false
    fi
done
$all_ok && echo "  Все обязательные файлы на месте." || die "часть обязательных файлов не залилась"
