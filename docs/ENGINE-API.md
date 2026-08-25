# 4XVR — карта API движка (контракт для нового движка DDD)

Восстановлено из dynsym `libvr4p-movieplayer-lib.so` (1476 экспортов) и
`libvr4p-oculus.so` (494 экспорта). Символы не стриплены → имена классов и
сигнатуры настоящие, не догадки.

**573 JNI-точки** в 17 классах, 3 Java-пакета:

| Пакет | Роль |
|---|---|
| `cn.vr4p.vr4pmovieplayer` | медиа-ядро: воспроизведение, каталог, сеть, картинки |
| `cn.vr4p.oculus4xvrplayerov` | VR-оболочка: рендер, слои, «кинозал», подписка |
| `cn.vr4p.vr4pmobiletvlib` | текстурный мост GL ↔ декодер |

| Класс | JNI | Что делает |
|---|---:|---|
| `MediaFileLib` | 221 | каталог, метаданные, плейлисты, история, шифрование, миниатюры |
| `Vr4pMediaPlayer` | 139 | **ядро воспроизведения** — главный интерес |
| `Main4XActivity` | 72 | VR-рендер, геометрия, субтитры, passthrough |
| `My4XLinkSvr` | 37 | «4XLink» — свой сетевой сервер/клиент |
| `JNIWrapper` | 20 | инициализация, NetBIOS, локи, лог |
| `Vr4pImagePlayer` | 18 | фото/GIF через FFmpeg |
| `MySmbHttpWrapper` | 16 | мост Java-IO → native (SMB/HTTP) |
| `Vr4pMobileTVLib` | 15 | GL-текстуры, refresh rate, YUV→RGB |
| `MyUpnpInputStream` | 7 | UPnP/DLNA поток как native IO |
| `V4Window` | 7 | оверлеи/тосты в VR |
| `MyTest4XVIP` | 6 | подписка/UUID устройства |
| `V4PlayerViewOp` | 5 | форматирование времени/чисел |
| `MediaParamLib` | 4 | сырой доступ к файлу-контейнеру |
| `MediaImageParamLib` | 2 | bitmap ↔ файл |
| `V4SettingActivity` | 2 | настройки |
| `V4PlayerActivity` | 1 | вход в плеер |
| `AllStatisticsData` | 1 | отправка телеметрии (**выбросить**) |

---

## 1. `Vr4pMediaPlayer` — ядро воспроизведения

Это то, что нужно воспроизвести в DDD. Разбор по подсистемам.

### 1.1 Демукс/декод (FFmpeg, native)

```
InitFFmpegData  InitFFmpegDataM3u8  ReleaseFFmpegData
ffmpegBeginThread  ffmpegEndThread  ffmpegStartPause  ffmpegStopOnlyForIO
ffmepgSeekTo  ffmepgSetPlaySpeed  ffmepgGetMediaMaxPos  ffmepgGetPacketDurationUs
ffmpegMediaEnable  ffmpegMediaFinished  ffmpegIsPacketReadEnough
GetVideoDecodedData  GetAudioDecodedData  GetSubTitleDecodedData
HaveDecodeData  ClearUsedVideoPacket  SetDecodeThreadPri
nativeInterruptAll  nativeInterruptLoad
```

Модель: native-поток читает пакеты, декодирует, отдаёт кадры в Java по запросу.
Не ExoPlayer-подобная push-модель, а pull.

### 1.2 Выбор пути декодирования (HW ↔ SW)

```
nativeIsHWPossible  nativeIsVideoFFmpegDecode  nativeForceSetVideoFFmpegDecode
TestSupport10Bit  IsYUV10Bit12BitVideo
GetMediaFormatKey  GetMediaFormatType
GetMediaFormatValueIL  GetMediaFormatValueFl  GetMediaFormatValueS  GetMediaFormatValueBA
```

Ключевой механизм HDR: native собирает **полный набор ключей `MediaFormat`**
(включая `hdr-static-info` через `conv_sidedata_to_shatic_hdr_info`) и передаёт
их в Java по индексу — Java лишь перекладывает в `MediaFormat` для `MediaCodec`.
Так метаданные HDR не теряются между FFmpeg и аппаратным декодером.
Именно этого не хватает DDD.

