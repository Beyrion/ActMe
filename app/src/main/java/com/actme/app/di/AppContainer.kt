package com.actme.app.di

import android.content.Context
import com.actme.app.data.agent.ActMeAgent
import com.actme.app.data.local.ActMeDatabase
import com.actme.app.data.remote.BundledAuthManager
import com.actme.app.data.remote.OpenAiResponsesClient
import com.actme.app.data.repo.ActMeRepository
import com.actme.app.notifications.ReminderScheduler

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: ActMeDatabase = ActMeDatabase.getInstance(appContext)
    private val authManager = BundledAuthManager(appContext)
    private val openAiClient = OpenAiResponsesClient(authManager)
    private val agent = ActMeAgent(openAiClient)
    private val reminderScheduler = ReminderScheduler(appContext)

    val repository: ActMeRepository = ActMeRepository(
        chatDao = database.chatDao(),
        memoryDao = database.memoryDao(),
        scheduleDao = database.scheduleDao(),
        skillDao = database.skillDao(),
        agent = agent,
        reminderScheduler = reminderScheduler
    )
}
