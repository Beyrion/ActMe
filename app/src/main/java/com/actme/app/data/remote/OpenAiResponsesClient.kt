package com.actme.app.data.remote

import com.actme.app.util.AppLogger
import com.actme.app.util.LogCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.URI
import java.net.SocketTimeoutException
import java.net.UnknownHostException

data class ProviderConfig(
    val providerFormat: String, // "openai" or "anthropic"
    val endpoint: String,
    val sk: String,
    val model: String
)

data class MessagePayload(
    val role: String,
    val content: String,
    val imageBase64: String? = null,
    val imageMimeType: String? = null
)

data class TokenUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val totalTokens: Int = inputTokens + outputTokens
) {
    fun plus(other: TokenUsage): TokenUsage {
        return TokenUsage(
            inputTokens = inputTokens + other.inputTokens,
            outputTokens = outputTokens + other.outputTokens,
            totalTokens = totalTokens + other.totalTokens
        )
    }
}

data class LlmResult(
    val text: String,
    val usage: TokenUsage? = null
)

data class LlmStreamChunk(
    val text: String = "",
    val usage: TokenUsage? = null
)

class OpenAiResponsesClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val okHttp = OkHttpClient.Builder().build()

    /**
     * Build a full URL from a user-supplied endpoint and an API path.
     * If the endpoint doesn't contain /v1 or /v2, we insert /v1 automatically.
     * e.g. endpoint="https://api.openai.com", path="chat/completions"
     *      -> "https://api.openai.com/v1/chat/completions"
     */
    private fun apiUrl(endpoint: String, path: String): String {
        val base = endpoint.trimEnd('/')
        val uri = URI.create(base).path.orEmpty()
        val hasVersion = uri.contains("/v1") || uri.contains("/v2")
        return if (hasVersion) {
            "$base/$path"
        } else {
            "$base/v1/$path"
        }
    }

    suspend fun run(
        messages: List<MessagePayload>,
        config: ProviderConfig,
        enableWebSearch: Boolean = false
    ): String {
        return runWithUsage(messages, config, enableWebSearch).text
    }

    suspend fun runWithUsage(
        messages: List<MessagePayload>,
        config: ProviderConfig,
        enableWebSearch: Boolean = false,
        responseFormat: JsonObject? = null
    ): LlmResult {
        if (config.sk.isBlank()) {
            AppLogger.i(TAG, "run aborted: api key is blank")
            return LlmResult("\u5f53\u524d\u672a\u914d\u7f6e API Key\uff0c\u8bf7\u5728\u8bbe\u7f6e\u4e2d\u6dfb\u52a0\u63d0\u4f9b\u5546\u3002")
        }
        val url = when (config.providerFormat) {
            "anthropic" -> apiUrl(config.endpoint, "messages")
            else -> apiUrl(config.endpoint, "chat/completions")
        }
        AppLogger.i(
            TAG,
            "run request: url=$url, model=${config.model}, messages=${messages.size}, hasSchema=${responseFormat != null}"
        )

        val request = when (config.providerFormat) {
            "anthropic" -> buildAnthropicRequest(messages, config, enableWebSearch)
            else -> buildOpenAiRequest(messages, config, enableWebSearch, responseFormat = responseFormat)
        }

        var response = try {
            executeRequestWithTransientRetries(request, "run")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (config.providerFormat != "anthropic" && responseFormat != null) {
                AppLogger.w(TAG, "run request failed before response; retry compat fallback without response_format/stream_options: ${e.message}")
                val fallback = runCatching {
                    executeRequestWithTransientRetries(
                        buildOpenAiRequest(messages, config, enableWebSearch, includeUsage = false, responseFormat = null),
                        "run exception compat fallback"
                    )
                }
                if (fallback.isSuccess) {
                    fallback.getOrThrow()
                } else {
                    AppLogger.e(TAG, "run exception compat fallback error: ${fallback.exceptionOrNull()?.message}")
                    AppLogger.e(TAG, "run error: ${e.message}")
                    return LlmResult(safeNetworkErrorText(e))
                }
            } else {
                AppLogger.e(TAG, "run error: ${e.message}")
                return LlmResult(safeNetworkErrorText(e))
            }
        }
        var body = response.body?.string().orEmpty()
        if (!response.isSuccessful && shouldRetryWithoutOpenAiUsage(config, body)) {
            AppLogger.w(TAG, "run retry without stream_options.include_usage")
            response.close()
            response = try {
                executeRequestWithTransientRetries(
                    buildOpenAiRequest(messages, config, enableWebSearch, includeUsage = false, responseFormat = responseFormat),
                    "run fallback without usage"
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "run fallback without usage error: ${e.message}")
                return LlmResult(safeNetworkErrorText(e))
            }
            body = response.body?.string().orEmpty()
        }
        if (!response.isSuccessful && responseFormat != null && shouldRetryWithoutResponseFormat(body)) {
            AppLogger.w(TAG, "run retry without response_format")
            response.close()
            response = try {
                executeRequestWithTransientRetries(
                    buildOpenAiRequest(messages, config, enableWebSearch, includeUsage = false),
                    "run fallback without response_format"
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "run fallback without response_format error: ${e.message}")
                return LlmResult(safeNetworkErrorText(e))
            }
            body = response.body?.string().orEmpty()
        }
        if (!response.isSuccessful) {
            AppLogger.w(TAG, "run failed: code=${response.code}, bodyLen=${body.length}, bodyB64=${LogCodec.utf8Base64(body.take(4000))}")
            return LlmResult(safeHttpErrorText(response.code))
        }
        AppLogger.i(TAG, "run success")
        return parseSseBodyWithUsage(body, config.providerFormat)
    }

    fun runStreaming(
        messages: List<MessagePayload>,
        config: ProviderConfig,
        enableWebSearch: Boolean = false
    ): Flow<String> = flow {
        runStreamingWithUsage(messages, config, enableWebSearch).collect { chunk ->
            if (chunk.text.isNotEmpty()) emit(chunk.text)
        }
    }.flowOn(Dispatchers.IO)

    fun runStreamingWithUsage(
        messages: List<MessagePayload>,
        config: ProviderConfig,
        enableWebSearch: Boolean = false,
        responseFormat: JsonObject? = null
    ): Flow<LlmStreamChunk> = flow {
        if (config.sk.isBlank()) {
            emit(LlmStreamChunk(text = "\u5f53\u524d\u672a\u914d\u7f6e API Key\uff0c\u8bf7\u5728\u8bbe\u7f6e\u4e2d\u6dfb\u52a0\u63d0\u4f9b\u5546\u3002"))
            return@flow
        }

        val request = when (config.providerFormat) {
            "anthropic" -> buildAnthropicRequest(messages, config, enableWebSearch)
            else -> buildOpenAiRequest(messages, config, enableWebSearch, responseFormat = responseFormat)
        }
        val call = okHttp.newCall(request)
        var emittedAny = false
        try {
            val response = call.execute()
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                val retryWithoutUsage = shouldRetryWithoutOpenAiUsage(config, body)
                val retryWithoutSchema = responseFormat != null && shouldRetryWithoutResponseFormat(body)
                if (retryWithoutUsage || retryWithoutSchema) {
                    AppLogger.w(TAG, "stream retry: withoutUsage=$retryWithoutUsage, withoutSchema=$retryWithoutSchema")
                    response.close()
                    // Strip both stream_options and response_format — safest fallback for compat providers
                    val fallback = okHttp.newCall(buildOpenAiRequest(messages, config, enableWebSearch, includeUsage = false))
                    try {
                        val fallbackResponse = fallback.execute()
                        if (!fallbackResponse.isSuccessful) {
                            val fallbackBody = fallbackResponse.body?.string().orEmpty()
                            AppLogger.w(TAG, "stream fallback failed: code=${fallbackResponse.code}, bodyLen=${fallbackBody.length}, bodyB64=${LogCodec.utf8Base64(fallbackBody.take(4000))}")
                            emit(LlmStreamChunk(text = safeHttpErrorText(fallbackResponse.code)))
                            return@flow
                        }
                        val fallbackSource = fallbackResponse.body?.source() ?: return@flow
                        while (!fallbackSource.exhausted()) {
                            val line = fallbackSource.readUtf8Line() ?: break
                            val chunk = parseSseLine(line, config.providerFormat)
                            if (chunk != null && (chunk.text.isNotEmpty() || chunk.usage != null)) {
                                emittedAny = true
                                emit(chunk)
                            }
                        }
                    } finally {
                        fallback.cancel()
                    }
                    return@flow
                }
                AppLogger.w(TAG, "stream failed: code=${response.code}, bodyLen=${body.length}, bodyB64=${LogCodec.utf8Base64(body.take(4000))}")
                emit(LlmStreamChunk(text = safeHttpErrorText(response.code)))
                return@flow
            }
            val source = response.body?.source() ?: return@flow
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                val chunk = parseSseLine(line, config.providerFormat)
                if (chunk != null && (chunk.text.isNotEmpty() || chunk.usage != null)) {
                    emittedAny = true
                    emit(chunk)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            AppLogger.e(TAG, "runStreaming error: ${e.message}")
            if (!emittedAny && isTransientStreamError(e)) {
                repeat(3) { index ->
                    val attempt = index + 1
                    val backoffMs = 800L * attempt
                    AppLogger.w(TAG, "runStreaming transient error before chunks; retry $attempt/3 after ${backoffMs}ms: ${e.message}")
                    delay(backoffMs)
                    val useCompatFallback = attempt >= 2 && config.providerFormat != "anthropic"
                    val retryRequest = if (useCompatFallback) {
                        AppLogger.w(TAG, "runStreaming retry $attempt/3 using compat fallback without response_format/stream_options")
                        buildOpenAiRequest(messages, config, enableWebSearch, includeUsage = false, responseFormat = null)
                    } else {
                        request
                    }
                    val retryCall = okHttp.newCall(retryRequest)
                    try {
                        val retryResponse = retryCall.execute()
                        if (!retryResponse.isSuccessful) {
                            val retryBody = retryResponse.body?.string().orEmpty()
                            AppLogger.w(TAG, "stream retry $attempt/3 after exception failed: code=${retryResponse.code}, bodyLen=${retryBody.length}, bodyB64=${LogCodec.utf8Base64(retryBody.take(4000))}")
                            if (attempt == 3) {
                                emit(LlmStreamChunk(text = safeHttpErrorText(retryResponse.code)))
                                return@flow
                            }
                            return@repeat
                        }
                        val retrySource = retryResponse.body?.source() ?: return@flow
                        while (!retrySource.exhausted()) {
                            val line = retrySource.readUtf8Line() ?: break
                            val chunk = parseSseLine(line, config.providerFormat)
                            if (chunk != null && (chunk.text.isNotEmpty() || chunk.usage != null)) {
                                emittedAny = true
                                emit(chunk)
                            }
                        }
                        return@flow
                    } catch (retryError: Exception) {
                        if (retryError is CancellationException) throw retryError
                        AppLogger.e(TAG, "runStreaming retry $attempt/3 error: ${retryError.message}")
                    } finally {
                        retryCall.cancel()
                    }
                }
            }
            emit(LlmStreamChunk(text = safeNetworkErrorText(e)))
        } finally {
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    private fun isTransientStreamError(error: Exception): Boolean {
        val message = error.message.orEmpty().lowercase()
        return error is UnknownHostException ||
            error is SocketTimeoutException ||
            message == "timeout" ||
            message.contains("timed out") ||
            message.contains("unable to resolve host") ||
            message.contains("software caused connection abort") ||
            message.contains("connection reset") ||
            message.contains("broken pipe") ||
            message.contains("stream was reset") ||
            message.contains("unexpected end of stream")
    }

    private fun safeNetworkErrorText(error: Exception): String {
        val message = error.message ?: error::class.java.simpleName
        return if (error is UnknownHostException || message.contains("Unable to resolve host", ignoreCase = true)) {
            "\u6a21\u578b\u8bf7\u6c42\u5931\u8d25\uff1a\u7f51\u7edc DNS \u89e3\u6790\u5931\u8d25\uff0c\u65e0\u6cd5\u8fde\u63a5\u6a21\u578b\u670d\u52a1\uff08$message\uff09\u3002\u8bf7\u68c0\u67e5\u7f51\u7edc\u3001DNS/\u4ee3\u7406\uff0c\u6216\u7a0d\u540e\u91cd\u8bd5\u3002"
        } else {
            "\u6a21\u578b\u8bf7\u6c42\u5931\u8d25\uff1a$message"
        }
    }

    private fun safeHttpErrorText(code: Int): String {
        return "\u6a21\u578b\u8bf7\u6c42\u5931\u8d25\uff08HTTP $code\uff09\u3002\u8bf7\u68c0\u67e5\u6a21\u578b\u3001\u63a5\u53e3\u5730\u5740\u3001API Key \u6216\u7a0d\u540e\u91cd\u8bd5\u3002"
    }
    private suspend fun executeRequestWithTransientRetries(
        request: Request,
        label: String,
        retries: Int = 3
    ): Response {
        var lastError: Exception? = null
        for (attemptIndex in 0..retries) {
            val attempt = attemptIndex + 1
            try {
                return okHttp.newCall(request).execute()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastError = e
                if (!isTransientStreamError(e) || attemptIndex == retries) throw e
                val backoffMs = 800L * attempt
                AppLogger.w(TAG, "$label transient error; retry $attempt/$retries after ${backoffMs}ms: ${e.message}")
                delay(backoffMs)
            }
        }
        throw lastError ?: IllegalStateException("$label failed without error")
    }

    suspend fun fetchModels(endpoint: String, sk: String, providerFormat: String = "openai"): List<String> {
        if (sk.isBlank() || endpoint.isBlank()) return emptyList()
        return try {
            val url = apiUrl(endpoint, "models")
            val authHeader = if (providerFormat == "anthropic") "x-api-key" else "Authorization"
            val authValue = if (providerFormat == "anthropic") sk else "Bearer $sk"
            AppLogger.i(TAG, "fetchModels: GET $url")
            val request = Request.Builder()
                .url(url)
                .header(authHeader, authValue)
                .header("Content-Type", "application/json")
                .get()
                .build()
            val response = okHttp.newCall(request).execute()
            if (!response.isSuccessful) {
                AppLogger.i(TAG, "fetchModels failed: code=${response.code}, body=${response.body?.string()}")
                return emptyList()
            }
            val body = response.body?.string().orEmpty()
            val root = json.parseToJsonElement(body).jsonObject
            root["data"]?.jsonArray?.mapNotNull { elem ->
                elem.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            }?.sorted() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---- OpenAI-compatible chat/completions ----

    private fun buildOpenAiRequest(
        messages: List<MessagePayload>,
        config: ProviderConfig,
        enableWebSearch: Boolean,
        includeUsage: Boolean = true,
        responseFormat: JsonObject? = null
    ): Request {
        val payload = buildJsonObject {
            put("model", config.model)
            put("stream", true)
            if (includeUsage) {
                putJsonObject("stream_options") {
                    put("include_usage", true)
                }
            }
            responseFormat?.let { put("response_format", it) }
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

        return Request.Builder()
            .url(apiUrl(config.endpoint, "chat/completions"))
            .header("Authorization", "Bearer ${config.sk}")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    // ---- Anthropic Messages API ----

    private fun buildAnthropicRequest(
        messages: List<MessagePayload>,
        config: ProviderConfig,
        enableWebSearch: Boolean,
        stream: Boolean = true
    ): Request {
        // Separate system message from conversation
        val systemMessages = mutableListOf<String>()
        val conversationMessages = mutableListOf<MessagePayload>()

        for (msg in messages) {
            if (msg.role == "system") {
                systemMessages.add(msg.content)
            } else {
                conversationMessages.add(msg)
            }
        }

        val systemPrompt = systemMessages.joinToString("\n")

        val payload = buildJsonObject {
            put("model", config.model)
            put("max_tokens", 4096)
            put("stream", stream)
            if (systemPrompt.isNotBlank()) {
                put("system", systemPrompt)
            }
            putJsonArray("messages") {
                conversationMessages.forEach { msg ->
                    add(
                        buildJsonObject {
                            put("role", msg.role)
                            put("content", msg.content)
                        }
                    )
                }
            }
        }


        return Request.Builder()
            .url(apiUrl(config.endpoint, "messages"))
            .header("x-api-key", config.sk)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    // ---- SSE Parsing ----

    private fun parseSseBody(raw: String, format: String): String {
        return parseSseBodyWithUsage(raw, format).text
    }

    private fun parseSseBodyWithUsage(raw: String, format: String): LlmResult {
        return when (format) {
            "anthropic" -> parseAnthropicSseBody(raw)
            else -> parseOpenAiSseBody(raw)
        }
    }

    private fun parseSseLine(line: String, format: String): LlmStreamChunk? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) return null
        val data = trimmed.removePrefix("data:").trim()
        if (data.isBlank() || data == "[DONE]") return null
        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
        return when (format) {
            "anthropic" -> {
                val text = if (obj["type"]?.jsonPrimitive?.contentOrNull == "content_block_delta") {
                    obj["delta"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
                } else ""
                val usage = parseAnthropicUsage(obj)
                if (text.isEmpty() && usage == null) null else LlmStreamChunk(text = text, usage = usage)
            }
            else -> {
                val text = obj["choices"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("delta")?.jsonObject
                    ?.get("content")?.jsonPrimitive?.contentOrNull
                    .orEmpty()
                val usage = parseOpenAiUsage(obj["usage"] as? JsonObject)
                if (text.isEmpty() && usage == null) null else LlmStreamChunk(text = text, usage = usage)
            }
        }
    }

    private fun parseOpenAiSseBody(raw: String): LlmResult {
        if (!raw.contains("data:")) return LlmResult(raw)

        val deltaBuilder = StringBuilder()
        var usage: TokenUsage? = null
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) return@forEach
            val data = trimmed.removePrefix("data:").trim()
            if (data.isBlank() || data == "[DONE]") return@forEach

            val eventObj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@forEach
            parseOpenAiUsage(eventObj["usage"] as? JsonObject)?.let { usage = it }
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
        return LlmResult(deltaBuilder.toString().ifBlank { raw }, usage)
    }

    private fun parseAnthropicSseBody(raw: String): LlmResult {
        if (!raw.contains("data:")) return LlmResult(raw)

        val deltaBuilder = StringBuilder()
        var usage = TokenUsage()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) return@forEach
            val data = trimmed.removePrefix("data:").trim()
            if (data.isBlank()) return@forEach

            val eventObj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@forEach
            parseAnthropicUsage(eventObj)?.let { parsed ->
                val input = maxOf(usage.inputTokens, parsed.inputTokens)
                val output = maxOf(usage.outputTokens, parsed.outputTokens)
                usage = TokenUsage(
                    inputTokens = input,
                    outputTokens = output,
                    totalTokens = maxOf(usage.totalTokens, parsed.totalTokens, input + output)
                )
            }
            val type = eventObj["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
            when (type) {
                "content_block_delta" -> {
                    val delta = eventObj["delta"]?.jsonObject
                    val text = delta?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (text.isNotEmpty()) deltaBuilder.append(text)
                }
                "message_stop" -> { /* stream end */ }
            }
        }
        return LlmResult(deltaBuilder.toString().ifBlank { raw }, usage.takeIf { it.totalTokens > 0 })
    }

    private fun parseOpenAiUsage(obj: JsonObject?): TokenUsage? {
        if (obj == null) return null
        val input = obj["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val output = obj["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val total = obj["total_tokens"]?.jsonPrimitive?.intOrNull ?: input + output
        if (input == 0 && output == 0 && total == 0) return null
        return TokenUsage(inputTokens = input, outputTokens = output, totalTokens = total)
    }

    private fun parseAnthropicUsage(obj: JsonObject): TokenUsage? {
        val messageUsage = (obj["message"] as? JsonObject)?.get("usage") as? JsonObject
        val usageObj = (obj["usage"] as? JsonObject) ?: messageUsage ?: return null
        val input = usageObj["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val output = usageObj["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        if (input == 0 && output == 0) return null
        return TokenUsage(inputTokens = input, outputTokens = output, totalTokens = input + output)
    }

    private fun shouldRetryWithoutResponseFormat(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("response_format") ||
            lower.contains("json_schema") ||
            lower.contains("structured")
    }

    private fun shouldRetryWithoutOpenAiUsage(config: ProviderConfig, body: String): Boolean {
        if (config.providerFormat == "anthropic") return false
        val lower = body.lowercase()
        return lower.contains("stream_options") ||
            lower.contains("include_usage") ||
            lower.contains("unknown parameter") ||
            lower.contains("unsupported parameter")
    }

    companion object {
        private const val TAG = "ActMeLlmClient"
    }
}
