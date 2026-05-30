package com.actme.app.ui.plugins

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.actme.app.data.local.PluginDao
import com.actme.app.plugins.CardRenderer
import com.actme.app.plugins.Plugin
import com.actme.app.plugins.PluginAlarmManager
import com.actme.app.plugins.PluginBridge

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PluginManagementScreen(
    plugin: Plugin,
    pluginDao: PluginDao,
    pluginAlarmManager: PluginAlarmManager,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val html = plugin.getManagementHtml()

    val webView = remember(plugin.id) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = WebViewClient()
            addJavascriptInterface(
                PluginBridge(
                    pluginId = plugin.id,
                    pluginDao = pluginDao,
                    pluginAlarmManager = pluginAlarmManager,
                    onExecuteTool = { t, a -> plugin.execute(t, a) },
                    onBack = onBack
                ),
                PluginBridge.JS_INTERFACE_NAME
            )
            if (html != null) {
                val page = CardRenderer.renderManagement(context, plugin.id, html, colorScheme, hasNetwork = false)
                loadDataWithBaseURL(null, page, "text/html", "UTF-8", null)
            }
        }
    }

    DisposableEffect(plugin.id) {
        onDispose { webView.destroy() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                Icons.Filled.Extension,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(plugin.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize()
        )
    }
}
