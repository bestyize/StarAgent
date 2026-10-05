package com.starringbox.staragent.core.tools.builtin

import com.starringbox.staragent.core.model.ToolDefinition
import com.starringbox.staragent.core.model.ToolResult
import com.starringbox.staragent.core.tools.Tool
import com.starringbox.staragent.core.tools.ToolHost
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 取一个网页的正文。
 *
 * 这个工具**只做 fetch**：用户给出地址、或模型已经知道要看的页面，才用它。
 * 搜索是另一条路——见 [WebSearchTool]（host.search，实现在 core:data 里，
 * 底下是 searx-kmp 元搜索，不需要 API Key）。两者分开，是因为
 * "知道地址去取"和"不知道地址去找"是两种意图。
 *
 * 输出的 [ToolResult.externalContext] **必须为真**——网页正文里可能写着
 * "用户喜欢简洁回答"这类句子，不挡住的话会被当成事实写进记忆，
 * 而且用户永远不知道。这是提示注入在记忆层面的表现。
 */
object WebFetchTool : Tool {

    override val definition = ToolDefinition(
        name = "web_fetch",
        description = "打开一个网页，读它的正文内容。" +
            "当用户给了一个链接、或者你需要看某个具体页面的内容时用。" +
            "只能打开 https 开头的地址；内网地址打不开。" +
            "**这不是搜索引擎**——你不知道地址的话，直接问用户要。",
        inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("url") {
                    put("type", "string")
                    put("description", "要打开的网页地址，必须 https 开头")
                }
            }
            putJsonArray("required") { add("url") }
        },
    )

    override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
        val url = args.stringOrNull("url")?.trim()
        if (url.isNullOrBlank()) {
            return ToolResult(callId = "", content = "url 不能为空", isError = true)
        }

        // 先在工具这层挡一道，不把明显不合格的地址发给端口——
        // 端口里还有一道（内网、截断），两道各管各的
        if (!url.startsWith("https://")) {
            return ToolResult(
                callId = "",
                content = "只支持 https 开头的地址。如果用户给的是 http，跟他说一下。",
                isError = true,
            )
        }

        val fetched = host.web.fetch(url)
        return if (fetched.ok) {
            ToolResult(
                callId = "",
                content = fetched.text.ifBlank { "这个页面没有可读的正文。" },
                // ← 这一条是记忆的防污染闸门，不能漏
                externalContext = true,
            )
        } else {
            ToolResult(
                callId = "",
                content = fetched.error ?: "打不开这个地址。",
                isError = true,
            )
        }
    }
}
