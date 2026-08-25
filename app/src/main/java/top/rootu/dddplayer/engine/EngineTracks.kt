package top.rootu.dddplayer.engine

import top.rootu.dddplayer.player.BackendAudioTrack
import top.rootu.dddplayer.player.BackendSubtitleTrack

/**
 * Видеодорожка (вариант качества). Для локального файла обычно одна;
 * для HLS/DASH — по одной на вариант.
 *
 * Заменяет пару `androidx.media3.common.TrackGroup` + индекс, которую сейчас
 * хранит `VideoQualityOption`: единый движок отдаёт дорожку по стабильному [id],
 * без ссылок на внутренние объекты бекенда.
 */
data class EngineVideoTrack(
    val id: Int,
    val width: Int = 0,
    val height: Int = 0,
    val bitrate: Int = 0,
    val frameRate: Float = 0f,
    val codec: String? = null,
    val selected: Boolean = false
) {
    /** `2160p`, либо `null` если размер неизвестен. */
    val heightLabel: String? get() = if (height > 0) "${height}p" else null
}

/**
 * Полный набор дорожек текущего элемента.
 *
 * До унификации их было два независимых набора: `exoPlayer.currentTracks`
 * (`PlayerManager.kt:774`, `TrackLogic`) и `getVlc*Tracks` (`PlayerManager.kt:760-765`).
 * Единый движок отдаёт один.
 *
 * `BackendAudioTrack` и `BackendSubtitleTrack` переиспользуются из существующего
 * кода DDD: они уже описывают ровно нужные поля.
 */
data class EngineTracks(
    val video: List<EngineVideoTrack> = emptyList(),
    val audio: List<BackendAudioTrack> = emptyList(),
    val subtitle: List<BackendSubtitleTrack> = emptyList(),

    /**
     * Выбор видеодорожки отдан адаптивной логике движка (ABR), а не пользователю.
     * Соответствует пункту `Auto` в меню качества.
     */
    val videoAuto: Boolean = true
) {
    val selectedVideo: EngineVideoTrack? get() = video.firstOrNull { it.selected }
    val selectedAudio: BackendAudioTrack? get() = audio.firstOrNull { it.selected }
    val selectedSubtitle: BackendSubtitleTrack? get() = subtitle.firstOrNull { it.selected }

    val isEmpty: Boolean get() = video.isEmpty() && audio.isEmpty() && subtitle.isEmpty()

    companion object {
        val EMPTY = EngineTracks()
    }
}
