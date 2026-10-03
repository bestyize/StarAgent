package com.starringbox.staragent.core.data.extensions

import com.starringbox.staragent.core.data.MemoryBlock
import com.starringbox.staragent.core.data.TaskBlock
import com.starringbox.staragent.core.model.Agent
import com.starringbox.staragent.core.model.MemoryItem
import com.starringbox.staragent.core.model.Skill
import com.starringbox.staragent.core.tools.Tool
import com.starringbox.staragent.core.tools.builtin.*

/**
 * 记忆扩展：提供记忆回忆/记录工具，以及系统提示中关于用户的长期记忆注入。
 */
class MemoryExtension(
    private val memoriesOf: (agentId: String) -> List<MemoryItem>,
    private val maxPromptMemories: Int = 20,
) : AgentExtension {
    override val id = "memory"

    override fun tools(agent: Agent): List<Tool> = listOf(
        RecallMemoryTool,
        RememberTool,
    )

    override fun contributePrompt(agent: Agent, toolsAvailable: Boolean): String? {
        if (!toolsAvailable) return MemoryBlock.INSTRUCTION
        val known = memoriesOf(agent.id).take(maxPromptMemories)
        if (known.isEmpty()) return null
        return buildString {
            append("【你已经记得的关于他的事】")
            known.forEach { append('\n').append("- ").append(it.text) }
            append("\n自然地用上这些，不要刻意复述，也不要逐条罗列。")
        }
    }
}

/**
 * 技能扩展：提供符合 agentskills.io 标准的技能体系。
 * 渐进披露：每轮系统提示仅展示已启用技能的目录，完整说明与附带文件通过工具按需读取。
 */
class SkillExtension(
    private val installedSkills: () -> List<Skill>,
    private val maxListedDescription: Int = 200,
    private val maxInlineChars: Int = 1_500,
) : AgentExtension {
    override val id = "skills"

    override fun tools(agent: Agent): List<Tool> = listOf(
        ListSkillsTool,
        UseSkillTool,
        ReadSkillFileTool,
        SetSkillInstalledTool,
        InstallSkillTool,
        CreateSkillTool,
        RemoveSkillTool,
    )

    override fun contributePrompt(agent: Agent, toolsAvailable: Boolean): String? {
        val listed = installedSkills().filter { it.installed && it.modelInvocable }
        if (listed.isEmpty() && !toolsAvailable) return null

        return buildString {
            if (listed.isNotEmpty()) {
                append("【已启用的技能】")
                listed.forEach { skill ->
                    append('\n').append("- ").append(skill.name).append("（id=").append(skill.id).append("）：")
                        .append(skill.summary.take(maxListedDescription))
                    if (!toolsAvailable && skill.instructions.isNotBlank()) {
                        append('\n').append(skill.instructions.take(maxInlineChars).prependIndent("  "))
                    }
                }
                if (toolsAvailable) {
                    append("\n用户的请求用得上其中某个技能时，先调用 use_skill 读取它的完整说明，再按说明去做；同一轮里读过的不必重复读。")
                }
            }
            if (toolsAvailable) {
                if (isNotEmpty()) append("\n\n")
                append("如果你刚帮用户完成了一件步骤较多、以后很可能还会再做的事，可以提议用 create_skill 把做法沉淀成技能；保存前会请用户确认。")
            }
        }
    }
}

/**
 * 任务扩展：提供托管任务查看、调整与取消工具；当智能体允许调度时额外提供建任务工具。
 */
class TaskExtension : AgentExtension {
    override val id = "tasks"

    override fun tools(agent: Agent): List<Tool> = if (agent.canScheduleTasks) {
        listOf(CreateTaskTool, ListTasksTool, UpdateTaskTool, CancelTaskTool)
    } else {
        listOf(ListTasksTool, UpdateTaskTool, CancelTaskTool)
    }

    override fun contributePrompt(agent: Agent, toolsAvailable: Boolean): String? {
        return if (!toolsAvailable && agent.canScheduleTasks) {
            TaskBlock.INSTRUCTION
        } else null
    }
}

/**
 * 设备操作扩展：提供移动端跨应用操作、界面层级检查与权限申请工具。
 */
class DeviceExtension : AgentExtension {
    override val id = "device"

    override fun tools(agent: Agent): List<Tool> = listOf(
        DeviceInspectTool,
        DeviceUseTool,
        ListAppsTool,
        RequestPermissionTool,
    )

    override fun contributePrompt(agent: Agent, toolsAvailable: Boolean): String? {
        return if (toolsAvailable) {
            """
            【移动端设备操作与系统权限规范】
            当你需要在手机上操作应用（如点击、滑动、输入、查看界面）时：
            1. 若无障碍服务未开启或工具报错提示未连接，请先调用 request_permission(capability="accessibility") 协助用户跳转到系统设置开启。用户开启返回后系统会自动接续，切勿盲目重复点击或空报失败。
            2. 启动第三方应用并进行跨应用屏幕操作时，悬浮窗权限（overlay）可在前台提供实时操作进度与控制胶囊。
            """.trimIndent()
        } else null
    }
}

/** 网页检索与浏览器扩展。 */
class WebExtension : AgentExtension {
    override val id = "web"

    override fun tools(agent: Agent): List<Tool> = listOf(
        WebSearchTool,
        WebFetchTool,
        BrowserOpenTool,
        BrowserReadTool,
    )
}

/** 文件沙箱与产物交付扩展。 */
class FileExtension : AgentExtension {
    override val id = "files"

    override fun tools(agent: Agent): List<Tool> = listOf(
        ListFilesTool,
        ReadFileTool,
        WriteFileTool,
        DeleteFileTool,
        CreateArtifactTool,
    )
}

/** 命令行执行扩展。 */
class CommandExtension : AgentExtension {
    override val id = "commands"

    override fun tools(agent: Agent): List<Tool> = listOf(RunCommandTool)
}

/** 人机协作交互输入扩展。 */
class UserInputExtension : AgentExtension {
    override val id = "user_input"

    override fun tools(agent: Agent): List<Tool> = listOf(RequestUserInputTool)
}

/** 模型上下文协议（MCP）扩展。 */
class McpExtension : AgentExtension {
    override val id = "mcp"

    override fun tools(agent: Agent): List<Tool> = listOf(
        McpListServersTool,
        McpConnectTool,
        McpDisconnectTool,
    )
}
