# Единый движок DDD — архитектура

Решение: **один движок вместо двух**. `Media3Backend` и `VlcBackend` не остаются
рядом с новым — они исчезают, их место занимает единственная реализация.
Ни `PLAYBACK_ENGINE_AUTO`, ни `VLC_FALLBACK`, ни `VLC_ONLY`, ни
`forceVlcForCurrentPlaylistSession`, ни эвристика `isVideoDecoderError` — всё это
существует только потому, что бекендов два.

---

## 1. Почему это не «третий бекенд рядом»

`PlaybackBackend` сегодня описывает 13 методов — воспроизведение и позиция.
Но реально приложение работает с Media3 **напрямую**, минуя интерфейс:

| Место | Что берётся у ExoPlayer | Чем это является для UI |
|---|---|---|
| `PlayerViewModel.kt:312` | `val player: ExoPlayer?` — публичный геттер | точка утечки всего API Media3 в UI |
| `PlayerViewModel.kt:316` | `playerRecreatedEvent: LiveData<ExoPlayer>` | привязка Surface после пересоздания |
| `PlayerViewModel.kt:818` | `p?.playbackState` | запуск/останов `progressUpdater` |
| `PlayerViewModel.kt:880-905` | `mediaItemCount`, `getMediaItemAt`, `currentMediaItemIndex`, `currentPosition` | пересборка плейлиста при смене MIME (HLS-recovery) |
| `PlayerActivity.kt:326` | `currentMediaItem?.localConfiguration?.uri` | результат Activity |
| `PlayerFragment.kt:1116,1118` | `playWhenReady` | пауза при показе диалога |
| `PlayerFragment.kt:1157` | `currentLiveOffset` | индикатор отставания от live |
| `PlayerFragment.kt:1360` | `setVideoSurface(null)` | отвязка поверхности |
| `TrackLogic.kt:16,42,93` | `androidx.media3.common.Tracks`, `TrackGroup`, `Format` | **вся модель дорожек в меню** |
| `VideoQualityOption` | хранит `TrackGroup` + индекс | выбор качества видео |
| `RuntimeFpsDetector.kt:28,95` | `ExoPlayer` | AFR — переключение частоты экрана |
| `PlayerManager.kt:626,692,696,707,774` | `setMediaSources`, `currentMediaItem`, `currentMediaItemIndex`, `playbackState`, `currentTracks` | плейлист и текущая дорожка |

Плюс параллельная ветка для VLC: `getVlcAudioTracks`, `getVlcSelectedAudioTrackId`,
`selectVlcAudioTrackById` и три таких же для субтитров (`PlayerManager.kt:760-765`),
и `getCurrentUri` / `getCurrentWindowIndex` / `getCurrentTitle` /
`getPlaybackStateCompat` — каждый с `when (activeBackend)` на два случая
(`PlayerManager.kt:690-708`).

Вывод: **Media3 — это не бекенд декодирования, это модель данных приложения.**
Поэтому «поставить третью реализацию `PlaybackBackend`» физически не убирает ни
Media3, ни VLC. Единый движок обязан забрать себе плейлист, дорожки, состояние и
цвет — иначе унификация не наступает.

---

## 2. Ограничения гарнитуры — источник «вечной проблемы с HDR»

Проверено живьём на `PA921CMGK6120092G` (Pico A9210, Android 14, платформа `anorak`
— Snapdragon XR2 класса Gen 2), `dumpsys display` / `dumpsys SurfaceFlinger` /
`/vendor/etc/media_codecs_*.xml`:

| Факт с устройства | Значение |
|---|---|
| `hdrCapabilities HdrCapabilities{mSupportedHdrTypes=[], mMaxLuminance=500.0, mMaxAverageLuminance=500.0, mMinLuminance=0.0}` | **система не заявляет ни одного HDR-типа**: ни HDR10, ни HLG, ни DV |
| `supportedColorModes [0]`, `Current color mode: ColorMode::NATIVE (0)` | широкого цветового режима у композитора нет |
| `hdr metadata types=0` на всех слоях | HDR-метаданные до композитора не доходят и не используются |
| `supportedHdrTypes=[]` во **всех 32** режимах экрана | это не режимо-зависимо |
| `ro.surface_flinger.has_HDR_display=true` | противоречит `HdrCapabilities` — проп есть, возможностей нет |
| декодеры: `c2.qti.{avc,hevc,vp9,av1}.decoder` до 8192×8192 | HW-декод 4K/8K есть, и очень быстрый (4K до 240 fps по `performance-point`) |
| **`video/dolby-vision` отсутствует полностью** (нет ни в одном `media_codecs*.xml`, `getprop \| grep -i dolby` пуст) | **аппаратного DV-декодера на гарнитуре нет** |
| экран 4320×2160 (2160×2160 на глаз), 60–90 Гц шагом 1 Гц | AFR имеет смысл и требует точной `frameRate` от движка |

Отсюда прямой вывод, объясняющий, почему HDR в DDD «вечно ломается»:

**На этом устройстве нет системного HDR-конвейера.** Текущая схема DDD —
`MediaCodec → Surface → SurfaceFlinger` — отдаёт PQ/ST2084-сигнал композитору,
который работает в `ColorMode::NATIVE` и HDR-метаданные игнорирует (`hdr metadata
types=0`). PQ-кодированные значения интерпретируются как обычные SDR-значения →
знакомая вымытая, тёмная, низкоконтрастная картинка. Никакая настройка ExoPlayer это
не лечит: тонмаппинга при **воспроизведении** у Media3 нет вовсе (его
`setTonemapAv1ToSdr` и родственные относятся к транскодированию через Transformer).

Значит HDR-метаданные нужны не для того, чтобы «включить HDR-режим экрана» — его нет.
Они нужны как **входные данные тонмаппера**: `maxCLL`, `maxFALL` и яркость
мастеринг-дисплея определяют кривую сжатия в 500 нит панели. Именно поэтому в 4XVR
есть `bsVideoPlayer::GetHDRTextureLab`, `Vr4pMobileTVTLib::InitHDRMapping`,
`GetHDRValue(float)` и per-file регулятор `Set/GetHDRBrightness`, а в
`pico-hdr-ffmpeg-lab` — `shaders/st2084_tonemap.glsl`.

И то же объясняет Dolby Vision: HW-декодера DV на гарнитуре нет, поэтому 4XVR и не
может им пользоваться — он декодирует базовый HEVC-слой и **сам** применяет RPU
(парсер + reshaping + mapping-текстура + шейдер). DDD сейчас подменяет MIME
DoVi→HEVC (`PlayerManager.kt:228`), то есть играет базовый слой и выбрасывает RPU —
отсюда неверные цвета на DV-файлах.

## 3. Почему единый движок обязан быть native

Следствие §2: кадр обязан попадать не в `Surface`, а в **свою текстуру**, потому что
весь HDR делается своим шейдером.

