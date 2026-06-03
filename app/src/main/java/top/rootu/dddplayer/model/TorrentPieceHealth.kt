package top.rootu.dddplayer.model

data class TorrentPieceHealth(
    val percent: Int,
    val level: Level,
    val activeDots: Int,
    val totalDots: Int = 5
) {
    enum class Level {
        RED,
        YELLOW,
        GREEN
    }
}
