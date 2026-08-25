package top.rootu.dddplayer.engine

/**
 * Подкачивает декодированный PCM в [EngineAudioSink] на отдельном потоке.
 *
 * `AudioTrack.write(WRITE_BLOCKING)` нельзя выполнять в видеопотоке: заполнение
 * аппаратного буфера блокирует его на десятки миллисекунд, после чего A/V sync
 * вынужден выбрасывать уже опоздавшие кадры. Этот класс фиксирует саму границу
 * потоков, чтобы ошибка не вернулась в оркестраторе плеера.
 */
class EngineAudioPump(
    private val decoder: NativeAudioDecoder,
    private val sink: EngineAudioSink,
    private val targetQueueUs: Long = DEFAULT_TARGET_QUEUE_US
) {
    // AudioTrack with DEEP_BUFFER may not consume a single frame until its
    // complete client buffer is primed. Never stop feeding below that device-
    // specific threshold (17,316 frames / ~361 ms on Pixel Android 16).
    private val effectiveTargetQueueUs = maxOf(targetQueueUs, sink.bufferCapacityDurationUs)

    @Volatile var isRunning: Boolean = false
        private set
    @Volatile var isEos: Boolean = false
        private set
    @Volatile var error: Throwable? = null
        private set
    @Volatile private var paused = false

    private var worker: Thread? = null

    fun start() {
        check(worker == null) { "audio pump уже запускался" }
        paused = false
        isRunning = true
        sink.play()
        worker = Thread(::runLoop, "DddAudioPump").also { it.start() }
    }

    fun pause() {
        // Сначала запрещаем новые write: pause() может разблокировать уже
        // выполняющийся AudioTrack.write() с корректной частичной записью.
        paused = true
        sink.pause()
    }

    fun resume() {
        sink.play()
        paused = false
    }

    fun stop() {
        isRunning = false
        val thread = worker ?: return
        thread.interrupt()
        thread.join(STOP_JOIN_MS)
        if (thread.isAlive) {
            // WRITE_BLOCKING ограничен одним PCM chunk (~85 мс при 4096/48k),
            // поэтому второй интервал — страховка маршрутизации Bluetooth.
            thread.join(STOP_JOIN_MS)
        }
        worker = null
    }

    private fun runLoop() {
        val pcm = NativeAudioDecoder.allocateBuffer(CHUNK_FRAMES, decoder.channels)
        try {
            while (isRunning && !isEos) {
                if (paused) {
                    Thread.sleep(IDLE_SLEEP_MS)
                    continue
                }
                if (sink.queuedDurationUs >= effectiveTargetQueueUs) {
                    Thread.sleep(IDLE_SLEEP_MS)
                    continue
                }
                val chunk = decoder.nextPcm(pcm, CHUNK_FRAMES, 20)
                when (chunk.step) {
                    NativeAudioDecoder.Step.PCM -> {
                        var writtenFrames = 0
                        while (isRunning && writtenFrames < chunk.frames) {
                            if (paused) {
                                Thread.sleep(IDLE_SLEEP_MS)
                                continue
                            }
                            val written = sink.write(
                                pcm,
                                chunk.ptsUs + framesToUs(writtenFrames)
                            )
                            if (written == 0) {
                                // Нулевой/частичный результат допустим при
                                // pause/resume и смене аудиомаршрута. Остаток
                                // ByteBuffer будет дописан после возобновления.
                                Thread.sleep(IDLE_SLEEP_MS)
                            } else {
                                writtenFrames += written
                            }
                        }
                    }
                    NativeAudioDecoder.Step.AGAIN -> Thread.sleep(IDLE_SLEEP_MS)
                    NativeAudioDecoder.Step.EOS -> isEos = true
                    NativeAudioDecoder.Step.ERROR ->
                        throw IllegalStateException("audio decode error: ${decoder.decoderName}")
                }
            }
        } catch (_: InterruptedException) {
            // Нормальный stop().
        } catch (t: Throwable) {
            error = t
        } finally {
            isRunning = false
        }
    }

    private fun framesToUs(frames: Int): Long =
        frames.toLong() * 1_000_000L / decoder.sampleRate

    companion object {
        const val DEFAULT_TARGET_QUEUE_US = 300_000L
        const val CHUNK_FRAMES = 4096
        private const val IDLE_SLEEP_MS = 2L
        private const val STOP_JOIN_MS = 750L
    }
}
