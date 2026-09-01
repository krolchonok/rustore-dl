package com.rustore.dl

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

object ApkInstaller {
    fun canInstallPackages(context: Context): Boolean {
        return context.packageManager.canRequestPackageInstalls()
    }

    fun createInstallPermissionIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun install(context: Context, apkFiles: List<File>): InstallResult {
        val existing = apkFiles.filter { it.exists() && it.length() > 0 }
        if (existing.isEmpty()) {
            return InstallResult.Failure("APK files are missing")
        }
        if (!canInstallPackages(context)) {
            return InstallResult.NeedsPermission
        }

        return if (existing.size == 1) {
            installSingle(context, existing.first())
        } else {
            installSplit(context, existing)
        }
    }

    private fun installSingle(context: Context, apkFile: File): InstallResult {
        return runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            InstallResult.Started
        }.getOrElse { error ->
            InstallResult.Failure(error.message ?: "Install failed")
        }
    }

    private fun installSplit(context: Context, apkFiles: List<File>): InstallResult {
        return runCatching {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ).apply {
                setInstallReason(android.content.pm.PackageManager.INSTALL_REASON_USER)
            }

            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            try {
                apkFiles.sortedBy { it.name }.forEach { file ->
                    file.inputStream().use { input ->
                        session.openWrite(file.name, 0, file.length()).use { output ->
                            input.copyTo(output)
                            session.fsync(output)
                        }
                    }
                }

                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(context, InstallResultReceiver::class.java),
                    pendingIntentFlags(),
                )
                session.commit(pendingIntent.intentSender)
                InstallResult.Started
            } catch (error: Exception) {
                session.abandon()
                throw error
            } finally {
                session.close()
            }
        }.getOrElse { error ->
            InstallResult.Failure(error.message ?: "Split install failed")
        }
    }

    private fun pendingIntentFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }
}

sealed class InstallResult {
    data object Started : InstallResult()
    data object NeedsPermission : InstallResult()
    data class Failure(val message: String) : InstallResult()
}
