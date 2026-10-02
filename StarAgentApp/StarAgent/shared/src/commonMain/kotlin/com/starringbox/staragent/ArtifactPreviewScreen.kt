package com.starringbox.staragent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.starringbox.staragent.core.data.LocalAppState
import com.starringbox.staragent.core.designsystem.component.EmptyHint
import com.starringbox.staragent.core.designsystem.component.StarImage
import com.starringbox.staragent.core.designsystem.component.StarImageViewer
import com.starringbox.staragent.core.designsystem.component.StarTopBar
import com.starringbox.staragent.core.designsystem.navigation.Navigator
import com.starringbox.staragent.core.designsystem.theme.StarPalette

@Composable
internal fun ArtifactPreviewScreen(navigator: Navigator, artifactId: String) {
    val state = LocalAppState.current
    val artifact = remember(artifactId) { state.artifactById(artifactId) }
    val content = remember(artifact?.id) {
        artifact?.let { value ->
            state.readArtifactText(value) ?: if (value.filePath == null) {
                buildString {
                    append(value.summary)
                    value.sections.forEach { section ->
                        append("\n\n").append(section.heading).append("\n")
                        section.lines.forEach { append("• ").append(it).append("\n") }
                    }
                }
            } else {
                null
            }
        }
    }
    val bytes = remember(artifact?.id) { artifact?.let(state::readArtifactBytes) }
    val mime = artifact?.mimeType?.substringBefore(';')?.lowercase().orEmpty()
    val remoteImageHtml = artifact?.sourceUrl
        ?.takeIf { it.startsWith("https://") }
        ?.let { rawUrl ->
            val url = rawUrl.replace("&", "&amp;").replace("\\", "&#92;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
            "<!doctype html><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
                "<body style=\"margin:0;background:#111;display:grid;place-items:center;min-height:100vh\">" +
                "<img src=\"$url\" style=\"max-width:100%;max-height:100vh;object-fit:contain\"></body>"
        }
    val svgHtml = if (mime == "image/svg+xml" && content != null) {
        "<!doctype html><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<body style=\"margin:0\">$content</body>"
    } else null

    Column(Modifier.fillMaxSize()) {
        StarTopBar(
            title = artifact?.title ?: "文件预览",
            subtitle = artifact?.fileName ?: artifact?.kind?.label,
            onBack = { navigator.pop() },
        )
        when {
            artifact == null -> EmptyHint("找不到这个文件，可能已经从会话中删除。")
            mime.startsWith("image/") && mime != "image/svg+xml" && bytes != null -> {
                StarImageViewer(
                    images = listOf(bytes),
                    onDismiss = { navigator.pop() },
                    modifier = Modifier.weight(1f),
                )
            }
            mime == "text/html" || mime == "image/svg+xml" || remoteImageHtml != null -> {
                val html = when {
                    mime == "image/svg+xml" -> svgHtml.orEmpty()
                    remoteImageHtml != null -> remoteImageHtml
                    else -> content.orEmpty()
                }
                if (html.isBlank()) {
                    EmptyHint("这个 HTML 文件没有可预览的内容。")
                } else {
                    ArtifactHtmlContent(html, Modifier.weight(1f).fillMaxWidth())
                }
            }
            content != null -> {
                SelectionContainer {
                    Text(
                        text = content,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(18.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = StarPalette.Text,
                    )
                }
            }
            bytes != null -> {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    StarImage(bytes, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            }
            else -> EmptyHint("文件内容暂时无法读取。")
        }
    }
}

@Composable
internal expect fun ArtifactHtmlContent(html: String, modifier: Modifier = Modifier)
