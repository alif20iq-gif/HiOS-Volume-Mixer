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

    private fun packageForConfig(config: Any, ctx: Context): String? {
        val pid = intField(config, "mClientPid") ?: return null
        val am = ctx.getSystemService(android.app.ActivityManager::class.java) ?: return null
        val process = am.runningAppProcesses?.firstOrNull { it.pid == pid } ?: return null
        return process.pkgList?.firstOrNull()
    }

    override fun getActivePackages(): String {
        val ctx = context() ?: return ""
        return activeConfigurations().mapNotNull { config ->
            val state = intField(config, "mPlayerState") ?: -1
            if (state != 2 && state != 3) return@mapNotNull null
            val pkg = packageForConfig(config, ctx) ?: return@mapNotNull null
            val pid = intField(config, "mClientPid") ?: -1
            "$pkg\\t$pid"
        }.distinct().joinToString("\\n")
    }

    override fun setPackageVolume(packageName: String, volume: Float): String {
        val ctx = context() ?: return "ERROR: no context"
        val normalized = volume.coerceIn(0f, 1f)
        var matched = 0
        var applied = 0
        val failures = mutableListOf<String>()
        for (config in activeConfigurations()) {
            val state = intField(config, "mPlayerState") ?: -1
            if (state != 2 && state != 3) continue
            if (packageForConfig(config, ctx) != packageName) continue
            matched++
            try {
                // Use the framework IPlayer interface directly, as the Android per-player
                // volume implementation does. PlayerProxy reflection can be blocked on OEM ROMs.
                val player = config.javaClass.getDeclaredMethod("getIPlayer").apply { isAccessible = true }.invoke(config)
                if (player == null) {
                    failures.add("no-player")
                    continue
                }
                val setVolume = player.javaClass.methods.firstOrNull {
                    it.name == "setVolume" && it.parameterTypes.contentEquals(arrayOf(Float::class.javaPrimitiveType))
                } ?: player.javaClass.interfaces.asSequence().flatMap { it.methods.asSequence() }
                    .firstOrNull { it.name == "setVolume" && it.parameterTypes.size == 1 }
                if (setVolume == null) {
                    failures.add("setVolume-unavailable")
                    continue
                }
                setVolume.invoke(player, normalized)
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