package com.actme.app.ui

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import com.actme.app.data.agent.GeckoSearchEngine
import com.actme.app.util.AppLogger
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.json.JSONObject
import java.net.URLEncoder

class GeckoDebugActivity : ComponentActivity() {
    private lateinit var runtime: GeckoRuntime
    private lateinit var session: GeckoSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        runtime = GeckoSearchEngine.getOrCreateRuntime(this)
        session = GeckoSession()
        session.open(runtime)
        installSearchExtension()
        session.setProgressDelegate(object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                AppLogger.i(TAG, "GECKO-PAGE-START: $url")
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                AppLogger.i(TAG, "GECKO-PAGE-STOP: success=$success")
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                AppLogger.i(TAG, "GECKO-PROGRESS: $progress")
            }
        })

        val geckoView = GeckoView(this)
        geckoView.setSession(session)

        val input = EditText(this).apply {
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            setText(defaultBingUrl())
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    load(text.toString())
                    true
                } else {
                    false
                }
            }
        }
        val loadButton = Button(this).apply {
            text = "打开"
            setOnClickListener { load(input.text.toString()) }
        }

        val backButton = Button(this).apply {
            text = "返回"
            setOnClickListener { finish() }
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(backButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(loadButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(controls, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(geckoView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        load(input.text.toString())
    }

    override fun onDestroy() {
        session.close()
        super.onDestroy()
    }

    private fun load(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        AppLogger.i(TAG, "GECKO-LOAD: $trimmed")
        session.loadUri(trimmed)
    }

    private fun defaultBingUrl(): String {
        val query = "2026年5月31日国际金价实时价格"
        val q = URLEncoder.encode(query, "UTF-8")
        return "https://www.bing.com/search?q=$q&form=QBRE&pq=$q&qs=n&sp=-1&lq=0"
    }

    private fun installSearchExtension() {
        val messageDelegate = object : WebExtension.MessageDelegate {
            override fun onMessage(
                nativeApp: String,
                message: Any,
                sender: WebExtension.MessageSender
            ): GeckoResult<Any>? {
                AppLogger.i(TAG, "GECKO-EXT-MESSAGE: nativeApp=$nativeApp message=$message")
                if (message is JSONObject) logRenderedPage(message)
                return null
            }
        }

        runtime.webExtensionController
            .ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID)
            .accept(
                { extension ->
                    if (extension == null) {
                        AppLogger.e(TAG, "GECKO-EXTENSION-ERROR: extension is null")
                        return@accept
                    }
                    session.webExtensionController.setMessageDelegate(extension, messageDelegate, NATIVE_APP)
                    AppLogger.i(TAG, "GECKO-EXTENSION-READY: $EXTENSION_ID")
                    session.reload()
                },
                { error ->
                    AppLogger.e(TAG, "GECKO-EXTENSION-ERROR: ${error?.message ?: "unknown"}")
                }
            )
    }

    private fun logRenderedPage(message: JSONObject) {
        val type = message.optString("type")
        if (type != "rendered_page") return
        val text = message.optString("text")
        AppLogger.i(
            TAG,
            "GECKO-RENDERED-PAGE: reason=${message.optString("reason")} chars=${text.length} title=${message.optString("pageTitle")} url=${message.optString("pageUrl")}"
        )
        AppLogger.i(TAG, "GECKO-PAGE-PREVIEW: ${text.take(500)}")
    }

    private companion object {
        const val TAG = "GeckoDebugActivity"
        const val EXTENSION_ID = "gecko-search@actme.local"
        const val EXTENSION_LOCATION = "resource://android/assets/gecko_search/"
        const val NATIVE_APP = "actme_gecko_search"
    }
}
