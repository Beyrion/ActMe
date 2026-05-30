package com.actme.app.ui.plugins

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.actme.app.data.local.PluginDao
import com.actme.app.plugins.CardRenderer
import com.actme.app.plugins.Plugin
import com.actme.app.plugins.PluginAlarmManager
import com.actme.app.plugins.PluginBridge
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginSheet(
    plugin: Plugin,
    pluginDao: PluginDao,
    pluginAlarmManager: PluginAlarmManager,
    onDismiss: () -> Unit
) {
    val html = plugin.getManagementHtml()
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val statusBarTopDp = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }
    val maxSheetHeight = LocalConfiguration.current.screenHeightDp.dp - statusBarTopDp - 64.dp

    fun dismiss() { scope.launch { sheetState.hide(); onDismiss() } }

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
                    onBack = { dismiss() }
                ),
                PluginBridge.JS_INTERFACE_NAME
            )
            if (html != null) {
                val page = CardRenderer.renderManagement(context, plugin.id, html, colorScheme, hasNetwork = false)
                loadDataWithBaseURL(null, page, "text/html", "UTF-8", null)
            }
        }
    }

    DisposableEffect(plugin.id) { onDispose { webView.destroy() } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null
    ) {
        Column(modifier = Modifier.fillMaxWidth().height(maxSheetHeight)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Extension, contentDescription = null,
                        modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(plugin.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(plugin.id, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
                }
                IconButton(onClick = { dismiss() }) {
                    Icon(Icons.Filled.ExpandMore, contentDescription = "关闭")
                }
            }
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().weight(1f))
        }
    }
}
