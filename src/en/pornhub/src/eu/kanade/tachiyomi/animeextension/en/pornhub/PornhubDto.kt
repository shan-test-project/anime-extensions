package eu.kanade.tachiyomi.animeextension.en.pornhub

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
class FlashVarsDto(
    @SerialName("video_title") val videoTitle: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("video_duration") val videoDuration: Long? = null,
    @SerialName("video_unavailable") val videoUnavailable: Boolean? = null,
    val mediaDefinitions: List<MediaDefinitionDto> = emptyList(),
)

@Serializable
class MediaDefinitionDto(
    val format: String = "",
    val videoUrl: String = "",
    val quality: JsonElement? = null,
    val height: Int? = null,
)
