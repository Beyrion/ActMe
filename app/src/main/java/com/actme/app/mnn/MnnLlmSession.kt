package com.actme.app.mnn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MnnLlmSession {

    companion object {
        init {
            System.loadLibrary("actme_jni")
        }
    }

    @Volatile
    private var nativePtr: Long = 0

    val isLoaded: Boolean get() = nativePtr != 0L

    external fun nativeInit(modelDir: String, configJson: String): Long
    external fun nativeSubmit(nativePtr: Long, prompt: String): String
    external fun nativeSubmitRaw(nativePtr: Long, prompt: String): String
    external fun nativeReset(nativePtr: Long)
    external fun nativeRelease(nativePtr: Long)
    external fun nativeSetMaxNewTokens(nativePtr: Long, maxTokens: Int)
    external fun nativeDumpConfig(nativePtr: Long): String

    suspend fun init(modelDir: String, configJson: String = "{}") = withContext(Dispatchers.IO) {
        val cfg = if (configJson == "{}") {
            JSONObject().apply {
                put("llm_model", "llm.mnn")
                put("llm_weight", "llm.mnn.weight")
                put("backend_type", "cpu")
                put("thread_num", 4)
            }.toString()
        } else {
            configJson
        }
        nativePtr = nativeInit(modelDir, cfg)
    }

    suspend fun submit(prompt: String): String = withContext(Dispatchers.IO) {
        val result = nativeSubmit(nativePtr, prompt)
        android.util.Log.i("MnnLlmSession", "submit result (${result.length} chars): ${result.take(200)}")
        result
    }

    suspend fun submitRaw(prompt: String): String = withContext(Dispatchers.IO) {
        val result = nativeSubmitRaw(nativePtr, prompt)
        android.util.Log.i("MnnLlmSession", "submitRaw result (${result.length} chars): ${result.take(200)}")
        result
    }

    fun reset() {
        if (nativePtr != 0L) {
            nativeReset(nativePtr)
        }
    }

    fun release() {
        if (nativePtr != 0L) {
            nativeRelease(nativePtr)
            nativePtr = 0L
        }
    }

    fun setMaxNewTokens(maxTokens: Int) {
        if (nativePtr != 0L) {
            nativeSetMaxNewTokens(nativePtr, maxTokens)
        }
    }

    fun dumpConfig(): String {
        return if (nativePtr != 0L) nativeDumpConfig(nativePtr) else "{}"
    }

    protected fun finalize() {
        release()
    }
}
