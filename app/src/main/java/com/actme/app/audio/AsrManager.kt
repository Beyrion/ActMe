package com.actme.app.audio

import android.util.Log
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
                // ASR uses a fully-formed prompt template and must not be wrapped again
                // by the generic chat template/history path.
                json.put("use_template", false)
                json.put("prompt_cache", false)
                json.put("system_prompt", "")
                json.toString()
            } else {
                buildDefaultConfig()
            }

            Log.i(TAG, "Initializing ASR with config: ${mergedConfig.take(500)}")

            session = MnnLlmSession().apply {
                init(modelDir, mergedConfig)
                setMaxNewTokens(256) // ASR only needs short output
            }

            Log.i(TAG, "ASR session initialized. Config: ${session?.dumpConfig()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ASR session", e)
            false
        }
    }

    suspend fun transcribe(audioFile: File, language: String = "Chinese"): String = withContext(Dispatchers.IO) {
        val s = session ?: throw IllegalStateException("ASR not initialized")

        val prompt = buildAsrPrompt(audioFile, language)
        Log.i(TAG, "Transcribing: ${audioFile.absolutePath} (${audioFile.length()} bytes)")
        Log.i(TAG, "Prompt: $prompt")

        try {
            val result = s.submitRaw(prompt)
            Log.i(TAG, "ASR result: $result")
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
            put("use_template", false)
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

    private fun buildAsrPrompt(audioFile: File, language: String): String {
        return "<|im_start|>system<|im_end|>" +
            "<|im_start|>user<audio>${audioFile.absolutePath}</audio><|im_end|>" +
            "<|im_start|>assistantlanguage $language<asr_text>"
    }
}
