package com.actme.app.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import com.actme.app.ActMeApp
import com.actme.app.ui.chat.ChatScreen
import com.actme.app.ui.chat.ChatViewModel
import com.actme.app.ui.chat.MenuScreen
import com.actme.app.ui.memory.MemoryCategoryScreen
import com.actme.app.ui.memory.MemoryItemScreen
import com.actme.app.ui.memory.MemoryListScreen
import com.actme.app.ui.memory.MemoryViewModel
import com.actme.app.ui.plugins.PluginListScreen
import com.actme.app.ui.plugins.PluginManagementScreen
import com.actme.app.ui.plugins.PluginSheet
import com.actme.app.ui.settings.LogViewerScreen
import com.actme.app.ui.settings.SettingsScreen
import com.actme.app.mnn.DownloadState
import com.actme.app.ui.settings.SettingsViewModel
import com.actme.app.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    private val viewModelFactory by lazy {
        val app = application as ActMeApp
        AppViewModelFactory(app, app.container.repository)
    }

    private val app by lazy { application as ActMeApp }

    private val chatViewModel: ChatViewModel by viewModels { viewModelFactory }
    private val memoryViewModel: MemoryViewModel by viewModels { viewModelFactory }
    private val settingsViewModel: SettingsViewModel by viewModels { viewModelFactory }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        setContent {
            AppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    var pluginSheetId by remember { mutableStateOf<String?>(null) }

                    NavHost(navController = navController, startDestination = "chat") {
                        composable("chat") {
                            val messages by chatViewModel.messages.collectAsStateWithLifecycle(emptyList())
                            val isRecording by chatViewModel.isRecording.collectAsStateWithLifecycle(false)
                            val availableModels by chatViewModel.availableModels.collectAsStateWithLifecycle(emptyList())
                            val selectedModel by chatViewModel.selectedModel.collectAsStateWithLifecycle("")
                            val sendingConversationId by chatViewModel.sendingConversationId.collectAsStateWithLifecycle(null)
                            val asrLanguage by settingsViewModel.asrLanguage.collectAsStateWithLifecycle("Chinese")
                            val isModelReady by settingsViewModel.isModelReady.collectAsStateWithLifecycle(false)
                            ChatScreen(
                                messages = messages,
                                onSend = { text, imgBase64, imgMime -> chatViewModel.sendMessage(text, imgBase64, imgMime) },
                                sendingConversationId = sendingConversationId,
                                isRecording = isRecording,
                                onStartRecording = { chatViewModel.setRecording(true) },
                                onStopRecording = { chatViewModel.setRecording(false) },
                                availableModels = availableModels,
                                selectedModel = selectedModel,
                                onSelectModel = chatViewModel::selectModel,
                                asrLanguage = asrLanguage,
                                isModelReady = isModelReady,
                                onNavigateToMenu = { navController.navigate("menu") },
                                onNavigateToPlugin = { route ->
                                    pluginSheetId = route.removePrefix("plugin/").ifBlank { null }
                                },
                                onSaveCardHeight = { msgId, heightDp ->
                                    chatViewModel.saveCardHeight(msgId, heightDp)
                                },
                                isPluginAvailable = { pluginId ->
                                    app.container.pluginRegistry.get(pluginId) != null
                                }
                            )
                        }
                        composable("menu") {
                            val sessionInfos by chatViewModel.sessionInfos.collectAsStateWithLifecycle(emptyList())
                            val currentConversationId by chatViewModel.currentConversationId.collectAsStateWithLifecycle(null)
                            val sendingConversationId by chatViewModel.sendingConversationId.collectAsStateWithLifecycle(null)
                            MenuScreen(
                                sessionInfos = sessionInfos,
                                currentConversationId = currentConversationId,
                                sendingConversationId = sendingConversationId,
                                onCreateConversation = {
                                    chatViewModel.createNewConversation()
                                    navController.popBackStack("chat", false)
                                },
                                onSwitchConversation = { id ->
                                    chatViewModel.switchConversation(id)
                                    navController.popBackStack("chat", false)
                                },
                                onRenameConversation = chatViewModel::renameConversation,
                                onDeleteConversation = chatViewModel::deleteConversation,
                                onNavigateToMemory = { navController.navigate("memory") },
                                onNavigateToPlugins = { navController.navigate("plugins") },
                                onNavigateToSettings = { navController.navigate("settings") }
                            )
                        }
                        composable("memory") {
                            MemoryCategoryScreen(
                                categories = memoryViewModel.categories,
                                onBack = { navController.popBackStack() },
                                onOpenCategory = { category ->
                                    navController.navigate("memory/${Uri.encode(category)}")
                                }
                            )
                        }
                        composable(
                            route = "memory/{category}",
                            arguments = listOf(navArgument("category") { type = NavType.StringType })
                        ) { backEntry ->
                            val category = Uri.decode(backEntry.arguments?.getString("category").orEmpty())
                            val items by memoryViewModel.observeCategory(category)
                                .collectAsStateWithLifecycle(initialValue = emptyList())
                            MemoryListScreen(
                                category = category,
                                items = items,
                                onBack = { navController.popBackStack() },
                                onSaveNew = { content -> memoryViewModel.saveMemoryItem(0L, category, content) },
                                onOpenItem = { itemId ->
                                    navController.navigate("memory/${Uri.encode(category)}/item/$itemId")
                                }
                            )
                        }
                        composable(
                            route = "memory/{category}/item/{itemId}",
                            arguments = listOf(
                                navArgument("category") { type = NavType.StringType },
                                navArgument("itemId") { type = NavType.LongType }
                            )
                        ) { backEntry ->
                            val category = Uri.decode(backEntry.arguments?.getString("category").orEmpty())
                            val itemId = backEntry.arguments?.getLong("itemId") ?: 0L
                            val item by memoryViewModel.observeItem(itemId)
                                .collectAsStateWithLifecycle(initialValue = null)
                            MemoryItemScreen(
                                category = category,
                                item = item,
                                onBack = { navController.popBackStack() },
                                onSave = { id, content ->
                                    memoryViewModel.saveMemoryItem(id, category, content)
                                },
                                onDelete = { id ->
                                    memoryViewModel.deleteMemoryItem(id)
                                }
                            )
                        }
                        composable("plugins") {
                            val scope = rememberCoroutineScope()
                            PluginListScreen(
                                plugins = app.container.pluginRegistry.getAll(),
                                pluginDao = app.container.database.pluginDao(),
                                pluginAlarmManager = app.container.pluginAlarmManager,
                                onBack = { navController.popBackStack() },
                                onReloadPlugin = { pluginId ->
                                    scope.launch {
                                        app.container.pluginRegistry.reloadPlugin(
                                            pluginId,
                                            app.container.database.pluginDao(),
                                            app.container.pluginRuntimeManager
                                        )
                                    }
                                }
                            )
                        }
                        composable(
                            route = "plugin/{pluginId}",
                            arguments = listOf(navArgument("pluginId") { type = NavType.StringType })
                        ) { backEntry ->
                            val pluginId = Uri.decode(backEntry.arguments?.getString("pluginId").orEmpty())
                            val plugin = app.container.pluginRegistry.get(pluginId)
                            if (plugin != null) {
                                PluginManagementScreen(
                                    plugin = plugin,
                                    pluginDao = app.container.database.pluginDao(),
                                    pluginAlarmManager = app.container.pluginAlarmManager,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }
                        composable("settings") {
                            val providers by settingsViewModel.providers.collectAsStateWithLifecycle(emptyList())
                            val activeProviderId by settingsViewModel.activeProviderId.collectAsStateWithLifecycle(-1L)
                            val isModelReady by settingsViewModel.isModelReady.collectAsStateWithLifecycle(false)
                            val downloadState by settingsViewModel.downloadState.collectAsStateWithLifecycle(DownloadState.NotStarted)
                            val asrLanguage by settingsViewModel.asrLanguage.collectAsStateWithLifecycle("Chinese")
                            SettingsScreen(
                                providers = providers,
                                activeProviderId = activeProviderId,
                                isModelReady = isModelReady,
                                downloadState = downloadState,
                                asrLanguage = asrLanguage,
                                onSetAsrLanguage = settingsViewModel::setAsrLanguage,
                                onDownloadModel = settingsViewModel::downloadModel,
                                onDeleteModel = settingsViewModel::deleteModel,
                                onClearChatHistory = settingsViewModel::clearAllChatHistory,
                                onNavigateToLogs = { navController.navigate("logs") },
                                onAddProvider = settingsViewModel::addProvider,
                                onUpdateProvider = settingsViewModel::updateProvider,
                                onDeleteProvider = settingsViewModel::deleteProvider,
                                onSetActiveProvider = settingsViewModel::setActiveProvider,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable("logs") {
                            LogViewerScreen(onBack = { navController.popBackStack() })
                        }
                    }

                    // Plugin bottom sheet — triggered by tapping a plugin card in chat
                    if (pluginSheetId != null) {
                        val plugin = app.container.pluginRegistry.get(pluginSheetId!!)
                        if (plugin != null) {
                            PluginSheet(
                                plugin = plugin,
                                pluginDao = app.container.database.pluginDao(),
                                pluginAlarmManager = app.container.pluginAlarmManager,
                                onDismiss = { pluginSheetId = null }
                            )
                        } else {
                            pluginSheetId = null
                        }
                    }
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
