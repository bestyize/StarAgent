# 无头运行与核心边界验证

在项目目录运行：

```powershell
.\gradlew.bat :samples:headless:run
```

示例使用固定模型响应和一个真实执行加法的工具，无需 API Key 或外部服务。
同一组输入分别由同步宿主直接驱动 `AgentLoopMachine`，以及 Kotlin 协程宿主驱动
`TurnRunner`；结果和回喂历史必须相同。协程路径还检查工具完成事件与一次资源收尾。

成功输出：

```text
HEADLESS_OK answer=42 model_calls=2 tool_calls=1 cleanup=1
```

`verifyRuntimeBoundary` 同时验证运行依赖没有 App 数据层、平台实现、Compose、
Ktor 或本地语音模型。它是依赖边界和受控行为的验证，真实模型、硬件与其他语言
实现仍需各自适配和实测。

当前分工：

- `core:llm-contracts`：模型请求、对话和响应事件，保留既有公开包名。
- `AgentLoopMachine`：状态、动作、轮数限制及工具结果匹配，无 I/O 或协程调用。
- `AgentLoop`：Kotlin 协程执行宿主。
- `ToolCallExecutor` / `ToolConfirmationPort`：可替换的执行与结构化确认边界。
- `AgentExtensions`：每个宿主自己的扩展装配、工具和 Prompt 贡献。

核心模型仍共享现有序列化、日期等领域类型；Kotlin 宿主使用协程与 JSON 解析。
本阶段保留 `ToolHost` 的端口字段，后续根据实际扩展需求继续收敛能力访问契约。
