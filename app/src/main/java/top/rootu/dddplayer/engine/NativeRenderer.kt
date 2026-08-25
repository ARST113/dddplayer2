package top.rootu.dddplayer.engine

import android.view.Surface

/**
 * Обёртка над render stage движка: EGL-контекст, конверсия YUV→RGB, вывод кадра.
 *
 * Единственный путь вывода в движке (UNIFIED-ENGINE.md §4): и плоский
 * `SurfaceView`, и VR-слой, и снимок кадра идут через него.
 *
 * **Поток.** Все методы, кроме [reference], обязаны вызываться из одного и того
 * же потока — того, где выполнен [create]. Это свойство EGL: контекст текущий
 * ровно в одном потоке, и GL-вызов из чужого потока молча ничего не делает.
 * Обёртка не заводит поток сама: на шаге 5 кадры пойдут из декодера, и поток
 * рендера будет общим с ним, а лишний поток здесь пришлось бы сразу убирать.
 *
 * **Ресурсы.** Хэндл — `Long`, без финализатора: незакрытый контекст держит
 * видеопамять и окно, а финализаторы в Android не гарантируют вызов. Закрывает
 * владелец, через [release].
 */
class NativeRenderer private constructor(private var handle: Long) {

    /**
     * Раскладка кадра в памяти; порядок значений дублирует `FramePixelFormat` в
     * native, поэтому переставлять элементы нельзя — передаётся [Enum.ordinal].
     *
     * 10/12/16-битные варианты перечислены по осям «глубина × прореживание»: в
     * 4XVR это были семь отдельных функций `ConvertData_*_10Bit` с NEON-упаковкой
     * на каждую комбинацию, здесь — семь строк таблицы и один шейдер.
     */
    enum class PixelFormat {
        YUV420P, NV12, NV21,
        YUV420P10LE, YUV420P12LE, YUV420P16LE,
        YUV422P10LE, YUV422P12LE, YUV422P16LE,
        YUV444P10LE, YUV444P12LE, YUV444P16LE,

        /**
         * `COLOR_FormatYUVP010` у MediaCodec: Y + перемежённые UV по 16 бит,
         * значащие 10 бит в **старших** разрядах. Спутать с [YUV420P10LE] —
         * получить картинку в 64 раза темнее.
         */
        P010;

        /** Значащих бит на отсчёт. */
        val bitDepth: Int
            get() = when (this) {
                YUV420P, NV12, NV21 -> 8
                YUV420P10LE, YUV422P10LE, YUV444P10LE, P010 -> 10
                YUV420P12LE, YUV422P12LE, YUV444P12LE -> 12
                YUV420P16LE, YUV422P16LE, YUV444P16LE -> 16
            }

        /** Прореживание цветности по горизонтали и вертикали. */
        val subX: Int
            get() = if (this in setOf(YUV444P10LE, YUV444P12LE, YUV444P16LE)) 1 else 2

        val subY: Int
            get() = when (this) {
                YUV444P10LE, YUV444P12LE, YUV444P16LE -> 1
                YUV422P10LE, YUV422P12LE, YUV422P16LE -> 1
                else -> 2
            }

        /** UV в одной перемежённой плоскости. */
        val isSemiplanar: Boolean get() = this == NV12 || this == NV21 || this == P010

        val isSixteenBit: Boolean get() = bitDepth > 8
    }

    /** Каким путём 16-битные плоскости попали в текстуру; см. `UploadPath` в native. */
    enum class UploadPath { BYTE, NORM16, BYTE_PAIR }

    /** Матрица конверсии; значения совпадают с `EngineColorInfo.ColorStandard`. */
    enum class Standard { BT601, BT709, BT2020 }

    /**
     * Передаточная характеристика источника; порядок дублирует `ColorTransfer`
     * в `tone_map.h`, поэтому переставлять нельзя — передаётся [Enum.ordinal].
     */
    enum class Transfer {
        /** Тонмаппинг не применяется: путь шага 4 бит-в-бит. */
        SDR,
        PQ,
        HLG;

