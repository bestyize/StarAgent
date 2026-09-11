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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 先装好 Context，模型服务的 API Key 靠它落到 SharedPreferences。
        AndroidAppContext.install(applicationContext)

        // 选图与保存都必须挂在 Activity 的 result 回调上，所以在这里注册。
        MediaPickers.install(AndroidMediaPicker(applicationContext, this))
        MediaSavers.install(AndroidMediaSaver(applicationContext, this))

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
