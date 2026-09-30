package com.starringbox.staragent.core.tools.builtin

import com.starringbox.staragent.core.model.ToolDefinition
import com.starringbox.staragent.core.model.ToolResult
import com.starringbox.staragent.core.tools.Tool
import com.starringbox.staragent.core.tools.ToolHost
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 向用户发起交互提问并等待选择或输入。
 *
 * 在多步执行中遇到关键分支、需要用户拍板或二次确认时使用。
 */
object RequestUserInputTool : Tool {

    override val definition = ToolDefinition(
        name = "request_user_input",
        description = "向用户提出一个需要确认或选择的问题，并挂起等待用户作答。" +
            "当且仅当遇到关键决策分支（如确认班次、确定日期）、需要用户明确授权（如清理/重置操作）、" +
            "或需要用户从多个备选中选择时使用。" +
            "不要拿无关痛痒的问题反复打扰用户。",
        inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("question") {
                    put("type", "string")
                    put("description", "向用户提出的问题内容，清晰表达需要用户确认什么")
                }
                putJsonObject("options") {
                    put("type", "array")
                    put("description", "供用户快速单选的选项列表（建议 2~4 个），可选")
                    putJsonObject("items") { put("type", "string") }
                }
                putJsonObject("placeholder") {
                    put("type", "string")
                    put("description", "文本输入框的占位提示文字，引导用户输入自定义内容，可选")
                }
            }
            putJsonArray("required") { add("question") }
        },
    )

    // 人机交互需要思考与打字时间，给予 5 分钟超时，豁免通用工具的 30 秒看门狗
    override val timeoutMillis: Long = 300_000L

    override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
        val question = args.stringOrNull("question")?.trim()
        if (question.isNullOrBlank()) {
            return ToolResult(callId = "", content = "question 不能为空", isError = true)
        }

        val options = args["options"]?.let { el ->
            (el as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.trim() }
        }.orEmpty()

        val placeholder = args.stringOrNull("placeholder")?.trim()

        val answer = host.userInput.requestInput(
            agentId = host.agentId,
            question = question,
            options = options,
            placeholder = placeholder,
        )

        return ToolResult(
            callId = "",
            content = "用户给出的回答是：$answer",
        )
    }
}
