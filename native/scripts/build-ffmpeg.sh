#!/usr/bin/env bash
#
# Сборка FFmpeg 7.1.5 для DDD-движка, arm64-v8a, NDK 27, Windows-хост (Git Bash).
#
# Опорная точка — конфигурация 4XVR, восстановленная из libavutil4x.so
# (analysis/COMPONENT-AUDIT.md §2). Отличия от неё — осознанные:
#
#   4XVR (FFmpeg 4.4.3, NDK r21)      здесь (FFmpeg 7.1.5, NDK 27)      почему
#   -----------------------------      ---------------------------      ------
#   --extra-cflags='-Os'               -O3                              в аудите: собрано
#                                                                       на размер, есть
#                                                                       запас скорости
#   -mtune=cortex-a55                  -mtune=cortex-a78                XR2 Gen 2, а не Gen 1;
#                                                                       ISA остаётся armv8-a
#   --enable-openssl                   нет                              TLS/HTTPS делает
#                                                                       Java-слой DDD (OkHttp)
#                                                                       через io_bridge
#   --enable-libdav1d                  нет (пока)                       нативный av1-декодер
#                                                                       + HW c2.qti.av1.decoder
#   --build_suffix=4x                  --build-suffix=_ddd              свои имена
#   FFmpeg 4.4.3                       7.1.5                            DoVi RPU (dovi_rpudec.c),
#                                                                       MV-HEVC, HDR10+
#
# Не отключаем декодеры/демуксеры/парсеры/протоколы: «всеформатность как в VLC»
# — это и есть набор FFmpeg по умолчанию. Отключено только то, что плееру не нужно
# вообще (кодирование, мультиплексирование, устройства, программы).
#
# Известные пробелы этой сборки, закрываются отдельными шагами:
#   * DASH-демуксер требует libxml2 → появится после сборки libxml2;
#   * https:// в native недоступен (см. выше) — идёт через Java;
#   * libbluray (BDMV/ISO) и dav1d подключаются позже.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NATIVE="$(cd "$HERE/.." && pwd)"

SRC="${FFMPEG_SRC:-$NATIVE/ffmpeg-src}"
ABI="arm64-v8a"
API="${ANDROID_API:-23}"          # = minSdk DDD; у 4XVR был тот же android23
OUT="${OUT_DIR:-$NATIVE/prebuilt/ffmpeg/$ABI}"
LOG="$NATIVE/build/ffmpeg-$ABI.log"

NDK="${ANDROID_NDK_HOME:-F:/CODEX/android-toolchain/sdk/ndk/27.0.12077973}"
TC="$NDK/toolchains/llvm/prebuilt/windows-x86_64"
MAKE="$NDK/prebuilt/windows-x86_64/bin/make.exe"

TRIPLE="aarch64-none-linux-android$API"
# Прямой clang.exe, а не bash-обёртка aarch64-linux-android$API-clang: обёртка на
# каждый файл поднимает лишний процесс bash, а под Windows это ~2000 лишних
# запусков за сборку.
CLANG="$TC/bin/clang.exe"

# --- проверки окружения -------------------------------------------------------

die() { echo "ОШИБКА: $*" >&2; exit 1; }

[ -d "$SRC" ]      || die "нет исходников FFmpeg: $SRC"
[ -x "$CLANG" ]    || die "нет clang: $CLANG"
[ -x "$MAKE" ]     || die "нет make: $MAKE"
[ -d "$TC/sysroot" ] || die "нет sysroot: $TC/sysroot"

# core.autocrlf=true портит sh-скрипты и .S-файлы: configure падает на $'\r'.
if [ -d "$SRC/.git" ] && [ "$(git -C "$SRC" config core.autocrlf || true)" = "true" ]; then
    die "в $SRC включён core.autocrlf=true — дерево в CRLF. Исправить:
  git -C \"$SRC\" config core.autocrlf false && git -C \"$SRC\" config core.eol lf
  git -C \"$SRC\" rm --cached -r -q . && git -C \"$SRC\" reset --hard"
fi
head -c 12 "$SRC/configure" | grep -q $'\r' && die "$SRC/configure в CRLF (см. выше)"

JOBS="${JOBS:-$(nproc 2>/dev/null || echo 4)}"

mkdir -p "$(dirname "$LOG")"

# Системный TMPDIR под Windows приходит как 'C:\Users\...\Temp' — configure ломает
# обратные слэши, а в имени пользователя ещё и не-ASCII. Свой ASCII-путь в POSIX-виде.
export TMPDIR="$NATIVE/build/tmp"
mkdir -p "$TMPDIR"

# --host-cc: на этой машине нет ни одного хост-компилятора (ни gcc, ни cl), а
# configure требует от него C11. В нашей конфигурации хост-программы не собираются
# вообще: HOSTPROGS есть только у --enable-hardcoded-tables (выключено),
# tests/ и doc/print_options (--disable-doc, тесты не запускаются). Поэтому
# host_cc = кросс-компилятор: проверка C11 проходит, собирать им нечего.
# Условие: `make check` и `make doc` в этой сборке неприменимы (дали бы aarch64-бинари).
if [ -n "${STRICT_HOST_CC:-}" ]; then
    command -v gcc >/dev/null || die "STRICT_HOST_CC задан, но gcc не найден"
