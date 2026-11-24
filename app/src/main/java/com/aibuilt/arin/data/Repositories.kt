package com.aibuilt.arin.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private val Context.dataStore by preferencesDataStore(name = "arin_settings")

@Serializable
data class RepairRecord(
    val id: Long,
    val atEpochMs: Long,
    val round: Int,
    val level: String, // info | success | warn | error
    val message: String
)

@Serializable
data class BuildRecord(
    val id: Long,
    val owner: String,
    val repo: String,
    val atEpochMs: Long,
    val success: Boolean,
    val summary: String
)

class Repositories(context: Context) {

    private val appContext = context.applicationContext
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val tokenKey = stringPreferencesKey("github_token")

    val tokenFlow: Flow<String> =
        appContext.dataStore.data.map { prefs -> prefs[tokenKey].orEmpty() }

    suspend fun saveToken(value: String) {
        appContext.dataStore.edit { it[tokenKey] = value.trim() }
    }

    suspend fun tokenBlocking(): String = tokenFlow.first()

    private val repairsFile: File get() = File(appContext.filesDir, "repair_history.json")
    private val buildsFile: File get() = File(appContext.filesDir, "build_history.json")

    suspend fun loadRepairs(): List<RepairRecord> = withContext(Dispatchers.IO) {
        runCatching {
            json.decodeFromString(ListSerializer(RepairRecord.serializer()), repairsFile.readText())
        }.getOrDefault(emptyList())
    }

    suspend fun appendRepair(level: String, message: String, round: Int = 0): RepairRecord {
        val current = loadRepairs()
        val record = RepairRecord(
            id = (current.maxOfOrNull { it.id } ?: 0L) + 1L,
            atEpochMs = System.currentTimeMillis(),
            round = round,
            level = level,
            message = message
        )
        saveRepairs((listOf(record) + current).take(MAX_REPAIRS))
        return record
    }

    suspend fun saveRepairs(list: List<RepairRecord>) = withContext(Dispatchers.IO) {
        repairsFile.writeText(json.encodeToString(ListSerializer(RepairRecord.serializer()), list))
    }

    suspend fun loadBuilds(): List<BuildRecord> = withContext(Dispatchers.IO) {
        runCatching {
            json.decodeFromString(ListSerializer(BuildRecord.serializer()), buildsFile.readText())
        }.getOrDefault(emptyList())
    }

    suspend fun appendBuild(owner: String, repo: String, success: Boolean, summary: String): BuildRecord {
        val current = loadBuilds()
        val record = BuildRecord(
            id = (current.maxOfOrNull { it.id } ?: 0L) + 1L,
            owner = owner,
            repo = repo,
            atEpochMs = System.currentTimeMillis(),
            success = success,
            summary = summary
        )
        saveBuilds((listOf(record) + current).take(MAX_BUILDS))
        return record
    }

    suspend fun saveBuilds(list: List<BuildRecord>) = withContext(Dispatchers.IO) {
        buildsFile.writeText(json.encodeToString(ListSerializer(BuildRecord.serializer()), list))
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        repairsFile.delete()
        buildsFile.delete()
    }

    companion object {
        const val MAX_REPAIRS = 500
        const val MAX_BUILDS = 100
    }
}
