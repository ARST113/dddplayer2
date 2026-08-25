/*
 * engine_probe.cpp — нативный тест шага 3: io_bridge, пробинг, поток демукса.
 *
 * Тот же принцип, что на шаге 2: сначала проверяем движок отдельным бинарём на
 * устройстве, и только потом подключаем JNI и Gradle. Если ошибка видна здесь,
 * её не надо искать через три слоя.
 *
 * Проверяется:
 *   1. самотест `hdr-static-info` (25 байт по CTA-861.3);
 *   2. пробинг по URL средствами FFmpeg (протокол `file`);
 *   3. пробинг через СВОЙ AVIOContext (`FileIoSource`) — тот же путь, которым
 *      пойдут TorrServer, LocalBridgeServer и SMB: результаты обоих способов
 *      обязаны совпасть до последнего поля;
 *   4. поток демукса: чтение пакетов, очереди, статистика;
 *   5. seek через поток демукса и попадание позиции рядом с целью;
 *   6. обратное давление: если пакеты не забирать, память не растёт бесконечно;
 *   7. для остальных переданных файлов — метаданные: HDR10 (25 байт и все
 *      инварианты раскладки), Dolby Vision, HDR10+, стерео, проекции, поворот.
 *
 * Запуск:
 *   ./engine_probe <полный-файл> [<файл-с-метаданными>...]
 *
 * Первым аргументом нужен НЕ обрезанный файл: на обрезанном seek в середину
 * законно не находит данных, и часть проверок пропускается (тест об этом
 * предупреждает).
 */
#include <cinttypes>
#include <cstdio>
#include <cstring>
#include <string>
#include <thread>

#include "../ddd_engine/src/ddd_log.h"
#include "../ddd_engine/src/demux_session.h"
#include "../ddd_engine/src/ff_include.h"
#include "../ddd_engine/src/hdr_static_info.h"
#include "../ddd_engine/src/io_source.h"
#include "../ddd_engine/src/probe.h"

