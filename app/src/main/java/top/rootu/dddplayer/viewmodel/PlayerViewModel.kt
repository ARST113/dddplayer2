package top.rootu.dddplayer.viewmodel

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker
import kotlinx.coroutines.launch
import top.rootu.dddplayer.R
import top.rootu.dddplayer.bridge.BridgeConfig
import top.rootu.dddplayer.bridge.BridgeDispatcher
import top.rootu.dddplayer.bridge.BridgeEvent
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.data.VideoSettings
import top.rootu.dddplayer.logic.SettingsMutator
import top.rootu.dddplayer.logic.TrackLogic
import top.rootu.dddplayer.model.MediaItem
import top.rootu.dddplayer.model.MenuItem
import top.rootu.dddplayer.model.PlaybackSpeed
import top.rootu.dddplayer.model.ResizeMode
import top.rootu.dddplayer.player.PlayerManager
import top.rootu.dddplayer.utils.MediaFormatHelper
import top.rootu.dddplayer.utils.afr.RuntimeFpsDetector
import top.rootu.dddplayer.utils.getString
import androidx.media3.common.MediaItem as Media3MediaItem
import java.util.concurrent.atomic.AtomicBoolean


// --- Enums & Data Classes ---
enum class SettingType {
    AUDIO_TRACK, SUBTITLES
}

data class TrackOption(
    val format: Format?, // null для пункта "Off"
    val nameFromMeta: String?,
    val index: Int, // Порядковый номер (для генерации "Track 1")
    val group: Tracks.Group?,
    val trackIndex: Int,
    val isOff: Boolean = false
)

data class TrackFingerprint(
    val trackType: Int,
    val formatId: String?,
    val language: String?,
    val label: String?,
    val normalizedName: String?,
    val sampleMimeType: String?,
    val channelCount: Int?,
    val bitrate: Int?,
    val ordinal: Int,
    val trackCount: Int?,
    val nameFromMeta: String?
)

enum class TrackRestoreMode { SAFE, SMART, AGGRESSIVE }
data class TrackMatchResult(val index: Int, val score: Int, val reason: String)
data class AudioOptionDebug(
    val index: Int,
    val formatId: String?,
    val normalizedName: String?,
    val label: String?,
    val nameFromMeta: String?,
    val language: String?,
    val channelCount: Int?,
    val sampleMimeType: String?,
    val bitrate: Int?,
    val isSelected: Boolean,
    val isOff: Boolean
)
data class AudioRestoreDebugSnapshot(
    val timestamp: Long,
    val uri: String?,
    val mediaIndex: Int,
    val mediaTitle: String?,
    val pendingAudioId: String?,
    val pendingSubtitleId: String?,
    val hasExplicitSessionAudioPreference: Boolean,
    val sessionPreference: String?,
    val selectedBeforeIndex: Int,
    val selectedAfterIndex: Int,
    val restoreReason: String?,
    val restoreScore: Int?,
    val options: List<AudioOptionDebug>
)


data class PlaybackSnapshot(
    val sessionId: String?,
    val ts: Long,
    val uri: String?,
    val position: Long?,
    val duration: Long?,
    val bufferedPosition: Long?,
    val bufferedPercentage: Int?,
    val windowIndex: Int?,
    val playlistSize: Int?,
    val title: String?,
    val isPlaying: Boolean?,
    val playbackState: Int?,
    val reason: String?
)

data class VideoQualityOption(
    val name: String,
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val group: Tracks.Group?,
    val trackIndex: Int,
    val isAuto: Boolean = false
)

