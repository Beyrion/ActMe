package com.actme.app.skills

import android.content.Context
import com.actme.app.data.local.SkillDao
import com.actme.app.data.local.SkillEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class SkillSeedModel(
    val name: String,
    val description: String,
    val trigger_keywords: List<String>,
    val action_template: String
)

object SkillSeeder {
    private val json = Json { ignoreUnknownKeys = true }

    fun seedIfNeeded(context: Context, skillDao: SkillDao) {
        CoroutineScope(Dispatchers.IO).launch {
            if (skillDao.countAll() > 0) return@launch
            val content = runCatching {
                context.assets.open("skills/preload_skills.json").use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrElse { return@launch }
            val list = runCatching { json.decodeFromString<List<SkillSeedModel>>(content) }.getOrDefault(emptyList())
            list.forEach {
                skillDao.upsert(
                    SkillEntity(
                        name = it.name,
                        description = it.description,
                        triggerKeywords = Json.encodeToString(it.trigger_keywords),
                        actionTemplate = it.action_template,
                        enabled = true
                    )
                )
            }
        }
    }
}
