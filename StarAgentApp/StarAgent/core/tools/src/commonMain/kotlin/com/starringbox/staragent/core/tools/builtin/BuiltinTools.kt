package com.starringbox.staragent.core.tools.builtin

import com.starringbox.staragent.core.tools.ToolRegistry

/**
 * 一期内置的全部工具。
 *
 * 注册顺序是稳定的，模型看到的清单顺序就是它——顺序会影响模型的选择，
 * 所以别随手插在中间。
 */
object BuiltinTools {
    val all: ToolRegistry = ToolRegistry.of(
        RecallMemoryTool,
        RememberTool,
        CreateTaskTool,
    )
}
