package top.rootu.dddplayer.compat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem as Media3MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import okhttp3.OkHttpClient
import top.rootu.dddplayer.bridge.BridgeConfig
import top.rootu.dddplayer.bridge.BridgeDispatcher
import top.rootu.dddplayer.bridge.BridgeEvent
import top.rootu.dddplayer.bridge.BridgeMediaItem
import top.rootu.dddplayer.bridge.BridgeMode
import top.rootu.dddplayer.bridge.BridgeTransport
import top.rootu.dddplayer.bridge.BroadcastTransport
import top.rootu.dddplayer.bridge.CompositeTransport
import top.rootu.dddplayer.bridge.DddSyncContext
import top.rootu.dddplayer.bridge.DddSyncRemoteClient
import top.rootu.dddplayer.bridge.LocalBridgeManager
import top.rootu.dddplayer.bridge.LocalBridgeTransport
import top.rootu.dddplayer.model.MediaItem
import top.rootu.dddplayer.utils.IntentUtils
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

/**
 * Compatibility layer used by the Just+ based 0.0.15 transition build.
 *
 * Playback remains owned by Just+/Media3. This object only observes Player events and feeds the
 * existing DDD broadcast/local bridge and DddSync server contracts. Keeping this in the legacy
 * library means the Just+ fork needs only two small hooks: attach after player creation and detach
 * from Activity.onDestroy().
 */
object JustPlusBridgeInterop {
    const val EXTRA_ORIGINAL_URI = "ddd_original_uri"
    const val EXTRA_FORCE_LEGACY = "ddd_force_legacy"

    private val main = Handler(Looper.getMainLooper())
    private val sessions = WeakHashMap<Activity, Session>()

    @JvmStatic
    @Synchronized
    fun attach(activity: Activity, launchIntent: Intent, player: Player) {
        val existing = sessions[activity]
        if (existing != null) {
            existing.replacePlayer(player)
            return
        }

        val intent = restoreOriginalIntent(launchIntent)
        val config = runCatching { IntentUtils.parseBridgeConfig(intent) }
            .getOrElse {
                Log.w(TAG, "Bridge config parse failed", it)
                BridgeConfig()
            }
        val parsed = runCatching { IntentUtils.parseIntent(activity, intent) }
            .getOrElse {
                Log.w(TAG, "DDD sync intent parse failed", it)
                emptyList<MediaItem>() to 0
            }
        val items = parsed.first

        val transports = mutableListOf<BridgeTransport>()
        if (config.enabled && (config.mode == BridgeMode.BROADCAST || config.mode == BridgeMode.BOTH)) {
            transports += BroadcastTransport(activity.applicationContext, config)
        }
        if (config.enabled && (config.mode == BridgeMode.LOCAL || config.mode == BridgeMode.BOTH)) {
            val store = LocalBridgeManager.startOrReuse(config)
            transports += LocalBridgeTransport(config, store)
        }
        val dispatcher = when (transports.size) {
            0 -> null
            1 -> BridgeDispatcher(config, transports.first())
            else -> BridgeDispatcher(config, CompositeTransport(transports))
        }

        val hasRemote = items.any { it.dddSyncContext?.enabled == true }
        val remoteClient = if (hasRemote) {
            DddSyncRemoteClient(
                OkHttpClient.Builder()
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .writeTimeout(5, TimeUnit.SECONDS)
                    .build()
            )
        } else null

        val session = Session(activity, config, dispatcher, items, remoteClient, player)
        sessions[activity] = session
        session.start()
    }

    @JvmStatic
    @Synchronized
    fun detach(activity: Activity, reason: String = "activity_destroyed") {
        sessions.remove(activity)?.stop(reason)
    }

    private fun restoreOriginalIntent(intent: Intent): Intent {
        val original = intent.getStringExtra(EXTRA_ORIGINAL_URI)?.takeIf { it.isNotBlank() }
            ?: return Intent(intent)
        return Intent(intent).setData(runCatching { Uri.parse(original) }.getOrNull() ?: intent.data)
    }

