package com.actme.app.data.agent

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.provider.Settings
import android.util.Base64
import com.actme.app.mnn.MnnLlmSession
import com.actme.app.mnn.VisionModelManager
import com.actme.app.ui.GuiAgentOverlayService
import com.actme.app.util.AppLogger
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.json.JSONObject

object GuiSubAgent {
    private const val TAG = "GuiSubAgent"
    private const val MAX_STEPS = 12
    private const val HISTORY_LIMIT = 4
    private val ALLOWED_ACTIONS = setOf(
        "open_app",
        "click",
        "tap",
        "long_press",
        "swipe",
        "scroll",
        "type",
        "system_button",
        "wait",
        "answer",
        "terminate"
    )

    suspend fun run(
        instruction: String,
        timeoutMs: Long = 120_000L,
        guidance: String = "",
        plan: String = "",
        targetText: String = ""
    ): String {
        val context = PythonSkillEngine.applicationContext()
            ?: return "[GUI_AGENT_ERROR] App context is not initialized."
        val task = instruction.trim().ifBlank { "inspect current screen" }
        val cloudGuidance = guidance.trim()
        val cloudPlan = plan.trim()
        val fixedTargetText = targetText.trim()
        val maxSteps = (timeoutMs / 10_000L).toInt().coerceIn(1, MAX_STEPS)
        AppLogger.i(TAG, "GUI-AGENT-BEGIN task=$task, plan=${cloudPlan.take(240)}, targetText=${fixedTargetText.take(120)}, guidance=${cloudGuidance.take(240)}, maxSteps=$maxSteps, timeoutMs=$timeoutMs")
        AppLogger.i(TAG, "GUI-AGENT-BEGIN-B64 taskB64=${task.toBase64ForLog()}")
        AppLogger.i(TAG, "GUI-AGENT-PLAN-B64 planB64=${cloudPlan.toBase64ForLog()}")
        AppLogger.i(TAG, "GUI-AGENT-TARGET-B64 targetTextB64=${fixedTargetText.toBase64ForLog()}")
        AppLogger.i(TAG, "GUI-AGENT-GUIDANCE-B64 guidanceB64=${cloudGuidance.toBase64ForLog()}")
        GuiAgentOverlayService.start(context, "ActMe GUI running")

        val adbReady = AdbSkillEngine.shell("echo actme_adb_ready", timeoutMs = 8_000L)
        if (!adbReady.ok || !adbReady.output.contains("actme_adb_ready")) {
            AppLogger.w(TAG, "GUI-AGENT-ADB missing: output=${adbReady.output}, error=${adbReady.error}")
            AdbPairingScreenshotWatcher.start(context)
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { AppLogger.e(TAG, "GUI-AGENT open developer settings failed", it) }
            GuiAgentOverlayService.stop(context)
            return """
                [GUI_AGENT_NEEDS_ADB]
                Wireless ADB is not connected. I started the screenshot pairing watcher and opened Developer options.
                Please open Wireless debugging pairing details, take one screenshot, wait for ActMe to pair/connect, then ask me to run the GUI task again.
            """.trimIndent()
        }

        prelaunchTargetAppIfNeeded(task, cloudPlan, cloudGuidance)

        val modelDir = VisionModelManager.getDefaultModelDir(context)
        val dir = File(modelDir)
        if (!dir.exists() || !File(dir, "config.json").exists()) {
            GuiAgentOverlayService.stop(context)
            return "[GUI_AGENT_ERROR] GUI-Owl model is not ready: $modelDir"
        }

        val session = MnnLlmSession()
        val workDir = File(context.filesDir, "gui_agent/screenshots").apply { mkdirs() }
        val history = mutableListOf<String>()
        val transcript = mutableListOf<String>()
        var consecutiveFailures = 0
        var fixedTargetTextTyped = false
        var finalOverlayText: String? = null

        return try {
            session.init(modelDir, buildMergedConfig(dir))
            session.setMaxNewTokens(384)
            for (step in 0 until maxSteps) {
                val screenshot = File(workDir, "step_${System.currentTimeMillis()}_$step.png")
                val capture = AdbSkillEngine.captureScreenshot(screenshot, timeoutMs = 12_000L)
                if (!capture.ok) {
                    val message = "screenshot failed: ${capture.error.ifBlank { capture.output }}"
                    AppLogger.w(TAG, "GUI-AGENT-SCREEN failed step=$step $message")
                    transcript += "step=$step $message"
                    break
                }
                val size = imageSize(screenshot)
                AppLogger.i(TAG, "GUI-AGENT-SCREEN step=$step path=${screenshot.absolutePath}, size=${size.width}x${size.height}, bytes=${screenshot.length()}")
                AppLogger.i(TAG, "GUI-AGENT-STEP step=$step phase=screenshot size=${size.width}x${size.height} path=${screenshot.absolutePath}")
                GuiAgentOverlayService.start(context, "ActMe GUI step ${step + 1}")

                val prompt = buildPrompt(task, cloudPlan, fixedTargetText, cloudGuidance, screenshot, size, history)
                AppLogger.i(TAG, "GUI-AGENT-MODEL-INPUT step=$step\n$prompt")
                session.reset()
                val output = session.submit(prompt)
                AppLogger.i(TAG, "GUI-AGENT-MODEL-OUTPUT step=$step\n$output")
                transcript += "step=$step model=$output"

                val action = parseAction(output)
                if (action == null) {
                    consecutiveFailures += 1
                    AppLogger.w(TAG, "GUI-AGENT-PARSE failed step=$step failures=$consecutiveFailures")
                    AppLogger.w(TAG, "GUI-AGENT-STEP step=$step phase=parse ok=false failures=$consecutiveFailures")
                    transcript += "step=$step parse_failed failures=$consecutiveFailures"
                    if (consecutiveFailures >= 2) break
                    delay(1_000L)
                    continue
                }
                val actionToExecute = if (action.action == "type") {
                    if (fixedTargetText.isNotBlank() && fixedTargetTextTyped) {
                        AppLogger.w(TAG, "GUI-AGENT-STEP step=$step phase=auto_correct reason=repeated_target_type targetTextB64=${fixedTargetText.toBase64ForLog()} requestedTextB64=${action.text.toBase64ForLog()}")
                        transcript += "step=$step repeated_type_auto_correct action=system_button button=Enter"
                        action.copy(action = "system_button", button = "Enter", text = "")
                    } else {
                        val effectiveText = fixedTargetText.ifBlank { action.text.trim() }
                        if (fixedTargetText.isNotBlank() && action.text.trim() != fixedTargetText) {
                            AppLogger.w(TAG, "GUI-AGENT-TYPE-OVERRIDE step=$step requestedB64=${action.text.toBase64ForLog()} targetB64=${fixedTargetText.toBase64ForLog()}")
                        }
                        action.copy(text = effectiveText)
                    }
                } else {
                    action
                }
                AppLogger.i(TAG, "GUI-AGENT-STEP step=$step phase=decision action=${actionToExecute.action} app=${actionToExecute.app} textB64=${actionToExecute.text.toBase64ForLog()} coord=${actionToExecute.coordinate} coord2=${actionToExecute.coordinate2}")
                val execution = executeAction(context, actionToExecute, size)
                val verifiedExecution = if (
                    execution.ok &&
                    actionToExecute.action == "type" &&
                    fixedTargetText.isNotBlank()
                ) {
                    verifyTypedTargetText(actionToExecute.text)
                } else {
                    execution
                }
                AppLogger.i(TAG, "GUI-AGENT-ACTION step=$step action=$actionToExecute result=${verifiedExecution.ok}, output=${verifiedExecution.output}, error=${verifiedExecution.error}")
                AppLogger.i(TAG, "GUI-AGENT-STEP step=$step phase=execute ok=${verifiedExecution.ok} action=${actionToExecute.action} outputB64=${verifiedExecution.output.toBase64ForLog()} errorB64=${verifiedExecution.error.toBase64ForLog()}")
                transcript += "step=$step action=${actionToExecute.action} ok=${verifiedExecution.ok} error=${verifiedExecution.error.ifBlank { "-" }}"
                history += "Step ${step + 1}: ${actionToExecute.action} ${actionToExecute.text} ok=${verifiedExecution.ok} error=${verifiedExecution.error.ifBlank { "-" }}"
                while (history.size > HISTORY_LIMIT) history.removeAt(0)

                if (actionToExecute.action == "terminate" || actionToExecute.action == "answer") {
                    AppLogger.i(TAG, "GUI-AGENT-END terminate action=${actionToExecute.action}, status=${actionToExecute.status}, text=${actionToExecute.text}")
                    finalOverlayText = buildCompletionOverlayText(actionToExecute, transcript)
                    break
                }
                if (!verifiedExecution.ok) {
                    val errorText = verifiedExecution.error.ifBlank { verifiedExecution.output }.ifBlank { "ADB action failed without output." }
                    AppLogger.w(TAG, "GUI-AGENT-STEP step=$step phase=stop reason=adb_error action=${actionToExecute.action} error=${errorText.take(500)}")
                    transcript += "[GUI_AGENT_ACTION_ERROR] step=$step action=${actionToExecute.action} error=$errorText"
                    finalOverlayText = "GUI action failed\n${errorText.take(320)}"
                    break
                } else {
                    if (actionToExecute.action == "type" && fixedTargetText.isNotBlank()) {
                        fixedTargetTextTyped = true
                    }
                    consecutiveFailures = 0
                }
                delay(action.waitMs ?: 1_200L)
            }
            val result = "[GUI_AGENT_RESULT]\n" + transcript.joinToString("\n")
            finalOverlayText = finalOverlayText ?: summarizeResultForOverlay(result)
            result
        } catch (error: Throwable) {
            AppLogger.e(TAG, "GUI-AGENT-ERROR", error)
            finalOverlayText = "GUI task failed\n${(error.message ?: error::class.java.simpleName).take(320)}"
            "[GUI_AGENT_ERROR]\n${error.stackTraceToString()}"
        } finally {
            session.release()
            val overlayText = finalOverlayText
            if (overlayText.isNullOrBlank()) {
                GuiAgentOverlayService.stop(context)
            } else {
                AppLogger.i(TAG, "GUI-AGENT-OVERLAY-RESULT chars=${overlayText.length}, text=${overlayText.take(500)}")
                GuiAgentOverlayService.showResult(context, overlayText)
            }
            AppLogger.i(TAG, "GUI-AGENT-FINISH")
        }
    }

