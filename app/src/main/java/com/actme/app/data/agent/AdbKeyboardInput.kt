package com.actme.app.data.agent

import android.content.Context
import android.util.Base64
import com.actme.app.util.AppLogger
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object AdbKeyboardInput {
    private const val TAG = "AdbKeyboardInput"
    private const val PACKAGE_NAME = "com.android.adbkeyboard"
    private const val IME_ID = "com.android.adbkeyboard/.AdbIME"
    private const val ASSET_PATH = "agent/tools/ADBKeyboard.apk"

    suspend fun inputText(context: Context, text: String): AdbShellResult {
        if (text.isBlank()) return AdbShellResult(false, "", "ADB keyboard text is empty.")

        if (text.isSimpleAdbInputText()) {
            AppLogger.i(TAG, "ADB-KEYBOARD-TYPE method=input_text chars=${text.length}, textB64=${text.toBase64ForLog()}")
            val result = AdbSkillEngine.shell("input text ${escapeInputText(text)}", timeoutMs = 15_000L)
            if (result.ok) return result
            AppLogger.w(TAG, "ADB-KEYBOARD-TYPE input_text failed; fallback to ime. output=${result.output.take(240)}, error=${result.error.take(240)}")
        }

        val ready = ensureReady(context)
        if (!ready.ok) return ready

        val originalIme = readCurrentIme().trim()
        AppLogger.i(TAG, "ADB-KEYBOARD-IME original=$originalIme target=$IME_ID")
        val switchResult = AdbSkillEngine.shell("ime set $IME_ID", timeoutMs = 8_000L)
        if (!switchResult.ok) {
            return AdbShellResult(
                false,
                switchResult.output,
                "failed to switch to ADBKeyBoard: ${switchResult.error.ifBlank { switchResult.output }}"
            )
        }

        return try {
            val b64 = Base64.encodeToString(text.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            AppLogger.i(TAG, "ADB-KEYBOARD-BROADCAST action=ADB_INPUT_B64 chars=${text.length}, textB64=${text.toBase64ForLog()}")
            val b64Result = AdbSkillEngine.shell(
                "am broadcast -a ADB_INPUT_B64 --es msg '$b64'",
                timeoutMs = 10_000L
            )
            if (b64Result.ok) {
                delay(600L)
                b64Result
            } else {
                AppLogger.w(TAG, "ADB-KEYBOARD-B64 failed; fallback to ADB_INPUT_TEXT output=${b64Result.output.take(240)}, error=${b64Result.error.take(240)}")
                val textResult = AdbSkillEngine.shell(
                    "am broadcast -a ADB_INPUT_TEXT --es msg ${shellQuote(text)}",
                    timeoutMs = 10_000L
                )
                if (textResult.ok) {
                    delay(600L)
                    textResult
                } else {
                    AdbShellResult(
                        false,
                        "b64=${b64Result.output}\ntext=${textResult.output}",
                        "b64=${b64Result.error}\ntext=${textResult.error}"
                    )
                }
            }
        } finally {
            restoreIme(originalIme)
        }
    }

    private suspend fun ensureReady(context: Context): AdbShellResult {
        val installed = AdbSkillEngine.shell("pm path $PACKAGE_NAME", timeoutMs = 8_000L)
        if (!installed.ok || !installed.output.contains(PACKAGE_NAME)) {
            AppLogger.i(TAG, "ADB-KEYBOARD-INSTALL begin asset=$ASSET_PATH")
            val install = installBundledApk(context)
            if (!install.ok) return install
        } else {
            AppLogger.i(TAG, "ADB-KEYBOARD-INSTALL already_installed output=${installed.output.trim()}")
        }

        val enable = AdbSkillEngine.shell("ime enable $IME_ID", timeoutMs = 8_000L)
        AppLogger.i(TAG, "ADB-KEYBOARD-ENABLE ok=${enable.ok}, output=${enable.output.trim()}, error=${enable.error.trim()}")
        if (!enable.ok) {
            return AdbShellResult(
                false,
                enable.output,
                "failed to enable ADBKeyBoard: ${enable.error.ifBlank { enable.output }}"
            )
        }

        val list = AdbSkillEngine.shell("ime list -a", timeoutMs = 8_000L)
        val available = list.output.contains(IME_ID) || list.output.contains(PACKAGE_NAME)
        AppLogger.i(TAG, "ADB-KEYBOARD-LIST available=$available, chars=${list.output.length}")
        return if (available) {
            AdbShellResult(true, "ADBKeyBoard is installed and enabled.")
        } else {
            AdbShellResult(false, list.output, "ADBKeyBoard IME is not visible after install/enable.")
        }
    }

    private suspend fun installBundledApk(context: Context): AdbShellResult = withContext(Dispatchers.IO) {
        runCatching {
            val apk = File(context.cacheDir, "actme_tools/ADBKeyboard.apk")
            apk.parentFile?.mkdirs()
            context.assets.open(ASSET_PATH).use { input ->
                apk.outputStream().use { output -> input.copyTo(output) }
            }
            if (!apk.isFile || apk.length() <= 0L) {
                AdbShellResult(false, "", "bundled ADBKeyboard.apk is missing or empty.")
            } else {
                val config = AdbSkillEngine.getSavedConfig()
                AppLogger.i(TAG, "ADB-KEYBOARD-INSTALL pushing bytes=${apk.length()} host=${config.host}:${config.port}")
                val install = AdbSkillEngine.installApk(apk, "-r", config.host, config.port, timeoutMs = 30_000L)
                if (install.ok) AppLogger.i(TAG, "ADB-KEYBOARD-INSTALL ok")
                install
            }
        }.getOrElse { error ->
            AppLogger.e(TAG, "ADB-KEYBOARD-INSTALL failed", error)
            AdbShellResult(false, "", error.message ?: error::class.java.simpleName)
        }
    }

    private suspend fun readCurrentIme(): String {
        val result = AdbSkillEngine.shell("settings get secure default_input_method", timeoutMs = 5_000L)
        return if (result.ok) result.output.trim() else ""
    }

    private suspend fun restoreIme(originalIme: String) {
        val clean = originalIme.trim()
        if (clean.isBlank() || clean == "null") {
            val reset = AdbSkillEngine.shell("ime reset", timeoutMs = 8_000L)
            AppLogger.i(TAG, "ADB-KEYBOARD-RESTORE method=reset ok=${reset.ok}, output=${reset.output.trim()}, error=${reset.error.trim()}")
            return
        }
        val restore = AdbSkillEngine.shell("ime set $clean", timeoutMs = 8_000L)
        AppLogger.i(TAG, "ADB-KEYBOARD-RESTORE method=set ime=$clean ok=${restore.ok}, output=${restore.output.trim()}, error=${restore.error.trim()}")
    }

    private fun String.isSimpleAdbInputText(): Boolean =
        isNotBlank() && all { char ->
            char.code in 0x21..0x7e &&
                char !in listOf('"', '\'', '\\', '&', ';', '|', '<', '>', '$', '`', '(', ')')
        }

    private fun escapeInputText(text: String): String =
        text.replace(" ", "%s")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("'", "\\'")
            .take(500)

    private fun shellQuote(text: String): String =
        "'" + text.replace("'", "'\\''") + "'"

    private fun String.toBase64ForLog(): String =
        Base64.encodeToString(toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
}
