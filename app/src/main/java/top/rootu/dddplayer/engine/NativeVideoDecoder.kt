package top.rootu.dddplayer.engine

import android.view.Surface

/**
 * Обёртка над стадией декодирования: `AMediaCodec` поверх уже открытого демукса.
 *
 * Шаг 5 движка (UNIFIED-ENGINE.md §6): достать из контейнера 10 бит и довести их
 * до текстуры, не потеряв младшие разряды. Пакеты берутся из очередей
 * [NativeDemuxer], кадры отдаются в [NativeRenderer] — через native, без
 * копирования кадра в JVM.
 *
 * **Поток.** Как и у рендерера, все методы — из одного потока. Буфер кадра
 * принадлежит декодеру: между [nextFrame] и [releaseFrame] отдавать его в чужой
 * поток нельзя, иначе следующий кадр перепишет данные под читающим.
 *
 * **Кадр за раз.** [nextFrame] с неотпущенным предыдущим кадром — ошибка, а не
 * молчаливое освобождение: кадр, на который вызывающий ещё держит ссылку,
 * вернулся бы декодеру и переписался следующим. Это была бы тихая порча данных.
 *
 * Хэндл — `Long` без финализатора: незакрытый декодер держит буферы MediaCodec.
 * Закрывает владелец, через [release].
 */
class NativeVideoDecoder private constructor(private var handle: Long) {

    /** Итог одного шага насоса; порядок дублирует `Step` в decode_session.h. */
    enum class Step {
        /** Кадр есть; после работы с ним обязателен [releaseFrame]. */
        FRAME,

        /** Кадра пока нет: нужно больше данных или больше времени. */
        AGAIN,

        /** Поток закончился и декодер отдал всё, что мог. */
        EOS,

        ERROR
    }

    val isReleased: Boolean get() = handle == 0L

    /**
     * Докармливает декодер и пытается вынуть кадр.
     *
     * @param timeoutMs сколько ждать кадра; 0 — не ждать вообще.
     */
    fun nextFrame(timeoutMs: Int = DEFAULT_TIMEOUT_MS): Step {
        if (handle == 0L) return Step.ERROR
        return Step.entries.getOrElse(nativeNextFrame(handle, timeoutMs)) { Step.ERROR }
    }

    /** Возвращает буфер декодеру. Обязателен для каждого [Step.FRAME]. Идемпотентен. */
    fun releaseFrame() {
        if (handle != 0L) nativeReleaseFrame(handle)
    }

    /**
     * Сброс декодера после seek. Сам seek делает [NativeDemuxer.seek] — очереди
     * пакетов принадлежат демуксу.
     */
    fun flush(): Boolean = handle != 0L && nativeFlush(handle)

    /**
     * Рабочий путь: текущий кадр из буфера декодера — прямо в текстуры рендерера.
     *
     * Кадр не проходит через JVM. Для 4K это ~12 МБ на кадр в каждую сторону:
     * копирование через `byte[]` съело бы бюджет кадра целиком.
     */
    fun uploadToRenderer(renderer: NativeRenderer): Boolean =
        handle != 0L && !renderer.isReleased &&
            nativeUploadToRenderer(handle, renderer.nativeHandle)

    /** Представляет текущий кадр в Surface, настроенный при [create]. */
    fun renderToSurface(): Boolean = handle != 0L && nativeRenderToSurface(handle)

    /** Декодер выводит кадры напрямую в Surface, сохраняя общий demux/audio clock. */
    val isSurfaceOutput: Boolean get() = handle != 0L && nativeSurfaceOutput(handle)

    // ─────────────── сырые байты кадра (проверки, не воспроизведение) ───────────────

    /**
     * Значащих байт в строке плоскости текущего кадра; 0 — плоскости нет.
     *
     * Значащих, а не `stride`: указатель плоскости уже сдвинут на кроп, и полный
     * stride последней строки читал бы за конец буфера.
     */
    fun planeRowBytes(plane: Int): Int = if (handle == 0L) 0 else nativePlaneRowBytes(handle, plane)

