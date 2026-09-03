package com.rustore.dl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppCatalogCard(
    app: AppInfo,
    isDownloading: Boolean,
    isLoadingDetails: Boolean,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(app.appId) { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                AsyncImage(
                    model = app.iconUrl,
                    contentDescription = app.appName,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop,
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.appName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    app.companyName?.let { company ->
                        Text(
                            text = company,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        app.displayRating?.let { rating ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = AppInfoFormatters.formatRating(rating),
                                    modifier = Modifier.padding(start = 4.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                AppInfoFormatters.formatRatingVotes(app.displayRatingVotes)?.let { votes ->
                                    Text(
                                        text = "($votes)",
                                        modifier = Modifier.padding(start = 4.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        AppInfoFormatters.formatDownloads(app)?.let { downloads ->
                            Text(
                                text = downloads,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            app.shortDescription?.let { description ->
                Text(
                    text = description,
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            MetadataChips(app = app, modifier = Modifier.padding(top = 12.dp))

            when {
                isLoadingDetails -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
                app.screenshots.isNotEmpty() -> {
                    ScreenshotCarousel(
                        screenshots = app.screenshots,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }

            if (expanded) {
                ExpandedDetails(app = app, modifier = Modifier.padding(top = 12.dp))
            }

            val canExpand = !app.fullDescription.isNullOrBlank() ||
                !app.whatsNew.isNullOrBlank() ||
                app.screenshots.isNotEmpty()

            if (canExpand) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(if (expanded) "Свернуть" else "Подробнее")
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    val version = app.versionName ?: app.versionCode?.toString() ?: "?"
                    Text(
                        text = "Версия $version",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Button(onClick = onDownload, enabled = !isDownloading) {
                    if (isDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Default.Download, contentDescription = null)
                    }
                    Text(text = if (isDownloading) "Загрузка" else "Скачать")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetadataChips(
    app: AppInfo,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        app.categories.orEmpty().take(2).forEach { category ->
            AssistChip(
                onClick = {},
                label = { Text(AppInfoFormatters.formatCategory(category)) },
                enabled = false,
            )
        }
        AppInfoFormatters.formatAge(app)?.let { age ->
            AssistChip(onClick = {}, label = { Text(age) }, enabled = false)
        }
        AppInfoFormatters.formatFileSize(app.fileSize)?.let { size ->
            AssistChip(onClick = {}, label = { Text(size) }, enabled = false)
        }
    }
}

@Composable
private fun ScreenshotCarousel(
    screenshots: List<String>,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
    ) {
        itemsIndexed(screenshots, key = { index, url -> "$index-$url" }) { _, url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier
                    .width(132.dp)
                    .height(234.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun ExpandedDetails(
    app: AppInfo,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        app.whatsNew?.takeIf { it.isNotBlank() }?.let { whatsNew ->
            Text(
                text = "Что нового",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = whatsNew.trim(),
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        app.fullDescription?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = "Описание",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = description.trim(),
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
fun UpdateItemCard(
    item: UpdateItem,
    isDownloading: Boolean,
    isReadyToInstall: Boolean,
    onUpdate: () -> Unit,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = item.latest.iconUrl,
                contentDescription = item.installedAppName,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop,
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.installedAppName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val installedVersion = item.installedVersionName ?: item.installedVersionCode.toString()
                val latestVersion = item.latest.versionName ?: item.latest.versionCode?.toString() ?: "?"
                Text(
                    text = "$installedVersion → $latestVersion",
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }

            when {
                isDownloading -> {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                isReadyToInstall -> {
                    Button(onClick = onInstall) {
                        Text("Установить")
                    }
                }
                else -> {
                    Button(onClick = onUpdate) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Text("Обновить")
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryCatalogCard(
    record: DownloadRecord,
    downloadedAt: String,
    onInstall: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = record.appName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = record.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val version = record.versionName ?: record.versionCode?.toString() ?: "?"
            Text(
                text = "v$version • $downloadedAt",
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = if (record.isSplit) {
                    "Split APK (${record.apkFiles.size} файлов)"
                } else {
                    "Single APK"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onInstall, modifier = Modifier.weight(1f)) {
                    Text("Установить")
                }
                TextButton(onClick = onDelete) {
                    Text("Удалить")
                }
            }
        }
    }
}
