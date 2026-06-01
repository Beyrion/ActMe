package com.actme.app.mnn

import android.content.Context
import com.actme.app.util.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class VisionModelManager(private val context: Context) {
    companion object {
        const val TAG = "VisionModelManager"
        const val MODEL_OWNER = "MNN"
        const val MODEL_NAME = "Qwen3-VL-2B-Instruct-MNN"
        private const val BASE_URL = "https://modelscope.cn/api/v1/models"

        fun getDefaultModelDir(context: Context): String {
            return "${context.filesDir}/models/$MODEL_NAME"
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.NotStarted)
    val downloadState: StateFlow<DownloadState> = _downloadState

    private val _modelInfo = MutableStateFlow<ModelInfo?>(null)
    val modelInfo: StateFlow<ModelInfo?> = _modelInfo

    val modelDir: String get() = getDefaultModelDir(context)

    val isModelReady: Boolean
        get() = File(modelDir, "config.json").exists() &&
            File(modelDir, "llm.mnn.weight").exists() &&
            File(modelDir, "visual.mnn.weight").exists()

    suspend fun fetchModelInfo(): ModelInfo = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/$MODEL_OWNER/$MODEL_NAME/repo/files?Revision=master&Recursive=true"
        AppLogger.i(TAG, "Fetching vision model info from: $url")

        val request = Request.Builder().url(url).get()
            .header("Accept", "application/json")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("获取视觉模型信息失败: HTTP ${response.code}")
        }

        val body = response.body?.string() ?: throw Exception("服务器返回空数据")
        val files = mutableListOf<ModelFile>()
        var totalSize = 0L

        try {
            val json = JSONObject(body)
            val data = json.optJSONArray("Data")
                ?: json.optJSONArray("Files")
                ?: JSONArray()

            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val name = item.optString("Name", item.optString("Path", "unknown"))
                val size = item.optLong("Size", 0L)
                val type = item.optString("Type", "file")
                files.add(ModelFile(name, size, type))
                totalSize += size
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to parse vision model info, using fallback", e)
            return@withContext fallbackFileList()
        }

        if (files.isEmpty()) return@withContext fallbackFileList()
        ModelInfo(name = MODEL_NAME, files = files, totalSize = totalSize, fileCount = files.size)
    }

    private fun fallbackFileList(): ModelInfo {
        val files = listOf(
            ModelFile("config.json", 1024, "file"),
            ModelFile("llm.mnn", 1024 * 512, "lfs"),
            ModelFile("llm.mnn.json", 1024 * 1024, "file"),
            ModelFile("llm.mnn.weight", 1024L * 1024 * 1024 * 123 / 100, "lfs"),
            ModelFile("llm_config.json", 8192, "file"),
            ModelFile("tokenizer.txt", 1024 * 1024 * 4, "file"),
            ModelFile("visual.mnn", 1024 * 512, "lfs"),
            ModelFile("visual.mnn.weight", 1024L * 1024 * 238, "lfs")
        )
        return ModelInfo(
            name = MODEL_NAME,
            files = files,
            totalSize = files.sumOf { it.size },
            fileCount = files.size
        )
    }

    suspend fun downloadModel(): String = withContext(Dispatchers.IO) {
        try {
            _downloadState.value = DownloadState.Checking
            val info = try {
                fetchModelInfo()
            } catch (e: Exception) {
                AppLogger.w(TAG, "Could not fetch vision model info, using fallback", e)
                fallbackFileList()
            }
            _modelInfo.value = info

            val targetDir = File(modelDir)
            targetDir.mkdirs()

            var totalBytesDownloaded = 0L
            info.files.forEachIndexed { index, file ->
                _downloadState.value = DownloadState.Downloading(
                    currentFile = file.name,
                    currentFileIndex = index + 1,
                    totalFiles = info.fileCount,
                    currentFileProgress = 0f,
                    totalBytesDownloaded = totalBytesDownloaded,
                    totalBytes = info.totalSize
                )

                val targetFile = File(targetDir, file.name)
                targetFile.parentFile?.mkdirs()
                if (targetFile.exists() && targetFile.length() > 0 &&
                    (file.size <= 0 || targetFile.length() >= file.size * 0.95)) {
                    totalBytesDownloaded += targetFile.length()
                    return@forEachIndexed
                }

                val downloadUrl = "$BASE_URL/$MODEL_OWNER/$MODEL_NAME/repo?Revision=master&FilePath=${file.name}"
                val request = Request.Builder().url(downloadUrl).get().build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw Exception("下载 ${file.name} 失败: HTTP ${response.code}")
                }
                val body = response.body ?: throw Exception("下载 ${file.name} 失败: 响应为空")
                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(65536)
                        var bytesRead: Int
                        var fileBytesDownloaded = 0L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            fileBytesDownloaded += bytesRead
                            if (fileBytesDownloaded % (1024 * 1024) < 65536) {
                                val fileProgress = if (file.size > 0) {
                                    fileBytesDownloaded.toFloat() / file.size
                                } else 0f
                                _downloadState.value = DownloadState.Downloading(
                                    currentFile = file.name,
                                    currentFileIndex = index + 1,
                                    totalFiles = info.fileCount,
                                    currentFileProgress = fileProgress,
                                    totalBytesDownloaded = totalBytesDownloaded + fileBytesDownloaded,
                                    totalBytes = info.totalSize
                                )
                            }
                        }
                        totalBytesDownloaded += fileBytesDownloaded
                    }
                }
            }

            _downloadState.value = DownloadState.Completed(modelDir)
            AppLogger.i(TAG, "Vision model download complete: $modelDir")
            modelDir
        } catch (e: Exception) {
            AppLogger.e(TAG, "Vision model download failed", e)
            _downloadState.value = DownloadState.Error(e.message ?: "下载失败")
            throw e
        }
    }
}
