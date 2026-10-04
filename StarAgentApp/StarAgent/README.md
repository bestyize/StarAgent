# StarAgent · 星环智能 Agent（一期）

移动端 AI Agent。一期目标是把"支持自定义大模型的、完善的 AI 智能体"做扎实：
对话与多模态交互、长期记忆、任务托管、技能、渠道、模型自由。
真机操作作为二期目标。

技术栈：**Kotlin Multiplatform + Compose Multiplatform**，一套代码支撑 Android / iOS / Desktop。

---

## 先配一个模型

应用**不内置任何 API Key**，第一次打开需要自己配一个：

**我的 → 模型服务 → 选一家（或「添加自定义服务商」）→ 填 API Key → 获取模型列表 → 勾选模型 → 保存**

「获取模型列表」会真的去那家服务商拉一次当前可用的模型清单，不用手打模型名。
Key 只保存在本机（桌面 `java.util.prefs`、Android `SharedPreferences`、iOS `NSUserDefaults`），
配置好之后重启不用重填。

支持的协议：

| 协议 | 请求 | 鉴权 | 典型服务商 |
| --- | --- | --- | --- |
| OpenAI 兼容 | `POST {base}/chat/completions` | `Authorization: Bearer` | DeepSeek、通义千问、智谱 GLM、Kimi、多数自建端点 |
| Anthropic | `POST {base}/messages` | `x-api-key` + `anthropic-version` | Claude |

自定义服务商时由用户在这两种协议之间二选一。

---

## 模块结构

```
StarAgent/
├── core/                    核心层：不含业务界面，被所有功能模块依赖
│   ├── model/               领域模型（纯 Kotlin，不依赖 Compose）
│   ├── designsystem/        主题、图标、通用组件、导航契约
│   ├── platform/            平台能力层：本地存储 / 选图保存 / 设备语音
│   │                        （内部分包，三端实现各在自己的源集里）
│   ├── llm/                 模型接入：协议实现 + 流式解析 + 工具 wire 渲染（不依赖 Compose）
│   ├── llm-contracts/       模型请求、对话、响应事件（没有网络或平台实现）
│   ├── agent-runtime/       无 I/O 循环规则、Kotlin 执行宿主与扩展装配
│   ├── speech-contracts/    语音契约：识别 / 合成 / 录放接口、PCM / WAV、准入规则（纯 Kotlin）
│   ├── speech-cloud/        云端语音：OpenAI 兼容 / 火山方舟 / 小米 MiMo
│   ├── speech-local/        SenseVoice / Piper / Silero 离线语音与模型管理
│   ├── tools/               工具执行层：契约、注册表、内置工具（只依赖 core:model）
│   ├── tool-host/           工具端口的各端实现：Android 无障碍设备操作、桌面命令与 MCP、权限
│   ├── data/                应用状态、模型服务配置、对话流、移动产品装配与展示
│   │   ├── speech/          语音控制器：引擎、服务商与 Key、离线模型、朗读
│   │   └── ModelConfigController.kt  模型服务：服务商、Key、模型、默认模型、回复模式
│   └── bootstrap/           装配层：启动时把各端的平台能力一次装起来
│
├── feature/                 功能层：一个模块 = 一块完整功能
│   ├── agents/              智能体：列表、创建、配置
│   ├── chat/                对话：消息流、内联卡片、输入坞
│   ├── tasks/               任务中心
│   └── settings/            我的：模型服务、IM 渠道、记忆、技能
│
├── shared/                  应用外壳：组装功能模块、导航宿主、三端共用入口
├── androidApp/              Android 入口
├── desktopApp/              桌面入口（开发预览用，窗口按手机比例）
└── iosApp/                  iOS 入口
```

### 依赖方向

