/*
 * ffmpeg_smoke — проверка собранного FFmpeg на самом устройстве и прототип
 * пробинга для шага 3 (analysis/UNIFIED-ENGINE.md §6).
 *
 * Зачем нативный бинарь, а не JNI-тест: он проверяет ровно сборку и ничего
 * кроме неё. Если libav*_ddd.so не загрузятся линкером Android, не совпадут
 * sonames, не хватит символа или API-уровня — это видно здесь, а не через
 * три слоя Gradle/JNI.
 *
 * Без аргументов: версии, конфигурация, лицензия, число компонентов, наличие
 * ключевых декодеров/демуксеров/протоколов.
 *
 * С аргументом-файлом (или URL): демукс + avformat_find_stream_info + разбор
 * дорожек и цвета. Это уже прототип `EngineTracks` / `EngineColorInfo`:
 * печатается тот самый 25-байтный hdr-static-info в раскладке CTA-861.3,
 * который потом уйдёт в MediaFormat.KEY_HDR_STATIC_INFO.
 *
 * Сборка: native/scripts/build-smoke.sh
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <inttypes.h>

#include <libavutil/avutil.h>
#include <libavutil/channel_layout.h>
#include <libavutil/samplefmt.h>
#include <libavutil/pixdesc.h>
#include <libavutil/dict.h>
#include <libavutil/opt.h>
#include <libavutil/mastering_display_metadata.h>
#include <libavutil/dovi_meta.h>
#include <libavutil/hdr_dynamic_metadata.h>
#include <libavcodec/avcodec.h>
#include <libavcodec/bsf.h>
#include <libavcodec/version.h>
#include <libavformat/avformat.h>
#include <libavfilter/avfilter.h>
#include <libswscale/swscale.h>
#include <libswresample/swresample.h>

#define OK   "[ ok ]"
#define FAIL "[FAIL]"

static int failures = 0;

static void check(int cond, const char *what) {
    printf("  %s %s\n", cond ? OK : FAIL, what);
    if (!cond) failures++;
}

/* ─────────────────────── 1. сборка ─────────────────────── */

static void report_build(void) {
    printf("== сборка ==\n");
    printf("  av_version_info      : %s\n", av_version_info());
    printf("  libavutil            : %d.%d.%d\n", AV_VERSION_MAJOR(avutil_version()),
           AV_VERSION_MINOR(avutil_version()), AV_VERSION_MICRO(avutil_version()));
    printf("  libavcodec           : %d.%d.%d\n", AV_VERSION_MAJOR(avcodec_version()),
           AV_VERSION_MINOR(avcodec_version()), AV_VERSION_MICRO(avcodec_version()));
    printf("  libavformat          : %d.%d.%d\n", AV_VERSION_MAJOR(avformat_version()),
           AV_VERSION_MINOR(avformat_version()), AV_VERSION_MICRO(avformat_version()));
    printf("  libavfilter          : %d.%d.%d\n", AV_VERSION_MAJOR(avfilter_version()),
           AV_VERSION_MINOR(avfilter_version()), AV_VERSION_MICRO(avfilter_version()));
    printf("  libswscale           : %d.%d.%d\n", AV_VERSION_MAJOR(swscale_version()),
           AV_VERSION_MINOR(swscale_version()), AV_VERSION_MICRO(swscale_version()));
    printf("  libswresample        : %d.%d.%d\n", AV_VERSION_MAJOR(swresample_version()),
           AV_VERSION_MINOR(swresample_version()), AV_VERSION_MICRO(swresample_version()));
    printf("  license              : %s\n", avutil_license());
    printf("  configuration        : %s\n", avutil_configuration());
    printf("\n");

    /* LGPL без GPL-компонентов — то же положение, что у 4XVR: код можно
     * распространять закрытым, но мы наоборот открываем; главное — что
     * связывание не тянет GPL-обязательств на приложение. */
    check(strstr(avutil_license(), "LGPL") != NULL, "лицензия LGPL (не GPL)");
    check(strstr(avutil_configuration(), "--enable-gpl") == NULL, "нет --enable-gpl");
    check(strstr(avutil_configuration(), "--enable-neon") != NULL, "NEON включён");
    check(strstr(avutil_configuration(), "--enable-jni") != NULL, "JNI включён");
    check(strstr(avutil_configuration(), "--enable-libxml2") != NULL, "libxml2 (нужен DASH)");
    printf("\n");
}

