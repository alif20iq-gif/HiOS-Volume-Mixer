package com.alif.hiosvolumemixer

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

data class AudioAppSession(val uid: Int, val packageName: String, val state: String)

object AudioSessionDetector {
    @Volatile private var shellService: IShellService? = null
    @Volatile private var binding = false
    private val lock = Object()

    private val connection = object : Shizuku.UserServiceConnection() {
        override fun onServiceConnected(componentName: ComponentName, binder: IBinder) {
            synchronized(lock) {
                shellService = IShellService.Stub.asInterface(binder)
                lock.notifyAll()
            }
        }
        override fun onServiceDisconnected(componentName: ComponentName) {
            shellService = null
        }
    }

    fun runRootProbe(): String = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val output = process.inputStream.bufferedReader().readText().trim()
        val code = process.waitFor()
        if (code == 0 && output.contains("uid=0")) "Akses root tersedia"
        else "Akses root tidak tersedia atau ditolak"
    } catch (_: Exception) { "Akses root tidak tersedia" }

    private fun readWithShizuku(context: Context): String? {
        if (!Shizuku.pingBinder() ||
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return null

        synchronized(lock) {
            if (shellService == null && !binding) {
                binding = true
                try {
                    val args = Shizuku.UserServiceArgs(
                        ComponentName(context, ShellUserService::class.java)
                    ).daemon(false).processNameSuffix("audio").debuggable(false).version(1)
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
            val service = shellService ?: return null
            return try { service.execute("dumpsys audio") } catch (_: Exception) {
                shellService = null
                null
            }
        }
    }

    fun readDump(context: Context): String {
        readWithShizuku(context)?.let { return it }
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "dumpsys audio"))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            if (code == 0) output else ""
        } catch (_: Exception) { "" }
    }

    fun parseActiveSessions(dump: String, packageManager: PackageManager): List<AudioAppSession> {
        val result = linkedMapOf<Int, AudioAppSession>()
        val uidRegex = Regex("""client uid\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE)
        val blocks = dump.split(Regex("""(?=AudioPlaybackConfiguration|PlaybackActivityMonitor|piid:)"""))
        for (block in blocks) {
            val uid = uidRegex.find(block)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
            val lower = block.lowercase()
            val active = lower.contains("state: started") ||
                lower.contains("state=started") ||
                lower.contains("isactive=true") ||
                lower.contains("active: true")
            if (!active) continue
            val packages = try { packageManager.getPackagesForUid(uid)?.toList().orEmpty() }
            catch (_: Exception) { emptyList() }
            val name = packages.firstOrNull() ?: "UID $uid"
            result[uid] = AudioAppSession(uid, name, "Mengeluarkan audio")
        }
        return result.values.toList()
    }
}