    private fun buildCompletionOverlayText(action: GuiAction, transcript: List<String>): String {
        val transcriptFallback = transcript
            .asReversed()
            .firstOrNull { it.contains("model=", ignoreCase = true) || it.contains("action=", ignoreCase = true) }
            ?.take(420)
            .orEmpty()
        val body = action.text.ifBlank { action.status }.ifBlank { transcriptFallback }.ifBlank { "GUI task completed." }
        val title = if (action.status.equals("success", ignoreCase = true)) {
            "GUI task completed"
        } else {
            "GUI task result"
        }
        return "$title\n${body.take(420)}"
    }

    private fun summarizeResultForOverlay(result: String): String {
        val lastMeaningful = result
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .lastOrNull { line ->
                line.contains("action=", ignoreCase = true) ||
                    line.contains("parse_failed", ignoreCase = true) ||
                    line.contains("failed", ignoreCase = true) ||
                    line.contains("error=", ignoreCase = true)
            }
            ?: "GUI task finished."
        return "GUI task finished\n${lastMeaningful.take(420)}"
    }

    private suspend fun prelaunchTargetAppIfNeeded(
        task: String,
        plan: String,
        guidance: String
    ) {
        val text = "$task\n$plan\n$guidance"
        val packageName = inferLaunchPackage(text) ?: return
        val focus = AdbSkillEngine.shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp|topResumedActivity'", timeoutMs = 5_000L)
        val alreadyForeground = focus.output.contains(packageName)
        AppLogger.i(TAG, "GUI-AGENT-PRELAUNCH package=$packageName alreadyForeground=$alreadyForeground focus=${focus.output.take(300)}")
        if (alreadyForeground) return

        val launch = AdbSkillEngine.shell(
            "monkey -p $packageName -c android.intent.category.LAUNCHER 1",
            timeoutMs = 12_000L
        )
        AppLogger.i(TAG, "GUI-AGENT-PRELAUNCH result ok=${launch.ok}, output=${launch.output.take(300)}, error=${launch.error.take(300)}")
        if (launch.ok) delay(3_000L)
    }

