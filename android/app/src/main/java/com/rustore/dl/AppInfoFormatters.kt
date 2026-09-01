package com.rustore.dl

import java.util.Locale

object AppInfoFormatters {
    fun formatRating(rating: Double?): String {
        if (rating == null) {
            return "—"
        }
        return String.format(Locale.getDefault(), "%.1f", rating)
    }

    fun formatRatingVotes(votes: Long?): String? {
        if (votes == null || votes <= 0) {
            return null
        }
        return when {
            votes >= 1_000_000 -> String.format(Locale.getDefault(), "%.1f млн", votes / 1_000_000.0)
            votes >= 1_000 -> String.format(Locale.getDefault(), "%.0f тыс.", votes / 1_000.0)
            else -> votes.toString()
        }
    }

    fun formatDownloads(app: AppInfo): String? {
        app.roundedDownloadsText?.takeIf { it.isNotBlank() }?.let { return it }
        val downloads = app.downloads ?: return null
        if (downloads <= 0) {
            return null
        }
        return when {
            downloads >= 1_000_000 -> "${formatCompact(downloads / 1_000_000.0)} млн+"
            downloads >= 1_000 -> "${formatCompact(downloads / 1_000.0)} тыс+"
            else -> downloads.toString()
        }
    }

    fun formatFileSize(bytes: Long?): String? {
        if (bytes == null || bytes <= 0) {
            return null
        }
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024
            unitIndex++
        }
        return if (unitIndex == 0) {
            "${bytes.toInt()} ${units[unitIndex]}"
        } else {
            String.format(Locale.getDefault(), "%.1f %s", value, units[unitIndex])
        }
    }

    fun formatCategory(category: String): String {
        return category.replace('_', ' ').replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
        }
    }

    fun formatAge(app: AppInfo): String? {
        return app.ageLegal?.takeIf { it.isNotBlank() }
            ?: app.ageRestriction?.category?.takeIf { it.isNotBlank() }
    }

    private fun formatCompact(value: Double): String {
        return if (value >= 10) {
            String.format(Locale.getDefault(), "%.0f", value)
        } else {
            String.format(Locale.getDefault(), "%.1f", value)
        }
    }
}
