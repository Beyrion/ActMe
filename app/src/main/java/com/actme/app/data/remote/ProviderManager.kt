package com.actme.app.data.remote

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.actme.app.data.local.ProviderDao
import com.actme.app.data.local.ProviderEntity
import kotlinx.coroutines.flow.Flow

class ProviderManager(private val context: Context, private val providerDao: ProviderDao) {

    private val securePrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "actme_provider_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val appPrefs by lazy {
        context.getSharedPreferences("actme_provider_state", Context.MODE_PRIVATE)
    }

    val providers: Flow<List<ProviderEntity>> = providerDao.observeAll()

    suspend fun getAllProviders(): List<ProviderEntity> = providerDao.getAll()

    fun getSk(providerId: Long): String {
        return securePrefs.getString("sk_$providerId", "") ?: ""
    }

    fun setSk(providerId: Long, sk: String) {
        securePrefs.edit().putString("sk_$providerId", sk).apply()
    }

    fun deleteSk(providerId: Long) {
        securePrefs.edit().remove("sk_$providerId").apply()
    }

    fun getActiveProviderId(): Long {
        return appPrefs.getLong("active_provider_id", -1L)
    }

    fun setActiveProviderId(id: Long) {
        appPrefs.edit().putLong("active_provider_id", id).apply()
    }

    suspend fun getActiveProvider(): ProviderEntity? {
        val id = getActiveProviderId()
        if (id > 0) {
            providerDao.getById(id)?.let { return it }
        }
        return providerDao.getAll().firstOrNull()?.also {
            setActiveProviderId(it.id)
        }
    }

    fun getLastModel(providerId: Long): String {
        return appPrefs.getString("last_model_$providerId", "") ?: ""
    }

    fun setLastModel(providerId: Long, model: String) {
        appPrefs.edit().putString("last_model_$providerId", model).apply()
    }

    suspend fun addProvider(name: String, format: String, endpoint: String, sk: String): Long {
        val id = providerDao.insert(
            ProviderEntity(
                name = name,
                providerFormat = format,
                endpoint = endpoint
            )
        )
        setSk(id, sk)
        if (getActiveProviderId() < 0) {
            setActiveProviderId(id)
        }
        return id
    }

    suspend fun updateProvider(id: Long, name: String, format: String, endpoint: String, sk: String) {
        providerDao.update(
            ProviderEntity(
                id = id,
                name = name,
                providerFormat = format,
                endpoint = endpoint
            )
        )
        if (sk.isNotBlank()) {
            setSk(id, sk)
        }
    }

    suspend fun deleteProvider(id: Long) {
        deleteSk(id)
        providerDao.deleteById(id)
        if (getActiveProviderId() == id) {
            val next = providerDao.getAll().firstOrNull()
            setActiveProviderId(next?.id ?: -1L)
        }
    }
}
