# 4XVR 1.10.2 (OVRport 3.2.2) — аудит native-компонентов

Источник: `PICO_4XVR_Video_Player_1.10.2_OVRport_3.2.2.apk` (344 MB, 1637 файлов).
Единственный ABI: `arm64-v8a`, 29 библиотек. Всё проверено живьём
(`llvm-readelf` из NDK 27.0.12077973, строки/dynsym), не по памяти.

Цель аудита: понять, что в 4XVR **уже открыто** (и значит пересобирается из upstream),
что закрыто **чужое** (нельзя открыть), и что закрыто **своё** (нужно восстанавливать).

---

## 1. Главный вывод

**Никакого «закрытого HDR-кода» в FFmpeg-слое 4XVR нет.** `libav*4x` — это чистый
upstream FFmpeg 4.4.3 под LGPL-2.1+, собранный с `--build_suffix=4x` (отсюда `4x` в
именах). Из бинаря восстановлена точная строка конфигурации, сборка воспроизводима
1:1 из исходников ffmpeg.org.

Настоящий «секрет» 4XVR лежит не в FFmpeg, а в **собственном движке** —
`libvr4p-oculus.so` + `libvr4p-movieplayer-lib.so`. Там свой парсер Dolby Vision RPU,
свой цветовой конвейер, 10/12-битные текстуры и VR-геометрия. Символы **не стриплены**
(C++ mangling с полными сигнатурами), поэтому API восстанавливается почти дословно.

---

## 2. Точная конфигурация FFmpeg (восстановлена из `libavutil4x.so`)

```
--prefix=/ffmpeg_443v_4x/ffmpeg-arm8/android/arm64-v8a
--enable-shared --disable-static
--disable-doc --disable-debug --disable-programs
--disable-ffmpeg --disable-ffplay --disable-ffprobe --disable-avdevice
--enable-libdav1d --enable-openssl
--disable-symver --disable-stripping
--build_suffix=4x
--enable-asm --enable-neon
--cross-prefix=/ndk-r21/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android23-
--target-os=android --arch=aarch64 --enable-cross-compile
--sysroot=/ndk-r21/toolchains/llvm/prebuilt/linux-x86_64/sysroot
--extra-libs='-lgcc -ldav1d'
--extra-cflags='-Os -fpic -I/dav1d '
--extra-ldflags=' -march=armv8-a -mtune=cortex-a55 -L/dav1d/arm8'
```

Что это даёт:

| Факт | Значение для нас |
|---|---|
| `/ffmpeg_443v_4x` | версия **FFmpeg 4.4.3** |
| Lavc `58.134.100`, Lavf `58.76.100` | подтверждает ветку 4.4 |
| `--build_suffix=4x` | суффикс имён, не форк |
| лицензия во всех 5 либах | `LGPL version 2.1 or later` — **`--enable-gpl` не включён** |
| `-mtune=cortex-a55` | тюнинг под Snapdragon XR2 (Pico 4 / Quest 2-3) |
| `-Os` | собрано на размер, не на скорость → **есть запас производительности** |
| NDK **r21**, API 23 | старый тулчейн; пересборка на NDK 27 легальна и выгодна |

`--disable-avdevice` и отсутствие `--enable-gpl` означают: никакого x264/x265,
только декодирование. Для плеера этого достаточно, и LGPL остаётся чистой.

---

## 3. Полная таблица 29 библиотек

### 3.1 Открытое — пересобирается из upstream (10 шт.)

| Библиотека | Что это | Лицензия | Статус |
|---|---|---|---|
| `libavcodec4x.so` (11.5 MB) | FFmpeg 4.4.3 | LGPL-2.1+ | пересобрать |
| `libavformat4x.so` (2.2 MB) | FFmpeg 4.4.3, с TLS через OpenSSL | LGPL-2.1+ | пересобрать |
| `libavfilter4x.so` (3.1 MB) | FFmpeg 4.4.3 | LGPL-2.1+ | пересобрать |
| `libavutil4x.so` (457 KB) | FFmpeg 4.4.3 | LGPL-2.1+ | пересобрать |
| `libswscale4x.so` (468 KB) | FFmpeg 4.4.3 | LGPL-2.1+ | пересобрать |
| `libswresample4x.so` (83 KB) | FFmpeg 4.4.3 | LGPL-2.1+ | пересобрать |
| `libdav1d.so` (694 KB) | AV1-декодер VideoLAN | BSD-2 | пересобрать |
| `libbluray.so` (244 KB) | VideoLAN, пути `libbluray/bdnav/*.c` в бинаре | LGPL-2.1+ | пересобрать |
| `libssl.so` / `libcrypto.so` (5.2 MB) | **OpenSSL 3.3.0-dev** | Apache-2.0 | пересобрать |
| `libsoundspeedx-lib.so` (264 KB) | обёртка вокруг **SoundTouch** (`soundtouch::SoundTouch`) | LGPL-2.1 | пересобрать |
| `libv4png-lib.so` (285 KB) | **libpng 1.6.40** + zlib | libpng/zlib | пересобрать |

