package com.actme.app.mnn

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class ModelFile(
    val name: String,
    val size: Long,
    val type: String // "file" or "lfs"
)

data class ModelInfo(
    val name: String,
    val files: List<ModelFile>,
    val totalSize: Long,
    val fileCount: Int
)

sealed class DownloadState {
    data object NotStarted : DownloadState()
    data object Checking : DownloadState()
    data class Downloading(
        val currentFile: String,
        val currentFileIndex: Int,
        val totalFiles: Int,
        val currentFileProgress: Float,
        val totalBytesDownloaded: Long,
        val totalBytes: Long
    ) : DownloadState()
    data class Completed(val modelDir: String) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

class ModelManager(private val context: Context) {
    companion object {
        const val TAG = "ModelManager"
        const val MODEL_OWNER = "huangzhengxiang"
        const val MODEL_NAME = "Qwen3-ASR-0.6B-INT8-MNN"
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

    val isModelReady: Boolean get() = File(modelDir, "config.json").exists()

    fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
            else -> "${"%.2f".format(bytes.toDouble() / (1024 * 1024 * 1024))} GB"
        }
    }

    suspend fun fetchModelInfo(): ModelInfo = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/$MODEL_OWNER/$MODEL_NAME/repo/files?Revision=master&Recursive=true"
        Log.i(TAG, "Fetching model info from: $url")

        val request = Request.Builder().url(url).get()
            .header("Accept", "application/json")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("获取模型信息失败: HTTP ${response.code}")
        }

        val body = response.body?.string() ?: throw Exception("服务器返回空数据")
        Log.d(TAG, "API response: ${body.take(500)}")

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
            Log.e(TAG, "Failed to parse response, using fallback file list", e)
            return@withContext fallbackFileList()
        }

        if (files.isEmpty()) {
            Log.w(TAG, "Empty file list from API, using fallback")
            return@withContext fallbackFileList()
        }

        ModelInfo(name = MODEL_NAME, files = files, totalSize = totalSize, fileCount = files.size)
    }

    private fun fallbackFileList(): ModelInfo {
        val files = listOf(
            ModelFile("config.json", 1024, "file"),
            ModelFile("llm_config.json", 2048, "file"),
            ModelFile("export_args.json", 1024, "file"),
            ModelFile("tokenizer.txt", 1024 * 1024, "file"),
            ModelFile("audio.mnn", 1024 * 1024 * 200, "lfs"),
            ModelFile("audio.mnn.weight", 1024 * 1024 * 500, "lfs"),
            ModelFile("llm.mnn", 1024 * 1024 * 100, "lfs"),
            ModelFile("llm.mnn.json", 4096, "file"),
            ModelFile("llm.mnn.weight", 1024 * 1024 * 600, "lfs"),
            ModelFile("embeddings_bf16.bin", 1024 * 1024 * 20, "file")
        )
        val totalSize = files.sumOf { it.size }
        return ModelInfo(
            name = MODEL_NAME,
            files = files,
            totalSize = totalSize,
            fileCount = files.size
        )
    }

    suspend fun downloadModel(): String = withContext(Dispatchers.IO) {
        try {
            _downloadState.value = DownloadState.Checking
            Log.i(TAG, "Starting model download...")

            val info = try {
                fetchModelInfo()
            } catch (e: Exception) {
                Log.w(TAG, "Could not fetch model info, using fallback", e)
                fallbackFileList()
            }
            _modelInfo.value = info
            Log.i(TAG, "Model: ${info.name}, ${info.fileCount} files, ${formatSize(info.totalSize)}")

            val targetDir = File(modelDir)
            targetDir.mkdirs()

            var totalBytesDownloaded = 0L

            info.files.forEachIndexed { index, file ->
                Log.i(TAG, "[${index + 1}/${info.fileCount}] Downloading: ${file.name} (${formatSize(file.size)})")

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

                // Skip if already downloaded with correct size
                if (targetFile.exists() && targetFile.length() > 0 &&
                    (file.size <= 0 || targetFile.length() >= file.size * 0.95)) {
                    Log.i(TAG, "  Skipping (already exists): ${file.name}")
                    totalBytesDownloaded += targetFile.length()
                    return@forEachIndexed
                }

                val downloadUrl = if (file.type == "lfs") {
                    "$BASE_URL/$MODEL_OWNER/$MODEL_NAME/repo?Revision=master&FilePath=${file.name}"
                } else {
                    "$BASE_URL/$MODEL_OWNER/$MODEL_NAME/repo?Revision=master&FilePath=${file.name}"
                }

                val request = Request.Builder().url(downloadUrl).get().build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    throw Exception("下载 ${file.name} 失败: HTTP ${response.code}")
                }

                val body = response.body
                if (body == null) {
                    throw Exception("下载 ${file.name} 失败: 响应为空")
                }

                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(65536)
                        var bytesRead: Int
                        var fileBytesDownloaded = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            fileBytesDownloaded += bytesRead

                            // Update progress every ~1MB to reduce UI updates
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

                Log.i(TAG, "  Done: ${file.name}")
            }

            _downloadState.value = DownloadState.Completed(modelDir)
            Log.i(TAG, "Model download complete: $modelDir")
            modelDir
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed", e)
            _downloadState.value = DownloadState.Error(e.message ?: "下载失败")
            throw e
        }
    }
}