        companion object {
            /**
             * Из `MediaFormat.KEY_COLOR_TRANSFER` (значения [EngineColorInfo]).
             *
             * Неизвестное значение — [SDR], а не исключение: неверно опознанный
             * transfer у одного файла не должен ломать воспроизведение, а вымытая
             * картинка на нём — меньшее зло, чем отказ играть.
             */
            fun fromColorTransfer(value: Int): Transfer = when (value) {
                EngineColorInfo.COLOR_TRANSFER_ST2084 -> PQ
                EngineColorInfo.COLOR_TRANSFER_HLG -> HLG
                else -> SDR
            }
        }
    }

    /**
     * Параметры тонмаппинга PQ/HLG → панель (шаг 6).
     *
     * Это и есть закрытие исходной проблемы проекта: на гарнитуре нет системного
     * HDR-конвейера (UNIFIED-ENGINE.md §2 — `HdrCapabilities` пуст, композитор в
     * `ColorMode::NATIVE`), поэтому PQ-сигнал, отданный как есть, читается как
     * SDR и даёт вымытую тёмную картинку. Здесь HDR-метаданные работают входными
     * данными тонмаппера, а не переключателем режима экрана.
     *
     * Задаются на файл: mastering display и maxCLL описывают весь поток.
     */
    data class HdrParams(
        val transfer: Transfer = Transfer.SDR,

        /** Пик панели, кд/м². 500 — измеренное значение гарнитуры (§2). */
        val displayPeakNits: Float = 500f,

        /**
         * Пик мастеринга из `hdr-static-info`, кд/м²; 0 — нет данных.
         *
         * Ради этого числа и собирается блок CTA-861.3: тонмаппер обязан знать,
         * ОТКУДА сжимать. Завышенное значение делает картинку темнее задуманного.
         */
        val masteringPeakNits: Float = 0f,

        /** maxCLL, кд/м²; резерв, когда пика мастеринга нет. */
        val maxCllNits: Float = 0f,

        /**
         * maxFALL, кд/м².
         *
         * Пока не участвует в кривой: средняя яркость нужна ДИНАМИЧЕСКОМУ
         * тонмаппингу (HDR10+/DV, шаги 10+), а статическая кривая обязана
         * зависеть только от пика — иначе яркость сцены поехала бы от содержимого
         * соседних кадров.
         */
        val maxFallNits: Float = 0f,

        /**
         * Регулятор яркости HDR: множитель к световому потоку ДО кривой.
         *
         * До кривой, а не после: умножение после тонмаппинга загнало бы верх в
         * клиппинг и съело детали в облаках. Аналог per-file `SetHDRBrightness`
         * из 4XVR.
         */
        val brightness: Float = 1f,

        /**
         * Гамма панели. 2.2, а не 2.4 (BT.1886): композитор в `ColorMode::NATIVE`
         * трактует кадр как sRGB-подобный, и 2.4 дала бы провал в тенях.
         */
        val displayGamma: Float = 2.2f,

        /** Переводить BT.2020 → BT.709. */
        val convertGamut: Boolean = true,

        /**
         * Повторять HDR-модификатор 4XVR для Dolby Vision.
         *
         * Это отдельный путь, а не замена корректному BT.2390: эталоном для
         * данного проекта пользователь выбрал именно картинку 4XVR. Значение
         * [brightness] 1.0 соответствует штатному HDR-регулятору 4XVR 0.5.
         */
        val matchFourXvr: Boolean = false
    ) {
        val isActive: Boolean get() = transfer != Transfer.SDR

        /** Пик источника с резервами: mastering → maxCLL → 1000 нит. */
        val sourcePeakNits: Float
            get() = when {
                masteringPeakNits > 0f -> masteringPeakNits
                maxCllNits > 0f -> maxCllNits
                else -> 1000f
            }

        companion object {
            /**
             * Собирает параметры из результата пробинга.
             *
             * SDR-файл даёт [Transfer.SDR], то есть тонмаппинг не включается: HDR
             * «на всякий случай» осветлил бы обычное видео.
             */
            fun from(
                color: EngineColorInfo,
                displayPeakNits: Float = 500f,
                brightness: Float = 1f
            ): HdrParams = HdrParams(
                transfer = Transfer.fromColorTransfer(color.colorTransfer),
                displayPeakNits = displayPeakNits,
                masteringPeakNits = (color.maxDisplayLuminance ?: 0).toFloat(),
                maxCllNits = (color.maxContentLightLevel ?: 0).toFloat(),
                maxFallNits = (color.maxFrameAverageLightLevel ?: 0).toFloat(),
                brightness = brightness,
                matchFourXvr = color.dolbyProfile != 0
            )
        }
    }