```
                         ┌──────────────┐
                         │    shared    │  外壳 + 导航宿主
                         └──────┬───────┘
              ┌─────────────────┼─────────────────┐
              ▼                 ▼                 ▼
      ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
      │feature:agents│  │ feature:chat │  │ …:tasks/settings
      └──────┬───────┘  └──────┬───────┘  └──────┬───────┘
             └─────────────────┼─────────────────┘
                               ▼
                    ┌────────────────────┐
                    │  core:designsystem │  主题 / 图标 / 组件 / 导航契约
                    │      core:data     │  状态 / 模型配置 / 对话流 / Agent 循环
                    │   core:tool-host   │  设备操作 / 命令 / MCP / 权限的各端实现
                    │     core:tools     │  工具契约 / 注册表 / 内置工具
                    │      core:llm      │  协议实现 + 流式解析 + 工具 wire 渲染
                    │    core:platform   │  存储 / 选图保存 / 设备语音
                    │    core:speech-*   │  语音契约 / 云端语音 / 离线语音
                    │     core:model     │  领域模型（零平台依赖）
                    └────────────────────┘
```

`core:bootstrap` 不在上面这张图里——**它只被各端入口依赖**，`shared` 和功能模块都不碰它，
详见下面「平台相关代码」。

规则：

- **功能模块之间互不依赖。** 需要跨模块跳转时，走 `core:designsystem` 里的 [Route](core/designsystem/src/commonMain/kotlin/com/starringbox/staragent/core/designsystem/navigation/Navigation.kt) 与 `Navigator`，不直接引用对方的界面。
- **只有 push / pop 才做转场动画**，Tab 之间是平级切换、不加动画。方向随 `NavTarget` 一起交给界面，不要分别去读「当前路由」和「上一次动作」——两次读取之间状态可能已经变了，动画会选错方向。转场也不用淡入淡出：整屏页面叠加时两边都是半透明，会看到重影。
- **`core:model` 与 `core:llm` 不依赖 Compose，`core:llm` 也不依赖 `core:platform`。** 协议层保持纯粹，将来可以独立复用或搬到服务端。
- **`core:platform` 不依赖 `core:tools`。** 工具端口的平台实现放在 `core:tool-host`，只想用存储、媒体或语音的模块不必认识工具层。
- **语音三路实现只经由 `core:speech-contracts` 对齐。** 设备实现在 `core:platform`，云端在 `core:speech-cloud`，离线在 `core:speech-local`，彼此互不依赖。
- **`core:designsystem` 不含业务逻辑。** 只有主题、图标、通用组件和导航契约。
- **界面层只读 `AppState` / 调它的方法**，不自己持有业务状态，也不直接发网络请求。
  按领域拆出的状态挂在 `AppState` 下面，界面同样只经由它访问：语音（引擎偏好、服务商与 Key、离线模型、听写与朗读）在 `state.speech`（[SpeechController](core/data/src/commonMain/kotlin/com/starringbox/staragent/core/data/speech/SpeechController.kt)），模型服务（服务商、Key、模型、默认模型、回复模式、执行策略）在 `state.models`（[ModelConfigController](core/data/src/commonMain/kotlin/com/starringbox/staragent/core/data/ModelConfigController.kt)）。`AppState` 上的 `endpointFor` / `providers` 等仍然转发过去，`Seed` 和 `CompanionVoiceSession` 这类内部调用方不用改。

新增一个功能时：在 `feature/` 下建模块 → 在 `settings.gradle.kts` 注册 → 若需新页面，在 `Route` 里加一个目的地 → 在 `shared/App.kt` 的 `RouteHost` 里接上。

---

## 平台相关代码

通用执行核心已支持独立运行。`AgentLoopMachine` 处理状态推进，`AgentLoop` 是
Kotlin 协程宿主，扩展通过 `AgentExtensions` 或 `AppState(extensionFactory = ...)`
显式装配。运行时只依赖模型契约、工具和领域模型，无头示例不携带 Compose、网络
或平台实现，见 [samples/headless](samples/headless/README.md)：

```powershell
.\gradlew.bat :samples:headless:run
.\gradlew.bat :core:agent-runtime:jvmTest
```

按平台实现的代码集中在 `core:platform` 里；各端入口**只负责把它们装起来**：

