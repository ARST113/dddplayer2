package top.rootu.dddplayer.bridge

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

class DddSyncRemoteClient(
    private val httpClient: OkHttpClient
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun send(event: BridgeEvent, context: DddSyncContext) {
        if (!context.enabled) return

        val requestBody = buildBody(event, context)
            ?.toString()
            ?.toRequestBody(jsonMediaType)
            ?: return

        val request = Request.Builder()
            .url(context.remoteEventsUrl)
            .post(requestBody)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.d(TAG, "send failed type=${event.typeName()}: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        Log.d(TAG, "send ok type=${event.typeName()} code=${it.code}")
                    } else {
                        Log.w(TAG, "send rejected type=${event.typeName()} code=${it.code}")
                    }
                }
            }
        })
    }

    private fun buildBody(event: BridgeEvent, context: DddSyncContext): JsonObject? {
        val payload = payloadFromEvent(event) ?: return null

        return JsonObject().apply {
            addProperty("schema", context.schema)
            addProperty("deviceId", context.deviceId)
            add("events", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("schema", context.schema)
                    addProperty("type", event.typeName())
                    addProperty("client", "dddplayer2")
                    addProperty("deviceId", context.deviceId)
                    addProperty("sessionId", event.sessionId ?: context.sessionId)
                    addProperty("ts", event.ts)
                    add("context", contextJson(context, event.uri))
                    add("payload", payload)
                })
            })
        }
    }

    private fun contextJson(context: DddSyncContext, eventUri: String?): JsonObject {
        return JsonObject().apply {
            addProperty("contentKey", context.contentKey.orEmpty())
            addProperty("sourceKey", context.sourceKey.orEmpty())
            addProperty("timelineHash", context.timelineHash.orEmpty())
            addProperty("sourceKind", context.sourceKind.orEmpty())
            addProperty("uri", context.uri ?: eventUri.orEmpty())
            addProperty("title", context.title.orEmpty())
            addProperty("filename", context.filename.orEmpty())
        }
    }

    private fun payloadFromEvent(event: BridgeEvent): JsonObject? {
        return JsonObject().apply {
            when (event) {
                is BridgeEvent.SessionStarted -> {
                    addLong("position", event.startPosition)
                    addProperty("windowIndex", event.startIndex)
                    addProperty("playlistSize", event.playlistSize)
                    addProperty("isPlaying", true)
                    addProperty("reason", "ddd_launch")
                }

                is BridgeEvent.PositionTick -> {
                    addLong("position", event.position)
                    addLong("duration", event.duration)
                    addLong("bufferedPosition", event.bufferedPosition)
                    addInt("bufferedPercentage", event.bufferedPercentage)
                    addInt("windowIndex", event.windowIndex)
                    addProperty("reason", event.reason ?: "tick")
                }

                is BridgeEvent.PlaybackStateChanged -> {
                    addProperty("isPlaying", event.isPlaying)
                    addProperty("isBuffering", event.isBuffering)
                    addLong("position", event.position)
                    addLong("duration", event.duration)
                    addInt("windowIndex", event.windowIndex)
                    addProperty("reason", event.reason ?: "state")
                }

                is BridgeEvent.SeekCompleted -> {
                    addLong("position", event.toPosition)
                    addLong("fromPosition", event.fromPosition)
                    addInt("windowIndex", event.windowIndex)
                    addProperty("reason", "seek")
                }

                is BridgeEvent.PlaylistItemChanged -> {
                    addLong("position", event.position)
                    addLong("duration", event.duration)
                    addProperty("windowIndex", event.windowIndex)
                    addProperty("playlistSize", event.playlistSize)
                    addProperty("reason", event.reason)
                }

                is BridgeEvent.PlaybackEnded -> {
                    addLong("position", event.position)
                    addLong("duration", event.duration)
                    addProperty("windowIndex", event.windowIndex)
                    addProperty("playlistSize", event.playlistSize)
                    addProperty("finished", true)
                    addProperty("reason", "playback_ended")
                }

                is BridgeEvent.SessionFinished -> {
                    addLong("position", event.position)
                    addLong("duration", event.duration)
                    addInt("windowIndex", event.windowIndex)
                    addInt("playlistSize", event.playlistSize)
                    addProperty("finished", event.endBy == "completion" || event.endBy == "ended" || event.endBy == "playback_ended")
                    addProperty("endBy", event.endBy)
                    addProperty("reason", event.endBy)
                }

                is BridgeEvent.Error -> {
                    addLong("position", event.position)
                    addLong("duration", event.duration)
                    addInt("windowIndex", event.windowIndex)
                    addInt("playlistSize", event.playlistSize)
                    addProperty("error", event.code ?: event.message)
                    addProperty("message", event.message)
                    addProperty("finished", event.fatal)
                    addProperty("reason", "error")
                }

                is BridgeEvent.TrackSelectionChanged -> {
                    if (event.trackType == "audio") {
                        addProperty("selectedAudioTrack", event.label ?: event.trackId)
                        addProperty("selectedAudioTrackId", event.trackId)
                        addProperty("selectedAudioTrackIndex", event.trackIndex)
                        addProperty("selectedAudioTrackLanguage", event.language)
                        addProperty("selectedAudioTrackMimeType", event.sampleMimeType)
                        addInt("selectedAudioTrackChannels", event.channelCount)
                    }

                    if (event.trackType == "subtitle") {
                        addProperty("selectedSubtitleTrack", event.label ?: event.trackId)
                        addProperty("selectedSubtitleTrackId", event.trackId)
                        addProperty("selectedSubtitleTrackIndex", event.trackIndex)
                        addProperty("selectedSubtitleTrackLanguage", event.language)
                        addProperty("selectedSubtitleTrackMimeType", event.sampleMimeType)
                    }

                    addProperty("trackType", event.trackType)
                    addInt("trackIndex", event.trackIndex)
                    addProperty("trackId", event.trackId)
                    addProperty("reason", event.reason)
                }

                is BridgeEvent.UserAction -> {
                    addProperty("reason", event.action)
                    addInt("windowIndex", event.windowIndex)
                }
            }
        }
    }

    private fun JsonObject.addLong(name: String, value: Long?) {
        if (value != null) addProperty(name, value)
    }

    private fun JsonObject.addInt(name: String, value: Int?) {
        if (value != null) addProperty(name, value)
    }

    companion object {
        private const val TAG = "DDDPlayer/DddSync"
    }
}
