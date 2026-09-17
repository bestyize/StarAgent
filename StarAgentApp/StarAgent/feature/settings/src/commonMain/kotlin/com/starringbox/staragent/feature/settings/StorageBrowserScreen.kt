package com.starringbox.staragent.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.starringbox.staragent.core.designsystem.component.EmptyHint
import com.starringbox.staragent.core.designsystem.component.IconTile
import com.starringbox.staragent.core.designsystem.component.StarCard
import com.starringbox.staragent.core.designsystem.component.StarImage
import com.starringbox.staragent.core.designsystem.component.StarTopBar
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.navigation.Navigator
import com.starringbox.staragent.core.designsystem.navigation.Route
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import com.starringbox.staragent.core.platform.storage.LocalStores

/**
 * 文件浏览器：把存档那棵目录树摊开给人看，**只读**。
 *
 * 存在的意义是"东西到底存在哪"这件事要能被看见——桌面上它就是 `~/.staragent`，
 * 用户拿文件管理器打开就能看到自己的智能体是一个个文件（`SOUL.md` 是他手写的人格、
 * `agent.json` 是状态、`MEMORY.md` 是记忆）。看得见才敢信。
 *
 * [path] 是相对根目录的路径。**它是路由参数而不是页面内部状态**，所以系统返回键
 * 天然就是"上一层目录"。一个路径既可能是目录也可能是文件：文件走同一个页面的只读预览，
 * 于是返回键也就顺势是"回到所在目录"。
 */
@Composable
fun StorageBrowserScreen(navigator: Navigator, path: String) {
    val store = LocalStores.current

    // 目录没有 size，文件有——用这个区分，比去猜名字里有没有点靠谱
    val isFile = remember(path) { runCatching { store.size(path) }.getOrNull() != null }

    if (isFile) FilePreview(navigator, path) else DirectoryListing(navigator, path)
}

// ── 目录 ────────────────────────────────────────

