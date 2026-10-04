package com.starringbox.staragent.core.toolhost

import com.starringbox.staragent.core.tools.ports.*
import kotlinx.coroutines.withTimeoutOrNull

actual fun platformCommandPort(): CommandPort = object : CommandPort {
    override suspend fun execute(agentId: String, command: String, workingDirectory: String?, timeoutMillis: Long): CommandResult {
        val process = ProcessBuilder("sh", "-lc", command).apply {
            if (!workingDirectory.isNullOrBlank()) directory(java.io.File(workingDirectory))
            redirectErrorStream(false)
        }.start()
        val completed = withTimeoutOrNull(timeoutMillis) {
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            process.waitFor()
            CommandResult(process.exitValue(), out.take(16000), err.take(8000))
        }
        return completed ?: run { process.destroyForcibly(); CommandResult(null, "", "命令执行超时", true) }
    }
}
