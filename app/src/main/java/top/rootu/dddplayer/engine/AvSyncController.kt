package top.rootu.dddplayer.engine

import kotlin.math.ceil

/** Решение для видеокадра относительно реально сыгранного audio clock. */
class AvSyncController(
    private val earlyToleranceUs: Long = DEFAULT_EARLY_TOLERANCE_US,
    private val lateDropUs: Long = DEFAULT_LATE_DROP_US,
    private val maxWaitMs: Long = DEFAULT_MAX_WAIT_MS
) {
    sealed interface Decision {
        data object Render : Decision
        data object Drop : Decision
        data class Wait(val milliseconds: Long) : Decision
    }

    /**
     * @param videoPtsUs PTS готового видеокадра.
     * @param audioPositionUs PTS реально сыгранного аудиосемпла; null для файла
     * без звука — тогда кадр не блокируется этим контроллером.
     */
    fun decide(videoPtsUs: Long, audioPositionUs: Long?): Decision {
        if (audioPositionUs == null) return Decision.Render
        val deltaUs = videoPtsUs - audioPositionUs
        if (deltaUs < -lateDropUs) return Decision.Drop
        if (deltaUs <= earlyToleranceUs) return Decision.Render
        val waitMs = ceil((deltaUs - earlyToleranceUs) / 1_000.0).toLong()
            .coerceIn(1L, maxWaitMs)
        return Decision.Wait(waitMs)
    }

    companion object {
        const val DEFAULT_EARLY_TOLERANCE_US = 5_000L
        const val DEFAULT_LATE_DROP_US = 80_000L
        const val DEFAULT_MAX_WAIT_MS = 20L
    }
}
