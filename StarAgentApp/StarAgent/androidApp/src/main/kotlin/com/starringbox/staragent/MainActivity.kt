package com.starringbox.staragent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.starringbox.staragent.core.data.AndroidAppContext
import com.starringbox.staragent.core.media.MediaPickers
import com.starringbox.staragent.core.media.MediaSavers
import com.starringbox.staragent.core.storage.LocalStores
import com.starringbox.staragent.core.voice.Players
import com.starringbox.staragent.core.voice.Recorders
import com.starringbox.staragent.core.voice.Voice

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 先装好 Context，模型服务的 API Key 靠它落到 SharedPreferences。
        AndroidAppContext.install(applicationContext)

        // 选图、保存、本地存储都要挂在 Activity 或 Context 上，在这里统一注入。
        LocalStores.install(AndroidLocalStore(applicationContext))
        MediaPickers.install(AndroidMediaPicker(applicationContext, this))
        MediaSavers.install(AndroidMediaSaver(applicationContext, this))

        // 语音：录音与播放是设备能力；识别和合成**两路都注册**——
        // 系统自带的那路不要 Key、不花钱，模型那路由 core:data 装。
        // 具体用哪路由 core:voice 按偏好挑（默认自动，优先系统能力）。
        Recorders.install(AndroidVoiceRecorder(applicationContext, this))
        Players.install(AndroidVoicePlayer(applicationContext))
        Voice.installDevice(
            transcriber = AndroidSystemTranscriber(applicationContext, this),
            synthesizer = AndroidSystemSynthesizer(applicationContext),
        )

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            App()
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
