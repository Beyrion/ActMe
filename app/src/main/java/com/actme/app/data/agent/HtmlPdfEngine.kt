package com.actme.app.data.agent

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Handler
import android.os.Looper
import android.print.ActMePrintBridge
import android.print.PrintAttributes
import android.text.Html
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebView
import android.webkit.WebViewClient
import com.actme.app.ActMeApp
import com.actme.app.util.AppLogger
import java.io.File
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

object HtmlPdfEngine {
    private const val TAG = "HtmlPdfEngine"

    suspend fun render(htmlFile: File, pdfFile: File): Result<File> = suspendCancellableCoroutine { cont ->
        val context = PythonSkillEngine.applicationContext()
        if (context == null) {
            cont.resume(Result.failure(IllegalStateException("Application context is not initialized.")))
            return@suspendCancellableCoroutine
        }
        if (!htmlFile.isFile) {
            cont.resume(Result.failure(java.io.FileNotFoundException(htmlFile.absolutePath)))
            return@suspendCancellableCoroutine
        }
        Handler(Looper.getMainLooper()).post {
            renderOnMain(context, htmlFile, pdfFile) { result ->
                if (cont.isActive) cont.resume(result)
            }
        }
    }

    private fun renderOnMain(
        context: Context,
        htmlFile: File,
        pdfFile: File,
        done: (Result<File>) -> Unit
    ) {
        val rawHtml = runCatching { htmlFile.readText(Charsets.UTF_8) }.getOrElse { error ->
            done(Result.failure(error))
            return
        }
        val html = preparePrintableHtml(rawHtml)
        val handler = Handler(Looper.getMainLooper())
        var timeout: Runnable? = null
        var finished = false

        fun fallback(error: Throwable): Result<File> {
            AppLogger.w(TAG, "WebView PDF failed, trying native text PDF fallback:\n${error.stackTraceToString()}")
            return runCatching {
                writeNativeTextPdf(html, pdfFile)
                AppLogger.i(TAG, "native fallback pdf written: ${pdfFile.absolutePath}, bytes=${pdfFile.length()}")
                pdfFile
            }
        }

        fun finish(webView: WebView?, result: Result<File>) {
            if (finished) return
            finished = true
            timeout?.let { handler.removeCallbacks(it) }
            runCatching {
                val host = webView?.parent as? ViewGroup
                val root = host?.parent as? ViewGroup
                if (host != null && root != null) root.removeView(host)
            }
            runCatching { webView?.destroy() }
            done(result)
        }

        fun finishWithFallback(webView: WebView?, error: Throwable) {
            finish(webView, fallback(error))
        }

        val app = context.applicationContext as? ActMeApp
        val activity = app?.currentActivity()
        val root = activity?.window?.decorView as? ViewGroup
        val ownerContext = activity ?: context
        val displayWidth = context.resources.displayMetrics.widthPixels.coerceAtLeast(595)
        val rootWidth = root?.width?.takeIf { it > 0 } ?: displayWidth
        val width = rootWidth.coerceAtMost(1440).coerceAtLeast(595)
        val height = ((width * 842f) / 595f).toInt().coerceAtLeast(842)
        val host = FrameLayout(ownerContext).apply {
            setBackgroundColor(Color.WHITE)
            elevation = 10_000f
            isClickable = true
            isFocusable = true
            layoutParams = ViewGroup.LayoutParams(width, height)
        }
        val webView = WebView(ownerContext).apply {
            isClickable = false
            isFocusable = false
            setBackgroundColor(Color.WHITE)
        }
        if (root != null) {
            root.addView(host, ViewGroup.LayoutParams(width, height))
            host.addView(webView, FrameLayout.LayoutParams(width, height))
            AppLogger.i(TAG, "attached visible WebView to activity decor: ${activity::class.java.simpleName}, host=${width}x$height")
        } else {
            AppLogger.w(TAG, "No resumed Activity; using unattached WebView with application context")
        }
        timeout = Runnable {
            finishWithFallback(webView, TimeoutException("WebView PDF render timed out."))
        }
        handler.postDelayed(timeout, 20_000L)

        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.settings.javaScriptEnabled = false
        webView.settings.loadWithOverviewMode = false
        webView.settings.useWideViewPort = false
        webView.settings.builtInZoomControls = false
        webView.settings.displayZoomControls = false
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

            override fun onPageFinished(view: WebView, url: String) {
                AppLogger.i(TAG, "html loaded: $url")
                view.postDelayed({
                    printToPdf(view, htmlFile, pdfFile) { result ->
                        result.fold(
                            onSuccess = { finish(view, Result.success(it)) },
                            onFailure = { finishWithFallback(view, it) }
                        )
                    }
                }, 300L)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    finishWithFallback(view, RuntimeException("WebView load failed: ${error.errorCode} ${error.description}"))
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                finishWithFallback(
                    view,
                    RuntimeException("WebView renderer process gone: didCrash=${detail.didCrash()}, priorityAtExit=${detail.rendererPriorityAtExit()}")
                )
                return true
            }
        }
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        webView.layout(0, 0, width, height)
        AppLogger.i(TAG, "load html data: ${htmlFile.absolutePath}, rawChars=${rawHtml.length}, printableChars=${html.length}, viewport=${width}x$height")
        webView.loadDataWithBaseURL(
            htmlFile.parentFile?.toURI()?.toString(),
            html,
            "text/html",
            "UTF-8",
            null
        )
    }

    private fun preparePrintableHtml(html: String): String {
        val printCss = """
            <style>
            @page { size: A4; margin: 14mm; }
            * { box-sizing: border-box !important; animation: none !important; transition: none !important; }
            html, body { width: auto !important; max-width: 100% !important; margin: 0 !important; padding: 0 !important; background: #fff !important; color: #111 !important; }
            body { font-family: sans-serif; font-size: 12pt; line-height: 1.45; overflow-wrap: anywhere; }
            img, svg, canvas, video { max-width: 100% !important; height: auto !important; }
            table { width: 100% !important; max-width: 100% !important; border-collapse: collapse; page-break-inside: auto; }
            tr { page-break-inside: avoid; }
            th, td { word-break: break-word; overflow-wrap: anywhere; }
            pre, code { white-space: pre-wrap !important; overflow-wrap: anywhere !important; }
            .shadow, [style*="box-shadow"] { box-shadow: none !important; }
            </style>
        """.trimIndent()
        if (html.contains("</head>", ignoreCase = true)) {
            return html.replace(Regex("</head>", RegexOption.IGNORE_CASE), "$printCss</head>")
        }
        if (html.contains("<body", ignoreCase = true)) {
            return html.replace(Regex("<body([^>]*)>", RegexOption.IGNORE_CASE), "<body$1>$printCss")
        }
        return if (html.contains("<html", ignoreCase = true)) {
            html.replace(Regex("<html([^>]*)>", RegexOption.IGNORE_CASE), "<html$1><head><meta charset=\"utf-8\">$printCss</head>")
        } else {
            "<!doctype html><html><head><meta charset=\"utf-8\">$printCss</head><body>$html</body></html>"
        }
    }

    private fun printToPdf(
        webView: WebView,
        htmlFile: File,
        pdfFile: File,
        done: (Result<File>) -> Unit
    ) {
        runCatching { pdfFile.parentFile?.mkdirs() }
        val adapter = webView.createPrintDocumentAdapter(htmlFile.nameWithoutExtension)
        val attributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(PrintAttributes.Resolution("actme_pdf_600", "ActMe PDF 600dpi", 600, 600))
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .build()
        AppLogger.i(TAG, "print pdf: ${pdfFile.absolutePath}, resolution=600dpi")

        ActMePrintBridge.print(adapter, attributes, pdfFile, object : ActMePrintBridge.Callback {
            override fun onSuccess(file: File) {
                AppLogger.i(TAG, "pdf written: ${file.absolutePath}, bytes=${file.length()}")
                done(Result.success(file))
            }

            override fun onError(error: Throwable) {
                done(Result.failure(error))
            }
        })
    }

    private fun writeNativeTextPdf(html: String, pdfFile: File) {
        runCatching { pdfFile.parentFile?.mkdirs() }
        val text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trim().ifBlank { html.ifBlank { " " } }
        val document = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 48
        val contentWidth = pageWidth - margin * 2
        val contentHeight = pageHeight - margin * 2
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 12f
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(4f, 1.0f)
            .setIncludePad(false)
            .build()
        var startLine = 0
        var pageNumber = 1
        while (startLine < layout.lineCount) {
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            val page = document.startPage(pageInfo)
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)
            canvas.save()
            canvas.translate(margin.toFloat(), margin.toFloat())
            val startTop = layout.getLineTop(startLine)
            var endLine = startLine
            while (endLine < layout.lineCount && layout.getLineBottom(endLine) - startTop <= contentHeight) {
                endLine++
            }
            if (endLine == startLine) endLine++
            canvas.clipRect(0, 0, contentWidth, contentHeight)
            canvas.translate(0f, -startTop.toFloat())
            layout.draw(canvas)
            canvas.restore()
            document.finishPage(page)
            startLine = endLine
            pageNumber++
        }
        pdfFile.outputStream().use { output -> document.writeTo(output) }
        document.close()
    }
}
