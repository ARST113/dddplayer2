package top.rootu.dddplayer.player

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import top.rootu.dddplayer.engine.AvSyncController
import top.rootu.dddplayer.engine.EngineAudioPump
import top.rootu.dddplayer.engine.EngineAudioSink
import top.rootu.dddplayer.engine.EngineError
import top.rootu.dddplayer.engine.EngineProbe
import top.rootu.dddplayer.engine.NativeAudioDecoder
import top.rootu.dddplayer.engine.NativeDemuxer
import top.rootu.dddplayer.engine.NativeRenderer
import top.rootu.dddplayer.engine.NativeVideoDecoder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * Реальный backend единого FFmpeg/MediaCodec/GLES-движка.
 *
 * Видео и AudioTrack обслуживаются разными потоками: блокирующий audio write не
 * имеет права задерживать кадры. AudioTrack playback head — master clock; без
 * аудиодорожки используется монотонный clock видеопотока.
 */
class NativePlaybackBackend(
    private val dataSourceFactory: DataSource.Factory
) : PlaybackBackend {
    private val main = Handler(Looper.getMainLooper())
    private val surfaceGeneration = AtomicInteger()

    @Volatile private var listener: PlaybackBackend.Listener? = null
    @Volatile private var holder: SurfaceHolder? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var demuxHandle = 0L
    @Volatile private var stopRequested = false
    @Volatile private var playRequested = true
    @Volatile private var pendingSeekMs = NO_SEEK
    @Volatile private var positionMs = 0L
    @Volatile private var durationMs = 0L
    @Volatile private var bufferedPositionMs = 0L
    @Volatile private var prepared = false
    @Volatile private var playbackSpeed = 1f
    @Volatile private var audioSink: EngineAudioSink? = null
    @Volatile private var sourceUri: Uri? = null
    @Volatile private var sourceHeaders: Map<String, String> = emptyMap()
    @Volatile private var requestedAudioTrackId: Int? = null
    @Volatile private var availableAudioTracks: List<BackendAudioTrack> = emptyList()
    @Volatile private var selectedAudioTrackId: Int? = null
    @Volatile private var forceSoftwareVideo = false

    override fun attachSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        holder = surfaceHolder
        surfaceGeneration.incrementAndGet()
    }

    override fun prepare(uri: Uri, headers: Map<String, String>, startPositionMs: Long) {
        requestedAudioTrackId = null
        availableAudioTracks = emptyList()
        selectedAudioTrackId = null
        forceSoftwareVideo = false
        prepareInternal(uri, headers, startPositionMs, shouldPlay = true)
    }

    private fun prepareInternal(
        uri: Uri,
        headers: Map<String, String>,
        startPositionMs: Long,
        shouldPlay: Boolean
    ) {
        stop()
        stopRequested = false
        playRequested = shouldPlay
        pendingSeekMs = NO_SEEK
        sourceUri = uri
        sourceHeaders = headers.toMap()
        positionMs = startPositionMs.coerceAtLeast(0L)
        durationMs = 0L
        bufferedPositionMs = 0L
        prepared = false
        post { listener?.onBuffering() }
        val useSoftwareVideo = forceSoftwareVideo
        worker = Thread(
            { runPlayback(uri, headers, startPositionMs.coerceAtLeast(0L), useSoftwareVideo) },
            "DddNativeVideo"
        ).also { it.start() }
    }

    override fun play() {
        playRequested = true
    }

    override fun pause() {
        playRequested = false
    }

    override fun seekTo(positionMs: Long) {
        pendingSeekMs = positionMs.coerceIn(0L, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
    }

    override fun stop() {
        stopRequested = true
        val handle = demuxHandle
        if (handle != 0L) NativeDemuxer.stop(handle)
        val thread = worker
        if (thread != null && thread !== Thread.currentThread()) {
            thread.interrupt()
            thread.join(STOP_JOIN_MS)
        }
        worker = null
        prepared = false
        positionMs = 0L
    }

    override fun release() = stop()
    override fun getPositionMs(): Long = positionMs
    override fun getDurationMs(): Long = durationMs
    override fun isPlaying(): Boolean = prepared && playRequested && !stopRequested
    override fun getBufferedPositionMs(): Long = bufferedPositionMs
    override fun getBufferedPercentage(): Int = if (durationMs > 0) {
        (bufferedPositionMs * 100 / durationMs).toInt().coerceIn(0, 100)
    } else 0
    override fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed.coerceIn(EngineAudioSink.MIN_SPEED, EngineAudioSink.MAX_SPEED)
        audioSink?.speed = playbackSpeed
    }
    override fun setListener(listener: PlaybackBackend.Listener?) { this.listener = listener }

    fun getAudioTracks(): List<BackendAudioTrack> = availableAudioTracks.map {
        it.copy(selected = it.id == selectedAudioTrackId)
    }

    fun getSelectedAudioTrackId(): Int? = selectedAudioTrackId

    fun selectAudioTrack(id: Int): Boolean {
        if (availableAudioTracks.none { it.id == id }) return false
        if (selectedAudioTrackId == id) return true
        val uri = sourceUri ?: return false
        val resumeAt = positionMs
        val shouldPlay = playRequested
        requestedAudioTrackId = id
        prepareInternal(uri, sourceHeaders, resumeAt, shouldPlay)
        return true
    }

    private fun runPlayback(
        uri: Uri,
        headers: Map<String, String>,
        startPositionMs: Long,
        useSoftwareVideo: Boolean
    ) {
        val runThread = Thread.currentThread()
        var handle = 0L
        var video: NativeVideoDecoder? = null
        var audio: NativeAudioDecoder? = null
        var sink: EngineAudioSink? = null
        var pump: EngineAudioPump? = null
        var renderer: NativeRenderer? = null
        try {
            val source = DataSourceEngineIo.open(
                dataSourceFactory.createDataSource(),
                DataSpec.Builder().setUri(uri).setHttpRequestHeaders(headers).build()
            )
            handle = NativeDemuxer.open(null, source)
            demuxHandle = handle
            val probe = NativeDemuxer.probe(handle)
            durationMs = probe.durationMs
            if (probe.bestVideoIndex < 0 && probe.bestAudioIndex < 0) {
                throw EngineError(EngineError.Code.UNSUPPORTED, "в источнике нет audio/video")
            }
            val atmosTrack = probe.tracks.audio.firstOrNull { track ->
                track.label.contains("Atmos", ignoreCase = true) ||
                        track.description.orEmpty().contains("Atmos", ignoreCase = true)
            }
            val audioIndex = requestedAudioTrackId
                ?.takeIf { requested -> probe.tracks.audio.any { it.id == requested } }
                ?: atmosTrack?.id
                ?: probe.bestAudioIndex
            if (atmosTrack != null && requestedAudioTrackId == null) {
                Log.i(TAG, "preferred Atmos track=${atmosTrack.id} label=${atmosTrack.label}")
            }
            availableAudioTracks = probe.tracks.audio
            selectedAudioTrackId = audioIndex.takeIf { it >= 0 }
            if (!NativeDemuxer.selectStreams(
                    handle, probe.bestVideoIndex, audioIndex, -1
                ) || !NativeDemuxer.start(handle)) {
                throw EngineError(EngineError.Code.DEMUX, "не запустился demux")
            }
            if (startPositionMs > 0) seekDemux(handle, startPositionMs)

            if (audioIndex >= 0) {
                audio = NativeAudioDecoder.create(handle, audioIndex)
                sink = EngineAudioSink(audio.sampleRate, audio.channels)
                sink.speed = playbackSpeed
                audioSink = sink
                pump = EngineAudioPump(audio, sink)
                pump.start()
            }
            if (probe.bestVideoIndex >= 0) {
                video = createVideoDecoder(handle, probe, useSoftwareVideo)
            }

            prepared = true
            post {
                listener?.onVideoSizeChanged(
                    probe.videoFormat.width,
                    probe.videoFormat.height,
                    probe.videoFormat.pixelAspectRatio
                )
                if (playRequested) listener?.onPlaying() else listener?.onPaused()
            }
            runLoop(handle, probe, video, audio, sink, pump, renderer)
        } catch (_: InterruptedException) {
            // Обычный stop/release.
        } catch (t: Throwable) {
            val retryInSoftware = !stopRequested && !useSoftwareVideo &&
                (t as? EngineError)?.code == EngineError.Code.VIDEO_DECODER
            if (retryInSoftware) {
                val retryAtMs = positionMs
                Log.w(TAG, "MediaCodec failed; restarting native pipeline with libavcodec at ${retryAtMs}ms", t)
                forceSoftwareVideo = true
                post {
                    if (!stopRequested && worker === runThread && sourceUri == uri) {
                        prepareInternal(uri, headers, retryAtMs, playRequested)
                    }
                }
            } else if (!stopRequested) {
                post { listener?.onError(t) }
            }
        } finally {
            pump?.stop()
            renderer?.release()
            sink?.release()
            if (audioSink === sink) audioSink = null
            audio?.release()
            video?.release()
            if (handle != 0L) {
                NativeDemuxer.stop(handle)
                NativeDemuxer.close(handle)
            }
            if (demuxHandle == handle) demuxHandle = 0L
            prepared = false
        }
    }

    private fun runLoop(
        handle: Long,
        probe: EngineProbe,
        video: NativeVideoDecoder?,
        audio: NativeAudioDecoder?,
        sink: EngineAudioSink?,
        initialPump: EngineAudioPump?,
        initialRenderer: NativeRenderer?
    ) {
        var pump = initialPump
        var renderer = initialRenderer
        var seenSurfaceGeneration = -1
        var wasPlaying = true
        var fallbackAnchorPtsUs: Long? = null
        var fallbackAnchorNs = 0L
        var lastPositionCallbackNs = 0L
        var renderedSurfaceGeneration = -1
        val sync = AvSyncController()

        try {
        while (!stopRequested) {
            val currentSurfaceGeneration = surfaceGeneration.get()
            if (video?.isSurfaceOutput == true) {
                // MediaCodec is configured with the same SurfaceHolder surface.
                // Demux, audio clock and sync remain in this backend; only the
                // decoded AV1 buffer presentation bypasses the CPU/GLES upload.
                if (seenSurfaceGeneration < 0) {
                    seenSurfaceGeneration = currentSurfaceGeneration
                }
            } else if (renderer == null || seenSurfaceGeneration != currentSurfaceGeneration) {
                renderer?.release()
                renderer = null
                renderer = holder?.surface?.takeIf { it.isValid }
                    ?.let { NativeRenderer.forSurface(it) }
                if (renderer != null) {
                    renderer.setHdrParams(NativeRenderer.HdrParams.from(probe.color, 500f))
                    seenSurfaceGeneration = currentSurfaceGeneration
                }
                // A backend may legitimately run before/without a Surface
                // (audio clock, background playback and integration tests).
                // Keep draining decoded video frames instead of blocking the
                // whole demux here; otherwise the video queue fills, audio
                // stops and position remains forever at zero. We intentionally
                // do not acknowledge the generation until EGL creation works,
                // so a later Surface or transient EGL_BAD_ALLOC is retried.
            }

            val seek = pendingSeekMs
            if (seek != NO_SEEK) {
                pendingSeekMs = NO_SEEK
                pump?.stop()
                sink?.flush()
                seekDemux(handle, seek)
                video?.flush()
                audio?.flush()
                positionMs = seek
                fallbackAnchorPtsUs = null
                pump = if (audio != null && sink != null) {
                    EngineAudioPump(audio, sink).also {
                        it.start()
                        if (!playRequested) it.pause()
                    }
                } else null
                post { listener?.onBuffering() }
            }

            if (!playRequested) {
                if (wasPlaying) {
                    pump?.pause()
                    post { listener?.onPaused() }
                    wasPlaying = false
                }
                Thread.sleep(IDLE_MS)
                continue
            } else if (!wasPlaying) {
                pump?.resume()
                fallbackAnchorPtsUs = positionMs * 1000
                fallbackAnchorNs = System.nanoTime()
                post { listener?.onPlaying() }
                wasPlaying = true
            }

            pump?.error?.let { throw it }
            updateStats(handle)

            if (video == null) {
                val audioPosition = sink?.positionUs
                if (audioPosition != null) positionMs = max(0L, audioPosition / 1000)
                if (pump?.isEos == true && (sink?.queuedDurationUs ?: 0L) < 10_000) break
                postPositionIfDue(lastPositionCallbackNs).also { lastPositionCallbackNs = it }
                Thread.sleep(IDLE_MS)
                continue
            }

            // При наличии audio не выпускаем видео до появления реального clock.
            if (audio != null && sink?.positionUs == null) {
                Thread.sleep(2)
                continue
            }

            when (video.nextFrame(20)) {
                NativeVideoDecoder.Step.AGAIN -> Thread.sleep(1)
                NativeVideoDecoder.Step.ERROR ->
                    throw EngineError(EngineError.Code.VIDEO_DECODER,
                        "ошибка ${video.decoderName}")
                NativeVideoDecoder.Step.EOS -> {
                    if (pump == null || (pump.isEos && (sink?.queuedDurationUs ?: 0L) < 10_000)) break
                    Thread.sleep(IDLE_MS)
                }
                NativeVideoDecoder.Step.FRAME -> {
                    try {
                        val videoPts = video.framePtsUs
                        if (audio == null) {
                            if (fallbackAnchorPtsUs == null) {
                                fallbackAnchorPtsUs = videoPts
                                fallbackAnchorNs = System.nanoTime()
                            }
                        }

                        var decided = false
                        while (!decided && playRequested && pendingSeekMs == NO_SEEK && !stopRequested) {
                            // A new EGLSurface starts black. Always present one decoded frame
                            // before applying late-frame dropping; otherwise a slow software
                            // decoder can remain permanently behind the audio clock and the
                            // user sees a black screen even though decoding is progressing.
                            val mustPrimeSurface = renderedSurfaceGeneration != seenSurfaceGeneration
                            val decision = if (mustPrimeSurface) {
                                AvSyncController.Decision.Render
                            } else {
                                sync.decide(videoPts, clockNow(
                                    audio, sink, fallbackAnchorPtsUs, fallbackAnchorNs
                                ))
                            }
                            when (decision) {
                                AvSyncController.Decision.Drop -> decided = true
                                AvSyncController.Decision.Render -> {
                                    val presented = if (video.isSurfaceOutput) {
                                        video.renderToSurface()
                                    } else {
                                        val target = renderer
                                        target != null &&
                                            video.uploadToRenderer(target) &&
                                            target.draw(
                                                probe.videoFormat.rotationDegrees,
                                                NativeRenderer.ScaleMode.FIT
                                            ) &&
                                            target.swap()
                                    }
                                    if (presented) {
                                        positionMs = max(0L, videoPts / 1000)
                                        if (mustPrimeSurface) {
                                            renderedSurfaceGeneration = seenSurfaceGeneration
                                            Log.i(TAG, "video surface primed ptsUs=$videoPts generation=$seenSurfaceGeneration")
                                        }
                                    }
                                    decided = true
                                }
                                is AvSyncController.Decision.Wait -> Thread.sleep(decision.milliseconds)
                            }
                        }
                    } finally {
                        video.releaseFrame()
                    }
                }
            }
            sink?.positionUs?.let { positionMs = max(0L, it / 1000) }
            postPositionIfDue(lastPositionCallbackNs).also { lastPositionCallbackNs = it }
        }

        if (!stopRequested) post { listener?.onEnded() }
        } finally {
            pump?.stop()
            renderer?.release()
        }
    }

    private fun createVideoDecoder(
        handle: Long,
        probe: EngineProbe,
        useSoftwareVideo: Boolean
    ): NativeVideoDecoder {
        if (useSoftwareVideo) {
            return NativeVideoDecoder.create(
                handle,
                probe.bestVideoIndex,
                forceSoftware = true
            )
        }

        // AV1 remains inside the unified engine. Pixel's fast dav1d component
        // exposes graphic buffers only, so configure it with our final Surface.
        // The demuxer, audio path, seek, playlist and A/V sync remain Native;
        // this is a decoder output mode, not a Media3/VLC backend switch.
        if (probe.videoFormat.mimeType == "video/av01") {
            val surface = holder?.surface?.takeIf { it.isValid }
            if (surface != null) {
                for (codec in PREFERRED_AV1_SURFACE_DECODERS) {
                    try {
                        return NativeVideoDecoder.create(
                            handle,
                            probe.bestVideoIndex,
                            preferTenBit = false,
                            allowEscalate = false,
                            codecName = codec,
                            surface = surface
                        ).also {
                            Log.i(TAG, "unified AV1 decoder=${it.decoderName} output=Surface")
                        }
                    } catch (t: EngineError) {
                        Log.w(TAG, "$codec Surface output unavailable", t)
                    }
                }
            }
            Log.w(TAG, "AV1 Surface unavailable; trying the native ByteBuffer/libavcodec ladder")
        }
        return NativeVideoDecoder.create(handle, probe.bestVideoIndex)
    }

    private fun clockNow(
        audio: NativeAudioDecoder?,
        sink: EngineAudioSink?,
        fallbackPtsUs: Long?,
        fallbackNs: Long
    ): Long? = if (audio != null) sink?.positionUs
    else fallbackPtsUs?.plus((System.nanoTime() - fallbackNs) / 1000)

    private fun seekDemux(handle: Long, positionMs: Long) {
        val before = NativeDemuxer.stats(handle)?.seeks ?: 0
        if (!NativeDemuxer.seek(handle, positionMs)) {
            throw EngineError(EngineError.Code.DEMUX, "seek не принят")
        }
        val deadline = System.nanoTime() + SEEK_TIMEOUT_NS
        while (!stopRequested && System.nanoTime() < deadline) {
            if ((NativeDemuxer.stats(handle)?.seeks ?: before) > before) return
            Thread.sleep(5)
        }
        if (!stopRequested) throw EngineError(EngineError.Code.TIMEOUT, "demux seek timeout")
    }

    private fun updateStats(handle: Long) {
        NativeDemuxer.stats(handle)?.let { bufferedPositionMs = it.bufferedPositionMs }
    }

    private fun postPositionIfDue(previousNs: Long): Long {
        val now = System.nanoTime()
        if (now - previousNs >= POSITION_CALLBACK_NS) {
            val p = positionMs
            val d = durationMs
            post { listener?.onPositionChanged(p, d) }
            return now
        }
        return previousNs
    }

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    companion object {
        private const val TAG = "DDDPlayer/Backend"
        private val PREFERRED_AV1_SURFACE_DECODERS = arrayOf(
            "c2.android.av1-dav1d.decoder",
            "c2.android.av1.decoder"
        )
        private const val NO_SEEK = Long.MIN_VALUE
        private const val IDLE_MS = 10L
        private const val STOP_JOIN_MS = 5_000L
        // A seek on a large remote MKV can legitimately take several seconds
        // (the 1.55 GB HEVC 4:4:4 sample needs about 2.5 s on Pixel). Two
        // seconds turned a successful late seek into a fatal track-switch
        // error. Keep a bounded timeout, but leave enough room for network IO.
        private const val SEEK_TIMEOUT_NS = 15_000_000_000L
        private const val POSITION_CALLBACK_NS = 250_000_000L
    }
}
