package top.rootu.dddplayer.engine

import android.graphics.Rect
import android.net.Uri
import android.view.Surface
import android.view.SurfaceHolder
import top.rootu.dddplayer.model.MediaItem
import top.rootu.dddplayer.model.SubtitleItem

/**
 * Единый движок воспроизведения DDD.
 *
 * Это **единственная** абстракция плеера в приложении: она заменяет и
 * `PlaybackBackend` + `Media3Backend` + `VlcBackend`, и прямые обращения UI к
 * `ExoPlayer` (`PlayerViewModel.player`, `TrackLogic`, `RuntimeFpsDetector`,
 * `PlayerFragment`, `PlayerActivity`). Поэтому контракт шире, чем «play/pause/seek»:
 * движок владеет плейлистом, дорожками, состоянием, цветом и геометрией.
 *
 * Ни одного типа `androidx.media3.*` в сигнатурах — это условие того, что бекенд
 * действительно один, а не спрятан за фасадом.
 *
 * Все методы вызываются с main-потока; колбэки [Listener] приходят на main-поток.
 * См. `analysis/UNIFIED-ENGINE.md`.
 */
interface PlaybackEngine {

    // ───────────────────────── плейлист ─────────────────────────
    // Было: exoPlayer.setMediaSources / currentMediaItemIndex / currentMediaItem
    // + отдельная ветка currentPlaylistItems[currentWindowIndex] для VLC.

    /** Загрузить плейлист и начать с [startIndex] с позиции [startPositionMs]. */
    fun setPlaylist(items: List<MediaItem>, startIndex: Int = 0, startPositionMs: Long = 0L)

    /** Перейти к элементу. `false` — индекс вне плейлиста. */
    fun playIndex(index: Int, startPositionMs: Long = 0L): Boolean

    fun next(): Boolean
    fun previous(): Boolean

    val currentIndex: Int
    val playlistSize: Int
    val currentItem: MediaItem?
    val currentUri: Uri?
    val currentTitle: String?

    val hasNext: Boolean get() = currentIndex < playlistSize - 1
    val hasPrevious: Boolean get() = currentIndex > 0

    // ───────────────────────── транспорт ─────────────────────────

    fun play()
    fun pause()
    fun stop()
    fun release()
    fun seekTo(positionMs: Long)

    /** Было: `exoPlayer.playWhenReady` (`PlayerFragment.kt:1116,1118`). */
    var playWhenReady: Boolean

    /** Скорость с сохранением тона (SoundTouch на аудио-пути). */
    var speed: Float

    val positionMs: Long
    val durationMs: Long
    val bufferedPositionMs: Long
    val bufferedPercentage: Int
    val isPlaying: Boolean

    /** Было: `exoPlayer.playbackState` (`PlayerViewModel.kt:818`, `PlayerManager.kt:707`). */
    val state: EngineState

    /** Отставание от края live-трансляции; 0 для не-live. Было: `currentLiveOffset`. */
    val liveOffsetMs: Long

    val isLive: Boolean get() = durationMs <= 0L

    // ───────────────────────── поверхность ─────────────────────────

    /** `null` отвязывает поверхность. Было: `exoPlayer.setVideoSurface(null)`. */
    fun attachSurface(surface: Surface?)

    fun attachSurfaceHolder(holder: SurfaceHolder?)

    // ───────────────────────── дорожки ─────────────────────────

    val tracks: EngineTracks

    fun selectAudioTrack(id: Int): Boolean

    /** [id] `null` — выключить субтитры. */
    fun selectSubtitleTrack(id: Int?): Boolean

    /** [id] `null` — вернуть адаптивный выбор качества (`Auto`). */
    fun selectVideoTrack(id: Int?): Boolean

    /** Подключить внешний файл субтитров к текущему элементу. */
    fun addExternalSubtitle(item: SubtitleItem): Boolean

    // ───────────────────────── видео: цвет, HDR, геометрия ─────────────────────────

    val videoFormat: EngineVideoFormat?

    /**
     * Цвет и HDR текущего потока. Это то, ради чего пишется движок: значения
     * доходят до `MediaCodec` без потерь, а не только до информационной панели,
     * как сейчас в `MediaFormatHelper.getHdrInfo`.
     */
    val colorInfo: EngineColorInfo?

    val stereoLayout: StereoLayout
    val projection: Projection

    /** Найденные чёрные поля кадра, `null` пока не посчитаны или отсутствуют. */
    val blackBorders: Rect?

    /** Текущий путь декодирования: `true` — MediaCodec, `false` — libavcodec. */
    val isHardwareDecode: Boolean

    /** Принудительно перевести видео на SW-путь (диагностика и обход битых HW-декодеров). */
    fun forceSoftwareDecode(enabled: Boolean)

    // ───────────────────────── тонмаппинг HDR ─────────────────────────
    // На гарнитуре нет системного HDR: `HdrCapabilities.mSupportedHdrTypes` пуст,
    // композитор в `ColorMode::NATIVE`, `hdr metadata types=0`, пик 500 нит.
    // Поэтому PQ/HLG → display сжимает сам движок, в своём шейдере, и параметры
    // этого сжатия — часть публичного контракта. См. `analysis/UNIFIED-ENGINE.md` §2.

    /**
     * Пользовательская поправка яркости HDR, `1.0` — нейтрально.
     * Аналог `Set/GetHDRBrightness` из 4XVR (там значение хранится на каждый файл).
     */
    var hdrBrightness: Float

    /**
     * Целевая пиковая яркость вывода в кд/м². По умолчанию берётся из
     * `Display.getHdrCapabilities().desiredMaxLuminance`; на этой гарнитуре — 500.
     */
    var toneMapPeakNits: Float

    /** Показатель того, применяется ли тонмаппинг к текущему кадру. */
    val isToneMapping: Boolean

    // ───────────────────────── переопределения геометрии ─────────────────────────
    // Движок сам рендерит кадр, поэтому масштаб и раскладка — его параметры,
    // а не свойства View. Автоопределение можно переопределить вручную:
    // у 4XVR это `SetVideoAndMapType` / `Set3DReverseEye` / `SetCurZoom`.

    /** `null` — вернуться к автоопределению. */
    fun overrideStereoLayout(layout: StereoLayout?)

    /** `null` — вернуться к автоопределению. */
    fun overrideProjection(projection: Projection?)

    /** Поменять глаза местами для стереопары. */
    var reverseEye: Boolean

    /** Масштаб кадра, `1.0` — без увеличения. */
    var zoom: Float

    /** Обрезать найденные чёрные поля. */
    var cropBlackBorders: Boolean

    // ───────────────────────── события ─────────────────────────

    fun setListener(listener: Listener?)

    interface Listener {
        fun onStateChanged(state: EngineState) {}
        fun onPositionChanged(positionMs: Long, durationMs: Long) {}
        fun onVideoSizeChanged(width: Int, height: Int, pixelAspectRatio: Float) {}
        fun onTracksChanged(tracks: EngineTracks) {}
        fun onColorInfoChanged(info: EngineColorInfo?) {}

        /** Движок сам перешёл к следующему элементу плейлиста. */
        fun onItemTransition(index: Int) {}

        /** Плейлист закончился. */
        fun onEnded() {}

        fun onError(error: EngineError) {}
    }
}
