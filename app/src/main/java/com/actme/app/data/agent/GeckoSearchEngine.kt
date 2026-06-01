package com.actme.app.data.agent

import android.content.Context
import com.actme.app.util.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

object GeckoSearchEngine {
    private const val TAG = "GeckoSearchEngine"
    private const val EXTENSION_ID = "gecko-search@actme.local"
    private const val EXTENSION_LOCATION = "resource://android/assets/gecko_search/"
    private const val NATIVE_APP = "actme_gecko_search"

    @Volatile private var appContext: Context? = null
    @Volatile private var runtime: GeckoRuntime? = null
    @Volatile private var extension: WebExtension? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        AppLogger.i(TAG, "GECKO-SEARCH-INIT")
    }

    fun getOrCreateRuntime(context: Context): GeckoRuntime {
        val app = context.applicationContext
        appContext = app
        runtime?.let { return it }
        return synchronized(this) {
            runtime ?: GeckoRuntime.create(app).also { runtime = it }
        }
    }

    suspend fun search(url: String, timeoutMs: Long = 10_000L): String? {
        val context = appContext ?: run {
            AppLogger.w(TAG, "GECKO-SEARCH-SKIP: not initialized")
            return null
        }

        return withContext(Dispatchers.Main.immediate) {
            val session = GeckoSession()
            val deferred = CompletableDeferred<String?>()

            try {
                val rt = getOrCreateRuntime(context)
                val ext = ensureExtension(rt) ?: return@withContext null

                session.open(rt)
                session.webExtensionController.setMessageDelegate(
                    ext,
                    object : WebExtension.MessageDelegate {
                        override fun onMessage(
                            nativeApp: String,
                            message: Any,
                            sender: WebExtension.MessageSender
                        ): GeckoResult<Any>? {
                            AppLogger.i(TAG, "GECKO-SEARCH-MESSAGE: nativeApp=$nativeApp message=$message")
                            if (message is JSONObject) {
                                val pageText = parseRenderedPage(message)
                                if (!deferred.isCompleted && pageText != null) {
                                    deferred.complete(pageText)
                                }
                            }
                            return null
                        }
                    },
                    NATIVE_APP
                )

                AppLogger.i(TAG, "GECKO-SEARCH-LOAD: $url")
                session.loadUri(url)

                withTimeoutOrNull(timeoutMs) { deferred.await() }.also { results ->
                    AppLogger.i(TAG, "GECKO-SEARCH-DONE: chars=${results?.length ?: 0}")
                }
            } catch (e: Throwable) {
                AppLogger.e(TAG, "GECKO-SEARCH-ERROR: ${e.message}")
                null
            } finally {
                runCatching { session.close() }
            }
        }
    }

    private suspend fun ensureExtension(runtime: GeckoRuntime): WebExtension? {
        extension?.let { return it }
        val deferred = CompletableDeferred<WebExtension?>()
        runtime.webExtensionController
            .ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID)
            .accept(
                { ext ->
                    if (ext == null) {
                        AppLogger.e(TAG, "GECKO-SEARCH-EXTENSION-ERROR: null extension")
                        deferred.complete(null)
                    } else {
                        extension = ext
                        AppLogger.i(TAG, "GECKO-SEARCH-EXTENSION-READY: $EXTENSION_ID")
                        deferred.complete(ext)
                    }
                },
                { error ->
                    AppLogger.e(TAG, "GECKO-SEARCH-EXTENSION-ERROR: ${error?.message ?: "unknown"}")
                    deferred.complete(null)
                }
            )
        return deferred.await()
    }

    private fun parseRenderedPage(message: JSONObject): String? {
        if (message.optString("type") != "rendered_page") return null
        val text = message.optString("text").trim()
        AppLogger.i(
            TAG,
            "GECKO-RENDERED-PAGE: reason=${message.optString("reason")} chars=${text.length} title=${message.optString("pageTitle")} url=${message.optString("pageUrl")}"
        )
        if (text.isBlank()) return null
        return buildString {
            appendLine("页面标题：${message.optString("pageTitle")}")
            appendLine("页面文本：")
            append(text)
        }
    }
}
