cmake_minimum_required(VERSION 3.22.1)

project(ddd_native_player LANGUAGES CXX)

set(DDD_FFMPEG_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../ffmpeg")
set(DDD_HAS_FFMPEG 0)
set(DDD_FFMPEG_INCLUDE_DIR "")
set(DDD_FFMPEG_LIBS "")

if(EXISTS "${DDD_FFMPEG_ROOT}/include/libavformat/avformat.h")
    set(DDD_FFMPEG_INCLUDE_DIR "${DDD_FFMPEG_ROOT}/include")
    set(DDD_FFMPEG_ALL_LIBS_FOUND TRUE)
    foreach(DDD_FFMPEG_LIB avformat avcodec avutil swscale swresample)
        set(DDD_FFMPEG_LIB_PATH "${DDD_FFMPEG_ROOT}/${ANDROID_ABI}/lib${DDD_FFMPEG_LIB}.so")
        if(EXISTS "${DDD_FFMPEG_LIB_PATH}")
            add_library(ddd_${DDD_FFMPEG_LIB} SHARED IMPORTED)
            set_target_properties(
                ddd_${DDD_FFMPEG_LIB}
                PROPERTIES IMPORTED_LOCATION "${DDD_FFMPEG_LIB_PATH}"
            )
            list(APPEND DDD_FFMPEG_LIBS ddd_${DDD_FFMPEG_LIB})
        else()
            message(STATUS "DDD_NATIVE FFmpeg missing for ${ANDROID_ABI}: lib${DDD_FFMPEG_LIB}.so")
            set(DDD_FFMPEG_ALL_LIBS_FOUND FALSE)
        endif()
    endforeach()
    if(DDD_FFMPEG_ALL_LIBS_FOUND)
        set(DDD_HAS_FFMPEG 1)
        message(STATUS "DDD_NATIVE FFmpeg playback enabled for ${ANDROID_ABI}: ${DDD_FFMPEG_ROOT}")
    endif()
endif()

add_library(ddd_native_player SHARED
    ddd_native_player.cpp
    video/DddAudioOutput.cpp
    video/DddPlaybackSession.cpp
)

target_compile_features(ddd_native_player PRIVATE cxx_std_17)
target_compile_definitions(
    ddd_native_player
    PRIVATE
        DDD_HAS_FFMPEG=${DDD_HAS_FFMPEG}
)
target_include_directories(ddd_native_player PRIVATE ${DDD_FFMPEG_INCLUDE_DIR})

target_link_libraries(ddd_native_player
    android
    log
    dl
    z
    mediandk
    OpenSLES
    ${DDD_FFMPEG_LIBS}
)
