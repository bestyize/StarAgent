package com.starringbox.staragent.core.speech.local.asr.vad

import java.io.File
import java.security.MessageDigest

/** Copies the bundled model out of the app once, since the native runtime loads models from a file path. */
internal fun bundledSileroModel(directory: File): File {
    val target = File(directory, SileroSettings.FILE_NAME)
    if (target.isFile && target.length() == SileroSettings.SIZE && sha256(target.readBytes()) == SileroSettings.SHA256) return target
    val bytes = checkNotNull(SpeechDetector::class.java.classLoader?.getResourceAsStream(SileroSettings.RESOURCE)) {
        "Silero VAD resource is missing from the app"
    }.use { it.readBytes() }
    check(bytes.size.toLong() == SileroSettings.SIZE && sha256(bytes) == SileroSettings.SHA256) { "Silero VAD resource is corrupt" }
    check(directory.isDirectory || directory.mkdirs()) { "Cannot create $directory" }
    val partial = File(directory, "${target.name}.part")
    try {
        partial.writeBytes(bytes)
        check(!target.exists() || target.delete()) { "Cannot replace $target" }
        check(partial.renameTo(target)) { "Cannot save $target" }
    } finally {
        if (partial.exists()) partial.delete()
    }
    return target
}

private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