### 1.3 Цвет и HDR

```
ffmepgGetVideoClrRange  ffmepgGetVideoClrStandard  ffmepgGetVideoClrTransfer
ffmepgGetVideoWidth / Height / Mime
Fill10BitFFmpegTex
ffmpegGetDolbyProfile  ffmpegGetDolbyFrameSidePtr  ffmpegUsingDolbyFrameSideTime
```

`Fill10BitFFmpegTex` — заливка 10-битного кадра прямо в GL-текстуру
(через `libvr4p_convertdatayuv`). SW-путь тоже 10-битный, без деградации до 8 бит.

### 1.4 3D / стерео

```
Is3DMovie  Is3DMvHEVCMp4  Is3DSsifVideo
NeedWaitingMvHevc  IsNeedWaitMvHevcTest  SetMvHevcDecodeData  IsTwoEyeHaveData
```

Полноценный **MV-HEVC** (Apple Spatial Video / 3D Blu-ray-подобный) и **SSIF/MVC**
с раздельными потоками на глаз и синхронизацией.

### 1.5 Аудио (AAudio + SoundTouch)

```
CreateAAudio  PlayAAudio  PauseAAudio  ResetAAudio  ReleaseAAudio  WriteAAudio  IsPlayingAAudio
BuildSonicHandle  ChangeSonicSpeed  ClearSonicHandle
GetDefaultChannelLayout  GetSampleCountInBuf  UpdateVolumeBoost
SetListenPoseAAudio  SetCPPOnlyPlayAudio  SetNeedWaitingAudio
```

AAudio напрямую (низкая задержка), скорость через SoundTouch,
`SetListenPoseAAudio` — спатиализация по позе головы.

### 1.6 Дорожки и субтитры

```
ffmpegGetAudioTrackCount / Name / Title   ffmpegChangeAudioTrack   ffmpegGetCurAudioTrackSel
ffmpegGetSubtitleTrackCount / Name / Title   ffmpegChangeSubtitleTrack
ffmpegGetInSubtitleTrackCount / Name / Title
ffmpegSetSubtitleFile  ffmpegGetSubtitleFile  ffmpegSetSupSubtitleFile  ffmpegClearSupSubtitleFile
ffmpegIsSupSubtitles  ffmpegIsCur3DSubtitle  ForceSubtitlsVerticalFlip
ffmpegGetMaxSubtitleScWidth / ScHeight   SubtitleParseThread   GetSameNameSubtitle
```

Отдельно: внутренние дорожки, внешние файлы, PGS/SUP (битмапные), 3D-субтитры.

### 1.7 Blu-ray

```
ffmepgGetGetBlurayPlayList  ffmpegGetBlurayPlayListCount
ffmpegGetBlurayListShowTxt  ffmpegGetBlurayThisPlayListIdx
```

### 1.8 Passthrough / альфа-канал (14 функций)

```
nativeIsAlphaPassthrough  nativeForceToAlphaPassthrough  nativeGetAlphaPassthroughChannel
nativeIsFilterPassthrough  nativeForceToFilterPassthrough
nativeIsPassthScreenBlack  nativeIsPassthScreenGreenBlue  nativeIsPureColor
nativeGetPassthroughColor  nativeGetPassthroughRange  nativeUpdatePassthroughRange
nativeReComputePassthrough  nativeResetPassthroughToDefault  nativeHaveComputePassthrough
nativeIsNeedPassthroughVideo  nativeIsShowPassthroughVideoUI  nativeSwitchOnOffPassthroughFunc
```

Автоопределение хромакея (чёрный / зелёно-синий фон) с подбором диапазона →
видео сливается с реальным окружением. Отдельная большая фича.

### 1.9 Прочее

```
nativeGetBlackBorder  NeedTestBlackBorder  TestNewBlockBorder  ffmepgIsSureBlackBorder
ffmepgGetVideoBlackBorderInX / InY   nativeNeedFixClipVideo
IsM3u8  nativeGetM3u8DataBuf  nativeModM3u8NameBuf
SetExMovieExactorIO  GetExMovieSampleData  IfHaveExMovieSampleData
nativePrintScreenBuffer  nativePrintScreenVideo
GetCPUPerfLevel  GetVideoFrameRate(F)  Get/SetOrientation  IsAlphaChannel  IsAudioMediaData
CopyByteBuffer  CopyMyBuffer  CopyMyBufferTxt  ReadDataA  GetSizeA
```