`libbluray` в списке — значит 4XVR реально играет BDMV/ISO с плейлистами
(подтверждается JNI: `ffmpegGetBlurayPlayListCount`, `ffmpegGetBlurayListShowTxt`).

### 3.2 Закрытое чужое — открыть невозможно, только заменить (9 шт.)

| Библиотека | Владелец | Замена |
|---|---|---|
| `libOVRPlugin.so` (4.1 MB) | Meta | не нужна: это Unity-плагин, у 4XVR native-путь |
| `libovrplatformloader.so` + `_meta` + `_meta_q1` (2.4 MB) | Meta Platform SDK (entitlement/IAP) | **выбросить** — это лицензии/покупки |
| `libovraudio.so` (3.0 MB) | Meta Audio SDK (спатиализация) | Resonance Audio / Steam Audio (Apache-2.0) |
| `libopenxr_loader.so` (108 KB) | Khronos | **уже открыт** (Apache-2.0), пересобрать |
| `libopenxr_loader_meta.so` (427 KB) | Meta runtime loader | берётся из SDK вендора |
| `libopenxr_loader_pico.so` (1.6 MB) | Pico runtime loader | берётся из SDK вендора |
| `libopenxr_loader_yvr.so` (14.9 MB) | YVR runtime loader | берётся из SDK вендора |
| `libpxrplatformloader.so` (252 KB) | Pico Platform SDK | **выбросить** |

Вендорные loader'ы — это не «код плеера», а мост к рантайму гарнитуры.
Их не открывают, их подключают. Строка в `libopenxr_loader.so`:
`OpenXR! (overport by crx, version %s)` — след того самого OVRport.

### 3.3 Закрытое чужое телеметрийное — выбросить безусловно (2 шт.)

| Библиотека | Что делает |
|---|---|
| `libumeng-spy.so` (240 KB) | Umeng (Alibaba) — аналитика, `utdid`-трекинг устройства |
| `libBugly_Native.so` (195 KB) | Tencent Bugly — крашрепортинг с отправкой на серверы Tencent |

Обе проприетарные, обе шлют данные наружу. В открытом движке для DDD им места нет —
это и лицензионная, и приватная проблема. Заменяются на локальный лог + опциональный
self-hosted crash-dump.

### 3.4 Закрытое своё — источник задачи (3 шт. + 2 шима)

| Библиотека | Размер | Содержимое | Символы |
|---|---|---|---|
| `libvr4p-oculus.so` | 9.4 MB | VR-рендерер: OpenXR-слои, геометрия, шейдеры, Dolby-маппинг, субтитры, «кинозалы» | 494 экспорта (80 JNI + 399 C++) |
| `libvr4p-movieplayer-lib.so` | 1.0 MB | Медиа-ядро: FFmpeg-обвязка, MediaCodec, AAudio, каталог, SMB/UPnP/NetBIOS, плейлисты, шифрование | 1476 экспортов (478 JNI + 998 C++) |
| `libvr4p_convertdatayuv.so` | 10 KB | 7 функций конвертации 10/12/16-бит YUV → текстуры | см. ниже |
| `liberrno-lib.so` | 5.8 KB | шим errno | тривиально |
| `libusb-lib.so` | 5.8 KB | шим USB | тривиально |

**Движок — рукописный C++, без Unity/Unreal/IL2CPP** (проверено: ни одного маркера
движка в dynsym). Пакеты: `cn.vr4p.oculus4xvrplayerov`, `cn.vr4p.vr4pmovieplayer`,
`cn.vr4p.vr4pmobiletvlib`.

Весь `libvr4p_convertdatayuv.so` (это и есть 10-битный путь):

```cpp
void ConvertData_YUV444P10LE_10Bit  (uint32_t*, int, int, uint8_t**, int*);
void ConvertData_YUV444P12LE_10Bit  (uint32_t*, int, int, uint8_t**, int*);
void ConvertData_YUV444P16LE_10Bit  (uint32_t*, int, int, uint8_t**, int*);
void ConvertData_YUV420_422P10LE_10Bit(uint32_t*, int, int, uint8_t**, int*, bool);
void ConvertData_YUV420_422P12LE_10Bit(uint32_t*, int, int, uint8_t**, int*, bool);
void ConvertData_YUV420_422P16LE_10Bit(uint32_t*, int, int, uint8_t**, int*, bool);
void ConvertData_YUVLE_Support_R16_EXT(uint16_t*, int, int, int, int, uint8_t**, int*, int);
```

10 KB на 7 функций — это NEON-упаковка планаров в `RGB10_A2` / `R16_EXT`.
Восстанавливается полностью и честно переписывается с нуля: контракт очевиден из
сигнатур, а сама операция — арифметика сдвигов.

---

## 4. Dolby Vision: где именно «крутой HDR»

Самое ценное открытие. В `libavutil4x` есть только `av_dovi_alloc` (FFmpeg 4.4 умеет
лишь читать `AVDOVIDecoderConfigurationRecord` из контейнера) — **парсера RPU в
FFmpeg 4.4 нет**. Но в движке есть свой:

