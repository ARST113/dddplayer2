package top.rootu.dddplayer.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FFmpeg-аудиодекодер поверх очереди выбранного потока [NativeDemuxer].
 *
 * Выход — interleaved float PCM с фиксированными sample rate/channel count.
 * Буфер обязан быть direct: native пишет в него без промежуточного `ByteArray`.
 */
class NativeAudioDecoder private constructor(private var handle: Long) {

    enum class Step { PCM, AGAIN, EOS, ERROR }

    data class Chunk(val step: Step, val ptsUs: Long, val frames: Int)

    val isReleased: Boolean get() = handle == 0L
    val sampleRate: Int get() = if (handle != 0L) nativeSampleRate(handle) else 0
    val channels: Int get() = if (handle != 0L) nativeChannels(handle) else 0
    val inputSampleRate: Int get() = if (handle != 0L) nativeInputSampleRate(handle) else 0
    val inputChannels: Int get() = if (handle != 0L) nativeInputChannels(handle) else 0
    val decoderName: String get() = if (handle != 0L) nativeDecoderName(handle).orEmpty() else ""
    val packetsIn: Long get() = if (handle != 0L) nativePacketsIn(handle) else 0
    val sampleFramesOut: Long get() = if (handle != 0L) nativeFramesOut(handle) else 0

    /**
     * Декодирует следующий блок. После PCM `buffer.position()==0`, а limit равен
     * числу записанных байт. На AGAIN/EOS буфер пуст.
     */
    fun nextPcm(buffer: ByteBuffer, maxFrames: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Chunk {
        require(buffer.isDirect) { "PCM buffer должен быть direct" }
        require(buffer.order() == ByteOrder.nativeOrder()) { "PCM buffer должен иметь nativeOrder" }
        require(maxFrames > 0) { "maxFrames должен быть > 0" }
        val needed = maxFrames.toLong() * channels * Float.SIZE_BYTES
        require(needed <= buffer.capacity()) {
            "PCM buffer: нужно $needed байт, есть ${buffer.capacity()}"
        }
        buffer.clear()
        if (handle == 0L) return Chunk(Step.ERROR, 0, 0)
        val result = LongArray(2)
        val step = Step.entries.getOrElse(
            nativeNextPcm(handle, buffer, maxFrames, timeoutMs, result)
        ) { Step.ERROR }
        val frames = if (step == Step.PCM) result[1].toInt() else 0
        buffer.position(0)
        buffer.limit(frames * channels * Float.SIZE_BYTES)
        return Chunk(step, result[0], frames)
    }

    fun flush(): Boolean = handle != 0L && nativeFlush(handle)

    fun release() {
        val old = handle
        handle = 0L
        if (old != 0L) nativeRelease(old)
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val DEFAULT_CHANNELS = 2
        const val DEFAULT_TIMEOUT_MS = 20

        fun create(
            demuxHandle: Long,
            streamIndex: Int = -1,
            sampleRate: Int = DEFAULT_SAMPLE_RATE,
            channels: Int = DEFAULT_CHANNELS
        ): NativeAudioDecoder {
            if (!NativeDemuxer.isAvailable) {
                throw EngineError(EngineError.Code.AUDIO_DECODER, "нативный движок не загружен")
            }
            val error = arrayOfNulls<String>(1)
            val handle = nativeCreate(demuxHandle, streamIndex, sampleRate, channels, error)
            if (handle == 0L) {
                throw EngineError(
                    EngineError.Code.AUDIO_DECODER,
                    "не создался аудиодекодер: ${error[0] ?: "причина не указана"}"
                )
            }
            return NativeAudioDecoder(handle)
        }

        fun allocateBuffer(maxFrames: Int, channels: Int = DEFAULT_CHANNELS): ByteBuffer =
            ByteBuffer.allocateDirect(maxFrames * channels * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())

        @JvmStatic private external fun nativeCreate(
            demuxHandle: Long,
            streamIndex: Int,
            sampleRate: Int,
            channels: Int,
            errorOut: Array<String?>
        ): Long
        @JvmStatic private external fun nativeRelease(handle: Long)
        @JvmStatic private external fun nativeNextPcm(
            handle: Long,
            buffer: ByteBuffer,
            maxFrames: Int,
            timeoutMs: Int,
            result: LongArray
        ): Int
        @JvmStatic private external fun nativeFlush(handle: Long): Boolean
        @JvmStatic private external fun nativeSampleRate(handle: Long): Int
        @JvmStatic private external fun nativeChannels(handle: Long): Int
        @JvmStatic private external fun nativeInputSampleRate(handle: Long): Int
        @JvmStatic private external fun nativeInputChannels(handle: Long): Int
        @JvmStatic private external fun nativeDecoderName(handle: Long): String?
        @JvmStatic private external fun nativePacketsIn(handle: Long): Long
        @JvmStatic private external fun nativeFramesOut(handle: Long): Long
    }
}