| 位置 | 桌面 | Android | iOS |
| --- | --- | --- | --- |
| `core:llm` 的 HTTP 引擎 | CIO | OkHttp | Darwin |
| `core:data` 的本地键值存储 | `java.util.prefs` | `SharedPreferences` | `NSUserDefaults` |
| `core:platform` 本地存储 | `DesktopLocalStore` | `AndroidLocalStore` | 尚未接入 |
| `core:platform` 选图 / 保存 | `DesktopMediaPicker/Saver` | `AndroidMediaPicker/Saver` | 尚未接入 |
| `core:platform` 语音 | 录音 + 播放 | 四样齐全（录放 + 系统识别/合成） | 四样已写，未在真机验证 |

**装配集中在 `core:bootstrap`**：各端入口只调一次 `PlatformInstall.install(...)`——
Android 传 `Context` 和 `ActivityResultCaller`（**必须在 `super.onCreate` 之前**），
桌面和 iOS 不传参。Android 上 `SettingsStore` 需要的 Context 也由它注入。

为什么把"实现"和"什么时候装"分成两层：实现在 `core:platform` 各自的源集里，
装配在 `core:bootstrap` 里。合在一起的话，各端入口要自己记住装哪几样、按什么顺序；
分开之后入口只剩一行，漏装一样会很明显。

---

## 存档

内容存成**一棵目录树**，不是一份大 JSON：

```
~/.staragent/                      Android 上是 /data/data/<包名>/files/staragent/
├── layout.json                    版本 + 列表顺序。最后写，它的存在 = 这棵树完整
├── config.json                    渠道、已安装的技能
├── agents/<id>/
│   ├── SOUL.md                    人格正文，用户手写的那部分
│   ├── agent.json                 名字/图标/配色/模型/技能/置顶/能否托管任务
│   └── MEMORY.md                  记忆，按五个分类分节
├── skills/<id>/SKILL.md
├── groups/<id>.json
├── sessions/agents/<id>.jsonl     一行一条消息
├── sessions/groups/<id>.jsonl
├── tasks/tasks.json
├── media/<id>.<ext>
└── backups/
    ├── state.v1.json              迁移前的旧存档，原样留档
    └── broken/                    读不出来的文件挪到这
```

**为什么拆开**：单份 `state.json` 的失败模式是"一个字节坏 → 整份读不出 → 退回种子 →
用户内容全没"。拆开之后坏一个只坏一个，其余照常加载，坏文件进 `backups/broken/`
并在设置页里报出来。顺带解决两件事：只有变了的文件会被重写（不是每次改动重写全部），
以及智能体成了一个能被单独带走的文件夹。

几个不那么显然的决定：

- **`SOUL.md` 和 `agent.json` 是两个文件**。前者是用户手写的散文，后者是 App 会改的状态。
  合成一个的话，用户每点一次"置顶"，App 就要重写一遍他手写的字。
- **列表顺序记在 `layout.json` 里**。扫描目录只能得到字母序，而顺序在界面上是可见的。
  顺序坏了只坏观感、不会少数据，所以放在这份元信息里而不是每个实体自己的文件里。
- **`sessions/` 分 agents 和 groups 两层**。会话键里有 `group:g-1`，而 `:` 在 Windows
  文件名里非法——分层是不用做键编码就绕开它的办法。
- **`layout.json` 最后写**。中途断电时下次启动看到的还是"只有旧的 `state.json`、
  没有 `layout.json`"，于是重新迁一遍——幂等，而且旧数据一直没被删。
- **密钥不进这棵树**。API Key 留在 `SettingsStore` 里：设置页的文件浏览器能预览文件内容，
  把密钥放进可浏览的树等于一点就能看到明文。

`LocalStore`（`core:platform` 的 storage 包）只有"按名字读写 + 列举 + 大小 + 根路径"，
不认识 JSON、不认识任何业务类型——怎么组织数据是 `core:data` 的事，见 `StateTree.kt`。