4XVR получает 4K HDR так: FFmpeg парсит side data → native собирает полный набор
ключей `MediaFormat` (`color-standard`, `color-transfer`, `color-range`,
`hdr-static-info` через `conv_sidedata_to_shatic_hdr_info`) → `MediaCodec` декодирует
в 10-битный буфер → `Fill10BitFFmpegTex` заливает его в GL-текстуру → шейдер делает
PQ/HLG→display тонмаппинг с учётом метаданных и пользовательской яркости.

В ExoPlayer `MediaFormat` для `MediaCodec` собирается из `Format`, который создают
**его собственные экстракторы**. Чтобы подать туда данные внешнего FFmpeg-демуксера,
нужна пара «свой `MediaSource` + свой `Renderer`». Написав её, получаешь тот же объём
работы, но внутри push-модели и потоковой дисциплины ExoPlayer, и всё равно отдельно
пишешь GL-слой для тонмаппинга, 10/12 бит и Dolby-маппинга. Выгоды нет — только
лишний слой.

Три вещи, которых у ExoPlayer нет вообще, а у 4XVR есть:

1. **10/12-битный путь до текстуры.** Кадр `P010/P012/P016` заливается в GL-текстуру
   (`RGB10_A2` / `R16_EXT`, 7 функций `libvr4p_convertdatayuv`), без деградации до 8 бит.
   Работает одинаково и для HW-, и для SW-декода.
2. **Свой тонмаппинг PQ/HLG → 500 нит** с `maxCLL`/`maxFALL`/mastering-яркостью и
   регулятором на файл.
3. **Dolby Vision display mapping** и **MV-HEVC / SSIF** (двухтекстурный путь на глаз).

Отсюда следует и решение по выводу: рендерить **всегда через GL**, а не отдавать кадр
прямо в `Surface`. Для SDR это лишняя копия текстуры (на XR2 Gen 2 при 4K приемлемо),
зато тонмаппинг, 3D-раскладки, проекции, зум, обрезка чёрных полей и композитинг
субтитров живут в одном месте — а не двумя разными путями для SDR и HDR. 4XVR
устроен именно так.

«Всеформатность как в VLC» при этом даётся бесплатно: libVLC — это тоже FFmpeg плюс
свои демуксеры. Native FFmpeg-движок покрывает тот же набор, но без второй копии
декодеров в APK (libvlc-all ≈ 30 МБ) — это прямой ответ на «DDD хороший, но тяжёлый».

---

## 4. Слои

```
┌─ Kotlin ─────────────────────────────────────────────────────────────┐
│ PlayerViewModel / PlayerFragment / TrackLogic / RuntimeFpsDetector    │
│        ↕ только PlaybackEngine, ни одного импорта androidx.media3     │
├──────────────────────────────────────────────────────────────────────┤
│ PlayerManager — тонкий: настройки→конфиг, MediaSession,               │
│                 LoudnessEnhancer, IO-фабрика (TorrServer/bridge)      │
├──────────────────────────────────────────────────────────────────────┤
│ DddEngine : PlaybackEngine  — ЕДИНСТВЕННАЯ реализация                 │
│   владеет: плейлистом, дорожками, состоянием, цветом, геометрией      │
├─ JNI (узкий, ~60 точек, не 573) ─────────────────────────────────────┤
│ ddd_engine (C++)                                                      │
│   demux thread → packet queue → decode dispatch                       │
│   HW: AMediaCodec (NDK), полный MediaFormat, вывод в 10-битный буфер  │
│   SW: libavcodec → те же 10/12 бит                                    │
│   ┌ render stage (единственный путь вывода) ──────────────────────┐   │
│   │ YUV P010/P012/P016 → GL-текстура (RGB10_A2 / R16_EXT)         │   │
│   │ tone-map PQ/HLG → 500 нит (maxCLL/maxFALL/mastering + user)   │   │
│   │ DoVi RPU → mapping tex → reshaping в шейдере                  │   │
│   │ 3D-раскладка, проекция, зум, crop чёрных полей, субтитры       │   │
│   │ → EGL на Surface из SurfaceView (плоский) или VR-слой          │   │
│   └───────────────────────────────────────────────────────────────┘   │
│   audio: AAudio + SoundTouch                                          │
│   subtitles: text + PGS/SUP bitmap                                    │
│   io_bridge: Java InputStream ↔ AVIOContext  ← сюда входит DDD-сервер  │
├──────────────────────────────────────────────────────────────────────┤
│ FFmpeg 7.x (LGPL-2.1+) + dav1d + libbluray + OpenSSL + SoundTouch     │
│ пересборка по восстановленному конфигу, NDK 27, без -Os               │
└──────────────────────────────────────────────────────────────────────┘
```

`io_bridge` — тот самый приём «Java-IO как источник для native», которым 4XVR
подключает SMB/UPnP (`MySmbHttpWrapper`, `MyUpnpInputStream`), а DDD — TorrServer
`/cache`, LocalBridgeServer и `ParsingDataSource`. Серверный слой DDD переносится
**без переписывания**: он остаётся Java/OkHttp, движок читает через индексный мост
(`openIo/read/skip/closeIo`). Это и есть механизм сохранения серверных функций.

---

## 5. Контракт `PlaybackEngine`

Расширенная замена `PlaybackBackend`. Всё, что сейчас берётся у ExoPlayer напрямую,
входит в контракт. Ни одного типа `androidx.media3.*`.

```kotlin
interface PlaybackEngine {
    // ── плейлист (было: exoPlayer.setMediaSources / currentMediaItemIndex)
    fun setPlaylist(items: List<MediaItem>, startIndex: Int, startPositionMs: Long)
    fun playIndex(index: Int, startPositionMs: Long = 0L): Boolean
    fun next(): Boolean
    fun previous(): Boolean
    val currentIndex: Int
    val playlistSize: Int
    val currentUri: Uri?
    val currentTitle: String?

    // ── транспорт
    fun play(); fun pause(); fun stop(); fun release()
    fun seekTo(positionMs: Long)
    var playWhenReady: Boolean            // было: exoPlayer.playWhenReady
    var speed: Float
    val positionMs: Long
    val durationMs: Long
    val bufferedPositionMs: Long
    val bufferedPercentage: Int
    val isPlaying: Boolean
    val state: EngineState                // своё, вместо Player.STATE_*
    val liveOffsetMs: Long                // было: exoPlayer.currentLiveOffset

    // ── поверхность
    fun attachSurface(surface: Surface?)          // включает setVideoSurface(null)
    fun attachSurfaceHolder(holder: SurfaceHolder?)

    // ── дорожки (было: exoPlayer.currentTracks + getVlc*Tracks — теперь одна модель)
    val tracks: EngineTracks
    fun selectAudioTrack(id: Int): Boolean
    fun selectSubtitleTrack(id: Int?): Boolean    // null = выключить
    fun selectVideoTrack(id: Int?): Boolean       // null = Auto (ABR)
    fun addExternalSubtitle(item: SubtitleItem): Boolean

    // ── видео: цвет, HDR, геометрия
    val videoFormat: EngineVideoFormat?           // w/h/par/fps/codec/bitDepth
    val colorInfo: EngineColorInfo?               // standard/transfer/range/hdrStaticInfo
    val dolbyProfile: Int                         // 0 = нет DoVi
    val stereoLayout: StereoLayout                // MONO/SBS/OU/MVHEVC/SSIF
    val projection: Projection                    // FLAT/CURVED/180/360/FISHEYE/CUBE
    val blackBorders: Rect?
    val isHardwareDecode: Boolean
    fun forceSoftwareDecode(enabled: Boolean)

    fun setListener(listener: Listener?)

    interface Listener {
        fun onStateChanged(state: EngineState) {}
        fun onPositionChanged(positionMs: Long, durationMs: Long) {}
        fun onVideoSizeChanged(w: Int, h: Int, par: Float) {}
        fun onTracksChanged(tracks: EngineTracks) {}
        fun onColorInfoChanged(info: EngineColorInfo?) {}
        fun onItemTransition(index: Int) {}
        fun onEnded() {}
        fun onError(error: EngineError) {}
    }
}
```

