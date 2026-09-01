package com.rustore.dl

import kotlinx.serialization.Serializable

@Serializable
data class DownloadRecord(
    val id: String,
    val packageName: String,
    val appName: String,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val downloadedAt: Long,
    val directoryPath: String,
    val apkFiles: List<String>,
    val isSplit: Boolean,
)