Автообрезка чёрных полей — заметная в VR фича (кадр на весь экран без рамок).

---

## 2. `Main4XActivity` — VR-слой (72 JNI)

```
# геометрия и тип видео
SetVideoAndMapType  GetVideoType  GetVideoMapType  SetMovieSize  IsMovieSizeCanPlay
GetWidthDHeight  SetFixVideoSize  IsFixVideoSize  Set/GetVRCurvedScreen
Set/GetCur180IPD  Set/Get3DReverseEye  Set/GetForce3DVideoShow2D  Set/GetCurZoom  UpdateUIZoom

# рендер
InitUpdateTexGL  UninitUpdateTexGL  UpdateMovie  UpdateMvcMovie  GetRenderVideoFrame
SetVideoColor  UpdateVideoImageProp  UpdateContrastEnhancement  UpdateDynamicLight

# passthrough
SetPassthrough  SetPassthroughVideos  IsAllowPassthrough  SetAlphaChannel

# субтитры
BuildSubtitleData  ModifySubtitleData  EndSubtitles  EndAllSubtitles
GetCharsetCode  GetCharsetIndex

# устройство/рантайм
GetDeviceType  GetDeviceName(2)  ForceSetQuestDeviceType  GetCurRefreshRate(F)
IsHandTracking  IsHeadTracking  SettingHandTracking  IsLastUseCtrlOrHand
GetCameraPoseForAudio  Set/GetOrientation  SetScreenSize  SetSystemBuild

# «кинозал» и UI
LoadNewTheatres  RecoveryTheatres  SetMainHall  IsInMainHall
nativeSetPosterFolder  setNativeAssetManager  AddCommonlyUISize

# монетизация — выбросить
GetPurchasePrice  LaunchCheckoutFlowVIP
```

`UpdateMvcMovie` отдельно от `UpdateMovie` — двухтекстурный путь для MVC/MV-HEVC.

---

## 3. `MediaFileLib` — каталог (221 JNI)

Не нужен для движка, но объясняет вес библиотеки. Подсистемы:

- **дерево файлов**: `GetAllRootNode`, `GetChild`, `GetParent`, `SearchAllFile`,
  `SearchChildFile`, `SearchFilter`, `RenameFileNode`, `DeleteFileNodeNative`
- **классификация**: `GetAllBigMovie`, `GetAllVR`, `GetAllBluray`, `GetAllAudio`,
  `GetAllImageNode`, `GetVideoTypeAndMapType`, `RecoveryAutoVideoType`, `IsShortVideo`
- **метаданные**: `GetMediaWidth/Height/FPSValue/VideoCodecName/ResolutionS`,
  `GetMediaOtherInfoString`
- **плейлисты и история**: `NewPlayingList`, `AddThisPlayingListArray`,
  `GetPlayingHistory`, `GetLastPlayPos`, `SetCurPlayPos`, `GetPreNextPlayMedia`,
  `GetRandomPlayMedia`
- **сеть**: `AddLocalNetNode(2)`, `AddMediaServerNetNode`, `NewLocalNetRoot(2)`,
  `GetNetSubPathName`, `ModNetMediaFileSize`, `DropLineNetFile`
- **шифрование**: `EncryptMedia`, `DecryptMedia`, `TestEncryptPin`,
  `GetAllEncryptMedia`, `DeleteAllEncrypt`
- **миниатюры**: `ComputeMediaImage`, `GetMediaImage`, `BuildRoundCorner`,
  `AddDefaultImageShadow`, `SetUserCoverThumbnail`, `SortByImageColorDiffDesc`
- **per-file картинка**: `Set/GetBrightness`, `Contrast`, `Saturation`,
  **`Set/GetHDRBrightness`**, `Is*Default` — настройки хранятся на каждый файл
- **чёрные поля**: `GetBlackBorderInX/InY`, `HaveComputeBlackBorder`
- **Web-UI**: `Set/GetWebUIGuid(Str)`

---

## 4. Сеть: `My4XLinkSvr`, `MySmbHttpWrapper`, `MyUpnpInputStream`

