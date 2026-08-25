package top.rootu.dddplayer.engine

/** Text subtitle decoder backed by the selected FFmpeg demux queue. */
class NativeSubtitleDecoder private constructor(private var handle: Long) {

    enum class Step { CUE, AGAIN, EOS, ERROR }

    data class Cue(val step: Step, val startUs: Long, val endUs: Long, val text: String?)

    val decoderName: String
        get() = if (handle != 0L) nativeDecoderName(handle).orEmpty() else ""

    fun nextCue(timeoutMs: Int = 0): Cue {
        if (handle == 0L) return Cue(Step.ERROR, 0, 0, null)
        val result = LongArray(3)
        val text = nativeNextCue(handle, timeoutMs, result)
        val step = Step.entries.getOrElse(result[0].toInt()) { Step.ERROR }
        return Cue(step, result[1], result[2], text)
    }

    fun flush(): Boolean = handle != 0L && nativeFlush(handle)

    fun release() {
        val old = handle
        handle = 0L
        if (old != 0L) nativeRelease(old)
    }

    companion object {
        fun create(demuxHandle: Long, streamIndex: Int): NativeSubtitleDecoder {
            if (!NativeDemuxer.isAvailable) {
                throw EngineError(EngineError.Code.UNSUPPORTED, "нативный движок не загружен")
            }
            val error = arrayOfNulls<String>(1)
            val handle = nativeCreate(demuxHandle, streamIndex, error)
            if (handle == 0L) {
                throw EngineError(
                    EngineError.Code.UNSUPPORTED,
                    "не создался декодер субтитров: ${error[0] ?: "причина не указана"}"
                )
            }
            return NativeSubtitleDecoder(handle)
        }

        @JvmStatic private external fun nativeCreate(
            demuxHandle: Long,
            streamIndex: Int,
            errorOut: Array<String?>
        ): Long
        @JvmStatic private external fun nativeRelease(handle: Long)
        @JvmStatic private external fun nativeNextCue(
            handle: Long,
            timeoutMs: Int,
            result: LongArray
        ): String?
        @JvmStatic private external fun nativeFlush(handle: Long): Boolean
        @JvmStatic private external fun nativeDecoderName(handle: Long): String?
    }
}
