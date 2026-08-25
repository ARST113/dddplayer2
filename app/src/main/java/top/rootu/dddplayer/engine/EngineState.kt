package top.rootu.dddplayer.engine

/**
 * Состояние движка. Заменяет `Player.STATE_*` из Media3, чтобы типы конкретного
 * бекенда не просачивались в ViewModel и UI.
 */
enum class EngineState {
    /** Ничего не загружено, либо освобождён. */
    IDLE,

    /** Источник открыт, данных для воспроизведения пока не хватает. */
    BUFFERING,

    /** Готов играть (играет или на паузе — см. [PlaybackEngine.isPlaying]). */
    READY,

    /** Текущий элемент дошёл до конца. */
    ENDED
}