Модель дорожек — единая, без деления «Media3-дорожки» / «VLC-дорожки»:

```kotlin
data class EngineTracks(
    val video: List<EngineVideoTrack> = emptyList(),
    val audio: List<BackendAudioTrack> = emptyList(),      // уже есть в DDD
    val subtitle: List<BackendSubtitleTrack> = emptyList() // уже есть в DDD
)
```

`BackendAudioTrack` / `BackendSubtitleTrack` переиспользуются как есть — они уже
описывают ровно то, что нужно (`id/label/selected/codec/channels/sampleRate/bitrate/
language/description`). `TrackLogic` перенаправляется с `Tracks` на `EngineTracks`;
`VideoQualityOption` перестаёт хранить `TrackGroup` и хранит `EngineVideoTrack.id`.

`EngineState` — свой enum (`IDLE/BUFFERING/READY/ENDED`), чтобы `Player.STATE_*`
не просачивался в ViewModel; `getPlaybackStateCompat()` с его `when (activeBackend)`
исчезает вместе с ним.

---

## 6. Порядок работ и проверка

| # | Шаг | Проверка | Статус |
|---|---|---|---|
| 1 | Контракт: `PlaybackEngine`, `EngineState`, `EngineTracks`, `EngineColorInfo`, `EngineVideoFormat`, `StereoLayout`, `Projection`, `EngineError` | `:app:compileDebugKotlin` | **сделано**, BUILD SUCCESSFUL |
| 2 | Воспроизводимая сборка FFmpeg 7.x по конфигу из `COMPONENT-AUDIT.md` §2, NDK 27, без `-Os` | `libav*.so` под arm64-v8a, версии из `av_version_info` | **сделано**, см. §6.1 |
| 3 | `ddd_engine`: демукс + `io_bridge` + пробинг (метаданные, дорожки, цвет) без декодирования | JNI-тест: открыть файл через `ParsingDataSource`, получить `EngineTracks` и `EngineColorInfo` с непустым `hdrStaticInfo` на HDR10-файле | **сделано**, см. §6.2 |
| 4 | Render stage: EGL на `Surface` из `SurfaceView`, 8-битный SDR через GL | картинка идентична текущей Media3 по цвету и без просадок на 4K | **сделано**, см. §6.3 |
| 5 | 10/12-битный путь: `AMediaCodec` с `COLOR_FormatYUVP010` + переписанные 7 функций `convertdatayuv` → `RGB10_A2`/`R16_EXT` | 10 бит доходят до текстуры (проверка градиента, отсутствие бэндинга) | **сделано и проверено реальным HEVC/P010 на Pixel 6**, см. §6.4 |
| 6 | **Тонмаппинг PQ/HLG → 500 нит** с `maxCLL`/`maxFALL`/mastering-яркостью + регулятор яркости HDR | 4K HDR10 на `PA921CMGK6120092G`: картинка не вымытая — это и есть закрытие исходной проблемы | **математика и GL проверены на Pixel 6**; визуальная приёмка на гарнитуре ещё нужна, см. §6.5 |
| 7 | SW-путь: libavcodec в тот же 10-битный upload (кодеки без HW) | HDR-файл с неподдерживаемым кодеком играет с сохранением 10 бит | **сделано**, `libavcodec:hevc` + P010-equivalent upload проверены на Pixel 6 |
| 8 | Аудио: decode/output, дорожки, скорость, downmix | переключение дорожек, `speed` без изменения тона | **основной путь сделан**: libavcodec + swresample + float AudioTrack, playback-head clock, 0.25–4× с pitch=1; downmix и тест переключения между двумя реальными дорожками ещё нужны |
| 9 | Субтитры: внутренние, внешние, PGS/SUP | все три типа на одном файле | |
| 10 | Dolby Vision: RPU из upstream FFmpeg + reshaping + mapping-текстура | DV profile 5/8 — верные цвета вместо подмены MIME на HEVC | |
| 11 | 3D и проекции: SBS/OU/MV-HEVC/SSIF, 180/360/fisheye/cube, обрезка чёрных полей | по одному файлу на раскладку | |
| 12 | Перевод UI: `TrackLogic`, `RuntimeFpsDetector`, `PlayerFragment`, `PlayerActivity`, `PlayerViewModel` на `PlaybackEngine` | ни одного `androidx.media3.*` вне удаляемого кода | **частично**: `NativePlaybackBackend` подключён в `PlayerManager`, настройка «Нативный DDD», transport/playlist/surface/progress/speed/audio tracks работают; полное удаление прямого Media3 API остаётся |
| 13 | **Удаление**: `Media3Backend`, `VlcBackend`, `libvlc-all`, media3-зависимости, `PLAYBACK_ENGINE_*`, fallback-эвристика | размер APK, отсутствие `libvlc*.so` | |
| 14 | Регресс серверных функций DDD | TorrServer `/cache`, LocalBridge, DDD-sync, HLS/IPTV — по списку функций | |

Шаги 1 и 2 независимы. Шаг 6 — первая точка, где исходная проблема закрыта
и результат виден глазом. Шаг 13 — только после 12.

### 6.1. Шаг 2 — что собрано и чем проверено

Собрано **FFmpeg 7.1.5**, arm64-v8a, NDK 27.0.12077973, целиком из Git Bash под
Windows: ни WSL, ни sudo, ни linux-тулчейна не потребовалось. Скрипты:
`native/scripts/build-ffmpeg.sh`, `build-libxml2.sh`, `pkg-config`.