namespace {

int g_failures = 0;
int g_checks = 0;

const char *kOk = "[ ok ]";
const char *kFail = "[ПРОВАЛ]";

void Check(bool cond, const char *what) {
    ++g_checks;
    if (!cond) ++g_failures;
    printf("  %s %s\n", cond ? kOk : kFail, what);
}

std::string Hex(const uint8_t *p, int n) {
    std::string s;
    char buf[3];
    for (int i = 0; i < n; ++i) {
        snprintf(buf, sizeof buf, "%02x", p[i]);
        s += buf;
    }
    return s;
}

void PrintProbe(const ddd::ProbeResult &r, const char *tag) {
    printf("  [%s] контейнер: %s (%s), %.3f с, %" PRId64 " бит/с, seekable=%d\n", tag,
           r.container.format.c_str(), r.container.long_name.c_str(),
           r.container.duration_us / 1e6, r.container.bitrate,
           static_cast<int>(r.container.seekable));

    for (const auto &v : r.video)
        printf("  [%s] видео #%d: %dx%d %s, %.3f fps, SAR %d:%d, %d бит, поворот %d, MIME %s\n",
               tag, v.stream_index, v.width, v.height, v.codec.c_str(), v.frame_rate, v.sar_num,
               v.sar_den, v.bit_depth, v.rotation, v.mime.empty() ? "<нет>" : v.mime.c_str());
    for (const auto &a : r.audio)
        printf("  [%s] аудио #%d: %s%s%s, %d кан., %d Гц, %d бит/с, язык '%s', '%s'%s\n", tag,
               a.stream_index, a.codec.c_str(), a.profile.empty() ? "" : " / ",
               a.profile.c_str(), a.channels, a.sample_rate, a.bitrate, a.language.c_str(),
               a.title.c_str(), a.is_default ? " [default]" : "");
    for (const auto &s : r.subtitle)
        printf("  [%s] субтитры #%d: %s, язык '%s', '%s'%s%s\n", tag, s.stream_index,
               s.codec.c_str(), s.language.c_str(), s.title.c_str(),
               s.is_bitmap ? " [битмап]" : " [текст]", s.is_forced ? " [forced]" : "");

    printf("  [%s] цвет: standard=%d transfer=%d range=%d %d бит, DV=%d, HDR10+=%d\n", tag,
           r.color.color_standard, r.color.color_transfer, r.color.color_range,
           r.color.bit_depth, r.color.dolby_profile, static_cast<int>(r.color.has_hdr10_plus));
    printf("  [%s] hdr-static-info: %s\n", tag,
           r.color.has_static_info ? Hex(r.color.static_info, ddd::kHdrStaticInfoSize).c_str()
                                   : "<пусто>");
    printf("  [%s] стерео=%d, проекция=%d, основные потоки V/A/S = %d/%d/%d\n", tag,
           static_cast<int>(r.stereo), static_cast<int>(r.projection), r.best_video_index,
           r.best_audio_index, r.best_subtitle_index);
}

/** Сравнение двух пробингов: путь через свой AVIO не должен ничего терять. */
void CompareProbes(const ddd::ProbeResult &a, const ddd::ProbeResult &b) {
    Check(a.container.format == b.container.format, "формат совпал");
    Check(a.container.duration_us == b.container.duration_us, "длительность совпала");
    Check(a.video.size() == b.video.size() && a.audio.size() == b.audio.size() &&
              a.subtitle.size() == b.subtitle.size(),
          "число дорожек совпало");
    Check(a.best_video_index == b.best_video_index, "основной видеопоток тот же");
    Check(a.color.color_standard == b.color.color_standard &&
              a.color.color_transfer == b.color.color_transfer &&
              a.color.color_range == b.color.color_range && a.color.bit_depth == b.color.bit_depth,
          "цветовые поля совпали");
    Check(a.color.has_static_info == b.color.has_static_info &&
              std::memcmp(a.color.static_info, b.color.static_info, ddd::kHdrStaticInfoSize) == 0,
          "hdr-static-info совпал побайтово");
    if (!a.video.empty() && !b.video.empty()) {
        Check(a.video[0].width == b.video[0].width && a.video[0].height == b.video[0].height,
              "размер кадра совпал");
        Check(a.video[0].mime == b.video[0].mime, "MIME совпал");
    }
}

/* ─────────────────────────── 1. пробинг по URL ─────────────────────────── */

bool ProbeByUrl(const char *path, ddd::ProbeResult *out) {
    printf("== пробинг по URL (протокол file) ==\n");
    int err = 0;
    ddd::DemuxSession *s = ddd::DemuxSession::Open(path, nullptr, {}, &err);
    Check(s != nullptr, "источник открыт по URL");
    if (s == nullptr) {
        printf("  AVERROR=%d\n", err);
        return false;
    }
    *out = s->probe();
    PrintProbe(*out, "url");
    delete s;
    return true;
}

/* ──────────────────── 2. пробинг через свой AVIOContext ──────────────────── */

bool ProbeByIo(const char *path, ddd::ProbeResult *out) {
    printf("== пробинг через io_bridge (свой AVIOContext) ==\n");
    ddd::FileIoSource *io = ddd::FileIoSource::Open(path);
    Check(io != nullptr, "источник байт создан");
    if (io == nullptr) return false;

    int err = 0;
    // url=nullptr: FFmpeg обязан обойтись без имени файла и определить формат
    // только по содержимому — ровно как при чтении из TorrServer.
    ddd::DemuxSession *s = ddd::DemuxSession::Open(nullptr, io, {}, &err);
    Check(s != nullptr, "контейнер определён без имени файла, по содержимому");
    if (s == nullptr) {
        printf("  AVERROR=%d\n", err);
        return false;
    }
    *out = s->probe();
    PrintProbe(*out, "io");
    Check(out->container.seekable, "AVSEEK_SIZE отработал: источник признан перематываемым");
    delete s;
    return true;
}

/* ───────────────── 3. демукс, очереди, seek, обратное давление ───────────────── */

void TestDemux(const char *path) {
    printf("== поток демукса ==\n");
    ddd::FileIoSource *io = ddd::FileIoSource::Open(path);
    if (io == nullptr) {
        Check(false, "источник байт создан");
        return;
    }
    int err = 0;
    ddd::DemuxSession *s = ddd::DemuxSession::Open(nullptr, io, {}, &err);
    if (s == nullptr) {
        Check(false, "сессия открыта");
        return;
    }

    const int vi = s->probe().best_video_index;
    const int ai = s->probe().best_audio_index;
    Check(s->Start(), "поток демукса запущен");

    // ── вычитываем пакеты: проверяем, что данные реально доходят до очередей
    int video_packets = 0, audio_packets = 0, keyframes = 0;
    for (int i = 0; i < 200; ++i) {
        if (vi >= 0) {
            if (AVPacket *p = s->TakePacket(vi, 500)) {
                if ((p->flags & AV_PKT_FLAG_KEY) != 0) ++keyframes;
                ++video_packets;
                av_packet_free(&p);
            }
        }
        if (ai >= 0) {
            if (AVPacket *p = s->TakePacket(ai, 100)) {
                ++audio_packets;
                av_packet_free(&p);
            }
        }
    }
    printf("  прочитано пакетов: видео %d (ключевых %d), аудио %d, всего демуксом %" PRId64 "\n",
           video_packets, keyframes, audio_packets, s->packets_read());
    Check(vi < 0 || video_packets > 0, "видеопакеты доходят до очереди");
    Check(vi < 0 || keyframes > 0, "среди них есть ключевые кадры");
    Check(ai < 0 || audio_packets > 0, "аудиопакеты доходят до очереди");

    ddd::DemuxSession::Stats st = s->GetStats();
    printf("  статистика: буфер до %" PRId64 " мс, длительность буфера %" PRId64
           " мс, в очередях %" PRId64 " Б / %d пакетов, ошибок %d\n",
           st.buffered_position_ms, st.buffered_duration_ms, st.queued_bytes, st.queued_packets,
           st.read_errors);
    Check(st.read_errors == 0, "ошибок чтения нет");
    Check(st.buffered_position_ms > 0, "позиция буферизации растёт");

    // ── seek: цель — середина файла
    const int64_t duration = s->duration_ms();

    // Обрезанный файл (частичная загрузка, битый torrent-кусок) даёт EOF задолго
    // до заявленной длительности. Тогда seek в середину законно не находит
    // данных, и проверять наполнение очередей бессмысленно — но сам факт
    // выполнения seek проверить всё равно надо.
    const bool truncated = st.eof && duration > 0 && st.buffered_position_ms < duration / 2;
    if (truncated)
        printf("  ВНИМАНИЕ: данные кончились на %" PRId64 " мс из заявленных %" PRId64
               " мс — файл обрезан, проверки данных после seek пропускаются\n",
               st.buffered_position_ms, duration);

    if (duration > 2000) {
        const int64_t target = duration / 2;
        printf("  seek на %" PRId64 " мс (длительность %" PRId64 " мс)\n", target, duration);
        Check(s->Seek(target), "запрос seek принят");

        // Ждём, пока поток демукса выполнит seek и снова наполнит очередь.
        ddd::DemuxSession::Stats after;
        for (int i = 0; i < 200; ++i) {
            after = s->GetStats();
            if (after.seeks == 1 && after.queued_packets > 0) break;
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
        printf("  после seek: seek'ов %d, начало очереди %" PRId64 " мс, прочитано до %" PRId64
               " мс, пакетов %d\n",
               after.seeks, after.queue_start_ms, after.buffered_position_ms,
               after.queued_packets);
        Check(after.seeks == 1, "seek выполнен потоком демукса");

        if (!truncated) {
            Check(after.queued_packets > 0, "после seek очереди снова наполняются");

            // Сравнивать надо НАЧАЛО очереди: позиция чтения к этому моменту уже
            // уехала вперёд на глубину буфера (до 50 с), и по ней точность seek
            // не видна. Именно на начале очереди проявляется забытый `start_time`
            // — ошибка в секунды, а не в кадры. Допуск вниз — расстояние до
            // предыдущего ключевого кадра, вверх — небольшой запас на файлы без
            // индекса.
            const int64_t drift = after.queue_start_ms - target;
            printf("  расхождение начала очереди с целью: %" PRId64 " мс\n", drift);
            Check(after.queue_start_ms >= 0 && drift > -12000 && drift < 3000,
                  "seek встал рядом с целью (по началу очереди)");
            Check(after.buffered_position_ms >= after.queue_start_ms,
                  "позиция чтения не позади начала очереди");
        }
    } else {
        printf("  файл короче 2 с — проверка seek пропущена\n");
    }

    delete s;
}

void TestBackpressure(const char *path) {
    printf("== обратное давление (пакеты не забираем) ==\n");
    ddd::FileIoSource *io = ddd::FileIoSource::Open(path);
    if (io == nullptr) {
        Check(false, "источник байт создан");
        return;
    }
    int err = 0;
    ddd::DemuxSession *s = ddd::DemuxSession::Open(nullptr, io, {}, &err);
    if (s == nullptr) {
        Check(false, "сессия открыта");
        return;
    }
    s->Start();

    // Никто не читает очереди. Через секунду демукс обязан упереться в лимит
    // (50 с буфера по DDD или 96 МБ) и перестать наращивать память.
    std::this_thread::sleep_for(std::chrono::milliseconds(700));
    const ddd::DemuxSession::Stats a = s->GetStats();
    std::this_thread::sleep_for(std::chrono::milliseconds(700));
    const ddd::DemuxSession::Stats b = s->GetStats();

    printf("  очереди: %" PRId64 " Б → %" PRId64 " Б, буфер %" PRId64 " → %" PRId64
           " мс, eof=%d\n",
           a.queued_bytes, b.queued_bytes, a.buffered_duration_ms, b.buffered_duration_ms,
           static_cast<int>(b.eof));
    Check(b.queued_bytes <= ddd::kMaxQueueBytes, "лимит памяти очередей соблюдён");
    // Либо файл кончился (короткий файл), либо буфер перестал расти.
    const bool stabilized = b.eof || b.queued_bytes == a.queued_bytes ||
                            b.buffered_duration_ms >= ddd::kMaxBufferMs;
    Check(stabilized, "чтение остановлено по достижении буфера (или конец файла)");

    delete s;
}

/* ───────────────────────── 4. HDR-метаданные файла ───────────────────────── */

/** Обратный разбор 25 байт: единственный способ увидеть, что записано. */
struct DecodedHdr {
    int eotf;
    int metadata_id;
    int primaries[3][2];  // R, G, B × (x, y), единицы 1/50000
    int white[2];
    int max_luminance;  // кд/м²
    int min_luminance;  // единицы 0.0001 кд/м²
    int max_cll;
    int max_fall;
};

int Get16Le(const uint8_t *p) { return p[0] | (p[1] << 8); }

DecodedHdr DecodeHdr(const uint8_t *b) {
    DecodedHdr d{};
    d.eotf = b[0] & 0x0f;
    d.metadata_id = (b[0] >> 4) & 0x0f;
    for (int i = 0; i < 3; ++i) {
        d.primaries[i][0] = Get16Le(b + 1 + i * 4);
        d.primaries[i][1] = Get16Le(b + 3 + i * 4);
    }
    d.white[0] = Get16Le(b + 13);
    d.white[1] = Get16Le(b + 15);
    d.max_luminance = Get16Le(b + 17);
    d.min_luminance = Get16Le(b + 19);
    d.max_cll = Get16Le(b + 21);
    d.max_fall = Get16Le(b + 23);
    return d;
}

/**
 * Проверка HDR-метаданных файла.
 *
 * Сравнивать 25 байт с одной константой здесь нельзя: у каждого файла свой
 * мастеринг-дисплей. Поэтому проверяются инварианты раскладки CTA-861.3 —
 * и главный из них ловит ту самую ошибку порядка праймериз: в HEVC SEI и в
 * боксе `mdcv` они идут G,B,R, а в дескрипторе обязаны быть R,G,B. У любого
 * реального мастеринг-дисплея (BT.2020, DCI-P3, BT.709) x красного больше x
 * зелёного и x синего — если порядок перепутан, первым окажется зелёный и
 * проверка не пройдёт.
 */
void TestMetadataFile(const char *path) {
    printf("== метаданные: %s ==\n", path);
    ddd::FileIoSource *io = ddd::FileIoSource::Open(path);
    if (io == nullptr) {
        Check(false, "файл открыт");
        return;
    }
    int err = 0;
    ddd::DemuxSession *s = ddd::DemuxSession::Open(nullptr, io, {}, &err);
    if (s == nullptr) {
        Check(false, "файл разобран");
        printf("  AVERROR=%d\n", err);
        return;
    }
    const ddd::ProbeResult &r = s->probe();
    PrintProbe(r, "hdr");

    const bool pq_or_hlg = r.color.color_transfer == 6 || r.color.color_transfer == 7;
    printf("  transfer=%s\n", r.color.color_transfer == 6   ? "ST2084 (PQ)"
                              : r.color.color_transfer == 7 ? "HLG"
                                                            : "не HDR");

    if (r.color.has_static_info) {
        const DecodedHdr d = DecodeHdr(r.color.static_info);
        printf("  разбор: EOTF=%d id=%d\n", d.eotf, d.metadata_id);
        printf("          R (%.4f, %.4f)  G (%.4f, %.4f)  B (%.4f, %.4f)  W (%.4f, %.4f)\n",
               d.primaries[0][0] / 50000.0, d.primaries[0][1] / 50000.0,
               d.primaries[1][0] / 50000.0, d.primaries[1][1] / 50000.0,
               d.primaries[2][0] / 50000.0, d.primaries[2][1] / 50000.0, d.white[0] / 50000.0,
               d.white[1] / 50000.0);
        printf("          мастеринг: max %d кд/м², min %.4f кд/м²; MaxCLL %d, MaxFALL %d\n",
               d.max_luminance, d.min_luminance / 10000.0, d.max_cll, d.max_fall);

        Check(d.metadata_id == 0, "Static Metadata Descriptor ID = 0 (Type 1)");
        // Порядок праймериз: см. комментарий к функции.
        Check(d.primaries[0][0] > d.primaries[1][0] && d.primaries[0][0] > d.primaries[2][0],
              "порядок праймериз R,G,B (x красного наибольший), а не G,B,R");
        Check(d.primaries[1][1] > d.primaries[0][1] && d.primaries[1][1] > d.primaries[2][1],
              "y зелёного наибольший — второй по порядку действительно зелёный");
        Check(d.white[0] > 10000 && d.white[0] < 20000 && d.white[1] > 10000 &&
                  d.white[1] < 20000,
              "белая точка рядом с D65 (0.3127, 0.3290)");
        // Единицы min-люминанса — 0.0001 кд/м². Мастеринг-дисплеи имеют чёрный
        // от 0.0001 до ~0.05 кд/м², то есть 1..500 в этих единицах. Значение
        // порядка десятков тысяч означало бы, что единицы перепутаны с кд/м² —
        // ошибка, которую в Media3 допускает MatroskaExtractor.
        Check(d.min_luminance >= 0 && d.min_luminance < 5000,
              "min-люминанс в единицах 0.0001 кд/м², а не в кд/м²");
        Check(d.max_luminance == 0 || d.max_luminance > d.min_luminance / 10000,
              "max-люминанс больше min");
        Check(d.max_fall == 0 || d.max_cll == 0 || d.max_fall <= d.max_cll,
              "MaxFALL не превышает MaxCLL");

        // Симметрия кодирования: собранный заново из разобранных значений блоб
        // обязан совпасть побайтово. Это ловит рассинхрон между записью и
        // чтением — например, если поменять порядок полей только в одном месте.
        AVMasteringDisplayMetadata m{};
        m.has_primaries = 1;
        m.has_luminance = 1;
        for (int i = 0; i < 3; ++i) {
            m.display_primaries[i][0] = AVRational{d.primaries[i][0], 50000};
            m.display_primaries[i][1] = AVRational{d.primaries[i][1], 50000};
        }
        m.white_point[0] = AVRational{d.white[0], 50000};
        m.white_point[1] = AVRational{d.white[1], 50000};
        m.max_luminance = AVRational{d.max_luminance, 1};
        m.min_luminance = AVRational{d.min_luminance, 10000};
        AVContentLightMetadata l{};
        l.MaxCLL = d.max_cll;
        l.MaxFALL = d.max_fall;

        uint8_t again[ddd::kHdrStaticInfoSize] = {0};
        ddd::BuildHdrStaticInfo(&m, &l, again);
        Check(std::memcmp(again, r.color.static_info, ddd::kHdrStaticInfoSize) == 0,
              "пересборка из разобранных значений даёт те же 25 байт");
    } else {
        // Файл без статических метаданных — не провал сам по себе, но если он
        // размечен как PQ/HLG, тонмаппингу нечем работать, и это надо видеть.
        printf("  статических метаданных нет%s\n",
               pq_or_hlg ? " — при PQ/HLG тонмаппинг пойдёт на значениях по умолчанию" : "");
    }

    if (r.color.dolby_profile > 0) {
        printf("  Dolby Vision: профиль %d, конфигурация в потоке %d%s\n", r.color.dolby_profile,
               r.color.dolby_stream_index,
               r.color.dolby_stream_index != r.best_video_index ? " (enhancement layer)" : "");
        // Профиль 5 хранит цвет в IPT, а не в BT.2020: отдать его в обычный
        // HEVC-декодер как `video/hevc` — это готовая жалоба «зелёное кино».
        // Профили 7 и 8 несут HDR10/HLG-совместимый базовый слой и такой
        // подстановки не боятся. Решение принимается на шаге 5, но видеть
        // разницу нужно уже здесь.
        if (r.color.dolby_profile == 5)
            printf("        профиль 5 (IPT-PQ): в обычный HEVC-путь отдавать нельзя — "
                   "нужен декодер video/dolby-vision либо свой шейдер\n");
        else
            printf("        базовый слой совместим с %s — обычный HEVC-путь допустим\n",
                   r.color.color_transfer == 7 ? "HLG" : "HDR10");
    }
    if (r.color.has_hdr10_plus) printf("  HDR10+: динамические метаданные найдены\n");

    delete s;
}

}  // namespace

int main(int argc, char **argv) {
    printf("ddd_engine, шаг 3: io_bridge + пробинг + демукс\n");
    printf("FFmpeg %s, libavformat %u.%u.%u\n\n", av_version_info(),
           LIBAVFORMAT_VERSION_MAJOR, LIBAVFORMAT_VERSION_MINOR, LIBAVFORMAT_VERSION_MICRO);

    // Логи FFmpeg нужны: без них ошибки демуксера не видны, и «файл не открылся»
    // остаётся без причины.
    av_log_set_level(AV_LOG_WARNING);

    printf("== самотест hdr-static-info ==\n");
    Check(ddd::SelfTestHdrStaticInfo(), "25 байт по CTA-861.3 совпали побайтово");
    printf("\n");

    if (argc < 2) {
        printf("Файл не передан — проверены только самотесты.\n");
        printf("Использование: %s <файл> [<файл-с-метаданными>...]\n", argv[0]);
        printf("\nИтог: проверок %d, провалов %d\n", g_checks, g_failures);
        return g_failures == 0 ? 0 : 1;
    }

    ddd::ProbeResult by_url;
    ddd::ProbeResult by_io;
    const bool url_ok = ProbeByUrl(argv[1], &by_url);
    printf("\n");
    const bool io_ok = ProbeByIo(argv[1], &by_io);
    printf("\n");

    if (url_ok && io_ok) {
        printf("== сравнение двух путей чтения ==\n");
        CompareProbes(by_url, by_io);
        printf("\n");
    }

    TestDemux(argv[1]);
    printf("\n");
    TestBackpressure(argv[1]);
    printf("\n");

    // Остальные файлы проверяются только на метаданные: демукс и seek достаточно
    // прогнать один раз, а вот HDR, Dolby Vision, стерео и проекции — это разные
    // файлы, и каждый из них проверяет свою ветку разбора.
    for (int i = 2; i < argc; ++i) {
        TestMetadataFile(argv[i]);
        printf("\n");
    }
    if (argc < 3) printf("Файлы с HDR-метаданными не переданы — эта часть пропущена.\n\n");

    printf("Итог: проверок %d, провалов %d\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
