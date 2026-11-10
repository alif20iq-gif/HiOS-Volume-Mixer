package com.alif.hiosvolumemixer

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process

object RecentApps {
    private const val RECENT_WINDOW_MS = 12 * 60 * 60 * 1000L
    private const val MAX_APPS = 36

    fun hasAccess(context: Context): Boolean = try {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }

    /**
     * Recent user-facing apps, ordered by their last foreground use.
     * Core/system-only packages are excluded: only non-system apps with a launcher
     * activity are eligible. This avoids listing services, framework processes,
     * System UI and other packages that cannot meaningfully be selected by a user.
     */
    fun query(context: Context): List<ApplicationInfo> {
        if (!hasAccess(context)) return emptyList()

        val now = System.currentTimeMillis()
        val stats = try {
            (context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager)
                .queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    now - RECENT_WINDOW_MS,
                    now
                )
        } catch (_: Exception) {
            emptyList()
        }

        val pm = context.packageManager
        return stats.orEmpty()
            .asSequence()
            .filter { it.packageName != context.packageName }
            .filter { it.lastTimeUsed in (now - RECENT_WINDOW_MS)..now }
            .groupBy { it.packageName }
            .mapNotNull { (packageName, entries) ->
                val app = try {
                    pm.getApplicationInfo(packageName, 0)
                } catch (_: Exception) {
                    null
                } ?: return@mapNotNull null

                // Exclude preinstalled/system apps and packages without a launcher UI.
                val isSystemApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                if (isSystemApp || pm.getLaunchIntentForPackage(packageName) == null) {
                    return@mapNotNull null
                }

                app to entries.maxOf { it.lastTimeUsed }
            }
            .sortedByDescending { it.second }
            .take(MAX_APPS)
            .map { it.first }
            .toList()
    }
}