Результат — шесть библиотек с суффиксом `_ddd` (чтобы не столкнуться с чужими
`libavcodec.so` в процессе):

| Библиотека | Версия | После strip |
|---|---|---|
| `libavcodec_ddd.so` | 61.19.101 | 10.76 МБ |
| `libavformat_ddd.so` | 61.7.103 | 1.98 МБ |
| `libswscale_ddd.so` | 8.3.100 | 0.77 МБ |
| `libavutil_ddd.so` | 59.39.100 | 0.70 МБ |
| `libavfilter_ddd.so` | 10.5.100 | 0.22 МБ |
| `libswresample_ddd.so` | 5.3.100 | 0.10 МБ |
| **итого** | | **14.53 МБ** |

Для сравнения: `libavcodec4x.so` у 4XVR — 11.5 МБ при `-Os` (у нас 10.76 МБ при
`-O3`), а `libvlc-all`, который эта сборка заменяет, — около 30 МБ.
Лицензия — `LGPL version 2.1 or later`, без `--enable-gpl`: та же позиция, что у
4XVR, обязательств GPL на приложение не возникает.

**Проверено запуском на устройстве**, а не только чтением ELF: собран нативный
`native/tests/ffmpeg_smoke.c` (скрипт `build-smoke.sh`) и запущен на Pixel 6
(oriole, Android 16, arm64-v8a) через adb. Гарнитура для этого не нужна:
проверяется сборка, а не HDR-конвейер, а Android 16 строже к выравниванию страниц.

| Что проверено | Результат |
|---|---|
| загрузка линкером Android, совпадение sonames | все шесть грузятся, `NEEDED` совпадает |
| выравнивание LOAD-сегментов (требование Android 15+) | `0x4000` у всех шести |
| компоненты в рантайме | **496 декодеров**, 356 демуксеров, 20 фильтров, 45 bsf |
| энкодеры и муксеры | **0** — это плеер, кодирования нет вовсе |
| декодеры по именам (40 шт.: hevc/av1/vvc/vp9, truehd/dca/dolby_e/mlp, pgssub/dvbsub/…) | все найдены |
| демуксеры по именам (24 шт., включая `dash` и `hls`) | все найдены |
| протоколы | `file http tcp udp rtp crypto data pipe cache concat content` — есть; `https` нет (осознанно) |
| разбор реального файла (HEVC+AAC с камеры) | дорожки, цвет, fps, 50 пакетов, seek на середину |
| HTTP + Range с устройства (форма TorrServer `/cache`) | открытие, прыжок за `moov` в конец файла, seek — без скачивания 180 МБ |
| 25 байт `hdr-static-info` | побайтовое совпадение с CTA-861.3 на каноническом HDR10 |

Последняя строка — не формальность. Арифметика этих 25 байт (масштаб 50000,
единицы min-яркости 0.0001 кд/м², порядок байт, порядок праймериз) — то, что
нельзя проверить глазом: ошибка даёт «почти правильный» цвет, который потом
списывают на тонмаппинг. Два места, где это ломается:

* **порядок праймериз.** В HEVC SEI `mastering_display_colour_volume` они идут
  **G, B, R**; FFmpeg при разборе нормализует их в **R, G, B**, и CTA-861.3
  ждёт тоже R, G, B. То есть от FFmpeg — прямое копирование, но при чтении SEI
  напрямую порядок надо переставлять.
* **единицы min-яркости.** CTA-861.3 требует 0.0001 кд/м². `MatroskaExtractor`
  в ExoPlayer кладёт туда значение в кд/м² как есть, поэтому типичные 0.005 нит
  превращаются в 0.

Отдельно из проверки: протокол `content` присутствует (следствие
`--enable-jni`, `libavformat/android_content.c`) — native умеет читать SAF-URI
`content://` сам, без моста. И: `data`-потоки (в MP4 с камеры Pixel их три —
motion photo, гироскоп) декодера не имеют по определению, `EngineTracks`
обязан их пропускать, иначе в меню дорожек появятся пустые строки.

Технические решения, которые пришлось принять по ходу (детали — в заголовках
скриптов):

* линковка `libavcodec` — это ~1000 объектов в одной команде, а `CreateProcess`
  под Windows ограничен 32 КБ: строка обрезалась посреди имени файла. Решено
  единственным патчем к FFmpeg — `native/patches/0001-link-via-response-file.patch`,
  переводящим правило на response-файл через `$(file >...)` GNU Make 4.x;
* `pkg-config` в системе нет вообще — написан шим `native/scripts/pkg-config`
  (раскрытие `${var}`, рекурсивный `Requires`, всегда `Libs.private`);
* хост-компилятора нет ни одного, а `configure` требует от него C11. В этой
  конфигурации хост-программы не собираются, поэтому `host_cc` = кросс-компилятор.
  Следствие, которое надо помнить: `make check` и `make doc` здесь неприменимы;
* `core.autocrlf=true` в глобальном git приводит дерево FFmpeg в CRLF, и
  `configure` падает на `$'\r'`. В `build-ffmpeg.sh` стоит проверка с готовыми
  командами починки;
* `libxml2` 2.14.6 собран статически (`build-libxml2.sh`, только parser+tree)
  ровно ради `dash_demuxer_deps="libxml2"` — без него DDD потерял бы уже
  работающую поддержку MPD.

Сознательно отложено: **dav1d** (есть нативный `av1`-декодер и HW
`c2.qti.av1.decoder`), **libbluray** (BDMV/ISO), **OpenSSL** — TLS остаётся в
Java-слое DDD и приходит в движок через `io_bridge`; `--disable-mediacodec`
выбран специально, чтобы HW-путь был ровно один — свой драйвер `AMediaCodec`
с полным контролем над `MediaFormat`.

### 6.2. Шаг 3 — что собрано и чем проверено

Собран `libddd_engine.so` — демукс, мост ввода-вывода и пробинг. Декодирования
в нём ещё нет: шаг закрывает вопрос «движок видит файл так же, как FFmpeg, и
отдаёт это в Kotlin без потерь».

| Слой | Файлы | Строк |
|---|---|---|
| native `ddd_engine` | 9 `.cpp` + 10 `.h` + `CMakeLists.txt` | 2884 |
| Kotlin API движка | `engine/*.kt` (10 файлов) | 1230 |
| единственная точка стыка с media3 | `player/DataSourceEngineIo.kt` | 133 |

Разбиение native-части: `io_source` (`AVIOContext` поверх колбэков),
`jni_io_source` (те же колбэки, но в Java), `demux_session` (поток демукса,
`packet_queue` с обратным давлением), `probe` (дорожки, цвет, геометрия),
`hdr_static_info` (сборка 25 байт CTA-861.3), `media_format_map` (FFmpeg →
MIME/`MediaFormat`), `jni_util` (`ExceptionCheck` на каждом вызове,
`thread_local`-детачер), `jni_engine` (`RegisterNatives`).