**看它长什么样**：「我的」右上角齿轮 → 设置 → 右上角文件夹，或者直接去
`~/.staragent/` 用文件管理器打开。

---

## 多模态

图片能力在 `core:platform` 的 media 包下，职责边界划得很死——**只负责"拿到一张图，并把它变成可传输的形式"**。
资源表示本身则在 `core:model`：

```
core:model
└── MediaAsset   资源表示（bytes + mimeType），带 base64() / dataUrl() 两种编码

core:platform  的 media 包
├── MediaRules   格式与体积准入规则，不合格的在本地就挡下来
├── MediaPicker  选取契约 + 注册表
└── MediaSaver   保存契约 + 注册表
```

`MediaAsset` 放在 `core:model` 而不是平台层，是因为它是**领域类型**：
`MessageBody.Image` 和 `LlmTurn.images` 都要用它。反过来让领域模型去依赖平台能力层是错的，
所以它上移之后 `core:model` 仍然零平台依赖——原来那条 `api(core:media)` 已经去掉了，
改成 `core:platform` 依赖 `core:model`。

**为什么选图不做成 `expect/actual`**：Android 的选图必须挂在 Activity 的 `registerForActivityResult` 上，
用 `expect` 函数凭空造一个出来是做不到的。所以改成"平台注册、上层取用"——
各端实现放在 `core:platform` 各自的源集里，由 `core:bootstrap` 装进注册表；
契约所在的 `commonMain` 本身不认识任何平台。语音也是同一套形状，理由见那边 `Recorder.kt` 的注释。

依赖方向因此是单向且干净的：

```
core:model  ←  core:platform  ←  core:bootstrap
（叶子，零平台依赖）  （存储 / 选图 / 语音）   （只被各端入口依赖）
```

界面侧 `StarImage` 只接收 `ByteArray`、不接收 `MediaAsset`，
所以设计系统也不必反向依赖业务类型。全屏查看器 `StarImageViewer` 同理——
它把"保存"暴露成 `onSave: (Int) -> Unit` 回调，由对话页接到 `MediaSavers` 上。

**两家协议的图片格式完全不同**，这是接多模态最容易踩的坑，`core:llm` 里分了两套实现并用测试钉住：

| | 块类型 | base64 形式 |
| --- | --- | --- |
| OpenAI 兼容系（含 DeepSeek、千问、智谱、Kimi、MiniMax、MiMo、火山、OpenRouter） | `image_url` | **data URL**，带 `data:image/png;base64,` 前缀 |
| Anthropic | `image` | **裸 base64**，MIME 类型单独放 `media_type`，带前缀会 400 |

---

## 运行

### 常驻智能伙伴

底部导航为「首页 / 任务 / 伙伴 / 记忆 / 我的」。中间的「伙伴」直接进入星伴的专属页面，支持文字与语音交流；流动球体会根据聆听、思考与朗读状态变化，并在聆听时跟随麦克风音量。页面支持深浅外观与跟随系统。

星伴可以编辑名称、人设、模型与形象配色，不能删除。她的固定身份 `staragent-companion` 关联同一份对话与记忆；旧存档首次升级只补入这位伙伴，后续启动保留所有编辑。普通智能体仍按原来的方式管理。

语音对话复用现有识别与朗读配置，采用轮流聆听和回应的方式：停顿后提交，回复播放结束后继续聆听，也可以手动发送本轮、暂停麦克风或切回文字。Android 与 Desktop 的录音支持静音收尾；设备不支持自动收尾时可手动发送。进入语音模式会为本次对话启用朗读，不改变全局的回复朗读开关。离开伙伴页面会取消当前录音、识别与播放。

```bash
# 桌面端（开发预览，窗口按手机比例）
./gradlew :desktopApp:run

# Android
./gradlew :androidApp:assembleDebug

# iOS：用 Xcode 打开 iosApp/ 目录运行

# 测试
./gradlew :core:data:jvmTest
```

