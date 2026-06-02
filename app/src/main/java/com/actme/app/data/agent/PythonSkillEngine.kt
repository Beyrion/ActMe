package com.actme.app.data.agent

import android.content.Context
import com.actme.app.util.AppLogger
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

object PythonSkillEngine {
    private const val TAG = "PythonSkillEngine"
    private const val MAX_CODE_CHARS = 12_000
    private const val MAX_INPUT_CHARS = 24_000
    private const val MAX_OUTPUT_CHARS = 24_000

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var initialized = false

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun workspaceDir(context: Context? = appContext): File {
        val base = requireNotNull(context?.applicationContext ?: appContext) {
            "Python workspace requires initialized context."
        }
        return File(base.filesDir, "agent_workspace").apply { mkdirs() }
    }

    private fun ensureStarted(): Boolean {
        if (initialized) return true
        synchronized(this) {
            if (initialized) return true
            val context = appContext ?: return false
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context))
            }
            initialized = true
            AppLogger.i(TAG, "python runtime initialized")
        }
        return true
    }

    suspend fun execute(code: String, input: String = "", timeoutMs: Long = 3_000L): PythonExecutionResult {
        val trimmedCode = code.take(MAX_CODE_CHARS)
        val trimmedInput = input.take(MAX_INPUT_CHARS)
        val boundedTimeout = timeoutMs.coerceIn(1_000L, 10_000L)
        return withContext(Dispatchers.Default) {
            if (!initialized) {
                if (!ensureStarted()) {
                    return@withContext PythonExecutionResult(
                        ok = false,
                        error = "Python runtime is not initialized."
                    )
                }
            }
            runCatching {
                val module = Python.getInstance().getModule("agent_python")
                val raw = module.callAttr(
                    "run_code",
                    trimmedCode,
                    trimmedInput,
                    boundedTimeout,
                    workspaceDir().absolutePath
                ).toString()
                json.decodeFromString<PythonExecutionResult>(raw)
            }.getOrElse { e ->
                AppLogger.e(TAG, "python_exec failed: ${e.message}")
                PythonExecutionResult(ok = false, error = e.message ?: e::class.java.simpleName)
            }.trimmed()
        }
    }

    private fun PythonExecutionResult.trimmed(): PythonExecutionResult {
        return copy(
            stdout = stdout.take(MAX_OUTPUT_CHARS),
            stderr = stderr.take(MAX_OUTPUT_CHARS),
            error = error.take(MAX_OUTPUT_CHARS),
            result = result
        )
    }
}

@Serializable
data class PythonExecutionResult(
    val ok: Boolean = false,
    val stdout: String = "",
    val stderr: String = "",
    val error: String = "",
    val result: JsonElement? = null,
    val elapsed_ms: Int = 0
)
