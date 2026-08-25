#!/usr/bin/env bash
#
# Сборка и запуск ffmpeg_smoke на самой гарнитуре.
#
# Смысл шага: «файлы .so лежат на диске» и «FFmpeg работает на устройстве» —
# разные утверждения. Здесь проверяется второе: линкер Android грузит наши
# libav*_ddd.so, sonames совпадают, API 23 достаточно, компоненты находятся
# в рантайме, демукс реально читает пакеты.
#
#   bash build-smoke.sh                    — собрать, запустить проверку сборки
#   bash build-smoke.sh <файл-на-устройстве> — плюс пробинг этого файла
#
# Устройство выбирается через SERIAL=<серийник>; по умолчанию — Pixel 6 (oriole,
# Android 16). Гарнитура для этого теста не нужна: проверяется сборка, а не
# HDR-конвейер, а Android 16 вдобавок строже к выравниванию страниц на 16 КБ.
#
# Ничего не устанавливает и не меняет на устройстве вне /data/local/tmp/dddsmoke.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NATIVE="$(cd "$HERE/.." && pwd)"

ABI="arm64-v8a"
API="${ANDROID_API:-23}"
FF="${FF_DIR:-$NATIVE/prebuilt/ffmpeg/$ABI}"
OUT="$NATIVE/build/tests"
DEV="/data/local/tmp/dddsmoke"

NDK="${ANDROID_NDK_HOME:-F:/CODEX/android-toolchain/sdk/ndk/27.0.12077973}"
SDK="${ANDROID_SDK_ROOT:-F:/CODEX/android-toolchain/sdk}"
TC="$NDK/toolchains/llvm/prebuilt/windows-x86_64"
CLANG="$TC/bin/clang.exe"
ADB="${ADB:-$SDK/platform-tools/adb.exe}"
TRIPLE="aarch64-none-linux-android$API"

# Pixel 6 по умолчанию: обычный телефон, Android 16.
SERIAL="${SERIAL:-24081FDF600AK4}"

die() { echo "ОШИБКА: $*" >&2; exit 1; }

[ -x "$CLANG" ]            || die "нет clang: $CLANG"
[ -d "$FF/lib" ]           || die "нет сборки FFmpeg: $FF/lib (сначала build-ffmpeg.sh)"
[ -x "$ADB" ]              || die "нет adb: $ADB"

# Git Bash (MSYS) переписывает аргументы, начинающиеся со '/', в пути Windows:
# '/data/local/tmp/x' стал бы 'C:/Program Files/Git/data/local/tmp/x', и adb push
# падает с 'remote secure_mkdirs() failed'. Отключаем преобразование только для
# adb — clang, наоборот, требует путей Windows. Локальные пути для adb переводим
# сами через cygpath -m.
adb_() { MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "$ADB" -s "$SERIAL" "$@"; }
win()  { cygpath -m "$1"; }
adb_ get-state >/dev/null 2>&1 || die "устройство $SERIAL не в состоянии device ($("$ADB" devices | tr '\n' ' '))"

mkdir -p "$OUT"

echo ">>> компиляция ffmpeg_smoke ($TRIPLE)"
"$CLANG" --target="$TRIPLE" --sysroot="$TC/sysroot" \
    -O2 -fPIE -pie -Wall -Wextra -Wno-unused-parameter \
    -I"$FF/include" \
    "$NATIVE/tests/ffmpeg_smoke.c" \
    -L"$FF/lib" \
    -lavformat_ddd -lavcodec_ddd -lavfilter_ddd -lswscale_ddd -lswresample_ddd -lavutil_ddd \
    -lm -llog -lz \
    -o "$OUT/ffmpeg_smoke"

echo "    $(ls -l "$OUT/ffmpeg_smoke" | awk '{print $5}') байт"

# Что бинарь реально требует от линкера: имена должны совпасть с sonames .so.
echo ">>> NEEDED"
"$TC/bin/llvm-readelf.exe" -d "$OUT/ffmpeg_smoke" | grep -E 'NEEDED|RUNPATH' | sed 's/^/    /'

# Android 15+ требует, чтобы LOAD-сегменты были выровнены на 16 КБ. Проверяем
# по факту, а не по наличию флага в командной строке линкера.
echo ">>> выравнивание LOAD-сегментов (нужно 0x4000 для Android 15+)"
for f in "$FF/stripped"/*.so; do
    a="$("$TC/bin/llvm-readelf.exe" -l "$f" | awk '$1=="LOAD" {print $NF}' | sort -u | tr '\n' ' ')"
    printf '    %-26s %s\n' "$(basename "$f")" "$a"
done

echo ">>> устройство"
adb_ shell 'echo "    $(getprop ro.product.model) / Android $(getprop ro.build.version.release) / $(getprop ro.product.cpu.abi) / page=$(getconf PAGE_SIZE)"'

echo ">>> заливка в $DEV"
adb_ shell "mkdir -p $DEV" >/dev/null
# Только stripped-версии: на устройство идёт то, что попадёт в APK.
for f in "$FF/stripped"/*.so; do adb_ push "$(win "$f")" "$DEV/" >/dev/null; done
adb_ push "$(win "$OUT/ffmpeg_smoke")" "$DEV/" >/dev/null
adb_ shell "chmod 755 $DEV/ffmpeg_smoke"

echo ">>> запуск на устройстве"
echo "----------------------------------------------------------------"
set +e
adb_ shell "cd $DEV && LD_LIBRARY_PATH=$DEV ./ffmpeg_smoke $* ; echo __RC=\$?"
rc=$?
set -e
echo "----------------------------------------------------------------"
echo ">>> код возврата adb: $rc"
exit $rc