    /** Как вписывать кадр в окно. */
    enum class ScaleMode { FIT, FILL, STRETCH }

    private val sizeOut = IntArray(2)

    val isReleased: Boolean get() = handle == 0L

    /**
     * Хэндл для native-кода движка: [NativeVideoDecoder.uploadToRenderer] заливает
     * кадр из буфера декодера прямо в текстуры, минуя JVM.
     *
     * internal, а не public: снаружи движка с сырым указателем делать нечего, а
     * копия хэндла, пережившая [release], — это use-after-free.
     */
    internal val nativeHandle: Long get() = handle

    /**
     * Размер поверхности в пикселях. Перечитывается у EGL каждый раз: окно
     * меняет размер при повороте экрана, и кэшировать его нельзя.
     */
    fun surfaceSize(): Pair<Int, Int> {
        if (handle == 0L || !nativeSurfaceSize(handle, sizeOut)) return 0 to 0
        return sizeOut[0] to sizeOut[1]
    }

    /** Строка `OpenGL ES 3.x / <GPU>` — в информационную панель и баг-репорты. */
    fun glInfo(): String = if (handle == 0L) "нет контекста" else nativeGlInfo(handle) ?: "?"

    /**
     * Фильтр интерполяции цветности: `true` (по умолчанию) — линейный.
     *
     * В 4:2:0 на блок 2×2 приходится один отсчёт цветности, и способ его
     * размножения — это выбор, а не арифметика. Линейный лучше на градиентах и
     * лицах; `false` (ближайший сосед) нужен, чтобы результат совпадал с
     * эталоном `SWS_POINT` и был сравним побайтово.
     */
    fun setChromaFilter(linear: Boolean) {
        if (handle != 0L) nativeSetChromaFilter(handle, linear)
    }

    /**
     * Пиксельные пропорции (`sarNum / sarDen`).
     *
     * Анаморфное видео (DVD 720×576 с SAR 64:45) при честном 1:1 выглядит
     * сплющенным, и это не «так снято».
     */
    fun setPixelAspectRatio(par: Float) {
        if (handle != 0L) nativeSetPixelAspectRatio(handle, par)
    }

    /**
     * Запретить `GL_EXT_texture_norm16` и уйти на путь
     * [UploadPath.BYTE_PAIR].
     *
     * Нужно для проверяемости, а не для настройки: без переключателя выполнялся
     * бы только тот путь, который выбрал GPU. Замер на Pixel 6 (Mali-G78)
     * показал, что расширения там нет вообще и BYTE_PAIR — единственный путь;
     * переключатель поэтому пригодится на GPU, где расширение есть, чтобы
     * сравнить оба пути на одном кадре.
     */
    fun setForceBytePair(force: Boolean) {
        if (handle != 0L) nativeSetForceBytePair(handle, force)
    }

    /**
     * Включает тонмаппинг PQ/HLG (шаг 6). Действует со следующего [draw].
     *
     * Отдельно от [upload], потому что параметры относятся к файлу, а не к кадру:
     * на кадре они пересчитывались бы 60 раз в секунду без изменения результата.
     */
    fun setHdrParams(params: HdrParams) {
        if (handle == 0L) return
        hdrParams = params
        nativeSetHdrParams(
            handle, params.transfer.ordinal, params.displayPeakNits,
            params.masteringPeakNits, params.maxCllNits, params.maxFallNits,
            params.brightness, params.displayGamma, params.convertGamut,
            params.matchFourXvr
        )
    }

    /** Текущие параметры тонмаппинга. */
    var hdrParams: HdrParams = HdrParams()
        private set

    /** Каким путём залит последний кадр; null, если рендерер освобождён. */
    fun uploadPath(): UploadPath? {
        if (handle == 0L) return null
        return UploadPath.entries.getOrNull(nativeUploadPath(handle))
    }

    /** Есть ли `GL_EXT_texture_norm16` в этом контексте. */
    fun hasNorm16(): Boolean = handle != 0L && nativeHasNorm16(handle)

