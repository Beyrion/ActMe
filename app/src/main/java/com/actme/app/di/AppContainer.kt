package com.actme.app.di

import android.content.Context
import com.actme.app.data.agent.ActMeAgent
import com.actme.app.data.agent.AdbSkillEngine
import com.actme.app.data.agent.GeckoSearchEngine
import com.actme.app.data.agent.PythonSkillEngine
import com.actme.app.data.local.ActMeDatabase
import com.actme.app.data.remote.OpenAiResponsesClient
import com.actme.app.data.remote.ProviderManager
import com.actme.app.data.repo.ActMeRepository
import com.actme.app.notifications.ReminderScheduler

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    init {
        GeckoSearchEngine.initialize(appContext)
        PythonSkillEngine.initialize(appContext)
        AdbSkillEngine.initialize(appContext)
    }

    val database: ActMeDatabase = ActMeDatabase.getInstance(appContext)
    val providerManager = ProviderManager(appContext, database.providerDao())
    private val openAiClient = OpenAiResponsesClient()
    private val agent = ActMeAgent(openAiClient)
    private val reminderScheduler = ReminderScheduler(appContext)

    val repository: ActMeRepository = ActMeRepository(
        chatDao = database.chatDao(),
        memoryDao = database.memoryDao(),
        scheduleDao = database.scheduleDao(),
        skillDao = database.skillDao(),
        agent = agent,
        reminderScheduler = reminderScheduler,
        providerManager = providerManager,
        openAiClient = openAiClient
    )
}