```
v4BitRead::get_ue_coef(const AVDOVIRpuDataHeader*)
v4BitRead::get_se_coef(const AVDOVIRpuDataHeader*)
ff_dovi_guess_profile_hevc(const AVDOVIRpuDataHeader*)
FFmpegMedia::CheckDolbyPak(AVPacket*)  /  CheckDolby(uint8_t*, int, long)
FFmpegMedia::GetDolbyProfile()  /  GetDoviFrameSide(long)  /  UsingDolbyFrameSideTime(long)
BuildMMR(double, double, double, int, double, double(*)[7])
GetImageDataMMR(vector<float>&, AVDOVIReshapingCurve&, int)
GetImageDataPolynomial(vector<float>&, AVDOVIReshapingCurve&, int, int)
bsDolbyMappingTex::UpdateTexFromMapping(AVDOVIDataMapping*, int, int)
bsDoviData / bsDoviFrameSide  (кэш RPU по PTS: unordered_map<long, bsDoviFrameSide*>)
```

Типы `AVDOVIRpuDataHeader`, `AVDOVIDataMapping`, `AVDOVIReshapingCurve` — это имена из
**FFmpeg 5.0+/6.x** (`libavutil/dovi_meta.h`, `libavcodec/dovi_rpu.c`), которых в 4.4
не существует. То есть автор портировал в движок LGPL-код парсера RPU из более новой
FFmpeg и добавил свой GL-слой: полиномиальное и MMR-перешейпирование → mapping-текстура
→ шейдер.

**Вывод: этот блок открывается легально и без реверса.** Парсер RPU берётся из
upstream FFmpeg 6.x/7.x (LGPL-2.1+), а `BuildMMR` / `GetImageData*` /
`UpdateTexFromMapping` пишутся заново по спецификации Dolby Vision reshaping —
математика описана в открытых источниках, а сигнатуры у нас есть точные.

Дополнительно найдено: `conv_sidedata_to_shatic_hdr_info(vector<uint8_t>&, AVStream*)`
— сборка `hdr-static-info` для `MediaFormat` из FFmpeg side data (опечатка `shatic`
авторская). `bsVideoPlayer::GetHDRTextureLab(...)`, `Vr4pMobileTVTLib::InitHDRMapping()`,
`GetHDRValue(float)`, `GetDolbyTag(int, long)`, регулировка `Set/GetHDRBrightness`.

---

## 5. Что делать с каждым классом кода

| Класс | Кол-во | Как открывать |
|---|---|---|
| Upstream OSS (FFmpeg, dav1d, bluray, OpenSSL, SoundTouch, libpng, OpenXR loader) | 11 | пересобрать из исходников по восстановленному конфигу. Работы ноль, только CI |
| Свой тонкий native (convertdatayuv, errno, usb шимы) | 3 | переписать с нуля по сигнатурам — контракт полный |
| Свой Dolby Vision RPU + reshaping | ~15 функций | RPU из upstream FFmpeg 6.x + свой GL-слой по сигнатурам |
| Свой цветовой конвейер и VR-геометрия | ~380 C++ функций в `libvr4p-oculus` | восстановление по dynsym + уже извлечённые GLSL-блоки (`analysis/glsl/`) |
| Своё медиа-ядро | ~1000 C++ функций в `movieplayer-lib` | восстановление по 573 JNI-точкам (см. `ENGINE-API.md`) |
| Проприетарное чужое (Meta/Pico/YVR loader'ы, ovraudio, platformloader) | 9 | не открывать: подключать SDK или заменять на OSS-аналог |
| Телеметрия (Umeng, Bugly) | 2 | **удалить** |

Честная оценка: «декомпилировать 9.4 MB C++ в собираемые исходники» — нереально и не
нужно. Реально и достаточно — восстановить **контракт** (он у нас уже есть, символы не
стриплены) и реализовать его открыто, взяв LGPL-части из upstream. Это даёт тот же
результат по HDR/4K, но кодом, который можно читать, собирать и менять.

---

## 6. Проверяемые артефакты этого аудита

| Файл | Что внутри |
|---|---|
| `analysis/libvr4p-movieplayer-lib.exports.txt` | 1476 экспортов |
| `analysis/libvr4p-oculus.exports.txt` | 494 экспорта |
| `analysis/libvr4p_convertdatayuv.exports.txt` | 7 экспортов |
| `analysis/*.strings.txt` | строковые дампы 7 библиотек |
| `analysis/glsl/` | извлечённые GLSL-блоки |
| `4xvr_apk_extract/lib/arm64-v8a/` | все 29 `.so` (доизвлечены, было 11) |

Команда воспроизведения инвентаря:

```bash
unzip -l PICO_4XVR_Video_Player_1.10.2_OVRport_3.2.2.apk | grep '\.so$'
llvm-readelf -d lib/arm64-v8a/<lib>.so | grep -E 'SONAME|NEEDED'
llvm-readelf --dyn-syms lib/arm64-v8a/<lib>.so
```