/* ─────────────────────── 2. компоненты ─────────────────────── */

static void report_components(void) {
    int dec = 0, enc = 0, demux = 0, mux = 0, filt = 0, bsf = 0;
    void *it = NULL;
    const AVCodec *c;
    while ((c = av_codec_iterate(&it)))
        if (av_codec_is_decoder(c)) dec++; else if (av_codec_is_encoder(c)) enc++;

    it = NULL;
    const AVInputFormat *ifmt;
    while ((ifmt = av_demuxer_iterate(&it))) demux++;
    it = NULL;
    const AVOutputFormat *ofmt;
    while ((ofmt = av_muxer_iterate(&it))) mux++;
    it = NULL;
    const AVFilter *f;
    while ((f = av_filter_iterate(&it))) filt++;
    it = NULL;
    const AVBitStreamFilter *b;
    while ((b = av_bsf_iterate(&it))) bsf++;

    printf("== компоненты (рантайм, на устройстве) ==\n");
    printf("  декодеров %d, энкодеров %d, демуксеров %d, муксеров %d, фильтров %d, bsf %d\n",
           dec, enc, demux, mux, filt, bsf);

    /* protocols */
    printf("  протоколы вход : ");
    void *pit = NULL; const char *p;
    while ((p = avio_enum_protocols(&pit, 0))) printf("%s ", p);
    printf("\n\n");

    /* Декодеры «всеформатности»: то, чем VLC берёт, а Media3 — нет.
     * Каждый ищется по имени, как это будет делать движок.
     * Имена — именно имена декодеров, а не кодеков: DTS декодируется `dca`
     * (ff_dca_decoder), имени `dts` в FFmpeg нет вовсе. */
    static const char *decoders[] = {
        /* видео */ "hevc", "h264", "av1", "vp9", "vp8", "vvc", "mpeg2video", "mpeg4",
                    "vc1", "wmv3", "prores", "dnxhd", "ffv1", "theora", "cinepak", "rv40",
        /* аудио */ "aac", "ac3", "eac3", "truehd", "dca", "dolby_e", "mlp", "flac",
                    "alac", "opus", "vorbis", "mp3", "pcm_bluray", "cook", "wmapro",
        /* субтитры */ "subrip", "ass", "webvtt", "mov_text", "pgssub", "dvdsub",
                       "dvbsub", "xsub", "text",
        NULL
    };
    int miss = 0;
    printf("== декодеры (поиск по имени) ==\n  ");
    for (int i = 0; decoders[i]; i++) {
        if (!avcodec_find_decoder_by_name(decoders[i])) { printf("НЕТ:%s ", decoders[i]); miss++; }
    }
    if (!miss) printf("все %d найдены", (int)(sizeof(decoders)/sizeof(*decoders) - 1));
    printf("\n");
    check(miss == 0, "полный набор декодеров плеера");

    /* Демуксеры по их собственным (составным) именам. `vob` в списке нет
     * намеренно: в FFmpeg это только муксер, а VOB-файлы демуксит `mpeg`
     * (MPEG-PS) — он здесь и проверяется. */
    static const char *demuxers[] = {
        "matroska,webm", "mov,mp4,m4a,3gp,3g2,mj2", "mpegts", "mpeg", "hls", "dash",
        "rtsp", "avi", "flv", "asf", "ogg", "wav", "flac", "aac", "mp3", "concat",
        "image2", "mxf", "rm", "webm_dash_manifest", "sdp", "rtp", "srt", "ass",
        NULL
    };
    miss = 0;
    printf("== демуксеры (поиск по имени) ==\n  ");
    for (int i = 0; demuxers[i]; i++) {
        if (!av_find_input_format(demuxers[i])) { printf("НЕТ:%s ", demuxers[i]); miss++; }
    }
    if (!miss) printf("все %d найдены", (int)(sizeof(demuxers)/sizeof(*demuxers) - 1));
    printf("\n");
    check(miss == 0, "полный набор демуксеров плеера");

    /* Протоколы: file обязателен; http/tcp/udp/rtp нужны IPTV и TorrServer.
     * `content` — андроидный протокол из --enable-jni (libavformat/android_content.c):
     * native читает SAF-URI `content://` напрямую, без своего моста.
     * https сознательно отсутствует — TLS делает Java-слой через io_bridge. */
    static const char *protos[] = { "file", "http", "tcp", "udp", "rtp", "crypto",
                                    "data", "pipe", "cache", "concat", "content", NULL };
    miss = 0;
    printf("== протоколы ==\n  ");
    for (int i = 0; protos[i]; i++) {
        char url[64]; snprintf(url, sizeof url, "%s:", protos[i]);
        if (!avio_find_protocol_name(url)) { printf("НЕТ:%s ", protos[i]); miss++; }
    }
    if (!miss) printf("все найдены");
    printf("\n");
    check(miss == 0, "протоколы file/http/tcp/udp/rtp");
    check(avio_find_protocol_name("https:") == NULL,
          "https отсутствует (осознанно: TLS в Java через io_bridge)");

    /* Фильтры, оставленные в сборке. */
    static const char *filters[] = { "aresample", "aformat", "volume", "scale", "format",
                                     "yadif", "bwdif", "idet", "crop", "transpose", "fps", NULL };
    miss = 0;
    for (int i = 0; filters[i]; i++) if (!avfilter_get_by_name(filters[i])) miss++;
    check(miss == 0, "фильтры деинтерлейса/конверсии/аудио");

    /* Кодирования не должно быть вовсе — это плеер. */
    check(enc == 0, "энкодеров нет (--disable-encoders)");
    printf("\n");
}

