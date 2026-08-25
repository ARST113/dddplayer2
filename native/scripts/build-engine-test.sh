#!/usr/bin/env bash
#
# Сборка и запуск engine_probe — теста шага 3 (io_bridge, пробинг, демукс).
#
# Отличие от build-smoke.sh: там проверялась сама сборка FFmpeg, здесь — наш код
# движка (`native/ddd_engine/src`). Поэтому компилируется C++ и линкуется весь
# набор исходников движка, а не один тестовый файл.
#
#   bash build-engine-test.sh                        — только самотесты
#   bash build-engine-test.sh <файл>                 — плюс пробинг и демукс файла
#   bash build-engine-test.sh <файл> <файл-hdr10>    — плюс проверка HDR-метаданных
#
# Пути даются как ЛОКАЛЬНЫЕ файлы: скрипт сам заливает их на устройство. Если
# аргумент начинается с /sdcard или /data — считается, что файл уже на устройстве,
# и заливка пропускается.
#
# Устройство: SERIAL=<серийник>, по умолчанию Pixel 6 (oriole, Android 16).
# Гарнитура здесь не нужна — проверяется контейнерная часть, а не HDR-вывод.
#
# Вне /data/local/tmp/dddsmoke ничего не меняет.

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
CLANGXX="$TC/bin/clang++.exe"
ADB="${ADB:-$SDK/platform-tools/adb.exe}"
TRIPLE="aarch64-none-linux-android$API"

SERIAL="${SERIAL:-24081FDF600AK4}"

die() { echo "ОШИБКА: $*" >&2; exit 1; }

[ -x "$CLANGXX" ] || die "нет clang++: $CLANGXX"
[ -d "$FF/lib" ]  || die "нет сборки FFmpeg: $FF/lib (сначала build-ffmpeg.sh)"
[ -x "$ADB" ]     || die "нет adb: $ADB"

# MSYS переписывает аргументы, начинающиеся с '/', в пути Windows — для adb это
# смертельно ('remote secure_mkdirs() failed'). Отключаем только для adb: clang,
# наоборот, требует путей Windows.
adb_() { MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "$ADB" -s "$SERIAL" "$@"; }
win()  { cygpath -m "$1"; }
adb_ get-state >/dev/null 2>&1 || die "устройство $SERIAL не в состоянии device ($("$ADB" devices | tr '\n' ' '))"

mkdir -p "$OUT"

SRC="$NATIVE/ddd_engine/src"
SOURCES=(
    "$SRC/io_source.cpp"
    "$SRC/hdr_static_info.cpp"
    "$SRC/media_format_map.cpp"
    "$SRC/probe.cpp"
    "$SRC/packet_queue.cpp"
    "$SRC/demux_session.cpp"
    "$NATIVE/tests/engine_probe.cpp"
)
for f in "${SOURCES[@]}"; do [ -f "$f" ] || die "нет исходника: $f"; done

echo ">>> компиляция engine_probe ($TRIPLE, C++17)"
# -DDDD_HOST_TEST: логи движка идут в stderr, а не только в logcat, — иначе на
# отдельном бинаре причина ошибки не видна вообще.
#
# __STDC_*_MACROS: `libavutil/common.h` выдаёт #error, если их нет при сборке
# C++ (проверка родом из C++98, где UINT64_C и PRId64 были скрыты за этими
# макросами). Задаются флагом, а не в заголовке: тогда они действуют независимо
# от того, в каком порядке транслируемый файл включит заголовки FFmpeg. Те же
# три определения обязаны попасть в CMakeLists движка.
"$CLANGXX" --target="$TRIPLE" --sysroot="$TC/sysroot" \
    -std=c++17 -O2 -fPIE -pie -Wall -Wextra -Wno-unused-parameter \
    -DDDD_HOST_TEST=1 \
    -D__STDC_CONSTANT_MACROS -D__STDC_FORMAT_MACROS -D__STDC_LIMIT_MACROS \
    -I"$FF/include" \
    "${SOURCES[@]}" \
    -L"$FF/lib" \
    -lavformat_ddd -lavcodec_ddd -lavfilter_ddd -lswscale_ddd -lswresample_ddd -lavutil_ddd \
    -lm -llog -lz \
    -o "$OUT/engine_probe"

echo "    $(ls -l "$OUT/engine_probe" | awk '{print $5}') байт"

echo ">>> NEEDED"
"$TC/bin/llvm-readelf.exe" -d "$OUT/engine_probe" | grep -E 'NEEDED' | sed 's/^/    /'

echo ">>> устройство"
adb_ shell 'echo "    $(getprop ro.product.model) / Android $(getprop ro.build.version.release) / $(getprop ro.product.cpu.abi)"'

echo ">>> заливка в $DEV"
adb_ shell "mkdir -p $DEV" >/dev/null
for f in "$FF/stripped"/*.so; do adb_ push "$(win "$f")" "$DEV/" >/dev/null; done
# libc++_shared.so: движок на C++, а в системе Android этой библиотеки нет —
# приложения обязаны нести её сами (в APK её кладёт CMake через ANDROID_STL).
# Для отдельного бинаря делаем то же руками, чтобы окружение совпадало с APK.
STL="$TC/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
[ -f "$STL" ] || die "нет libc++_shared.so: $STL"
adb_ push "$(win "$STL")" "$DEV/" >/dev/null
adb_ push "$(win "$OUT/engine_probe")" "$DEV/" >/dev/null
adb_ shell "chmod 755 $DEV/engine_probe"

# Аргументы-файлы: локальные заливаем, устройственные оставляем как есть.
ARGS=()
for a in "$@"; do
    case "$a" in
        /sdcard/*|/data/*|/storage/*)
            ARGS+=("$a")
            ;;
        *)
            [ -f "$a" ] || die "нет файла: $a"
            base="$(basename "$a")"
            echo "    заливаю $base ($(ls -l "$a" | awk '{print $5}') байт)"
            adb_ push "$(win "$a")" "$DEV/$base" >/dev/null
            ARGS+=("$DEV/$base")
            ;;
    esac
done

echo ">>> запуск на устройстве"
echo "----------------------------------------------------------------"
# `adb shell` возвращает код своего соединения, а не код процесса на устройстве,
# поэтому провалившийся тест выглядел бы как успех. Забираем настоящий код через
# эхо маркера и разбор вывода.
set +e
out="$(adb_ shell "cd $DEV && LD_LIBRARY_PATH=$DEV ./engine_probe ${ARGS[*]} 2>&1 ; echo __RC=\$?" 2>&1)"
set -e
printf '%s\n' "$out" | sed 's/__RC=[0-9]*//'
echo "----------------------------------------------------------------"

rc="$(printf '%s\n' "$out" | sed -n 's/.*__RC=\([0-9]\+\).*/\1/p' | tail -1)"
[ -n "$rc" ] || die "не удалось получить код возврата теста (устройство отвалилось?)"
if [ "$rc" = "0" ]; then
    echo ">>> тест пройден"
else
    echo ">>> ТЕСТ ПРОВАЛЕН, код $rc"
fi
exit "$rc"
