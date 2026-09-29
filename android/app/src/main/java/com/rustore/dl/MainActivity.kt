package com.rustore.dl

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

private val RuStoreBlue = Color(0xFF0077FF)

@Composable
private fun ruStoreColorScheme() = darkColorScheme(
    primary = RuStoreBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF004899),
    secondary = Color(0xFF5DAEFF),
)

@Composable
private fun GlobalDownloadBar(
    download: DownloadUiState,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = download.appName ?: download.packageName.orEmpty(),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                download.progressPercent?.let { percent ->
                    Text(
                        text = "$percent%",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            download.status?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            val percent = download.progressPercent
            if (percent != null) {
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun BadgedIcon(icon: ImageVector, count: Int) {
    if (count > 0) {
        BadgedBox(badge = { Badge { Text(count.toString()) } }) {
            Icon(icon, contentDescription = null)
        }
    } else {
        Icon(icon, contentDescription = null)
    }
}

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coil.Coil.setImageLoader(
            coil.ImageLoader.Builder(this)
                .okHttpClient(RuStoreClient.defaultHttpClient())
                .build()
        )
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        setContent {
            MaterialTheme(colorScheme = ruStoreColorScheme()) {
                MainScreen()
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        // The app is now visible to the user, so the lingering "download complete"
        // notification is redundant — dismiss it (but never touch an in-progress
        // download's notification, which backs the foreground service).
        val viewModel = ViewModelProvider(this, MainViewModel.Factory(this))[MainViewModel::class.java]
        if (!viewModel.uiState.value.download.isActive) {
            NotificationManagerCompat.from(this).cancel(DownloadService.NOTIFICATION_ID)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val viewModel: MainViewModel = viewModel(factory = MainViewModel.Factory(context))
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var showClearHistoryDialog by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, snackbarHostState) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                snackbarHostState.currentSnackbarData?.dismiss()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text("Clear history?") },
            text = { Text("This removes saved APK files from app storage.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearHistoryDialog = false
                        viewModel.clearHistory()
                    },
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("RuStore DL")
                        Text(
                            text = "Download without the store app",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (uiState.selectedTab == MainTab.History && uiState.history.isNotEmpty()) {
                        IconButton(onClick = { showClearHistoryDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear history")
                        }
                    }
                    if (uiState.selectedTab == MainTab.Updates) {
                        IconButton(
                            onClick = { viewModel.checkForUpdates(context.applicationContext) },
                            enabled = !uiState.updates.isChecking,
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Check for updates")
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column {
                if (uiState.download.isActive) {
                    GlobalDownloadBar(download = uiState.download)
                }
                NavigationBar {
                    NavigationBarItem(
                        selected = uiState.selectedTab == MainTab.Search,
                        onClick = { viewModel.selectTab(MainTab.Search) },
                        icon = { Icon(Icons.Default.Search, contentDescription = null) },
                        label = { Text("Search") },
                    )
                    NavigationBarItem(
                        selected = uiState.selectedTab == MainTab.History,
                        onClick = { viewModel.selectTab(MainTab.History) },
                        icon = { Icon(Icons.Default.History, contentDescription = null) },
                        label = { Text("History") },
                    )
                    NavigationBarItem(
                        selected = uiState.selectedTab == MainTab.Updates,
                        onClick = { viewModel.selectTab(MainTab.Updates) },
                        icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
                        label = { Text("Updates") },
                    )
                    NavigationBarItem(
                        selected = uiState.selectedTab == MainTab.Pending,
                        onClick = { viewModel.selectTab(MainTab.Pending) },
                        icon = {
                            BadgedIcon(
                                icon = Icons.Default.InstallMobile,
                                count = uiState.pendingInstalls.size,
                            )
                        },
                        label = { Text("Pending") },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (uiState.selectedTab) {
            MainTab.Search -> SearchTab(
                modifier = Modifier.padding(padding),
                uiState = uiState,
                viewModel = viewModel,
            )
            MainTab.History -> HistoryTab(
                modifier = Modifier.padding(padding),
                uiState = uiState,
                viewModel = viewModel,
            )
            MainTab.Updates -> UpdatesTab(
                modifier = Modifier.padding(padding),
                uiState = uiState,
                viewModel = viewModel,
            )
            MainTab.Pending -> PendingTab(
                modifier = Modifier.padding(padding),
                uiState = uiState,
                viewModel = viewModel,
            )
        }
    }
}

@Composable
private fun SearchTab(
    modifier: Modifier = Modifier,
    uiState: MainUiState,
    viewModel: MainViewModel,
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                modifier = Modifier.weight(1f),
                value = uiState.query,
                onValueChange = viewModel::updateQuery,
                label = { Text("App name, package, or RuStore URL") },
                singleLine = true,
            )
            IconButton(onClick = viewModel::search) {
                Icon(Icons.Default.Search, contentDescription = "Search")
            }
        }

        if (uiState.isLoading) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        uiState.error?.let { message ->
            Text(text = message, color = MaterialTheme.colorScheme.error)
        }

        uiState.download.status?.let { status ->
            Text(text = "${uiState.download.packageName}: $status")
        }
        uiState.download.error?.let { message ->
            Text(text = message, color = MaterialTheme.colorScheme.error)
        }
        if (uiState.download.savedPaths.isNotEmpty()) {
            Text(
                text = "Saved ${uiState.download.savedPaths.size} file(s)",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.installLatestDownload(context) }) {
                    Icon(Icons.Default.InstallMobile, contentDescription = null)
                    Text("Install")
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(uiState.results, key = { it.app.appId }) { item ->
                AppCatalogCard(
                    app = item.app,
                    isDownloading = uiState.download.packageName == item.app.packageName &&
                        uiState.download.status != null &&
                        uiState.download.error == null,
                    isLoadingDetails = item.isLoadingDetails,
                    onDownload = { viewModel.download(item.app, context.applicationContext) },
                )
            }
        }
    }
}

@Composable
private fun HistoryTab(
    modifier: Modifier = Modifier,
    uiState: MainUiState,
    viewModel: MainViewModel,
) {
    val context = LocalContext.current

    if (uiState.history.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Пока нет загрузок")
            Text(
                text = "Найди приложение и скачай — оно появится здесь.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(uiState.history, key = { it.id }) { record ->
            HistoryCatalogCard(
                record = record,
                downloadedAt = viewModel.formatTimestamp(record.downloadedAt),
                onInstall = { viewModel.install(record, context) },
                onDelete = { viewModel.deleteHistoryRecord(record.id) },
            )
        }
    }
}

@Composable
private fun PendingTab(
    modifier: Modifier = Modifier,
    uiState: MainUiState,
    viewModel: MainViewModel,
) {
    val context = LocalContext.current

    if (uiState.pendingInstalls.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Нечего устанавливать")
            Text(
                text = "Все скачанные APK уже установлены.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(uiState.pendingInstalls, key = { it.id }) { record ->
            HistoryCatalogCard(
                record = record,
                downloadedAt = viewModel.formatTimestamp(record.downloadedAt),
                onInstall = { viewModel.install(record, context) },
                onDelete = { viewModel.deleteHistoryRecord(record.id) },
            )
        }
    }
}

@Composable
private fun UpdatesTab(
    modifier: Modifier = Modifier,
    uiState: MainUiState,
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    val updates = uiState.updates

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            updates.isChecking -> {
                val progress = if (updates.totalCount > 0) {
                    updates.checkedCount.toFloat() / updates.totalCount
                } else {
                    0f
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Проверено ${updates.checkedCount} из ${updates.totalCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            updates.lastCheckedAt == null -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Проверка обновлений")
                    Text(
                        text = "Сравним версии установленных приложений с RuStore.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        modifier = Modifier.padding(top = 12.dp),
                        onClick = { viewModel.checkForUpdates(context.applicationContext) },
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Text("Проверить обновления")
                    }
                }
            }
            updates.items.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Все приложения обновлены")
                    Text(
                        text = "Обновлений через RuStore не найдено.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                Text(
                    text = "Доступно обновлений: ${updates.items.size}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(updates.items, key = { it.packageName }) { item ->
                UpdateItemCard(
                    item = item,
                    isDownloading = uiState.download.packageName == item.packageName &&
                        uiState.download.status != null &&
                        uiState.download.error == null,
                    isReadyToInstall = uiState.download.packageName == item.packageName &&
                        uiState.download.savedPaths.isNotEmpty(),
                    onUpdate = { viewModel.download(item.latest, context.applicationContext) },
                    onInstall = { viewModel.installLatestDownload(context) },
                )
            }
        }
    }
}
