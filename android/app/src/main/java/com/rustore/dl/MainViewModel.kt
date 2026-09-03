package com.rustore.dl

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

enum class MainTab {
    Search,
    History,
    Updates,
    Pending,
}

data class DownloadUiState(
    val packageName: String? = null,
    val appName: String? = null,
    val status: String? = null,
    val progressPercent: Int? = null,
    val savedPaths: List<String> = emptyList(),
    val recordId: String? = null,
    val error: String? = null,
) {
    val isActive: Boolean
        get() = packageName != null && status != null && error == null && savedPaths.isEmpty()
}

data class AppListItem(
    val app: AppInfo,
    val isLoadingDetails: Boolean = false,
)

data class UpdateItem(
    val packageName: String,
    val installedAppName: String,
    val installedVersionName: String?,
    val installedVersionCode: Long,
    val latest: AppInfo,
) {
    val hasUpdate: Boolean
        get() = (latest.versionCode ?: 0L) > installedVersionCode
}

data class UpdatesUiState(
    val isChecking: Boolean = false,
    val checkedCount: Int = 0,
    val totalCount: Int = 0,
    val items: List<UpdateItem> = emptyList(),
    val lastCheckedAt: Long? = null,
)

data class MainUiState(
    val selectedTab: MainTab = MainTab.Search,
    val query: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val results: List<AppListItem> = emptyList(),
    val download: DownloadUiState = DownloadUiState(),
    val history: List<DownloadRecord> = emptyList(),
    val pendingInstalls: List<DownloadRecord> = emptyList(),
    val updates: UpdatesUiState = UpdatesUiState(),
    val message: String? = null,
)

class MainViewModel(
    private val client: RuStoreClient = RuStoreClient(),
    private val historyStore: DownloadHistoryStore,
    private val appContext: Context,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    companion object {
        private const val TAG = "MainViewModel"
    }

    init {
        refreshHistory()
        viewModelScope.launch {
            DownloadEventBus.events.collect { event -> handleDownloadEvent(event) }
        }
    }

    private fun handleDownloadEvent(event: DownloadEvent) {
        when (event) {
            is DownloadEvent.Progress -> {
                if (_uiState.value.download.packageName == event.packageName) {
                    _uiState.update {
                        it.copy(
                            download = it.download.copy(
                                status = event.status,
                                progressPercent = event.percent,
                            ),
                        )
                    }
                }
            }
            is DownloadEvent.Completed -> {
                refreshHistory()
                if (_uiState.value.download.packageName == event.packageName) {
                    _uiState.update {
                        it.copy(
                            download = DownloadUiState(
                                packageName = event.packageName,
                                appName = event.appName,
                                status = "Saved",
                                savedPaths = event.savedPaths,
                                recordId = event.recordId,
                                error = null,
                            ),
                            message = "Downloaded ${event.appName}",
                        )
                    }
                }
            }
            is DownloadEvent.Failed -> {
                if (_uiState.value.download.packageName == event.packageName) {
                    _uiState.update {
                        it.copy(
                            download = it.download.copy(status = null, error = event.message),
                        )
                    }
                }
            }
        }
    }

    fun selectTab(tab: MainTab) {
        _uiState.update { it.copy(selectedTab = tab, message = null) }
        if (tab == MainTab.History || tab == MainTab.Pending) {
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

    fun checkForUpdates(context: Context) {
        if (_uiState.value.updates.isChecking) {
            return
        }

        viewModelScope.launch {
            val installed = withContext(Dispatchers.IO) { InstalledApps.listUpdatable(context) }
            _uiState.update {
                it.copy(
                    updates = it.updates.copy(
                        isChecking = true,
                        checkedCount = 0,
                        totalCount = installed.size,
                        items = emptyList(),
                    ),
                )
            }

            val semaphore = Semaphore(4)
            val results = withContext(Dispatchers.IO) {
                installed.map { app ->
                    async {
                        val latest = semaphore.withPermit {
                            runCatching { client.getAppInfo(app.packageName) }.getOrNull()
                        }
                        _uiState.update { state ->
                            state.copy(
                                updates = state.updates.copy(
                                    checkedCount = state.updates.checkedCount + 1,
                                ),
                            )
                        }
                        latest?.let { info ->
                            UpdateItem(
                                packageName = app.packageName,
                                installedAppName = app.appName,
                                installedVersionName = app.versionName,
                                installedVersionCode = app.versionCode,
                                latest = info,
                            )
                        }
                    }
                }.awaitAll()
            }

            val withUpdates = results.filterNotNull()
                .filter { it.hasUpdate }
                .sortedBy { it.installedAppName.lowercase() }

            _uiState.update {
                it.copy(
                    updates = it.updates.copy(
                        isChecking = false,
                        items = withUpdates,
                        lastCheckedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    fun download(app: AppInfo, context: Context) {
        _uiState.update {
            it.copy(
                download = DownloadUiState(
                    packageName = app.packageName,
                    appName = app.appName,
                    status = "Подготовка…",
                    progressPercent = null,
                    savedPaths = emptyList(),
                    recordId = null,
                    error = null,
                ),
                message = null,
            )
        }
        // Runs in a foreground service (with a progress notification) so Android
        // doesn't kill the download if the app is backgrounded or swiped away.
        DownloadService.start(context.applicationContext, app)
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
            val pending = withContext(Dispatchers.IO) { records.filter(::isPendingInstall) }
            _uiState.update { it.copy(history = records, pendingInstalls = pending) }
        }
    }

    private fun isPendingInstall(record: DownloadRecord): Boolean {
        val packageManager = appContext.packageManager
        val pkgInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(record.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(record.packageName, 0)
            }
        } catch (error: PackageManager.NameNotFoundException) {
            return true
        }
        val installedVersionCode = PackageInfoCompat.getLongVersionCode(pkgInfo)
        val recordVersionCode = record.versionCode ?: return true
        return installedVersionCode < recordVersionCode
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(
                historyStore = DownloadHistoryStore(context),
                appContext = context.applicationContext,
            ) as T
        }
    }
}
