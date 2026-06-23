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

class VisionModelManager(
    private val context: Context,
    private val modelName: String = MODEL_NAME
) {
    companion object {
        const val TAG = "VisionModelManager"
        const val MODEL_OWNER = "MNN"
        const val MODEL_NAME = "GUI-Owl-1.5-2B-Instruct-MNN"
        const val OCR_MODEL_NAME = "GLM-OCR-MNN"
        private const val BASE_URL = "https://modelscope.cn/api/v1/models"

        fun getDefaultModelDir(context: Context): String {
            return "${context.filesDir}/models/$MODEL_NAME"
        }

        fun getOcrModelDir(context: Context): String {
            return "${context.filesDir}/models/$OCR_MODEL_NAME"
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

    val modelDir: String get() = "${context.filesDir}/models/$modelName"

    val isModelReady: Boolean
        get() {
            val dir = File(modelDir)
            val commonReady = File(dir, "config.json").exists() &&
                File(dir, "llm.mnn.weight").exists() &&
                File(dir, "visual.mnn.weight").exists()
            if (!commonReady) return false
            return modelName != OCR_MODEL_NAME || File(dir, "embeddings_bf16.bin").exists()
        }

    suspend fun fetchModelInfo(): ModelInfo = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/$MODEL_OWNER/$modelName/repo/files?Revision=master&Recursive=true"
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
        ModelInfo(name = modelName, files = files, totalSize = totalSize, fileCount = files.size)
    }

    private fun fallbackFileList(): ModelInfo {
        val files = mutableListOf(
            ModelFile("config.json", 1024, "file"),
            ModelFile("llm.mnn", 1024 * 512, "lfs"),
            ModelFile("llm.mnn.json", 1024 * 1024, "file"),
            ModelFile("llm.mnn.weight", 1024L * 1024 * 1024 * 123 / 100, "lfs"),
            ModelFile("llm_config.json", 8192, "file"),
            ModelFile("tokenizer.txt", 1024 * 1024 * 4, "file"),
            ModelFile("visual.mnn", 1024 * 512, "lfs"),
            ModelFile("visual.mnn.weight", 1024L * 1024 * 238, "lfs")
        )
        if (modelName == OCR_MODEL_NAME) {
            files.add(6, ModelFile("embeddings_bf16.bin", 1024L * 1024 * 128, "lfs"))
        }
        return ModelInfo(
            name = modelName,
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

                val downloadUrl = "$BASE_URL/$MODEL_OWNER/$modelName/repo?Revision=master&FilePath=${file.name}"
                AppLogger.i(TAG, "Downloading vision model file: model=$modelName, file=${file.name}, size=${file.size}, url=$downloadUrl")
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
            AppLogger.i(TAG, "Vision model download complete: model=$modelName, dir=$modelDir")
            modelDir
        } catch (e: Exception) {
            AppLogger.e(TAG, "Vision model download failed", e)
            _downloadState.value = DownloadState.Error(e.message ?: "下载失败")
            throw e
        }
    }
}