**Проверено на устройстве**: 11 инструментальных тестов
`top.rootu.dddplayer.engine.NativeProbeTest` на **Pixel 6** (oriole, Android 16)
через `native/scripts/run-engine-tests.sh` — 11 пройдено, 0 провалено,
**0 пропущено**.

| Тест | Что доказывает |
|---|---|
| `selfTestPasses`, `versionReportsFfmpeg` | библиотека грузится, версия — та самая сборка шага 2 |
| `hdr10ThroughDataSourceGivesStaticInfo` | **критерий шага**: файл открыт через `ParsingDataSource`, получены `EngineTracks` и 25 байт `hdrStaticInfo` |
| `ioBridgeMatchesDirectPath` | результат через Java-мост побайтово совпадает с прямым чтением файла |
| `sdrFileHasNoStaticInfo` | обратная сторона: на SDR метаданных нет, а не «нули» |
| `tracksCarryStreamIndices` | индексы потоков переживают дорогу в Kotlin (иначе переключение дорожек ткнёт не туда) |
| `dolbyVisionProfile7FindsEnhancementLayer` | `dvvC` найден на **втором** видеопотоке |
| `sphericalDetectsEquirect360` | проекция из Matroska Projection |
| `demuxFillsBufferAndSeeks`, `backpressureStopsGrowth`, `closeClosesIo` | поток демукса: наполнение, seek, обратное давление, закрытие без утечки |

Проверено отдельно от тестов: в `libddd_engine.so` экспортирован **только**
`JNI_OnLoad` (`llvm-readelf --dyn-syms`), ни одного `Java_*` — как и задумано
при `RegisterNatives`; в APK лежат все шесть `libav*_ddd.so` плюс
`libddd_engine.so`. `libvlc*.so` там пока тоже лежат — их убирает шаг 13.

Медиафайлы для тестов вынесены из репозитория: `native/scripts/fetch-test-media.sh`
качает 12 семплов из **FATE** (официальный набор FFmpeg — эталонное поведение
известно и не зависит от нашей сборки), происхождение и sha256 каждого — в
`test-media/README.md`.

Грабли, которые стоили времени и которые нельзя забыть к шагам 4–14:

* **Kotlin вкладывает блочные комментарии.** `/*` внутри KDoc (в тексте пути
  `<dir>/<abi>/*.so`) открыл вложенный комментарий, `*/` закрыл только его, и
  наружный съел 116 строк `build.gradle.kts` — 341 ошибка «Unexpected symbol»
  в местах, где всё правильно. В Java такого нет.
* **`adb push` создаёт подкаталог `shell:ext_data_rw` с режимом 0770.**
  Приложение — владелец `files/`, но не подкаталога, и войти в него не может:
  файлы просто невидимы. Лечится `chmod 777`, это делает `push-test-media.sh`.
* **`gradle connectedAndroidTest` переустанавливает приложение вокруг прогона**,
  а переустановка стирает `/sdcard/Android/data/<пакет>/`. Залитые до запуска
  файлы исчезают. Поэтому прогон идёт через `am instrument` с явным порядком.
* **`am instrument -w` без `-r` печатает «OK (11 tests)», даже если все 11
  пропущены** по `assumeTrue`. Именно так шаг 3 один раз «прошёл», ничего не
  проверив. `run-engine-tests.sh` считает `STATUS_CODE` по каждому тесту и
  возвращает 2 при любом пропуске: пропущенный тест ничего не доказывает.

Что осталось открытым и куда отнесено:

* **`has_hdr10_plus` на пробинге всегда `false`.** Динамические метаданные
  лежат в SEI/ITU-T T.35 внутри пакетов, а пробинг пакеты не декодирует.
  Определять на шаге 5 (при разборе первых пакетов) и применять на шаге 6.
* **Dolby Vision profile 5 нельзя пускать как `video/hevc`** — это IPT-PQ, а не
  YCbCr, подмена MIME даёт зелёно-розовую картинку. Решение о MIME принимается
  на шаге 5, полный путь — шаг 10.
* Синтетический генератор HDR10-файлов не понадобился: FATE закрыл все нужные
  случаи. Для шага 6 (10-битные PQ-градиенты для проверки бэндинга) есть
  `libavfilter`: `gradients` + `setparams` даёт кадр с нужными `color_trc`
  и `color_primaries` без внешних файлов.

### 6.3. Шаг 4 — render stage: что собрано и чем проверено

Один путь вывода на весь движок. Плоский `SurfaceView`, VR-слой шага 11 и снимок
кадра — это один и тот же код, различающийся только тем, какой `Surface` (или
pbuffer) подставлен под EGL.

| Слой | Файлы | Строк |
|---|---|---|
| EGL-контекст | `egl_context.{h,cpp}` | 274 |
| Сборка шейдеров | `gl_util.{h,cpp}` | 133 |
| Конверсия и геометрия | `video_renderer.{h,cpp}` | 589 |
| Эталон и генератор кадров | `render_reference.{h,cpp}` | 268 |
| Граница JNI | `jni_renderer.{h,cpp}` | 376 |
| Kotlin-обёртка | `engine/NativeRenderer.kt` | 242 |
| Тест | `androidTest/.../NativeRenderTest.kt` | 773 |

Устройство рендерера:

* **`EglContext`** — выбор конфига, контекст ES 3, поверхность окна или pbuffer.
  ES 3, а не ES 2, выбран из-за шага 5: `GL_R16`, `GL_RG16` и `GL_RGB10_A2` в
  ES 2 отсутствуют, и 10-битный путь пришлось бы делать отдельной реализацией.
  Конфиг 8 бит на канал здесь сознательно: HDR-вывод шага 6 потребует
  `EGL_GL_COLORSPACE_BT2020_PQ_EXT` и `EGL_RED_SIZE 10`, и это будет вторая
  ветка выбора конфига, а не правка этой — SDR-путь обязан продолжать работать
  на устройствах без HDR-вывода.
* **`VideoRenderer`** — YUV→RGB в шейдере, `YUV420P`/`NV12`/`NV21`, три матрицы
  (BT.601/709/2020), оба диапазона, поворот 0/90/180/270, SAR, три режима
  вписывания. Заливка кадра и рисование разведены намеренно: на шагах 5–6
  меняется первое, а геометрия и вписывание остаются как есть.
* **`SwscaleReference`** — та же конверсия через swscale, для сверки. Не часть
  воспроизведения: swscale на 4K это ~30 мс на кадр на CPU.
* **`NativeRenderer`** (Kotlin) — 12 нативных методов, регистрация в том же
  `JNI_OnLoad`, что и демуксер.

