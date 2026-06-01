package com.actme.app.image

import com.actme.app.mnn.MnnLlmSession
import com.actme.app.mnn.VisionModelManager
import com.actme.app.util.AppLogger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

@Serializable
data class TodoImportPlan(
    val items: List<TodoImportItem> = emptyList(),
    @SerialName("source_text") val sourceText: String = ""
)

@Serializable
data class OcrExtractResult(
    @SerialName("source_text") val sourceText: String = ""
)

@Serializable
data class TodoImportItem(
    val title: String,
    val detail: String = ""
)

class LocalImageImportManager(
    private val modelDir: String
) {
    private val session = MnnLlmSession()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var initialized = false

    suspend fun init(): Boolean = withContext(Dispatchers.IO) {
        if (initialized) return@withContext true
        try {
            val dir = File(modelDir)
            require(dir.exists()) { "本地视觉模型目录不存在：$modelDir" }
            require(File(dir, "config.json").exists()) { "未找到视觉模型 config.json：$modelDir" }
            session.init(modelDir, buildMergedConfig(dir))
            session.setMaxNewTokens(2048)
            initialized = true
            AppLogger.i(TAG, "local vision model initialized: $modelDir")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "init local vision model failed", e)
            false
        }
    }

    suspend fun extractText(imageFile: File): OcrExtractResult {
        ensureReady()
        session.reset()
        val prompt = """
            请对这张图片执行严格 OCR。
            仅输出 JSON，不要解释，不要 Markdown。
            不要直接构建日程，不要输出重复规则判断，只提取图片里的文字信息。
            输出格式：
            {
              "source_text":"尽可能完整、逐字保留的图片文字。"
            }
            规则：
            - 尽量完整逐字转写，不要总结，不要改写，不要补充解释。
            - 保留日期、时间、地点、课程名、事项名、数字、换行顺序。
            - 如果图片中存在多行课程表、海报、截图、表格文本，尽量全部保留。
            - 无法识别的部分可以省略，但不要编造。
            - 如果有重复内容，按图片中出现的顺序保留。

            <img>${imageFile.absolutePath}</img>
        """.trimIndent()
        val raw = session.submit(prompt)
        return parseOcrResult(raw)
    }

    suspend fun parseTodos(imageFile: File): TodoImportPlan {
        ensureReady()
        session.reset()
        val prompt = """
            请读取这张图片中的文字和布局信息，把其中适合作为“待办/行动项”的内容整理出来。
            仅输出 JSON，不要解释，不要 Markdown。
            如果图片中没有明显待办，也请提取最接近行动项的内容；不要编造不存在的信息。
            输出格式：
            {
              "items":[
                {"title":"待办1","detail":"补充说明"},
                {"title":"待办2","detail":"补充说明"}
              ],
              "source_text":"图片中的关键原文摘要"
            }
            规则：
            - title 必须简洁，适合作为待办标题。
            - detail 可为空。
            - items 最多返回 8 条。

            <img>${imageFile.absolutePath}</img>
        """.trimIndent()
        val raw = session.submit(prompt)
        return parseTodoImportPlan(raw)
    }

    fun release() {
        session.release()
        initialized = false
    }

    private suspend fun ensureReady() {
        require(init()) { "本地视觉模型加载失败，请检查模型目录和配置" }
    }

    private fun buildMergedConfig(modelDir: File): String {
        val configFile = File(modelDir, "config.json")
        val llmConfigFile = File(modelDir, "llm_config.json")
        val merged = JSONObject(configFile.readText())
        if (llmConfigFile.exists()) {
            val llmConfig = JSONObject(llmConfigFile.readText())
            for (key in llmConfig.keys()) {
                merged.put(key, llmConfig.get(key))
            }
        }
        return merged.toString()
    }

    private fun extractJson(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start >= 0 && end > start) { "本地模型未返回有效 JSON：${raw.take(200)}" }
        return raw.substring(start, end + 1)
    }

    private fun parseOcrResult(raw: String): OcrExtractResult {
        for (candidate in jsonCandidates(raw)) {
            runCatching { json.decodeFromString<OcrExtractResult>(candidate) }
                .getOrNull()
                ?.let { return it }

            val sourceText = runCatching { JSONObject(candidate).optString("source_text") }
                .getOrDefault("")
                .trim()
            if (sourceText.isNotBlank()) return OcrExtractResult(sourceText = sourceText)
        }

        val sourceText = extractLooseStringField(raw, "source_text").ifBlank { cleanModelText(raw) }
        AppLogger.w(TAG, "OCR JSON parse failed; using loose text fallback, raw=${raw.take(300)}")
        return OcrExtractResult(sourceText = sourceText)
    }

    private fun parseTodoImportPlan(raw: String): TodoImportPlan {
        for (candidate in jsonCandidates(raw)) {
            runCatching { json.decodeFromString<TodoImportPlan>(candidate) }
                .getOrNull()
                ?.let { return it }

            runCatching {
                val obj = JSONObject(candidate)
                val items = obj.optJSONArray("items").toTodoImportItems()
                val sourceText = obj.optString("source_text").trim()
                TodoImportPlan(items = items, sourceText = sourceText)
            }.getOrNull()?.let { plan ->
                if (plan.items.isNotEmpty() || plan.sourceText.isNotBlank()) return plan
            }
        }

        val sourceText = extractLooseStringField(raw, "source_text").ifBlank { cleanModelText(raw) }
        AppLogger.w(TAG, "todo import JSON parse failed; using loose fallback, raw=${raw.take(300)}")
        return TodoImportPlan(sourceText = sourceText)
    }

    private fun JSONArray?.toTodoImportItems(): List<TodoImportItem> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                val title = item.optString("title").trim()
                if (title.isBlank()) continue
                add(TodoImportItem(title = title, detail = item.optString("detail").trim()))
            }
        }.take(8)
    }

    private fun jsonCandidates(raw: String): List<String> {
        val cleaned = stripCodeFence(raw.trim())
        val candidates = linkedSetOf<String>()
        if (cleaned.startsWith("{") && cleaned.endsWith("}")) candidates.add(cleaned)
        runCatching { extractJson(cleaned) }.getOrNull()?.let { candidates.add(it) }
        candidates.addAll(extractBalancedJsonObjects(cleaned))
        return candidates.toList()
    }

    private fun stripCodeFence(text: String): String {
        return text
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    private fun extractBalancedJsonObjects(text: String): List<String> {
        val results = mutableListOf<String>()
        var start = -1
        var depth = 0
        var inString = false
        var escaped = false
        for (index in text.indices) {
            val ch = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = index
                    depth += 1
                }
                '}' -> {
                    if (depth > 0) {
                        depth -= 1
                        if (depth == 0 && start >= 0) {
                            results.add(text.substring(start, index + 1))
                            start = -1
                        }
                    }
                }
            }
        }
        return results
    }

    private fun extractLooseStringField(raw: String, field: String): String {
        val pattern = Regex(
            """"${Regex.escape(field)}"\s*:\s*"([\s\S]*?)(?:"\s*[,}]|$)""",
            setOf(RegexOption.IGNORE_CASE)
        )
        val match = pattern.find(raw) ?: return ""
        return match.groupValues.getOrNull(1)
            ?.replace("\\n", "\n")
            ?.replace("\\\"", "\"")
            ?.trim()
            .orEmpty()
    }

    private fun cleanModelText(raw: String): String {
        return stripCodeFence(raw)
            .replace(Regex("""^\s*json\s*""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    companion object {
        private const val TAG = "LocalImageImportMgr"

        fun getDefaultModelPath(context: android.content.Context): String {
            return VisionModelManager.getDefaultModelDir(context)
        }
    }
}