    private fun inferLaunchPackage(text: String): String? {
        val lower = text.lowercase()
        Regex("""\b[a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z][a-zA-Z0-9_]*){2,}\b""")
            .findAll(text)
            .map { it.value }
            .firstOrNull { candidate ->
                candidate.startsWith("com.") ||
                    candidate.startsWith("cn.") ||
                    candidate.startsWith("tv.") ||
                    candidate.startsWith("me.")
            }
            ?.let { return it }
        return when {
            lower.contains("高德") ||
                lower.contains("amap") ||
                lower.contains("gaode") ||
                lower.contains("autonavi") -> "com.autonavi.minimap"
            lower.contains("百度地图") ||
                lower.contains("baidu map") ||
                lower.contains("baidumap") -> "com.baidu.BaiduMap"
            lower.contains("设置") ||
                lower.contains("settings") ||
                lower.contains("developer options") -> "com.android.settings"
            else -> null
        }
    }

    private fun buildPrompt(
        instruction: String,
        plan: String,
        targetText: String,
        guidance: String,
        image: File,
        size: ImageSize,
        history: List<String>
    ): String {
        val historyText = if (history.isEmpty()) "无" else history.joinToString("\n")
        val planText = plan.ifBlank { "无。请只根据当前目标和截图决定下一步。" }
        val guidanceText = guidance.ifBlank { "无" }
        return """
            你是 ActMe 的手机 GUI 操作子 Agent。
            当前目标：$instruction
            主 Agent 的文字计划：
            $planText
            固定输入文本：${targetText.ifBlank { "无" }}
            云端主 Agent 指导：$guidanceText
            当前截图分辨率：${size.width}x${size.height}。
            坐标规则：默认使用 0-1000 的归一化坐标，相对于整张截图；除非你明确填写 "coordinate_mode":"absolute"，否则不要输出真实像素坐标。
            历史动作：
            $historyText
            你只能返回一个 JSON 对象，不能返回解释、Markdown、代码块或多个 JSON。
            action 字段必须严格等于下面其中一个值：open_app、click、long_press、swipe、type、system_button、wait、answer、terminate。
            不要把 action 候选列表原样复制到 action 字段里。
            你的职责是执行主 Agent 计划中的当前下一步，不要重新制定完整计划。
            每一轮只做一个动作。需要输入文字时，必须先 click 搜索框或输入框；下一轮看到光标、键盘或输入框已聚焦后，再返回 type。
            如果存在固定输入文本，type 动作的 text 必须等于固定输入文本；不要根据截图、联想词或历史结果改写输入文本。
            如果上一步 type 失败，优先重新点击输入框，不要连续乱点。
            如果当前截图显示无法继续、目标应用未打开、输入框不可见、权限弹窗阻挡或上一步失败，请返回 answer，并在 text 中简短说明当前错误。
            示例：
            {"action":"open_app","app":"Amap"}
            {"action":"click","coordinate":[500,300],"coordinate_mode":"normalized_1000"}
            {"action":"type","text":"Fudan University"}
            {"action":"system_button","button":"Back"}
            {"action":"answer","status":"success","text":"done"}
            <img>${image.absolutePath}</img>
        """.trimIndent()
    }

