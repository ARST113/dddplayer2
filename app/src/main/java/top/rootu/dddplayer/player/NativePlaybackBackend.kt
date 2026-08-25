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
import top.rootu.dddplayer.engine.NativeSubtitleDecoder
import top.rootu.dddplayer.engine.NativeVideoDecoder
import java.util.ArrayDeque
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
    private val playbackGeneration = AtomicInteger()
    private val runStateLock = Any()

    @Volatile private var listener: PlaybackBackend.Listener? = null
    @Volatile private var holder: SurfaceHolder? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var demuxHandle = 0L
    @Volatile private var activeIo: DataSourceEngineIo? = null
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
    @Volatile private var requestedSubtitleTrackId: Int = SUBTITLE_OFF_ID
    @Volatile private var availableSubtitleTracks: List<BackendSubtitleTrack> = listOf(SUBTITLE_OFF)
    @Volatile private var selectedSubtitleTrackId: Int = SUBTITLE_OFF_ID
    @Volatile private var forceSoftwareVideo = false

    override fun attachSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        holder = surfaceHolder
        surfaceGeneration.incrementAndGet()
    }

    override fun prepare(uri: Uri, headers: Map<String, String>, startPositionMs: Long) {
        availableAudioTracks = emptyList()
        selectedAudioTrackId = null
        availableSubtitleTracks = listOf(SUBTITLE_OFF)
        selectedSubtitleTrackId = SUBTITLE_OFF_ID
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
        val runId = playbackGeneration.incrementAndGet()
        postForRun(runId) { listener?.onBuffering() }
        val useSoftwareVideo = forceSoftwareVideo
        Thread(
            { runPlayback(uri, headers, startPositionMs.coerceAtLeast(0L), useSoftwareVideo, runId) },
            "DddNativeVideo-$runId"
        ).also {
            synchronized(runStateLock) {
                if (isRunActive(runId)) worker = it
            }
            it.start()
        }
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
        // Invalidate the current worker before resetting any shared fields for
        // the next item. A remote DataSource.read() can outlive join(timeout);
        // without a generation token that stale worker would become active
        // again as soon as prepareInternal sets stopRequested=false.
        playbackGeneration.incrementAndGet()
        val (io, handle, thread) = synchronized(runStateLock) {
            val state = Triple(activeIo, demuxHandle, worker)
            activeIo = null
            demuxHandle = 0L
            state
        }
        // Closing the Java transport is what actually unblocks a pending HTTP
        // read. NativeDemuxer.stop alone cannot interrupt a DataSource call that
        // is currently executing through JNI.
        try {
            io?.close()
        } catch (t: Throwable) {
            // Media3/OkHttp DataSource is not specified as thread-safe. Closing
            // it is still required to unblock a remote read, but Okio can throw
            // IllegalStateException if close races exactly with Source.read().
            // This transport belongs to the invalidated run and is discarded;
            // never let its cleanup exception crash PlayerActivity.
            Log.w(TAG, "Ignoring transport close race while stopping stale run", t)
        }
        if (handle != 0L) NativeDemuxer.stop(handle)
        if (thread != null && thread !== Thread.currentThread()) {
            thread.interrupt()
            thread.join(STOP_JOIN_MS)
        }
        synchronized(runStateLock) {
            if (worker === thread) worker = null
        }
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

    /** Applies Lampa's stream id before the first decoder is opened. */
    fun setInitialAudioTrackId(id: Int?) {
        if (id != null && sourceUri == null) requestedAudioTrackId = id
    }

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

    fun getSubtitleTracks(): List<BackendSubtitleTrack> = availableSubtitleTracks.map {
        it.copy(selected = it.id == selectedSubtitleTrackId)
    }

    fun getSelectedSubtitleTrackId(): Int = selectedSubtitleTrackId

    fun selectSubtitleTrack(id: Int): Boolean {
        val track = availableSubtitleTracks.firstOrNull { it.id == id } ?: return false
        if (track.isBitmap) {
            Log.w(TAG, "bitmap subtitle track=$id codec=${track.codec} is not a text cue")
            return false
        }
        if (selectedSubtitleTrackId == id) return true
        val uri = sourceUri ?: return false
        val resumeAt = positionMs
        val shouldPlay = playRequested
        requestedSubtitleTrackId = id
        prepareInternal(uri, sourceHeaders, resumeAt, shouldPlay)
        return true
    }

    private fun runPlayback(
        uri: Uri,
        headers: Map<String, String>,
        startPositionMs: Long,
        useSoftwareVideo: Boolean,
        runId: Int
    ) {
        val runThread = Thread.currentThread()
        var handle = 0L
        var source: DataSourceEngineIo? = null
        var video: NativeVideoDecoder? = null
        var audio: NativeAudioDecoder? = null
        var subtitle: NativeSubtitleDecoder? = null
        var sink: EngineAudioSink? = null
        var pump: EngineAudioPump? = null
        var renderer: NativeRenderer? = null
        try {
            source = DataSourceEngineIo.open(
                dataSourceFactory.createDataSource(),
                DataSpec.Builder().setUri(uri).setHttpRequestHeaders(headers).build()
            )
            synchronized(runStateLock) {
                if (isRunActive(runId)) activeIo = source
            }
            if (!isRunActive(runId)) return
            handle = NativeDemuxer.open(null, source)
            synchronized(runStateLock) {
                if (isRunActive(runId)) demuxHandle = handle
            }
            if (!isRunActive(runId)) return
            val probe = NativeDemuxer.probe(handle)
            if (!isRunActive(runId)) return
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
            val subtitleIndex = requestedSubtitleTrackId
                .takeIf { requested -> probe.tracks.subtitle.any { it.id == requested } }
                ?: SUBTITLE_OFF_ID
            availableSubtitleTracks = listOf(SUBTITLE_OFF) + probe.tracks.subtitle
            selectedSubtitleTrackId = subtitleIndex
            Log.i(
                TAG,
                "subtitle tracks=${probe.tracks.subtitle.joinToString { "${it.id}:${it.codec}:${it.language}:${it.label}:bitmap=${it.isBitmap}" }}"
            )
            if (!NativeDemuxer.selectStreams(
                    handle, probe.bestVideoIndex, audioIndex, subtitleIndex
                ) || !NativeDemuxer.start(handle)) {
                throw EngineError(EngineError.Code.DEMUX, "не запустился demux")
            }
            if (startPositionMs > 0) seekDemux(handle, startPositionMs, runId)
            if (!isRunActive(runId)) return

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
            if (subtitleIndex >= 0) {
                subtitle = NativeSubtitleDecoder.create(handle, subtitleIndex)
                Log.i(TAG, "unified subtitle decoder=${subtitle.decoderName} stream=$subtitleIndex")
            }

            prepared = true
            postForRun(runId) {
                listener?.onVideoSizeChanged(
                    probe.videoFormat.width,
                    probe.videoFormat.height,
                    probe.videoFormat.pixelAspectRatio
                )
                if (playRequested) listener?.onPlaying() else listener?.onPaused()
            }
            postForRun(runId) { listener?.onSubtitleTextChanged(null) }
            runLoop(handle, probe, video, audio, subtitle, sink, pump, renderer, runId)
        } catch (_: InterruptedException) {
            // Обычный stop/release.
        } catch (t: Throwable) {
            val retryInSoftware = isRunActive(runId) && !useSoftwareVideo &&
                (t as? EngineError)?.code == EngineError.Code.VIDEO_DECODER
            if (retryInSoftware) {
                val retryAtMs = positionMs
                Log.w(TAG, "MediaCodec failed; restarting native pipeline with libavcodec at ${retryAtMs}ms", t)
                forceSoftwareVideo = true
                postForRun(runId) {
                    if (worker === runThread && sourceUri == uri) {
                        prepareInternal(uri, headers, retryAtMs, playRequested)
                    }
                }
            } else if (isRunActive(runId)) {
                postForRun(runId) { listener?.onError(t) }
            }
        } finally {
            pump?.stop()
            renderer?.release()
            sink?.release()
            if (audioSink === sink) audioSink = null
            audio?.release()
            subtitle?.release()
            video?.release()
            if (handle != 0L) {
                NativeDemuxer.stop(handle)
                NativeDemuxer.close(handle)
            }
            source?.close()
            synchronized(runStateLock) {
                if (demuxHandle == handle) demuxHandle = 0L
                if (activeIo === source) activeIo = null
                if (worker === runThread) worker = null
            }
            if (playbackGeneration.get() == runId) prepared = false
        }
    }

    private fun runLoop(
        handle: Long,
        probe: EngineProbe,
        video: NativeVideoDecoder?,
        audio: NativeAudioDecoder?,
        subtitle: NativeSubtitleDecoder?,
        sink: EngineAudioSink?,
        initialPump: EngineAudioPump?,
        initialRenderer: NativeRenderer?,
        runId: Int
    ) {
        var pump = initialPump
        var renderer = initialRenderer
        var seenSurfaceGeneration = -1
        var wasPlaying = true
        var fallbackAnchorPtsUs: Long? = null
        var fallbackAnchorNs = 0L
        var lastPositionCallbackNs = 0L
        var renderedSurfaceGeneration = -1
        val pendingSubtitles = ArrayDeque<NativeSubtitleDecoder.Cue>()
        val activeSubtitles = mutableListOf<NativeSubtitleDecoder.Cue>()
        var renderedSubtitleText: String? = null
        val sync = AvSyncController()

        try {
        while (isRunActive(runId)) {
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
                seekDemux(handle, seek, runId)
                if (!isRunActive(runId)) break
                video?.flush()
                audio?.flush()
                subtitle?.flush()
                pendingSubtitles.clear()
                activeSubtitles.clear()
                if (renderedSubtitleText != null) {
                    renderedSubtitleText = null
                    postForRun(runId) { listener?.onSubtitleTextChanged(null) }
                }
                positionMs = seek
                fallbackAnchorPtsUs = null
                pump = if (audio != null && sink != null) {
                    EngineAudioPump(audio, sink).also {
                        it.start()
                        if (!playRequested) it.pause()
                    }
                } else null
                postForRun(runId) { listener?.onBuffering() }
            }

            if (!playRequested) {
                if (wasPlaying) {
                    pump?.pause()
                    postForRun(runId) { listener?.onPaused() }
                    wasPlaying = false
                }
                Thread.sleep(IDLE_MS)
                continue
            } else if (!wasPlaying) {
                pump?.resume()
                fallbackAnchorPtsUs = positionMs * 1000
                fallbackAnchorNs = System.nanoTime()
                postForRun(runId) { listener?.onPlaying() }
                wasPlaying = true
            }

            pump?.error?.let { throw it }
            updateStats(handle)
            renderedSubtitleText = updateSubtitles(
                subtitle,
                pendingSubtitles,
                activeSubtitles,
                sink?.positionUs ?: positionMs * 1000,
                renderedSubtitleText,
                runId
            )

            if (video == null) {
                val audioPosition = sink?.positionUs
                if (audioPosition != null) positionMs = max(0L, audioPosition / 1000)
                if (pump?.isEos == true && (sink?.queuedDurationUs ?: 0L) < 10_000) break
                postPositionIfDue(lastPositionCallbackNs, runId).also { lastPositionCallbackNs = it }
                Thread.sleep(IDLE_MS)
                continue
            }

            // При наличии audio не выпускаем видео до появления реального clock.
            if (audio != null && sink?.positionUs == null) {
                Thread.sleep(2)
                continue
            }

            val step = video.nextFrame(20)
            if (!isRunActive(runId)) break
            when (step) {
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
                        while (!decided && playRequested && pendingSeekMs == NO_SEEK && isRunActive(runId)) {
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
            postPositionIfDue(lastPositionCallbackNs, runId).also { lastPositionCallbackNs = it }
        }

        if (isRunActive(runId)) postForRun(runId) {
            listener?.onSubtitleTextChanged(null)
            listener?.onEnded()
        }
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

    private fun updateSubtitles(
        decoder: NativeSubtitleDecoder?,
        pending: ArrayDeque<NativeSubtitleDecoder.Cue>,
        active: MutableList<NativeSubtitleDecoder.Cue>,
        nowUs: Long,
        previousText: String?,
        runId: Int
    ): String? {
        if (decoder == null) return previousText

        while (pending.size < MAX_PENDING_SUBTITLES) {
            val cue = decoder.nextCue(0)
            when (cue.step) {
                NativeSubtitleDecoder.Step.CUE -> {
                    if (!cue.text.isNullOrBlank() && cue.endUs > cue.startUs) pending.addLast(cue)
                }
                NativeSubtitleDecoder.Step.AGAIN,
                NativeSubtitleDecoder.Step.EOS -> break
                NativeSubtitleDecoder.Step.ERROR -> throw EngineError(
                    EngineError.Code.UNSUPPORTED,
                    "ошибка ${decoder.decoderName}"
                )
            }
        }

        while (pending.isNotEmpty() && pending.first.startUs <= nowUs) {
            val cue = pending.removeFirst()
            if (cue.endUs > nowUs) active += cue
        }
        active.removeAll { it.endUs <= nowUs }
        val nextText = active.mapNotNull { it.text?.takeIf(String::isNotBlank) }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
        if (nextText != previousText) {
            postForRun(runId) { listener?.onSubtitleTextChanged(nextText) }
        }
        return nextText
    }

    private fun seekDemux(handle: Long, positionMs: Long, runId: Int) {
        val before = NativeDemuxer.stats(handle)?.seeks ?: 0
        if (!NativeDemuxer.seek(handle, positionMs)) {
            throw EngineError(EngineError.Code.DEMUX, "seek не принят")
        }
        val deadline = System.nanoTime() + SEEK_TIMEOUT_NS
        while (isRunActive(runId) && System.nanoTime() < deadline) {
            if ((NativeDemuxer.stats(handle)?.seeks ?: before) > before) return
            Thread.sleep(5)
        }
        if (isRunActive(runId)) throw EngineError(EngineError.Code.TIMEOUT, "demux seek timeout")
    }

    private fun updateStats(handle: Long) {
        NativeDemuxer.stats(handle)?.let { bufferedPositionMs = it.bufferedPositionMs }
    }

    private fun postPositionIfDue(previousNs: Long, runId: Int): Long {
        val now = System.nanoTime()
        if (isRunActive(runId) && now - previousNs >= POSITION_CALLBACK_NS) {
            val p = positionMs
            val d = durationMs
            postForRun(runId) { listener?.onPositionChanged(p, d) }
            return now
        }
        return previousNs
    }

    private fun isRunActive(runId: Int): Boolean =
        !stopRequested && playbackGeneration.get() == runId

    private fun postForRun(runId: Int, block: () -> Unit) {
        post {
            if (isRunActive(runId)) block()
        }
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
        private const val MAX_PENDING_SUBTITLES = 64
        private const val SUBTITLE_OFF_ID = -1
        private val SUBTITLE_OFF = BackendSubtitleTrack(
            id = SUBTITLE_OFF_ID,
            label = "Отключено",
            selected = true
        )
    }
}