```
My4XLinkSvr:  nativeConnect4XLink  nativeDisconnect4XLink  nativeRelease4XLink
              nativeThread4XLinkRun  Get4XLinkSvrIP/Name  Is4XLinkSvrActivate
              GetMediaServerCount  GetMSURL/Name/Charset/ProtocolType
              Open4XLinkFile  GetFileName/Path/Size/ExtName  IsFileFolder
              GetCreate/Modify/AccessTime  IsPasswordCheckOK  SetDeviceGuid/Name
JNIWrapper:   InitNetBIOS  RunNetBIOS  UninitNetBIOS
              AddHttpURLToBuffer  GetHttpURLFromBuffer  GetHttpURLBufferCount
MySmbHttpWrapper / MyUpnpInputStream: Java открывает поток, native читает через
              индексный мост (OpenIOIndex/ReadBuffer/Skip/CloseIOIndex)
```

**Это прямой аналог серверных функций DDD.** В DDD уже есть TorrServer `/cache`,
LocalBridgeServer и `ParsingDataSource` — та же схема «Java-IO как источник для
native». Значит серверный слой DDD подключается к новому движку тем же приёмом,
без переписывания.

---

## 5. Отображение на DDD

DDD уже имеет `PlaybackBackend` (`prepare/play/pause/seekTo/stop/release` +
`Listener`), реализации `Media3Backend` и `VlcBackend`, оркестратор `PlayerManager`.
Новый движок — третья реализация того же интерфейса.

| Возможность 4XVR | Есть в DDD | Что делать |
|---|---|---|
| FFmpeg-демукс всех форматов | частично (VLC fallback) | native FFmpeg 4.4.3+/7.x |
| HDR-метаданные → `MediaFormat` | только чтение для подписи (`MediaFormatHelper.getHdrInfo`) + DoVi-fallback в `MediaCodecSelector` (`PlayerManager.kt:228`); своего пути метаданных нет | порт `conv_sidedata_to_shatic_hdr_info` |
| 10-битный SW-путь в GL | нет | порт `convertdatayuv` (7 функций) |
| Dolby Vision RPU | нет | RPU из upstream FFmpeg 6.x + свой GL-маппинг |
| MV-HEVC / SSIF | нет | двухтекстурный путь |
| AAudio + SoundTouch | Media3/VLC свои | опционально |
| Blu-ray плейлисты | нет | libbluray |
| Автообрезка чёрных полей | нет | перенести |
| Passthrough/хромакей | нет | отдельная фича, не блокирует |
| Каталог/плейлисты/история | **есть своё** | не трогать |
| Серверные функции (TorrServer/bridge) | **есть своё** | сохранить как есть |

Расширения `PlaybackBackend`, которых требует HDR-путь и которых сейчас нет:

```kotlin
// цвет и HDR
fun getColorStandard(): Int      // AVCOL_SPC → MediaFormat.COLOR_STANDARD_*
fun getColorTransfer(): Int      // AVCOL_TRC → COLOR_TRANSFER_*
fun getColorRange(): Int
fun getHdrStaticInfo(): ByteArray?   // mastering display + content light
fun getDolbyProfile(): Int           // 0 = нет DoVi
fun getDoviRpuForPts(ptsUs: Long): ByteArray?
fun is10Or12Bit(): Boolean
fun isHardwareDecodePossible(): Boolean

// стерео/геометрия
fun getStereoLayout(): StereoLayout  // MONO/SBS/OU/MVHEVC/SSIF
fun getProjection(): Projection      // FLAT/CURVED/180/360/FISHEYE/CUBE

// кадр
fun fill10BitTexture(texId: Int): Boolean
fun getBlackBorders(): Rect?
```

---

## 6. Что нельзя восстановить и не нужно

- `libOVRPlugin.so`, `libovraudio.so`, `libovrplatformloader*.so`,
  `libpxrplatformloader.so`, вендорные `libopenxr_loader_*.so` — чужое проприетарное.
- `libumeng-spy.so`, `libBugly_Native.so` + `AllStatisticsData.SendStatisticsData`
  + `MyTest4XVIP` + `GetPurchasePrice`/`LaunchCheckoutFlowVIP` — телеметрия и
  монетизация. В открытый движок не переносим.
