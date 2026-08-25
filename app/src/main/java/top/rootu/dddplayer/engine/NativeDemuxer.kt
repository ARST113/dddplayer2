package top.rootu.dddplayer.engine

import android.util.Log

/**
 * Тонкая обёртка над `libddd_engine.so`: демукс, пробинг, состояние буфера.
 *
 * Пакетов данных здесь нет намеренно. Декодирование остаётся в native (шаги 5 и
 * 7), поэтому гонять пакеты в JVM и обратно было бы копированием ради
 * копирования — Kotlin получает модель дорожек и цифры буфера, то есть ровно то,
 * что нужно UI.
 *
 * Сессия представлена `Long`-хэндлом, а не объектом с финализатором: финализаторы
 * в Android не гарантируют вызов, а незакрытая сессия — это живой поток демукса и
 * открытое сетевое соединение. Закрывать обязан владелец, через [close].
 *
 * Нативные методы регистрируются в `JNI_OnLoad` через `RegisterNatives`: если
 * подпись здесь и в `jni_engine.cpp` разойдутся, `System.loadLibrary` упадёт
 * сразу, а не выдаст `UnsatisfiedLinkError` через полчаса воспроизведения.
 */
object NativeDemuxer {

    private const val TAG = "DddEngine"

    /**
     * Библиотеки FFmpeg загружаются явно и в порядке зависимостей.
     *
     * Динамический линкер Android разрешил бы их и сам по `DT_NEEDED`, но тогда
     * при отсутствующей библиотеке в APK сообщение выглядит как «cannot load
     * library ddd_engine» без имени настоящей причины. Здесь же в логе видно, на
     * какой именно библиотеке всё встало.
     *
     * Суффикс `_ddd` — защита от чужой `libavcodec.so` в том же процессе (её
     * несут VLC и часть SDK): линкер разрешает имена глобально, и одинаковое имя
     * означало бы чужой FFmpeg с другим набором декодеров.
     */
    private val libraries = listOf(
        "avutil_ddd", "swresample_ddd", "swscale_ddd",
        "avcodec_ddd", "avformat_ddd", "avfilter_ddd",
        "ddd_engine"
    )

    /** false — движок недоступен (нет .so под этот ABI); вызывать методы нельзя. */
    val isAvailable: Boolean = run {
        try {
            libraries.forEach { System.loadLibrary(it) }
            true
        } catch (e: Throwable) {
            // Throwable, а не Exception: UnsatisfiedLinkError — это Error.
            Log.e(TAG, "Не загрузился нативный движок", e)
            false
        }
    }

    // ───────────────────────────── публичный API ─────────────────────────────

    /** Версия движка и FFmpeg — в информационную панель и в баг-репорты. */
    fun version(): String = if (isAvailable) nativeVersion() else "недоступен"

    /**
     * Самопроверка сборки `hdr-static-info` на каноническом HDR10.
     *
     * Дёшево (сравнение 25 байт) и отвечает на вопрос, который иначе выясняется
     * только по вымытой картинке: правильно ли собран HDR-блок в этой сборке.
     */
    fun selfTest(): Boolean = isAvailable && nativeSelfTest()

    fun setVerboseLogs(verbose: Boolean) {
        if (isAvailable) nativeSetVerboseLogs(verbose)
    }

    /**
     * Открывает источник и выполняет пробинг.
     *
     * @param io      источник байт; если задан, [url] игнорируется и вся сеть
     *                остаётся в Java. Движок забирает владение и вызовет
     *                `EngineIo.close()` сам.
     * @param url     используется только при `io == null` — для локальных файлов
     *                и `content://`, которые FFmpeg читает своими протоколами.
     * @param options опции демуксера (`user_agent`, `headers`, `timeout`).
     * @return хэндл сессии; закрывать через [close].
     * @throws EngineError с кодом [EngineError.Code.SOURCE].
     */
    fun open(url: String?, io: EngineIo?, options: Map<String, String> = emptyMap()): Long {
        if (!isAvailable) throw EngineError(EngineError.Code.SOURCE, "нативный движок не загружен")

        val flat = ArrayList<String>(options.size * 2)
        options.forEach { (k, v) -> flat.add(k); flat.add(v) }

        val error = IntArray(1)
        val handle = nativeOpen(url, io, flat.toTypedArray(), error)
        if (handle == 0L) {
            val code = error[0]
            throw EngineError(
                EngineError.Code.SOURCE,
                "не открылся ${io?.name() ?: url}: ${errorString(code)} ($code)"
            )
        }
        return handle
    }

    /**
     * Отдаёт результат пробинга в [sink].
     *
     * Пробинг выполняется при [open]; здесь только перекладывание уже готовой
     * модели, поэтому вызов дешёвый и его можно делать повторно.
     */
    fun probe(handle: Long, sink: ProbeSink): Boolean =
        isAvailable && handle != 0L && nativeProbe(handle, sink)

    /** Разбирает контейнер в модель движка. Удобная обёртка над [probe]. */
    fun probe(handle: Long): EngineProbe {
        val collector = ProbeCollector()
        if (!probe(handle, collector)) {
            throw EngineError(EngineError.Code.DEMUX, "пробинг не удался")
        }
        return collector.build()
    }

    /** Длительность, мс; 0 для live. */
    fun durationMs(handle: Long): Long = if (isAvailable && handle != 0L) nativeDurationMs(handle) else 0

