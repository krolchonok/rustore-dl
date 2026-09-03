package com.rustore.dl

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat

data class InstalledApp(
    val packageName: String,
    val appName: String,
    val versionName: String?,
    val versionCode: Long,
)

object InstalledApps {
    fun listUpdatable(context: Context): List<InstalledApp> {
        val packageManager = context.packageManager
        val selfPackage = context.packageName

        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledPackages(0)
        }

        return packages
            .asSequence()
            .filter { it.packageName != selfPackage }
            .filter { pkg ->
                val appInfo = pkg.applicationInfo ?: return@filter false
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val wasUpdated = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                !isSystem || wasUpdated
            }
            .map { pkg ->
                val appInfo = pkg.applicationInfo
                InstalledApp(
                    packageName = pkg.packageName,
                    appName = appInfo?.let { packageManager.getApplicationLabel(it).toString() }
                        ?: pkg.packageName,
                    versionName = pkg.versionName,
                    versionCode = PackageInfoCompat.getLongVersionCode(pkg),
                )
            }
            .toList()
    }
}