fi

echo "FFmpeg   : $(cat "$SRC/RELEASE" 2>/dev/null || echo '?')  ($SRC)"
echo "NDK      : $NDK"
echo "target   : $TRIPLE"
echo "output   : $OUT"
echo "jobs     : $JOBS"
echo "log      : $LOG"
echo

# --- патчи --------------------------------------------------------------------

# 0001-link-via-response-file: линковка libavcodec — это ~1000 объектов в одной
# командной строке, а CreateProcess под Windows ограничен 32 КБ. Строка обрезается
# посреди имени файла ("clang: error: no such file or directory: 'libavcod'").
# Правило переведено на response-файл, который пишет сам make ($(file ...),
# GNU Make 4.x) — без подпроцесса и без лимита.
for p in "$NATIVE/patches"/*.patch; do
    [ -f "$p" ] || continue
    name="$(basename "$p")"
    if git -C "$SRC" apply --reverse --check "$p" >/dev/null 2>&1; then
        echo "патч уже применён: $name"
    elif git -C "$SRC" apply --check "$p" >/dev/null 2>&1; then
        git -C "$SRC" apply "$p" && echo "патч применён:      $name"
    else
        die "патч не применяется к $SRC: $name"
    fi
done
echo

# --- внешние зависимости ------------------------------------------------------

# libxml2 — только ради DASH-демуксера (`dash_demuxer_deps="libxml2"`), статически.
# pkg-config в системе нет, поэтому configure получает наш шим (scripts/pkg-config).
LIBXML2="$NATIVE/prebuilt/libxml2/$ABI"
PKGDIRS=""
XML2_OPT=""
EXTRA_CFLAGS="-O3 -fpic -mtune=cortex-a78 -ffunction-sections -fdata-sections -DANDROID"
if [ -f "$LIBXML2/lib/pkgconfig/libxml-2.0.pc" ]; then
    PKGDIRS="$LIBXML2/lib/pkgconfig"
    XML2_OPT="--enable-libxml2"
    # configure проверяет наличие через <libxml2/libxml/xmlversion.h>, то есть ждёт
    # на include-пути родительский каталог. На обычной системе это /usr/include,
    # а .pc отдаёт только .../include/libxml2 — поэтому корень добавляем сами.
    EXTRA_CFLAGS="$EXTRA_CFLAGS -I$LIBXML2/include"
else
    echo "ВНИМАНИЕ: libxml2 не собран ($LIBXML2) — DASH-демуксера не будет."
    echo "          сначала: bash $HERE/build-libxml2.sh"
    echo
fi
export PKG_CONFIG_PATH="$PKGDIRS"

# --- конфигурация -------------------------------------------------------------

# Минимальный набор фильтров: тяжёлую обработку кадра делает GL-стадия движка,
# libavfilter нужен только для деинтерлейса, программной конверсии и аудио-формата.
FILTERS="aresample,aformat,volume,anull,asetpts"
FILTERS="$FILTERS,scale,format,null,copy,crop,transpose,setpts,fps"
FILTERS="$FILTERS,yadif,bwdif,idet"

cd "$SRC"

if [ "${RECONFIGURE:-1}" = "1" ] || [ ! -f config.h ]; then
    echo ">>> make distclean" | tee "$LOG"
    "$MAKE" distclean >/dev/null 2>&1 || true

    echo ">>> configure" | tee -a "$LOG"
    ./configure \
        --prefix="$OUT" \
        --enable-cross-compile \
        --target-os=android \
        --arch=aarch64 \
        --cpu=armv8-a \
        --sysroot="$TC/sysroot" \
        --cc="$CLANG --target=$TRIPLE" \
        --cxx="$TC/bin/clang++.exe --target=$TRIPLE" \
        --host-cc="$CLANG --target=$TRIPLE --sysroot=$TC/sysroot" \
        --ar="$TC/bin/llvm-ar.exe" \
        --nm="$TC/bin/llvm-nm.exe" \
        --ranlib="$TC/bin/llvm-ranlib.exe" \
        --strip="$TC/bin/llvm-strip.exe" \
        --enable-shared \
        --disable-static \
        --enable-pic \
        --build-suffix=_ddd \
        --enable-asm \
        --enable-neon \
        --enable-jni \
        --disable-programs \
        --disable-doc \
        --disable-avdevice \
        --disable-postproc \
        --disable-encoders \
        --disable-muxers \
        --disable-devices \
        --disable-mediacodec \
        --disable-v4l2-m2m \
        --disable-vulkan \
        --disable-symver \
        --disable-stripping \
        --disable-filters \
        --enable-filter="$FILTERS" \
        --pkg-config="$HERE/pkg-config" \
        --pkg-config-flags=--static \
        ${XML2_OPT:+$XML2_OPT} \
        --extra-cflags="$EXTRA_CFLAGS" \
        --extra-ldflags="-Wl,--gc-sections -Wl,-z,max-page-size=16384" \
        2>&1 | tee -a "$LOG" | tail -25
else
    echo ">>> configure пропущен (RECONFIGURE=0, config.h есть)"
fi

# --- сборка -------------------------------------------------------------------

# CONFIGURE_ONLY=1 — только конфигурация: быстрый прогон, чтобы поймать ошибки в
# опциях, не ожидая ~2000 файлов. Затем RECONFIGURE=0 продолжает сборку
# инкрементально, сколько бы вызовов make на это ни ушло.
if [ "${CONFIGURE_ONLY:-0}" = "1" ]; then
    echo ">>> CONFIGURE_ONLY=1 — останов после configure"
    exit 0
fi

echo ">>> make -j$JOBS" | tee -a "$LOG"
"$MAKE" -j"$JOBS" >>"$LOG" 2>&1 || {
    echo "сборка упала, последние 40 строк лога:" >&2
    tail -40 "$LOG" >&2
    exit 1
}

rm -rf "$OUT"
"$MAKE" install >>"$LOG" 2>&1 || { tail -40 "$LOG" >&2; exit 1; }

# --- проверка -----------------------------------------------------------------

echo
echo ">>> результат"
ls -la "$OUT/lib"/*.so 2>/dev/null || die "в $OUT/lib нет .so"

READELF="$TC/bin/llvm-readelf.exe"
for so in "$OUT/lib"/*.so; do
    name="$(basename "$so")"
    soname="$("$READELF" -d "$so" | grep -oP '(?<=SONAME\s{12}Library soname: \[)[^]]+' || true)"
    [ -n "$soname" ] || soname="$("$READELF" -d "$so" | grep SONAME | sed 's/.*\[\(.*\)\].*/\1/')"
    arch="$("$READELF" -h "$so" | grep -oP '(?<=Machine:\s{22}).*' || echo '?')"
    printf '%-26s soname=%-26s %s\n' "$name" "$soname" "$arch"
