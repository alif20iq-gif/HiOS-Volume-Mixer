package com.alif.hiosvolumemixer

import android.content.Context
import android.media.AudioManager

class ShellUserService : IShellService.Stub() {
    override fun execute(command: String): String {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val code = process.waitFor()
        return buildString {
            append(stdout)
            if (stderr.isNotBlank()) append("\n").append(stderr)
            if (code != 0) append("\n[exit ").append(code).append("]")
        }
    }

    private fun context(): Context? = try {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication").invoke(null) as? Context
    } catch (_: Throwable) { null }

    private fun activeConfigurations(): List<Any> {
        val ctx = context() ?: return emptyList()
        val audio = ctx.getSystemService(AudioManager::class.java) ?: return emptyList()
        val method = AudioManager::class.java.getDeclaredMethod("getActivePlaybackConfigurations")
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (method.invoke(audio) as? List<Any>).orEmpty()
    }

    private fun intField(instance: Any, name: String): Int? = try {
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.getInt(instance)
    } catch (_: Throwable) { null }

    private fun packageForConfig(config: Any, ctx: Context): String? {
        val uid = intField(config, "mClientUid") ?: return null
        return ctx.packageManager.getPackagesForUid(uid)
            ?.firstOrNull { packageName ->
                try {
                    val app = ctx.packageManager.getApplicationInfo(packageName, 0)
                    (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 ||
                        (app.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                } catch (_: Throwable) { false }
            }
    }

    override fun getActivePackages(): String {
        val ctx = context() ?: return ""
        return activeConfigurations().mapNotNull { config ->
            val state = intField(config, "mPlayerState") ?: -1
            if (state != 2 && state != 3) return@mapNotNull null
            val pkg = packageForConfig(config, ctx) ?: return@mapNotNull null
            val pid = intField(config, "mClientPid") ?: -1
            "$pkg\t$pid"
        }.distinct().joinToString("\n")
    }

    override fun setPackageVolume(packageName: String, volume: Float): String {
        val ctx = context() ?: return "ERROR: no context"
        val normalized = volume.coerceIn(0f, 1f)
        val packages = ctx.packageManager
        var matched = 0
        var applied = 0
        val failures = mutableListOf<String>()
        for (config in activeConfigurations()) {
            val state = intField(config, "mPlayerState") ?: -1
            if (state != 2 && state != 3) continue
            val uid = intField(config, "mClientUid") ?: continue
            if (packages.getPackagesForUid(uid)?.contains(packageName) != true) continue
            matched++
            try {
                // Use AudioPlaybackConfiguration.getPlayerProxy(), the same framework
                // per-player API used by Android's multi-app volume implementations.
                val proxyMethod = config.javaClass.getDeclaredMethod("getPlayerProxy").apply { isAccessible = true }
                val proxy = proxyMethod.invoke(config) ?: throw IllegalStateException("player-proxy-null")
                val setter = proxy.javaClass.getMethod("setVolume", java.lang.Float.TYPE)
                setter.invoke(proxy, normalized)
                applied++
            } catch (e: Throwable) {
                val cause = e.cause ?: e
                failures.add(cause.javaClass.simpleName + (cause.message?.let { ":$it" } ?: ""))
            }
        }
        return "matched=$matched;applied=$applied" +
            if (failures.isEmpty()) "" else ";errors=${failures.distinct().take(3).joinToString("|")}"
    }
}