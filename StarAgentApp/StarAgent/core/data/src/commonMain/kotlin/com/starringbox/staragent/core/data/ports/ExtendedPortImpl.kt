package com.starringbox.staragent.core.data.ports

import com.starringbox.staragent.core.data.AppState
import com.starringbox.staragent.core.model.Skill
import com.starringbox.staragent.core.platform.storage.LocalStores
import com.starringbox.staragent.core.tools.ports.*
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import com.starringbox.staragent.core.llm.httpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull

private fun agentRoot(agentId: String) = "agent-sandbox/${agentId.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
private fun cleanPath(path: String): String? {
    val value = path.trim().replace('\\', '/')
    if (value.isBlank() || value.startsWith('/') || value.contains("..") || value.contains('\u0000')) return null
    return value.trimStart('/')
}

class AppFilePort : FilePort {
    override fun list(agentId: String, path: String): List<FileEntry> {
        val relative = cleanPath(path) ?: ""
        val prefix = agentRoot(agentId) + if (relative.isBlank()) "/" else "/$relative/"
        val files = LocalStores.current.list(prefix.removeSuffix("/"))
        return files.map { it.removePrefix("$prefix").let { p -> FileEntry(p, LocalStores.current.size(it) ?: 0L, false) } }
    }
    override fun read(agentId: String, path: String): String? = cleanPath(path)?.let { LocalStores.current.readText("${agentRoot(agentId)}/$it") }
    override fun write(agentId: String, path: String, content: String): FileOperationResult {
        val p = cleanPath(path) ?: return FileOperationResult(false, "路径不安全或为空")
        if (!LocalStores.available) return FileOperationResult(false, "本地文件存储未安装")
        LocalStores.current.writeText("${agentRoot(agentId)}/$p", content)
        return FileOperationResult(true, "已写入 $p")
    }
    override fun delete(agentId: String, path: String): FileOperationResult {
        val p = cleanPath(path) ?: return FileOperationResult(false, "路径不安全或为空")
        LocalStores.current.delete("${agentRoot(agentId)}/$p")
        return FileOperationResult(true, "已删除 $p")
    }
}

class AppSkillPort(private val state: AppState) : SkillPort {
    override fun list() = state.skills.map { SkillSummary(it.id, it.name, it.summary, it.installed) }
    override fun setInstalled(id: String, installed: Boolean): Boolean {
        return state.setSkillInstalled(id, installed)
    }

    override fun installFromFile(agentId: String, path: String): SkillInstallResult {
        val clean = path.trim().replace('\\', '/')
        if (clean.isBlank() || clean.startsWith('/') || clean.contains("..")) {
            return SkillInstallResult(false, message = "Skill 路径不安全")
        }
        val text = LocalStores.current.readText("agent-sandbox/${agentId.replace(Regex("[^A-Za-z0-9._-]"), "_")}/$clean")
            ?: return SkillInstallResult(false, message = "找不到 Skill 文件：$path")
        val front = text.substringAfter("---", "").substringBefore("---")
        fun field(name: String) = Regex("(?m)^$name\\s*:\\s*(.+)$").find(front)?.groupValues?.get(1)?.trim()
        val id = field("id") ?: clean.substringBeforeLast('/').substringAfterLast('/').ifBlank { clean.substringBeforeLast('.').substringAfterLast('/') }
        val name = field("name") ?: id
        val summary = field("summary") ?: text.substringAfterLast("---", text).lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (id.isBlank() || !id.matches(Regex("[A-Za-z0-9._-]+"))) return SkillInstallResult(false, message = "Skill id 不合法")
        val ok = state.addSkill(Skill(id, name, summary.take(200), "🧩", installed = true))
        return if (ok) SkillInstallResult(true, id, "已安装 Skill：$name") else SkillInstallResult(false, id, "Skill 已存在：$id")
    }

    override fun remove(id: String): Boolean = state.removeSkill(id)
}

class AppSearchPort : SearchPort {
    override suspend fun search(query: String, limit: Int): SearchResult = runCatching {
        val url = "https://html.duckduckgo.com/html/?q=" + encode(query)
        val html = httpClient().get(url).bodyAsText()
        val items = Regex("(?is)<a[^>]+class=\\\"result__a\\\"[^>]*href=\\\"([^\\\"]+)\\\"[^>]*>(.*?)</a>")
            .findAll(html).take(limit).map { m ->
                SearchItem(strip(m.groupValues[2]), m.groupValues[1], "")
            }.toList()
        SearchResult(true, items)
    }.getOrElse { SearchResult(false, emptyList(), "搜索失败：${it.message ?: "网络错误"}") }

    private fun encode(value: String) = value.trim().replace(" ", "+")
    private fun strip(value: String) = value.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
}

class AppBrowserPort(private val web: WebPort) : BrowserPort {
    override suspend fun open(url: String): BrowserResult {
        if (!url.startsWith("https://")) return BrowserResult(false, error = "只支持 https 地址")
        return if (com.starringbox.staragent.core.platform.platformOpenUrl(url)) {
            BrowserResult(true, "已在系统浏览器打开：$url")
        } else {
            BrowserResult(false, error = "当前平台无法打开系统浏览器")
        }
    }

    override suspend fun read(url: String): BrowserResult {
        val result = web.fetch(url)
        return if (result.ok) BrowserResult(true, result.text, externalContext = true)
        else BrowserResult(false, error = result.error ?: "网页读取失败")
    }
}
