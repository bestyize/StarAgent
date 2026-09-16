package com.starringbox.staragent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.starringbox.staragent.core.bootstrap.PlatformInstall
import com.starringbox.staragent.core.bootstrap.install

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 这一端所有平台能力一次装齐：Context、本地存储、选图、保存、语音。
        // **必须在 super.onCreate 之前**——选图、保存、录音、识别四样都要
        // registerForActivityResult，那只有 Activity 初始化阶段能调。
        // 装了什么、为什么是这个顺序，都在 core:bootstrap 里。
        PlatformInstall.install(applicationContext, this)

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
