package com.tuya.smart.ai_voice.nativeui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.thingclips.smart.ai.audio.utils.PhoneMicAudioInputFactory
import com.thingclips.smart.earphone.enhance.api.bean.RealTimeTransferStatus
import com.thingclips.smart.earphone.enhance.api.bean.RecordParamsV2
import com.thingclips.smart.earphone.enhance.api.bean.RecordStatusBean
import com.thingclips.smart.earphone.enhance.api.bean.RecordTransferRealTimeResult
import com.thingclips.smart.earphone.enhance.api.bean.TTSConfig
import com.thingclips.smart.earphone.enhance.api.bean.TTSEncode
import com.thingclips.smart.earphone.enhance.api.bean.TTSOutput
import com.thingclips.smart.earphone.enhance.api.bean.TTSOutputChannel
import com.thingclips.smart.earphone.enhance.api.bean.def.RecordStatusDef
import com.thingclips.smart.earphone.enhance.api.bean.ttt.BTConnectedStatus
import com.thingclips.smart.earphone.enhance.api.bean.ttt.PhoneBatteryInfo
import com.thingclips.smart.earphone.enhance.api.bean.ttt.RecordQualityInfo
import com.thingclips.smart.earphone.enhance.api.listener.INativeAbilityListener
import com.thingclips.smart.earphone.enhance.api.listener.IRecordCallBack
import com.thingclips.smart.earphone.enhance.api.listener.IRecordListener
import com.thingclips.smart.sdk.api.IResultCallback
import com.tuya.smart.ai_voice.R
import com.tuya.smart.ai_voice.databinding.ActivityNativeRecordBinding
import com.tuya.smart.ai_voice.nativeui.base.NativeDemoBaseActivity
import com.tuya.smart.ai_voice.nativeui.widget.AmplitudeWaveView
import com.tuya.smart.ai_voice.nativeui.widget.InjectEndDispatcher
import com.tuya.smart.ai_voice.nativeui.widget.InjectEndDispatcher.Companion.get
import com.tuya.smart.ai_voice.nativeui.widget.RecordErrorCode.messageOf
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 录音控制页（录音室风格，与生产小程序 Simultaneous 容器同构）。
 * 
 * 
 * 模式由 [NativeEntryActivity] 的模式弹窗经 [.EXTRA_RECORD_MODE] 锁定，
 * 本页没有模式切换 UI；缺 extra 直接 `finish()`——控制页必须经弹窗进入。
 * 
 * 
 * 页面结构：
 * 
 *  * 白卡片：计时行（状态点 + 时长 + 设备能力）→ 内容区
 * （现场/电话 = [AmplitudeWaveView] 振幅波形；实时/同传 = 转写文本流；
 * 空闲 = 空态提示）→ 卡片底行（语言文字链 + TTS 开关，按模式显隐）
 *  * 底部控制条：设备切换入口（弹设备 Popup）→ 开始/暂停/继续大胶囊 → 完成
 * 
 * 
 * <h3>模式与参数</h3>
 * 由 [RecordModeOption] 固化，**模式定义项在页面上不可改**
 * （实时/同传的 needAsr / needTranslate 由模式决定；现场的 needAsr 恒为 false——
 * 打开它就变成了实时转写，侵蚀模式身份）。可调的只有 TTS 开关（同传页的朗读图标）。
 * 
 * <h3>audioSource 的推导</h3>
 * 音源由**设备类型 + 是否电话模式**共同决定，见 [.resolveAudioSource]。
 * 取错会直接录不出声，是接入时最容易卡住的地方。
 * 另外 Native 侧底层要读 `audioSourceList[0]`，因此 `audioSource` 与
 * `audioSourceList` 必须同时设置。
 * 
 * <h3>模块 7 · 设备能力</h3>
 * 蓝牙连接状态 / 电量 / 信噪比展示在计时行右端；进页先拉初值，后续靠监听增量更新。
 * 
 * <h3>对话翻译</h3>
 * 走独立的 [NativeFaceToFaceActivity]（recordMode=3，双 VAD + f2fChannel 路由），
 * 本页不承载。
 * 
 * <h3>音频注入（灌流）</h3>
 * 顶栏齿轮进 [NativeAudioInjectActivity]；注入开启时本页横幅常驻提示，
 * 注入播完由 [InjectEndDispatcher] 停录音并弹窗（页面在 onResume 订阅）。
 */
class NativeRecordActivity : NativeDemoBaseActivity() {
    // ===== 模式 =====
    private lateinit var mode: RecordModeOption

    // ===== 视图 =====

    // ===== 状态 =====
    private var status = RecordStatusDef.IDLE

    /** 语言对（仅实时 / 同传模式使用；现场 / 电话无语言区）。  */
    private var sourceLang: String? = "zh"
    private var targetLang: String? = "en"

    /** 同传页的朗读开关（needTts），默认关；用户点朗读图标后再开。  */
    private var ttsOn = false

    /**
     * 本地累计的录音时长（毫秒）。
     * 
     * 
     * `onRecordStatusUpdate` 只在状态变化时才推送，两次之间界面会静止，
     * 因此本地按秒自增，收到事件时再用 `bean.getDuration()` 校准。
     */
    private var recordedMs = 0L

    /** 控制操作进行中，用于拦截连点。  */
    private var controlLocked = false

    // ===== 模块 7 缓存的设备能力 =====
    private var btConnectedStatus = -1
    private var batteryDesc = "-"
    private var qualityDesc = "-"

    /** 录音事件监听，用字段持有，remove 时传同一引用。  */
    /** 内容布局绑定。  */
    private lateinit var binding: ActivityNativeRecordBinding

    private var recordListener: IRecordListener? = null

    /** 设备能力监听，用字段持有，remove 时传同一引用。  */
    private var abilityListener: INativeAbilityListener? = null

