package com.starringbox.staragent

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.starringbox.staragent.core.bootstrap.PlatformInstall
import com.starringbox.staragent.core.bootstrap.install
import com.starringbox.staragent.core.designsystem.navigation.Route

class MainActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_DESTINATION_TYPE = "com.starringbox.staragent.notification.destination_type"
        private const val EXTRA_DESTINATION_ID = "com.starringbox.staragent.notification.destination_id"
    }

    private var notificationEvent by mutableStateOf<AppNavigationEvent?>(null)
    private var eventSequence = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        // 这一端所有平台能力一次装齐：Context、本地存储、选图、保存、语音。
        // **必须在 super.onCreate 之前**——选图、保存、录音、识别四样都要
        // registerForActivityResult，那只有 Activity 初始化阶段能调。
        // 装了什么、为什么是这个顺序，都在 core:bootstrap 里。
        PlatformInstall.install(applicationContext, this)

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        notificationEvent = navigationEventFor(intent)

        setContent {
            App(notificationEvent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        navigationEventFor(intent)?.let { notificationEvent = it }
    }

    private fun navigationEventFor(intent: Intent?): AppNavigationEvent? {
        val id = intent?.getStringExtra(EXTRA_DESTINATION_ID) ?: return null
        val route = when (intent.getStringExtra(EXTRA_DESTINATION_TYPE)) {
            "task" -> Route.TaskDetail(id)
            "artifact" -> Route.ArtifactPreview(id)
            else -> return null
        }
        eventSequence += 1
        return AppNavigationEvent(eventSequence, route)
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
