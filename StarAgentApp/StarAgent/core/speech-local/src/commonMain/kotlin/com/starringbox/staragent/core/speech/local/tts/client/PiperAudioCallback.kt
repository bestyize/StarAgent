package com.starringbox.staragent.core.speech.local.tts.client

/**
 * sherpa-onnx Android JNI looks up invoke(float[]) -> java.lang.Integer by reflection.
 * Kotlin's invokedynamic Function1 lambdas expose only invoke(Object) after D8 desugaring.
 * A concrete implementation retains the specialized boxed bridge that JNI requires.
 */
class PiperAudioCallback(private val consume: (FloatArray) -> Int) : (FloatArray) -> Int {
    override fun invoke(samples: FloatArray): Int = consume(samples)
}