    /** 灌流播放结束的订阅，仅在开启灌流时会被触发。  */
    private var injectEndConsumer: InjectEndDispatcher.Consumer? = null

    /** 当前选中的设备（录音室没有 Spinner，设备经图标入口的 Popup 切换）。  */
    private var selectedDevice: DeviceItem? = null

    /**
     * 用户点了「完成」：等状态机真正走到 STOP 后再关闭页面，
     * 避免停止指令还在路上时页面被销毁。灌流结束等其它停止路径不受影响。
     */
    private var finishAfterStop = false

    /** 转写流配色：ASR 用次要色、译文用主色（黑），靠字色区分层级，不再用箭头标记。  */
    private val asrTextColor by lazy {
        ContextCompat.getColor(this, R.color.ai_voice_text_secondary)
    }
    private val translateTextColor by lazy {
        ContextCompat.getColor(this, R.color.ai_voice_text_primary)
    }

    /**
     * 录音模式与其参数组合。取自 AI 笔记的 4 种模式。
     */
    private enum class RecordModeOption(
        val labelRes: Int, val recordMode: Int, val needAsr: Boolean, val needTranslate: Boolean,
        val needAmplitude: Boolean
    ) {
        /** 现场录音：只录音不转写。  */
        LIVE(R.string.native_mode_live, RECORD_MODE_LIVE, false, false, true),

        /** 电话录音：`recordMode=0`，未在通话中开录会返回错误码 10061。  */
        PHONE(R.string.native_mode_phone, RECORD_MODE_PHONE, false, false, true),

        /** 实时转写：边录边出文字。  */
        REALTIME(R.string.native_mode_realtime, RECORD_MODE_LIVE, true, false, false),

        /** 同声传译：实时转写 + 翻译，可选 TTS 把译文播回。  */
        SIMULTANEOUS(R.string.native_mode_simultaneous, RECORD_MODE_LIVE, true, true, false);

        val isCall: Boolean
            /** @return 是否为电话录音模式，影响 Pro 耳机的音源取值
             */
            get() = recordMode == RECORD_MODE_PHONE

        val isAudioOnly: Boolean
            /** @return 是否为音频专用模式（内容区显示波形而非转写）
             */
            get() = !needAsr && !needTranslate

        companion object {
            /** @return 按名字查找模式；名字非法时返回 null
             */
            fun of(name: String?): RecordModeOption? {
                if (name == null) return null
                for (option in entries) {
                    if (option.name == name) return option
                }
                return null
            }
        }
    }

    override val contentLayoutId: Int
        get() = R.layout.activity_native_record

    override val titleResId: Int
        get() = R.string.native_record_title

    override fun onContentViewCreated() {
        val parsed = RecordModeOption.of(
            if (getIntent() == null) null else getIntent().getStringExtra(EXTRA_RECORD_MODE)
        )
        if (parsed == null) {
            // 控制页必须经模式弹窗进入：显式契约，静默缺省反而藏住调用链问题
            finish()
            return
        }
        mode = parsed
        setTitleText(mode.labelRes)

        bindViews()
        populateDeviceItems()
        selectedDevice = if (deviceItems.isEmpty()) null else deviceItems.get(0)
        setupRoom()
        refreshAbilityText()
        setupInjectEntry()
    }

    override fun useScrollContainer(): Boolean {
        // 录音室结构：卡片 + 底部控制条固定布局，转写区内部滚动，外层不能再套 NestedScrollView
        return false
    }

    private fun bindViews() {
        binding = ActivityNativeRecordBinding.bind(contentView())
    }

    // ===================== 录音室装配 =====================
    private fun setupRoom() {
        // 语言链：实时只有源语言；同传源 + 目标；现场/电话整条隐藏
        val hasLang = !mode.isAudioOnly
        binding.nativeLangChain.setVisibility(if (hasLang) View.VISIBLE else View.GONE)
        binding.nativeLangArrow.setVisibility(if (mode == RecordModeOption.SIMULTANEOUS) View.VISIBLE else View.GONE)
        binding.nativeLangTarget.setVisibility(if (mode == RecordModeOption.SIMULTANEOUS) View.VISIBLE else View.GONE)
        binding.nativeLangChain.setOnClickListener(View.OnClickListener { v: View? -> showLangPicker(true) })
        binding.nativeLangTarget.setOnClickListener(View.OnClickListener { v: View? -> showLangPicker(false) })
        refreshLangChain()

        // 朗读开关：只有同传需要（把译文播回）
        binding.nativeTtsIcon.setVisibility(if (mode == RecordModeOption.SIMULTANEOUS) View.VISIBLE else View.GONE)
        binding.nativeTtsIcon.setOnClickListener(View.OnClickListener { v: View? ->
            ttsOn = !ttsOn
            refreshTtsIcon()
            if (this.isRecordingOrPaused) {
                // 生产同款：TTS 开关录音中直接 updateParams 下发
                updateParams()
            }
        })
        refreshTtsIcon()

        // 设备入口：弹设备 Popup，录音中锁定
        binding.nativeDeviceEntry.setOnClickListener(View.OnClickListener { v: View? -> showDevicePicker() })
        refreshDeviceEntry()

        // 大胶囊：状态分派（开始 / 暂停 / 继续走同一条控制链，锁在各方法内部）
        binding.nativeCapsule.setOnClickListener(View.OnClickListener { v: View? ->
            if (this.isRecordingOrPaused) {
                if (status == RecordStatusDef.RECORDING) {
                    pause()
                } else {
                    resume()
                }
            } else {
                startRecord()
            }
        })

        // 完成 = 停止，停稳后自动关闭本页；灰置规则在 refreshRoomState 里
        binding.nativeDone.setOnClickListener(View.OnClickListener { v: View? -> stopRecord(true) })

        // 空态提示随模式固定（录音开始后由 refreshRoomState 隐藏）
        binding.nativeEmptyState.setText(
            getString(
                if (mode == RecordModeOption.LIVE)
                    R.string.native_room_empty_live
                else
                    if (mode == RecordModeOption.PHONE)
                        R.string.native_room_empty_phone
                    else
                        if (mode == RecordModeOption.REALTIME)
                            R.string.native_room_empty_realtime
                        else
                            R.string.native_room_empty_simultaneous
            )
        )
        refreshRoomState()
    }