Android 本地构建需要 `local.properties` 中的 `sdk.dir` 指向本机 SDK。

---

## 一期交互形态

三 Tab（对话 / 任务 / 我的），首页是**智能体联系人列表**——产品终局是"住在 IM 里的 Agent"，
所以 App 本身按 IM 的心智来组织：你的联系人都是智能体。

三个关键设计：

1. **对话即任务创建（按智能体开关）。** 开启后，系统提示会要求模型在判断出可托管意图时，
   于回复末尾附一个 ` ```task ` JSON 块；解析器把它变成任务草稿卡片，点「创建」才真正落任务。
   关掉时提示里根本不出现任务指令，就只是聊天。**新智能体默认关闭**——
   只有"把事情交出去"才需要它，陪聊、答疑类的智能体不该动不动就弹卡片。
   解析失败按普通回复处理：模型没按格式输出，绝不能因此让用户看不到它的回答。
2. **产物沉淀。** 一期智能体没有"手"，交付感全靠产物卡片——
   把结果从"聊天流里的一段文字"变成"一个能带走的成果"。
3. **状态位常驻。** 对话页顶栏一直显示 `模型 · 已连接渠道`，
   把"模型自由"和"IM 连接"做成持续可见的状态，而不是藏在设置里的开关。

一个刻意的取舍：**响应模式在前，模型选择在后**。用户关心的是"这次要快还是要深"，
不是模型的名字；但"模型自由"是立身之本，所以两者都给，只是排序不同。

设计依据见仓库 `docs/` 目录下的《产品白皮书》与《一期交互形态设计》。

---

## 接入真实后端

对话已经直连用户自己配置的大模型服务。剩下需要接的：

| 位置 | 现状 | 替换为 |
| --- | --- | --- |
| [Seed](core/data/src/commonMain/kotlin/com/starringbox/staragent/core/data/Seed.kt) | 内存种子数据（智能体、任务、记忆） | 持久化仓储 |
| 任务调度 | 只有展示，没有真正的定时触发 | 平台侧的后台调度 |
| IM 渠道 | 开关式模拟绑定 | 真实的渠道接入 |
| 记忆 | 静态列表，没有参与推理 | 写入 / 召回链路 |

`AppState` 是界面的唯一数据源，界面层不需要改动。

---

## 已知待办

- `core` / `feature` 各模块的 `build.gradle.kts` 目前是逐模块复制的最小 KMP 配置，
  模块再多一些时值得抽成 `buildSrc` 约定插件。
- iOS 目标只写了代码，尚未在 Mac 环境编译验证过。

## 当前 Agent 工具

除记忆、任务、网页读取和用户提问外，当前工具注册表还包含：

- `list_files` / `read_file` / `write_file` / `delete_file`：智能体专属文件沙箱
- `create_artifact`：生成 HTML、SVG、Markdown、文本、JSON、CSV 与常见图片文件，并写入该智能体的沙箱
- `run_command`：受平台限制的命令执行（Desktop 可用，Android/iOS 默认禁用）
- `web_search`：DuckDuckGo HTML 搜索适配
- `list_skills` / `set_skill_enabled` / `install_skill` / `remove_skill`：查看、安装、启停和卸载本地 Skill

HTML 和图片产物会在聊天里显示可点击的预览卡片；Android 使用内置 HTML 预览，普通文本与 Markdown 显示在文件预览页。定时任务点击通知后，若本次生成了文件则直接打开该产物，否则进入任务详情查看最近结果。

文件写入、删除、命令执行和 Skill 状态变更会在“按需确认”策略下先请求用户确认。动态下载 Skill、MCP、浏览器控制和任意第三方 App 读屏/点击仍需后续阶段接入。

## MCP 工具扩展

MCP 工具已经接入统一 Tool 系统：

```kotlin
McpRegistry.install(
    port = myMcpPort,
    descriptors = descriptorsFromServer,
)
```

注册后会自动出现在每个 Agent 的工具列表中，工具名格式为：

```text
mcp__<serverId>__<toolName>
```

MCP 工具默认要求用户确认，并通过 `agentId` 保持调用隔离；MCP 返回内容默认视为外部上下文，不会直接写入长期记忆。当前仓库已经完成协议/注册/调用适配层，具体 stdio、HTTP 或 Streamable HTTP transport 仍需由平台连接器实现。

### Desktop stdio MCP

Desktop 端可显式启动一个本地 MCP Server：

```kotlin
val tools = DesktopMcp.connect(
    serverId = "filesystem",
    command = "npx",
    args = listOf("-y", "@modelcontextprotocol/server-filesystem", "/tmp/staragent"),
)
```

连接过程会执行：

```text
启动进程 → initialize → notifications/initialized → tools/list → 注册工具
```

后续 Agent 调用会复用该 MCP 进程，通过 `tools/call` 执行。应用不会自动执行外部命令；连接必须由 Desktop 入口或设置页面显式发起。

### 浏览器工具

当前新增：

- `browser_open`：Desktop 打开系统默认浏览器；移动端未注入时返回不可用
- `browser_read`：复用安全网页抓取能力读取 HTTPS 正文，并标记为外部上下文

这两个工具不等同于自动点击任意第三方 App；浏览器内的点击、输入和页面会话仍需要后续 WebView/浏览器驱动适配。

### MCP Server 管理工具

Agent 现在可以在 Desktop 上管理 MCP Server：

- `mcp_list_servers`：列出已连接 Server 和工具数量
- `mcp_connect`：经用户确认后启动本地 MCP 进程并发现工具
- `mcp_disconnect`：经用户确认后断开 Server

Android/iOS 不会启动本地 MCP 进程，相关调用会返回平台不支持。Desktop 连接仍不会自动持久化到下次启动，避免应用启动时未经用户同意执行外部命令。

### 测试环境说明

默认情况下，`allTests` 会跳过需要真实 iOS Simulator Runtime 的 `iosSimulatorArm64Test`，但仍会编译 iOS 模拟器目标并运行 JVM 测试。安装了 Xcode Simulator Runtime 后，可显式运行：

```bash
./gradlew allTests -Pstaragent.runIosSimulatorTests=true
```


## Android permission tool

Agent 可以通过内置的 `request_permission` 工具按功能申请权限：

- `microphone`、`notifications`：显示 Android 运行时权限弹窗；
- `accessibility`：直接打开 Android 无障碍设置，用户手动启用「星环智能 Agent」后，返回时自动复查；
- `exact_alarm`：打开当前应用的精确闹钟权限页，返回时自动复查；
- `overlay`：打开「显示在其他应用上层」设置，返回时自动复查。

工具只接受上述固定能力名，不接受任意系统权限字符串。Android 系统负责最终授权；无障碍、精确闹钟和悬浮窗都需要用户在系统界面确认。桌面端和 iOS 当前返回平台不支持。

## Android device_use

Android 端新增了受控设备工具：

- `device_inspect`：读取当前前台窗口的可访问控件树，返回短期有效的 `screen_id` / `element_id`
- `device_use`：经用户确认后执行 `click`、`type`、`swipe`、`back`、`launch_app`

首次使用前，需要用户在 Android 系统设置中手动启用「星环智能 Agent」无障碍服务。跨 App 操作会启动用户可见的前台操作服务；若已授予悬浮窗权限，控制球只在目标 App 前台时显示，可返回星环或结束手机操作。未授予时，前台服务通知作为备用返回入口，实际展示方式遵循 Android 的通知权限规则。AgentLoop 结束、取消或用户停止手机操作后，服务会收尾。句柄只保留 15 秒，并且会校验 screen、前台包名和控件路径，页面变化后必须重新 `device_inspect`。密码字段不会返回文本内容。

Desktop 和 iOS 当前通过统一 `DevicePort` 返回明确的未安装/不支持状态；后续分别接入 macOS Accessibility、Windows UI Automation，以及 iOS 自有页面/App Intents 能力。