    private suspend fun executeAction(context: Context, action: GuiAction, size: ImageSize): AdbShellResult {
        val command = when (action.action) {
            "open_app" -> {
                val app = action.app.ifBlank { action.text }.trim()
                val packageName = resolveLaunchPackage(app)
                    ?: return AdbShellResult(false, "", "unsupported or not installed app: $app")
                "monkey -p $packageName -c android.intent.category.LAUNCHER 1"
            }
            "click", "tap" -> {
                val p = action.coordinate?.toPixel(size) ?: return AdbShellResult(false, "", "click coordinate missing")
                "input tap ${p.x} ${p.y}"
            }
            "long_press" -> {
                val p = action.coordinate?.toPixel(size) ?: return AdbShellResult(false, "", "long_press coordinate missing")
                val duration = ((action.time ?: 1.0) * 1000.0).roundToInt().coerceIn(300, 3000)
                "input swipe ${p.x} ${p.y} ${p.x} ${p.y} $duration"
            }
            "swipe", "scroll" -> {
                val p1 = action.coordinate?.toPixel(size) ?: return AdbShellResult(false, "", "swipe coordinate missing")
                val p2 = action.coordinate2?.toPixel(size) ?: return AdbShellResult(false, "", "swipe coordinate2 missing")
                "input swipe ${p1.x} ${p1.y} ${p2.x} ${p2.y} 800"
            }
            "type" -> {
                val text = action.text.trim()
                if (text.isBlank()) return AdbShellResult(false, "", "type text missing")
                return typeText(context, text)
            }
            "system_button" -> when (action.button.lowercase()) {
                "back" -> "input keyevent 4"
                "home" -> "input keyevent 3"
                "enter" -> "input keyevent 66"
                else -> {
                    val p = action.coordinate?.toPixel(size)
                        ?: return AdbShellResult(false, "", "unsupported system_button: ${action.button}")
                    "input tap ${p.x} ${p.y}"
                }
            }
            "wait" -> {
                delay(action.waitMs ?: 1_000L)
                return AdbShellResult(true, "waited")
            }
            "answer", "terminate" -> return AdbShellResult(true, action.text.ifBlank { action.status })
            else -> return AdbShellResult(false, "", "unsupported action: ${action.action}")
        }
        return AdbSkillEngine.shell(command, timeoutMs = 15_000L)
    }

