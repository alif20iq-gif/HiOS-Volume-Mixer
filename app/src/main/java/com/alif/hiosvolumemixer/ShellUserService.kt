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

    private fun playerFor(config: Any): Any? = try {
        config.javaClass.getDeclaredMethod("getIPlayer").apply { isAccessible = true }.invoke(config)
    } catch (_: Throwable) { null }

    override fun getActivePackages(): String {
        val ctx = context() ?: return ""
        val pm = ctx.packageManager
        return activeConfigurations().mapNotNull { config ->
            val uid = intField(config, "mClientUid") ?: return@mapNotNull null
            val pid = intField(config, "mClientPid") ?: -1
            val state = intField(config, "mPlayerState") ?: -1
            if (state != 2 && state != 3) return@mapNotNull null
            val packageName = pm.getPackagesForUid(uid)?.firstOrNull() ?: return@mapNotNull null
            "$packageName\t$pid"
        }.distinct().joinToString("\n")
    }

    override fun setPackageVolume(packageName: String, volume: Float): String {
        val ctx = context() ?: return "ERROR: no context"
        val normalized = volume.coerceIn(0f, 1f)
        var matched = 0
        var applied = 0
        val failures = mutableListOf<String>()
        for (config in activeConfigurations()) {
            val uid = intField(config, "mClientUid") ?: continue
            if (ctx.packageManager.getPackagesForUid(uid)?.contains(packageName) != true) continue
            matched++
            try {
                // PlayerProxy is the framework-supported remote wrapper around IPlayer.
                val proxyMethod = config.javaClass.getDeclaredMethod("getPlayerProxy").apply { isAccessible = true }
                val proxy = proxyMethod.invoke(config)
                if (proxy == null) {
                    failures.add("no-player-proxy")
                    continue
                }
                proxy.javaClass.getMethod("setVolume", Float::class.javaPrimitiveType!!)
                    .invoke(proxy, normalized)
                applied++
            } catch (e: Throwable) {
                failures.add(e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName)
            }
        }
        return "matched=$matched;applied=$applied" +
            if (failures.isEmpty()) "" else ";errors=${failures.distinct().take(3).joinToString(",")}"
    }
}