done

echo
echo ">>> версии"
for l in avcodec:61 avformat:61 avutil:59 avfilter:10 swscale:8 swresample:5; do
    lib="${l%%:*}"
    vh="$OUT/include/lib$lib/version.h"
    vhm="$OUT/include/lib$lib/version_major.h"
    maj="$( { grep -hoP '(?<=_VERSION_MAJOR)\s+\d+' "$vhm" "$vh" 2>/dev/null || true; } | head -1 | tr -d ' ')"
    min="$( { grep -hoP '(?<=_VERSION_MINOR)\s+\d+' "$vh" 2>/dev/null || true; } | head -1 | tr -d ' ')"
    printf '  lib%-12s %s.%s\n' "$lib" "$maj" "$min"
done

echo
echo ">>> ключевые компоненты (символы LOCAL: у shared-сборки FFmpeg скрыты, ищем в symtab)"
have() {  # have <файл> <символ>
    # без grep -q: он закрывает пайп раньше времени, readelf получает SIGPIPE,
    # и при `set -o pipefail` проверка всегда давала бы «НЕТ».
    "$READELF" --syms "$1" 2>/dev/null | grep -E "[[:space:]]$2\$" >/dev/null
}
CODEC="$OUT/lib/libavcodec_ddd.so"
FORMAT="$OUT/lib/libavformat_ddd.so"
for sym in ff_hevc_decoder ff_h264_decoder ff_av1_decoder ff_vp9_decoder ff_vvc_decoder \
           ff_dolby_e_decoder ff_truehd_decoder ff_pgssub_decoder ff_dvbsub_decoder; do
    have "$CODEC" "$sym" && printf '  %-22s есть\n' "$sym" || printf '  %-22s НЕТ\n' "$sym"
done
for sym in ff_matroska_demuxer ff_mov_demuxer ff_mpegts_demuxer ff_hls_demuxer \
           ff_rtsp_demuxer ff_dash_demuxer; do
    have "$FORMAT" "$sym" && printf '  %-22s есть\n' "$sym" || printf '  %-22s НЕТ\n' "$sym"
done
printf '  %-22s %s\n' "всего декодеров" \
    "$({ "$TC/bin/llvm-nm.exe" --defined-only "$CODEC" 2>/dev/null || true; } | grep -cE 'ff_[a-z0-9_]+_decoder$')"

echo
echo ">>> размер после strip (в APK попадёт этот)"
STRIPPED="$OUT/stripped"
rm -rf "$STRIPPED"; mkdir -p "$STRIPPED"
for f in "$OUT/lib"/*.so; do
    cp "$f" "$STRIPPED/"
    "$TC/bin/llvm-strip.exe" --strip-all "$STRIPPED/$(basename "$f")"
done
ls -l "$STRIPPED" | awk 'NR>1 {printf "  %-26s %8.2f МБ\n", $9, $5/1048576; t+=$5} END {printf "  %-26s %8.2f МБ\n", "ИТОГО", t/1048576}'

echo
echo "готово: $OUT"