    /** Строк в плоскости текущего кадра; 0 — плоскости нет. */
    fun planeRows(plane: Int): Int = if (handle == 0L) 0 else nativePlaneRows(handle, plane)

    /**
     * Копирует плоскость ПЛОТНО: `planeRowBytes * planeRows`, без stride.
     *
     * Нужно проверке 10 бит: [uploadToRenderer] показывает результат через GL, а
     * доказать, что биты настоящие (MSB-выравнивание P010, коды не кратны 4),
     * можно только по байтам декодера. Плотная упаковка убирает stride из
     * уравнения — сравниваются данные, а не раскладка.
     *
     * @param plane 0=Y, 1=UV (P010/NV12) либо U, 2=V.
     * @return скопировано байт, 0 если плоскости нет, -1 при ошибке.
     */
    fun copyPlane(plane: Int, out: ByteArray): Int =
        if (handle == 0L) -1 else nativeCopyPlane(handle, plane, out)

    /** [copyPlane] с массивом нужного размера; null — плоскости нет или ошибка. */
    fun planeBytes(plane: Int): ByteArray? {
        val need = planeRowBytes(plane) * planeRows(plane)
        if (need <= 0) return null
        val out = ByteArray(need)
        return if (copyPlane(plane, out) == need) out else null
    }

    /** PTS текущего кадра, мкс; 0, если кадра нет. */
    val framePtsUs: Long get() = if (handle == 0L) 0 else nativeFramePtsUs(handle)

    // ─────────────── состояние декодера (диагностика и проверки) ───────────────

    /** Сырой `KEY_COLOR_FORMAT`, который вернул декодер; -1 при ошибке. */
    val colorFormat: Int get() = if (handle == 0L) -1 else nativeColorFormat(handle)

    /**
     * Раскладка кадра — та же, что принимает [NativeRenderer.upload].
     *
     * Осмысленна только после первого [Step.FRAME]: до него декодер не сообщил
     * выходной формат.
     */
    val pixelFormat: NativeRenderer.PixelFormat?
        get() = if (handle == 0L) null
        else NativeRenderer.PixelFormat.entries.getOrNull(nativePixelFormat(handle))

    /** Видимый размер кадра: после кропа. */
    val outputWidth: Int get() = if (handle == 0L) 0 else nativeOutputWidth(handle)

    val outputHeight: Int get() = if (handle == 0L) 0 else nativeOutputHeight(handle)

    /** stride декодера, байт — диагностика; [copyPlane] отдаёт плотные строки. */
    val stride: Int get() = if (handle == 0L) 0 else nativeStride(handle)

    /** Строк в плоскости Y, включая невидимые: смещение плоскости UV. */
    val sliceHeight: Int get() = if (handle == 0L) 0 else nativeSliceHeight(handle)

    /**
     * true — stride и slice-height пришли от декодера; false — выведены из ширины.
     *
     * Различать обязательно: догадка о раскладке, совпавшая на одном устройстве,
     * на другом даёт «косой» кадр, и списывают это обычно на кодек.
     */
    val strideReported: Boolean get() = handle != 0L && nativeStrideReported(handle)

    /** Имя реально работающего компонента — в баг-репорты и в информационную панель. */
    val decoderName: String get() = if (handle == 0L) "" else nativeDecoderName(handle) ?: ""

    /** 1/2/3 — ступень лестницы декодеров, на которой поднялся текущий. */
    val rung: Int get() = if (handle == 0L) 0 else nativeRung(handle)

    /**
     * Описание эскалации или пустая строка.
     *
     * Непустая означает: аппаратный декодер не отдал 10 бит и был заменён
     * программным. Это ровно тот отказ, который проверка обязана показать, а не
     * сгладить, — поэтому он вынесен в API, а не только в logcat.
     */
    val escalation: String get() = if (handle == 0L) "" else nativeEscalation(handle) ?: ""