Почему конверсия своя, а не `swscale` в RGBA с последующей заливкой: 30 мс на
кадр на 4K — заведомый провал даже 24 fps; 10-битный путь шага 5 всё равно
требует шейдера (P010 в RGBA8 не влезает); тонмаппинг шага 6 обязан работать по
яркости **до** матрицы, а не после.

**14 тестов, все на Pixel 6, ноль пропусков** (плюс 11 от шага 3 — итого 25):

| Тест | Что доказывает |
|---|---|
| `offscreenContextIsGles3` | контекст поднимается, и это ES 3, а не ES 2 |
| `releaseIsIdempotent` | владелец не обязан помнить состояние |
| `colorMatchesSwscaleForAllStandards` | GL совпадает с swscale на всех 3 матрицах × 2 диапазонах |
| `wrongStandardIsDetectable` | допуск достаточно тугой, чтобы отличить матрицу |
| `linearChromaAffectsOnlyTileEdges` | линейный фильтр цветности не сдвигает выборку |
| `canonicalValuesMatchStandard` | 16→0, 235→255, красный BT.709 — по ITU-R, посчитано вручную |
| `semiplanarMatchesPlanar` | NV12 и NV21 дают **побитово** то же, что YUV420P |
| `oddDimensionsRender` | 65×33: округление размеров цветности вверх |
| `oversizedStrideIsRespected` | stride 256 при ширине 100, все три формата |
| `fitLetterboxesWideFrame` | FIT даёт поля там, где надо; STRETCH их не даёт |
| `rotationTurnsFrameClockwise` | 0/90/180/270 — по часовой, с проверкой обратного угла |
| `pixelAspectRatioWidensFrame` | анаморфный SAR применяется |
| `windowSurfaceDeliversFrame` | оконный путь: `Surface` → EGL → `eglSwapBuffers` → наружу |
| `uhdFrameFitsInFrameBudget` | 4K влезает в бюджет кадра |

Про первую половину критерия — «идентична Media3 по цвету». Сверяться напрямую с
Media3 бессмысленно: её GL-путь считает те же матрицы по тем же формулам, и
совпадение двух реализаций одной формулы не доказывает ничего. Поэтому эталонов
два: **swscale** (независимая реализация в фиксированной точке — проверяет, что
стандарт прочитан верно) и **значения, посчитанные вручную по ITU-R** (проверяет
раскрытие ограниченного диапазона, самую частую причину блёклой картинки).

Кадры генерирует Kotlin и передаёт **одни и те же массивы** в GL и в эталон. Если
бы кадр генерировали в native для обеих сторон, ошибка генератора вычиталась бы
сама из себя, и тест проходил бы на неверных данных.

Допуск против swscale — **max 4, среднее 1.5 LSB на канал** (фактически на
Mali-G78: max 3, среднее 1.25). Ослаблять его нельзя, и это не вкусовое
соображение: BT.709 и BT.2020 расходятся на 8-битном SDR максимум на 16 LSB —
при больших отсчётах цветности обе матрицы уходят в клиппинг раньше, чем разница
успевает вырасти. Допуск порядка 8 LSB перестал бы их различать, то есть тест
перестал бы проверять, та ли матрица применена. Поэтому `wrongStandardIsDetectable`
задаёт порог не абсолютным числом, а **отношением** к совпадающему случаю
(≥3× по максимуму, ≥2× по среднему): абсолютное расхождение зависит от того,
сколько пикселей кадра ушло в клиппинг, а отношение — нет.

Про вторую половину критерия — «без просадок на 4K»: **6.20 мс на кадр**
(3840×2160, Mali-G78, 30 кадров), 37 % бюджета 60 fps. Замер пессимистичен сразу
с трёх сторон: `glFinish` после каждого кадра запрещает драйверу перекрывать
кадры; поверхность вывода тоже 3840×2160, хотя на телефоне это 1080p, а на
гарнитуре ~2K на глаз; заливка и отрисовка выстроены в очередь, а не идут
параллельно. Кадр генерируется в native и не ходит через JVM — иначе замерялось
бы копирование массивов. **Декодирование 4K к этому замеру не относится, это
шаг 5.**

Как проверялось то, что вообще-то проверить нечем:

* **Цвет без чтения с экрана.** Из `SurfaceFlinger` пиксели не читаются, из
  pbuffer — читаются. Тот же код рисует в оба, потому что `VideoRenderer` не
  знает про EGL и не создаёт контекст: ему нужен лишь текущий контекст в потоке.
* **Оконный путь без экрана.** `ImageReader` даёт настоящий `ANativeWindow` — то
  есть проверяет самое хрупкое в EGL, совместимость конфига с форматом буферов
  окна (`EGL_BAD_MATCH`) — и при этом позволяет прочитать, что в него отдали.
  `SurfaceView` второго не позволяет.
* **Сравнимость с `SWS_POINT`.** При текстурных координатах, растянутых на [0,1],
  и `GL_NEAREST` отсчёт цветности для пикселя x равен
  `floor((x+0.5)/W · W/2) = x >> 1` — ровно то размножение, которое делает
  `SWS_POINT`. Способ интерполяции перестаёт участвовать, и остаётся сравнение
  одной арифметики с другой; сравнивать можно весь кадр, а не только
  внутренности плиток. Проверено и для нечётных размеров: при W=65 и chroma_w=33
  формула даёт тот же `x >> 1` вплоть до последнего столбца.
* **4K без 4K-файла.** Кодировщиков в сборке FFmpeg нет вообще (0 encoders), и
  4K-семпла в FATE нет. Замер идёт на синтетическом кадре 3840×2160,
  сгенерированном в native.

Грабли шага 4:

* **`GL_UNPACK_ROW_LENGTH` задаётся в пикселях, а stride декодера — в байтах.**
  Для двухканальной плоскости NV12 это ровно вдвое меньше. Перепутанный
  коэффициент даёт сдвиг цветности на полкадра — картинка при этом остаётся
  «нормальной» по яркости, и ошибку легко списать на кодек.
* **`glReadPixels` отдаёт строки снизу вверх.** Переворот сделан один раз в
  `NativeReadPixels`, а не у вызывающего: иначе он неизбежно окажется сделан
  дважды в одном месте и ни разу в другом.
* **`mediump` в фрагментном шейдере — это fp16 на многих мобильных GPU.** После
  матрицы конверсии остаётся ~1 LSB шума на 8-битном выходе; на 10-битном пути
  шага 5 fp16 съест ровно те два бита, за которыми вся работа. Поставлен `highp`;
  разницы в скорости нет, узкое место — выборка текстур, а не арифметика.
* **Смена формата кадра обязана сбросить кэш размеров текстур.** Иначе
  `glTexSubImage2D` зальёт данные в текстуру с прошлым internal format, и
  результат — зелёный экран без единой ошибки GL.
* **Неиспользуемый сэмплер всё равно должен быть привязан к полной текстуре.**
  Для NV12 слот V не нужен, но выборка из неполной текстуры в GL не определена, и
  часть драйверов падает даже в неисполняемой ветке шейдера. Заливается 1×1.