    /**
     * Переключает вывод в offscreen-цель заданной точности.
     *
     * Без этого проверить 10-битный путь нечем: поверхность окна на телефоне
     * 8-битная, и любой градиент схлопнется в 256 уровней независимо от того, что
     * лежало в текстуре. На шаге 6 сюда же будет писать тонмаппинг, чтобы
     * промежуточный результат не терял точность до вывода.
     *
     * @param tenBit `GL_RGB10_A2` вместо `GL_RGBA8`.
     */
    fun setRenderTarget(width: Int, height: Int, tenBit: Boolean): Boolean =
        handle != 0L && nativeSetRenderTarget(handle, width, height, tenBit)

    /** Возвращает вывод в поверхность EGL и освобождает offscreen-цель. */
    fun clearRenderTarget(): Boolean =
        handle != 0L && nativeSetRenderTarget(handle, 0, 0, false)

    /**
     * Заливает кадр в текстуры. Не рисует.
     *
     * @param planes  Y, U, V (для NV12/NV21 — Y и UV, третий элемент не нужен).
     * @param strides байт на строку каждой плоскости. Это НЕ ширина: декодеры
     *                выравнивают строки, и без учёта stride картинка «косая».
     */
    fun upload(
        planes: Array<ByteArray?>,
        strides: IntArray,
        width: Int,
        height: Int,
        format: PixelFormat = PixelFormat.YUV420P,
        standard: Standard = Standard.BT709,
        fullRange: Boolean = false
    ): Boolean {
        if (handle == 0L) return false
        return nativeUploadFrame(
            handle,
            planes.getOrNull(0), strides.getOrElse(0) { 0 },
            planes.getOrNull(1), strides.getOrElse(1) { 0 },
            planes.getOrNull(2), strides.getOrElse(2) { 0 },
            width, height, format.ordinal, standard.ordinal, fullRange
        )
    }

    /**
     * Рисует последний залитый кадр в текущую поверхность.
     *
     * @param rotation 0/90/180/270 из display matrix контейнера.
     */
    fun draw(rotation: Int = 0, mode: ScaleMode = ScaleMode.FIT): Boolean =
        handle != 0L && nativeDraw(handle, rotation, mode.ordinal)

    /** Показывает нарисованное. Для offscreen-контекста — пустая операция. */
    fun swap(): Boolean = handle != 0L && nativeSwap(handle)

    /**
     * Читает поверхность в RGBA, **сверху вниз** (переворот `glReadPixels`
     * сделан в native).
     *
     * @param out буфер `width * height * 4`.
     */
    fun readPixels(out: ByteArray): Boolean = handle != 0L && nativeReadPixels(handle, out)

    /**
     * Читает offscreen-цель в упакованные слова `2_10_10_10_REV`: R в битах 0–9,
     * G в 10–19, B в 20–29, A в 30–31. Строки сверху вниз.
     *
     * Требует активной цели ([setRenderTarget]): у поверхности EGL точность 8 бит,
     * и «десятибитное» чтение из неё создавало бы ровно ту иллюзию, которую
     * проверка обязана опровергнуть.
     *
     * @param out массив `width * height`.
     */
    fun readPacked10(out: IntArray): Boolean = handle != 0L && nativeReadPacked10(handle, out)

    /**
     * Замер вывода: заливка текстур + рисование + `glFinish` на кадр.
     *
     * Кадр генерируется в native и не ходит через JVM, иначе замер мерил бы
     * копирование массивов. `glFinish` обязателен: без него измеряется время
     * записи команд в очередь драйвера, а не отрисовки.
     *
     * @return наносекунды на [frames] кадров; -1 при ошибке.
     */
    fun benchmark(width: Int, height: Int, frames: Int, format: PixelFormat = PixelFormat.YUV420P): Long =
        if (handle == 0L) -1 else nativeBenchmark(handle, width, height, frames, format.ordinal)

    /** Освобождает GL-объекты, контекст и окно — в этом порядке. Идемпотентно. */
    fun release() {
        val h = handle
        handle = 0L
        if (h != 0L) nativeRelease(h)
    }

