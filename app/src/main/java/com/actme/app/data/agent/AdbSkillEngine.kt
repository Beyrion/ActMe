package com.actme.app.data.agent

import android.content.Context
import com.actme.app.util.AppLogger
import com.flyfishxu.kadb.Kadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class AdbConnectionConfig(
    val host: String = "127.0.0.1",
    val port: Int = 5555
)

data class AdbShellResult(
    val ok: Boolean,
    val output: String,
    val error: String = "",
    val exitCode: Int? = null
)

object AdbSkillEngine {
    private const val TAG = "AdbSkillEngine"
    private const val PREFS = "actme_adb"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val DEFAULT_TIMEOUT_MS = 15_000L

    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun getSavedConfig(): AdbConnectionConfig {
        val context = requireContext()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AdbConnectionConfig(
            host = prefs.getString(KEY_HOST, "127.0.0.1") ?: "127.0.0.1",
            port = prefs.getInt(KEY_PORT, 5555)
        )
    }

    fun saveConfig(host: String, port: Int) {
        val cleanHost = normalizeHost(host)
        require(port in 1..65535) { "ADB port must be 1..65535" }
        val context = requireContext()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOST, cleanHost)
            .putInt(KEY_PORT, port)
            .apply()
        AppLogger.i(TAG, "ADB-CONFIG: saved $cleanHost:$port")
    }

    suspend fun pair(host: String, port: Int, pairingCode: String): AdbShellResult =
        withContext(Dispatchers.IO) {
            val cleanHost = normalizeHost(host)
            val cleanCode = pairingCode.trim()
            if (cleanCode.isBlank()) return@withContext AdbShellResult(false, "", "Pairing code is empty.")
            if (port !in 1..65535) return@withContext AdbShellResult(false, "", "Pairing port must be 1..65535.")
            runCatching {
                AppLogger.i(TAG, "ADB-PAIR: $cleanHost:$port")
                withTimeout(DEFAULT_TIMEOUT_MS) {
                    Kadb.pair(cleanHost, port, cleanCode)
                }
                AdbShellResult(true, "Paired with $cleanHost:$port")
            }.getOrElse { error ->
                AppLogger.e(TAG, "ADB-PAIR failed: ${error.message}", error)
                AdbShellResult(false, "", error.message ?: error::class.java.simpleName)
            }
        }

    suspend fun testConnection(host: String, port: Int): AdbShellResult {
        val result = shell("echo actme_adb_ready", host, port, timeoutMs = 8_000L)
        if (result.ok) saveConfig(host, port)
        return result
    }

    suspend fun shell(
        command: String,
        host: String? = null,
        port: Int? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): AdbShellResult = withContext(Dispatchers.IO) {
        val config = getSavedConfig()
        val cleanHost = normalizeHost(host?.takeIf { it.isNotBlank() } ?: config.host)
        val cleanPort = port ?: config.port
        val cleanCommand = normalizeShellCommand(command)
        if (cleanCommand.isBlank()) return@withContext AdbShellResult(false, "", "ADB shell command is empty.")
        if (cleanPort !in 1..65535) return@withContext AdbShellResult(false, "", "ADB port must be 1..65535.")

        runCatching {
            AppLogger.i(TAG, "ADB-SHELL: $cleanHost:$cleanPort command=${cleanCommand.take(120)}")
            val response = withTimeout(timeoutMs.coerceIn(1_000L, 60_000L)) {
                Kadb.create(cleanHost, cleanPort).use { kadb ->
                    kadb.shell(cleanCommand)
                }
            }
            val allOutput = response.allOutput
            AdbShellResult(
                ok = response.exitCode == 0,
                output = allOutput,
                error = response.errorOutput,
                exitCode = response.exitCode
            )
        }.getOrElse { error ->
            AppLogger.e(TAG, "ADB-SHELL failed: ${error.message}", error)
            AdbShellResult(false, "", error.message ?: error::class.java.simpleName)
        }
    }

    private fun normalizeHost(host: String): String {
        val trimmed = host.trim()
        return trimmed.removePrefix("adb://").substringBefore(':').ifBlank { "127.0.0.1" }
    }

    private fun normalizeShellCommand(command: String): String {
        return command.trim()
            .removePrefix("adb shell ")
            .removePrefix("shell ")
            .trim()
    }

    private fun requireContext(): Context {
        return appContext ?: error("AdbSkillEngine is not initialized")
    }
}
