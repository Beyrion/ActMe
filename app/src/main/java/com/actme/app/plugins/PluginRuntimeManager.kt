package com.actme.app.plugins

import android.annotation.SuppressLint
import android.content.Context
import com.actme.app.util.AppLogger
import android.webkit.WebView
import android.webkit.WebViewClient
import com.actme.app.data.local.PluginDao
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class PluginRuntimeManager(
    private val context: Context,
    private val pluginDao: PluginDao,
    private val pluginAlarmManager: PluginAlarmManager,
    private val pluginRegistry: PluginRegistry
) {
    private data class Entry(
        val webView: WebView,
        /** Completed by onPageFinished — must await before evaluating JS. */
        val pageReady: CompletableDeferred<Unit>,
        var refCount: Int = 0,
        var managementOpen: Boolean = false,
        var idleJob: Job? = null
    )

    private val entries = mutableMapOf<String, Entry>()
    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val pendingCalls = ConcurrentHashMap<String, CompletableDeferred<String>>()

    /** Register a pending async JS call; the deferred is completed by [resolveCall]. */
    fun registerCall(callId: String): CompletableDeferred<String> {
        val d = CompletableDeferred<String>()
        pendingCalls[callId] = d
        return d
    }

    /** Called from @JavascriptInterface (PluginBridge) when the JS Promise resolves. */
    fun resolveCall(callId: String, resultJson: String) {
        pendingCalls.remove(callId)?.complete(resultJson)
    }

    /**
     * Acquire a runtime WebView for [pluginId].
     * Waits until the execution page is fully loaded before returning.
     * Caller must call [release] in a finally block.
     */
    suspend fun get(pluginId: String, executeScript: String, hasNetwork: Boolean): WebView {
        // Phase 1: get/create entry under lock
        val entry = mutex.withLock {
            entries.getOrPut(pluginId) {
                val pageReady = CompletableDeferred<Unit>()
                val webView = createWebView(pluginId, executeScript, hasNetwork, pageReady)
                Entry(webView = webView, pageReady = pageReady)
            }.also { e ->
                e.idleJob?.cancel()
                e.idleJob = null
                e.refCount++
            }
        }
        // Phase 2: wait for page outside the lock so other coroutines aren't blocked
        entry.pageReady.await()
        return entry.webView
    }

    /** Release a previously acquired runtime. Starts idle timer when refCount reaches 0. */
    suspend fun release(pluginId: String) = mutex.withLock {
        val entry = entries[pluginId] ?: return@withLock
        entry.refCount = (entry.refCount - 1).coerceAtLeast(0)
        maybeScheduleIdle(pluginId, entry)
    }

    fun onManagementPageOpened(pluginId: String) {
        scope.launch {
            mutex.withLock {
                val entry = entries[pluginId] ?: return@withLock
                entry.managementOpen = true
                entry.idleJob?.cancel()
                entry.idleJob = null
            }
        }
    }

    fun onManagementPageClosed(pluginId: String) {
        scope.launch {
            mutex.withLock {
                val entry = entries[pluginId] ?: return@withLock
                entry.managementOpen = false
                maybeScheduleIdle(pluginId, entry)
            }
        }
    }

    /** Called when plugin is disabled/uninstalled. Destroys immediately once refCount reaches 0. */
    fun onPluginDisabled(pluginId: String) {
        scope.launch {
            mutex.withLock {
                val entry = entries[pluginId] ?: return@withLock
                entry.managementOpen = false
                if (entry.refCount <= 0) {
                    destroyEntry(pluginId, entry)
                } else {
                    entry.idleJob?.cancel()
                    entry.idleJob = scope.launch {
                        while (true) {
                            delay(200)
                            val e = mutex.withLock { entries[pluginId] } ?: return@launch
                            if (e.refCount <= 0) {
                                mutex.withLock { destroyEntry(pluginId, e) }
                                return@launch
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun evaluateJavascript(pluginId: String, js: String): String =
        withContext(Dispatchers.Main) {
            val entry = mutex.withLock { entries[pluginId] }
                ?: return@withContext "{\"error\":\"runtime not found\"}"
            suspendCoroutine { cont ->
                entry.webView.evaluateJavascript(js) { result ->
                    cont.resume(result ?: "null")
                }
            }
        }

    private fun maybeScheduleIdle(pluginId: String, entry: Entry) {
        if (entry.refCount <= 0 && !entry.managementOpen && entry.idleJob == null) {
            entry.idleJob = scope.launch {
                delay(IDLE_TIMEOUT_MS)
                mutex.withLock {
                    val e = entries[pluginId] ?: return@withLock
                    if (e.refCount <= 0 && !e.managementOpen) destroyEntry(pluginId, e)
                }
            }
        }
    }

    private fun destroyEntry(pluginId: String, entry: Entry) {
        entry.idleJob?.cancel()
        entries.remove(pluginId)
        entry.webView.destroy()
        AppLogger.i(TAG, "destroyed WebView: plugin=$pluginId")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun createWebView(
        pluginId: String,
        executeScript: String,
        hasNetwork: Boolean,
        pageReady: CompletableDeferred<Unit>
    ): WebView = withContext(Dispatchers.Main) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (pageReady.complete(Unit)) {
                        AppLogger.i(TAG, "page ready: plugin=$pluginId")
                    }
                }
            }
            addJavascriptInterface(
                PluginBridge(
                    pluginId = pluginId,
                    pluginDao = pluginDao,
                    pluginAlarmManager = pluginAlarmManager,
                    onExecuteTool = null,
                    onResolveCall = { callId, result -> resolveCall(callId, result) }
                ),
                PluginBridge.JS_INTERFACE_NAME
            )
            val html = CardRenderer.renderExecution(context, executeScript, hasNetwork)
            loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            AppLogger.i(TAG, "created WebView: plugin=$pluginId")
        }
    }

    companion object {
        private const val IDLE_TIMEOUT_MS = 60_000L
        private const val TAG = "PluginRuntimeManager"
    }
}