@Composable
private fun DirectoryListing(navigator: Navigator, path: String) {
    val entries = remember(path) { readEntries(path) }
    val rootPath = remember { runCatching { LocalStores.current.rootPath }.getOrNull() }
    val title = path.substringAfterLast('/').ifEmpty { "文件结构" }

    Column(Modifier.fillMaxSize()) {
        StarTopBar(
            title = title,
            // 路径由下面的面包屑负责，这里只说清楚"这一页是只读的"。
            // 之前放的是相对路径，结果是第一层目录标题和副标题一模一样（"agents / agents"）。
            subtitle = "只读",
            onBack = { navigator.pop() },
        )

        // 根目录的真实路径。浏览器要回答的第一个问题就是"这些存在哪"，
        // 所以它摆在最上面，而不是藏在某一层里。没有实现存储的平台不显示这一行。
        if (rootPath != null) {
            Text(
                text = rootPath,
                style = MaterialTheme.typography.labelSmall,
                color = StarPalette.TextFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        Breadcrumb(path) { navigator.push(Route.StorageBrowser(it)) }

        if (entries.isEmpty) {
            EmptyHint(if (path.isEmpty()) "这里还没有东西" else "这个目录是空的")
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(entries.directories, key = { "d:$it" }) { name ->
                EntryRow(
                    glyph = StarIconGlyph.Folder,
                    title = name,
                    subtitle = null,
                    trailingText = null,
                    onClick = { navigator.push(Route.StorageBrowser(joinPath(path, name))) },
                )
            }
            items(entries.files, key = { "f:$it" }) { name ->
                EntryRow(
                    glyph = StarIconGlyph.Book,
                    title = name,
                    subtitle = null,
                    trailingText = formatBytes(entries.sizes[name] ?: 0L),
                    // 图片名是 UUID，一行放不下就被截断，而**后缀恰恰是唯一有意义的那一段**。
                    // 允许折到第二行，才看得见它是 .png 还是 .md。
                    titleMaxLines = 2,
                    onClick = { navigator.push(Route.StorageBrowser(joinPath(path, name))) },
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun EntryRow(
    glyph: StarIconGlyph,
    title: String,
    subtitle: String?,
    trailingText: String?,
    titleMaxLines: Int = 1,
    onClick: () -> Unit,
) {
    StarCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clickable { onClick() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(glyph = glyph, tint = StarPalette.Indigo, containerColor = StarPalette.SurfaceAlt)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = StarPalette.Text,
                    maxLines = titleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = StarPalette.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailingText != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = trailingText,
                    style = MaterialTheme.typography.labelSmall,
                    color = StarPalette.TextFaint,
                )
            }
        }
    }
}

/**
 * 面包屑。每一段都能点回去，所以从深层目录回根目录不必按好几次返回。
 *
 * 横向可滚：路径深了之后一行放不下，而面包屑被截断就失去意义了。
 */
@Composable
private fun Breadcrumb(path: String, onNavigate: (String) -> Unit) {
    val segments = path.split('/').filter { it.isNotEmpty() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crumb(text = "根目录", current = segments.isEmpty()) { onNavigate("") }
        segments.forEachIndexed { index, segment ->
            Text(
                text = "/",
                style = MaterialTheme.typography.labelMedium,
                color = StarPalette.TextFaint,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            val target = segments.take(index + 1).joinToString("/")
            Crumb(text = segment, current = index == segments.lastIndex) { onNavigate(target) }
        }
    }
}

@Composable
private fun Crumb(text: String, current: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (current) StarPalette.Indigo.copy(alpha = 0.12f) else StarPalette.SurfaceAlt)
            // 当前这一层不响应点击——点它等于原地不动，只会让人以为界面卡了
            .let { if (current) it else it.clickable { onClick() } }
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (current) StarPalette.Indigo else StarPalette.TextMuted,
        )
    }
}

// ── 文件 ────────────────────────────────────────

@Composable
private fun FilePreview(navigator: Navigator, path: String) {
    val name = path.substringAfterLast('/')
    val extension = name.substringAfterLast('.', "").lowercase()
    val isImage = extension in IMAGE_EXTENSIONS

    Column(Modifier.fillMaxSize()) {
        StarTopBar(title = name, subtitle = path, onBack = { navigator.pop() })

        if (isImage) {
            val bytes = remember(path) { runCatching { LocalStores.current.readBytes(path) }.getOrNull() }
            if (bytes == null) {
                EmptyHint("读不到这个文件")
            } else {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) {
                    StarCard {
                        StarImage(
                            bytes = bytes,
                            modifier = Modifier.fillMaxWidth().height(280.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            }
            return@Column
        }

        val text = remember(path) { runCatching { LocalStores.current.readText(path) }.getOrNull() }

        when {
            text == null -> EmptyHint("这是二进制文件，没法在这里预览")

            // 会话 JSONL 会一直长。整份铺出来既慢又没法看，截一段并说清楚截了。
            text.length > PREVIEW_LIMIT -> Column {
                Text(
                    text = "文件较长，只显示前 ${PREVIEW_LIMIT / 1024} KB（共 ${formatBytes(text.length.toLong())}）",
                    style = MaterialTheme.typography.labelSmall,
                    color = StarPalette.TextFaint,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
                TextBody(text.take(PREVIEW_LIMIT))
            }

            text.isBlank() -> EmptyHint("这个文件是空的")

            else -> TextBody(text)
        }
    }
}

@Composable
private fun TextBody(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(StarPalette.SurfaceSunken)
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(14.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = StarPalette.Text,
        )
    }
}

// ── 读目录 ──────────────────────────────────────

/** 一层目录下的条目：子目录、文件，以及文件各自的大小。 */
private data class Entries(
    val directories: List<String>,
    val files: List<String>,
    val sizes: Map<String, Long>,
) {
    val isEmpty: Boolean get() = directories.isEmpty() && files.isEmpty()
}

/**
 * 只取**这一层**的条目。
 *
 * [LocalStores] 的 `list` 是递归的，所以要把更深一层的路径折回成它当层的目录名——
 * 浏览器是按层走的，一个目录下有多少层不该一次性摊平。
 */
private fun readEntries(path: String): Entries {
    val store = LocalStores.current
    val all = runCatching { store.list(path) }.getOrDefault(emptyList())
    val prefix = if (path.isEmpty()) "" else "$path/"
    val relative = all.map { it.removePrefix(prefix) }

    val directories = relative.filter { it.contains('/') }
        .map { it.substringBefore('/') }
        .distinct()
        .sorted()

    val files = relative.filter { !it.contains('/') }.sorted()
    val sizes = files.associateWith { name ->
        runCatching { store.size(joinPath(path, name)) ?: 0L }.getOrDefault(0L)
    }

    return Entries(directories = directories, files = files, sizes = sizes)
}

private fun joinPath(parent: String, child: String) =
    if (parent.isEmpty()) child else "$parent/$child"

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic")

/** 预览上限。超过就只显示开头一段——会话文件会一直长，整份铺出来没法看。 */
private const val PREVIEW_LIMIT = 64 * 1024
