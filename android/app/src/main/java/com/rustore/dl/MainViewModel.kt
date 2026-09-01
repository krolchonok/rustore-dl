package com.rustore.dl

import android.content.Context
import android.util.Log
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

enum class MainTab {
    Search,
    History,
}

data class DownloadUiState(
    val packageName: String? = null,
    val status: String? = null,
    val savedPaths: List<String> = emptyList(),
    val recordId: String? = null,
    val error: String? = null,
)

data class AppListItem(
    val app: AppInfo,
    val isLoadingDetails: Boolean = false,
)

data class MainUiState(
    val selectedTab: MainTab = MainTab.Search,
    val query: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val results: List<AppListItem> = emptyList(),
    val download: DownloadUiState = DownloadUiState(),
    val history: List<DownloadRecord> = emptyList(),
    val message: String? = null,
)

class MainViewModel(
    private val client: RuStoreClient = RuStoreClient(),
    private val historyStore: DownloadHistoryStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    companion object {
        private const val TAG = "MainViewModel"
    }

    init {
        refreshHistory()
    }

    fun selectTab(tab: MainTab) {
        _uiState.update { it.copy(selectedTab = tab, message = null) }
        if (tab == MainTab.History) {
            refreshHistory()
        }
    }

    fun updateQuery(value: String) {
        _uiState.update { it.copy(query = value, error = null) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun search() {
        val query = _uiState.value.query.trim()
        if (query.isEmpty()) {
            _uiState.update { it.copy(error = "Enter a search query or package name") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, results = emptyList()) }
            try {
                val basic = withContext(Dispatchers.IO) { client.resolveQuery(query) }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        results = basic.map { app ->
                            AppListItem(
                                app = app,
                                isLoadingDetails = !app.hasRichDetails,
                            )
                        },
                        error = if (basic.isEmpty()) "No apps found" else null,
                    )
                }

                basic.filterNot { it.hasRichDetails }.forEach { app ->
                    launch(Dispatchers.IO) {
                        val enriched = runCatching { client.getAppInfo(app.packageName) }
                            .getOrNull()
                            ?.let { app.mergeDetails(it) }
                            ?: app
                        _uiState.update { state ->
                            state.copy(
                                results = state.results.map { item ->
                                    if (item.app.appId == app.appId) {
                                        AppListItem(enriched, isLoadingDetails = false)
                                    } else {
                                        item
                                    }
                                },
                            )
                        }
                    }
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = error.message ?: "Search failed",
                    )
                }
            }
        }
    }

    fun download(app: AppInfo, context: Context) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    download = DownloadUiState(
                        packageName = app.packageName,
                        status = "Resolving download links…",
                        savedPaths = emptyList(),
                        recordId = null,
                        error = null,
                    ),
                    message = null,
                )
            }

            try {
                val artifacts = withContext(Dispatchers.IO) { client.getDownloadArtifacts(app.appId) }
                val destinationDir = withContext(Dispatchers.IO) {
                    createDownloadDirectory(context, app.packageName)
                }

                val savedPaths = mutableListOf<String>()
                artifacts.forEachIndexed { index, artifact ->
                    val target = File(destinationDir, artifact.fileName)
                    _uiState.update {
                        it.copy(
                            download = it.download.copy(
                                status = "Downloading ${index + 1}/${artifacts.size}: ${artifact.fileName}",
                            ),
                        )
                    }
                    withContext(Dispatchers.IO) {
                        client.downloadToFile(artifact.url, target) { downloaded, total ->
                            val label = if (total != null && total > 0) {
                                val percent = ((downloaded * 100) / total).toInt()
                                "Downloading ${index + 1}/${artifacts.size}: $percent%"
                            } else {
                                "Downloading ${index + 1}/${artifacts.size}: ${downloaded / (1024 * 1024)} MB"
                            }
                            _uiState.update { state ->
                                state.copy(download = state.download.copy(status = label))
                            }
                        }
                    }
                    savedPaths += target.absolutePath
                }

                val record = DownloadRecord(
                    id = historyStore.createRecordId(),
                    packageName = app.packageName,
                    appName = app.appName,
                    versionName = app.versionName,
                    versionCode = app.versionCode,
                    downloadedAt = System.currentTimeMillis(),
                    directoryPath = destinationDir.absolutePath,
                    apkFiles = savedPaths,
                    isSplit = savedPaths.size > 1,
                )
                historyStore.add(record)
                refreshHistory()

                _uiState.update {
                    it.copy(
                        download = DownloadUiState(
                            packageName = app.packageName,
                            status = "Saved",
                            savedPaths = savedPaths,
                            recordId = record.id,
                            error = null,
                        ),
                        message = "Downloaded ${app.appName}",
                    )
                }
            } catch (error: Exception) {
                Log.e(TAG, "Download failed for ${app.packageName}", error)
                _uiState.update {
                    it.copy(
                        download = DownloadUiState(
                            packageName = app.packageName,
                            status = null,
                            savedPaths = emptyList(),
                            recordId = null,
                            error = error.message ?: "Download failed",
                        ),
                    )
                }
            }
        }
    }

    fun install(record: DownloadRecord, context: Context) {
        val files = record.apkFiles.map(::File)
        when (val result = ApkInstaller.install(context, files)) {
            InstallResult.Started -> {
                _uiState.update { it.copy(message = "Opening installer for ${record.appName}") }
            }
            InstallResult.NeedsPermission -> {
                _uiState.update {
                    it.copy(message = "Allow installs from this app in system settings")
                }
                context.startActivity(ApkInstaller.createInstallPermissionIntent(context))
            }
            is InstallResult.Failure -> {
                _uiState.update { it.copy(message = result.message) }
            }
        }
    }

    fun installLatestDownload(context: Context) {
        val download = _uiState.value.download
        val recordId = download.recordId ?: return
        val record = _uiState.value.history.firstOrNull { it.id == recordId }
            ?: DownloadRecord(
                id = recordId,
                packageName = download.packageName.orEmpty(),
                appName = download.packageName.orEmpty(),
                downloadedAt = System.currentTimeMillis(),
                directoryPath = File(download.savedPaths.first()).parent.orEmpty(),
                apkFiles = download.savedPaths,
                isSplit = download.savedPaths.size > 1,
            )
        install(record, context)
    }

    fun deleteHistoryRecord(recordId: String) {
        viewModelScope.launch {
            historyStore.remove(recordId)
            refreshHistory()
            _uiState.update { it.copy(message = "Removed from history") }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            historyStore.clear()
            refreshHistory()
            _uiState.update { it.copy(message = "History cleared") }
        }
    }

    fun formatTimestamp(timestamp: Long): String {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(timestamp))
    }

    private fun refreshHistory() {
        viewModelScope.launch {
            val records = historyStore.load()
            _uiState.update { it.copy(history = records) }
        }
    }

    private fun createDownloadDirectory(context: Context, packageName: String): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val directory = File(root, packageName)
        if (directory.exists()) {
            directory.listFiles()?.forEach { it.delete() }
        } else {
            directory.mkdirs()
        }
        return directory
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(historyStore = DownloadHistoryStore(context)) as T
        }
    }
}
