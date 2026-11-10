package com.alif.hiosvolumemixer

import java.io.BufferedReader
import java.io.InputStreamReader
import rikka.shizuku.Shizuku

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
}