/* ─────────────────────── 3. пробинг файла ─────────────────────── */

static void put16le(uint8_t *p, int v) {
    if (v < 0) v = 0; if (v > 0xFFFF) v = 0xFFFF;
    p[0] = (uint8_t)(v & 0xFF); p[1] = (uint8_t)((v >> 8) & 0xFF);
}

/*
 * Сборка MediaFormat.KEY_HDR_STATIC_INFO — 25 байт, раскладка CTA-861.3.
 * Ровно это делает 4XVR в conv_sidedata_to_shatic_hdr_info; без этих байт
 * MediaCodec не получает ни maxCLL, ни maxFALL, ни яркость мастеринга,
 * а значит тонмапперу нечем работать (см. UNIFIED-ENGINE.md §2).
 *
 *   [0]      тип = 0
 *   [1..16]  R.x R.y G.x G.y B.x B.y W.x W.y, единицы 0.00002 (то есть ×50000)
 *   [17..18] max display luminance, кд/м²
 *   [19..20] min display luminance, единицы 0.0001 кд/м²
 *   [21..22] maxCLL
 *   [23..24] maxFALL
 * Порядок байт — little-endian.
 *
 * Два места, где легко получить молча неверный цвет:
 *
 * 1. Порядок праймериз. В HEVC SEI mastering_display_colour_volume они идут
 *    G, B, R; FFmpeg при разборе уже нормализует их в R, G, B
 *    (mastering_display_metadata.h: "in r, g, b order"), а CTA-861.3/Android
 *    ждёт тоже R, G, B. То есть здесь нужно прямое копирование — но если брать
 *    значения из SEI напрямую, минуя FFmpeg, порядок надо переставлять.
 * 2. Единицы min-яркости. Здесь 0.0001 кд/м², как требует CTA-861.3.
 *    MatroskaExtractor в ExoPlayer кладёт туда значение в кд/м² как есть,
 *    поэтому типичные 0.005 нит превращаются в 0 — мелкая, но реальная
 *    потеря точности, которой у нас не будет.
 */
static int build_hdr_static_info(const AVMasteringDisplayMetadata *m,
                                 const AVContentLightMetadata *l,
                                 uint8_t out[25]) {
    memset(out, 0, 25);
    int any = 0;
    if (m && m->has_primaries) {
        /* порядок в AVMasteringDisplayMetadata — R,G,B; в CTA-861.3 тот же */
        for (int i = 0; i < 3; i++) {
            put16le(out + 1 + i * 4 + 0, (int)lround(av_q2d(m->display_primaries[i][0]) * 50000));
            put16le(out + 1 + i * 4 + 2, (int)lround(av_q2d(m->display_primaries[i][1]) * 50000));
        }
        put16le(out + 13, (int)lround(av_q2d(m->white_point[0]) * 50000));
        put16le(out + 15, (int)lround(av_q2d(m->white_point[1]) * 50000));
        any = 1;
    }
    if (m && m->has_luminance) {
        put16le(out + 17, (int)lround(av_q2d(m->max_luminance)));
        put16le(out + 19, (int)lround(av_q2d(m->min_luminance) * 10000));
        any = 1;
    }
    if (l) {
        put16le(out + 21, l->MaxCLL);
        put16le(out + 23, l->MaxFALL);
        any = 1;
    }
    return any;
}

