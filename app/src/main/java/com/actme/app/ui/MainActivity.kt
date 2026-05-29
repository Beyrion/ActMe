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
import androidx.compose.runtime.getValue
import com.actme.app.ActMeApp
import com.actme.app.ui.chat.ChatScreen
import com.actme.app.ui.chat.ChatViewModel
import com.actme.app.ui.chat.MenuScreen
import com.actme.app.ui.memory.MemoryCategoryScreen
import com.actme.app.ui.memory.MemoryItemScreen
import com.actme.app.ui.memory.MemoryListScreen
import com.actme.app.ui.memory.MemoryViewModel
import com.actme.app.ui.schedule.ScheduleScreen
import com.actme.app.ui.schedule.ScheduleViewModel
import com.actme.app.ui.settings.SettingsScreen
import com.actme.app.ui.settings.SettingsViewModel
import com.actme.app.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    private val viewModelFactory by lazy {
        val app = application as ActMeApp
        AppViewModelFactory(app.container.repository)
    }

    private val chatViewModel: ChatViewModel by viewModels { viewModelFactory }
    private val memoryViewModel: MemoryViewModel by viewModels { viewModelFactory }
    private val scheduleViewModel: ScheduleViewModel by viewModels { viewModelFactory }
    private val settingsViewModel: SettingsViewModel by viewModels { viewModelFactory }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        setContent {
            AppTheme {
                val navController = rememberNavController()

                NavHost(
                    navController = navController,
                    startDestination = "chat"
                ) {
                    composable("chat") {
                        val messages by chatViewModel.messages.collectAsStateWithLifecycle(emptyList())
                        val isRecording by chatViewModel.isRecording.collectAsStateWithLifecycle(false)
                        val availableModels by chatViewModel.availableModels.collectAsStateWithLifecycle(emptyList())
                        val selectedModel by chatViewModel.selectedModel.collectAsStateWithLifecycle("")
                        val sendingConversationId by chatViewModel.sendingConversationId.collectAsStateWithLifecycle(null)
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
                            onNavigateToMenu = { navController.navigate("menu") }
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
                            onNavigateToSchedule = { navController.navigate("schedule") },
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
                    composable("schedule") {
                        val schedules by scheduleViewModel.schedules.collectAsStateWithLifecycle(emptyList())
                        ScheduleScreen(
                            schedules = schedules,
                            onAddManual = scheduleViewModel::addManualSchedule,
                            onDeleteSchedule = scheduleViewModel::deleteSchedule,
                            onAddBySubAgent = scheduleViewModel::addScheduleBySubAgent,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("settings") {
                        val providers by settingsViewModel.providers.collectAsStateWithLifecycle(emptyList())
                        val activeProviderId by settingsViewModel.activeProviderId.collectAsStateWithLifecycle(-1L)
                        SettingsScreen(
                            providers = providers,
                            activeProviderId = activeProviderId,
                            onClearChatHistory = settingsViewModel::clearAllChatHistory,
                            onAddProvider = settingsViewModel::addProvider,
                            onUpdateProvider = settingsViewModel::updateProvider,
                            onDeleteProvider = settingsViewModel::deleteProvider,
                            onSetActiveProvider = settingsViewModel::setActiveProvider,
                            onBack = { navController.popBackStack() }
                        )
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
