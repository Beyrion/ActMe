package com.actme.app.data.agent

import android.content.Context
import com.actme.app.util.AppLogger
import com.flyfishxu.kadb.Kadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

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
    private val connectionMutex = Mutex()
    private var cachedConnection: CachedAdbConnection? = null

    private data class CachedAdbConnection(
        val host: String,
        val port: Int,
        val kadb: Kadb
    )

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
        if (cachedConnection?.let { it.host != cleanHost || it.port != port } == true) {
            closeCachedConnection("config_changed")
        }
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
            val response = withCachedConnection(cleanHost, cleanPort, timeoutMs.coerceIn(1_000L, 60_000L)) { kadb ->
                kadb.shell(cleanCommand)
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

    suspend fun captureScreenshot(
        targetFile: File,
        host: String? = null,
        port: Int? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): AdbShellResult = withContext(Dispatchers.IO) {
        val config = getSavedConfig()
        val cleanHost = normalizeHost(host?.takeIf { it.isNotBlank() } ?: config.host)
        val cleanPort = port ?: config.port
        if (cleanPort !in 1..65535) return@withContext AdbShellResult(false, "", "ADB port must be 1..65535.")
        runCatching {
            targetFile.parentFile?.mkdirs()
            val remotePath = "/data/local/tmp/actme_gui_${System.currentTimeMillis()}.png"
            AppLogger.i(TAG, "ADB-SCREENSHOT: begin $cleanHost:$cleanPort remote=$remotePath local=${targetFile.absolutePath}")
            withCachedConnection(cleanHost, cleanPort, timeoutMs.coerceIn(2_000L, 60_000L)) { kadb ->
                val capture = kadb.shell("screencap -p $remotePath")
                if (capture.exitCode != 0) {
                    AdbShellResult(false, capture.allOutput, capture.errorOutput.ifBlank { "screencap failed" }, capture.exitCode)
                } else {
                    kadb.pull(targetFile, remotePath)
                    runCatching { kadb.shell("rm -f $remotePath") }
                    if (!targetFile.isFile || targetFile.length() <= 0L) {
                        AdbShellResult(false, "", "pulled screenshot is empty: ${targetFile.absolutePath}")
                    } else {
                        AppLogger.i(TAG, "ADB-SCREENSHOT: ok bytes=${targetFile.length()}")
                        AdbShellResult(true, targetFile.absolutePath, exitCode = 0)
                    }
                }
            }
        }.getOrElse { error ->
            AppLogger.e(TAG, "ADB-SCREENSHOT failed: ${error.message}", error)
            AdbShellResult(false, "", error.message ?: error::class.java.simpleName)
        }
    }

    suspend fun installApk(
        apk: File,
        options: String = "-r",
        host: String? = null,
        port: Int? = null,
        timeoutMs: Long = 30_000L
    ): AdbShellResult = withContext(Dispatchers.IO) {
        val config = getSavedConfig()
        val cleanHost = normalizeHost(host?.takeIf { it.isNotBlank() } ?: config.host)
        val cleanPort = port ?: config.port
        if (cleanPort !in 1..65535) return@withContext AdbShellResult(false, "", "ADB port must be 1..65535.")
        if (!apk.isFile || apk.length() <= 0L) return@withContext AdbShellResult(false, "", "APK is missing or empty: ${apk.absolutePath}")
        runCatching {
            AppLogger.i(TAG, "ADB-INSTALL: begin $cleanHost:$cleanPort bytes=${apk.length()} options=$options")
            withCachedConnection(cleanHost, cleanPort, timeoutMs.coerceIn(5_000L, 120_000L)) { kadb ->
                kadb.install(apk, *options.split(" ").filter { it.isNotBlank() }.toTypedArray())
            }
            AppLogger.i(TAG, "ADB-INSTALL: ok ${apk.name}")
            AdbShellResult(true, "Installed ${apk.name}")
        }.getOrElse { error ->
            AppLogger.e(TAG, "ADB-INSTALL failed: ${error.message}", error)
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

    private suspend fun <T> withCachedConnection(
        host: String,
        port: Int,
        timeoutMs: Long,
        block: (Kadb) -> T
    ): T {
        return connectionMutex.withLock {
            val existing = cachedConnection
            val kadb = if (existing != null && existing.host == host && existing.port == port) {
                AppLogger.i(TAG, "ADB-CONNECTION: reuse $host:$port connected=${existing.kadb.connectionCheck()}")
                existing.kadb
            } else {
                existing?.kadb?.close()
                AppLogger.i(TAG, "ADB-CONNECTION: create $host:$port")
                Kadb.create(host, port, connectTimeout = 5_000, socketTimeout = timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    .also { cachedConnection = CachedAdbConnection(host, port, it) }
            }
            try {
                withTimeout(timeoutMs) {
                    block(kadb)
                }
            } catch (error: Throwable) {
                AppLogger.w(TAG, "ADB-CONNECTION: drop $host:$port reason=${error.message ?: error::class.java.simpleName}")
                if (cachedConnection?.kadb === kadb) {
                    runCatching { kadb.close() }
                    cachedConnection = null
                }
                throw error
            }
        }
    }

    private fun closeCachedConnection(reason: String) {
        val old = cachedConnection ?: return
        AppLogger.i(TAG, "ADB-CONNECTION: close ${old.host}:${old.port} reason=$reason")
        runCatching { old.kadb.close() }
        cachedConnection = null
    }

    private fun requireContext(): Context {
        return appContext ?: error("AdbSkillEngine is not initialized")
    }
}
