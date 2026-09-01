package com.rustore.dl

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class DownloadHistoryStore(context: Context) {
    private val appContext = context.applicationContext
    private val historyFile = File(appContext.filesDir, "download_history.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun load(): List<DownloadRecord> = withContext(Dispatchers.IO) {
        if (!historyFile.exists()) {
            return@withContext emptyList()
        }
        runCatching {
            json.decodeFromString<List<DownloadRecord>>(historyFile.readText())
        }.getOrElse { emptyList() }
            .filter { record ->
                record.apkFiles.isNotEmpty() &&
                    File(record.directoryPath).exists() &&
                    record.apkFiles.all { File(it).exists() }
            }
    }

    suspend fun add(record: DownloadRecord) = withContext(Dispatchers.IO) {
        val current = load().filterNot { it.packageName == record.packageName }
        save(listOf(record) + current)
    }

    suspend fun remove(recordId: String) = withContext(Dispatchers.IO) {
        val current = load()
        val target = current.firstOrNull { it.id == recordId }
        target?.let { deleteFiles(it) }
        save(current.filterNot { it.id == recordId })
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        load().forEach { deleteFiles(it) }
        if (historyFile.exists()) {
            historyFile.delete()
        }
    }

    fun createRecordId(): String = UUID.randomUUID().toString()

    private suspend fun save(records: List<DownloadRecord>) {
        historyFile.writeText(json.encodeToString(records))
    }

    private fun deleteFiles(record: DownloadRecord) {
        record.apkFiles.forEach { path ->
            runCatching { File(path).delete() }
        }
        runCatching {
            val directory = File(record.directoryPath)
            if (directory.exists() && directory.listFiles()?.isEmpty() != false) {
                directory.delete()
            }
        }
    }
}
