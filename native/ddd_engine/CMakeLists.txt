# CMakeLists.txt — libddd_engine.so, единый нативный движок (UNIFIED-ENGINE.md).
#
# Собирается из Gradle через externalNativeBuild. FFmpeg здесь НЕ собирается: его
# шесть библиотек уже лежат готовыми в native/prebuilt/ffmpeg/<abi>/ после
# scripts/build-ffmpeg.sh. Причина в скорости и воспроизводимости — сборка FFmpeg
# идёт минуты и завязана на configure, и повторять её на каждый Gradle sync
# нечем оправдать.
#
# Суффикс _ddd в именах библиотек не косметика: в APK может оказаться чужая
# libavcodec.so (её несут VLC и часть SDK), и динамический линкер Android
# разрешает имена глобально по процессу. Одинаковые имена — это чужой FFmpeg,
# подставленный вместо нашего, с другим набором декодеров.

cmake_minimum_required(VERSION 3.22.1)
project(ddd_engine C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Каталог с результатами scripts/build-ffmpeg.sh. Переопределяется из Gradle
# через -DDDD_FFMPEG_DIR=..., если сборка вынесена в другое место.
if(NOT DEFINED DDD_FFMPEG_DIR)
    set(DDD_FFMPEG_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../prebuilt/ffmpeg/${ANDROID_ABI}")
endif()

get_filename_component(DDD_FFMPEG_DIR "${DDD_FFMPEG_DIR}" ABSOLUTE)
get_filename_component(DDD_FFMPEG_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../ffmpeg-src" ABSOLUTE)

if(NOT EXISTS "${DDD_FFMPEG_DIR}/include/libavformat/avformat.h")
    message(FATAL_ERROR
        "Нет сборки FFmpeg для ${ANDROID_ABI} в ${DDD_FFMPEG_DIR}.\n"
        "Сначала: bash native/scripts/build-ffmpeg.sh")
endif()

# Стрипнутые библиотеки идут в APK, полные — остаются для отладки: символы нужны
# ndk-stack, чтобы из адресов в tombstone получались имена функций FFmpeg.
set(DDD_FFMPEG_LIB_DIR "${DDD_FFMPEG_DIR}/stripped")
if(NOT EXISTS "${DDD_FFMPEG_LIB_DIR}")
    set(DDD_FFMPEG_LIB_DIR "${DDD_FFMPEG_DIR}/lib")
endif()

foreach(ff avutil avcodec avformat avfilter swscale swresample)
    add_library(ff_${ff} SHARED IMPORTED)
    set_target_properties(ff_${ff} PROPERTIES
        IMPORTED_LOCATION "${DDD_FFMPEG_LIB_DIR}/lib${ff}_ddd.so")
    if(NOT EXISTS "${DDD_FFMPEG_LIB_DIR}/lib${ff}_ddd.so")
        message(FATAL_ERROR "нет ${DDD_FFMPEG_LIB_DIR}/lib${ff}_ddd.so")
    endif()
endforeach()

add_library(ddd_engine SHARED
    src/io_source.cpp
    src/hdr_static_info.cpp
    src/media_format_map.cpp
    src/probe.cpp
    src/packet_queue.cpp
    src/demux_session.cpp
    src/egl_context.cpp
    src/gl_util.cpp
    src/gl_render_target.cpp
    src/dovi_rpu_parser.cpp
    src/tone_map.cpp
    src/video_renderer.cpp
    src/render_reference.cpp
    src/hw_decoder.cpp
    src/sw_decoder.cpp
    src/decode_session.cpp
    src/audio_decoder.cpp
    src/jni_util.cpp
    src/jni_io_source.cpp
    src/jni_renderer.cpp
    src/jni_decoder.cpp
    src/jni_audio_decoder.cpp
    src/jni_engine.cpp
    # FFmpeg keeps its DOVI parser private. Compile the three small LGPL
    # implementation units into the engine so MediaCodec playback can consume
    # RPU metadata without decoding the 4K video a second time in software.
    "${DDD_FFMPEG_SOURCE_DIR}/libavcodec/dovi_rpu.c"
    "${DDD_FFMPEG_SOURCE_DIR}/libavcodec/dovi_rpudec.c"
    "${DDD_FFMPEG_SOURCE_DIR}/libavcodec/golomb.c"
    "${DDD_FFMPEG_SOURCE_DIR}/libavcodec/refstruct.c")

# dovi_rpudec.c is normally compiled inside FFmpeg, where its private utility
# declarations and configured bit-reader macros are already in scope.
set_source_files_properties(
    "${DDD_FFMPEG_SOURCE_DIR}/libavcodec/dovi_rpudec.c"
    PROPERTIES COMPILE_OPTIONS "-include;libavutil/internal.h")

target_include_directories(ddd_engine PRIVATE
    "${DDD_FFMPEG_DIR}/include"
    "${DDD_FFMPEG_DIR}/include-private"
    "${DDD_FFMPEG_SOURCE_DIR}"
    src)

# __STDC_*_MACROS: libavutil/common.h выдаёт #error без них при сборке C++
# (проверка родом из C++98, где UINT64_C и PRId64 были спрятаны за этими
# макросами). Именно определения компилятора, а не #define в заголовке: тогда
# они действуют независимо от порядка включения заголовков FFmpeg в единице
# трансляции. Те же три флага стоят в scripts/build-engine-test.sh — расхождение
# между тестом и APK здесь означало бы «на устройстве собралось, в APK нет».
# __ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__: minSdk проекта 23, а AMediaCodec_getName
# и AMediaCodec_releaseName введены в API 28. Без этого макроса NDK 27 помечает
# такие символы `unavailable`, и вызов даёт ОШИБКУ КОМПИЛЯЦИИ даже внутри
# __builtin_available(android 28, *): проверка версии в рантайме не снимает
# пометку недоступности. С макросом NDK объявляет их слабыми, и связка
# «слабый символ + __builtin_available» работает как задумано — на API < 28
# адрес нулевой и ветка просто не исполняется.
#
# Не поднимать minSdk: API 28 отрезал бы Android TV 8.x, ради которых
# лестница декодеров и писалась. Имя компонента — диагностика, а не функция.
target_compile_definitions(ddd_engine PRIVATE
    __ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__
    __STDC_CONSTANT_MACROS
    __STDC_FORMAT_MACROS
    __STDC_LIMIT_MACROS)

target_compile_options(ddd_engine PRIVATE
    -Wall -Wextra -Wno-unused-parameter
    -fvisibility=hidden)

# Android 15+ devices may use 16 KiB memory pages.  Every PT_LOAD segment in
# our ELF must therefore be aligned to at least 0x4000; otherwise Android 16
# puts a compatibility warning over PlayerActivity before the first frame.
# FFmpeg is linked with the same flag in scripts/build-ffmpeg.sh.
target_link_options(ddd_engine PRIVATE
    "-Wl,-z,max-page-size=16384")

# JNI_OnLoad обязан остаться видимым при -fvisibility=hidden: его ищет
# динамический линкер по имени. JNIEXPORT в jni.h — это как раз
# __attribute__((visibility("default"))), поэтому отдельного экспорта не нужно;
# скрытыми становятся только внутренние символы движка, и это защищает от
# коллизий с другими .so в процессе.

target_link_libraries(ddd_engine
    ff_avformat ff_avcodec ff_avfilter ff_swscale ff_swresample ff_avutil
    android log mediandk z
    EGL GLESv3)