* **`CLAMP_TO_EDGE` не по умолчанию.** По умолчанию в GL стоит `GL_REPEAT`, при
  котором правый край кадра подмешивает левый — на градиентах это видно полосой.
* **Порядок разрушения: GL-объекты → контекст → окно.** Обратный порядок удаляет
  текстуры без текущего контекста, то есть тихо течёт видеопамять на каждое
  пересоздание поверхности. `NativeRelease` поэтому делает контекст текущим
  перед `delete`.
* **`ImageReader` без `USAGE_GPU_COLOR_OUTPUT`** (API 29+) на части драйверов
  отвечает на `eglCreateWindowSurface` ошибкой `EGL_BAD_MATCH`: буфер выделен
  только под чтение процессором.
* **Плитка цветности в генераторе задаётся в координатах яркости.** Считать её
  сразу в chroma-координатах — самая простая ошибка здесь: границы плиток
  разъедутся с сеткой 2×2, внутренности перестанут быть однородными, и допуск на
  границу потеряет смысл.

Что осталось открытым и куда отнесено:

* **Кадры пока приходят из Kotlin массивами байт.** Это временно и только для
  шага 4, где источник кадров — тест. На шаге 5 `AMediaCodec` отдаст кадр внутри
  native (буфер декодера → текстура, без JVM), и `nativeUploadFrame` останется
  только для проверок и снимков экрана.
* **Рендерер не заводит своего потока.** EGL-контекст принадлежит одному потоку,
  и на шаге 5 этот поток будет общим с декодером; отдельный поток здесь пришлось
  бы сразу убирать.
* **Тонмаппинг, гамут и PQ/HLG — шаг 6.** Матрица гамута BT.2020→BT.709 войдёт в
  ту же `uColorMatrix`: умножение в шейдере должно остаться одно.

### 6.4. Шаг 5 — стадия декодирования: проверено на реальном кадре

Native-часть шага 5 (`hw_decoder`, `decode_session`, `jni_decoder`) была написана,
но не собиралась ни разу: сборка и JNI-регистрация остались за концом прошлой
сессии. Здесь она впервые прошла компиляцию, линковку и запуск на устройстве.

Найдено и исправлено три дефекта — каждый из них давал бы отказ **в рантайме**, а
не при сборке, и потому заслуживает отдельной записи.

* **`AMediaCodec_getName` не компилировался при minSdk 23.** Функция введена в
  API 28, и NDK 27 помечает её `unavailable`. Обёртка в
  `__builtin_available(android 28, *)` пометку не снимает: проверка версии
  работает в рантайме, а запрет — на этапе компиляции. Лечится
  `__ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__` в `CMakeLists.txt` — тогда символ
  объявлен слабым, и связка «слабый символ + `__builtin_available`» работает как
  задумано (в `.so` он теперь помечен `w`, а не `U`). Поднимать minSdk до 28 было
  нельзя: это отрезало бы Android TV 8.x, ради которых лестница декодеров и
  писалась, — а имя компонента всего лишь диагностика.
* **`RegisterDecoderNatives` не вызывалась.** Функция была объявлена и
  реализована, но `JNI_OnLoad` регистрировал только демукс и рендерер. Собралось
  бы и запустилось молча; отказ пришёл бы `UnsatisfiedLinkError` на первом
  обращении к декодеру.
* **Kotlin-стороны декодера не существовало.** `jni_decoder.cpp` ищет класс
  `top/rootu/dddplayer/engine/NativeVideoDecoder`, которого в проекте не было.
  Написан `NativeVideoDecoder.kt` — 28 нативных методов, подписи сверены с
  `kMethods` побайтово. `NativeRenderer` получил `internal val nativeHandle`:
  `uploadToRenderer` заливает кадр из буфера декодера прямо в текстуры, и на 4K
  это экономит ~12 МБ копирования через JVM на каждый кадр.

Почему расхождение подписей поймано сразу: `RegisterNatives` для всех трёх классов
вызывается в `JNI_OnLoad`, поэтому любая опечатка в дескрипторе роняет
`System.loadLibrary` целиком — на старте, а не через полчаса воспроизведения.

Проверено на Pixel 6 (oriole, Android 16, Mali-G78):

| Что | Результат |
|---|---|
| `:app:assembleDebug` | BUILD SUCCESSFUL, `app-debug.apk` 75,3 МБ |
| `libddd_engine.so` (arm64-v8a, stripped) | 0,33 МБ; экспортирован ровно один символ — `JNI_OnLoad` |
| `AMediaCodec_getName`/`releaseName` в `.so` | слабые (`w`) — на API < 28 ветка просто не исполняется |
| `JNI_OnLoad` | `ddd_engine загружен, ffmpeg n7.1.5` — то есть все три `RegisterNatives` прошли |
| Инструментальные тесты | **33 теста, 0 падений, 0 пропусков** (было 11 из 20 пропущено) |

Грабли, стоившие времени на этом шаге:

* **Установку блокировал `INSTALL_FAILED_VERSION_DOWNGRADE`.** На устройстве
  оставался `top.rootu.dddplayer` с versionCode 900, а debug-сборка берёт версию
  из `git rev-list --count HEAD` и даёт 123. Диагноз важен: это конфликт
  установки, а не отказ кода.
* **Тестовые медиафайлы исчезают при переустановке APK.** Они лежат в
  `/sdcard/Android/data/top.rootu.dddplayer/files/dddtest`, а этот каталог
  принадлежит пакету и стирается вместе с ним. Заливать надо ПОСЛЕ установки,
  иначе 9 probe-тестов молча пропускаются через `assumeTrue` — и прогон выглядит
  зелёным, ничего не проверив. Ровно этот случай и был в отчёте прошлой сессии:
  «11 тестов, 9 пропущено».

После реализации шага 6 добавлены `NativeDecoderTest` и настоящий HDR10-семпл
`hdr10tags-both.mkv`. На Pixel 6 подтверждено:

* SDR: `c2.exynos.h264.decoder`, 854×480, `COLOR_FormatYUV420Flexible`, кадр
  проходит `DataSource → FFmpeg → MediaCodec → uploadToRenderer → GL`;
* HDR10: `c2.exynos.hevc.decoder`, 560×320, запрос и фактический ответ
  `COLOR_FormatYUVP010` (`0x36`), 10-битные коды не схлопнулись в 8 бит;
* реальный stride декодера 1152 байт учтён, P010 MSB-aligned, кадр отрисован с
  HDR-параметрами без копирования через JVM.

То есть шаг 5 закрыт не только сборкой: выполнен тот самый путь
`nextFrame → uploadToRenderer`, которого не хватало в предыдущем отчёте.

### 6.5. Шаг 6 — статический HDR tone mapping

