package com.actme.app.plugins

import android.content.Context
import com.actme.app.util.AppLogger
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Builds fully self-contained HTML pages for plugin WebViews.
 *
 * Theme tokens are generated dynamically from the live Compose ColorScheme so
 * plugin pages always match the app's active theme (light/dark, custom palette).
 *
 * Injects from assets/web-sdk/:
 *   actme-bridge.js — ActMe.* Bridge namespace wrapper (execution + management)
 */
object CardRenderer {

    private const val TAG = "CardRenderer"

    private var bridgeJs: String? = null

    private fun loadAsset(context: Context, path: String): String =
        context.assets.open(path).bufferedReader().readText()

    private fun ensureAssets(context: Context) {
        if (bridgeJs == null) {
            bridgeJs = runCatching { loadAsset(context, "web-sdk/actme-bridge.js") }
                .getOrElse { AppLogger.e(TAG, "missing web-sdk/actme-bridge.js"); "" }
        }
    }

    private fun Color.toHex(): String {
        val r = (red   * 255 + 0.5f).toInt().coerceIn(0, 255)
        val g = (green * 255 + 0.5f).toInt().coerceIn(0, 255)
        val b = (blue  * 255 + 0.5f).toInt().coerceIn(0, 255)
        return "#%02x%02x%02x".format(r, g, b)
    }

    private fun ColorScheme.toCssTokens(): String = buildString {
        appendLine(":root {")
        appendLine("  --md-sys-color-primary:              ${primary.toHex()};")
        appendLine("  --md-sys-color-primary-container:    ${primaryContainer.toHex()};")
        appendLine("  --md-sys-color-on-primary:           ${onPrimary.toHex()};")
        appendLine("  --md-sys-color-on-primary-container: ${onPrimaryContainer.toHex()};")
        appendLine("  --md-sys-color-secondary:            ${secondary.toHex()};")
        appendLine("  --md-sys-color-secondary-container:  ${secondaryContainer.toHex()};")
        appendLine("  --md-sys-color-on-secondary:         ${onSecondary.toHex()};")
        appendLine("  --md-sys-color-surface:              ${surface.toHex()};")
        appendLine("  --md-sys-color-surface-variant:      ${surfaceVariant.toHex()};")
        appendLine("  --md-sys-color-on-surface:           ${onSurface.toHex()};")
        appendLine("  --md-sys-color-on-surface-variant:   ${onSurfaceVariant.toHex()};")
        appendLine("  --md-sys-color-background:           ${background.toHex()};")
        appendLine("  --md-sys-color-outline:              ${outline.toHex()};")
        appendLine("  --md-sys-color-outline-variant:      ${outlineVariant.toHex()};")
        appendLine("  --md-sys-color-error:                ${error.toHex()};")
        appendLine("  --md-sys-color-on-error:             ${onError.toHex()};")
        appendLine("}")
    }

    private fun applyData(html: String, data: Map<String, String>): String {
        var result = html
        data.forEach { (k, v) -> result = result.replace("{{$k}}", v) }
        return result.replace(Regex("\\{\\{[^}]+\\}\\}"), "")
    }

    /** Card inside chat bubble. Fully offline, no CDN, no Bridge. */
    fun renderCard(
        context: Context,
        html: String,
        data: Map<String, String>,
        colorScheme: ColorScheme
    ): String {
        ensureAssets(context)
        return buildHtml(
            csp = "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data:",
            extraHeadContent = buildString {
                appendLine("<style>")
                appendLine("html,body{margin:0;padding:0;background:transparent;}")
                appendLine(colorScheme.toCssTokens())
                appendLine("</style>")
            },
            body = applyData(html, data)
        )
    }

    /**
     * Management page. Injects theme tokens + Bridge.
     * CSP allows script-src https: so plugins can load @material/web from CDN.
     * Network fetch (connect-src) only if plugin declared 'network' permission.
     */
    fun renderManagement(
        context: Context,
        pluginId: String,
        html: String,
        colorScheme: ColorScheme,
        hasNetwork: Boolean
    ): String {
        ensureAssets(context)
        val connectSrc = if (hasNetwork) " connect-src *;" else ""
        val csp = "default-src 'none'; style-src 'unsafe-inline' https:; script-src 'unsafe-inline' https:;$connectSrc img-src * data:"
        return buildHtml(
            csp = csp,
            extraHeadContent = buildString {
                appendLine("<style>${colorScheme.toCssTokens()}</style>")
                if (bridgeJs?.isNotEmpty() == true) appendLine("<script>${bridgeJs}</script>")
            },
            body = html
        )
    }

    /**
     * Headless execution page (execute_script only, no visible UI).
     * No color tokens needed — there is nothing to display.
     */
    fun renderExecution(
        context: Context,
        executeScript: String,
        hasNetwork: Boolean
    ): String {
        ensureAssets(context)
        val connectSrc = if (hasNetwork) " connect-src *;" else ""
        val csp = "default-src 'none'; script-src 'unsafe-inline';$connectSrc"
        return buildHtml(
            csp = csp,
            extraHeadContent = if (bridgeJs?.isNotEmpty() == true) "<script>${bridgeJs}</script>" else "",
            body = "<script>\n$executeScript\n</script>"
        )
    }

    private fun buildHtml(csp: String, extraHeadContent: String, body: String): String = buildString {
        appendLine("<!DOCTYPE html><html><head>")
        appendLine("<meta charset=\"utf-8\">")
        appendLine("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        appendLine("<meta http-equiv=\"Content-Security-Policy\" content=\"$csp\">")
        if (extraHeadContent.isNotBlank()) appendLine(extraHeadContent)
        appendLine("</head><body>")
        appendLine(body)
        appendLine("</body></html>")
    }
}
