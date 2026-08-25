#!/usr/bin/env bash
#
# libxml2 для DDD-движка, arm64-v8a. Нужен ровно для одного: без него у FFmpeg
# нет DASH-демуксера (`dash_demuxer_deps="libxml2"`), а DDD DASH поддерживает —
# `PlayerManager.kt:151,162` и `MediaFormatHelper.kt:67,333` мапят `.mpd` и
# `application/dash+xml`. Заодно libxml2 включает демуксер IMF.
#
# Статическая сборка (MIT, статическая линковка допустима): libxml2 линкуется
# внутрь libavformat_ddd.so, лишней .so в APK не появляется.
#
# Из API libxml2 FFmpeg использует только parser+tree (проверено по dashdec.c и
# imfdec.c: xmlReadMemory, xmlDocGetRootElement, xmlFirstElementChild,
# xmlNextElementSibling, xmlGetProp, xmlNodeGetContent, xmlCopyNode, xmlNewNode,
# xmlNodeSetContent, xmlFree*). Ни XPath, ни HTML, ни schemas, ни reader/writer —
# всё это выключено.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NATIVE="$(cd "$HERE/.." && pwd)"

SRC="${LIBXML2_SRC:-$NATIVE/libxml2-src}"
ABI="arm64-v8a"
API="${ANDROID_API:-23}"
OUT="${OUT_DIR:-$NATIVE/prebuilt/libxml2/$ABI}"
BUILD="$NATIVE/build/libxml2/$ABI-cmake"
LOG="$NATIVE/build/libxml2-$ABI.log"

NDK="${ANDROID_NDK_HOME:-F:/CODEX/android-toolchain/sdk/ndk/27.0.12077973}"
SDK="${ANDROID_SDK_ROOT:-F:/CODEX/android-toolchain/sdk}"
CMAKE="$SDK/cmake/3.22.1/bin/cmake.exe"
NINJA="$SDK/cmake/3.22.1/bin/ninja.exe"

die() { echo "ОШИБКА: $*" >&2; exit 1; }

[ -f "$SRC/CMakeLists.txt" ] || die "нет исходников libxml2: $SRC"
[ -x "$CMAKE" ] || die "нет cmake: $CMAKE"
[ -x "$NINJA" ] || die "нет ninja: $NINJA"

mkdir -p "$(dirname "$LOG")"

echo "libxml2 : $SRC ($(git -C "$SRC" describe --tags 2>/dev/null || echo '?'))"
echo "output  : $OUT"
echo

rm -rf "$BUILD" "$OUT"

"$CMAKE" -G Ninja \
    -S "$SRC" -B "$BUILD" \
    -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$OUT" \
    -DCMAKE_C_FLAGS="-O3 -fPIC -ffunction-sections -fdata-sections" \
    -DBUILD_SHARED_LIBS=OFF \
    -DLIBXML2_WITH_PROGRAMS=OFF \
    -DLIBXML2_WITH_TESTS=OFF \
    -DLIBXML2_WITH_PYTHON=OFF \
    -DLIBXML2_WITH_CATALOG=OFF \
    -DLIBXML2_WITH_DEBUG=OFF \
    -DLIBXML2_WITH_HTML=OFF \
    -DLIBXML2_WITH_HTTP=OFF \
    -DLIBXML2_WITH_ICONV=OFF \
    -DLIBXML2_WITH_ICU=OFF \
    -DLIBXML2_WITH_LEGACY=OFF \
    -DLIBXML2_WITH_LZMA=OFF \
    -DLIBXML2_WITH_ZLIB=OFF \
    -DLIBXML2_WITH_MODULES=OFF \
    -DLIBXML2_WITH_PATTERN=OFF \
    -DLIBXML2_WITH_REGEXPS=OFF \
    -DLIBXML2_WITH_SCHEMAS=OFF \
    -DLIBXML2_WITH_SCHEMATRON=OFF \
    -DLIBXML2_WITH_READER=OFF \
    -DLIBXML2_WITH_WRITER=OFF \
    -DLIBXML2_WITH_VALID=OFF \
    -DLIBXML2_WITH_XINCLUDE=OFF \
    -DLIBXML2_WITH_XPATH=OFF \
    -DLIBXML2_WITH_THREADS=ON \
    > "$LOG" 2>&1 || { tail -30 "$LOG" >&2; die "cmake configure упал"; }

"$CMAKE" --build "$BUILD" --target install --parallel "${JOBS:-$(nproc 2>/dev/null || echo 4)}" \
    >> "$LOG" 2>&1 || { tail -40 "$LOG" >&2; die "сборка упала"; }

echo ">>> результат"
find "$OUT" -name '*.a' -printf '  %p  %s байт\n'
PC="$(find "$OUT" -name 'libxml-2.0.pc' | head -1)"
[ -n "$PC" ] || die "не установлен libxml-2.0.pc — FFmpeg его не найдёт"
echo "  $PC"
echo
echo ">>> проверка шима pkg-config"
PKG_CONFIG_PATH="$(dirname "$PC")" "$HERE/pkg-config" --modversion libxml-2.0
PKG_CONFIG_PATH="$(dirname "$PC")" "$HERE/pkg-config" --cflags --libs libxml-2.0
echo
echo "готово: $OUT"
