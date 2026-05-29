package com.actme.app.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.actme.app.ActMeApp
import com.actme.app.ui.chat.ChatScreen
import com.actme.app.ui.chat.ChatViewModel
import com.actme.app.ui.memory.MemoryCategoryScreen
import com.actme.app.ui.memory.MemoryItemScreen
import com.actme.app.ui.memory.MemoryListScreen
import com.actme.app.ui.memory.MemoryViewModel
import com.actme.app.ui.schedule.ScheduleScreen
import com.actme.app.ui.schedule.ScheduleViewModel
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModelFactory by lazy {
        val app = application as ActMeApp
        AppViewModelFactory(app.container.repository)
    }

    private val chatViewModel: ChatViewModel by viewModels { viewModelFactory }
    private val memoryViewModel: MemoryViewModel by viewModels { viewModelFactory }
    private val scheduleViewModel: ScheduleViewModel by viewModels { viewModelFactory }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        setContent {
            val navController = rememberNavController()
            val tabs = listOf(
                BottomTab("chat", "聊天", "聊"),
                BottomTab("memory", "记忆", "忆"),
                BottomTab("schedule", "日程", "程")
            )
            val backStack by navController.currentBackStackEntryAsState()
            val current = backStack?.destination

            Scaffold(
                bottomBar = {
                    NavigationBar {
                        tabs.forEach { tab ->
                            val selected = when (tab.route) {
                                "memory" -> current?.route?.startsWith("memory") == true
                                else -> current?.hierarchy?.any { it.route == tab.route } == true
                            }
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        launchSingleTop = true
                                        restoreState = true
                                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    }
                                },
                                icon = { Text(tab.emoji) },
                                label = { Text(tab.label) }
                            )
                        }
                    }
                }
            ) { padding ->
                NavHost(
                    navController = navController,
                    startDestination = "chat",
                    modifier = Modifier.padding(padding)
                ) {
                    composable("chat") {
                        val sessions by chatViewModel.sessions.collectAsStateWithLifecycle(emptyList())
                        val currentConversationId by chatViewModel.currentConversationId.collectAsStateWithLifecycle(null)
                        val messages by chatViewModel.messages.collectAsStateWithLifecycle(emptyList())
                        val isRecording by chatViewModel.isRecording.collectAsStateWithLifecycle(false)
                        ChatScreen(
                            sessions = sessions,
                            currentConversationId = currentConversationId,
                            messages = messages,
                            onCreateConversation = chatViewModel::createNewConversation,
                            onSwitchConversation = chatViewModel::switchConversation,
                            onRenameConversation = chatViewModel::renameConversation,
                            onDeleteConversation = chatViewModel::deleteConversation,
                            onSend = { text, imgBase64, imgMime -> chatViewModel.sendMessage(text, imgBase64, imgMime) },
                            sending = chatViewModel.sending.collectAsStateWithLifecycle(false).value,
                            isRecording = isRecording,
                            onStartRecording = { chatViewModel.setRecording(true) },
                            onStopRecording = { chatViewModel.setRecording(false) }
                        )
                    }
                    composable("memory") {
                        MemoryCategoryScreen(
                            categories = memoryViewModel.categories,
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
                            onAddBySubAgent = scheduleViewModel::addScheduleBySubAgent
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

private data class BottomTab(
    val route: String,
    val label: String,
    val emoji: String
)