    /** Глубина потока по пробингу: 8, 10 или 12. */
    val streamBitDepth: Int get() = if (handle == 0L) 0 else nativeStreamBitDepth(handle)

    /** Просили ли у декодера 10 бит (`COLOR_FormatYUVP010`). */
    val tenBitRequested: Boolean get() = handle != 0L && nativeTenBitRequested(handle)

    /** Пришли ли 10 бит на самом деле. Осмысленно после первого кадра. */
    val tenBitOutput: Boolean get() = handle != 0L && nativeTenBitOutput(handle)

    /** Матрица конверсии из пробинга контейнера. */
    val standard: NativeRenderer.Standard?
        get() = if (handle == 0L) null
        else NativeRenderer.Standard.entries.getOrNull(nativeStandard(handle))

    val fullRange: Boolean get() = handle != 0L && nativeFullRange(handle)

    /** Кадров выдано декодером — для проверки, что насос вообще крутится. */
    val framesOut: Long get() = if (handle == 0L) 0 else nativeFramesOut(handle)

    val packetsIn: Long get() = if (handle == 0L) 0 else nativePacketsIn(handle)

    /** true для MediaCodec (ступени 1–3), false для libavcodec (ступень 4). */
    val isHardwareDecode: Boolean get() = rung in 1..3

    /**
     * Отпускает текущий кадр и освобождает декодер — в этом порядке.
     * Идемпотентно.
     */
    fun release() {
        val h = handle
        handle = 0L
        if (h != 0L) nativeRelease(h)
    }

