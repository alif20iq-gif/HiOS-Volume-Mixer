package com.alif.hiosvolumemixer

import android.content.pm.PackageManager
import android.os.Process
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

data class AudioAppSession(val uid: Int, val packageName: String, val state: String)

object AudioSessionDetector {
    fun runRootProbe(): String = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val output = process.inputStream.bufferedReader().readText().trim()
        val code = process.waitFor()
        if (code == 0 && output.contains("uid=0")) "Akses root tersedia"
        else "Akses root tidak tersedia atau ditolak"
    } catch (_: Exception) { "Akses root tidak tersedia" }

    fun readDump(): String {
        val command = arrayOf("sh", "-c", "dumpsys audio")
        val process = if (Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            Shizuku.newProcess(arrayOf("sh", "-c", "dumpsys audio"), null, null)
        } else {
            try { Runtime.getRuntime().exec(arrayOf("su", "-c", "dumpsys audio")) }
            catch (_: Exception) { Runtime.getRuntime().exec(command) }
        }
        return try {
            val text = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            text
        } catch (e: Exception) {
            try { process.destroy() } catch (_: Exception) {}
            ""
        }
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