    private fun refreshLangChain() {
        binding.nativeLangSource.setText(sourceLang)
        binding.nativeLangTarget.setText(targetLang)
    }

    private fun refreshTtsIcon() {
        binding.nativeTtsIcon.setText(
            getString(
                if (ttsOn)
                    R.string.native_tts_on
                else
                    R.string.native_tts_off
            )
        )
    }

    private fun refreshDeviceEntry() {
        // 手机收音时用手机图标，其余设备沿用耳机图标；未选中按默认手机处理
        val isPhone = currentDeviceItem()?.isPhone != false
        binding.nativeDeviceEntry.setImageResource(
            if (isPhone) R.drawable.ic_ai_phone else R.drawable.ic_ai_device
        )
        binding.nativeDeviceEntry.setEnabled(!this.isRecordingOrPaused)
        binding.nativeDeviceEntry.setAlpha(if (this.isRecordingOrPaused) 0.4f else 1f)
    }

    override fun currentDeviceItem(): DeviceItem? {
        // 录音室没有 Spinner：选中态由设备弹窗维护，缺省落在 deviceItems[0]（PHONE）
        return if (selectedDevice != null) selectedDevice else super.currentDeviceItem()
    }

    /**
     * 语言选择。用单选对话框而不是 Spinner：录音室里语言是「点一下改」的轻入口。
     * 
     * @param source true 选源语言，false 选目标语言
     */
    private fun showLangPicker(source: Boolean) {
        val current: Int = indexOf(LANGS, if (source) sourceLang else targetLang)
        AlertDialog.Builder(this)
            .setTitle(if (source) R.string.native_source_lang else R.string.native_target_lang)
            .setSingleChoiceItems(
                LANGS,
                current,
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                    if (source) {
                        sourceLang = LANGS[which]
                    } else {
                        targetLang = LANGS[which]
                    }
                    refreshLangChain()
                    dialog?.dismiss()
                    if (this.isRecordingOrPaused) {
                        updateParams()
                    }
                })
            .show()
    }

    /** 设备切换 Popup。列表来自基类的 [.deviceItems]，离线设备置灰。  */
    private fun showDevicePicker() {
        val names = arrayOfNulls<CharSequence>(deviceItems.size)
        val enabled = BooleanArray(deviceItems.size)
        var checked = 0
        val current = currentDeviceItem()
        for (i in deviceItems.indices) {
            val item = deviceItems.get(i)
            names[i] = item.displayName
            enabled[i] = item.isPhone || item.online
            if (item == current) {
                checked = i
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.native_device_id)
            .setSingleChoiceItems(
                names,
                checked,
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                    val item = deviceItems.get(which)
                    if (!enabled[which]) {
                        toast(getString(R.string.native_toast_device_offline))
                        return@OnClickListener
                    }
                    dialog?.dismiss()
                    if (item != current) {
                        selectedDevice = item
                        refreshDeviceEntry()
                        queryBtStatus()
                    }
                })
            .show()
    }

    // ===================== 音源与参数 =====================
    /**
     * 推导音源。**取错会直接录不出声。**
     * 
     * 
     * 推导规则（设备类型 + 是否电话模式）：
     * 手机走系统 MIC；入门耳机与 OS 耳机走系统蓝牙；Pro 耳机按是否电话模式分现场 / 电话音源；
     * 录音卡片走卡片音源。
     * 
     * @return audioSource 取值
     */
    private fun resolveAudioSource(): Int {
        val device = currentDeviceItem()
        if (device == null || device.isPhone) {
            return AUDIO_SOURCE_SYSTEM_MIC
        }
        val type = device.productType
        if (type == null) {
            return AUDIO_SOURCE_SYSTEM_MIC
        }
        when (type) {
            DeviceItem.TYPE_PRO -> return if (mode.isCall) AUDIO_SOURCE_PRO_CALL else AUDIO_SOURCE_PRO_LIVE
            DeviceItem.TYPE_CARD -> return AUDIO_SOURCE_CARD
            DeviceItem.TYPE_ENTRY, DeviceItem.TYPE_OS_BUDS -> return AUDIO_SOURCE_SYSTEM_BT
            else -> return AUDIO_SOURCE_SYSTEM_BT
        }
    }

    /**
     * 构造录音参数。
     * 
     * 
     * 未在 UI 中暴露、AI 笔记也未使用的三个字段，用法如下：
     * 
     *  * `recordTransfer3AConfig` —— 降噪 / 自动增益 / 回声消除开关，
     * 默认由底层按设备能力决定，需要定制音频处理时才设置
     *  * `autoRecognize` —— 是否自动识别语种，置 true 后 `originalLanguage` 可不传
     *  * `startLivingStatus` —— 直播场景状态机（0 仅同传 / 1 开始直播 / 2 直播中变更）
     * 
     * 
     * @param forStart true 为 `startAudioRecording` 入参，false 为 `updateParams` 入参
     * @return 参数对象
     */
    private fun buildParams(forStart: Boolean): RecordParamsV2 {
        val params = RecordParamsV2()
        params.updateParams = !forStart
        params.businessType = BUSINESS_TYPE_NOTE
        params.recordMode = mode.recordMode

        val audioSource = resolveAudioSource()
        params.audioSource = audioSource
        // 底层 RecordExecutorManager.getRecordType 读的是 audioSourceList[0]，不能为空
        val sourceList: MutableList<Int> = ArrayList()
        sourceList.add(audioSource)
        params.audioSourceList = sourceList

        // 模式定义项不可改：needAsr / needTranslate / needAmplitude 均跟模式走
        params.needAsr = mode.needAsr
        params.needAmplitude = mode.needAmplitude
        params.needTranslate = mode.needTranslate
        params.needTts = mode == RecordModeOption.SIMULTANEOUS && ttsOn

        if (params.needAsr || params.needTranslate) {
            params.originalLanguage = sourceLang
        }
        if (params.needTranslate) {
            params.targetLanguage = targetLang
        }
        if (params.needTts) {
            params.ttsConfig = buildTtsConfig(audioSource)
        }
        return params
    }

    /**
     * 构造 TTS 输出配置：把译文合成语音后播回指定通道。
     * 
     * 
     * 三处非直觉的取值规则：
     * 
     *  * `devId` —— 输出到设备时才下发；手机 / 入门耳机 / 卡片走系统通道，必须传空
     *  * `output` —— 由音源反推：系统 MIC 音源配 MIC 输出，系统蓝牙音源配 BT 输出，
     * Pro / 卡片等设备音源配设备输出
     *  * `encode` —— Pro 耳机用 opus_silk，其余用默认；opus_celt 为特定固件才支持
     * 
     * 
     * @param audioSource 当前音源
     * @return TTS 配置
     */
    private fun buildTtsConfig(audioSource: Int): TTSConfig {
        val device = currentDeviceItem()
        val isPro = device != null && DeviceItem.TYPE_PRO == device.productType

        val output: TTSOutput?
        if (audioSource == AUDIO_SOURCE_SYSTEM_MIC) {
            output = TTSOutput.SYSTEM_MIC
        } else if (audioSource == AUDIO_SOURCE_SYSTEM_BT) {
            output = TTSOutput.SYSTEM_BLUETOOTH
        } else {
            output = TTSOutput.DEVICE
        }

        // 走系统通道时 devId 必须传空，否则底层会尝试往设备写流
        val devId = if (output == TTSOutput.DEVICE) currentDeviceId() else ""
        val encode = if (isPro) TTSEncode.OPUS_SILK else TTSEncode.DEFAULT

        return TTSConfig(devId, output, encode, TTSOutputChannel.DEFAULT)
    }

    // ===================== 事件监听 =====================
    override fun onResume() {
        super.onResume()
        // 灌流配置可能刚在设置页被改动，回到本页时重新读取
        refreshInjectBanner()
        // 订阅灌流结束事件，接管本次处置（停录音 + 弹窗）。
        // 底层那个 setter 是覆盖式单槽，统一由 InjectEndDispatcher 独占，页面只向它订阅。
        val consumer = object : InjectEndDispatcher.Consumer {
            override fun onInjectEnd(deviceId: String) {
                onInjectFileEnd()
            }
        }
        injectEndConsumer = consumer
        get().setPageConsumer(this, consumer)
    }

    override fun onPause() {
        super.onPause()
        injectEndConsumer?.let {
            get().clearPageConsumer(it)
            injectEndConsumer = null
        }
    }

    override fun registerListeners() {
        recordListener = object : IRecordListener {
            override fun onRecordStatusUpdate(deviceId: String, bean: RecordStatusBean?) {
                if (bean == null) return
                runOnUi { onStatusUpdate(bean) }
            }

            override fun onRecordAmplitudeUpdate(
                deviceId: String,
                channel: Int,
                amplitude: Double
            ) {
                // 高频回调，View 内部对 invalidate 做了节流，无需切主线程
                binding.nativeWave.push(normalizeAmp(amplitude))
            }

            override fun onRealTimeStatusUpdate(s: RealTimeTransferStatus) {
                runOnUi { appendRealtime(s) }
            }

            override fun onRecordSwitchAudioSourceEvent(
                devId: String,
                recordType: Int,
                audioSource: Int
            ) {
            }

            override fun onRecordFinish(deviceId: String) {
                // 结束以 onRecordStatusUpdate 的 status=STOP 为准，这里不做处理
            }

            override fun onRecordErrorFinish(deviceId: String, errorCode: Int, errorMsg: String) {
                runOnUi { handleRecordingStopped(errorCode) }
            }
        }
        manager.addRecordListener(recordListener)

        val ability = object : INativeAbilityListener {
            override fun onBTConnectChange(info: BTConnectedStatus) {
                runOnUi {
                    btConnectedStatus = info.connectedStatus
                    refreshAbilityText()
                }
            }

            override fun onPhoneBatteryChange(info: PhoneBatteryInfo) {
                runOnUi {
                    batteryDesc = if (!info.needShow)
                        "-"
                    else String.format(Locale.getDefault(), "%.0f%%", info.batteryValue)
                    refreshAbilityText()
                }
            }

            override fun onRecordQualityChange(info: RecordQualityInfo) {
                runOnUi {
                    qualityDesc = if (!info.needShow)
                        "-"
                    else String.format(Locale.getDefault(), "%.1f dB", info.snrValue)
                    refreshAbilityText()
                }
            }
        }
        abilityListener = ability
        manager.addNativeAbilityListener(ability)

        restoreExistingTask()
        queryBtStatus()
    }

    override fun unregisterListeners() {
        if (recordListener != null) {
            manager.removeRecordListener(recordListener)
            recordListener = null
        }
        if (abilityListener != null) {
            abilityListener?.let { manager.removeNativeAbilityListener(it) }
            abilityListener = null
        }
    }

    // ===================== 模块 7 · 设备能力 =====================
    /**
     * 拉取耳机蓝牙连接状态初值。后续变化由 [INativeAbilityListener] 推送。
     * PHONE 走手机麦克风，无蓝牙概念，跳过。
     */
    private fun queryBtStatus() {
        val deviceId = currentDeviceId() ?: DEVICE_ID_PHONE
        if (DEVICE_ID_PHONE == deviceId) {
            btConnectedStatus = -1
            refreshAbilityText()
            return
        }
        manager.getEarPhoneBTConntectedStatus(
            deviceId,
            object : IRecordCallBack<BTConnectedStatus?> {
                override fun onSuccess(result: BTConnectedStatus?) {
                    runOnUi {
                        btConnectedStatus = if (result == null) -1 else result.connectedStatus
                        refreshAbilityText()
                    }
                }

                override fun onError(code: String?, error: String?) {
                    toastRecordError(code)
                }
            })
    }

    private fun refreshAbilityText() {
        binding.nativeDeviceAbility.setText(
            getString(
                R.string.native_ability_format,
                btStatusName(), batteryDesc, qualityDesc
            )
        )
    }

    private fun btStatusName(): String {
        if (btConnectedStatus == BT_CONNECTED) return getString(R.string.native_bt_connected)
        if (btConnectedStatus == 0) return getString(R.string.native_bt_disconnected)
        return getString(R.string.native_bt_unknown)
    }

    // ===================== 灌流（音频注入） =====================
    /**
     * 装配灌流入口：顶栏设置按钮 + 提示条。
     * 
     * 
     * 灌流是测试能力，把麦克风采集替换为指定音频文件，详见 [NativeAudioInjectActivity]。
     */
    private fun setupInjectEntry() {
        setTopBarAction(
            R.drawable.ic_ai_settings, R.string.native_inject_title,
            View.OnClickListener { v: View? -> NativeAudioInjectActivity.Companion.start(this) })
        binding.tvInjectBanner.setOnClickListener(View.OnClickListener { v: View? ->
            NativeAudioInjectActivity.Companion.start(
                this
            )
        })
        refreshInjectBanner()
    }

    /**
     * 刷新灌流提示条。
     * 
     * 
     * 灌流开启时波形与实时转写全部来自音频文件，与麦克风无关。这一点不在界面上明说，
     * 使用者第一反应会是「SDK 有问题」而不是「我开着灌流」，因此常驻提示。
     */
    private fun refreshInjectBanner() {
        val config = PhoneMicAudioInputFactory.getDebugStrategy()
        if (config == null) {
            binding.tvInjectBanner.setVisibility(View.GONE)
            return
        }
        binding.tvInjectBanner.setText(
            getString(
                R.string.native_inject_banner,
                config.replaceCaptureData.fileName
            )
        )
        binding.tvInjectBanner.setVisibility(View.VISIBLE)
    }

    /**
     * 灌流音频播放到结尾。此后麦克风通道不再有数据流入，录音继续下去只会得到静音，
     * 故直接走与手动停止相同的路径结束本次录音。
     */
    private fun onInjectFileEnd() {
        if (!this.isRecordingOrPaused) return
        if (controlLocked) {
            // 刚好撞上其它控制操作的锁定期，等锁释放后再停，避免提示了却没停下来
            main.postDelayed({ this.onInjectFileEnd() }, CONTROL_LOCK_MS)
            return
        }
        stopRecord()
        // 延后重试的那条路径可能落在 onPause 之后，此时弹窗会因 window token 失效而抛异常
        if (isFinishing() || isDestroyed()) {
            return
        }
        val config = PhoneMicAudioInputFactory.getDebugStrategy()
        val fileName = if (config == null) "-" else config.replaceCaptureData.fileName
        AlertDialog.Builder(this)
            .setTitle(R.string.native_inject_end_dialog_title)
            .setMessage(getString(R.string.native_inject_end_dialog_message, fileName))
            .setPositiveButton(R.string.native_inject_end_dialog_ok, null)
            .show()
    }

    // ===================== 恢复进行中的任务 =====================
    /**
     * 恢复进行中的录音任务。
     * 
     * 
     * `recordTransferTask` 同步返回当前任务，无任务时返回 null。
     * 除了状态，**还要把已转写的历史句子补回来**——页面重进 / 从快捷方式进入时
     * 也会调 `getRecordTransferRealTimeResult({recordId})` 回填，否则界面上是空白的。
     */
    private fun restoreExistingTask() {
        val deviceId = currentDeviceId() ?: DEVICE_ID_PHONE
        try {
            val bean = manager.recordTransferTask(deviceId)
            if (bean == null) return
            onStatusUpdate(bean)
            if (bean.status == RecordStatusDef.RECORDING
                || bean.status == RecordStatusDef.PAUSING
            ) {
                restoreRealtimeSentences(bean.recordId)
            }
        } catch (e: Exception) {
        }
    }

    /**
     * 补拉已转写的实时句子，回填到界面。
     * 
     * @param recordId 实时转写记录 ID，来自 `RecordStatusBean.recordId`
     */
    private fun restoreRealtimeSentences(recordId: String?) {
        if (TextUtils.isEmpty(recordId)) return
        manager.getRecordTransferRealTimeResult(
            null, recordId, null,
            object : IRecordCallBack<MutableList<RecordTransferRealTimeResult?>?> {
                override fun onSuccess(result: MutableList<RecordTransferRealTimeResult?>?) {
                    runOnUi {
                        if (result == null || result.isEmpty()) return@runOnUi
                        finishedSentences.clear()
                        for (item in result) {
                            if (item == null) continue
                            val s = Sentence()
                            s.asr = if (item.text == null) item.asr else item.text
                            s.translate = item.translate
                            finishedSentences.add(s)
                        }
                        renderRealtime()
                    }
                }

                override fun onError(code: String?, error: String?) {
                    toastRecordError(code)
                }
            })
    }

    // ===================== 录音控制 =====================
    /**
     * 开始录音。依次做四重校验：操作锁 → 录音权限 → 设备可用性（在线 + BT）→ 参数完整性。
     */
    private fun startRecord() {
        if (controlLocked) {
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    Manifest.permission.RECORD_AUDIO
                )
            ) {
                toast(getString(R.string.native_toast_record_permission_rationale))
            }
            ActivityCompat.requestPermissions(
                this,
                arrayOf<String>(Manifest.permission.RECORD_AUDIO), REQ_CODE_RECORD_AUDIO
            )
            return
        }
        if (status == RecordStatusDef.RECORDING) {
            toast(getString(R.string.native_toast_already_recording))
            return
        }
        val device = currentDeviceItem()
        // 设备离线时开录必然失败，先拦一道
        if (device != null && !device.isPhone && !device.online) {
            toast(getString(R.string.native_toast_device_offline))
            return
        }
        // 模块 7 的前置校验：选了真实耳机但蓝牙未连接，开录必然失败
        if (device != null && !device.isPhone && btConnectedStatus == 0) {
            toast(getString(R.string.native_toast_bt_disconnected))
            return
        }
        val params = buildParams(true)
        if ((params.needAsr || params.needTranslate)
            && TextUtils.isEmpty(params.originalLanguage)
        ) {
            toast(getString(R.string.native_toast_need_source_lang))
            return
        }
        if (params.needTranslate && TextUtils.isEmpty(params.targetLanguage)) {
            toast(getString(R.string.native_toast_need_target_lang))
            return
        }

        val deviceId = currentDeviceId() ?: DEVICE_ID_PHONE
        lockControl()
        manager.startAudioRecording(deviceId, params, object : IResultCallback {
            override fun onSuccess() {
                runOnUi {
                    status = RecordStatusDef.RECORDING
                    binding.nativeWave.reset()
                    resetRealtime()
                    resetDuration()
                    startTicking()
                    refreshRoomState()
                }
            }

            override fun onError(code: String?, error: String?) {
                runOnUi { onControlError("startAudioRecording", code, error) }
            }
        })
    }

    private fun pause() {
        if (controlLocked) return
        val deviceId = currentDeviceId() ?: DEVICE_ID_PHONE
        lockControl()
        manager.pauseRecordTransfer(deviceId, object : IResultCallback {
            override fun onSuccess() {
                runOnUi {
                    status = RecordStatusDef.PAUSING
                    stopTicking()
                    refreshRoomState()
                }
            }

            override fun onError(code: String?, error: String?) {
                runOnUi { onControlError("pauseRecordTransfer", code, error) }
            }
        })
    }

    private fun resume() {
        if (controlLocked) return
        val deviceId = currentDeviceId() ?: DEVICE_ID_PHONE
        lockControl()
        manager.resumeRecordTransfer(deviceId, object : IResultCallback {
            override fun onSuccess() {
                runOnUi {
                    status = RecordStatusDef.RECORDING
                    startTicking()
                    refreshRoomState()
                }
            }

            override fun onError(code: String?, error: String?) {
                runOnUi { onControlError("resumeRecordTransfer", code, error) }
            }
        })
    }

    /** 停止录音（「完成」）。仅在录音中 / 暂停中允许，其余状态直接忽略。  */
    private fun stopRecord(finishPageAfterStop: Boolean = false) {
        if (controlLocked || !this.isRecordingOrPaused) return
        if (finishPageAfterStop) {
            finishAfterStop = true
        }
        val deviceId = currentDeviceId() ?: DEVICE_ID_PHONE
        lockControl()
        manager.stopRecordTransfer(deviceId, object : IResultCallback {
            override fun onSuccess() {
                // 真正结束以 onRecordStatusUpdate 的 status=STOP 为准，这里只刷新按钮态
                runOnUi { this@NativeRecordActivity.refreshRoomState() }
            }

            override fun onError(code: String?, error: String?) {
                // 停止指令未下发成功：撤销「停稳后关页」的意图，避免后续 STOP 误关页
                finishAfterStop = false
                runOnUi { onControlError("stopRecordTransfer", code, error) }
            }
        })
    }

    /** 上锁并在 [.CONTROL_LOCK_MS] 后自动释放，避免连点重复下发。  */
    private fun lockControl() {
        controlLocked = true
        refreshRoomState()
        main.postDelayed({
            controlLocked = false
            refreshRoomState()
        }, CONTROL_LOCK_MS)
    }

    /**
     * 控制类接口失败的统一处理：按错误码给可读提示，并立即释放操作锁。
     * 
     * @param action 动作名，用于日志区分
     * @param code   错误码
     * @param error  底层错误描述
     */
    private fun onControlError(action: String?, code: String?, error: String?) {
        controlLocked = false
        refreshRoomState()
        toastRecordError(code)
    }

    /**
     * 按录音链路的错误码表给出可读提示。
     * 
     * 
     * 该链路的错误码语义细分很多，直接抛裸码或底层 errorMsg 对使用者没有意义。
     * 注意 `10061` 在**电话录音模式**下表示「尚未进入通话」，
     * 与其余模式的「设备忙或异常」不是一回事，故映射时要带上当前模式。
     * 
     * 
     * SDK 回调可能在子线程，内部已切回主线程。
     * 
     * @param code 错误码
     */
    private fun toastRecordError(code: String?) {
        runOnUi {
            toast(
                messageOf(
                    this, code,
                    mode.isCall
                )
            )
        }
    }

    /**
     * 录音中动态更新参数，无需 stop / restart。
     * 
     * 
     * `onSuccess` 立即返回，**不代表参数已生效**，真正生效以
     * `onRecordStatusUpdate` 推送的状态为准。
     */
    private fun updateParams() {
        val deviceId = currentDeviceId() ?: DEVICE_ID_PHONE
        val params = buildParams(false)
        manager.updateParams(deviceId, params, object : IResultCallback {
            override fun onSuccess() {
            }

            override fun onError(code: String?, error: String?) {
                toastRecordError(code)
            }
        })
    }

    // ===================== 状态渲染 =====================
    private fun onStatusUpdate(bean: RecordStatusBean) {
        val previous = status
        status = bean.status
        // 事件带的时长是权威值，用它校准本地计时
        if (bean.duration > 0) {
            recordedMs = bean.duration
            binding.nativeTimer.setText(formatDuration(recordedMs))
        }
        if (status == RecordStatusDef.RECORDING) {
            startTicking()
        } else {
            stopTicking()
        }
        refreshRoomState()
        // 录音结束统一以状态事件的 status=STOP 判定（onRecordFinish 事件当前 SDK 不予触发）
        if (status == RecordStatusDef.STOP && previous != RecordStatusDef.STOP) {
            handleRecordingStopped(null)
        }
    }

    // ===================== 录音时长计时 =====================
    /**
     * 每秒自增一次录音时长。
     * 
     * 
     * 状态事件只在状态变化时到达，仅靠它会让计时停在开始那一刻，因此本地自增，
     * 由 [.onStatusUpdate] 用事件里的 `duration` 校准，避免长时间累积漂移。
     */
    private val durationTicker: Runnable = object : Runnable {
        override fun run() {
            recordedMs += DURATION_TICK_MS
            binding.nativeTimer.setText(formatDuration(recordedMs))
            main.postDelayed(this, DURATION_TICK_MS)
        }
    }

    private fun startTicking() {
        main.removeCallbacks(durationTicker)
        main.postDelayed(durationTicker, DURATION_TICK_MS)
    }

    private fun stopTicking() {
        main.removeCallbacks(durationTicker)
    }

    /** 重置计时并刷新显示，用于开始新录音与录音结束。  */
    private fun resetDuration() {
        stopTicking()
        recordedMs = 0L
        binding.nativeTimer.setText(R.string.native_duration_zero)
    }

    /**
     * 录音结束的收尾。
     *
     * 正常结束由状态事件的 `status=STOP` 触发；异常结束由 `onRecordErrorFinish` 带错误码触发。
     *
     * @param errorCode 异常结束时的错误码；正常结束传 null
     */
    private fun handleRecordingStopped(errorCode: Int?) {
        status = RecordStatusDef.STOP
        resetDuration()
        binding.nativeWave.reset()
        resetRealtime()
        refreshRoomState()
        if (errorCode != null) {
            // 异常结束同样按错误码给可读提示
            toastRecordError(errorCode.toString())
        }
        // 用户点「完成」触发的停止：真正停稳后再关闭页面
        if (finishAfterStop) {
            finishAfterStop = false
            finish()
        }
    }

    /**
     * 录音室的状态渲染：胶囊文案 / 状态点颜色 / 「完成」可用性 / 内容区切换 / 设备入口锁定。
     */
    private fun refreshRoomState() {
        val recording = status == RecordStatusDef.RECORDING
        val paused = status == RecordStatusDef.PAUSING
        val active = recording || paused

        binding.nativeCapsule.setText(
            if (recording)
                R.string.native_pause
            else
                if (paused) R.string.native_resume else R.string.native_start
        )
        binding.nativeDone.setTextColor(
            getResources().getColor(
                if (active && !controlLocked)
                    R.color.ai_voice_text_primary
                else
                    R.color.ai_voice_btn_disable,
                getTheme()
            )
        )
        binding.nativeStateDot.getBackground().setTint(
            getResources().getColor(
                if (recording)
                    R.color.ai_voice_status_recording
                else
                    if (paused) R.color.ai_voice_tab_selected else R.color.ai_voice_btn_disable,
                getTheme()
            )
        )

        // 内容区：音频模式录音中显示波形，文字模式显示转写，空闲显示空态
        val showWave = mode.isAudioOnly && active
        binding.nativeWave.setVisibility(if (showWave) View.VISIBLE else View.GONE)
        binding.nativeTranscriptScroll.setVisibility(if (!mode.isAudioOnly && active) View.VISIBLE else View.GONE)
        binding.nativeEmptyState.setVisibility(if (active) View.GONE else View.VISIBLE)
        if (!showWave) {
            binding.nativeWave.reset()
        }

        // 录音中不允许换设备，避免参数与底层状态不一致
        binding.nativeDeviceEntry.setEnabled(!active)
        binding.nativeDeviceEntry.setAlpha(if (active) 0.4f else 1f)
        binding.nativeLangChain.setEnabled(!active)
        binding.nativeLangChain.setAlpha(if (active) 0.4f else 1f)
    }

    private val isRecordingOrPaused: Boolean
        get() = status == RecordStatusDef.RECORDING || status == RecordStatusDef.PAUSING

    // ===================== 实时转写渲染 =====================
    /**
     * 一句实时转写。原文与译文分两次事件到达，需就地合并。
     */
    private class Sentence {
        var asr: String? = ""
        var translate: String? = ""
    }

    private val finishedSentences: MutableList<Sentence> = ArrayList<Sentence>()
    private var currentSentence: Sentence? = null

    /**
     * 处理实时转写事件。
     * 
     * 
     * 事件模型：同一句的 `text` / `translateText` 是**累积文案**而非增量，
     * 因此进行中直接覆盖当前句；`phase=ASR 且 status=结束` 时把当前句定稿。
     * 译文（`phase=TEXT`）可能晚于定稿到达，因此要写进「最后一句」而不是「当前句」。
     * 
     * @param s 实时转写事件
     */
    private fun appendRealtime(s: RealTimeTransferStatus?) {
        if (s == null) return
        val phase = if (s.phase == null) -1 else s.phase
        val phaseStatus = if (s.status == null) -1 else s.status

        if (phase == PHASE_ASR) {
            if (!TextUtils.isEmpty(s.text)) {
                ensureCurrent().asr = s.text
            }
            if (phaseStatus == PHASE_STATUS_FINISH) {
                currentSentence?.let {
                    finishedSentences.add(it)
                    currentSentence = null
                }
            }
        } else if (phase == PHASE_TEXT) {
            if (!TextUtils.isEmpty(s.translateText)) {
                lastSentence().translate = s.translateText
            }
        }
        renderRealtime()
    }

    private fun ensureCurrent(): Sentence {
        return currentSentence ?: Sentence().also { currentSentence = it }
    }

    private fun lastSentence(): Sentence {
        return currentSentence
            ?: if (finishedSentences.isNotEmpty()) finishedSentences.last() else ensureCurrent()
    }

    private fun renderRealtime() {
        val sb = SpannableStringBuilder()
        for (st in finishedSentences) {
            appendSentence(sb, st)
        }
        currentSentence?.let { appendSentence(sb, it) }
        binding.nativeRealtimeText.setText(sb)
        // 新内容到达后滚到底部，与生产同款行为
        binding.nativeTranscriptScroll.post {
            (binding.nativeTranscriptScroll as ScrollView)
                .fullScroll(View.FOCUS_DOWN)
        }
    }

    /**
     * 追加一句：ASR 灰色、译文黑色，去掉箭头与句末空行，靠字色区分层级。
     *
     * @param sb 目标富文本
     * @param st 句子内容
     */
    private fun appendSentence(sb: SpannableStringBuilder, st: Sentence) {
        if (!TextUtils.isEmpty(st.asr)) {
            val start = sb.length
            sb.append(st.asr).append('\n')
            sb.setSpan(
                ForegroundColorSpan(asrTextColor),
                start,
                sb.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (!TextUtils.isEmpty(st.translate)) {
            val start = sb.length
            sb.append(st.translate).append('\n')
            sb.setSpan(
                ForegroundColorSpan(translateTextColor),
                start,
                sb.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun resetRealtime() {
        finishedSentences.clear()
        currentSentence = null
        binding.nativeRealtimeText.setText("")
    }

    // ===================== 辅助 =====================
    override fun onDeviceSelected(item: DeviceItem) {
        super.onDeviceSelected(item)
        queryBtStatus()
    }

    /**
     * 把 SDK 的振幅值映射到便于观察的区间。
     * 
     * 
     * 底层已把 PCM 16bit 归一化到 0~1，但 RMS 模式下正常说话通常只有 0.02~0.15，
     * 线性显示几乎看不出波形，故用幂函数做非线性拉伸。
     * 
     * @param amp 原始振幅
     * @return 0~1 的展示值
     */
    private fun normalizeAmp(amp: Double): Double {
        val v = abs(amp).pow(0.4)
        return min(1.0, max(0.0, v))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String?>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CODE_RECORD_AUDIO) return
        if (grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startRecord()
        } else {
            if (!ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    Manifest.permission.RECORD_AUDIO
                )
            ) {
                toast(getString(R.string.native_toast_record_permission_never_ask))
            } else {
                toast(getString(R.string.native_toast_record_permission_denied))
            }
        }
    }

    companion object {
        /** 入口弹窗写入的模式标记，取值为 [RecordModeOption] 的 `name()`。  */
        const val EXTRA_RECORD_MODE: String = "extra_record_mode"

        private val LANGS = arrayOf<String?>("zh", "en", "de", "ja", "ko", "fr", "es")
        private const val REQ_CODE_RECORD_AUDIO = 1001

        // ===== RecordParamsV2.recordMode =====
        /** 电话录音。  */
        private const val RECORD_MODE_PHONE = 0

        /** 现场录音 / 会议。  */
        private const val RECORD_MODE_LIVE = 1

        // ===== 音源（AudioSource 枚举值） =====
        /** 系统 MIC 16K 单声道（手机本地录音）。  */
        private const val AUDIO_SOURCE_SYSTEM_MIC = 1

        /** 系统蓝牙 16K 单声道。  */
        private const val AUDIO_SOURCE_SYSTEM_BT = 0

        /** Pro 耳机现场音源。  */
        private const val AUDIO_SOURCE_PRO_LIVE = 20

        /** Pro 耳机电话音源。  */
        private const val AUDIO_SOURCE_PRO_CALL = 21

        /** 录音卡片立体声音源。  */
        private const val AUDIO_SOURCE_CARD = 41

        // ===== BTConnectedStatus.connectedStatus =====
        /** 耳机蓝牙已连接。  */
        private const val BT_CONNECTED = 1

        /** 录音时长自增间隔。  */
        private const val DURATION_TICK_MS = 1000L

        /** 控制操作进行中的锁定时长，拦截连点。  */
        private const val CONTROL_LOCK_MS = 600L

        /** 实时转写阶段：ASR 原文。  */
        private const val PHASE_ASR = 4

        /** 实时转写阶段：翻译文本。  */
        private const val PHASE_TEXT = 5

        /** 阶段状态：已结束。  */
        private const val PHASE_STATUS_FINISH = 2

        /** 业务类型：AI 笔记。  */
        private const val BUSINESS_TYPE_NOTE = 0

        /**
         * 进入指定模式的录音控制页。
         * 
         * @param context 上下文
         * @param mode    模式名（[RecordModeOption] 的 `name()`）
         */
        fun start(context: Context, mode: String) {
            val intent = Intent(context, NativeRecordActivity::class.java)
            intent.putExtra(EXTRA_RECORD_MODE, mode)
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        private fun formatDuration(ms: Long): String {
            val totalSec = ms / 1000
            return String.format(Locale.getDefault(), "%02d:%02d", totalSec / 60, totalSec % 60)
        }

        private fun indexOf(arr: Array<String?>, v: String?): Int {
            for (i in arr.indices) {
                if (arr[i] == v) return i
            }
            return 0
        }
    }
}
