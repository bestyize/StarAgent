package com.starringbox.staragent.core.platform.voice

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechActivityTest {
    @Test
    fun silenceAndClicksDoNotCreateAnUtterance() {
        val activity = SpeechActivity()
        activity.acceptLevel(0f, 5_000)
        assertFalse(activity.ended)
        activity.acceptLevel(0.5f, 100)
        activity.acceptLevel(0f, 2_000)
        assertFalse(activity.ended)
    }

    @Test
    fun aPauseEndsSpeechButABriefGapDoesNot() {
        val activity = SpeechActivity()
        activity.acceptLevel(0.04f, 400)
        activity.acceptLevel(0f, 800)
        assertFalse(activity.ended)
        activity.acceptLevel(0.04f, 200)
        activity.acceptLevel(0f, 1_000)
        assertFalse(activity.ended)
        activity.acceptLevel(0f, 100)
        assertTrue(activity.ended)
        activity.reset()
        assertFalse(activity.ended)
    }

    @Test
    fun signedLittleEndianPcmDrivesTheLevelAndEndpoint() {
        val activity = SpeechActivity()
        val pcm = ByteArray(9_600) { index -> if (index % 2 == 0) 0 else -16 }
        activity.acceptPcm(pcm, pcm.size)
        assertTrue(activity.level > 0.5f)
        activity.acceptPcm(ByteArray(35_200), 35_200)
        assertTrue(activity.ended)
    }
}