    private suspend fun typeText(context: Context, text: String): AdbShellResult {
        AppLogger.i(TAG, "GUI-AGENT-TYPE method=adb_keyboard chars=${text.length}, textB64=${text.toBase64ForLog()}")
        return AdbKeyboardInput.inputText(context, text)
    }

    private suspend fun verifyTypedTargetText(text: String): AdbShellResult {
        delay(700L)
        val dump = AdbSkillEngine.shell(
            "uiautomator dump /sdcard/actme_gui_verify.xml >/dev/null && cat /sdcard/actme_gui_verify.xml",
            timeoutMs = 8_000L
        )
        if (!dump.ok) {
            AppLogger.w(TAG, "GUI-AGENT-TYPE-VERIFY dump failed output=${dump.output.take(240)}, error=${dump.error.take(240)}")
            return AdbShellResult(true, "typed; verify dump unavailable")
        }
        val found = dump.output.contains(text) || dump.output.contains(text.xmlEscaped())
        AppLogger.i(TAG, "GUI-AGENT-TYPE-VERIFY targetB64=${text.toBase64ForLog()} found=$found dumpChars=${dump.output.length}")
        return if (found) {
            AdbShellResult(true, "typed and verified")
        } else {
            AdbShellResult(
                false,
                dump.output,
                "target_text was pasted but not visible in current UI dump; the input field may not have accepted paste or focus changed."
            )
        }
    }

    private fun parseAction(raw: String): GuiAction? {
        for (jsonText in jsonCandidates(raw)) {
            val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: continue
            val args = obj.optJSONObject("arguments") ?: obj
            val action = normalizeAction(args) ?: continue
            return GuiAction(
                action = action,
                coordinate = args.optCoordinate("coordinate", args.optString("coordinate_mode")),
                coordinate2 = args.optCoordinate("coordinate2", args.optString("coordinate_mode")),
                app = args.optString("app"),
                text = args.optString("text"),
                button = args.optString("button"),
                status = args.optString("status"),
                time = if (args.has("time")) args.optDouble("time") else null
            )
        }
        return null
    }

    private fun normalizeAction(args: JSONObject): String? {
        val actionText = args.optString("action").trim().lowercase()
        if (actionText in ALLOWED_ACTIONS) return actionText

        val typeText = args.optString("type").trim().lowercase()
        if (typeText in ALLOWED_ACTIONS) return typeText

        if ('|' in actionText) {
            if (args.has("coordinate")) return "click"
            if (args.has("coordinate2")) return "swipe"
            if (args.optString("text").isNotBlank()) return "type"
            if (args.optString("app").isNotBlank()) return "open_app"
        }
        return null
    }

