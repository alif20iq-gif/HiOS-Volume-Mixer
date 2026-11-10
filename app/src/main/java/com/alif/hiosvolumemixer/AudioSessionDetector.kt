package com.alif.hiosvolumemixer

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

data class AudioAppSession(val uid: Int, val packageName: String, val state: String)

object AudioSessionDetector {
    @Volatile private var shellService: IShellService? = null
    @Volatile private var binding = false
    private val lock = Object()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, binder: IBinder) {
            synchronized(lock) {
                shellService = IShellService.Stub.asInterface(binder)
                lock.notifyAll()
            }
        }
        override fun onServiceDisconnected(componentName: ComponentName) {
            synchronized(lock) { shellService = null }
        }
    }

    private fun service(context: Context): IShellService? {
        if (!Shizuku.pingBinder() ||
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return null
        synchronized(lock) {
            if (shellService == null && !binding) {
                binding = true
                try {
                    val args = Shizuku.UserServiceArgs(
                        ComponentName(context, ShellUserService::class.java)
                    ).daemon(false).processNameSuffix("audio").debuggable(false).version(2)
                    Shizuku.bindUserService(args, connection)
                } catch (_: Exception) {
                    binding = false
                    return null
                }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (shellService == null && System.nanoTime() < deadline) {
                try {
                    val remaining = deadline - System.nanoTime()
                    if (remaining > 0) TimeUnit.NANOSECONDS.timedWait(lock, remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    binding = false
                    return null
                }
            }
            binding = false
            return shellService
        }
    }

    fun getActivePackages(context: Context): List<String> = try {
        service(context)?.activePackages.orEmpty().lineSequence()
            .map { it.substringBefore('\t').trim() }
            .filter { it.isNotBlank() }.distinct().toList()
    } catch (_: Exception) { emptyList() }

    fun setPackageVolume(context: Context, packageName: String, volume: Float): String {
        return try {
            service(context)?.setPackageVolume(packageName, volume)
                ?: "Shizuku belum terhubung atau izin belum diberikan"
        } catch (e: Exception) {
            "Gagal menerapkan volume: ${e.message ?: "unknown"}"
        }
    }

    fun runRootProbe(): String = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val output = process.inputStream.bufferedReader().readText().trim()
        val code = process.waitFor()
        if (code == 0 && output.contains("uid=0")) "Akses root tersedia"
        else "Akses root tidak tersedia atau ditolak"
    } catch (_: Exception) { "Akses root tidak tersedia" }

    fun readDump(context: Context): String = readAudioDiagnostics(context)

    fun readAudioDiagnostics(context: Context): String {
        val shell = service(context) ?: return "SHIZUKU_ERROR: Shizuku belum terhubung atau izin belum diberikan."
        val commands = listOf(
            "dumpsys audio",
            "dumpsys media.audio_flinger",
            "dumpsys media.audio_policy",
            "dumpsys media_session"
        )
        return buildString {
            appendLine("HiOS Volume Mixer - AUDIO DIAGNOSTIC")
            appendLine("Waktu: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Shizuku: connected")
            for (command in commands) {
                appendLine()
                appendLine("========== $command ==========")
                val output = try { shell.execute(command) } catch (e: Exception) {
                    "ERROR: ${e.javaClass.simpleName}: ${e.message}"
                }
                appendLine(output.take(26000))
                if (output.length > 26000) appendLine("[TRUNCATED: output melebihi 26000 karakter]")
            }
        }
    }

    fun parseActiveSessions(dump: String, packageManager: PackageManager): List<AudioAppSession> {
        val result = linkedMapOf<Int, AudioAppSession>()
        val uidRegex = Regex("""client uid\\s*[:=]\\s*(\\d+)""", RegexOption.IGNORE_CASE)
        val blocks = dump.split(Regex("""(?=AudioPlaybackConfiguration|PlaybackActivityMonitor|piid:)"""))
        for (block in blocks) {
            val uid = uidRegex.find(block)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
            val lower = block.lowercase()
            val active = lower.contains("state: started") || lower.contains("state=started") ||
                lower.contains("isactive=true") || lower.contains("active: true")
            if (!active) continue
            val name = try { packageManager.getPackagesForUid(uid)?.firstOrNull() } catch (_: Exception) { null }
                ?: "UID $uid"
            result[uid] = AudioAppSession(uid, name, "Mengeluarkan audio")
        }
        return result.values.toList()
    }
}