package com.starringbox.staragent.core.tools

import com.starringbox.staragent.core.model.ToolDefinition
import com.starringbox.staragent.core.model.ToolResult
import kotlinx.serialization.json.JsonObject

/**
 * 一个可被模型调用的工具。
 *
 * [execute] 的 [args] 是**已经解析并校验过**的 JSON 对象——解析失败的调用
 * 不会走到这里，会在循环里直接变成一条 isError 的结果回喂给模型。
 * 所以实现里可以假定参数形状是对的（缺字段仍然要处理，见各工具自己的校验）。
 */
interface Tool {
    val definition: ToolDefinition

    /**
     * 该工具单独声明的执行超时（毫秒）。
     * null 时走循环引擎默认的 30 秒看门狗。
     * 人机交互类工具（如 request_user_input）可声明 5 分钟超时，避免误伤人类思考与打字时间。
     */
    val timeoutMillis: Long? get() = null

    suspend fun execute(args: JsonObject, host: ToolHost): ToolResult
}
