package top.rootu.dddplayer.engine

/**
 * Приёмник результатов пробинга: нативная сторона вызывает эти методы по одному
 * разу на каждую найденную сущность.
 *
 * Почему колбэк, а не возврат готовых объектов из native: собрать Kotlin
 * `data class` через JNI можно только `NewObject` с позиционными аргументами, и
 * тогда добавление поля в `EngineVideoTrack` ломает нативный код молча — подпись
 * конструктора меняется, `GetMethodID` возвращает null уже в рантайме. Здесь же
 * весь контракт — примитивы, `String?` и `ByteArray?`, а расхождение подписи
 * ловится при `System.loadLibrary` (`RegisterNatives` в `jni_engine.cpp`).
 *
 * Порядок вызовов: [container], затем дорожки в порядке индексов потоков, затем
 * [color], [geometry], [best]. Ни один метод не обязателен к повторному вызову:
 * у файла без звука [audio] не вызовется ни разу.
 *
 * Все методы вызываются из того же потока, что и `NativeDemuxer.probe`.
 */
interface ProbeSink {

    fun container(format: String?, longName: String?, durationUs: Long, bitrate: Long, seekable: Boolean)

    /**
     * @param sarNum/sarDen отношение сторон пикселя; нативная сторона нормализует
     *        неуказанный SAR в 1:1 (FFmpeg отдаёт его как 0:1, а 0 в числителе
     *        дал бы нулевую ширину кадра).
     * @param mime `null`, если для кодека нет MIME в Android — это не ошибка, а
     *        признак «HW-пути нет, играем через libavcodec».
     */
    fun video(
        streamIndex: Int,
        width: Int,
        height: Int,
        sarNum: Int,
        sarDen: Int,
        frameRate: Float,
        bitrate: Int,
        codec: String?,
        mime: String?,
        profile: Int,
        level: Int,
        rotation: Int,
        bitDepth: Int,
        isDefault: Boolean
    )

    /** @param profile `DTS-HD MA`, `TrueHD` и подобное — для подписи дорожки в меню. */
    fun audio(
        streamIndex: Int,
        codec: String?,
        profile: String?,
        channels: Int,
        sampleRate: Int,
        bitrate: Int,
        language: String?,
        title: String?,
        isDefault: Boolean,
        isForced: Boolean
    )

    /** @param isBitmap PGS/VobSub — картинка, а не текст. */
    fun subtitle(
        streamIndex: Int,
        codec: String?,
        language: String?,
        title: String?,
        isDefault: Boolean,
        isForced: Boolean,
        isBitmap: Boolean
    )

    /**
     * @param standard/transfer/range константы домена `MediaFormat`, см. [EngineColorInfo].
     * @param dolbyStreamIndex поток, в котором найдена конфигурация Dolby Vision;
     *        отличается от основного видеопотока у профиля 7, где `dvvC` лежит на
     *        втором (enhancement layer).
     * @param staticInfo 25 байт CTA-861.3 либо `null`. Пустой блок не приходит
     *        никогда: 25 нулей декодер понял бы как «пиковая яркость 0».
     */
    fun color(
        standard: Int,
        transfer: Int,
        range: Int,
        bitDepth: Int,
        dolbyProfile: Int,
        dolbyStreamIndex: Int,
        hasHdr10Plus: Boolean,
        staticInfo: ByteArray?
    )

    /** @param stereo/projection ordinal'ы [StereoLayout] и [Projection] — порядок enum'ов часть контракта. */
    fun geometry(stereo: Int, projection: Int)

    /** Индексы потоков, выбранных по умолчанию (`av_find_best_stream`); -1 — нет такого типа. */
    fun best(video: Int, audio: Int, subtitle: Int)
}