    /**
     * Выбирает потоки для чтения. Вызывать до [start]: после запуска потока
     * демукса `AVFormatContext` менять нельзя.
     *
     * @param video/audio/subtitle индексы потоков; -1 — не читать этот тип.
     */
    fun selectStreams(handle: Long, video: Int, audio: Int, subtitle: Int): Boolean =
        isAvailable && handle != 0L && nativeSelectStreams(handle, video, audio, subtitle)

    /** Запускает поток демукса — с этого момента источник читается. */
    fun start(handle: Long): Boolean = isAvailable && handle != 0L && nativeStart(handle)

    /** Останавливает поток демукса и прерывает висящее сетевое чтение. */
    fun stop(handle: Long) {
        if (isAvailable && handle != 0L) nativeStop(handle)
    }

    /**
     * Просит перейти на позицию. Возврат означает «запрос принят»: сам seek
     * делается в потоке демукса, чтобы `AVFormatContext` жил в одном потоке.
     */
    fun seek(handle: Long, positionMs: Long): Boolean =
        isAvailable && handle != 0L && nativeSeek(handle, positionMs)

    /**
     * Состояние буфера. Массив переиспользуемый: статистику опрашивает UI
     * несколько раз в секунду, и выделять под неё объект каждый раз незачем.
     *
     * @param out массив минимум из [STAT_SLOT_COUNT] элементов.
     */
    fun stats(handle: Long, out: LongArray): Boolean =
        isAvailable && handle != 0L && nativeStats(handle, out)

    /** Аллоцирующий вариант [stats] — для мест, где важнее читаемость. */
    fun stats(handle: Long): EngineBufferStats? {
        val out = LongArray(STAT_SLOT_COUNT)
        if (!stats(handle, out)) return null
        return EngineBufferStats(
            bufferedPositionMs = out[STAT_BUFFERED_POSITION_MS],
            queueStartMs = out[STAT_QUEUE_START_MS],
            bufferedDurationMs = out[STAT_BUFFERED_DURATION_MS],
            queuedBytes = out[STAT_QUEUED_BYTES],
            queuedPackets = out[STAT_QUEUED_PACKETS].toInt(),
            eof = out[STAT_EOF] != 0L,
            readErrors = out[STAT_READ_ERRORS].toInt(),
            seeks = out[STAT_SEEKS].toInt(),
            packetsRead = out[STAT_PACKETS_READ]
        )
    }

    /**
     * Закрывает сессию: останавливает поток демукса, закрывает демуксер,
     * освобождает `AVIOContext` и вызывает `EngineIo.close()` — в этом порядке.
     */
    fun close(handle: Long) {
        if (isAvailable && handle != 0L) nativeClose(handle)
    }

    /** Текст ошибки FFmpeg по коду `AVERROR`. */
    fun errorString(code: Int): String = if (isAvailable) nativeErrorString(code) else "код $code"

    // ───────────────────────── раскладка массива статистики ─────────────────────────
    // Дублируется enum'ом StatsSlot в jni_engine.cpp.

    const val STAT_BUFFERED_POSITION_MS = 0
    const val STAT_QUEUE_START_MS = 1
    const val STAT_BUFFERED_DURATION_MS = 2
    const val STAT_QUEUED_BYTES = 3
    const val STAT_QUEUED_PACKETS = 4
    const val STAT_EOF = 5
    const val STAT_READ_ERRORS = 6
    const val STAT_SEEKS = 7
    const val STAT_PACKETS_READ = 8
    const val STAT_SLOT_COUNT = 9

    // ───────────────────────────── native ─────────────────────────────

    private external fun nativeVersion(): String
    private external fun nativeSelfTest(): Boolean
    private external fun nativeSetVerboseLogs(verbose: Boolean)
    private external fun nativeErrorString(code: Int): String
    private external fun nativeOpen(
        url: String?,
        io: EngineIo?,
        options: Array<String>?,
        errorOut: IntArray
    ): Long

    private external fun nativeProbe(handle: Long, sink: ProbeSink): Boolean
    private external fun nativeDurationMs(handle: Long): Long
    private external fun nativeSelectStreams(handle: Long, video: Int, audio: Int, subtitle: Int): Boolean
    private external fun nativeStart(handle: Long): Boolean
    private external fun nativeStop(handle: Long)
    private external fun nativeSeek(handle: Long, positionMs: Long): Boolean
    private external fun nativeStats(handle: Long, out: LongArray): Boolean
    private external fun nativeClose(handle: Long)
}

/**
 * Состояние буферизации.
 *
 * Замена связки `player.bufferedPosition` + `totalBufferedDuration` из Media3.
 * Отдельно от позиции воспроизведения: воспроизведением занимается шаг 8, а
 * буфером — очереди пакетов.
 */
data class EngineBufferStats(
    /** До какой позиции прочитан источник, мс. Это `bufferedPosition` для UI. */
    val bufferedPositionMs: Long,

    /**
     * Позиция первого пакета в очередях, мс; -1 если очереди пусты.
     *
     * После seek именно она показывает, куда демуксер реально встал (обычно на
     * предыдущий ключевой кадр): [bufferedPositionMs] к этому моменту уже уехал
     * вперёд на всю глубину буфера.
     */
    val queueStartMs: Long,

    /** Сколько секунд контента лежит в очередях (минимум по видео/аудио). */
    val bufferedDurationMs: Long,

    val queuedBytes: Long,
    val queuedPackets: Int,
    val eof: Boolean,

    /** Ошибки чтения, которые удалось переждать: индикатор проблем с сетью. */
    val readErrors: Int,
    val seeks: Int,
    val packetsRead: Long
)
