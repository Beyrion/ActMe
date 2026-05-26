package com.actme.app.data.remote

import android.util.Log
import com.actme.app.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class MessagePayload(
    val role: String,
    val content: String,
    val imageBase64: String? = null,
    val imageMimeType: String? = null
)

class OpenAiResponsesClient(private val authManager: BundledAuthManager) {
    private val json = Json { ignoreUnknownKeys = true }
    private val okHttp = OkHttpClient.Builder().build()

    private val useChatCompletions = BuildConfig.CRS_WIRE_API == "chat_completions"

    suspend fun run(messages: List<MessagePayload>, enableWebSearch: Boolean = false): String {
        val apiKey = authManager.getApiKey()
        if (apiKey.isBlank()) {
            Log.i(TAG, "openai run aborted: api key is blank")
            return "当前未配置 API Key，请在构建时检查 ~/.codex/auth.json 与 actme.packKey。"
        }
        Log.i(
            TAG,
            "openai run request: messageCount=${messages.size}, model=${BuildConfig.MODEL_NAME}, webSearch=$enableWebSearch, api=${BuildConfig.CRS_WIRE_API}"
        )

        val request = if (useChatCompletions) {
            buildChatRequest(messages, enableWebSearch, apiKey)
        } else {
            buildResponsesRequest(messages, enableWebSearch, apiKey)
        }

        val response = okHttp.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            Log.i(TAG, "openai run failed: code=${response.code}")
            return "请求失败(${response.code})：$body"
        }
        Log.i(TAG, "openai run success")
        return if (useChatCompletions) {
            parseChatSseBody(body)
        } else {
            parseOutputText(parseSseBodyIfNeeded(body))
        }
    }

    private fun buildChatRequest(
        messages: List<MessagePayload>,
        enableWebSearch: Boolean,
        apiKey: String
    ): Request {
        val payload = buildJsonObject {
            put("model", BuildConfig.MODEL_NAME)
            put("stream", true)
            if (!BuildConfig.CUSTOM_AUTH_HEADER.isNullOrBlank()) {
                put(BuildConfig.CUSTOM_AUTH_HEADER, apiKey)
            }
            putJsonArray("messages") {
                messages.forEach { msg ->
                    add(
                        buildJsonObject {
                            put("role", msg.role)
                            if (!msg.imageBase64.isNullOrBlank() && !msg.imageMimeType.isNullOrBlank()) {
                                putJsonArray("content") {
                                    add(
                                        buildJsonObject {
                                            put("type", "text")
                                            put("text", msg.content)
                                        }
                                    )
                                    add(
                                        buildJsonObject {
                                            put("type", "image_url")
                                            putJsonObject("image_url") {
                                                put("url", "data:${msg.imageMimeType};base64,${msg.imageBase64}")
                                            }
                                        }
                                    )
                                }
                            } else {
                                put("content", msg.content)
                            }
                        }
                    )
                }
            }
        }

        val authHeader = if (BuildConfig.CUSTOM_AUTH_HEADER.isNullOrBlank()) {
            "${BuildConfig.CUSTOM_AUTH_PREFIX} $apiKey"
        } else {
            "Bearer dummy"
        }

        return Request.Builder()
            .url("${BuildConfig.CRS_BASE_URL.trimEnd('/')}/chat/completions")
            .header("Authorization", authHeader)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun buildResponsesRequest(
        messages: List<MessagePayload>,
        enableWebSearch: Boolean,
        apiKey: String
    ): Request {
        val payload = buildJsonObject {
            put("model", BuildConfig.MODEL_NAME)
            put("store", !BuildConfig.DISABLE_RESPONSE_STORAGE)
            put("stream", true)
            putJsonObject("reasoning") {
                put("effort", BuildConfig.MODEL_REASONING_EFFORT)
            }
            putJsonArray("input") {
                messages.forEach { msg ->
                    add(
                        buildJsonObject {
                            put("role", msg.role)
                            put("content", msg.content)
                        }
                    )
                }
            }
            if (enableWebSearch) {
                putJsonArray("tools") {
                    add(
                        buildJsonObject {
                            put("type", "web_search_preview")
                        }
                    )
                }
            }
        }

        return Request.Builder()
            .url("${BuildConfig.CRS_BASE_URL.trimEnd('/')}/responses")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun parseChatSseBody(raw: String): String {
        if (!raw.contains("data:")) return raw

        val deltaBuilder = StringBuilder()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) return@forEach
            val data = trimmed.removePrefix("data:").trim()
            if (data.isBlank() || data == "[DONE]") return@forEach

            val eventObj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@forEach
            val choices = eventObj["choices"]?.jsonArray ?: return@forEach
            for (choice in choices) {
                val choiceObj = choice.jsonObject
                val delta = choiceObj["delta"]?.jsonObject ?: continue
                val content = delta["content"]?.jsonPrimitive?.contentOrNull
                if (!content.isNullOrBlank()) {
                    deltaBuilder.append(content)
                }
            }
        }
        return deltaBuilder.toString().ifBlank { raw }
    }

    private fun parseOutputText(raw: String): String {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return raw

        root["output_text"]?.jsonPrimitive?.contentOrNull?.let { output ->
            if (output.isNotBlank()) return output
        }

        val outputArray = root["output"]?.jsonArray ?: JsonArray(emptyList())
        val textParts = mutableListOf<String>()
        for (item in outputArray) {
            val itemObj = item.jsonObject
            val contentArray = itemObj["content"]?.jsonArray ?: continue
            for (content in contentArray) {
                val contentObj = content.jsonObject
                val directText = contentObj["text"]?.jsonPrimitive?.contentOrNull
                if (!directText.isNullOrBlank()) {
                    textParts += directText
                    continue
                }
                val nested = contentObj["output_text"]?.jsonPrimitive?.contentOrNull
                if (!nested.isNullOrBlank()) {
                    textParts += nested
                }
            }
        }
        return textParts.joinToString("\n").ifBlank { raw }
    }

    private fun parseSseBodyIfNeeded(raw: String): String {
        if (!raw.contains("data:")) return raw

        val deltaBuilder = StringBuilder()
        var completedResponseJson: String? = null

        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) return@forEach
            val data = trimmed.removePrefix("data:").trim()
            if (data.isBlank() || data == "[DONE]") return@forEach

            val eventObj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@forEach
            val type = eventObj["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
            when (type) {
                "response.output_text.delta" -> {
                    val delta = eventObj["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (delta.isNotEmpty()) deltaBuilder.append(delta)
                }
                "response.completed" -> {
                    val responseObj = eventObj["response"]?.jsonObject
                    if (responseObj != null) completedResponseJson = responseObj.toString()
                }
            }
        }

        if (deltaBuilder.isNotEmpty()) return deltaBuilder.toString()
        if (!completedResponseJson.isNullOrBlank()) return completedResponseJson!!
        return raw
    }

    companion object {
        private const val TAG = "ActMeOpenAIClient"
    }
}
