package com.actme.app.audio

import com.actme.app.util.AppLogger
import com.actme.app.mnn.MnnLlmSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class AsrManager(
    private val modelDir: String
) {
    companion object {
        const val TAG = "AsrManager"
        private const val MODEL_NAME = "Qwen3-ASR-0.6B-INT8-MNN"

        fun getDefaultModelPath(context: android.content.Context): String {
            return "${context.filesDir}/models/$MODEL_NAME"
        }
    }

    private var session: MnnLlmSession? = null
    private var currentLanguage: String = "Chinese"

    val isLoaded: Boolean get() = session?.isLoaded == true

    suspend fun init(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Merge config.json + llm_config.json (audio settings are in the latter)
            val mainConfigFile = File(modelDir, "config.json")
            val llmConfigFile = File(modelDir, "llm_config.json")

            val mergedConfig = if (mainConfigFile.exists()) {
                val json = JSONObject(mainConfigFile.readText())
                // Merge llm_config.json for audio model settings
                if (llmConfigFile.exists()) {
                    val llmConfig = JSONObject(llmConfigFile.readText())
                    for (key in llmConfig.keys()) {
                        json.put(key, llmConfig.get(key))
                    }
                }
                applyAsrRuntimeConfig(json, currentLanguage)
                json.put("prompt_cache", false)
                json.put("system_prompt", "")
                json.toString()
            } else {
                buildDefaultConfig()
            }

            AppLogger.i(TAG, "Initializing ASR with config: ${mergedConfig.take(500)}")

            session = MnnLlmSession().apply {
                init(modelDir, mergedConfig)
                setMaxNewTokens(256) // ASR only needs short output
            }

            AppLogger.i(TAG, "ASR session initialized. Config: ${session?.dumpConfig()}")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to initialize ASR session", e)
            false
        }
    }

    suspend fun transcribe(audioFile: File, language: String = "Chinese"): String = withContext(Dispatchers.IO) {
        session ?: throw IllegalStateException("ASR not initialized")
        if (language != currentLanguage) {
            currentLanguage = language
            release()
            if (!init()) {
                throw IllegalStateException("ASR reinit failed")
            }
        }
        val activeSession = session ?: throw IllegalStateException("ASR not initialized")

        val prompt = buildAsrPrompt(audioFile)
        AppLogger.i(TAG, "Transcribing: ${audioFile.absolutePath} (${audioFile.length()} bytes)")
        AppLogger.i(TAG, "Prompt: $prompt")

        try {
            val result = activeSession.submitRaw(prompt)
            AppLogger.i(TAG, "ASR result: $result")
            result.trim()
        } finally {
            // Keep session warm for subsequent transcriptions
        }
    }

    fun release() {
        session?.release()
        session = null
    }

    private fun buildDefaultConfig(): String {
        return JSONObject().apply {
            put("model_type", "qwen3_asr")
            put("llm_model", "llm.mnn")
            put("llm_weight", "llm.mnn.weight")
            put("backend_type", "cpu")
            put("thread_num", 4)
            put("precision", "low")
            put("memory", "low")
            put("sampler_type", "mixed")
            put("temperature", 0.8)
            put("top_k", 40)
            put("top_p", 0.9)
            put("min_p", 0.05)
            put("tfs_z", 1.0)
            put("typical", 0.95)
            put("repetition_penalty", 1.0)
            put("penalty_window", 0)
            put("n_gram", 8)
            put("ngram_factor", 1.0)
            put("tokenizer_file", "tokenizer.txt")
            put("is_audio", true)
            put("audio_type", "qwen3_asr")
            put("audio_start", 151669)
            put("audio_end", 151670)
            put("audio_pad", 151676)
            applyAsrRuntimeConfig(this, currentLanguage)
            put("prompt_cache", false)
            put("system_prompt", "")
            put("mllm", JSONObject().apply {
                put("backend_type", "cpu")
                put("thread_num", 4)
                put("precision", "normal")
                put("memory", "low")
            })
        }.toString()
    }

    private fun applyAsrRuntimeConfig(json: JSONObject, language: String) {
        json.put("asr_language", language)
        if (!json.has("jinja")) {
            json.put("jinja", JSONObject().apply {
                put(
                    "chat_template",
                    "{%- set content = messages[-1].content -%}<|im_start|>system<|im_end|><|im_start|>user{{ content }}<|im_end|>{%- if add_generation_prompt and content is string and '<audio>' in content and '</audio>' in content -%}<|im_start|>assistantlanguage {{ asr_language }}<asr_text>{%- endif -%}"
                )
                put("context", JSONObject().apply {
                    put("asr_language", language)
                })
                put("eos", "<|im_end|>")
            })
        }
    }

    private fun buildAsrPrompt(audioFile: File): String {
        return "<audio>${audioFile.absolutePath}</audio>"
    }
}