@UnstableApi
class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private companion object { const val TAG = "PlayerViewModel" }

    private var ioRetryCount = 0
    private val MAX_IO_RETRIES = 3

    private val repository = SettingsRepository.getInstance(application)

    private var bridgeConfig: BridgeConfig = BridgeConfig()
    private var bridgeDispatcher: BridgeDispatcher? = null
    private var lastBridgeTickAt = 0L

    @Volatile
    private var lastKnownSnapshot: PlaybackSnapshot? = null
    private var lastEventFlushAt = 0L
    private var lastEventFlushReason: String? = null
    private var lastEventFlushPosition: Long? = null
    private var lastEventFlushUri: String? = null
    private var pendingSeekFromPosition: Long? = null
    private var pendingSeekReason: String? = null
    private var mediaTransitionGeneration = 0L
    private var previousSnapshotForTransition: PlaybackSnapshot? = null
    private val finalSessionFinishedSent = AtomicBoolean(false)
    private val EVENT_FLUSH_MIN_INTERVAL_MS = 500L
    private val EVENT_FLUSH_MIN_POSITION_DELTA_MS = 1_000L
    private var sessionAudioPreference: TrackFingerprint? = null
    private var hasExplicitSessionAudioPreference = false
    private val audioRestoreDebugBuffer = ArrayDeque<AudioRestoreDebugSnapshot>()
    private val maxAudioRestoreDebugEntries = 30
    private var showAudioRestoreDebugToast = repository.isShowAudioRestoreDebugEnabled()
    private val trackRestoreMode: TrackRestoreMode = TrackRestoreMode.SMART

    // --- LiveData ---
    private val _isPlaying = MutableLiveData<Boolean>()
    val isPlaying: LiveData<Boolean> = _isPlaying
    private val _afrTriggerEvent = MutableLiveData<Format>()
    val afrTriggerEvent: LiveData<Format> = _afrTriggerEvent

    private var afrAppliedForCurrentItem = false

    // Детектор FPS
    private val fpsDetector = RuntimeFpsDetector()

    fun setBridgeDispatcher(dispatcher: BridgeDispatcher?, config: BridgeConfig) {
        bridgeDispatcher = dispatcher
        bridgeConfig = config
    }

    private val _currentPosition = MutableLiveData<Long>()
    val currentPosition: LiveData<Long> = _currentPosition
    private val _duration = MutableLiveData<Long>()
    val duration: LiveData<Long> = _duration
    private val _videoTitle = MutableLiveData<String?>()
    val videoTitle: LiveData<String?> = _videoTitle
    private val _isBuffering = MutableLiveData<Boolean>()
    val isBuffering: LiveData<Boolean> = _isBuffering
    private val _toastMessage = MutableLiveData<String?>()
    val toastMessage: LiveData<String?> = _toastMessage
    private val _cues = MutableLiveData<List<Cue>>()
    val cues: LiveData<List<Cue>> = _cues

    // Playlist State
    private val _currentPlaylist = MutableLiveData<List<MediaItem>>()
    val currentPlaylist: LiveData<List<MediaItem>> = _currentPlaylist
    private val _currentWindowIndex = MutableLiveData(0)
    val currentWindowIndex: LiveData<Int> = _currentWindowIndex
    private val _playlistSize = MutableLiveData(0)
    val playlistSize: LiveData<Int> = _playlistSize
    private val _hasPrevious = MutableLiveData(false)
    val hasPrevious: LiveData<Boolean> = _hasPrevious
    private val _hasNext = MutableLiveData(false)
    val hasNext: LiveData<Boolean> = _hasNext

    // Quality & Info
    private val _videoResolution = MutableLiveData<String>()
    val videoResolution: LiveData<String> = _videoResolution
    private val _videoAspectRatio = MutableLiveData(1.777f)
    val videoAspectRatio: LiveData<Float> = _videoAspectRatio
    private val _videoQualityOptions = MutableLiveData<List<VideoQualityOption>>()
    val videoQualityOptions: LiveData<List<VideoQualityOption>> = _videoQualityOptions
    private val _currentQualityName = MutableLiveData("Auto")
    val currentQualityName: LiveData<String> = _currentQualityName

    // UI State
    private val _singleFrameSize = MutableLiveData<Pair<Float, Float>>()
    val singleFrameSize: LiveData<Pair<Float, Float>> = _singleFrameSize

    // Список доступных настроек для OSD (вычисляется здесь, передается в SettingsViewModel)
    private val _availableSettings = MutableLiveData<List<SettingType>>()
    val availableSettings: LiveData<List<SettingType>> = _availableSettings

    // Tracks & Nav
    private val _audioOutputInfo = MutableLiveData<String>()
    val audioOutputInfo: LiveData<String> = _audioOutputInfo
    private val _currentAudioTrack = MutableLiveData<TrackOption?>()
    val currentAudioTrack: LiveData<TrackOption?> = _currentAudioTrack

    private val _currentSubtitleTrack = MutableLiveData<TrackOption?>()
    val currentSubtitleTrack: LiveData<TrackOption?> = _currentSubtitleTrack
    private val _videoDisabledError = MutableLiveData<PlaybackException?>()
    val videoDisabledError: LiveData<PlaybackException?> = _videoDisabledError
    private val _fatalError = MutableLiveData<PlaybackException?>()
    val fatalError: LiveData<PlaybackException?> = _fatalError
    private val _bufferedPercentage = MutableLiveData(0)
    val bufferedPercentage: LiveData<Int> = _bufferedPercentage
    private val _bufferedPosition = MutableLiveData(0L)
    val bufferedPosition: LiveData<Long> = _bufferedPosition
    private val _playbackSpeed = MutableLiveData(PlaybackSpeed.X1_00)
    val playbackSpeed: LiveData<PlaybackSpeed> = _playbackSpeed

    private val _resizeMode = MutableLiveData(ResizeMode.FIT)
    val resizeMode: LiveData<ResizeMode> = _resizeMode

    private val _zoomScale = MutableLiveData(repository.getZoomScalePercent())
    val zoomScale: LiveData<Int> = _zoomScale

    private val _isLive = MutableLiveData<Boolean>()
    val isLive: LiveData<Boolean> = _isLive

    // Храним ID, которые нужно применить, когда треки загрузятся (восстановление треков)
    private var pendingAudioId: String? = null
    private var pendingSubtitleId: String? = null

    // Internal
    private var audioOptions = listOf<TrackOption>()
    private var subtitleOptions = listOf<TrackOption>()
    private var currentAudioIndex = 0
    private var currentSubtitleIndex = 0
    private var backupSettings: VideoSettings? = null
    private var isSettingsLoadedFromDb = false
    private var currentUri: String? = null
    private var lastVideoSize: VideoSize? = null
    var isUserInteracting = false
    private val _playbackEnded = MutableLiveData<Boolean>()
    val playbackEnded: LiveData<Boolean> = _playbackEnded

    private val handler = Handler(Looper.getMainLooper())

    // Player Manager
    private val playerManager: PlayerManager

    // Безопасный доступ к плееру (может быть null, если не инициализирован)
    val player: ExoPlayer? get() = playerManager.exoPlayer

    // Событие: Плеер был пересоздан (нужно привязать Surface)
    private val _playerRecreatedEvent = MutableLiveData<ExoPlayer>()
    val playerRecreatedEvent: LiveData<ExoPlayer> = _playerRecreatedEvent

    // Флаг для однократного срабатывания Resume при открытии плеера
    private var isFirstItemLoaded = false
    private val _showResumeDialogEvent = MutableLiveData<Pair<Long, Long>?>()
    val showResumeDialogEvent: LiveData<Pair<Long, Long>?> = _showResumeDialogEvent

    // Храним хэш настроек при старте
    private var lastSettingsHash = repository.getHardSettingsSignature()

    private val progressUpdater = object : Runnable {
        override fun run() {
            val p = playerManager.exoPlayer
            val isActive = playerManager.isPlaying() || playerManager.getPlaybackStateCompat() == Player.STATE_BUFFERING
            if (isActive) {
                if (!isUserInteracting) {
                    _currentPosition.value = playerManager.getPositionMs()
                }
                _bufferedPosition.value = playerManager.getBufferedPositionMs()

                _bufferedPercentage.value = playerManager.getBufferedPercentage().coerceIn(0, 100)
                _isBuffering.value = playerManager.getPlaybackStateCompat() == Player.STATE_BUFFERING || (_bufferedPercentage.value ?: 0) < 100 && !playerManager.isPlaying()

                // Если AFR еще не сработал, проверяем формат
                if (!afrAppliedForCurrentItem) {
                    val format = p?.videoFormat
                    if (format != null) {
                        updateVideoInfoBadge(format) // Обновляем инфо, а он сам решит, запускать ли детектор
                    }
                }
            }

            val now = System.currentTimeMillis()
            if (bridgeConfig.enabled && bridgeConfig.emitPosition && now - lastBridgeTickAt >= bridgeConfig.positionIntervalMs) {
                bridgeDispatcher?.emit(
                    BridgeEvent.PositionTick(
                        sessionId = bridgeConfig.sessionId,
                        ts = now,
                        uri = playerManager.getCurrentUri(),
                        position = playerManager.getPositionMs(),
                        duration = normalizeDuration(playerManager.getDurationMs()),
                        bufferedPosition = playerManager.getBufferedPositionMs(),
                        bufferedPercentage = _bufferedPercentage.value,
                        windowIndex = playerManager.getCurrentWindowIndex(),
                        title = playerManager.getCurrentTitle(),
                        reason = "tick"
                    )
                )
                lastBridgeTickAt = now
                previousSnapshotForTransition = updateLastKnownSnapshot("tick")
            }
            handler.postDelayed(this, 200)
        }
    }


    private fun normalizePosition(position: Long, duration: Long?, playbackState: Int?): Long {
        val safePosition = if (position < 0) 0L else position
        if (playbackState == Player.STATE_ENDED && duration != null) return duration
        return if (duration != null && safePosition > duration) duration else safePosition
    }

    private fun capturePlaybackSnapshot(reason: String): PlaybackSnapshot {
        val p = player
        val fallback = lastKnownSnapshot
        if (p == null && playerManager.getCurrentUri() == null) return fallback?.copy(ts = System.currentTimeMillis(), reason = reason)
            ?: PlaybackSnapshot(bridgeConfig.sessionId, System.currentTimeMillis(), null, null, null, null, null, null, null, null, null, null, reason)
        val normalizedDuration = normalizeDuration(playerManager.getDurationMs())
        return PlaybackSnapshot(
            sessionId = bridgeConfig.sessionId,
            ts = System.currentTimeMillis(),
            uri = playerManager.getCurrentUri() ?: fallback?.uri,
            position = normalizePosition(playerManager.getPositionMs(), normalizedDuration, p?.playbackState),
            duration = normalizedDuration,
            bufferedPosition = playerManager.getBufferedPositionMs(),
            bufferedPercentage = _bufferedPercentage.value,
            windowIndex = playerManager.getCurrentWindowIndex(),
            playlistSize = playerManager.getPlaylistSize(),
            title = playerManager.getCurrentTitle(),
            isPlaying = playerManager.isPlaying(),
            playbackState = playerManager.getPlaybackStateCompat(),
            reason = reason
        )
    }

    private fun updateLastKnownSnapshot(reason: String): PlaybackSnapshot {
        val snapshot = capturePlaybackSnapshot(reason)
        lastKnownSnapshot = snapshot
        return snapshot
    }

    private fun PlaybackSnapshot.toPositionTick(reason: String) = BridgeEvent.PositionTick(
        sessionId, System.currentTimeMillis(), uri, position, duration, bufferedPosition, bufferedPercentage, windowIndex, title, reason
    )

    private fun PlaybackSnapshot.toSessionFinished(endBy: String) = BridgeEvent.SessionFinished(
        sessionId, System.currentTimeMillis(), uri, position, duration, endBy, windowIndex, playlistSize, title
    )

    private fun PlaybackSnapshot.toError(error: PlaybackException) = BridgeEvent.Error(
        sessionId, System.currentTimeMillis(), uri, error.errorCodeName, error.errorCode, error.message, windowIndex, position, duration, bufferedPosition, bufferedPercentage, playlistSize, title, true
    )

    private fun shouldThrottleEventFlush(snapshot: PlaybackSnapshot, reason: String, force: Boolean): Boolean {
        if (force) return false
        val hardReasons = setOf("pause","resume","seek","seek_forward","seek_backward","manual_next","manual_previous","playlist_item_changed","ended","background","destroy","user_exit","error")
        if (reason in hardReasons) return false
        val now = System.currentTimeMillis()
        val posDelta = kotlin.math.abs((snapshot.position ?: 0L) - (lastEventFlushPosition ?: 0L))
        return reason == lastEventFlushReason && snapshot.uri == lastEventFlushUri && (now - lastEventFlushAt) < EVENT_FLUSH_MIN_INTERVAL_MS && posDelta < EVENT_FLUSH_MIN_POSITION_DELTA_MS
    }

    fun flushProgress(reason: String, final: Boolean = false, includeError: PlaybackException? = null, force: Boolean = false, saveSettings: Boolean = true) {
        if (final && finalSessionFinishedSent.get()) return
        val snapshot = capturePlaybackSnapshot(reason)
        if (snapshot.uri == null && snapshot.position == null) return
        if (shouldThrottleEventFlush(snapshot, reason, force)) return
        lastKnownSnapshot = snapshot
        lastEventFlushAt = System.currentTimeMillis()
        lastEventFlushReason = reason
        lastEventFlushPosition = snapshot.position
        lastEventFlushUri = snapshot.uri
        previousSnapshotForTransition = snapshot
        if (saveSettings) saveCurrentSettings()
        if (bridgeConfig.enabled && bridgeConfig.emitPosition) bridgeDispatcher?.emit(snapshot.toPositionTick(reason))
        if (final && bridgeConfig.enabled && finalSessionFinishedSent.compareAndSet(false, true)) {
            bridgeDispatcher?.emit(snapshot.toSessionFinished(reason))
        }
        if (includeError != null && bridgeConfig.enabled) bridgeDispatcher?.emit(snapshot.toError(includeError))
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            val reason = if (isPlaying) "resume" else "pause"
            flushProgress(reason, force = true)
            val snapshot = capturePlaybackSnapshot(reason)
            updateProgressUpdaterState()
            bridgeDispatcher?.emit(
                BridgeEvent.PlaybackStateChanged(
                    sessionId = bridgeConfig.sessionId,
                    ts = System.currentTimeMillis(),
                    uri = player?.currentMediaItem?.localConfiguration?.uri?.toString(),
                    isPlaying = isPlaying,
                    isBuffering = _isBuffering.value == true,
                    position = snapshot.position,
                    duration = snapshot.duration,
                    reason = reason
                )
            )
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _isBuffering.value = (playbackState == Player.STATE_BUFFERING)
            if (playbackState == Player.STATE_READY) {
                ioRetryCount = 0 // Сброс счетчика, если видео успешно заиграло
                _duration.value = player?.duration
                // После буферизации или старта обновляем инфо
                player?.videoFormat?.let { updateVideoInfoBadge(it) }
            }
            if (playbackState == Player.STATE_BUFFERING) flushProgress("buffering")
            if (playbackState == Player.STATE_READY) flushProgress("state_ready")
            if (playbackState == Player.STATE_ENDED) {
                _playbackEnded.value = true
                flushProgress("ended", force = true)
                val p = player
                bridgeDispatcher?.emit(
                    BridgeEvent.PlaybackEnded(
                        sessionId = bridgeConfig.sessionId,
                        ts = System.currentTimeMillis(),
                        uri = playerManager.getCurrentUri(),
                        windowIndex = p?.currentMediaItemIndex ?: 0,
                        playlistSize = p?.mediaItemCount ?: 0,
                        title = p?.currentMediaItem?.mediaMetadata?.title?.toString(),
                        position = p?.currentPosition,
                        duration = p?.duration?.let { normalizeDuration(it) }
                    )
                )
            }
            updateProgressUpdaterState()
            val stateReason = when (playbackState) {
                Player.STATE_BUFFERING -> "buffering"
                Player.STATE_READY -> "state_ready"
                Player.STATE_ENDED -> "ended"
                else -> "playback_state_changed"
            }
            val snapshot = capturePlaybackSnapshot(stateReason)
            bridgeDispatcher?.emit(
                BridgeEvent.PlaybackStateChanged(
                    sessionId = bridgeConfig.sessionId,
                    ts = System.currentTimeMillis(),
                    uri = player?.currentMediaItem?.localConfiguration?.uri?.toString(),
                    isPlaying = player?.isPlaying == true,
                    isBuffering = _isBuffering.value == true,
                    position = snapshot.position,
                    duration = snapshot.duration,
                    reason = stateReason
                )
            )
            _isLive.value = player?.isCurrentMediaItemLive ?: false
        }

        override fun onPlayerError(error: PlaybackException) {
            if (playerManager.maybeFallbackToVlcOnError(error)) {
                return
            }
            if (tryRecoverFromError(error)) {
                return
            }
            flushProgress(reason = "error", final = true, includeError = error)
            _fatalError.postValue(error)
            _isPlaying.postValue(false)
        }

        override fun onMediaItemTransition(mediaItem: Media3MediaItem?, reason: Int) {
            ioRetryCount = 0 // Сброс счетчика при смене видео
            previousSnapshotForTransition?.let {
                if (bridgeConfig.enabled && bridgeConfig.emitPosition) {
                    bridgeDispatcher?.emit(it.toPositionTick("before_playlist_item_changed"))
                }
            }
            handleMediaItemTransition(mediaItem)
            val p = player
            bridgeDispatcher?.emit(
                BridgeEvent.PlaylistItemChanged(
                    sessionId = bridgeConfig.sessionId,
                    ts = System.currentTimeMillis(),
                    uri = mediaItem?.localConfiguration?.uri?.toString(),
                    windowIndex = p?.currentMediaItemIndex ?: 0,
                    playlistSize = p?.mediaItemCount ?: 0,
                    title = mediaItem?.mediaMetadata?.title?.toString(),
                    reason = bridgeTransitionReason(reason),
                    position = p?.currentPosition,
                    duration = p?.duration?.let { normalizeDuration(it) },
                    hasPrevious = p?.hasPreviousMediaItem() == true,
                    hasNext = p?.hasNextMediaItem() == true
                )
            )
            flushProgress("playlist_item_changed", force = true, saveSettings = false)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            handleVideoSizeChanged(videoSize)
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateTracksInfo(tracks)
        }

        override fun onCues(cueGroup: CueGroup) {
            _cues.value = cueGroup.cues
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                fpsDetector.stop(player)
                val delta = newPosition.positionMs - oldPosition.positionMs
                val flushReason = pendingSeekReason ?: if (delta > 0) "seek_forward" else if (delta < 0) "seek_backward" else "seek"
                pendingSeekFromPosition = null
                pendingSeekReason = null
                bridgeDispatcher?.emit(
                    BridgeEvent.SeekCompleted(
                        sessionId = bridgeConfig.sessionId,
                        ts = System.currentTimeMillis(),
                        uri = player?.currentMediaItem?.localConfiguration?.uri?.toString(),
                        fromPosition = oldPosition.positionMs,
                        toPosition = newPosition.positionMs
                    )
                )
                flushProgress(flushReason, force = true)
            }
        }
    }

    init {
        // Загружаем кастомные настройки через делегат

        playerManager = PlayerManager(application, playerListener)

        // Подписываемся на создание нового плеера
        playerManager.onPlayerCreated = { newPlayer ->
            _playerRecreatedEvent.postValue(newPlayer)
            // Принудительно обновляем UI состояние
            _isPlaying.postValue(newPlayer.isPlaying)
            _duration.postValue(newPlayer.duration)
        }

        playerManager.onVideoFormatChanged = { format ->
            updateVideoInfoBadge(format)
        }

        playerManager.onMetadataAvailable = {
            // Метаданные из файла (MKV/MP4) готовы. Обновляем треки.
            handler.post {
                player?.currentTracks?.let { updateTracksInfo(it) }
            }
        }

        playerManager.onAudioOutputFormatChanged = { info ->
            _audioOutputInfo.postValue(info)
        }
        playerManager.onBackendPlayingChanged = { playing ->
            _isPlaying.postValue(playing)
            if (playing) {
                _isBuffering.postValue(false)
                android.util.Log.i("DDDPlayer/Backend", "ViewModel backend playing -> hide loading spinner")
                val d = playerManager.getDurationMs()
                if (d > 0) {
                    _duration.postValue(d)
                    android.util.Log.i("DDDPlayer/Backend", "duration update from backend=$d")
                }
            }
            updateProgressUpdaterState()
        }
        playerManager.onBackendBufferingChanged = { buffering ->
            _isBuffering.postValue(buffering)
            updateProgressUpdaterState()
        }
        playerManager.onBackendEnded = {
            saveCurrentSettings()
            _playbackEnded.postValue(true)
            flushProgress("ended", force = true)
        }
        playerManager.onBackendError = { err ->
            saveCurrentSettings()
            flushProgress(reason = "error", final = true)
            _fatalError.postValue(err as? PlaybackException)
            _isPlaying.postValue(false)
        }

        // Инициализируем плеер сразу
        playerManager.initializePlayer()

        if (repository.isRememberZoomEnabled()) {
            val savedModeOrd = repository.getGlobalResizeMode()
            val savedMode = ResizeMode.entries.getOrNull(savedModeOrd) ?: ResizeMode.FIT
            _resizeMode.value = savedMode
            // ZoomScale уже загружается в поле _zoomScale при инициализации
        }
    }

    private fun updateProgressUpdaterState() {
        val p = playerManager.exoPlayer
        val shouldRun = playerManager.isPlaying() || p?.playbackState == Player.STATE_BUFFERING || (_isBuffering.value == true)

        if (shouldRun) {
            handler.removeCallbacks(progressUpdater)
            handler.post(progressUpdater)
        } else {
            handler.removeCallbacks(progressUpdater)
        }
    }

    private fun tryRecoverFromError(error: PlaybackException): Boolean {
        val p = player ?: return false
        val cause = error.cause
        val TAG = "tryRecoverFromError"

        // Перехват застрявшего HLS плейлиста
        if (cause is HlsPlaylistTracker.PlaylistStuckException) {
            Log.w(TAG, "HLS Playlist stuck. Forcing reload...")
            val currentIndex = p.currentMediaItemIndex
            val currentPos = p.currentPosition

            // Для Live прыгаем на край, для VOD восстанавливаем позицию
            if (p.isCurrentMediaItemLive) {
                p.seekTo(currentIndex, C.TIME_UNSET)
            } else {
                p.seekTo(currentIndex, currentPos)
            }
            p.prepare()
            p.play()
            return true
        }

        // Универсальный перехват IO и сетевых ошибок (MKV/MP4/HLS)
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) {

            if (ioRetryCount < MAX_IO_RETRIES) {
                ioRetryCount++
                Log.w(TAG, "IO Error detected ($ioRetryCount/$MAX_IO_RETRIES). Retrying at current position...")

                val currentIndex = p.currentMediaItemIndex
                val currentPos = p.currentPosition // Сохраняем текущую точку

                // Собираем новый список MediaItem
                val newItems = mutableListOf<Media3MediaItem>()
                for (i in 0 until p.mediaItemCount) {
                    newItems.add(p.getMediaItemAt(i))
                }

                // Переподготавливаем плеер и возвращаем его на то же место
                p.setMediaItems(newItems, currentIndex, currentPos)
                p.prepare()
                p.play()
                return true
            }
        }

        // Попытка восстановления: Неверно определенный контейнер (скрытый HLS/DASH)
        if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) {
            val currentItem = p.currentMediaItem
            val originalUri = currentItem?.localConfiguration?.uri

            if (originalUri != null) {
                val resolvedMimeType = playerManager.getResolvedMimeType(originalUri)

                if (resolvedMimeType != null && currentItem.localConfiguration?.mimeType != resolvedMimeType) {

                    // ВАЖНО: Чтобы ExoPlayer гарантированно пересоздал MediaSource (например, сменил
                    // ProgressiveMediaSource на HlsMediaSource), нам нужно пересобрать весь плейлист.
                    // Метод replaceMediaItem может не сработать, если URI остался прежним.

                    val currentIndex = p.currentMediaItemIndex
                    val currentPos = p.currentPosition

                    // Собираем новый список MediaItem
                    val newItems = mutableListOf<Media3MediaItem>()
                    for (i in 0 until p.mediaItemCount) {
                        val item = p.getMediaItemAt(i)
                        if (i == currentIndex) {
                            // Подменяем MimeType только у проблемного элемента
                            newItems.add(
                                item.buildUpon()
                                    .setMimeType(resolvedMimeType)
                                    .build()
                            )
                        } else {
                            newItems.add(item)
                        }
                    }

                    // Полностью перезагружаем плейлист в плеер
                    p.setMediaItems(newItems, currentIndex, currentPos)
                    p.prepare()
                    p.play()

                    return true // Успешно перехватили и исправили
                }
            }
        }

        if (error !is ExoPlaybackException || error.type != ExoPlaybackException.TYPE_RENDERER) {
            return false
        }

        val rendererIndex = error.rendererIndex
        if (rendererIndex == C.INDEX_UNSET) return false

        player?.let { p ->
            saveCurrentSettings()
            val trackType = p.getRendererType(rendererIndex)

            when (trackType) {
                C.TRACK_TYPE_VIDEO -> {
                    _videoDisabledError.postValue(error)
                    _toastMessage.postValue(getString(R.string.error_video_decoder))

                    // Отключаем проблемный трек
                    val parameters = p.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(trackType, true)
                        .build()
                    p.trackSelectionParameters = parameters

                }
                C.TRACK_TYPE_AUDIO -> {
                    val hint = if (audioOptions.size > 2) getString(R.string.error_audio_disabled_hint, "${error.errorCodeName}: ${error.message}")
                    else getString(R.string.error_audio_disabled, "${error.errorCodeName}: ${error.message}")
                    _toastMessage.postValue(hint)
                    selectTrackByIndex(C.TRACK_TYPE_AUDIO, 0) // Выкл
                }
                else -> {
                    return false
                }
            }

            // Перезапускаем воспроизведение
            p.seekTo(p.currentMediaItemIndex, p.currentPosition)
            p.prepare()
            p.play()
        }
        return true
    }

    private fun handleMediaItemTransition(mediaItem: Media3MediaItem?) {
        afrAppliedForCurrentItem = false
        fpsDetector.stop(player)
        fpsDetector.reset() // Сброс для нового видео

        // Сбрасываем отложенные ID дорожек при переходе на новое видео
        pendingAudioId = null
        pendingSubtitleId = null

        player?.let { p ->
            val generation = ++mediaTransitionGeneration
            val uriForSettings = mediaItem?.localConfiguration?.uri?.toString()
            Log.d(TAG, "Media item transition generation=$generation uri=$uriForSettings")
            val title = mediaItem?.mediaMetadata?.title?.toString()
            currentUri = uriForSettings
            _videoTitle.value = title ?: mediaItem?.localConfiguration?.uri?.lastPathSegment

            _hasPrevious.value = p.hasPreviousMediaItem()
            _hasNext.value = p.hasNextMediaItem()
            _playlistSize.value = p.mediaItemCount
            _currentWindowIndex.value = p.currentMediaItemIndex

            isSettingsLoadedFromDb = false

            // Сбрасываем ошибку видео при ЛЮБОМ переходе
            _videoDisabledError.value = null

            // ВАЖНО: Принудительно включаем видео обратно, если оно было отключено из-за ошибки
            // Это нужно делать здесь, так как автоматический переход на следующий трек
            // не вызывает playPlaylistItem, но вызывает этот колбэк.
            val params = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .build()
            p.trackSelectionParameters = params

            if (uriForSettings != null) {
                viewModelScope.launch {
                    val saved = repository.getVideoSettings(uriForSettings)
                    if (generation != mediaTransitionGeneration || currentUri != uriForSettings) {
                        Log.d(TAG, "Skip stale settings load generation=$generation uri=$uriForSettings")
                        return@launch
                    }
                    if (saved != null) {
                        Log.d(TAG, "Loaded settings for uri=$uriForSettings audioTrackId=${saved.audioTrackId} subtitleTrackId=${saved.subtitleTrackId}")
                        applySettings(saved)
                        // Сохраняем ID из базы как отложенные
                        pendingAudioId = saved.audioTrackId
                        pendingSubtitleId = saved.subtitleTrackId

                        isSettingsLoadedFromDb = true

                        if (!isFirstItemLoaded) {
                            handleResumeLogic(saved)
                        }
                    } else {
                        loadGlobalDefaults()
                    }
                    handler.post {
                        if (generation == mediaTransitionGeneration && currentUri == uriForSettings) {
                            player?.currentTracks?.let { updateTracksInfo(it) }
                        }
                    }
                    // Помечаем, что первое видео обработавано.
                    isFirstItemLoaded = true
                }
            }
        }
    }

    private fun handleResumeLogic(settings: VideoSettings) {
        if (player?.isCurrentMediaItemLive == true) return

        val savedPos = settings.lastPosition
        val totalDur = settings.duration
        val resumeMode = repository.getResumeMode()

        val currentIndex = player?.currentMediaItemIndex ?: -1
        val intentPos = if (currentIndex != -1) {
            _currentPlaylist.value?.getOrNull(currentIndex)?.startPositionMs ?: 0L
        } else 0L
        // Если в интенте была позиция (не 0), игнорируем БД
        if (intentPos > 0 || resumeMode == SettingsRepository.RESUME_NEVER) return

        // Правило 95% или менее 5 секунд прогресса - начинаем с 0
        if (totalDur > 0) {
            val percent = (savedPos.toFloat() / totalDur.toFloat())
            if (percent > 0.95f || savedPos < 5000L) return
        } else if (savedPos < 5000L) return

        // Применяем режим
        when (resumeMode) {
            SettingsRepository.RESUME_ALWAYS -> {
                seekTo(savedPos)
            }
            SettingsRepository.RESUME_ASK -> {
                player?.pause()
                _showResumeDialogEvent.postValue(Pair(savedPos, totalDur))
            }
        }
    }

    fun resumePlayback(position: Long) {
        seekTo(position)
        player?.play()
        _showResumeDialogEvent.value = null
    }

    fun cancelResume() {
        player?.play()
        _showResumeDialogEvent.value = null
    }

    private fun updateVideoInfoBadge(format: Format) {
        // Используем сохраненный FPS, если он есть
        var fps = fpsDetector.detectedFrameRate ?: format.frameRate

        if (fps <= 0f) {
            fps = tryGetFpsFromDeepSources()
        }

        val originalUri = player?.currentMediaItem?.localConfiguration?.uri

        val manifest = player?.currentManifest

        val container = MediaFormatHelper.getShortContainerName(
            format.containerMimeType,
            originalUri,
            manifest
        )

        val codec = MediaFormatHelper.getShortVideoCodecName(format)
        val hdr = MediaFormatHelper.getHdrInfo(format)
        val fpsStr = MediaFormatHelper.formatFrameRate(fps)

        val builder = StringBuilder()

        if (container.isNotEmpty()) {
            builder.append(container)
            if (codec.isNotEmpty()) builder.append("($codec) ")
            else builder.append(" ")
        } else if (codec.isNotEmpty()) {
            builder.append("$codec ")
        }

        builder.append(MediaFormatHelper.formatResolution(format.width, format.height))

        if (fpsStr.isNotEmpty()) {
            builder.append("@$fpsStr")
            if (fpsDetector.detectedFrameRate != null && fpsDetector.detectedFrameRate!! > 0f) {
                builder.append("'")
            }
        }

        if (hdr.isNotEmpty()) {
            builder.append(" $hdr")
        }

        _videoResolution.postValue(builder.toString().trim())

        // ЛОГИКА ТРИГГЕРА AFR
        if (!afrAppliedForCurrentItem) {
            if (fps > 0f) {
                afrAppliedForCurrentItem = true
                val finalFormat = format.buildUpon().setFrameRate(fps).build()
                _afrTriggerEvent.postValue(finalFormat)
            } else {
                player?.let { p ->
                    fpsDetector.start(
                        player = p,
                        handler = handler,
                        onSuccess = { detectedFps, currentFormat ->
                            val updatedFormat = currentFormat.buildUpon().setFrameRate(detectedFps).build()
                            updateVideoInfoBadge(updatedFormat)
                        },
                        onVfrDetected = {
                            afrAppliedForCurrentItem = true
                        }
                    )
                }
            }
        }
    }

    private fun tryGetFpsFromDeepSources(): Float {
        val p = player ?: return -1f
        val manifest = p.currentManifest
        if (manifest is HlsManifest) {
            manifest.multivariantPlaylist.variants.forEach { variant ->
                if (variant.format.id == p.videoFormat?.id ||
                    (variant.format.width == p.videoFormat?.width && variant.format.height == p.videoFormat?.height)) {
                    if (variant.format.frameRate > 0) return variant.format.frameRate
                }
            }
        }
        if (manifest is DashManifest && manifest.periodCount > 0) {
            val period = manifest.getPeriod(0)
            period.adaptationSets.forEach { set ->
                if (set.type == C.TRACK_TYPE_VIDEO) {
                    set.representations.forEach { rep ->
                        if (rep.format.frameRate > 0) return rep.format.frameRate
                    }
                }
            }
        }
        val tracks = p.currentTracks
        tracks.groups.forEach { group ->
            if (group.type == C.TRACK_TYPE_VIDEO) {
                for (i in 0 until group.length) {
                    val f = group.getTrackFormat(i)
                    if (f.frameRate > 0) return f.frameRate
                }
            }
        }
        return -1f
    }

    private fun handleVideoSizeChanged(videoSize: VideoSize) {
        lastVideoSize = videoSize
        if (videoSize.height > 0) {
            val ratio = (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height
            _videoAspectRatio.postValue(ratio)
        }

        if (videoSize.width == 0 || videoSize.height == 0) return
    }

    private fun updateTracksInfo(tracks: Tracks) {
        val metadata = playerManager.getTrackMetadata()

        // 1. Аудио
        val (audio, audioIdx) = TrackLogic.extractAudioTracks(tracks, metadata)
        audioOptions = audio
        // Пытаемся применить отложенную аудиодорожку
        var finalAudioIdx = audioIdx
        var restoreReason: String? = null
        var restoreScore: Int? = null
        var shouldSaveRestoredTracks = false
        val requestedPendingAudioId = pendingAudioId
        val hadPendingAudioId = requestedPendingAudioId != null
        val sessionPref = sessionAudioPreference
        if (hasExplicitSessionAudioPreference && sessionPref != null) {
            val matched = matchTrackByPreference(audioOptions, sessionPref, trackRestoreMode, explicitNamePriority = true)
            if (matched != null) {
                finalAudioIdx = matched.index
                restoreReason = reasonFromSessionMatch(matched.reason)
                restoreScore = matched.score
            } else {
                pendingAudioId?.let { id ->
                    val foundIdx = audioOptions.indexOfFirst { it.format?.id == id }
                    if (foundIdx != -1) {
                        finalAudioIdx = foundIdx
                        restoreReason = "restore_exact_uri"
                        restoreScore = 100
                    }
                }
            }
        } else {
            pendingAudioId?.let { id ->
                val foundIdx = audioOptions.indexOfFirst { it.format?.id == id }
                if (foundIdx != -1) {
                    finalAudioIdx = foundIdx
                    restoreReason = "restore_exact_uri"
                    restoreScore = 100
                }
            }
            if (restoreReason == null && sessionPref != null) {
                val matched = matchTrackByPreference(audioOptions, sessionPref, trackRestoreMode)
                if (matched != null) {
                    finalAudioIdx = matched.index
                    restoreReason = reasonFromSessionMatch(matched.reason)
                    restoreScore = matched.score
                }
            }
        }
        if (hadPendingAudioId && restoreReason == null) {
            Log.d(TAG, "Pending audio id not found id=$requestedPendingAudioId uri=$currentUri")
        }
        if (restoreReason != null && finalAudioIdx in audioOptions.indices && finalAudioIdx != audioIdx) {
            selectTrackByIndex(C.TRACK_TYPE_AUDIO, finalAudioIdx, persist = false, reason = restoreReason!!, matchScore = restoreScore)
        } else if (restoreReason == "restore_exact_uri" && finalAudioIdx in audioOptions.indices && finalAudioIdx == audioIdx) {
            emitTrackSelectionChanged(audioOptions[finalAudioIdx], finalAudioIdx, "audio", "restore_exact_uri", restoreScore)
        }
        currentAudioIndex = finalAudioIdx
        _currentAudioTrack.value = audioOptions.getOrNull(currentAudioIndex)
        appendAudioRestoreDebugSnapshot(audioIdx, finalAudioIdx, requestedPendingAudioId, pendingSubtitleId, restoreReason, restoreScore)
        if (showAudioRestoreDebugToast && restoreReason != null) {
            val selected = audioOptions.getOrNull(finalAudioIdx)
            val label = selected?.nameFromMeta ?: selected?.format?.label ?: selected?.format?.id ?: "unknown"
            _toastMessage.postValue("Audio restore: $label, reason=$restoreReason, score=${restoreScore ?: 0}")
        }
        if (restoreReason != null && finalAudioIdx in audioOptions.indices) {
            shouldSaveRestoredTracks = true
        }

        // 2. Субтитры
        val (subs, subIdx) = TrackLogic.extractSubtitleTracks(tracks, metadata)
        subtitleOptions = subs
        // Пытаемся применить отложенные субтитры
        var finalSubIdx = subIdx
        val requestedPendingSubtitleId = pendingSubtitleId
        val hadPendingSubtitleId = requestedPendingSubtitleId != null
        var subtitleRestored = false
        pendingSubtitleId?.let { id ->
            // Специальная обработка для "Выкл"
            if (id == "disabled") {
                finalSubIdx = 0
                selectTrackByIndex(C.TRACK_TYPE_TEXT, 0)
                subtitleRestored = true
            } else {
                val foundIdx = subtitleOptions.indexOfFirst { it.format?.id == id }
                if (foundIdx != -1) {
                    finalSubIdx = foundIdx
                    selectTrackByIndex(C.TRACK_TYPE_TEXT, foundIdx)
                    subtitleRestored = true
                }
            }
        }
        if (hadPendingSubtitleId && !subtitleRestored) {
            Log.d(TAG, "Pending subtitle id not found id=$requestedPendingSubtitleId uri=$currentUri")
        }
        currentSubtitleIndex = finalSubIdx
        _currentSubtitleTrack.value = subtitleOptions.getOrNull(currentSubtitleIndex)
        if (shouldSaveRestoredTracks) {
            saveCurrentSettings()
        }

        // Очищаем отложенные ID, так как мы их уже применили (или не нашли)
        pendingAudioId = null
        pendingSubtitleId = null

        // 3. Видео (Quality)
        _videoQualityOptions.postValue(TrackLogic.extractVideoTracks(tracks))

        updateAvailableSettings()
    }

    // --- Player Controls ---
    fun seekForward() = playerManager.seekForward()
    fun seekBack() = playerManager.seekBack()
    fun seekTo(pos: Long) {
        val p = player ?: return
        val from = p.currentPosition
        pendingSeekFromPosition = from
        pendingSeekReason = if (pos > from) "seek_forward" else if (pos < from) "seek_backward" else "seek"
        p.seekTo(pos)
        _currentPosition.value = pos
    }
    fun bindSurfaceHolder(holder: SurfaceHolder?) = playerManager.bindSurfaceHolder(holder)
    fun getCurrentPositionMs(): Long = playerManager.getPositionMs()
    fun getDurationMs(): Long = playerManager.getDurationMs()
    fun isBackendPlaying(): Boolean = playerManager.isPlaying()

    fun togglePlayPause() = playerManager.togglePlayPause()
    fun setPlaybackActive(active: Boolean) { if (active) playerManager.play() else playerManager.pause() }
    fun nextTrack() {
        val p = player ?: return
        if (p.hasNextMediaItem()) { saveCurrentSettings(); pendingSeekReason = "manual_next"; p.seekToNextMediaItem(); flushProgress("manual_next", force = true, saveSettings = false) }
    }
    fun prevTrack() {
        val p = player ?: return
        if (p.hasPreviousMediaItem()) { saveCurrentSettings(); pendingSeekReason = "manual_previous"; p.seekToPreviousMediaItem(); flushProgress("manual_previous", force = true, saveSettings = false) }
    }

    fun setPlaybackSpeed(speed: PlaybackSpeed) {
        _playbackSpeed.value = speed
        player?.setPlaybackSpeed(speed.value)
    }

    fun setResizeMode(mode: ResizeMode) {
        _resizeMode.value = mode
        // Если включено запоминание - сохраняем
        if (repository.isRememberZoomEnabled()) {
            repository.setGlobalResizeMode(mode.ordinal)
        }
    }

    fun setZoomScale(percent: Int) {
        val newScale = percent.coerceIn(100, 200) // Ограничиваем от 100% до 200%
        repository.setZoomScalePercent(newScale)
        _zoomScale.value = newScale
        _resizeMode.value = ResizeMode.SCALE
        // Если включено запоминание - сохраняем режим SCALE
        if (repository.isRememberZoomEnabled()) {
            repository.setGlobalResizeMode(ResizeMode.SCALE.ordinal)
        }
    }

    fun loadPlaylist(items: List<MediaItem>, startIndex: Int, startPosMs: Long? = null) {
        _currentPlaylist.value = items
        val startPos = startPosMs ?: items.getOrNull(startIndex)?.startPositionMs ?: 0L
        playerManager.loadPlaylist(items, startIndex, startPos)
        viewModelScope.launch { repository.cleanupOldSettings() }
    }

    // --- Settings Logic ---

    private fun loadGlobalDefaults() {
        updateAvailableSettings()
    }

    fun saveCurrentSettings() {
        val p = player ?: return
        val playerUri = p.currentMediaItem?.localConfiguration?.uri?.toString()
        val uri = playerUri ?: currentUri ?: return
        if (currentUri != null && playerUri != null && currentUri != playerUri) {
            Log.d(TAG, "saveCurrentSettings uri mismatch currentUri=$currentUri playerUri=$playerUri; using playerUri")
        }
        currentUri = uri

        val positionToSave = if (p.isCurrentMediaItemLive) 0L else p.currentPosition
        // Получаем ID текущих дорожек
        val audioId = _currentAudioTrack.value?.format?.id
        val subId = if (_currentSubtitleTrack.value?.isOff == true) "disabled"
        else _currentSubtitleTrack.value?.format?.id

        val settings = VideoSettings(
            uri = uri,
            lastUpdated = System.currentTimeMillis(),
            lastPosition = positionToSave,
            duration = p.duration.coerceAtLeast(0L),
            audioTrackId = audioId,
            subtitleTrackId = subId
        )
        viewModelScope.launch { repository.saveVideoSettings(settings) }

        isSettingsLoadedFromDb = true
    }

    fun prepareSettingsPanel() {
        // Создаем копию текущих настроек для отката
        backupSettings = VideoSettings(
            uri = "",
            lastUpdated = 0,
            lastPosition = 0L,
            duration = 0L
        )
        updateAvailableSettings()
    }

    fun restoreSettings() {
        backupSettings?.let { applySettings(it) }
    }

    private fun applySettings(s: VideoSettings) {
        updateAvailableSettings()
    }

    // --- Menu Generation ---

    fun getPlaybackSpeedMenuItems(): List<MenuItem> {
        val currentSpeed = _playbackSpeed.value ?: PlaybackSpeed.X1_00
        return PlaybackSpeed.entries.map { speed ->
            MenuItem(
                id = speed.value.toString(),
                title = speed.label,
                isSelected = speed == currentSpeed
            )
        }
    }

    private fun getResizeModeIconResId(mode: ResizeMode?): Int? {
        return when (mode) {
            ResizeMode.FIT -> R.drawable.ic_fit_screen
            ResizeMode.ZOOM -> R.drawable.ic_fit_zoom
            ResizeMode.SCALE -> R.drawable.ic_fit_scale
            ResizeMode.FILL -> R.drawable.ic_fullscreen
            else -> null
        }
    }

    fun getResizeModeMenuItems(context: Context): List<MenuItem> {
        val currentMode = _resizeMode.value ?: ResizeMode.FIT
        val currentZoom = _zoomScale.value ?: 115

        return ResizeMode.entries.map { mode ->
            val titleResId = when (mode) {
                ResizeMode.FIT -> R.string.resize_mode_fit
                ResizeMode.ZOOM -> R.string.resize_mode_zoom
                ResizeMode.SCALE -> R.string.resize_mode_scale
                ResizeMode.FILL -> R.string.resize_mode_fill
            }
            val iconResId = getResizeModeIconResId(mode)

            val description = when (mode) {
                ResizeMode.FIT -> context.getString(R.string.resize_mode_fit_desc)
                ResizeMode.ZOOM -> context.getString(R.string.resize_mode_zoom_desc)
                ResizeMode.SCALE -> context.getString(R.string.playback_zoom_percent, currentZoom)
                ResizeMode.FILL -> context.getString(R.string.resize_mode_fill_desc)
            }

            MenuItem(
                id = mode.name,
                title = context.getString(titleResId),
                description = description,
                iconRes = iconResId,
                isSelected = mode == currentMode
            )
        }
    }

    fun getMainMenuItems(context: Context): List<MenuItem> {
        val currentAudioName = currentAudioTrack.value?.let { TrackLogic.buildTrackLabel(it, context) } ?: ""
        val currentSubtitleName = currentSubtitleTrack.value?.let { TrackLogic.buildTrackLabel(it, context) } ?: ""
        val currentSpeed = _playbackSpeed.value?.label ?: PlaybackSpeed.X1_00.label

        return listOf(
            MenuItem(
                "audio",
                context.getString(R.string.menu_audio_title, (audioOptions.size - 1).coerceAtLeast(0)),
                currentAudioName,
                R.drawable.ic_audio_track
            ),
            MenuItem(
                "subtitles",
                context.getString(R.string.menu_subtitle_title, (subtitleOptions.size - 1).coerceAtLeast(0)),
                currentSubtitleName,
                R.drawable.ic_subtitles
            ),
            MenuItem(
                "speed",
                context.getString(R.string.playback_speed),
                currentSpeed,
                R.drawable.ic_speed
            ),
            MenuItem(
                "resize",
                context.getString(R.string.playback_zoom),
                getResizeModeLabel(context),
                getResizeModeIconResId(_resizeMode.value)
            ),
            MenuItem(
                "quick_settings",
                context.getString(R.string.menu_quick_settings_title),
                context.getString(R.string.menu_quick_settings_desc),
                R.drawable.ic_settings_3d
            ),
            MenuItem(
                "global_settings",
                context.getString(R.string.menu_global_settings_title),
                context.getString(R.string.menu_global_settings_desc),
                R.drawable.ic_build
            ),
            MenuItem(
                "audio_restore_debug",
                "Диагностика аудио",
                "Показать журнал восстановления аудио",
                R.drawable.ic_build
            ),
            MenuItem(
                "audio_restore_debug_toggle",
                "Показывать debug audio restore",
                if (showAudioRestoreDebugToast) "Включено" else "Выключено",
                R.drawable.ic_settings_3d
            )
        )
    }

    /**
     * Метод для выбора элемента плейлиста из UI.
     * Гарантирует сброс ошибок и включение видео.
     */
    fun playPlaylistItem(index: Int) {
        player?.let { p ->
            saveCurrentSettings()
            val isVideoError = _videoDisabledError.value == null
            // Сбрасываем ошибку в UI немедленно
            _videoDisabledError.value = null

            // Включаем видео обратно (на случай, если оно было отключено из-за ошибки)
            val params = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .build()
            p.trackSelectionParameters = params

            if (p.currentMediaItemIndex == index) {
                // Если мы уже на этом треке и была ошибка перезапускаем его
                if (isVideoError) p.seekTo(index, C.TIME_UNSET) // C.TIME_UNSET для Live означает "край"
            } else {
                // Переключение на другой трек
                p.seekToDefaultPosition(index)
            }
            p.prepare() // На всякий случай
            p.play()
        }
    }

    fun getNextTrackTitle(): String? {
        val p = player ?: return null
        if (p.hasNextMediaItem()) {
            val nextIndex = p.nextMediaItemIndex
            val item = p.getMediaItemAt(nextIndex)
            return item.mediaMetadata.title?.toString() ?: "Video ${nextIndex + 1}"
        }
        return null
    }

    fun getPrevTrackTitle(): String? {
        val p = player ?: return null
        if (p.hasPreviousMediaItem()) {
            val prevIndex = p.previousMediaItemIndex
            val item = p.getMediaItemAt(prevIndex)
            return item.mediaMetadata.title?.toString() ?: "Video ${prevIndex + 1}"
        }
        return null
    }

    fun getAudioTrackMenuItems(context: Context): List<MenuItem> {
        return audioOptions.mapIndexed { index, option ->
            val name = TrackLogic.buildTrackLabel(option, context)
            MenuItem(index.toString(), name, isSelected = index == currentAudioIndex)
        }
    }

    fun getSubtitleMenuItems(context: Context): List<MenuItem> {
        return subtitleOptions.mapIndexed { index, option ->
            val name = TrackLogic.buildTrackLabel(option, context)
            MenuItem(index.toString(), name, isSelected = index == currentSubtitleIndex)
        }
    }

    private fun getResizeModeLabel(context: Context): String {
        val mode = _resizeMode.value ?: ResizeMode.FIT
        return when (mode) {
            ResizeMode.FIT -> context.getString(R.string.resize_mode_fit)
            ResizeMode.ZOOM -> context.getString(R.string.resize_mode_zoom)
            ResizeMode.SCALE -> context.getString(R.string.playback_zoom_scale, _zoomScale.value ?: 115)
            ResizeMode.FILL -> context.getString(R.string.resize_mode_fill)
        }
    }

    fun selectTrackByIndex(trackType: Int, index: Int, persist: Boolean = true, reason: String = "user_selected", matchScore: Int? = null) {
        val options = if (trackType == C.TRACK_TYPE_AUDIO) audioOptions else subtitleOptions

        if (index in options.indices) {
            val option = options[index]

            if (trackType == C.TRACK_TYPE_AUDIO) {
                currentAudioIndex = index
                _currentAudioTrack.value = option
                if (persist) {
                    saveCurrentSettings()
                    sessionAudioPreference = option.toFingerprint(trackType)
                    hasExplicitSessionAudioPreference = true
                    Log.d(TAG, "Session audio preference set index=$index normalized=${sessionAudioPreference?.normalizedName} id=${sessionAudioPreference?.formatId} label=${option.nameFromMeta ?: option.format?.label}")
                }
                emitTrackSelectionChanged(option, index, "audio", reason, matchScore)
            } else {
                currentSubtitleIndex = index
                _currentSubtitleTrack.value = option
            }

            // Общая логика применения
            player?.let { p ->
                val builder = p.trackSelectionParameters.buildUpon()
                if (option.isOff) {
                    builder.setTrackTypeDisabled(trackType, true)
                } else {
                    builder.setTrackTypeDisabled(trackType, false)
                    option.group?.let {
                        builder.setOverrideForType(
                            TrackSelectionOverride(
                                it.mediaTrackGroup,
                                option.trackIndex
                            )
                        )
                    }
                }
                p.trackSelectionParameters = builder.build()
            }
        }
    }

    private fun normalizeTrackName(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val technicalTokens = setOf(
            "ac3", "eac3", "dts", "aac", "flac", "opus", "truehd",
            "2", "0", "5", "1", "7", "192k", "224k", "384k", "448k", "640k"
        )
        return value.lowercase()
            .replace('ё', 'е')
            .replace(Regex("[^\\p{L}\\p{Nd}\\s]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .split(" ")
            .filter { token ->
                if (token.isBlank()) return@filter false
                if (token.matches(Regex("\\d+k"))) return@filter false
                if (token.matches(Regex("(2|5|7)(0|1)"))) return@filter false
                token !in technicalTokens
            }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { null }
    }

    private fun TrackOption.toFingerprint(trackType: Int): TrackFingerprint = TrackFingerprint(
        trackType = trackType,
        formatId = format?.id,
        language = format?.language,
        label = format?.label,
        normalizedName = normalizeTrackName(nameFromMeta ?: format?.label),
        sampleMimeType = format?.sampleMimeType,
        channelCount = format?.channelCount?.takeIf { it > 0 },
        bitrate = format?.bitrate?.takeIf { it > 0 },
        ordinal = index,
        trackCount = audioOptions.size,
        nameFromMeta = nameFromMeta
    )

    private fun matchTrackByPreference(options: List<TrackOption>, preference: TrackFingerprint, mode: TrackRestoreMode = TrackRestoreMode.SMART, explicitNamePriority: Boolean = false): TrackMatchResult? {
        if (explicitNamePriority && !preference.normalizedName.isNullOrBlank()) {
            val prefName = preference.normalizedName
            val named = options.mapIndexed { i, o -> i to o.toFingerprint(preference.trackType) }.filter { !it.second.normalizedName.isNullOrBlank() && !options[it.first].isOff }
            val exact = named.firstOrNull { it.second.normalizedName == prefName }
            if (exact != null) return TrackMatchResult(exact.first, 100, "exact_normalized_name")
            val contains = named.firstOrNull { it.second.normalizedName!!.contains(prefName!!) }
            if (contains != null) return TrackMatchResult(contains.first, 80, "name_contains_preference")
            val reverseContains = named.firstOrNull { prefName!!.contains(it.second.normalizedName!!) && it.second.normalizedName!!.length >= 4 }
            if (reverseContains != null) return TrackMatchResult(reverseContains.first, 70, "preference_contains_name")
            if (named.isNotEmpty()) return null
        }
        var bestIdx: Int? = null
        var bestScore = Int.MIN_VALUE
        var bestSameOrdinalSameCount = false
        options.forEachIndexed { i, option ->
            val fp = option.toFingerprint(preference.trackType)
            var score = 0
            var sameOrdinalSameCount = false
            if (!preference.formatId.isNullOrBlank() && preference.formatId == fp.formatId &&
                (!explicitNamePriority || !isNumericTrackId(preference.formatId))) {
                return TrackMatchResult(i, 100, "exact_format_id")
            }
            if (!option.isOff && preference.ordinal == fp.ordinal && preference.ordinal > 0 && preference.trackCount != null && preference.trackCount == options.size) {
                sameOrdinalSameCount = true
                score += 30
            }
            if (!preference.normalizedName.isNullOrBlank() && !fp.normalizedName.isNullOrBlank()) {
                when {
                    preference.normalizedName == fp.normalizedName -> score += 60
                    fp.normalizedName.contains(preference.normalizedName) -> score += 45
                    preference.normalizedName.contains(fp.normalizedName) -> score += 35
                }
            }
            if (!preference.language.isNullOrBlank() && preference.language == fp.language) {
                score += if (!preference.normalizedName.isNullOrBlank()) 35 else 25
            }
            if (!preference.sampleMimeType.isNullOrBlank() && preference.sampleMimeType == fp.sampleMimeType) score += 20
            if (preference.channelCount != null && preference.channelCount == fp.channelCount) score += 15
            if (preference.bitrate != null && fp.bitrate != null && kotlin.math.abs(preference.bitrate - fp.bitrate) < 64_000) score += 5
            if (score > bestScore) {
                bestScore = score
                bestIdx = i
                bestSameOrdinalSameCount = sameOrdinalSameCount
            }
        }
        if (bestIdx == null) return null
        if (bestScore >= 45 || (bestSameOrdinalSameCount && bestScore >= 35)) {
            val languageOnly = bestScore <= 35 &&
                !preference.language.isNullOrBlank() &&
                preference.normalizedName.isNullOrBlank() &&
                preference.sampleMimeType.isNullOrBlank() &&
                preference.channelCount == null
            val sameLanguageCount = options.count { it.format?.language == preference.language }
            if (mode == TrackRestoreMode.SMART && languageOnly && sameLanguageCount > 1) return null
            val bestFp = options[bestIdx!!].toFingerprint(preference.trackType)
            val reason = when {
                !preference.normalizedName.isNullOrBlank() && preference.normalizedName == bestFp.normalizedName -> "exact_normalized_name"
                !preference.normalizedName.isNullOrBlank() && !bestFp.normalizedName.isNullOrBlank() && bestFp.normalizedName.contains(preference.normalizedName) -> "name_contains_preference"
                !preference.normalizedName.isNullOrBlank() && !bestFp.normalizedName.isNullOrBlank() && preference.normalizedName.contains(bestFp.normalizedName) -> "preference_contains_name"
                bestSameOrdinalSameCount && bestScore >= 35 -> "ordinal_layout_match"
                else -> "scored_match"
            }
            return TrackMatchResult(bestIdx, bestScore, reason)
        }
        return null
    }
    private fun isNumericTrackId(value: String?): Boolean = value?.matches(Regex("\\d+")) == true
    private fun reasonFromSessionMatch(reason: String): String = when (reason) {
        "exact_normalized_name", "name_contains_preference", "preference_contains_name" -> "restore_session_preference_name"
        "ordinal_layout_match" -> "restore_session_preference_ordinal"
        else -> "restore_session_preference"
    }
    private fun appendAudioRestoreDebugSnapshot(beforeIdx: Int, afterIdx: Int, pendingAudio: String?, pendingSub: String?, reason: String?, score: Int?) {
        val p = player
        val sessionPref = sessionAudioPreference
        val snapshot = AudioRestoreDebugSnapshot(
            timestamp = System.currentTimeMillis(),
            uri = currentUri,
            mediaIndex = p?.currentMediaItemIndex ?: -1,
            mediaTitle = p?.currentMediaItem?.mediaMetadata?.title?.toString(),
            pendingAudioId = pendingAudio,
            pendingSubtitleId = pendingSub,
            hasExplicitSessionAudioPreference = hasExplicitSessionAudioPreference,
            sessionPreference = "normalizedName=${sessionPref?.normalizedName};formatId=${sessionPref?.formatId};ordinal=${sessionPref?.ordinal};trackCount=${sessionPref?.trackCount}",
            selectedBeforeIndex = beforeIdx,
            selectedAfterIndex = afterIdx,
            restoreReason = reason,
            restoreScore = score,
            options = audioOptions.mapIndexed { idx, option ->
                AudioOptionDebug(idx, option.format?.id, normalizeTrackName(option.nameFromMeta ?: option.format?.label), option.format?.label, option.nameFromMeta, option.format?.language, option.format?.channelCount, option.format?.sampleMimeType, option.format?.bitrate, idx == afterIdx, option.isOff)
            }
        )
        audioRestoreDebugBuffer.addLast(snapshot)
        while (audioRestoreDebugBuffer.size > maxAudioRestoreDebugEntries) audioRestoreDebugBuffer.removeFirst()
    }
    fun getAudioRestoreDebugText(): String {
        if (audioRestoreDebugBuffer.isEmpty()) return "=== Audio restore debug ===\nNo entries yet."
        return buildString {
            appendLine("=== Audio restore debug ===")
            audioRestoreDebugBuffer.forEach { s ->
                appendLine("ts=${s.timestamp}")
                appendLine("uri=${s.uri}")
                appendLine("mediaIndex=${s.mediaIndex}")
                appendLine("title=${s.mediaTitle}")
                appendLine("pendingAudioId=${s.pendingAudioId}")
                appendLine("pendingSubtitleId=${s.pendingSubtitleId}")
                appendLine("hasExplicitSessionAudioPreference=${s.hasExplicitSessionAudioPreference}")
                appendLine("sessionPref=${s.sessionPreference}")
                appendLine("selectedBeforeIndex=${s.selectedBeforeIndex}")
                appendLine("selectedAfterIndex=${s.selectedAfterIndex}")
                appendLine("restoreReason=${s.restoreReason}")
                appendLine("restoreScore=${s.restoreScore}")
                appendLine("Options:")
                s.options.forEach { o ->
                    if (o.isOff) appendLine("[${o.index}] OFF") else appendLine("[${o.index}] id=${o.formatId} normalized=${o.normalizedName} label=${o.label} nameFromMeta=${o.nameFromMeta} lang=${o.language} channels=${o.channelCount} mime=${o.sampleMimeType} bitrate=${o.bitrate} selected=${o.isSelected}")
                }
                appendLine()
            }
        }
    }
    fun isShowAudioRestoreDebugEnabled(): Boolean = showAudioRestoreDebugToast
    fun toggleShowAudioRestoreDebug() {
        showAudioRestoreDebugToast = !showAudioRestoreDebugToast
        repository.setShowAudioRestoreDebugEnabled(showAudioRestoreDebugToast)
    }

    private fun emitTrackSelectionChanged(option: TrackOption, index: Int, trackType: String, reason: String, matchScore: Int?) {
        if (!bridgeConfig.enabled) return
        bridgeDispatcher?.emit(
            BridgeEvent.TrackSelectionChanged(
                sessionId = bridgeConfig.sessionId,
                ts = System.currentTimeMillis(),
                uri = player?.currentMediaItem?.localConfiguration?.uri?.toString(),
                trackType = trackType,
                trackIndex = index,
                trackId = option.format?.id,
                language = option.format?.language,
                label = option.nameFromMeta ?: option.format?.label,
                sampleMimeType = option.format?.sampleMimeType,
                channelCount = option.format?.channelCount,
                reason = reason,
                matchScore = matchScore
            )
        )
    }

    // Логика изменения настроек (вызывается из Fragment по команде SettingsViewModel)
    fun changeSettingValue(settingType: SettingType, direction: Int) {
        when (settingType) {
            SettingType.AUDIO_TRACK -> {
                val options = audioOptions
                if (options.isNotEmpty()) {
                    val nextIndex = (currentAudioIndex + direction + options.size) % options.size
                    selectTrackByIndex(C.TRACK_TYPE_AUDIO, nextIndex)
                }
            }
            SettingType.SUBTITLES -> {
                val options = subtitleOptions
                if (options.isNotEmpty()) {
                    val nextIndex = (currentSubtitleIndex + direction + options.size) % options.size
                    selectTrackByIndex(C.TRACK_TYPE_TEXT, nextIndex)
                }
            }
        }
    }

    /**
     * Возвращает список ID ресурсов (Int) или нелокализованные строки.
     * Локализация должна происходить во Fragment.
     */
    fun getOptionsForSetting(type: SettingType, context: Context): Pair<List<String>, Int>? {
        return when (type) {
            SettingType.AUDIO_TRACK -> {
                val list = audioOptions.map { TrackLogic.buildTrackLabel(it, context) }
                val idx = currentAudioIndex
                Pair(list, idx.coerceAtLeast(0))
            }
            SettingType.SUBTITLES -> {
                val list = subtitleOptions.map { TrackLogic.buildTrackLabel(it, context) }
                val idx = currentSubtitleIndex
                Pair(list, idx.coerceAtLeast(0))
            }
        }
    }

    fun setVideoQuality(option: VideoQualityOption) {
        player?.let { p ->
            val builder = p.trackSelectionParameters.buildUpon()
            if (option.isAuto) {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            } else {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                option.group?.let {
                    builder.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, option.trackIndex))
                }
            }
            p.trackSelectionParameters = builder.build()
            _currentQualityName.value = option.name
        }
    }

    private fun updateAvailableSettings() {
        _availableSettings.value = listOf(SettingType.AUDIO_TRACK, SettingType.SUBTITLES)
    }

    // --- Hot Restart Logic ---

    fun checkSettingsAndRestart() {
        val currentHash = repository.getHardSettingsSignature()

        if (currentHash != lastSettingsHash) {
            restartPlayer()
            lastSettingsHash = currentHash
        } else {
            playerManager.updateTrackSelectionParameters()
        }
    }

    fun restartPlayer() {
        // Сохраняем текущее состояние перед пересозданием
        playerManager.releasePlayer(isFinalRelease = false, saveState = true)
        playerManager.initializePlayer()
    }

    fun clearToast() {
        _toastMessage.value = null
    }


    private fun normalizeDuration(value: Long): Long? {
        return if (value <= 0 || value == C.TIME_UNSET) null else value
    }

    private fun bridgeTransitionReason(reason: Int): String {
        return when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "auto"
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "seek"
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "playlist_changed"
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "repeat"
            else -> "unknown"
        }
    }

    fun emitUserAction(action: String, payload: Map<String, String> = emptyMap()) {
        if (!bridgeConfig.enabled || !bridgeConfig.emitUserActions) return

        bridgeDispatcher?.emit(
            BridgeEvent.UserAction(
                sessionId = bridgeConfig.sessionId,
                ts = System.currentTimeMillis(),
                uri = player?.currentMediaItem?.localConfiguration?.uri?.toString(),
                action = action,
                payload = payload
            )
        )
    }

    override fun onCleared() {
        super.onCleared()
        handler.removeCallbacks(progressUpdater)
        playerManager.releasePlayer(isFinalRelease = true, saveState = false)
    }
}
