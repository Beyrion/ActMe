package com.actme.app.di

import android.content.Context
import com.actme.app.data.agent.ActMeAgent
import com.actme.app.data.local.ActMeDatabase
import com.actme.app.data.remote.OpenAiResponsesClient
import com.actme.app.data.remote.ProviderManager
import com.actme.app.data.repo.ActMeRepository
import com.actme.app.notifications.ReminderScheduler
import com.actme.app.plugins.PluginAlarmManager
import com.actme.app.plugins.PluginRegistry
import com.actme.app.plugins.PluginRuntimeManager
import com.actme.app.plugins.SystemToolRegistry

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: ActMeDatabase = ActMeDatabase.getInstance(appContext)
    val providerManager = ProviderManager(appContext, database.providerDao())
    private val openAiClient = OpenAiResponsesClient()
    private val agent = ActMeAgent(openAiClient)
    val reminderScheduler = ReminderScheduler(appContext)

    val pluginAlarmManager = PluginAlarmManager(appContext, database.pluginAlarmDao())

    val pluginRegistry = PluginRegistry()

    val systemToolRegistry = SystemToolRegistry()

    val pluginRuntimeManager = PluginRuntimeManager(
        context = appContext,
        pluginDao = database.pluginDao(),
        pluginAlarmManager = pluginAlarmManager,
        pluginRegistry = pluginRegistry
    )

    val repository: ActMeRepository = ActMeRepository(
        chatDao = database.chatDao(),
        memoryDao = database.memoryDao(),
        scheduleDao = database.scheduleDao(),
        skillDao = database.skillDao(),
        agent = agent,
        reminderScheduler = reminderScheduler,
        providerManager = providerManager,
        openAiClient = openAiClient,
        pluginRegistry = pluginRegistry,
        systemToolRegistry = systemToolRegistry
    )

    suspend fun initPlugins(context: Context) {
        com.actme.app.plugins.PluginSeeder.seedBuiltins(context, database.pluginDao())
        pluginRegistry.loadFromDb(
            pluginDao = database.pluginDao(),
            runtimeManager = pluginRuntimeManager
        )
    }
}
