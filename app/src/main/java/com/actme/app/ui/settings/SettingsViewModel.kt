package com.actme.app.ui.settings

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.actme.app.data.local.ProviderEntity
import com.actme.app.data.repo.ActMeRepository
import com.actme.app.mnn.DownloadState
import com.actme.app.mnn.ModelInfo
import com.actme.app.mnn.ModelManager
import com.actme.app.mnn.VisionModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class SettingsViewModel(
    application: Application,
    private val repository: ActMeRepository
) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("actme_voice_settings", Context.MODE_PRIVATE)
    private val modelManager = ModelManager(application)
    private val visionModelManager = VisionModelManager(application)
    private val ocrModelManager = VisionModelManager(application, VisionModelManager.OCR_MODEL_NAME)

    val providers: StateFlow<List<ProviderEntity>> = repository.providers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _activeProviderId = MutableStateFlow(repository.getActiveProviderId())
    val activeProviderId: StateFlow<Long> = _activeProviderId

    private val _asrLanguage = MutableStateFlow(prefs.getString("asr_language", "Chinese") ?: "Chinese")
    val asrLanguage: StateFlow<String> = _asrLanguage

    private val _isModelReady = MutableStateFlow(modelManager.isModelReady)
    val isModelReady: StateFlow<Boolean> = _isModelReady
    private val _localAsrModelDir = MutableStateFlow(modelManager.modelDir)
    val localAsrModelDir: StateFlow<String> = _localAsrModelDir

    private val _isVisionModelReady = MutableStateFlow(visionModelManager.isModelReady)
    val isVisionModelReady: StateFlow<Boolean> = _isVisionModelReady
    private val _localVisionModelDir = MutableStateFlow(visionModelManager.modelDir)
    val localVisionModelDir: StateFlow<String> = _localVisionModelDir
    private val _isOcrModelReady = MutableStateFlow(ocrModelManager.isModelReady)
    val isOcrModelReady: StateFlow<Boolean> = _isOcrModelReady
    private val _localOcrModelDir = MutableStateFlow(ocrModelManager.modelDir)
    val localOcrModelDir: StateFlow<String> = _localOcrModelDir

    val downloadState: StateFlow<DownloadState> = modelManager.downloadState
    val modelInfo: StateFlow<ModelInfo?> = modelManager.modelInfo
    val visionDownloadState: StateFlow<DownloadState> = visionModelManager.downloadState
    val visionModelInfo: StateFlow<ModelInfo?> = visionModelManager.modelInfo
    val ocrDownloadState: StateFlow<DownloadState> = ocrModelManager.downloadState
    val ocrModelInfo: StateFlow<ModelInfo?> = ocrModelManager.modelInfo

    fun setAsrLanguage(lang: String) {
        _asrLanguage.value = lang
        prefs.edit().putString("asr_language", lang).apply()
    }

    fun downloadModel() {
        viewModelScope.launch {
            try {
                modelManager.downloadModel()
                _isModelReady.value = modelManager.isModelReady
            } catch (_: Exception) {}
        }
    }

    fun downloadVisionModel() {
        viewModelScope.launch {
            try {
                visionModelManager.downloadModel()
                _isVisionModelReady.value = visionModelManager.isModelReady
            } catch (_: Exception) {}
        }
    }

    fun downloadOcrModel() {
        viewModelScope.launch {
            try {
                ocrModelManager.downloadModel()
                _isOcrModelReady.value = ocrModelManager.isModelReady
            } catch (_: Exception) {}
        }
    }

    fun deleteModel() {
        File(modelManager.modelDir).deleteRecursively()
        _isModelReady.value = false
    }

    fun deleteVisionModel() {
        File(visionModelManager.modelDir).deleteRecursively()
        _isVisionModelReady.value = false
    }

    fun deleteOcrModel() {
        File(ocrModelManager.modelDir).deleteRecursively()
        _isOcrModelReady.value = false
    }

    fun clearAllChatHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val sessions = repository.chatSessions.firstOrNull() ?: return@launch
            for (session in sessions) {
                repository.deleteConversation(session.id)
            }
        }
    }

    fun addProvider(name: String, format: String, endpoint: String, defaultModel: String, sk: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.addProvider(name, format, endpoint, defaultModel, sk)
            _activeProviderId.value = repository.getActiveProviderId()
        }
    }

    fun updateProvider(id: Long, name: String, format: String, endpoint: String, defaultModel: String, sk: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateProvider(id, name, format, endpoint, defaultModel, sk)
        }
    }

    fun deleteProvider(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteProvider(id)
        }
    }

    fun setActiveProvider(id: Long) {
        repository.setActiveProvider(id)
        _activeProviderId.value = id
    }
}
