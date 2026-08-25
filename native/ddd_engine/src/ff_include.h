/*
 * ff_include.h — единственное место, где подключаются заголовки FFmpeg.
 *
 * Заголовки FFmpeg — чистый C и НЕ обёрнуты в `extern "C"` изнутри, поэтому из
 * C++ их обязательно подключать самому в `extern "C"`. Забыть это — получить
 * ошибки линковки на манглированных именах, которые выглядят как «нет символа»,
 * хотя символ есть.
 */
#pragma once

extern "C" {
#include <libavcodec/avcodec.h>
// bsf.h и mathematics.h подключаются явно: с FFmpeg 5 `avcodec.h` больше не
// тянет фильтры битового потока, а `av_rescale_q` живёт в mathematics.h. Без
// первого нет Annex-B для MediaCodec, без второго — пересчёта PTS в мкс.
#include <libavcodec/bsf.h>
#include <libavcodec/jni.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/mathematics.h>
#include <libavutil/channel_layout.h>
#include <libavutil/display.h>
#include <libavutil/dovi_meta.h>
#include <libavutil/error.h>
#include <libavutil/mastering_display_metadata.h>
#include <libavutil/opt.h>
#include <libavutil/pixdesc.h>
#include <libavutil/spherical.h>
#include <libavutil/stereo3d.h>
#include <libswresample/swresample.h>
}
