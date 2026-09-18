package com.starringbox.staragent.core.platform.voice

/**
 * 语音能力的统一入口。
 *
 * 上层（对话页、数据层）只调这里，不关心底下是设备自带的能力还是某个大模型服务商。
 *
 * 两路后端分别由谁注入：
 * - **设备能力**由 [DeviceVoice.install] 装——三端各有一份实现，分别在各自的源集里；
 * - **模型能力**由数据层装，因为它要拿用户当前配置的服务商去发请求。
 *
 * 选取顺序：先看 [preference]；为 null（自动）时**优先设备能力**——
 * 它不要 Key、不产生费用，能用就没理由不走它。
 *
 * 指定了某一档时，**识别那条是独占的**：选中的那路不可用就报错，不会偷偷换回设备——
 * "点了云端识别却被换回设备"会让用户以为自己选中的东西没生效。
 * **合成那条会退回设备**，理由见 [pickSynthesizer] 的注释。
 * 两路都不行，才报一个能照着做的理由。
 */
object Voice {

    private var deviceTranscriber: Transcriber? = null
    private var deviceSynthesizer: Synthesizer? = null
    private var modelTranscriber: Transcriber? = null
    private var modelSynthesizer: Synthesizer? = null

    /**
     * 「优先走哪一路」的来源，由数据层注入一次。
     *
     * **这里刻意不自己存这份状态**。它以前是个可写 `var`，而设置界面只写了数据层那份、
     * 忘了同步过来——于是 `pickTranscriber` 读到的永远是 null，
     * 「选了云端识别」在真机上一直没生效，界面却显示得好好的。
     * 改成只读的来源之后，`AppState.voiceSource` 就是唯一的真相，不存在两处各写一半。
     */
    private var preferenceSource: (() -> VoiceSource?)? = null

    /** 数据层在这里接一根线：`installPreference { voiceSource }`。 */
    fun installPreference(source: (() -> VoiceSource?)?) {
        preferenceSource = source
    }

    /** 当前优先走哪一路。null = 自动。 */
    internal val preference: VoiceSource? get() = preferenceSource?.invoke()

    /**
     * 本次"听"用的是哪一路。
     *
     * 交互是"点一下开始、再点一下结束"，中间必须还是同一个后端——
     * 否则两次选取之间用户改了设置，就会拿 A 的会话去结束 B。
     */
    private var listening: Transcriber? = null

    fun installDevice(transcriber: Transcriber? = null, synthesizer: Synthesizer? = null) {
        deviceTranscriber = transcriber
        deviceSynthesizer = synthesizer
    }

    fun installModel(transcriber: Transcriber? = null, synthesizer: Synthesizer? = null) {
        modelTranscriber = transcriber
        modelSynthesizer = synthesizer
    }

    /** 现在能不能语音输入。界面据此决定麦克风入口显不显示。 */
    val canListen: Boolean get() = pickTranscriber() != null

    /** 现在能不能朗读。 */
    val canSpeak: Boolean get() = pickSynthesizer() != null

    /**
     * **这台设备有没有系统识别这条路**——装了就算有，不看 `ready`。
     *
     * 设置页拿它决定「系统能力」这个选项能不能选。刻意不用 [deviceListenReady]：
     * 那个在权限还没申请时就是 false，用它当开关等于"没授权 → 选项禁用 →
     * 永远没机会授权"（[Transcriber.ready] 那条约定的同一个坑，只是搬到了设置页）。
     *
     * 为 false 才是真的没有：桌面和 iOS 压根没装系统识别，
     * 这时设置页该说"这台设备不支持"，而不是让用户选一个选不动的东西。
     */
    val deviceListenInstalled: Boolean get() = deviceTranscriber != null

    /** 系统识别现在能不能真的用起来。只用来给提示文案，不用来禁选项。 */
    val deviceListenReady: Boolean get() = deviceTranscriber?.ready == true

    /** 模型那一路现在能不能听。为 false 通常是"还没配 Key 或没选识别模型"。 */
    val modelListenReady: Boolean get() = modelTranscriber?.ready == true

    /**
     * 这台机器有没有能用的采集设备。
     *
     * 走服务商识别时**录音是我们自己的**，没有麦克风就一步都走不了——
     * 而这跟"没配服务商"是完全不同的两件事，用户要做的动作也不同。
     * 设置页拿它区分这两种"用不了"，不然会让一个配好了 Key 的人
     * 反复去检查 Key。
     */
    val canRecord: Boolean get() = Recorders.available

    /** 正在听。界面据此把输入区换成"正在听…"。 */
    val isListening: Boolean get() = listening != null

