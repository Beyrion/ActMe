package com.actme.app.ui

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.actme.app.audio.AsrManager
import com.actme.app.audio.AudioRecorderManager
import com.actme.app.util.AppLogger
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GuiAgentOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var audioRecorder: AudioRecorderManager? = null
    private var asrManager: AsrManager? = null
    private var isVoiceRecording = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        showRunningOverlay("ActMe GUI")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: "ActMe GUI"
        val isResult = intent?.getBooleanExtra(EXTRA_RESULT, false) == true
        removeOverlay()
        if (isResult) {
            showResultOverlay(text)
        } else {
            showRunningOverlay(text)
        }
        return if (isResult) START_NOT_STICKY else START_STICKY
    }

    override fun onDestroy() {
        audioRecorder?.cancelRecording()
        audioRecorder = null
        asrManager?.release()
        asrManager = null
        serviceScope.cancel()
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showRunningOverlay(text: String) {
        if (!ensureOverlayPermission()) return
        val view = TextView(this).apply {
            this.text = text.trim().ifBlank { "ActMe GUI" }.take(48)
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = bubbleBackground(Color.rgb(22, 26, 34), dp(16))
            setOnClickListener { stopSelf() }
        }
        addOverlay(view, focusable = false, width = WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun showResultOverlay(result: String) {
        if (!ensureOverlayPermission()) return
        val displayResult = result.trim().ifBlank { "GUI task completed." }.take(1200)
        AppLogger.i(TAG, "GUI-OVERLAY result panel chars=${displayResult.length}")
        val input = EditText(this).apply {
            hint = "New GUI task"
            setSingleLine(true)
            textSize = 13f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(168, 176, 190))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = bubbleBackground(Color.rgb(32, 38, 50), dp(10))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = bubbleBackground(Color.rgb(18, 22, 30), dp(14))
            addView(resultBubble(displayResult), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(inputToolRow(input), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))
            addView(actionButtonRow(input), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        addOverlay(root, focusable = true, width = dp(336))
        input.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun resultBubble(result: String): View {
        val title = TextView(this).apply {
            text = "\u6a21\u578b\u7ed3\u679c"
            textSize = 11f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.rgb(174, 210, 255))
            setPadding(0, 0, 0, dp(4))
        }
        val body = TextView(this).apply {
            text = result
            textSize = 13f
            maxLines = 9
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.WHITE)
            setLineSpacing(0f, 1.08f)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = bubbleBackground(Color.rgb(30, 38, 54), dp(12))
            addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun inputToolRow(input: EditText): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 0, 0, dp(6))
            val voiceButton = makeButton("\uD83C\uDFA4") { }
            voiceButton.setOnClickListener { toggleLocalVoiceInput(input, voiceButton) }
            addView(voiceButton, LinearLayout.LayoutParams(dp(42), dp(34)))
        }
    }

    private fun actionButtonRow(input: EditText): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
            addView(makeButton("\u5173\u95ed") { stopSelf() })
            addView(makeButton("\u8fd4\u56deActMe") {
                openActMe()
                stopSelf()
            })
            addView(makeButton("\u8f93\u5165") { submitNormalChatInput(input.text?.toString().orEmpty()) })
        }
    }

    private fun makeButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 11f
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dp(8), 0, dp(8), 0)
            setTextColor(Color.WHITE)
            background = bubbleBackground(Color.rgb(46, 55, 72), dp(9))
            setOnClickListener { onClick() }
        }
    }

    private fun submitNormalChatInput(raw: String) {
        val text = raw.trim()
        if (text.isBlank()) {
            Toast.makeText(this, "Input is empty.", Toast.LENGTH_SHORT).show()
            return
        }
        AppLogger.i(TAG, "GUI-OVERLAY submit GUI continuation via main agent chars=${text.length}")
        openActMe(buildMainAgentGuiContinuation(text))
        stopSelf()
    }

    private fun buildMainAgentGuiContinuation(text: String): String {
        return """
            [GUI_CONTEXT_CONTINUATION]
            User instruction from the GUI result overlay:
            $text

            Treat this as a normal ActMe conversation message. If it asks to continue operating another app or the phone UI, use the full main-agent planning workflow first, then call gui_agent with a concise plan/guidance. Do not bypass the main agent by directly executing local GUI actions.
        """.trimIndent()
    }

    private fun openActMe(guiTaskPrompt: String? = null) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (!guiTaskPrompt.isNullOrBlank()) {
                action = ACTION_GUI_TASK
                putExtra(EXTRA_GUI_TASK, guiTaskPrompt)
            }
        }
        runCatching { startActivity(intent) }
            .onFailure { AppLogger.e(TAG, "GUI-OVERLAY open ActMe failed", it) }
    }

    private fun toggleLocalVoiceInput(input: EditText, voiceButton: Button) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Record audio permission is required. Please grant it in ActMe.", Toast.LENGTH_SHORT).show()
            openActMe()
            return
        }
        if (isVoiceRecording) {
            stopLocalVoiceInput(input, voiceButton)
            return
        }
        val modelPath = AsrManager.getDefaultModelPath(this)
        if (!File(modelPath, "config.json").exists()) {
            Toast.makeText(this, "Local ASR model is not ready.", Toast.LENGTH_SHORT).show()
            AppLogger.w(TAG, "GUI-OVERLAY local ASR unavailable: $modelPath")
            openActMe()
            return
        }
        val recorder = audioRecorder ?: AudioRecorderManager(this).also { audioRecorder = it }
        recorder.onRecordingStarted = {
            isVoiceRecording = true
            voiceButton.text = "\u25A0"
            AppLogger.i(TAG, "GUI-OVERLAY local ASR recording started")
        }
        recorder.onError = { error ->
            isVoiceRecording = false
            voiceButton.text = "\uD83C\uDFA4"
            AppLogger.w(TAG, "GUI-OVERLAY local ASR recording error=$error")
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
        }
        recorder.startRecording(cacheDir)
    }

    private fun stopLocalVoiceInput(input: EditText, voiceButton: Button) {
        val wavFile = audioRecorder?.stopRecording()
        isVoiceRecording = false
        voiceButton.text = "ASR..."
        if (wavFile == null) {
            voiceButton.text = "\uD83C\uDFA4"
            AppLogger.w(TAG, "GUI-OVERLAY local ASR stop produced no file")
            return
        }
        serviceScope.launch {
            try {
                val manager = asrManager ?: AsrManager(AsrManager.getDefaultModelPath(this@GuiAgentOverlayService)).also {
                    asrManager = it
                }
                if (!manager.isLoaded) {
                    val ok = manager.init()
                    if (!ok) error("ASR model load failed")
                }
                val language = getSharedPreferences("actme_voice_settings", Context.MODE_PRIVATE)
                    .getString("asr_language", "Chinese")
                    ?: "Chinese"
                val text = withContext(Dispatchers.IO) { manager.transcribe(wavFile, language) }.trim()
                AppLogger.i(TAG, "GUI-OVERLAY local ASR text chars=${text.length}")
                if (text.isNotBlank()) {
                    val merged = input.text?.toString().orEmpty() + text
                    input.setText(merged)
                    input.setSelection(merged.length)
                } else {
                    Toast.makeText(this@GuiAgentOverlayService, "No speech recognized.", Toast.LENGTH_SHORT).show()
                }
            } catch (error: Throwable) {
                AppLogger.e(TAG, "GUI-OVERLAY local ASR failed", error)
                Toast.makeText(this@GuiAgentOverlayService, "Local ASR failed: ${error.message}", Toast.LENGTH_SHORT).show()
            } finally {
                wavFile.delete()
                voiceButton.text = "\uD83C\uDFA4"
            }
        }
    }

    private fun addOverlay(view: View, focusable: Boolean, width: Int) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = if (focusable) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = dp(72)
        }

        runCatching {
            windowManager.addView(view, params)
            overlayView = view
            AppLogger.i(TAG, "GUI-OVERLAY shown focusable=$focusable")
        }.onFailure { error ->
            AppLogger.e(TAG, "GUI-OVERLAY failed", error)
            overlayView = null
            stopSelf()
        }
    }

    private fun ensureOverlayPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            AppLogger.w(TAG, "GUI-OVERLAY skipped: overlay permission missing")
            Toast.makeText(this, "ActMe GUI overlay permission is not enabled.", Toast.LENGTH_SHORT).show()
            stopSelf()
            return false
        }
        return true
    }

    private fun removeOverlay() {
        overlayView?.let { view -> runCatching { windowManager.removeView(view) } }
        overlayView = null
    }

    private fun bubbleBackground(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            setStroke(dp(1), Color.rgb(84, 96, 120))
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val TAG = "GuiAgentOverlay"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_RESULT = "result"
        const val ACTION_GUI_TASK = "com.actme.app.action.GUI_TASK"
        const val EXTRA_GUI_TASK = "extra_gui_task"

        fun start(context: Context, text: String = "ActMe GUI") {
            val appContext = context.applicationContext
            val intent = Intent(appContext, GuiAgentOverlayService::class.java)
                .putExtra(EXTRA_TEXT, text)
            runCatching { appContext.startService(intent) }
                .onFailure { AppLogger.e(TAG, "GUI-OVERLAY start failed", it) }
        }

        fun showResult(context: Context, text: String) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, GuiAgentOverlayService::class.java)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_RESULT, true)
            runCatching { appContext.startService(intent) }
                .onFailure { AppLogger.e(TAG, "GUI-OVERLAY result start failed", it) }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            runCatching { appContext.stopService(Intent(appContext, GuiAgentOverlayService::class.java)) }
                .onFailure { AppLogger.e(TAG, "GUI-OVERLAY stop failed", it) }
        }
    }
}
