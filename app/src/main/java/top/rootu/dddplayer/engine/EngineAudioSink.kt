package top.rootu.dddplayer.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.PlaybackParams
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Потоковый float PCM sink и аудио-часы нового движка.
 *
 * Позиция считается не по wall clock, а по playback head AudioTrack: пауза,
 * underrun и задержка вывода автоматически перестают двигать время. Именно эти
 * часы должен догонять/ждать видеорендерер.
 */
class EngineAudioSink(
    val sampleRate: Int,
    val channels: Int,
    bufferDurationMs: Int = DEFAULT_BUFFER_MS
) {
    private val bytesPerFrame = channels * Float.SIZE_BYTES
    private val track: AudioTrack

    private var submittedFrames = 0L
    private var basePtsUs: Long? = null
    private var lastRawHead = 0L
    private var headWrapBase = 0L
    private var released = false

    var speed: Float = 1f
        set(value) {
            require(value in MIN_SPEED..MAX_SPEED) { "speed вне диапазона: $value" }
            field = value
            if (!released) {
                track.playbackParams = PlaybackParams()
                    .allowDefaults()
                    .setSpeed(value)
                    .setPitch(1f)
                    .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_DEFAULT)
            }
        }

    val isReleased: Boolean get() = released
    val isPlaying: Boolean get() = !released && track.playState == AudioTrack.PLAYSTATE_PLAYING

    /** Media PTS реально сыгранного семпла; null, пока первый PCM не поставлен. */
    val positionUs: Long?
        @Synchronized get() = basePtsUs?.let { base ->
            base + framesToUs(playedFramesLocked())
        }

    val submittedFramesCount: Long
        @Synchronized get() = submittedFrames

    val playedFramesCount: Long
        @Synchronized get() = playedFramesLocked()

    val queuedDurationUs: Long
        @Synchronized get() = framesToUs(max(0L, submittedFrames - playedFramesLocked()))

    init {
        require(sampleRate in 8_000..192_000) { "sampleRate: $sampleRate" }
        require(channels == 1 || channels == 2) { "AudioTrack sink поддерживает mono/stereo" }
        val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO
        else AudioFormat.CHANNEL_OUT_STEREO
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate, channelMask, AudioFormat.ENCODING_PCM_FLOAT
        )
        check(minBytes > 0) { "AudioTrack.getMinBufferSize: $minBytes" }
        val requestedBytes = sampleRate * bufferDurationMs / 1000 * bytesPerFrame
        val bufferBytes = max(minBytes, requestedBytes)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()
        check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack не инициализирован" }
    }

    fun play() {
        check(!released) { "AudioTrack уже release" }
        track.play()
    }

    fun pause() {
        if (!released && track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
    }

    fun setVolume(volume: Float) {
        if (!released) track.setVolume(volume.coerceIn(0f, 1f))
    }

    /**
     * Пытается записать весь оставшийся PCM; ByteBuffer продвигается на число
     * принятых байтов. При pause/resume или смене маршрута AudioTrack вправе
     * вернуть частичную запись — вызывающий должен позже дописать остаток.
     * Возвращает число sample frames, фактически поставленных в очередь.
     */
    fun write(buffer: ByteBuffer, ptsUs: Long): Int {
        check(!released) { "AudioTrack уже release" }
        require(buffer.isDirect) { "PCM buffer должен быть direct" }
        require(buffer.remaining() % bytesPerFrame == 0) { "нецелое число PCM frames" }
        val requestedFrames = buffer.remaining() / bytesPerFrame
        if (requestedFrames == 0) return 0

        synchronized(this) {
            if (basePtsUs == null) basePtsUs = ptsUs - framesToUs(submittedFrames)
        }
        var writtenBytes = 0
        while (buffer.hasRemaining()) {
            val wrote = track.write(buffer, buffer.remaining(), AudioTrack.WRITE_BLOCKING)
            if (wrote < 0) throw IllegalStateException("AudioTrack.write: $wrote")
            if (wrote == 0) break
            writtenBytes += wrote
        }
        val frames = writtenBytes / bytesPerFrame
        synchronized(this) { submittedFrames += frames }
        return frames
    }

    /** Сбрасывает очередь и временную привязку после seek/смены дорожки. */
    @Synchronized
    fun flush() {
        if (released) return
        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
        track.flush()
        submittedFrames = 0
        basePtsUs = null
        lastRawHead = 0
        headWrapBase = 0
    }

    @Synchronized
    fun release() {
        if (released) return
        released = true
        try {
            track.pause()
        } catch (_: IllegalStateException) {
            // Уже остановленный/маршрутизируемый AudioTrack может отвергнуть pause.
        }
        track.flush()
        track.release()
    }

    /** 32-bit playback head разворачивается в монотонный Long. */
    private fun playedFramesLocked(): Long {
        val raw = track.playbackHeadPosition.toLong() and 0xffff_ffffL
        if (raw < lastRawHead && lastRawHead - raw > 0x8000_0000L) {
            headWrapBase += 0x1_0000_0000L
        }
        lastRawHead = raw
        return minOf(submittedFrames, headWrapBase + raw)
    }

    private fun framesToUs(frames: Long): Long = frames * 1_000_000L / sampleRate

    companion object {
        const val DEFAULT_BUFFER_MS = 250
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4f
    }
}
