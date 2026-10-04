# 离线语音

从 AiFaceTech 的 SenseVoice、Piper、Silero 实现适配到 StarAgent。此模块只依赖平台语音契约和协程，不依赖 AppState、Compose 或云协议模块。

## 使用

在“我的 → 语音配置”下载离线识别或离线朗读模型，再分别选择识别、朗读的“离线模型”。识别与朗读可以使用不同来源；朗读还可独立选择云端服务商和音色。回复朗读默认关闭。

- SenseVoice：固定版本 INT8 模型和词表，合计约 240 MB。
- Piper：固定版本中文模型和 FST/词表，合计约 21 MB。
- Silero VAD：约 2.3 MB，随应用提供；资源和 MIT 许可文本位于 `src/sharedResources/vad`。

明确选择离线模式时，不会静默切换到云端。自动模式依次尝试系统能力、已安装的离线模型、已配置的云端服务。模型缺失或损坏时在设置页提示下载/重装。

桌面缓存：`~/.staragent/models/asr`、`~/.staragent/models/tts`。Android 使用应用的 `noBackupFilesDir/models`。模型不会进入会话存档或 API Key 存储。

下载逐文件检查固定大小与 SHA-256，临时文件使用 `.part`。协程取消会断开下载连接并清理临时文件。删除只删除该模型列出的文件。运行时按需创建单个缓存引擎，安装、删除和 `release()` 都会释放引擎；单次 ASR stream 与 VAD detector 也会释放。

## 音频和流式接口

录音与 SenseVoice 输入为 16 kHz、单声道 PCM16 WAV。VAD 会遍历 RIFF chunks，不假定音频始终从第 44 字节开始；没有说话返回空结果，原生 VAD 不可用时保留原始音频并使用已有准入规则。

Piper 的实际输出采样率由引擎提供。流式输出转换到平台的 `PcmAudioChunk`；不会错误套用录音的 16 kHz WAV 头。PCM 写入块和缓冲区均按完整采样对齐，支持 22.05 kHz 等采样率。

断句和会话队列在 `core:data/speech`，平台 PCM 播放器在 `core:platform/voice`。句子队列容量 2、音频队列容量 8；停止会关闭当前语音任务和设备缓冲，不取消 LLM 文字生成。不同工具轮次和群成员之间会冲刷尾句。代码块和任务/记忆载荷不会被念出来。

## 平台与构建

| 平台 | SenseVoice / Piper / VAD | PCM 播放 |
| --- | --- | --- |
| Android | sherpa-onnx 1.13.8，静态链接 ONNX Runtime 的官方 AAR | AudioTrack |
| Desktop | sherpa-onnx JVM 与对应 OS/CPU native 包 | SourceDataLine |
| iOS | 尚不支持离线引擎，设置页不提供下载 | AVAudioEngine；仍需 Mac/真机验证 |

项目沿用 StarAgent 的 Kotlin/AGP/Ktor 版本。桌面跨平台构建可用 `-Pspeech.targetOs=linux -Pspeech.targetArch=arm64` 覆盖 native 包的目标，不能将为其它 OS 选择的包直接拿来在构建主机运行。

常规测试无需下载模型或配置密钥：

```powershell
.\gradlew.bat :core:speech-contracts:jvmTest :core:speech-local:jvmTest :core:speech-cloud:jvmTest :core:platform:jvmTest :core:data:jvmTest
```

已安装模型的端到端推理测试和桌面静音硬件测试是显式开启的检查：

```powershell
$env:STARAGENT_VERIFY_OFFLINE='1'
$env:STARAGENT_VERIFY_PCM='1'
.\gradlew.bat :core:speech-local:jvmTest :core:platform:jvmTest
```

`InstalledLocalSpeechTest` 验证 Piper 合成、按实际采样率重采样后由 SenseVoice 识别，以及 Piper PCM 回调。`DesktopPcmPlayerHardwareTest` 只写零样本，验证 drain、采样率切换、取消阻塞写入和重新播放。

## 模型来源

- SenseVoice 导出：`pengzhendong/sherpa-onnx-sense-voice-zh-en-ja-ko-yue`，revision `73eca47697f980daa3d16112404174b6b950b514`（ModelScope）。
- Piper 导出：`csukuangfj2/vits-piper-zh_CN-chaowen-medium-int8`，revision `19375e11252db7afffa04181e114adab3db4219b`（Hugging Face）。

本次保留源项目固定版本、校验值和资源许可；大模型权重按需获取，不内嵌安装包。固定导出版本的模型许可文本未从在线仓库成功取得；不将运行库或上游代码的许可证直接当作权重许可证。权重再分发的确认项记录在迁移验收文档中。
