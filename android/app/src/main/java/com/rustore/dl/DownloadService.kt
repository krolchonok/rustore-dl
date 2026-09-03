package com.rustore.dl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = RuStoreClient()
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var historyStore: DownloadHistoryStore
    private lateinit var notificationManager: NotificationManager
    private var activeJob: Job? = null
    private var lastNotifiedPercent = -1

    override fun onCreate() {
        super.onCreate()
        historyStore = DownloadHistoryStore(applicationContext)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val appJson = intent?.getStringExtra(EXTRA_APP_INFO)
        if (appJson == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val app = json.decodeFromString(AppInfo.serializer(), appJson)

        startForeground(NOTIFICATION_ID, buildProgressNotification(app.appName, "Подготовка…", indeterminate = true))

        activeJob?.cancel()
        activeJob = scope.launch { runDownload(app, startId) }
        return START_NOT_STICKY
    }

    private suspend fun runDownload(app: AppInfo, startId: Int) {
        lastNotifiedPercent = -1
        try {
            postProgress(app, "Получение ссылок на загрузку…")
            val artifacts = client.getDownloadArtifacts(app.appId)
            val destinationDir = createDownloadDirectory(applicationContext, app.packageName)

            val savedPaths = mutableListOf<String>()
            artifacts.forEachIndexed { index, artifact ->
                val target = File(destinationDir, artifact.fileName)
                postProgress(app, "Загрузка ${index + 1}/${artifacts.size}: ${artifact.fileName}")

                client.downloadToFile(artifact.url, target) { downloaded, total ->
                    if (total != null && total > 0) {
                        val percent = ((downloaded * 100) / total).toInt()
                        if (percent != lastNotifiedPercent) {
                            lastNotifiedPercent = percent
                            val label = "Загрузка ${index + 1}/${artifacts.size}: $percent%"
                            notificationManager.notify(
                                NOTIFICATION_ID,
                                buildProgressNotification(app.appName, label, progressPercent = percent),
                            )
                            DownloadEventBus.post(
                                DownloadEvent.Progress(app.packageName, app.appName, label, percent),
                            )
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

            notificationManager.notify(NOTIFICATION_ID, buildCompletedNotification(app.appName, success = true))
            DownloadEventBus.post(DownloadEvent.Completed(app.packageName, app.appName, savedPaths, record.id))
        } catch (error: Exception) {
            Log.e(TAG, "Download failed for ${app.packageName}", error)
            notificationManager.notify(
                NOTIFICATION_ID,
                buildCompletedNotification(app.appName, success = false, message = error.message),
            )
            DownloadEventBus.post(DownloadEvent.Failed(app.packageName, error.message ?: "Download failed"))
        } finally {
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf(startId)
        }
    }

    private fun postProgress(app: AppInfo, status: String) {
        notificationManager.notify(NOTIFICATION_ID, buildProgressNotification(app.appName, status, indeterminate = true))
        DownloadEventBus.post(DownloadEvent.Progress(app.packageName, app.appName, status))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Загрузка приложений",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Прогресс загрузки APK из RuStore"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildProgressNotification(
        appName: String,
        status: String,
        indeterminate: Boolean = false,
        progressPercent: Int = 0,
    ): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle(appName)
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progressPercent, indeterminate)
            .setContentIntent(openAppPendingIntent())
            .build()
    }

    private fun buildCompletedNotification(
        appName: String,
        success: Boolean,
        message: String? = null,
    ): Notification {
        val text = if (success) "Загрузка завершена — откройте, чтобы установить" else message ?: "Загрузка не удалась"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle(appName)
            .setContentText(text)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent())
            .build()
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(this, 0, intent, flags)
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

    companion object {
        private const val TAG = "DownloadService"
        private const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 1001
        private const val EXTRA_APP_INFO = "app_info"

        fun start(context: Context, app: AppInfo) {
            val json = Json { ignoreUnknownKeys = true }
            val intent = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_APP_INFO, json.encodeToString(AppInfo.serializer(), app))
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
