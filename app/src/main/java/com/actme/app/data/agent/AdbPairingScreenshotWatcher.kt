package com.actme.app.data.agent

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.actme.app.ActMeApp
import com.actme.app.image.AdbConnectionInfo
import com.actme.app.image.LocalImageImportManager
import com.actme.app.mnn.VisionModelManager
import com.actme.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object AdbPairingScreenshotWatcher {
    private const val TAG = "AdbScreenshotWatcher"
    private const val NOTIFICATION_ID = 9301
    private const val POLL_INTERVAL_MS = 2_000L
    private const val MAX_OCR_IMAGE_SIDE = 1920
    private const val MAX_OCR_IMAGE_PIXELS = 1080 * 1600

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchJob: Job? = null

    fun hasImageReadPermission(context: Context): Boolean {
        val permission = imageReadPermission()
        return permission == null ||
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun imageReadPermission(): String? {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.READ_MEDIA_IMAGES
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> Manifest.permission.READ_EXTERNAL_STORAGE
            else -> null
        }
    }

    fun start(context: Context, timeoutMs: Long = AdbPairingScreenshotService.DEFAULT_TIMEOUT_MS) {
        val appContext = context.applicationContext
        if (!hasImageReadPermission(appContext)) {
            AppLogger.w(TAG, "ADB-SCREENSHOT-WATCH not started: image permission missing")
            return
        }
        val intent = Intent(appContext, AdbPairingScreenshotService::class.java)
            .putExtra(AdbPairingScreenshotService.EXTRA_TIMEOUT_MS, timeoutMs)
        runCatching {
            ContextCompat.startForegroundService(appContext, intent)
        }.onSuccess {
            AppLogger.i(TAG, "ADB-SCREENSHOT-WATCH service requested: timeoutMs=$timeoutMs")
        }.onFailure { error ->
            AppLogger.e(TAG, "ADB-SCREENSHOT-WATCH service start failed; using in-process fallback", error)
            startInProcess(appContext, timeoutMs)
        }
    }

    private fun startInProcess(context: Context, timeoutMs: Long) {
        val appContext = context.applicationContext
        val previous = watchJob
        watchJob = scope.launch {
            previous?.cancelAndJoin()
            runWatch(appContext, timeoutMs)
        }
    }

    internal suspend fun runWatch(appContext: Context, timeoutMs: Long) {
        val startedAtMs = System.currentTimeMillis()
        val baseline = queryLatestImage(appContext)
        AppLogger.i(
            TAG,
            "ADB-SCREENSHOT-WATCH start: baselineId=${baseline?.id}, baselineAdded=${baseline?.dateAddedSec}, timeoutMs=$timeoutMs"
        )
        val events = Channel<String>(Channel.CONFLATED)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                onChange(selfChange, null)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                AppLogger.i(TAG, "ADB-SCREENSHOT-WATCH media event: selfChange=$selfChange, uri=$uri")
                events.trySend("observer")
            }
        }
        appContext.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer
        )
        val deadline = startedAtMs + timeoutMs
        var tick = 0
        var detectedImage: GalleryImage? = null
        try {
            while (System.currentTimeMillis() < deadline) {
                val event = withTimeoutOrNull(POLL_INTERVAL_MS) { events.receive() } ?: "poll"
                tick += 1
                val latest = queryLatestImage(appContext)
                AppLogger.i(
                    TAG,
                    "ADB-SCREENSHOT-WATCH tick=$tick event=$event latestId=${latest?.id}, latestAdded=${latest?.dateAddedSec}, latestName=${latest?.displayName}, latestSize=${latest?.sizeBytes}, latestPending=${latest?.isPending}"
                )
                if (latest != null && isNewImage(latest, baseline, startedAtMs)) {
                    detectedImage = latest
                    AppLogger.i(
                        TAG,
                        "ADB-SCREENSHOT-WATCH detected new image; stopping monitor before OCR: id=${latest.id}, uri=${latest.uri}, size=${latest.sizeBytes}, pending=${latest.isPending}"
                    )
                    break
                }
            }
            if (detectedImage == null) AppLogger.i(TAG, "ADB-SCREENSHOT-WATCH timeout")
        } finally {
            appContext.contentResolver.unregisterContentObserver(observer)
            events.close()
            AppLogger.i(TAG, "ADB-SCREENSHOT-WATCH observer unregistered")
        }
        detectedImage?.let { image ->
            processDetectedImage(appContext, image)
        }
    }

    private suspend fun processDetectedImage(context: Context, image: GalleryImage) {
        val copied = runCatching {
            copyAndResizeImageToAppStorage(context, image)
        }.onFailure { error ->
            AppLogger.e(
                TAG,
                "ADB-SCREENSHOT-WATCH image save failed after monitor stopped: id=${image.id}, uri=${image.uri}",
                error
            )
            showCaptureFailedNotification(context, image)
        }.getOrNull() ?: return
        AppLogger.i(
            TAG,
            "ADB-SCREENSHOT-WATCH captured: uri=${image.uri}, copied=${copied.absolutePath}, bytes=${copied.length()}"
        )
        val adbInfo = extractAdbInfoIfReady(context, copied)
        val connected = pairAndConnectAdbIfPossible(adbInfo)
        showCapturedNotification(context, copied, adbInfo)
        if (connected) {
            cleanupPairingScreenshot(context, image, copied)
        }
    }

    private fun isNewImage(latest: GalleryImage, baseline: GalleryImage?, startedAtMs: Long): Boolean {
        val addedMs = latest.dateAddedSec * 1000L
        if (addedMs < startedAtMs - 2_000L) return false
        if (baseline == null) return true
        return latest.id != baseline.id || latest.dateAddedSec > baseline.dateAddedSec
    }

    private fun queryLatestImage(context: Context): GalleryImage? {
        return runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                imageProjection(),
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                cursor.toGalleryImage()
            }
        }.onFailure { error ->
            AppLogger.e(TAG, "ADB-SCREENSHOT-WATCH query failed", error)
        }.getOrNull()
    }

    private fun queryImageById(context: Context, id: Long): GalleryImage? {
        return runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                imageProjection(),
                "${MediaStore.Images.Media._ID}=?",
                arrayOf(id.toString()),
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                cursor.toGalleryImage()
            }
        }.onFailure { error ->
            AppLogger.e(TAG, "ADB-SCREENSHOT-WATCH query by id failed: id=$id", error)
        }.getOrNull()
    }

    private fun imageProjection(): Array<String> = buildList {
        add(MediaStore.Images.Media._ID)
        add(MediaStore.Images.Media.DISPLAY_NAME)
        add(MediaStore.Images.Media.DATE_ADDED)
        add(MediaStore.Images.Media.DATE_TAKEN)
        add(MediaStore.Images.Media.SIZE)
        @Suppress("DEPRECATION")
        add(MediaStore.Images.Media.DATA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(MediaStore.Images.Media.RELATIVE_PATH)
            add(MediaStore.Images.Media.IS_PENDING)
        }
    }.toTypedArray()

    private fun android.database.Cursor.toGalleryImage(): GalleryImage {
        val id = getLong(getColumnIndexOrThrow(MediaStore.Images.Media._ID))
        val name = getStringOrNull(MediaStore.Images.Media.DISPLAY_NAME).orEmpty()
        val dateAdded = getLongOrNull(MediaStore.Images.Media.DATE_ADDED)
            ?: (System.currentTimeMillis() / 1000L)
        val dateTaken = getLongOrNull(MediaStore.Images.Media.DATE_TAKEN)
        val sizeBytes = getLongOrNull(MediaStore.Images.Media.SIZE) ?: 0L
        @Suppress("DEPRECATION")
        val dataPath = getStringOrNull(MediaStore.Images.Media.DATA)
        val relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getStringOrNull(MediaStore.Images.Media.RELATIVE_PATH)
        } else {
            null
        }
        val isPending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (getLongOrNull(MediaStore.Images.Media.IS_PENDING) ?: 0L) != 0L
        } else {
            false
        }
        return GalleryImage(
            id = id,
            uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()),
            displayName = name.ifBlank { "adb_pairing_screenshot.jpg" },
            dateAddedSec = dateAdded,
            dateTakenMs = dateTaken,
            relativePath = relativePath,
            dataPath = dataPath,
            sizeBytes = sizeBytes,
            isPending = isPending
        )
    }

    private suspend fun copyAndResizeImageToAppStorage(context: Context, image: GalleryImage): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "adb_pairing_screenshots").apply { mkdirs() }
        val target = File(dir, "${System.currentTimeMillis()}_${safeBaseName(image.displayName)}_ocr.jpg")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsDecoded = retryDecodeImageBounds(context, image, bounds)
        if (!boundsDecoded) error("Cannot open gallery image: ${image.uri}")
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "Cannot decode gallery image bounds: ${image.uri}"
        }

        val sampleSize = calculateSampleSize(
            width = bounds.outWidth,
            height = bounds.outHeight,
            maxSide = MAX_OCR_IMAGE_SIDE,
            maxPixels = MAX_OCR_IMAGE_PIXELS
        )
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = retryOpenImageStream(context, image) { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        } ?: error("Cannot decode gallery image: ${image.uri}")

        val resized = resizeWithinLimit(
            bitmap = decoded,
            maxSide = MAX_OCR_IMAGE_SIDE,
            maxPixels = MAX_OCR_IMAGE_PIXELS
        )
        if (resized !== decoded) decoded.recycle()
        target.outputStream().use { output ->
            resized.compress(Bitmap.CompressFormat.JPEG, 92, output)
        }
        val finalWidth = resized.width
        val finalHeight = resized.height
        resized.recycle()
        AppLogger.i(
            TAG,
            "ADB-SCREENSHOT-WATCH resized image: original=${bounds.outWidth}x${bounds.outHeight}, sampleSize=$sampleSize, final=${finalWidth}x${finalHeight}, path=${target.absolutePath}, bytes=${target.length()}"
        )
        target
    }

    private suspend fun retryDecodeImageBounds(
        context: Context,
        image: GalleryImage,
        bounds: BitmapFactory.Options
    ): Boolean {
        var lastError: Throwable? = null
        repeat(30) { attempt ->
            val latest = queryImageById(context, image.id) ?: image
            try {
                context.contentResolver.openInputStream(latest.uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, bounds)
                    if (bounds.outWidth > 0 && bounds.outHeight > 0) return true
                }
            } catch (error: Throwable) {
                lastError = error
            }
            latest.dataPath?.let { path ->
                val file = File(path)
                if (file.length() > 0L) {
                    runCatching {
                        file.inputStream().use { input ->
                            BitmapFactory.decodeStream(input, null, bounds)
                        }
                    }.onSuccess {
                        if (bounds.outWidth > 0 && bounds.outHeight > 0) return true
                    }.onFailure { error ->
                        lastError = error
                    }
                }
            }
            delay(400L)
            AppLogger.i(
                TAG,
                "ADB-SCREENSHOT-WATCH waiting image bounds: id=${image.id}, uri=${latest.uri}, size=${latest.sizeBytes}, pending=${latest.isPending}, dataPath=${latest.dataPath}, attempt=${attempt + 1}"
            )
        }
        lastError?.let { AppLogger.w(TAG, "ADB-SCREENSHOT-WATCH image bounds retry failed: uri=${image.uri}", it) }
        return false
    }

    private suspend fun <T> retryOpenImageStream(
        context: Context,
        image: GalleryImage,
        block: (java.io.InputStream) -> T?
    ): T? {
        var lastError: Throwable? = null
        repeat(30) { attempt ->
            val latest = queryImageById(context, image.id) ?: image
            try {
                context.contentResolver.openInputStream(latest.uri)?.use { input ->
                    block(input)?.let { return it }
                }
            } catch (error: Throwable) {
                lastError = error
            }
            latest.dataPath?.let { path ->
                val file = File(path)
                if (file.length() > 0L) {
                    runCatching {
                        file.inputStream().use { input -> block(input) }
                    }.onSuccess { result ->
                        if (result != null) return result
                    }.onFailure { error ->
                        lastError = error
                    }
                }
            }
            delay(400L)
            AppLogger.i(
                TAG,
                "ADB-SCREENSHOT-WATCH waiting image stream: id=${image.id}, uri=${latest.uri}, size=${latest.sizeBytes}, pending=${latest.isPending}, dataPath=${latest.dataPath}, attempt=${attempt + 1}"
            )
        }
        lastError?.let { AppLogger.w(TAG, "ADB-SCREENSHOT-WATCH image stream retry failed: uri=${image.uri}", it) }
        return null
    }

    private fun calculateSampleSize(width: Int, height: Int, maxSide: Int, maxPixels: Int): Int {
        var sampleSize = 1
        while (true) {
            val sampledWidth = width / sampleSize
            val sampledHeight = height / sampleSize
            val sampledPixels = sampledWidth.toLong() * sampledHeight.toLong()
            if (sampledWidth <= maxSide && sampledHeight <= maxSide && sampledPixels <= maxPixels) break
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun resizeWithinLimit(bitmap: Bitmap, maxSide: Int, maxPixels: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        val pixels = bitmap.width.toLong() * bitmap.height.toLong()
        if (longest <= maxSide && pixels <= maxPixels) return bitmap
        val sideScale = maxSide.toDouble() / longest.toDouble()
        val pixelScale = sqrt(maxPixels.toDouble() / pixels.toDouble())
        val scale = min(sideScale, pixelScale)
        val targetWidth = ceil(bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = ceil(bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private suspend fun extractAdbInfoIfReady(context: Context, file: File): AdbConnectionInfo? =
        withContext(Dispatchers.IO) {
            val modelManager = VisionModelManager(context, VisionModelManager.OCR_MODEL_NAME)
            if (!modelManager.isModelReady) {
                AppLogger.i(TAG, "ADB-SCREENSHOT-OCR skipped: GLM-OCR-MNN not ready")
                return@withContext null
            }
            val imageImportManager = LocalImageImportManager(VisionModelManager.getOcrModelDir(context))
            runCatching {
                imageImportManager.extractText(file).adbConnectionInfo
            }.onSuccess { info ->
                AppLogger.i(
                    TAG,
                    "ADB-SCREENSHOT-OCR info: device=${info?.deviceName}, host=${info?.host}, connectPort=${info?.connectPort}, pairPort=${info?.pairPort}, pairCode=${info?.pairCode}"
                )
            }.onFailure { error ->
                AppLogger.e(TAG, "ADB-SCREENSHOT-OCR failed", error)
            }.also {
                imageImportManager.release()
            }.getOrNull()
        }

    private suspend fun pairAndConnectAdbIfPossible(info: AdbConnectionInfo?): Boolean {
        if (info == null) {
            AppLogger.w(TAG, "ADB-AUTO-CONNECT skipped: OCR did not produce ADB info")
            return false
        }
        val host = info.host.trim()
        val pairPort = info.pairPort.toIntOrNull()
        val connectPort = info.connectPort.toIntOrNull()
        val pairCode = info.pairCode.trim()
        val missing = buildList {
            if (host.isBlank()) add("host")
            if (pairPort == null) add("pairPort")
            if (connectPort == null) add("connectPort")
            if (pairCode.isBlank()) add("pairCode")
        }
        AppLogger.i(
            TAG,
            "ADB-AUTO-CONNECT parsed: device=${info.deviceName}, host=$host, pairPort=${info.pairPort}, connectPort=${info.connectPort}, pairCode=$pairCode"
        )
        if (missing.isNotEmpty()) {
            AppLogger.w(TAG, "ADB-AUTO-CONNECT skipped: missing=${missing.joinToString(",")}")
            return false
        }
        val pairPortValue = pairPort ?: return false
        val connectPortValue = connectPort ?: return false
        if (pairPortValue == connectPortValue) {
            AppLogger.w(
                TAG,
                "ADB-AUTO-CONNECT skipped: pairPort equals connectPort; likely OCR confused pairing and connection ports"
            )
            return false
        }

        AppLogger.i(TAG, "ADB-AUTO-CONNECT pair begin: $host:$pairPortValue")
        val pairResult = AdbSkillEngine.pair(host, pairPortValue, pairCode)
        AppLogger.i(
            TAG,
            "ADB-AUTO-CONNECT pair result: ok=${pairResult.ok}, output=${pairResult.output.take(240)}, error=${pairResult.error.take(240)}"
        )
        if (!pairResult.ok) {
            AppLogger.w(TAG, "ADB-AUTO-CONNECT stopped: pair failed")
            return false
        }

        AppLogger.i(TAG, "ADB-AUTO-CONNECT test begin: $host:$connectPortValue")
        val testResult = AdbSkillEngine.testConnection(host, connectPortValue)
        AppLogger.i(
            TAG,
            "ADB-AUTO-CONNECT test result: ok=${testResult.ok}, exitCode=${testResult.exitCode}, output=${testResult.output.take(240)}, error=${testResult.error.take(240)}"
        )
        if (testResult.ok) {
            AppLogger.i(TAG, "ADB-AUTO-CONNECT complete: saved=$host:$connectPortValue")
            return true
        } else {
            AppLogger.w(TAG, "ADB-AUTO-CONNECT failed: test connection failed")
            return false
        }
    }

    private suspend fun cleanupPairingScreenshot(context: Context, image: GalleryImage, copied: File) {
        withContext(Dispatchers.IO) {
            runCatching {
                if (copied.exists()) {
                    val deleted = copied.delete()
                    AppLogger.i(TAG, "ADB-SCREENSHOT-CLEANUP appCopy deleted=$deleted path=${copied.absolutePath}")
                } else {
                    AppLogger.i(TAG, "ADB-SCREENSHOT-CLEANUP appCopy already missing path=${copied.absolutePath}")
                }
            }.onFailure { error ->
                AppLogger.w(TAG, "ADB-SCREENSHOT-CLEANUP appCopy failed path=${copied.absolutePath}", error)
            }

            val dataPath = image.dataPath?.trim().orEmpty()
            if (dataPath.isNotBlank()) {
                val result = AdbSkillEngine.shell("rm -f ${shellQuote(dataPath)}", timeoutMs = 8_000L)
                AppLogger.i(
                    TAG,
                    "ADB-SCREENSHOT-CLEANUP galleryByAdb ok=${result.ok}, path=$dataPath, output=${result.output.trim()}, error=${result.error.trim()}"
                )
                if (result.ok) return@withContext
            } else {
                AppLogger.w(TAG, "ADB-SCREENSHOT-CLEANUP galleryByAdb skipped: DATA path is blank uri=${image.uri}")
            }

            runCatching {
                val rows = context.contentResolver.delete(image.uri, null, null)
                AppLogger.i(TAG, "ADB-SCREENSHOT-CLEANUP galleryByResolver rows=$rows uri=${image.uri}")
            }.onFailure { error ->
                AppLogger.w(TAG, "ADB-SCREENSHOT-CLEANUP galleryByResolver failed uri=${image.uri}", error)
            }
        }
    }

    private fun shellQuote(text: String): String =
        "'" + text.replace("'", "'\\''") + "'"

    private fun showCapturedNotification(context: Context, file: File, adbInfo: AdbConnectionInfo?) {
        val adbSummary = adbInfo?.toNotificationSummary().orEmpty()
        val contentText = adbSummary.ifBlank { "已读取新增图片：${file.name}" }
        val bigText = buildString {
            appendLine("已获取截图。")
            appendLine("已读取新增图片：${file.name}")
            if (adbSummary.isNotBlank()) {
                appendLine()
                append(adbSummary)
            }
            appendLine()
            append("可返回 ActMe 继续 ADB 配对。")
        }
        val notification = NotificationCompat.Builder(context, ActMeApp.ADB_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("已获取截图")
            .setContentText(contentText)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(bigText)
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun showCaptureFailedNotification(context: Context, image: GalleryImage) {
        val notification = NotificationCompat.Builder(context, ActMeApp.ADB_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("ADB screenshot read failed")
            .setContentText("Detected screenshot but cannot read it yet: ${image.displayName}")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Detected an ADB pairing screenshot, but failed to read the image from MediaStore.\nImage: ${image.displayName}\nPlease start listening and take the screenshot again.")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun safeFileName(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)
        return cleaned.ifBlank { "adb_pairing_screenshot.jpg" }
    }

    private fun safeBaseName(name: String): String {
        return safeFileName(name)
            .substringBeforeLast('.', "adb_pairing_screenshot")
            .ifBlank { "adb_pairing_screenshot" }
    }

    private fun AdbConnectionInfo.toNotificationSummary(): String {
        val parts = buildList {
            if (deviceName.isNotBlank()) add("设备：$deviceName")
            if (host.isNotBlank()) add("地址：$host")
            if (connectPort.isNotBlank()) add("连接端口：$connectPort")
            if (pairPort.isNotBlank()) add("配对端口：$pairPort")
            if (pairCode.isNotBlank()) add("配对码：$pairCode")
        }
        return parts.joinToString("\n")
    }

    private data class GalleryImage(
        val id: Long,
        val uri: Uri,
        val displayName: String,
        val dateAddedSec: Long,
        val dateTakenMs: Long?,
        val relativePath: String?,
        val dataPath: String?,
        val sizeBytes: Long,
        val isPending: Boolean
    )
}

private fun android.database.Cursor.getStringOrNull(columnName: String): String? {
    val index = getColumnIndex(columnName)
    return if (index >= 0 && !isNull(index)) getString(index) else null
}

private fun android.database.Cursor.getLongOrNull(columnName: String): Long? {
    val index = getColumnIndex(columnName)
    return if (index >= 0 && !isNull(index)) getLong(index) else null
}