static const AVPacketSideData *side(const AVCodecParameters *par, enum AVPacketSideDataType t) {
    return av_packet_side_data_get(par->coded_side_data, par->nb_coded_side_data, t);
}

/*
 * Самотест конверсии на каноническом HDR10: праймериз BT.2020, белая точка D65,
 * мастеринг 1000 / 0.005 кд/м², maxCLL 1000, maxFALL 400 — то же, что в
 * pico-hdr-ffmpeg-lab/samples/hdr10_ffprobe.json.
 *
 * Нужен потому, что арифметика (масштаб 50000, единицы 0.0001, порядок байт и
 * порядок праймериз) — это ровно то, что нельзя проверить глазом по картинке:
 * ошибка здесь даёт «почти правильный» цвет, который списывают на тонмаппинг.
 * Ожидаемые байты посчитаны вручную по CTA-861.3.
 */
static void selftest_hdr_static_info(void) {
    AVMasteringDisplayMetadata m;
    memset(&m, 0, sizeof m);
    m.has_primaries = 1;
    m.has_luminance = 1;
    /* BT.2020: R .708/.292  G .170/.797  B .131/.046 */
    m.display_primaries[0][0] = (AVRational){ 708,  1000 };
    m.display_primaries[0][1] = (AVRational){ 292,  1000 };
    m.display_primaries[1][0] = (AVRational){ 170,  1000 };
    m.display_primaries[1][1] = (AVRational){ 797,  1000 };
    m.display_primaries[2][0] = (AVRational){ 131,  1000 };
    m.display_primaries[2][1] = (AVRational){  46,  1000 };
    m.white_point[0]          = (AVRational){ 3127, 10000 };   /* D65 */
    m.white_point[1]          = (AVRational){ 3290, 10000 };
    m.max_luminance           = (AVRational){ 1000, 1 };
    m.min_luminance           = (AVRational){ 5,    1000 };    /* 0.005 кд/м² */

    AVContentLightMetadata l = { .MaxCLL = 1000, .MaxFALL = 400 };

    uint8_t got[25];
    int any = build_hdr_static_info(&m, &l, got);

    /* 00 | R 35400/14600 | G 8500/39850 | B 6550/2300 | W 15635/16450
     *    | maxLum 1000 | minLum 50 | maxCLL 1000 | maxFALL 400 */
    static const uint8_t want[25] = {
        0x00,
        0x48,0x8a, 0x08,0x39,   /* R.x=0x8A48  R.y=0x3908 */
        0x34,0x21, 0xaa,0x9b,   /* G.x=0x2134  G.y=0x9BAA */
        0x96,0x19, 0xfc,0x08,   /* B.x=0x1996  B.y=0x08FC */
        0x13,0x3d, 0x42,0x40,   /* W.x=0x3D13  W.y=0x4042 */
        0xe8,0x03,              /* max 1000    */
        0x32,0x00,              /* min 50      */
        0xe8,0x03,              /* maxCLL 1000 */
        0x90,0x01               /* maxFALL 400 */
    };

    printf("== самотест hdr-static-info (канонический HDR10) ==\n");
    printf("  получено: "); for (int i = 0; i < 25; i++) printf("%02x", got[i]); printf("\n");
    printf("  ожидание: "); for (int i = 0; i < 25; i++) printf("%02x", want[i]); printf("\n");
    check(any == 1, "метаданные распознаны как непустые");
    int diff = memcmp(got, want, 25);
    if (diff) {
        for (int i = 0; i < 25; i++)
            if (got[i] != want[i]) printf("  байт %2d: %02x вместо %02x\n", i, got[i], want[i]);
    }
    check(diff == 0, "25 байт совпадают побайтово с CTA-861.3");
    printf("\n");
}

