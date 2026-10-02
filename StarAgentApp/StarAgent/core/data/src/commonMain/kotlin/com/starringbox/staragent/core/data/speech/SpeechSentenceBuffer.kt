package com.starringbox.staragent.core.data.speech

/** Collects streamed text into complete sentences and flushes a trailing fragment at turn boundaries. */
internal class SpeechSentenceBuffer {
    private val pending = StringBuilder()

    /** Returns newly completed sentences, preserving their original text and order. */
    fun append(delta: String): List<String> {
        if (delta.isEmpty()) return emptyList()
        val ready = ArrayList<String>()
        var index = 0
        while (index < delta.length) {
            val char = delta[index]
            pending.append(char)
            val decimalPoint = char == '.' && index > 0 && index < delta.lastIndex &&
                delta[index - 1].isDigit() && delta[index + 1].isDigit()
            if (!decimalPoint && char in SENTENCE_ENDINGS) {
                // Keep closing quotes/brackets with their sentence when they arrived in this delta.
                index++
                while (index < delta.length && delta[index] in CLOSING_MARKS) {
                    pending.append(delta[index])
                    index++
                }
                pending.toString().trim().takeIf { it.isNotEmpty() }?.let(ready::add)
                pending.clear()
            } else index++
        }
        return ready
    }

    /** Flushes and clears the current fragment so separate turns cannot join together. */
    fun boundary(): String? = takePending()

    /** Flushes the final fragment. */
    fun finish(): String? = takePending()

    private fun takePending(): String? = pending.toString().trim().takeIf { it.isNotEmpty() }.also { pending.clear() }

    private companion object {
        const val SENTENCE_ENDINGS = ".!?。！？\n"
        const val CLOSING_MARKS = "\"'”’」』】）》〕〉〕"
    }
}