    private fun jsonCandidates(raw: String): List<String> {
        val text = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val results = linkedSetOf<String>()
        if (text.startsWith("{") && text.endsWith("}")) results += text
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) results += text.substring(start, end + 1)
        results += balancedJsonObjects(text)
        return results.toList()
    }

    private fun balancedJsonObjects(text: String): List<String> {
        val start = text.indexOf('{')
        if (start < 0) return emptyList()
        val results = mutableListOf<String>()
        var depth = 0
        var inString = false
        var escaping = false
        var objectStart = -1
        for (index in start until text.length) {
            val char = text[index]
            if (escaping) {
                escaping = false
                continue
            }
            if (char == '\\' && inString) {
                escaping = true
                continue
            }
            if (char == '"') {
                inString = !inString
                continue
            }
            if (inString) continue
            when (char) {
                '{' -> {
                    if (depth == 0) objectStart = index
                    depth += 1
                }
                '}' -> {
                    depth -= 1
                    if (depth == 0 && objectStart >= 0) {
                        results += text.substring(objectStart, index + 1)
                        objectStart = -1
                    }
                }
            }
        }
        return results
    }

    private fun JSONObject.optCoordinate(key: String, mode: String): GuiCoordinate? {
        val array = optJSONArray(key) ?: return null
        if (array.length() < 2) return null
        return GuiCoordinate(array.optDouble(0), array.optDouble(1), mode)
    }

    private fun GuiCoordinate.toPixel(size: ImageSize): PixelPoint {
        val normalized = mode == "normalized_1000" ||
            mode.isBlank() && x in 0.0..1000.0 && y in 0.0..1000.0
        val px = if (normalized) x / 1000.0 * size.width else x
        val py = if (normalized) y / 1000.0 * size.height else y
        val point = PixelPoint(
            x = px.roundToInt().coerceIn(0, (size.width - 1).coerceAtLeast(0)),
            y = py.roundToInt().coerceIn(0, (size.height - 1).coerceAtLeast(0))
        )
        AppLogger.i(TAG, "GUI-AGENT-COORD raw=($x,$y), mode=${if (normalized) "normalized_1000" else "absolute"}, screen=${size.width}x${size.height}, pixel=${point.x},${point.y}")
        return point
    }

    private fun imageSize(file: File): ImageSize {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return ImageSize(options.outWidth.coerceAtLeast(1), options.outHeight.coerceAtLeast(1))
    }

    private fun buildMergedConfig(modelDir: File): String {
        val configFile = File(modelDir, "config.json")
        val llmConfigFile = File(modelDir, "llm_config.json")
        val merged = JSONObject(configFile.readText())
        if (llmConfigFile.exists()) {
            val llmConfig = JSONObject(llmConfigFile.readText())
            for (key in llmConfig.keys()) merged.put(key, llmConfig.get(key))
        }
        return merged.toString()
    }

    private fun String.xmlEscaped(): String =
        replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private suspend fun resolveLaunchPackage(app: String): String? {
        for (packageName in appPackageCandidates(app)) {
            val resolved = AdbSkillEngine.shell(
                "cmd package resolve-activity --brief $packageName",
                timeoutMs = 5_000L
            )
            if (resolved.ok && !resolved.output.contains("No activity found", ignoreCase = true)) {
                AppLogger.i(TAG, "GUI-AGENT-OPEN-APP app=$app package=$packageName resolved=${resolved.output.trim()}")
                return packageName
            }
        }
        return null
    }

    private fun appPackageCandidates(app: String): List<String> {
        val key = app.trim().lowercase()
            .replace(" ", "_")
            .replace("-", "_")
        if (key.contains('.') && key.length >= 6) return listOf(app.trim())
        return when (key) {
            "高德", "高德地图", "高德地图app" -> listOf("com.autonavi.minimap")
            "百度地图", "百度地图app" -> listOf("com.baidu.BaiduMap", "com.autonavi.minimap")
            "baidu_map", "baidumap", "baidu_maps" -> listOf("com.baidu.BaiduMap", "com.autonavi.minimap")
            "amap", "gaode", "gaode_map", "autonavi" -> listOf("com.autonavi.minimap")
            "map", "maps" -> listOf("com.autonavi.minimap", "com.baidu.BaiduMap", "com.tencent.map")
            "settings", "android_settings", "developer_settings" -> listOf("com.android.settings")
            "browser", "web" -> listOf("com.android.browser", "com.quark.browser", "com.baidu.searchbox")
            else -> emptyList()
        }
    }

    private fun String.toBase64ForLog(): String =
        Base64.encodeToString(toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)

    private data class ImageSize(val width: Int, val height: Int)
    private data class PixelPoint(val x: Int, val y: Int)
    private data class GuiCoordinate(val x: Double, val y: Double, val mode: String)
    private data class GuiAction(
        val action: String,
        val coordinate: GuiCoordinate? = null,
        val coordinate2: GuiCoordinate? = null,
        val app: String = "",
        val text: String = "",
        val button: String = "",
        val status: String = "",
        val time: Double? = null
    ) {
        val waitMs: Long?
            get() = time?.let { (it * 1000.0).roundToInt().coerceIn(250, 10_000).toLong() }
    }
}