    companion object {

        /**
         * По умолчанию ждём кадр 10 мс.
         *
         * Не 0: на нуле насос крутится в busy-loop и жжёт батарею на ожидании
         * декодера. Не 100: при 60 fps бюджет кадра 16 мс, и одно ожидание не
         * должно съедать его целиком — лучше вернуть [Step.AGAIN] и дать
         * вызывающему решить.
         */
        const val DEFAULT_TIMEOUT_MS = 10

        /**
         * Создаёт декодер видеопотока уже открытого и запущенного демукса.
         *
         * Демукс обязан быть в состоянии после [NativeDemuxer.start] и с выбранным
         * видеопотоком: пакеты берутся из его очередей, а не читаются здесь.
         *
         * @param demuxHandle      хэндл сессии [NativeDemuxer].
         * @param streamIndex      индекс видеопотока; -1 — лучший по пробингу.
         * @param preferTenBit     просить `COLOR_FormatYUVP010` для потоков глубже 8
         *                         бит. На 8-битном потоке не делает ничего.
         * @param allowEscalate    разрешить замену на программный декодер, если
         *                         аппаратный вернул 8 бит на 10-битном потоке.
         *                         В проверках выключается: иначе не увидеть, что
         *                         именно умеет декодер устройства.
         * @param sendHdrStaticInfo передать декодеру `hdr-static-info` из контейнера.
         * @param forceSoftware   сразу использовать libavcodec (ступень 4).
         * @param codecName        конкретный компонент вместо лестницы; null — лестница.
         * @throws EngineError с кодом [EngineError.Code.VIDEO_DECODER].
         */
        fun create(
            demuxHandle: Long,
            streamIndex: Int = -1,
            preferTenBit: Boolean = true,
            allowEscalate: Boolean = true,
            sendHdrStaticInfo: Boolean = true,
            forceSoftware: Boolean = false,
            codecName: String? = null,
            surface: Surface? = null
        ): NativeVideoDecoder {
            if (!NativeDemuxer.isAvailable) {
                throw EngineError(EngineError.Code.VIDEO_DECODER, "нативный движок не загружен")
            }
            val error = arrayOfNulls<String>(1)
            val handle = nativeCreate(
                demuxHandle, streamIndex, preferTenBit, allowEscalate,
                sendHdrStaticInfo, forceSoftware, codecName, surface, error
            )
            if (handle == 0L) {
                throw EngineError(
                    EngineError.Code.VIDEO_DECODER,
                    "не создался декодер: ${error[0] ?: "причина не указана"}"
                )
            }
            return NativeVideoDecoder(handle)
        }

        /** [create], но без исключения: null при отказе, причина — в logcat. */
        fun createOrNull(
            demuxHandle: Long,
            streamIndex: Int = -1,
            preferTenBit: Boolean = true,
            allowEscalate: Boolean = true,
            sendHdrStaticInfo: Boolean = true,
            forceSoftware: Boolean = false,
            codecName: String? = null,
            surface: Surface? = null
        ): NativeVideoDecoder? = try {
            create(
                demuxHandle, streamIndex, preferTenBit, allowEscalate,
                sendHdrStaticInfo, forceSoftware, codecName, surface
            )
        } catch (e: EngineError) {
            null
        }

        // ───────────────────────────── native ─────────────────────────────
        // Регистрируются в JNI_OnLoad (jni_decoder.cpp) через RegisterNatives:
        // расхождение подписи здесь и там — падение на System.loadLibrary, а не
        // UnsatisfiedLinkError на первом кадре.

        @JvmStatic
        private external fun nativeCreate(
            demuxHandle: Long,
            streamIndex: Int,
            preferTenBit: Boolean,
            allowEscalate: Boolean,
            sendHdr: Boolean,
            forceSoftware: Boolean,
            codecName: String?,
            surface: Surface?,
            errorOut: Array<String?>
        ): Long

        @JvmStatic
        private external fun nativeRelease(handle: Long)

        @JvmStatic
        private external fun nativeNextFrame(handle: Long, timeoutMs: Int): Int

        @JvmStatic
        private external fun nativeReleaseFrame(handle: Long)

        @JvmStatic
        private external fun nativeFlush(handle: Long): Boolean

        @JvmStatic
        private external fun nativeUploadToRenderer(handle: Long, rendererHandle: Long): Boolean

        @JvmStatic
        private external fun nativeRenderToSurface(handle: Long): Boolean

        @JvmStatic
        private external fun nativeSurfaceOutput(handle: Long): Boolean

        @JvmStatic
        private external fun nativePlaneRowBytes(handle: Long, planeIndex: Int): Int

        @JvmStatic
        private external fun nativePlaneRows(handle: Long, planeIndex: Int): Int

        @JvmStatic
        private external fun nativeCopyPlane(handle: Long, planeIndex: Int, out: ByteArray): Int

        @JvmStatic
        private external fun nativeFramePtsUs(handle: Long): Long

        @JvmStatic
        private external fun nativeColorFormat(handle: Long): Int

        @JvmStatic
        private external fun nativePixelFormat(handle: Long): Int

        @JvmStatic
        private external fun nativeOutputWidth(handle: Long): Int

        @JvmStatic
        private external fun nativeOutputHeight(handle: Long): Int

        @JvmStatic
        private external fun nativeStride(handle: Long): Int

        @JvmStatic
        private external fun nativeSliceHeight(handle: Long): Int

        @JvmStatic
        private external fun nativeStrideReported(handle: Long): Boolean

        @JvmStatic
        private external fun nativeDecoderName(handle: Long): String?

        @JvmStatic
        private external fun nativeRung(handle: Long): Int

        @JvmStatic
        private external fun nativeEscalation(handle: Long): String?

        @JvmStatic
        private external fun nativeStreamBitDepth(handle: Long): Int

        @JvmStatic
        private external fun nativeTenBitRequested(handle: Long): Boolean

        @JvmStatic
        private external fun nativeTenBitOutput(handle: Long): Boolean

        @JvmStatic
        private external fun nativeStandard(handle: Long): Int

        @JvmStatic
        private external fun nativeFullRange(handle: Long): Boolean

        @JvmStatic
        private external fun nativeFramesOut(handle: Long): Long

        @JvmStatic
        private external fun nativePacketsIn(handle: Long): Long
    }
}