    private class Session(
        private val activity: Activity,
        private val config: BridgeConfig,
        private val dispatcher: BridgeDispatcher?,
        private val items: List<MediaItem>,
        private val remoteClient: DddSyncRemoteClient?,
        player: Player
    ) {
        private var player: Player = player
        private var listener: Player.Listener? = null
        private var lastPosition = 0L
        private var lastIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        private var ended = false
        private var stopped = false

        private val tick = object : Runnable {
            override fun run() {
                if (stopped) return
                emitPosition("tick")
                main.postDelayed(this, config.positionIntervalMs.coerceAtLeast(250L))
            }
        }

        fun start() {
            installListener()
            val now = System.currentTimeMillis()
            emit(
                BridgeEvent.SessionStarted(
                    sessionId = sessionId(),
                    ts = now,
                    uri = currentUri(),
                    title = currentTitle(),
                    playlistSize = player.mediaItemCount,
                    startIndex = player.currentMediaItemIndex.coerceAtLeast(0),
                    startPosition = player.currentPosition.coerceAtLeast(0L),
                    currentItem = currentBridgeItem()
                )
            )
            lastPosition = player.currentPosition.coerceAtLeast(0L)
            if (config.enabled || remoteClient != null) main.post(tick)
        }

        fun replacePlayer(next: Player) {
            listener?.let { runCatching { player.removeListener(it) } }
            player = next
            ended = false
            installListener()
        }

        fun stop(reason: String) {
            if (stopped) return
            stopped = true
            main.removeCallbacks(tick)
            listener?.let { runCatching { player.removeListener(it) } }
            emit(
                BridgeEvent.SessionFinished(
                    sessionId = sessionId(),
                    ts = System.currentTimeMillis(),
                    uri = currentUri(),
                    position = player.currentPosition.coerceAtLeast(0L),
                    duration = durationOrNull(),
                    endBy = reason,
                    windowIndex = player.currentMediaItemIndex.coerceAtLeast(0),
                    playlistSize = player.mediaItemCount,
                    title = currentTitle()
                )
            )
            if (config.mode == BridgeMode.LOCAL || config.mode == BridgeMode.BOTH) {
                LocalBridgeManager.stopDelayed()
            }
        }

        private fun installListener() {
            val l = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    emitState(if (isPlaying) "playing" else "paused")
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    emitState(
                        when (playbackState) {
                            Player.STATE_BUFFERING -> "buffering"
                            Player.STATE_READY -> "ready"
                            Player.STATE_ENDED -> "ended"
                            else -> "idle"
                        }
                    )
                    if (playbackState == Player.STATE_ENDED && !ended) {
                        ended = true
                        emit(
                            BridgeEvent.PlaybackEnded(
                                sessionId = sessionId(),
                                ts = System.currentTimeMillis(),
                                uri = currentUri(),
                                windowIndex = player.currentMediaItemIndex.coerceAtLeast(0),
                                playlistSize = player.mediaItemCount,
                                title = currentTitle(),
                                position = player.currentPosition.coerceAtLeast(0L),
                                duration = durationOrNull()
                            )
                        )
                    }
                }

                override fun onMediaItemTransition(mediaItem: Media3MediaItem?, reason: Int) {
                    ended = false
                    lastIndex = player.currentMediaItemIndex.coerceAtLeast(0)
                    emit(
                        BridgeEvent.PlaylistItemChanged(
                            sessionId = sessionId(),
                            ts = System.currentTimeMillis(),
                            uri = currentUri(),
                            windowIndex = lastIndex,
                            playlistSize = player.mediaItemCount,
                            title = currentTitle(),
                            reason = "media_transition_$reason",
                            position = player.currentPosition.coerceAtLeast(0L),
                            duration = durationOrNull(),
                            hasPrevious = player.hasPreviousMediaItem(),
                            hasNext = player.hasNextMediaItem(),
                            currentItem = currentBridgeItem()
                        )
                    )
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                        emit(
                            BridgeEvent.SeekCompleted(
                                sessionId = sessionId(),
                                ts = System.currentTimeMillis(),
                                uri = currentUri(),
                                fromPosition = oldPosition.positionMs,
                                toPosition = newPosition.positionMs,
                                windowIndex = player.currentMediaItemIndex.coerceAtLeast(0)
                            )
                        )
                    }
                    lastPosition = newPosition.positionMs.coerceAtLeast(0L)
                }

