package com.rustore.dl

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = ruStoreColorScheme()) {
                MainScreen()
            }
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
    var showClearHistoryDialog by remember { mutableStateOf(false) }

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
                },
            )
        },
        bottomBar = {
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
