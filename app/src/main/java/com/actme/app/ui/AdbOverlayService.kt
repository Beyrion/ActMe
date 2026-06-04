package com.actme.app.ui

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.actme.app.data.agent.AdbSkillEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AdbOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var expanded = false

    override fun onCreate() {
        super.onCreate()
        AdbSkillEngine.initialize(applicationContext)
        windowManager = getSystemService(WindowManager::class.java)
        showOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (overlayView == null) showOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showOverlay() {
        if (overlayView != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "无法显示 ADB 悬浮窗：请开启“显示在其他应用上层”，如有提示也要开启“允许在设置上重叠显示”。", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        val saved = AdbSkillEngine.getSavedConfig()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            background = roundedBackground(Color.WHITE)
            elevation = dp(10).toFloat()
        }

        val title = TextView(this).apply {
            text = "内置 ADB"
            textSize = 16f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.rgb(20, 25, 32))
        }
        val openSettings = Button(this).apply {
            text = "设置"
            makeCompactButton()
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        val toggle = Button(this).apply {
            text = "展开"
            makeCompactButton()
        }
        val close = Button(this).apply {
            text = "关闭"
            makeCompactButton()
            setOnClickListener { stopSelf() }
        }
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(openSettings, compactButtonParams())
            addView(toggle, compactButtonParams())
            addView(close, compactButtonParams())
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(actionRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        root.addView(titleRow)

        val status = TextView(this).apply {
            text = "点击展开后输入配对端口和验证码。如系统提示，请开启“允许在设置上重叠显示”。"
            textSize = 12f
            setTextColor(Color.rgb(35, 41, 50))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = roundedBackground(Color.rgb(244, 246, 250), stroke = false)
        }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val formScroll = ScrollView(this).apply {
            visibility = View.GONE
            isFillViewport = false
            addView(form)
        }

        val hint = TextView(this).apply {
            text = "保持系统无线调试的配对码弹窗不关闭，在这里填配对端口和验证码。如果设置页上看不到此窗口，请开启“允许在设置上重叠显示”。"
            textSize = 12f
            setTextColor(Color.rgb(85, 92, 105))
            setPadding(0, dp(6), 0, dp(4))
        }
        form.addView(hint)

        val host = editText("Host", saved.host)
        val pairPort = editText("配对端口", "")
        val pairCode = editText("验证码", "")
        val connectPort = editText("连接端口", saved.port.toString())
        val shellCommand = editText("adb shell 命令", "echo actme_adb_ready").apply {
            minLines = 2
            setSingleLine(false)
        }
        pairPort.inputType = InputType.TYPE_CLASS_NUMBER
        pairCode.inputType = InputType.TYPE_CLASS_NUMBER
        connectPort.inputType = InputType.TYPE_CLASS_NUMBER

        form.addView(host, fullWidthParams())
        form.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(pairPort, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(5) })
                addView(pairCode, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(5) })
            }
        )

        val pairButton = Button(this).apply { text = "配对" }
        pairButton.setOnClickListener {
            val port = pairPort.text.toString().toIntOrNull()
            if (port == null) {
                showError(status, "配对端口无效。")
                return@setOnClickListener
            }
            setBusy(form, true)
            showInfo(status, "正在配对 ${host.text}:$port ...")
            scope.launch {
                val result = AdbSkillEngine.pair(host.text.toString(), port, pairCode.text.toString())
                if (result.ok) {
                    showInfo(status, result.output)
                } else {
                    showError(status, "配对失败：${result.error.ifBlank { "未知错误" }}")
                }
                setBusy(form, false)
            }
        }
        form.addView(pairButton, fullWidthParams())

        form.addView(connectPort, fullWidthParams())
        val connectButton = Button(this).apply { text = "测试并保存连接" }
        connectButton.setOnClickListener {
            val port = connectPort.text.toString().toIntOrNull()
            if (port == null) {
                showError(status, "连接端口无效。")
                return@setOnClickListener
            }
            setBusy(form, true)
            showInfo(status, "正在连接 ${host.text}:$port ...")
            scope.launch {
                val result = AdbSkillEngine.testConnection(host.text.toString(), port)
                if (result.ok) {
                    showInfo(status, "连接成功，已保存 ${host.text}:$port\n${result.output.trim()}")
                } else {
                    showError(status, "ADB 连接失败：${result.error.ifBlank { result.output }.ifBlank { "未知错误" }}")
                }
                setBusy(form, false)
            }
        }
        form.addView(connectButton, fullWidthParams())

        form.addView(shellCommand, fullWidthParams())
        val shellButton = Button(this).apply { text = "执行 shell 测试" }
        shellButton.setOnClickListener {
            val port = connectPort.text.toString().toIntOrNull()
            setBusy(form, true)
            showInfo(status, "正在执行 shell ...")
            scope.launch {
                val result = AdbSkillEngine.shell(shellCommand.text.toString(), host.text.toString(), port)
                val message = buildString {
                    appendLine(if (result.ok) "执行成功" else "执行失败")
                    result.exitCode?.let { appendLine("exit_code: $it") }
                    if (result.output.isNotBlank()) appendLine(result.output.trimEnd())
                    if (result.error.isNotBlank()) appendLine("stderr: ${result.error.trimEnd()}")
                }.trim()
                if (result.ok) {
                    showInfo(status, message)
                } else {
                    showError(status, "ADB shell 执行失败：${message.ifBlank { "未知错误" }}")
                }
                setBusy(form, false)
            }
        }
        form.addView(shellButton, fullWidthParams())

        root.addView(formScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(210)).apply { topMargin = dp(8) })
        root.addView(status, fullWidthParams())

        val scroll = ScrollView(this).apply { addView(root) }
        attachDrag(titleRow)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            dp(260),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(10)
            y = dp(72)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        layoutParams = params
        overlayView = scroll

        toggle.setOnClickListener {
            expanded = !expanded
            formScroll.visibility = if (expanded) View.VISIBLE else View.GONE
            toggle.text = if (expanded) "收起" else "展开"
            layoutParams?.let {
                it.width = if (expanded) dp(370) else dp(260)
                overlayView?.let { view -> windowManager.updateViewLayout(view, it) }
            }
        }

        runCatching {
            windowManager.addView(scroll, params)
        }.onFailure {
            overlayView = null
            layoutParams = null
            Toast.makeText(this, "无法显示 ADB 悬浮窗：${it.message ?: it::class.java.simpleName}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun editText(label: String, value: String): EditText {
        return EditText(this).apply {
            hint = label
            setText(value)
            textSize = 14f
            setSingleLine(true)
            setSelectAllOnFocus(true)
            setPadding(dp(8), 0, dp(8), 0)
            setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
    }

    private fun roundedBackground(color: Int, stroke: Boolean = true): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(12).toFloat()
            if (stroke) setStroke(dp(1), Color.rgb(205, 211, 220))
        }
    }

    private fun Button.makeCompactButton() {
        minWidth = 0
        minHeight = 0
        minimumWidth = 0
        minimumHeight = 0
        includeFontPadding = false
        setPadding(dp(8), 0, dp(8), 0)
    }

    private fun compactButtonParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30)).apply {
            marginStart = dp(4)
        }
    }

    private fun fullWidthParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        }
    }

    private fun attachDrag(handle: View) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        handle.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX - (event.rawX - touchX)).toInt()
                    params.y = (startY + (event.rawY - touchY)).toInt()
                    overlayView?.let { windowManager.updateViewLayout(it, params) }
                    true
                }
                else -> false
            }
        }
    }

    private fun setBusy(root: LinearLayout, busy: Boolean) {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is Button) child.isEnabled = !busy
            if (child is LinearLayout) setBusy(child, busy)
        }
    }

    private fun showInfo(status: TextView, message: String) {
        status.text = message
        status.setTextColor(Color.rgb(35, 41, 50))
    }

    private fun showError(status: TextView, message: String) {
        status.text = message
        status.setTextColor(Color.rgb(170, 32, 32))
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
