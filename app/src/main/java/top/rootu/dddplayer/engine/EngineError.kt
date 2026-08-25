package top.rootu.dddplayer.engine

/**
 * Ошибка движка. Наследует [Exception], чтобы проходить через уже существующие
 * пути обработки ошибок в `PlayerViewModel`.
 *
 * [code] заменяет разбор текста стектрейса, которым сейчас занимается
 * `PlayerManager.isVideoDecoderError` — движок сам знает, что именно упало.
 */
class EngineError(
    val code: Code,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    enum class Code {
        /** Не открылся источник: сеть, файл, права, 404 от TorrServer. */
        SOURCE,

        /** Контейнер открылся, но разбор потока провалился. */
        DEMUX,

        /** Не удалось инициализировать или прокачать видеодекодер. */
        VIDEO_DECODER,

        /** Не удалось инициализировать или прокачать аудиодекодер. */
        AUDIO_DECODER,

        /** Формат/кодек/профиль не поддерживается ни HW-, ни SW-путём. */
        UNSUPPORTED,

        /** Источник перестал отдавать данные. */
        TIMEOUT,

        UNKNOWN
    }

    override fun toString(): String = "EngineError($code): $message"
}
