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
            val configFile = File(modelDir, "config.json")
            val configJson = if (configFile.exists()) {
                configFile.readText()
            } else {
                buildDefaultConfig()
            }

            session = MnnLlmSession().apply {
                init(modelDir, configJson)
                setMaxNewTokens(256) // ASR only needs short output
            }

            Log.i(TAG, "ASR session initialized. Config: ${session?.dumpConfig()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ASR session", e)
            false
        }
    }

    suspend fun transcribe(audioFile: File): String = withContext(Dispatchers.IO) {
        val s = session ?: throw IllegalStateException("ASR not initialized")

        val prompt = "<audio>${audioFile.absolutePath}</audio>"
        Log.d(TAG, "Transcribing: ${audioFile.absolutePath} (${audioFile.length()} bytes)")

        try {
            val result = s.submit(prompt)
            Log.d(TAG, "ASR result: $result")
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
            put("mllm", JSONObject().apply {
                put("backend_type", "cpu")
                put("thread_num", 4)
                put("precision", "normal")
                put("memory", "low")
            })
        }.toString()
    }
}
