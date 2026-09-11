package com.starringbox.staragent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.starringbox.staragent.core.data.AndroidAppContext
import com.starringbox.staragent.core.media.MediaPickers

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 先装好 Context，模型服务的 API Key 靠它落到 SharedPreferences。
        AndroidAppContext.install(applicationContext)

        // 选图能力必须在 Activity 初始化阶段注册，所以在这里注入。
        MediaPickers.install(AndroidMediaPicker(applicationContext, this))

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