Добавлены `tone_map.{h,cpp}`, параметры HDR в `NativeRenderer`, JNI-эталон на
CPU и эквивалентная ветка в GLES 3 fragment shader:

* точные ST 2084 EOTF/OETF и ARIB STD-B67;
* BT.2390 EETF с тождественным участком ниже колена;
* один коэффициент яркости для трёх каналов — цветность не тянется к серому;
* HLG OOTF с системной гаммой панели;
* BT.2020 → BT.709 в линейном свете;
* mastering peak → maxCLL → 1000 нит как явная цепочка резервов.

`NativeToneMapTest` проверяет известные значения PQ, round-trip, пик 1000→500
нит, неизменность участка ниже колена, сохранение цветности, монотонность HLG и
сравнение настоящего GL-шейдера с CPU-эталоном на пяти уровнях яркости.

Итоговый прогон на Pixel 6 (oriole, Android 16, Mali-G78): **42 теста,
0 падений, 0 пропусков**. 4K: 7,54 мс/кадр для 8 бит и 7,86 мс/кадр для
10-битного `BYTE_PAIR`. Отдельная тестовая сборка ставится рядом с пользовательской
как `top.rootu.dddplayer.engine`, поэтому чужая подпись и данные не затрагиваются.

Шаг 6 математически и аппаратно проверен. Приёмочный критерий таблицы остаётся
визуально подтвердить на целевой гарнитуре `PA921CMGK6120092G`: Pixel доказывает
корректность конвейера, но не яркость/цветовой режим конкретной панели гарнитуры.

### 6.6. Шаги 7–8 — SW fallback, audio и A/V sync

Оба декодера теперь отдают кадр в один и тот же `NativeRenderer`: аппаратный
`AMediaCodec` используется первым, а принудительный/аварийный путь
`libavcodec:hevc` сохраняет 10-битные значения и проходит тот же HDR-шейдер.

Аудиотракт собран как `libavcodec → swresample → float stereo 48 kHz →
AudioTrack`. Видео синхронизируется не по wall clock, а по реальному playback
head AudioTrack; блокирующая запись аудио вынесена в отдельный поток, поэтому не
задерживает видеодекодер. После seek оба декодера и AudioTrack сбрасываются и
получают новую общую временную базу. Скорость 0,25–4× задаётся через
`PlaybackParams` с `pitch=1`.

Проверено на Pixel 6:

* AAC декодируется в конечный float PCM с монотонным PTS;
* аппаратный audio clock движется, пауза его останавливает;
* video ждёт ранний кадр и отбрасывает поздний относительно audio master;
* seek/flush переставляет и audio, и video;
* HW HEVC (`c2.exynos.hevc.decoder`, P010) и SW HEVC (`libavcodec:hevc`) проходят
  один renderer.

### 6.7. Рабочий UI backend и сквозной SurfaceView

`NativePlaybackBackend` подключён к существующему `PlayerManager` и выбирается в
глобальных настройках как **«Нативный DDD»**. В этом режиме ExoPlayer не создаётся.
Работают `SurfaceHolder`, play/pause, seek, playlist/playIndex, позиция/длительность,
буфер, завершение/ошибка, скорость и модель выбранной аудиодорожки. Обычный пакет
приложения не трогался: тестовая сборка имеет суффикс `.engine`.

Сквозной тест запускает настоящий `PlayerActivity` через `ACTION_VIEW`, проходит
`PlayerManager → NativePlaybackBackend → AMediaCodec → GLES`, снимает экран Pixel
и проверяет цветной кадр строго внутри реального `SurfaceView`. Эта проверка нашла
пропущенный `eglSwapBuffers`: кадр рисовался в back buffer, позиция шла, но наружу
не публиковался. После исправления контрольный скриншот показывает кадр Big Buck
Bunny, а тест проходит за 1,132 с.

Все arm64 `.so` теперь совместимы с 16-КБ страницами Android 15/16: FFmpeg,
`libddd_engine.so`, `libc++_shared.so` и LibVLC 3.7.5 имеют `PT_LOAD` alignment
`0x4000`, APK проходит `zipalign -P 16`. Старый LibVLC 3.6.0-eap14 и движок без
`-z,max-page-size=16384` вызывали системное окно совместимости поверх плеера.

Финальный полный прогон на Pixel 6: **50 тестов, 0 падений, 0 пропусков** за
8,699 с. Логи реального прогона подтвердили `c2.exynos.hevc.decoder` с P010,
`c2.exynos.h264.decoder`, `libavcodec:hevc`, `libavcodec:aac`, seek, реальный
оконный кадр и нулевое число ошибок demux.

Не выданы за готовое: рендер внутренних/внешних/PGS-субтитров, Dolby Vision RPU
reshaping, MV-HEVC/SSIF, VR-проекции, downmix и полное удаление старых Media3/VLC
веток. Это следующие отдельные стадии, а не часть уже зелёного core-пути.

---

---

## 7. Честные риски

| Риск | Существо | Что делать |
|---|---|---|
| **ABR для HLS/DASH** | ExoPlayer переключает варианты по полосе; FFmpeg-демуксер играет один вариант | выбор варианта на уровне демукса + свой контроллер полосы; до этого — фиксированный вариант (регресс для IPTV с несколькими качествами) |
| **DRM (Widevine)** | у ExoPlayer из коробки; `c2.qti.*.secure` на устройстве есть | проверить, использует ли DDD DRM вообще; если да — либо отдельный путь, либо осознанный отказ |
| **`MediaSession`** | сейчас построен на `ExoPlayer` как `Player` | адаптер `PlaybackEngine → MediaSession` вручную |
| **Downmix / `ChannelMixingAudioProcessor`** | реализован через `DefaultRenderersFactory` (`PlayerManager.kt`) | матрицы `AudioMixerLogic.createMatrices` переносятся в native-микшер |
| **`LoadControl`** | буферизация 15000/50000/500/5000 | свой контроль очереди пакетов; параметры сохранить один-в-один |
| **Лишняя копия текстуры для SDR** | всегда-GL стоит одну копию кадра | на XR2 Gen 2 при 4K приемлемо (HW-декод 4K до 240 fps по `performance-point`); при просадках — прямой путь в `Surface` как оптимизация, но не как второй бекенд |
| **`COLOR_FormatYUVP010` может не поддерживаться** | XML не перечисляет цветовые форматы, профили отдаёт сам C2-компонент | рантайм-проба (у 4XVR ровно для этого есть `TestSupport10Bit`); при отказе — SW-декод 10 бит |
| **Объём работы** | это не «интеграция», это движок | поэтапно; VLC и Media3 живут до шага 13, приложение работает на каждом коммите |

Порядок специально выбран так, что VLC и Media3 живут до последнего шага —
приложение остаётся рабочим на каждом коммите, а «один бекенд» наступает разом,
когда движок закрыл всю поверхность из §1.
