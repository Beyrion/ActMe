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
            session.setMaxNewTokens(512)
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
            请读取这张图片中的文字内容，执行 OCR 与轻量整理。
            仅输出 JSON，不要解释，不要 Markdown。
            不要直接构建日程，不要输出重复规则判断，只提取图片里的文字信息。
            输出格式：
            {
              "source_text":"尽可能完整、按阅读顺序整理后的图片文字。保留日期、时间、地点、课程名、事项名等关键信息。"
            }
            规则：
            - source_text 要尽量保留原始信息，不要过度总结。
            - 无法识别的部分可以省略，但不要编造。
            - 如果图片中存在多行课程表/活动安排，尽量全部保留。

            <img>${imageFile.absolutePath}</img>
        """.trimIndent()
        val raw = session.submit(prompt)
        val jsonText = extractJson(raw)
        return json.decodeFromString<OcrExtractResult>(jsonText)
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
        val jsonText = extractJson(raw)
        return json.decodeFromString<TodoImportPlan>(jsonText)
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

    companion object {
        private const val TAG = "LocalImageImportMgr"

        fun getDefaultModelPath(context: android.content.Context): String {
            return VisionModelManager.getDefaultModelDir(context)
        }
    }
}