    private fun pickTranscriber(): Transcriber? = when (preference) {
        VoiceSource.Device -> deviceTranscriber?.takeIf { it.ready }
        VoiceSource.Model -> modelTranscriber?.takeIf { it.ready }
        null -> deviceTranscriber?.takeIf { it.ready } ?: modelTranscriber?.takeIf { it.ready }
    }

    private fun pickSynthesizer(): Synthesizer? = when (preference) {
        VoiceSource.Device -> deviceSynthesizer?.takeIf { it.ready }

        // 合成这条**会**退回设备，识别那条不会——这个不对称是有意的。
        //
        // 「云端识别」这个档管的是**识别**（连按钮上写的就是这两个字），用户没有要求
        // 把朗读也一起换掉；而朗读本来就没有独立的档位，退回去不会让他以为"我选中了别的"。
        // 具体的坑：Groq 只做识别，选了它之后模型合成没有 endpoint，
        // 不放宽这一条的话，Android 上明明有系统朗读却会整个哑掉。
        VoiceSource.Model ->
            modelSynthesizer?.takeIf { it.ready } ?: deviceSynthesizer?.takeIf { it.ready }

        null -> deviceSynthesizer?.takeIf { it.ready } ?: modelSynthesizer?.takeIf { it.ready }
    }

    /** 开始听。 */
    suspend fun startListening(): SpeechOutcome<Unit> {
        if (listening != null) return SpeechOutcome.Ok(Unit) // 已经在听了，再点一下是结束
        val transcriber = pickTranscriber() ?: return SpeechOutcome.Failed(whyNoListening())
        if (!transcriber.start()) {
            return SpeechOutcome.Failed("没能开始录音。检查一下麦克风权限，或者换一路语音引擎试试。")
        }
        listening = transcriber
        return SpeechOutcome.Ok(Unit)
    }

    /** 结束并取回文字。 */
    suspend fun stopListening(): SpeechOutcome<String> {
        val transcriber = listening ?: return SpeechOutcome.Failed("没有正在进行中的录音")
        listening = null
        return transcriber.finish()
    }

    /** 放弃这次录音。 */
    fun cancelListening() {
        listening?.cancel()
        listening = null
    }

    /** 念一段文字。 */
    suspend fun speak(text: String): SpeechOutcome<Unit> {
        if (text.isBlank()) return SpeechOutcome.Ok(Unit)
        val synthesizer = pickSynthesizer() ?: return SpeechOutcome.Failed(whyNoSpeaking())
        return synthesizer.speak(text)
    }

    /**
     * 打断正在念的内容。
     *
     * 两条路都停，不去判断"刚才是谁在念"——停止本身是幂等的，猜错反而会漏掉一路。
     * 不这么写的话，换过引擎设置、或者自动模式下设备那路顶上来之后，
     * 停的就永远是另一条。
     */
    fun stopSpeaking() {
        deviceSynthesizer?.stop()
        modelSynthesizer?.stop()
    }

    /**
     * 说清楚为什么不能听。
     *
     * 刻意区分"这台设备的系统识别起不来"和"没配服务商"——这两种情况用户要做的事完全不同，
     * 一句"语音不可用"等于没说。
     *
     * 注意这里**不提权限**：权限被拒时 [start] 会自己给出提示，走不到这儿来
     * （见 [Transcriber.ready] 关于"权限不算能力"的约定）。
     */
    private fun whyNoListening(): String = when {
        // 服务商识别是唯一的路，而这台机器没有麦克风——再怎么配 Key 也不会好。
        // 这一条必须排在"没配服务商"前面，否则会让配好了的人去反复检查 Key。
        deviceTranscriber == null && modelTranscriber != null && !Recorders.available ->
            "这台机器没有可用的麦克风，录不了音，所以语音输入用不了。"

        deviceTranscriber != null && modelTranscriber != null ->
            "语音输入现在用不了：这台设备的系统识别起不来，云端那路也还没配好。去「我的 → 语音」选一家服务商并填上 Key。"

        modelTranscriber != null ->
            "语音输入需要先在「我的 → 语音」选一家服务商并填上 Key。"

        deviceTranscriber != null -> "这台设备的系统识别用不了。"

        else -> "这个平台还没有接入语音输入。"
    }

    private fun whyNoSpeaking(): String = when {
        deviceSynthesizer != null && modelSynthesizer != null ->
            "朗读现在用不了：系统合成起不来，云端那路也还没配好。"

        modelSynthesizer != null ->
            "朗读需要先在「我的 → 语音」选一家能做合成的服务商（智谱 GLM 或 OpenAI）并填上 Key。"

        deviceSynthesizer != null -> "系统朗读现在用不了。"

        else -> "这个平台还没有接入朗读。"
    }
}