static void probe_video(AVStream *st) {
    AVCodecParameters *par = st->codecpar;
    const AVPixFmtDescriptor *d = av_pix_fmt_desc_get(par->format);

    printf("      %dx%d  pix=%s  bits=%d  SAR=%d:%d\n",
           par->width, par->height,
           d ? d->name : "?", d ? d->comp[0].depth : 0,
           st->sample_aspect_ratio.num ? st->sample_aspect_ratio.num : par->sample_aspect_ratio.num,
           st->sample_aspect_ratio.den ? st->sample_aspect_ratio.den : par->sample_aspect_ratio.den);
    printf("      fps=%.3f (avg %.3f)  profile=%d level=%d\n",
           st->r_frame_rate.den ? av_q2d(st->r_frame_rate) : 0.0,
           st->avg_frame_rate.den ? av_q2d(st->avg_frame_rate) : 0.0,
           par->profile, par->level);

    /* ─ цвет: это то, что уходит в MediaFormat как color-standard /
     *   color-transfer / color-range. Media3 их до MediaCodec доносит не всегда. */
    printf("      color: primaries=%s transfer=%s space=%s range=%s chroma=%s\n",
           av_color_primaries_name(par->color_primaries),
           av_color_transfer_name(par->color_trc),
           av_color_space_name(par->color_space),
           av_color_range_name(par->color_range),
           av_chroma_location_name(par->chroma_location));

    int hdr = par->color_trc == AVCOL_TRC_SMPTE2084 || par->color_trc == AVCOL_TRC_ARIB_STD_B67;
    printf("      HDR-передаточная: %s\n",
           par->color_trc == AVCOL_TRC_SMPTE2084 ? "PQ / ST2084 (HDR10)"
           : par->color_trc == AVCOL_TRC_ARIB_STD_B67 ? "HLG"
           : "нет (SDR)");

    /* ─ статические метаданные HDR */
    const AVMasteringDisplayMetadata *md = NULL;
    const AVContentLightMetadata *cll = NULL;
    const AVPacketSideData *sd;

    if ((sd = side(par, AV_PKT_DATA_MASTERING_DISPLAY_METADATA))) {
        md = (const AVMasteringDisplayMetadata *)sd->data;
        printf("      mastering display: primaries=%d luminance=%d",
               md->has_primaries, md->has_luminance);
        if (md->has_luminance)
            printf("  min=%.4f max=%.0f кд/м²", av_q2d(md->min_luminance), av_q2d(md->max_luminance));
        printf("\n");
    }
    if ((sd = side(par, AV_PKT_DATA_CONTENT_LIGHT_LEVEL))) {
        cll = (const AVContentLightMetadata *)sd->data;
        printf("      content light: maxCLL=%u maxFALL=%u\n", cll->MaxCLL, cll->MaxFALL);
    }
    if ((sd = side(par, AV_PKT_DATA_DOVI_CONF))) {
        const AVDOVIDecoderConfigurationRecord *dv =
            (const AVDOVIDecoderConfigurationRecord *)sd->data;
        printf("      Dolby Vision: profile=%d level=%d rpu=%d el=%d bl=%d compat=%d\n",
               dv->dv_profile, dv->dv_level, dv->rpu_present_flag,
               dv->el_present_flag, dv->bl_present_flag, dv->dv_bl_signal_compatibility_id);
    }
    if (side(par, AV_PKT_DATA_STEREO3D))
        printf("      stereo3d side data: есть\n");

    uint8_t hsi[25];
    if (build_hdr_static_info(md, cll, hsi)) {
        printf("      hdr-static-info (25 Б, для MediaFormat): ");
        for (int i = 0; i < 25; i++) printf("%02x", hsi[i]);
        printf("\n");
    } else {
        printf("      hdr-static-info: пусто%s\n",
               hdr ? "  ← ВНИМАНИЕ: transfer PQ/HLG, но метаданных нет" : " (ожидаемо для SDR)");
    }

    /* ─ подсказка о 3D в названии/метаданных: у 4XVR это часть автоопределения */
    AVDictionaryEntry *e = av_dict_get(st->metadata, "stereo_mode", NULL, 0);
    if (e) printf("      matroska stereo_mode: %s\n", e->value);
}

static void probe_audio(AVCodecParameters *par) {
    char ch[128] = "?";
    av_channel_layout_describe(&par->ch_layout, ch, sizeof ch);
    printf("      %s  %d Гц  каналов %d (%s)  %s  bitrate %" PRId64 "\n",
           av_get_sample_fmt_name(par->format) ? av_get_sample_fmt_name(par->format) : "?",
           par->sample_rate, par->ch_layout.nb_channels, ch,
           par->bits_per_raw_sample ? "raw-bits есть" : "", par->bit_rate);
}