                override fun onPlayerError(error: PlaybackException) {
                    emit(
                        BridgeEvent.Error(
                            sessionId = sessionId(),
                            ts = System.currentTimeMillis(),
                            uri = currentUri(),
                            code = error.errorCodeName,
                            errorCode = error.errorCode,
                            message = error.message,
                            windowIndex = player.currentMediaItemIndex.coerceAtLeast(0),
                            position = player.currentPosition.coerceAtLeast(0L),
                            duration = durationOrNull(),
                            bufferedPosition = player.bufferedPosition.coerceAtLeast(0L),
                            bufferedPercentage = player.bufferedPercentage,
                            playlistSize = player.mediaItemCount,
                            title = currentTitle(),
                            fatal = true
                        )
                    )
                }
            }
            listener = l
            player.addListener(l)
        }

        private fun emitState(reason: String) {
            emit(
                BridgeEvent.PlaybackStateChanged(
                    sessionId = sessionId(),
                    ts = System.currentTimeMillis(),
                    uri = currentUri(),
                    isPlaying = player.isPlaying,
                    isBuffering = player.playbackState == Player.STATE_BUFFERING,
                    position = player.currentPosition.coerceAtLeast(0L),
                    duration = durationOrNull(),
                    windowIndex = player.currentMediaItemIndex.coerceAtLeast(0),
                    title = currentTitle(),
                    reason = reason
                )
            )
        }

        private fun emitPosition(reason: String) {
            val position = player.currentPosition.coerceAtLeast(0L)
            lastPosition = position
            emit(
                BridgeEvent.PositionTick(
                    sessionId = sessionId(),
                    ts = System.currentTimeMillis(),
                    uri = currentUri(),
                    position = position,
                    duration = durationOrNull(),
                    bufferedPosition = player.bufferedPosition.coerceAtLeast(0L),
                    bufferedPercentage = player.bufferedPercentage,
                    windowIndex = player.currentMediaItemIndex.coerceAtLeast(0),
                    title = currentTitle(),
                    reason = reason
                )
            )
        }

        private fun emit(event: BridgeEvent) {
            dispatcher?.emit(event)
            currentSyncContext()?.let { context -> remoteClient?.send(event, context) }
        }

        private fun currentItem(): MediaItem? = items.getOrNull(player.currentMediaItemIndex.coerceAtLeast(0))

        private fun currentSyncContext(): DddSyncContext? = currentItem()?.dddSyncContext

        private fun sessionId(): String? = config.sessionId ?: currentSyncContext()?.sessionId

        private fun currentUri(): String? = player.currentMediaItem?.localConfiguration?.uri?.toString()
            ?: currentItem()?.uri?.toString()

        private fun currentTitle(): String? = player.currentMediaItem?.mediaMetadata?.title?.toString()
            ?: currentItem()?.title

        private fun durationOrNull(): Long? = player.duration.takeIf { it > 0L && it != C.TIME_UNSET }

        private fun currentBridgeItem(): BridgeMediaItem? {
            val item = currentItem()
            return BridgeMediaItem(
                uri = currentUri(),
                title = currentTitle(),
                filename = item?.filename,
                source = "justplus"
            )
        }
    }

    private const val TAG = "JustPlus/DDDInterop"
}