    companion object {
        /**
         * Оконный вывод на `Surface` (из `SurfaceView`, `ImageReader`, `MediaCodec`).
         *
         * @return null, если EGL или шейдер не поднялись; причина — в logcat.
         */
        fun forSurface(surface: Surface): NativeRenderer? = create(surface, 0, 0)

        /**
         * Offscreen-вывод в pbuffer.
         *
         * Нужен не только для тестов: снимок кадра, превью и рендер в файл — это
         * тот же самый конвейер без окна. Проверяемость получается побочным
         * следствием: из pbuffer пиксели читаются, из SurfaceFlinger — нет.
         */
        fun offscreen(width: Int, height: Int): NativeRenderer? = create(null, width, height)

        private fun create(surface: Surface?, width: Int, height: Int): NativeRenderer? {
            if (!NativeDemuxer.isAvailable) return null
            val handle = nativeCreate(surface, width, height)
            return if (handle == 0L) null else NativeRenderer(handle)
        }

        /**
         * Эталонная конверсия YUV→RGBA через swscale. GL не нужен.
         *
         * Это та же арифметика BT.601/709/2020, что и в шейдере, но независимая
         * реализация — тем и ценна: расхождение означает ошибку в одной из двух,
         * а не «оба посчитали одинаково неправильно».
         *
         * @param out буфер `width * height * 4`, RGBA, сверху вниз.
         */
        fun reference(
            planes: Array<ByteArray?>,
            strides: IntArray,
            width: Int,
            height: Int,
            format: PixelFormat = PixelFormat.YUV420P,
            standard: Standard = Standard.BT709,
            fullRange: Boolean = false,
            out: ByteArray
        ): Boolean {
            if (!NativeDemuxer.isAvailable) return false
            return nativeReference(
                planes.getOrNull(0), strides.getOrElse(0) { 0 },
                planes.getOrNull(1), strides.getOrElse(1) { 0 },
                planes.getOrNull(2), strides.getOrElse(2) { 0 },
                width, height, format.ordinal, standard.ordinal, fullRange, out
            )
        }

        /**
         * Эталон для 10/12/16-битных кадров: RGBA64LE, 16 бит на канал.
         *
         * Отдельно от [reference] не ради типа буфера: восьмибитный эталон на
         * 10-битном кадре сам стал бы горлышком точности, и сравнение показало бы
         * «совпадает» при любой потере младших разрядов в шейдере.
         *
         * @param out `width * height * 4` значений, R,G,B,A, сверху вниз.
         *            Значения беззнаковые — читать как `out[i].toInt() and 0xffff`.
         */
        fun reference16(
            planes: Array<ByteArray?>,
            strides: IntArray,
            width: Int,
            height: Int,
            format: PixelFormat,
            standard: Standard = Standard.BT709,
            fullRange: Boolean = false,
            out: ShortArray
        ): Boolean {
            if (!NativeDemuxer.isAvailable) return false
            return nativeReference16(
                planes.getOrNull(0), strides.getOrElse(0) { 0 },
                planes.getOrNull(1), strides.getOrElse(1) { 0 },
                planes.getOrNull(2), strides.getOrElse(2) { 0 },
                width, height, format.ordinal, standard.ordinal, fullRange, out
            )
        }

        /**
         * Эталон тон-кривой на CPU для тех же параметров, что у шейдера.
         *
         * Нужен, чтобы отделить ошибку в математике от ошибки в GL: расхождение
         * CPU и GPU указывает на шейдер, драйвер или точность, а совпадение обоих
         * при несовпадении с ITU-R — на формулу.
         *
         * @param rgb входные PQ/HLG-коды, кратно 3.
         * @return значения для панели (с гаммой) или null при ошибке.
         */
        fun toneMapReference(params: HdrParams, rgb: FloatArray): FloatArray? {
            if (!NativeDemuxer.isAvailable || rgb.isEmpty() || rgb.size % 3 != 0) return null
            val out = FloatArray(rgb.size)
            val ok = nativeToneMapReference(
                params.transfer.ordinal, params.displayPeakNits, params.masteringPeakNits,
                params.maxCllNits, params.maxFallNits, params.brightness,
                params.displayGamma, params.convertGamut, params.matchFourXvr, rgb, out
            )
            return if (ok) out else null
        }

        /** PQ-код → кд/м². Обратное преобразование — [nitsToPq]. */
        fun pqToNits(code: Float): Float =
            if (!NativeDemuxer.isAvailable) -1f else nativePqTransfer(code, false)

        /**
         * кд/м² → PQ-код.
         *
         * Тесты задают яркость в нитах: «пиковый белый 1000 нит» — утверждение о
         * физике, а «код 0.7518» — уже результат применения проверяемой функции.
         */
        fun nitsToPq(nits: Float): Float =
            if (!NativeDemuxer.isAvailable) -1f else nativePqTransfer(nits, true)

        // ───────────────────────────── native ─────────────────────────────
        // Регистрируются в JNI_OnLoad (jni_renderer.cpp) через RegisterNatives:
        // расхождение подписи здесь и там — падение на System.loadLibrary, а не
        // UnsatisfiedLinkError на первом кадре.

        @JvmStatic
        private external fun nativeCreate(surface: Surface?, width: Int, height: Int): Long

        @JvmStatic
        private external fun nativeRelease(handle: Long)

        @JvmStatic
        private external fun nativeSurfaceSize(handle: Long, out: IntArray): Boolean

        @JvmStatic
        private external fun nativeGlInfo(handle: Long): String?

        @JvmStatic
        private external fun nativeSetChromaFilter(handle: Long, linear: Boolean)

        @JvmStatic
        private external fun nativeSetPixelAspectRatio(handle: Long, par: Float)

        @JvmStatic
        private external fun nativeSetForceBytePair(handle: Long, force: Boolean)

        @JvmStatic
        private external fun nativeUploadPath(handle: Long): Int

        @JvmStatic
        private external fun nativeHasNorm16(handle: Long): Boolean

        @JvmStatic
        private external fun nativeSetRenderTarget(
            handle: Long, width: Int, height: Int, tenBit: Boolean
        ): Boolean

        @JvmStatic
        private external fun nativeUploadFrame(
            handle: Long,
            p0: ByteArray?, s0: Int,
            p1: ByteArray?, s1: Int,
            p2: ByteArray?, s2: Int,
            width: Int, height: Int,
            format: Int, standard: Int, fullRange: Boolean
        ): Boolean

        @JvmStatic
        private external fun nativeDraw(handle: Long, rotation: Int, mode: Int): Boolean

        @JvmStatic
        private external fun nativeSwap(handle: Long): Boolean

        @JvmStatic
        private external fun nativeReadPixels(handle: Long, out: ByteArray): Boolean

        @JvmStatic
        private external fun nativeReadPacked10(handle: Long, out: IntArray): Boolean

        @JvmStatic
        private external fun nativeReference(
            p0: ByteArray?, s0: Int,
            p1: ByteArray?, s1: Int,
            p2: ByteArray?, s2: Int,
            width: Int, height: Int,
            format: Int, standard: Int, fullRange: Boolean,
            out: ByteArray
        ): Boolean

        @JvmStatic
        private external fun nativeReference16(
            p0: ByteArray?, s0: Int,
            p1: ByteArray?, s1: Int,
            p2: ByteArray?, s2: Int,
            width: Int, height: Int,
            format: Int, standard: Int, fullRange: Boolean,
            out: ShortArray
        ): Boolean

        @JvmStatic
        private external fun nativeBenchmark(
            handle: Long, width: Int, height: Int, frames: Int, format: Int
        ): Long

        @JvmStatic
        private external fun nativeSetHdrParams(
            handle: Long, transfer: Int, displayPeak: Float, masteringPeak: Float,
            maxCll: Float, maxFall: Float, brightness: Float, gamma: Float,
            convertGamut: Boolean, matchFourXvr: Boolean
        )

        @JvmStatic
        private external fun nativeToneMapReference(
            transfer: Int, displayPeak: Float, masteringPeak: Float,
            maxCll: Float, maxFall: Float, brightness: Float, gamma: Float,
            convertGamut: Boolean, matchFourXvr: Boolean,
            rgbIn: FloatArray, rgbOut: FloatArray
        ): Boolean

        @JvmStatic
        private external fun nativePqTransfer(value: Float, inverse: Boolean): Float
    }
}