static int probe_file(const char *url) {
    printf("== пробинг: %s ==\n", url);

    AVFormatContext *fc = NULL;
    int r = avformat_open_input(&fc, url, NULL, NULL);
    if (r < 0) {
        char e[256]; av_strerror(r, e, sizeof e);
        printf("  %s avformat_open_input: %s\n", FAIL, e);
        failures++;
        return -1;
    }
    printf("  формат   : %s (%s)\n", fc->iformat->name, fc->iformat->long_name);

    r = avformat_find_stream_info(fc, NULL);
    if (r < 0) {
        char e[256]; av_strerror(r, e, sizeof e);
        printf("  %s avformat_find_stream_info: %s\n", FAIL, e);
        avformat_close_input(&fc);
        failures++;
        return -1;
    }
    printf("  длит.    : %.3f с   битрейт %" PRId64 "   потоков %u\n",
           fc->duration > 0 ? fc->duration / (double)AV_TIME_BASE : -1.0,
           fc->bit_rate, fc->nb_streams);
    AVDictionaryEntry *t = av_dict_get(fc->metadata, "title", NULL, 0);
    if (t) printf("  title    : %s\n", t->value);

    int nv = 0, na = 0, ns = 0, nd = 0;
    for (unsigned i = 0; i < fc->nb_streams; i++) {
        AVStream *st = fc->streams[i];
        AVCodecParameters *par = st->codecpar;
        const AVCodec *dec = avcodec_find_decoder(par->codec_id);
        AVDictionaryEntry *lang = av_dict_get(st->metadata, "language", NULL, 0);
        AVDictionaryEntry *title = av_dict_get(st->metadata, "title", NULL, 0);

        printf("  [%u] %-8s %-12s декодер=%-10s lang=%-4s%s%s\n", i,
               av_get_media_type_string(par->codec_type) ?: "?",
               avcodec_get_name(par->codec_id),
               dec ? dec->name : "НЕТ",
               lang ? lang->value : "-",
               title ? "  title=" : "", title ? title->value : "");

        switch (par->codec_type) {
            case AVMEDIA_TYPE_VIDEO:    nv++; probe_video(st); break;
            case AVMEDIA_TYPE_AUDIO:    na++; probe_audio(par); break;
            case AVMEDIA_TYPE_SUBTITLE: ns++; break;
            default:
                /* AVMEDIA_TYPE_DATA / ATTACHMENT: у них декодера и не должно
                 * быть. В MP4 с камеры Pixel таких потоков четыре (метаданные
                 * motion photo, гироскоп). `EngineTracks` обязан их
                 * пропускать, иначе в меню дорожек появятся пустые строки. */
                nd++;
                break;
        }
        /* Отсутствие декодера — проблема только для играбельных типов. */
        if (!dec && (par->codec_type == AVMEDIA_TYPE_VIDEO ||
                     par->codec_type == AVMEDIA_TYPE_AUDIO ||
                     par->codec_type == AVMEDIA_TYPE_SUBTITLE)) {
            printf("      %s нет декодера для %s\n", FAIL, avcodec_get_name(par->codec_id));
            failures++;
        }    }
    printf("  итого: видео %d, аудио %d, субтитры %d, служебных %d\n", nv, na, ns, nd);

    /* Чтение первых пакетов: демукс должен реально работать, а не только
     * отдавать заголовок. */
    AVPacket *pkt = av_packet_alloc();
    int got = 0;
    while (got < 50 && av_read_frame(fc, pkt) >= 0) { got++; av_packet_unref(pkt); }
    av_packet_free(&pkt);
    printf("  прочитано пакетов: %d\n", got);
    check(got > 0, "av_read_frame отдаёт пакеты");
    check(nv > 0, "видеопоток найден");

    /* Seek — проверка, что индексация работает (важно для больших MKV). */
    if (fc->duration > 0) {
        int64_t mid = fc->duration / 2;
        r = av_seek_frame(fc, -1, mid, AVSEEK_FLAG_BACKWARD);
        check(r >= 0, "av_seek_frame на середину");
    }

    avformat_close_input(&fc);
    printf("\n");
    return 0;
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IOLBF, 0);
    av_log_set_level(AV_LOG_ERROR);

    report_build();
    report_components();
    selftest_hdr_static_info();

    for (int i = 1; i < argc; i++) probe_file(argv[i]);

    printf("== итог: %s (%d провалов) ==\n", failures ? "ЕСТЬ ПРОБЛЕМЫ" : "всё сошлось", failures);
    return failures ? 1 : 0;
}
