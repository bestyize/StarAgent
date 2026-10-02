package com.starringbox.staragent.core.speech.local.tts

import com.starringbox.staragent.core.speech.local.tts.client.PiperAudioCallback
import kotlin.test.*

class PiperCallbackAbiTest {
    @Test fun exportsTheExactBoxedMethodSherpaJniLooksUp() {
        val callback = PiperAudioCallback { samples -> if (samples.size == 2) 1 else 0 }
        val method = callback.javaClass.getMethod("invoke", FloatArray::class.java)
        assertEquals(java.lang.Integer::class.java, method.returnType)
        assertEquals(1, method.invoke(callback, floatArrayOf(0f, 0.5f)))
    }
}
