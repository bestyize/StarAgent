package com.starringbox.staragent

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding

@Composable
internal actual fun ArtifactHtmlContent(html: String, modifier: Modifier) {
    SelectionContainer {
        Text(
            text = html,
            modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        )
    }
}
