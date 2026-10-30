package com.tuya.smart.ai_voice.nativeui

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.MediaPlayer
import android.text.TextUtils
import android.util.SparseArray
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.thingclips.smart.android.common.utils.L
import com.thingclips.smart.earphone.enhance.api.bean.RealTimeTransferStatus
import com.thingclips.smart.earphone.enhance.api.bean.RecordParamsV2
import com.thingclips.smart.earphone.enhance.api.bean.RecordStatusBean
import com.thingclips.smart.earphone.enhance.api.bean.RecordTransferRealTimeResult
import com.thingclips.smart.earphone.enhance.api.bean.TTSConfig
import com.thingclips.smart.earphone.enhance.api.bean.TTSEncode
import com.thingclips.smart.earphone.enhance.api.bean.TTSOutput
import com.thingclips.smart.earphone.enhance.api.bean.TTSOutputChannel
import com.thingclips.smart.earphone.enhance.api.bean.TtsPlaybackMode
import com.thingclips.smart.earphone.enhance.api.bean.def.RecordStatusDef
import com.thingclips.smart.earphone.enhance.api.listener.IRecordListener
import com.thingclips.smart.earphone.enhance.api.listener.IRecordCallBack
import com.thingclips.smart.sdk.api.IResultCallback
import com.tuya.smart.ai_voice.R
import com.tuya.smart.ai_voice.databinding.ActivityNativeF2fRecordBinding
import com.tuya.smart.ai_voice.nativeui.base.NativeDemoBaseActivity
import com.tuya.smart.ai_voice.nativeui.widget.InjectEndDispatcher
import com.tuya.smart.ai_voice.nativeui.widget.InjectEndDispatcher.Companion.get
import com.tuya.smart.ai_voice.nativeui.widget.RecordErrorCode.messageOf
import java.util.Locale

/**
 * 对话翻译控制页（`recordMode=3`）。
 * <p>
 * 结构同生产小程序 `FaceToFace/Recording`：气泡流转写 + 底部控制条
 * （语言对、两枚方向按钮、各通道的输入 / 输出 / TTS 播放配置）。
 * 录音中按返回弹窗确认结束并保存。
 * 
 * 
 * **双通道的原理**：这个模式跑在 `FaceToFacePhoneMicRecordExecutor` 上，
 * 内部有左右两条独立 VAD/ASR 流水线；`f2fChannel`（0/1）决定每一帧音频进哪条。
 * 语言对只传一对（`originalLanguage`/`targetLanguage`），方向由后端自动判；
 * 切方向的目的就是把两个人的语音流分开，避免混流。
 * 
 * 
 * **方向 / 配置切换走 `updateParams=true`**：`f2fChannel`、语言对、输出源与播放策略
 * 都是原地下发，录音不断流（换设备时由 SDK 重建 token）。请在语句间切换方向——
 * 一句话说到一半切流会把这句话劈成两段。
 * 
 * 
 * **TTS 输出按通道配置**：手机扬声器 → SYSTEM_MIC，蓝牙耳机 → SYSTEM_BLUETOOTH；
 * 只下发单条 `ttsConfig`（两条会创建第二个播放器并覆盖引擎拉流回调，导致无声）。
 * 播放策略支持立即 / 只缓存 / 边播边缓存，缓存型可在气泡上回放单句译文。
 * 
 * 
 * 本页不做任务恢复（f2f 任务的转写从文件列表看结果），也不订阅灌流结束事件——
 * 灌流开启时若在本页录音，[InjectEndDispatcher] 的兜底（无页面订阅者时停录音 + 弹窗）接管，
 * 行为正确。
 */
class NativeFaceToFaceActivity : NativeDemoBaseActivity() {
    // ===== 视图 =====
    // ===== 状态 =====
    private var status = RecordStatusDef.IDLE

    /** 当前录音方向（start 成功即记录；同方向连点拦截与 updateParams 都依赖它）。  */
    private var activeChannel: Int = CHANNEL_FRIEND

    /** 返回确认后置位：录音真正结束后自动退出页面（生产「返回即结束并保存」同款）。  */
    private var pendingBackAfterStop = false

    // ===== 每通道配置 =====
    /** 收音源：[AUDIO_SOURCE_PHONE_MIC] / [AUDIO_SOURCE_EARPHONE_MIC]。  */
    private var friendInput: Int = AUDIO_SOURCE_PHONE_MIC
    private var myInput: Int = AUDIO_SOURCE_PHONE_MIC

    /** 译文输出：[OUTPUT_PHONE_SPEAKER] / [OUTPUT_BT_EARPHONE]。  */
    private var friendOutput: Int = OUTPUT_PHONE_SPEAKER
    private var myOutput: Int = OUTPUT_PHONE_SPEAKER

    /** TTS 播放策略，按通道独立。  */
    private var friendPlayback: TtsPlaybackMode = TtsPlaybackMode.IMMEDIATE
    private var myPlayback: TtsPlaybackMode = TtsPlaybackMode.IMMEDIATE

    /** 通道共用的入门版耳机（任一通道输入/输出用到耳机时必须有）；deviceId 归属也随它。  */
    private var earphone: DeviceItem? = null
    private var friendLang: String? = "zh"
    private var myLang: String? = "en"
    private var recordedMs = 0L
    private var controlLocked = false

    /** 内容布局绑定。  */
    private lateinit var binding: ActivityNativeF2fRecordBinding

    private var recordListener: IRecordListener? = null
    /** 当前录音 ID：播放缓存 TTS 时用它查询实时转写结果里的 ttsPath。  */
    private var recordId: String? = null
    /** 单句 TTS 播放器（同一时刻只播一句，新的播放会先停旧的）。  */
    private var ttsPlayer: MediaPlayer? = null
    /** 正在播放的气泡，用于复位播放图标。  */
    private var playingBubble: Bubble? = null

    /**
     * 气泡流。每个气泡对应一句：origin（原文）+ trans（译文），
     * channel 决定左右对齐与译文颜色。
     */
    private class Bubble(
        val channel: Int,
        val wrapper: View,
        val card: View?,
        val originView: TextView,
        val transView: TextView,
        var playBtn: View?
    ) {
        var origin: String = ""
        var trans: String = ""
        /** 该句的实时转写 requestId，播放缓存 TTS 时按它匹配 ttsPath。  */
        var requestId: String? = null
    }

    /** 按创建顺序持有全部气泡，供裁剪；进行中的气泡也在里面。  */
    private val bubbles: MutableList<Bubble?> = ArrayList<Bubble?>()

    /** 每条通道各自「进行中」的气泡；同一时刻通常只有一条通道在收音。  */
    private val currentByChannel: SparseArray<Bubble?> = SparseArray<Bubble?>()

    override val contentLayoutId: Int
        get() = R.layout.activity_native_f2f_record

    override val titleResId: Int
        get() = R.string.native_f2f_title

    override fun onContentViewCreated() {
        bindViews()
        setupRoom()
    }

    override fun useScrollContainer(): Boolean {
        // 录音室结构：气泡区内部滚动，底部控制条固定；外层不能再套 NestedScrollView
        return false
    }

    override fun onDestroy() {
        // 单句 TTS 播放器属于页面资源，退出时释放，避免 MediaPlayer 泄漏
        stopTts()
        super.onDestroy()
    }

    private fun bindViews() {
        binding = ActivityNativeF2fRecordBinding.bind(contentView())
    }

    private fun setupRoom() {
        binding.f2fFriendSpeak.setOnClickListener(View.OnClickListener { v: View? ->
            startDirection(
                CHANNEL_FRIEND
            )
        })
        binding.f2fMySpeak.setOnClickListener(View.OnClickListener { v: View? -> startDirection(CHANNEL_MY) })
        binding.f2fPauseCircle.setOnClickListener(View.OnClickListener { v: View? -> pause() })
        binding.f2fSwap.setOnClickListener(View.OnClickListener { v: View? ->
            val tmp = friendLang
            friendLang = myLang
            myLang = tmp
            refreshLangButtons()
        })
        binding.f2fFriendLang.setOnClickListener(View.OnClickListener { v: View? -> showLangPicker(true) })
        binding.f2fMyLang.setOnClickListener(View.OnClickListener { v: View? -> showLangPicker(false) })
        binding.f2fFriendInput.setOnClickListener(View.OnClickListener { v: View? -> showInputPicker(CHANNEL_FRIEND) })
        binding.f2fMyInput.setOnClickListener(View.OnClickListener { v: View? -> showInputPicker(CHANNEL_MY) })
        binding.f2fFriendOutput.setOnClickListener(View.OnClickListener { v: View? -> showOutputPicker(CHANNEL_FRIEND) })
        binding.f2fMyOutput.setOnClickListener(View.OnClickListener { v: View? -> showOutputPicker(CHANNEL_MY) })
        binding.f2fFriendPlayback.setOnClickListener(View.OnClickListener { v: View? -> showPlaybackPicker(CHANNEL_FRIEND) })
        binding.f2fMyPlayback.setOnClickListener(View.OnClickListener { v: View? -> showPlaybackPicker(CHANNEL_MY) })
        refreshLangButtons()
        refreshPickerLabels()
        refreshRoomState()

        // 基类把顶栏返回键绑成了直接 finish；本页录音中返回要走「确认结束并保存」
        topBarBackView().setOnClickListener(View.OnClickListener { v: View? -> onBackPressed() })
    }

    override fun onBackPressed() {
        if (!this.isRecordingOrPaused) {
            super.onBackPressed()
            return
        }
        // 录音 / 暂停中返回：不直接退出，弹窗确认「结束并保存」
        AlertDialog.Builder(this)
            .setTitle(R.string.native_f2f_back_title)
            .setMessage(R.string.native_f2f_back_message)
            .setPositiveButton(
                R.string.native_f2f_stop,
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int -> stopAndBack() })
            .setNegativeButton(R.string.native_f2f_back_continue, null)
            .show()
    }

    private fun refreshLangButtons() {
        binding.f2fFriendLang.setText(getString(R.string.native_f2f_friend_lang) + " · " + friendLang)
        binding.f2fMyLang.setText(getString(R.string.native_f2f_my_lang) + " · " + myLang)
    }

    /**
     * 语言选择。单选对话框——语言对是「点一下改」的轻入口，与生产 LanguageSelectPopup 同位。
     * 
     * @param friend true 选朋友的语言，false 选我的语言
     */
    private fun showLangPicker(friend: Boolean) {
        val current = if (friend) friendLang else myLang
        var checked = 0
        for (i in LANGS.indices) {
            if (LANGS[i] == current) {
                checked = i
                break
            }
        }
        AlertDialog.Builder(this)
            .setTitle(if (friend) R.string.native_f2f_friend_lang else R.string.native_f2f_my_lang)
            .setSingleChoiceItems(
                LANGS,
                checked,
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                    if (friend) {
                        friendLang = LANGS[which]
                    } else {
                        myLang = LANGS[which]
                    }
                    refreshLangButtons()
                    dialog?.dismiss()
                    if (this.isRecordingOrPaused) {
                        // 录音中改语言对 = updateParams 只换语言，f2fChannel 保持当前方向
                        //（生产 updateRecording 同构：语言对变更从不经 stop/restart）
                        updateParams()
                    }
                })
            .show()
    }

    /**
     * 按方向开录。
     * 
     * 
     * 状态分派（见 [startDirection] 的完整表格）：
     * 
     *  * 空闲：直接 start
     *  * 录音中 + 同方向：忽略（无变化）
     *  * 其余录音中 / 暂停中变更：updateParams=true 原地下发
     * 
     * 
     * @param channel [.CHANNEL_FRIEND] 或 [.CHANNEL_MY]
     */
    /**
     * 按方向开录 / 原地更新。
     * <p>
     * 状态分派（`updateParams` 语义由 SDK 处理，无需 stop/restart）：
     * <ul>
     *     <li>空闲 / 已结束：正常 start，开新会话</li>
     *     <li>录音中：一律 `updateParams=true` 原地下发——切方向、换输入/输出、
     *         换播放策略都是参数更新，录音不断流（换设备时 SDK 走 token 重建）</li>
     *     <li>暂停中 + 同方向：`updateParams=false`，SDK 按同参会话映射为**继续**</li>
     *     <li>暂停中 + 换方向或改配置：`updateParams=true` 只更新参数、保持暂停，
     *         恢复时（点同方向播放键）以新参数继续</li>
     * </ul>
     *
     * @param force true 跳过「同方向连点」拦截，用于配置变更的原地下发
     */
    private fun startDirection(channel: Int, force: Boolean = false) {
        if (status == RecordStatusDef.RECORDING && activeChannel == channel && !force) {
            toast(getString(R.string.native_f2f_toast_same_direction))
            return
        }
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
        lockControl()
        val resuming = status == RecordStatusDef.PAUSING && activeChannel == channel && !force
        // 录音中（切方向 / 换配置）与暂停中（换配置）都走参数更新语义，SDK 原地切换不断流；
        // 暂停中点同方向播放键 = 恢复（updateParams=false，SDK 按同参会话映射继续）
        val updating = !resuming && !isFreshState()
        if (updating) {
            // 参数更新路径的回调可能不触发，方向先行生效
            activeChannel = channel
        }
        val params = buildParams(channel)
        params.updateParams = updating
        manager.startAudioRecording(
            recordingDeviceId(),
            params,
            object : IResultCallback {
                override fun onSuccess() {
                    runOnUi {
                        status = RecordStatusDef.RECORDING
                        // 气泡与计时跨方向切换保留（生产：切方向不清转写、计时持续）
                        startTicking()
                        refreshRoomState()
                    }
                }

                override fun onError(code: String?, error: String?) {
                    runOnUi {
                        controlLocked = false
                        refreshRoomState()
                        toastRecordError(code)
                    }
                }
            })
    }

    /** @return 页面是否处于「没有进行中/暂停中录音」的全新状态 */
    private fun isFreshState(): Boolean =
        status == RecordStatusDef.IDLE || status == RecordStatusDef.STOP

    /**
     * 构造 f2f 参数。
     * 
     * 
     * `f2fChannel` 与语言对方向绑定：朋友说 → original=朋友语言；
     * 我说 → original=我的语言。TTS 使用单条 `ttsConfig`（SYSTEM_MIC）。
     * 
     * @param channel 方向
     * @return 参数对象
     */
    private fun buildParams(channel: Int): RecordParamsV2 {
        val input = if (channel == CHANNEL_FRIEND) friendInput else myInput
        val output = if (channel == CHANNEL_FRIEND) friendOutput else myOutput
        val playback = if (channel == CHANNEL_FRIEND) friendPlayback else myPlayback
        val params = RecordParamsV2()
        params.businessType = BUSINESS_TYPE_NOTE
        params.recordMode = RECORD_MODE_F2F
        params.audioSource = input
        val sources: MutableList<Int> = ArrayList()
        sources.add(input)
        params.audioSourceList = sources
        params.needAsr = true
        params.needTranslate = true
        params.needTts = true
        params.needAmplitude = true
        params.ttsPlaybackMode = playback
        params.f2fChannel = channel
        params.originalLanguage = if (channel == CHANNEL_FRIEND) friendLang else myLang
        params.targetLanguage = if (channel == CHANNEL_FRIEND) myLang else friendLang
        params.ttsConfig = buildTtsConfig(output)
        return params
    }

    /**
     * TTS 配置：单条（双条会创建第二个播放器并覆盖引擎拉流回调，导致 TTS 无声；
     * 双播放器仅 Pro 耳机双通道输出场景需要）。
     * <p>
     * 输出源决定 output：手机扬声器 → SYSTEM_MIC；蓝牙耳机 → SYSTEM_BLUETOOTH
     * （走系统 BT 通道，devId 传空，播放到当前已连接的耳机）。
     */
    private fun buildTtsConfig(output: Int): TTSConfig = TTSConfig(
        "", if (output == OUTPUT_BT_EARPHONE) TTSOutput.SYSTEM_BLUETOOTH else TTSOutput.SYSTEM_MIC,
        TTSEncode.DEFAULT, TTSOutputChannel.DEFAULT
    )

    /**
     * 录音任务的 deviceId：任一通道输入用耳机 → 该耳机 devId（生产 PhoneEar 同款——
     * 会话归属挂在耳机上，朋友方向照样手机麦采集）；纯手机 → PHONE。
     */
    private fun recordingDeviceId(): String =
        if (friendInput == AUDIO_SOURCE_EARPHONE_MIC || myInput == AUDIO_SOURCE_EARPHONE_MIC)
            earphone?.devId ?: DEVICE_ID_PHONE
        else DEVICE_ID_PHONE

    private fun refreshPickerLabels() {
        binding.f2fFriendInput.setText(
            if (friendInput == AUDIO_SOURCE_EARPHONE_MIC) R.string.native_f2f_input_earphone
            else R.string.native_f2f_input_phone
        )
        binding.f2fMyInput.setText(
            if (myInput == AUDIO_SOURCE_EARPHONE_MIC) R.string.native_f2f_input_earphone
            else R.string.native_f2f_input_phone
        )
        binding.f2fFriendOutput.setText(
            if (friendOutput == OUTPUT_BT_EARPHONE) R.string.native_f2f_output_earphone
            else R.string.native_f2f_output_phone
        )
        binding.f2fMyOutput.setText(
            if (myOutput == OUTPUT_BT_EARPHONE) R.string.native_f2f_output_earphone
            else R.string.native_f2f_output_phone
        )
        binding.f2fFriendPlayback.setText(playbackLabel(friendPlayback))
        binding.f2fMyPlayback.setText(playbackLabel(myPlayback))
    }

    private fun playbackLabel(mode: TtsPlaybackMode): Int = when (mode) {
        TtsPlaybackMode.DEFERRED -> R.string.native_f2f_playback_deferred
        TtsPlaybackMode.PLAY_AND_CACHE -> R.string.native_f2f_playback_cache
        else -> R.string.native_f2f_playback_immediate
    }

    /**
     * 收音源选择。
     * <p>
     * 生效规则：改的是**活跃通道**且录音中 → 输入源跨界（手机麦↔耳机麦）意味着
     * executor 类型要换（由 audioSourceList 决定、创建时定死，params 更新不重建），
     * 走 `updateParams` 原地下发；其余情况只存内存，下次该方向 start 生效。
     */
    private fun showInputPicker(channel: Int) {
        val current = if (channel == CHANNEL_FRIEND) friendInput else myInput
        val items = arrayOf(
            getString(R.string.native_f2f_pick_phone_mic),
            getString(R.string.native_f2f_pick_earphone_mic) + earphoneSuffix()
        )
        val checked = if (current == AUDIO_SOURCE_EARPHONE_MIC) 1 else 0
        AlertDialog.Builder(this)
            .setTitle(R.string.native_f2f_pick_input)
            .setSingleChoiceItems(items, checked) { dialog, which ->
                dialog.dismiss()
                if (which == 1 && earphone == null) {
                    // 还没选耳机：先弹设备列表，选完回填输入源
                    showEarphonePicker(channel)
                    return@setSingleChoiceItems
                }
                val newValue = if (which == 1) AUDIO_SOURCE_EARPHONE_MIC else AUDIO_SOURCE_PHONE_MIC
                applyInput(channel, newValue)
            }
            .show()
    }

    private fun earphoneSuffix(): String =
        if (earphone == null) getString(R.string.native_f2f_pick_earphone_title)
        else "（" + (earphone?.displayName ?: "") + "）"

    private fun applyInput(channel: Int, value: Int) {
        if (channel == CHANNEL_FRIEND) friendInput = value else myInput = value
        refreshPickerLabels()
        // 输入源变更走 startAudioRecording + updateParams=true（SDK 原地切换；
        // 换设备时由 SDK 的 token 重建路径处理），无需 stop/restart
        applyLiveUpdateIfActive()
    }

    /** 耳机设备选择：家庭设备列表过滤入门版 / OS 耳机。  */
    private fun showEarphonePicker(inputChannel: Int?) {
        populateDeviceItems()
        val candidates = deviceItems.filter { it.productType == DeviceItem.TYPE_ENTRY || it.productType == DeviceItem.TYPE_OS_BUDS }
        if (candidates.isEmpty()) {
            toast(getString(R.string.native_f2f_no_earphone))
            return
        }
        val names = candidates.map { it.displayName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.native_f2f_pick_earphone_title)
            .setSingleChoiceItems(names, -1) { dialog, which ->
                dialog.dismiss()
                earphone = candidates[which]
                refreshPickerLabels()
                if (inputChannel != null) {
                    applyInput(inputChannel, AUDIO_SOURCE_EARPHONE_MIC)
        } else if (this.isRecordingOrPaused &&
                    (friendInput == AUDIO_SOURCE_EARPHONE_MIC || myInput == AUDIO_SOURCE_EARPHONE_MIC)
                ) {
                    // 录音中换耳机：deviceId 变化 → SDK 走 token 重建 + 新 executor
                    startDirection(activeChannel, force = true)
                }
            }
            .show()
    }

    /** 译文输出选择。生效：录音中 → updateParams=原地更新；空闲 → 下次 start 生效。  */
    private fun showOutputPicker(channel: Int) {
        val current = if (channel == CHANNEL_FRIEND) friendOutput else myOutput
        val items = arrayOf(
            getString(R.string.native_f2f_pick_phone_speaker),
            getString(R.string.native_f2f_pick_bt_earphone)
        )
        val checked = if (current == OUTPUT_BT_EARPHONE) 1 else 0
        AlertDialog.Builder(this)
            .setTitle(R.string.native_f2f_pick_output)
            .setSingleChoiceItems(items, checked) { dialog, which ->
                dialog.dismiss()
                val newValue = if (which == 1) OUTPUT_BT_EARPHONE else OUTPUT_PHONE_SPEAKER
                if (channel == CHANNEL_FRIEND) friendOutput = newValue else myOutput = newValue
                refreshPickerLabels()
                applyLiveUpdateIfActive()
            }
            .show()
    }

    /** TTS 播放策略选择（按通道独立）。生效规则同输出源。  */
    private fun showPlaybackPicker(channel: Int) {
        val current = if (channel == CHANNEL_FRIEND) friendPlayback else myPlayback
        val items = arrayOf(
            getString(R.string.native_f2f_pick_playback_immediate),
            getString(R.string.native_f2f_pick_playback_deferred),
            getString(R.string.native_f2f_pick_playback_cache)
        )
        val checked = when (current) {
            TtsPlaybackMode.DEFERRED -> 1
            TtsPlaybackMode.PLAY_AND_CACHE -> 2
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.native_f2f_pick_playback)
            .setSingleChoiceItems(items, checked) { dialog, which ->
                dialog.dismiss()
                val newValue = when (which) {
                    1 -> TtsPlaybackMode.DEFERRED
                    2 -> TtsPlaybackMode.PLAY_AND_CACHE
                    else -> TtsPlaybackMode.IMMEDIATE
                }
                if (channel == CHANNEL_FRIEND) friendPlayback = newValue else myPlayback = newValue
                refreshPickerLabels()
                // 播放模式变了：已有气泡的播放入口要跟着增减（缓存型才有 ttsPath 可播）
                syncPlayButtons()
                applyLiveUpdateIfActive()
            }
            .show()
    }

    /** 录音中/暂停中的配置类变更：updateParams= true 原地下发，录音不断。  */
    private fun applyLiveUpdateIfActive() {
        if (this.isRecordingOrPaused) {
            startDirection(activeChannel, force = true)
        }
    }

    /**
     * 结束并退出。
     * 
     * 
     * 点击时可能正处在其它控制动作的锁定期（`stopRecord` 会静默返回），锁住时延后重试。
     * 录音结束以 `status=STOP` 为准，收尾后自动退出页面。
     */
    private fun stopAndBack() {
        if (controlLocked) {
            main.postDelayed({ this.stopAndBack() }, CONTROL_LOCK_MS)
            return
        }
        if (!this.isRecordingOrPaused) {
            finish()
            return
        }
        pendingBackAfterStop = true
        stopRecord()
    }

    private fun stopRecord() {
        if (controlLocked || !this.isRecordingOrPaused) return
        lockControl()
        manager.stopRecordTransfer(DEVICE_ID_PHONE, object : IResultCallback {
            override fun onSuccess() {
                runOnUi { this@NativeFaceToFaceActivity.refreshRoomState() }
            }

            override fun onError(code: String?, error: String?) {
                runOnUi {
                    controlLocked = false
                    refreshRoomState()
                    toastRecordError(code)
                }
            }
        })
    }

    /** 录音中改动语言对时用：只换语言，方向保持 [.activeChannel]。  */
    private fun pause() {
        if (controlLocked || status != RecordStatusDef.RECORDING) return
        lockControl()
        manager.pauseRecordTransfer(DEVICE_ID_PHONE, object : IResultCallback {
            override fun onSuccess() {
                runOnUi {
                    status = RecordStatusDef.PAUSING
                    stopTicking()
                    refreshRoomState()
                }
            }

            override fun onError(code: String?, error: String?) {
                runOnUi {
                    controlLocked = false
                    refreshRoomState()
                    toastRecordError(code)
                }
            }
        })
    }

    private fun updateParams() {
        manager.updateParams(DEVICE_ID_PHONE, buildParams(activeChannel), object : IResultCallback {
            override fun onSuccess() {
            }

            override fun onError(code: String?, error: String?) {
                runOnUi { toastRecordError(code) }
            }
        })
    }

    private fun lockControl() {
        controlLocked = true
        refreshRoomState()
        main.postDelayed({
            controlLocked = false
            refreshRoomState()
        }, CONTROL_LOCK_MS)
    }

    private fun toastRecordError(code: String?) {
        runOnUi { toast(messageOf(this, code, false)) }
    }

    private val isRecordingOrPaused: Boolean
        get() = status == RecordStatusDef.RECORDING || status == RecordStatusDef.PAUSING

    override fun onResume() {
        super.onResume()
        // 不订阅灌流结束事件：本页不与 InjectEndDispatcher 抢单槽，
        // 灌流播完时由兜底（无页面订阅者路径）停录音 + 弹窗，行为正确。
        get()
    }

    override fun registerListeners() {
        recordListener = object : IRecordListener {
            override fun onRecordStatusUpdate(deviceId: String, bean: RecordStatusBean?) {
                if (bean == null) return
                runOnUi {
                    val previous = status
                    status = bean.status
                    // 记住当前录音 ID：播放缓存 TTS 时按它查询实时转写结果
                    if (!TextUtils.isEmpty(bean.recordId)) {
                        recordId = bean.recordId
                    }
                    if (bean.duration > 0) {
                        recordedMs = bean.duration
                        binding.f2fTimer.text = formatDuration(recordedMs)
                    }
                    if (status == RecordStatusDef.RECORDING) {
                        startTicking()
                    } else {
                        stopTicking()
                    }
                    refreshRoomState()
                    // 结束统一以 status=STOP 判定（onRecordFinish 事件当前 SDK 不予触发）
                    if (status == RecordStatusDef.STOP && previous != RecordStatusDef.STOP) {
                        handleRecordingStopped(null)
                    }
                }
            }

            override fun onRecordAmplitudeUpdate(
                deviceId: String,
                channel: Int,
                amplitude: Double
            ) {
                // f2f 不展示波形：振幅仅用于底层质量统计
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
    }

    override fun unregisterListeners() {
        if (recordListener != null) {
            manager.removeRecordListener(recordListener)
            recordListener = null
        }
    }

    // ===================== 状态渲染 =====================
    private fun refreshRoomState() {
        val recording = status == RecordStatusDef.RECORDING
        val paused = status == RecordStatusDef.PAUSING
        val active = recording || paused

        // 仅录音中锁定配置（改动走 updateParams=true 原地下发）；暂停中开放修改
        val pickers = arrayOf(
            binding.f2fFriendInput, binding.f2fFriendOutput, binding.f2fFriendPlayback,
            binding.f2fMyInput, binding.f2fMyOutput, binding.f2fMyPlayback
        )
        for (btn in pickers) {
            btn?.setEnabled(!recording)
            btn.setAlpha(if (recording) 0.4f else 1f)
        }

        // 方向按钮区随状态收缩：录音中收成单枚暂停圆（当前方向色），其余状态两枚圆
        val showPauseCircle = recording
        binding.f2fCircleRow.setVisibility(if (showPauseCircle) View.GONE else View.VISIBLE)
        binding.f2fPauseCircle.setVisibility(if (showPauseCircle) View.VISIBLE else View.GONE)
        binding.f2fPauseCircle.getBackground().setTint(
            getResources().getColor(
                if (activeChannel == CHANNEL_MY)
                    R.color.ai_voice_tab_selected
                else
                    R.color.ai_voice_card_green,
                getTheme()
            )
        )

        binding.f2fStateDot.getBackground().setTint(
            getResources().getColor(
                if (recording)
                    R.color.ai_voice_status_recording
                else
                    if (paused) R.color.ai_voice_tab_selected else R.color.ai_voice_btn_disable,
                getTheme()
            )
        )
    }

    // ===================== 计时 =====================
    private val durationTicker: Runnable = object : Runnable {
        override fun run() {
            recordedMs += DURATION_TICK_MS
            binding.f2fTimer.setText(formatDuration(recordedMs))
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

    private fun resetDuration() {
        stopTicking()
        recordedMs = 0L
        binding.f2fTimer.setText(R.string.native_duration_zero)
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
        refreshRoomState()
        if (pendingBackAfterStop) {
            pendingBackAfterStop = false
            finish()
            return
        }
        resetDuration()
        if (errorCode == null) {
            toast(getString(R.string.native_f2f_toast_stopped))
        } else {
            toastRecordError(errorCode.toString())
        }
    }

    // ===================== 气泡流转写 =====================
    /**
     * 处理实时转写事件，按 `channel` 分流到左 / 右气泡。
     * 
     * 
     * 事件模型与录音页一致：`text` 是累积文案（进行中覆盖），
     * `status=结束` 封句；译文（phase=TEXT）晚到时回填到该通道最后一句。
     * 
     * @param s 实时转写事件
     */
    private fun appendRealtime(s: RealTimeTransferStatus?) {
        if (s == null) return
        val phase = if (s.phase == null) -1 else s.phase
        val phaseStatus = if (s.status == null) -1 else s.status
        val channel: Int = if (s.channel == null)
            CHANNEL_FRIEND
        else
            (if (s.channel == CHANNEL_MY) CHANNEL_MY else CHANNEL_FRIEND)

        if (phase == PHASE_ASR) {
            // 记录 requestId：播放缓存 TTS 时按它匹配 ttsPath
            if (!TextUtils.isEmpty(s.requestId)) {
                val current = currentByChannel.get(channel)
                if (current != null) {
                    current.requestId = s.requestId
                } else if (!bubbles.isEmpty()) {
                    // 封句后迟到的事件：回填到该通道最后一句
                    val last = bubbles.get(bubbles.size - 1)
                    if (last != null && last.channel == channel && TextUtils.isEmpty(last.requestId)) {
                        last.requestId = s.requestId
                    }
                }
            }
            if (!TextUtils.isEmpty(s.text)) {
                val bubble = ensureCurrent(channel)
                if (!TextUtils.isEmpty(s.requestId)) {
                    bubble.requestId = s.requestId
                }
                bubble.origin = s.text
                bubble.originView.setText(s.text)
            }
            if (phaseStatus == PHASE_STATUS_FINISH) {
                currentByChannel.remove(channel)
            }
        } else if (phase == PHASE_TEXT) {
            if (!TextUtils.isEmpty(s.translateText)) {
                var bubble = currentByChannel.get(channel)
                if (bubble == null && !bubbles.isEmpty()) {
                    bubble = bubbles.get(bubbles.size - 1)
                }
                if (bubble != null && bubble.channel == channel) {
                    bubble.trans = s.translateText
                    bubble.transView.setText(s.translateText)
                }
            }
        }
        binding.f2fBubbleScroll.post { binding.f2fBubbleScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** 取该通道进行中的气泡，没有就新建一个（气泡 View 同时建好并挂进容器）。  */
    private fun ensureCurrent(channel: Int): Bubble {
        val existing = currentByChannel.get(channel)
        if (existing != null) {
            return existing
        }
        val bubble = createBubble(channel)
        bubbles.add(bubble)
        binding.f2fBubbleContainer.addView(bubble.wrapper)
        currentByChannel.put(channel, bubble)
        trimOldBubbles()
        return bubble
    }

    private fun createBubble(channel: Int): Bubble {
        val card = LinearLayout(this)
        card.setOrientation(LinearLayout.VERTICAL)
        card.setBackgroundResource(R.drawable.bg_native_record_bubble)
        val pad = (12 * getResources().getDisplayMetrics().density).toInt()
        card.setPadding(pad, pad, pad, pad)
        val cardParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        card.setLayoutParams(cardParams)

        val origin = TextView(this)
        origin.setTextSize(13f)
        origin.setTextColor(getResources().getColor(R.color.ai_voice_text_secondary, getTheme()))
        card.addView(origin)

        val trans = TextView(this)
        trans.setTextSize(16f)
        trans.setTypeface(null, Typeface.BOLD)
        trans.setTextColor(
            getResources().getColor(
                if (channel == CHANNEL_FRIEND)
                    R.color.ai_voice_card_green
                else
                    R.color.ai_voice_tab_selected, getTheme()
            )
        )
        card.addView(trans)

        // 气泡行的外层容器：气泡 + 播放按钮（广播按钮放在气泡「起始位」，
        // 即右通道在气泡左侧、左通道在气泡右侧，整行垂直居中）
        val wrapper = LinearLayout(this)
        wrapper.orientation = LinearLayout.HORIZONTAL
        wrapper.gravity = Gravity.CENTER_VERTICAL
        val wrapperParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        wrapperParams.bottomMargin = pad
        wrapper.layoutParams = wrapperParams

        val playBtn = if (isCachedPlayback(channel)) createPlayButton(channel) else null
        if (channel == CHANNEL_FRIEND) {
            // 左通道气泡靠左，播放按钮在其右侧（朝屏幕中间）
            wrapper.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            wrapper.addView(card)
            if (playBtn != null) {
                wrapper.addView(playBtn)
            }
        } else {
            // 右通道气泡靠右，播放按钮在其左侧
            wrapper.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            if (playBtn != null) {
                wrapper.addView(playBtn)
            }
            wrapper.addView(card)
        }

        val bubble = Bubble(channel, wrapper, card, origin, trans, playBtn)
        if (playBtn != null) {
            playBtn.setOnClickListener(View.OnClickListener { v: View? -> onPlayClicked(bubble) })
        }
        return bubble
    }

    /** @return 该通道的播放策略是否为缓存型（只有缓存型才会有落盘的 ttsPath） */
    private fun isCachedPlayback(channel: Int): Boolean {
        val mode = if (channel == CHANNEL_FRIEND) friendPlayback else myPlayback
        return mode != TtsPlaybackMode.IMMEDIATE
    }

    /**
     * 播放按钮：气泡两侧的圆形小按钮，垂直居中。
     * <p>
     * 常驻展示（不预先查 ttsPath）——TTS 是异步合成的，封句时路径通常还没落库；
     * 点击时才查询一次，既省请求也避免「按钮迟迟不出现」。
     */
    private fun createPlayButton(channel: Int): View {
        val btn = android.widget.ImageView(this)
        val size = (32 * getResources().getDisplayMetrics().density).toInt()
        val margin = (8 * getResources().getDisplayMetrics().density).toInt()
        val params = LinearLayout.LayoutParams(size, size)
        params.setMargins(margin, 0, margin, 0)
        btn.layoutParams = params
        btn.setBackgroundResource(R.drawable.bg_native_f2f_circle)
        btn.setImageResource(R.drawable.ic_native_play)
        btn.setColorFilter(
            getResources().getColor(
                if (channel == CHANNEL_FRIEND) R.color.ai_voice_card_green
                else R.color.ai_voice_tab_selected, getTheme()
            )
        )
        btn.contentDescription = getString(R.string.native_f2f_play_tts)
        btn.setPadding(margin, margin, margin, margin)
        return btn
    }

    /**
     * 点击播放该句缓存 TTS。
     * <p>
     * 路径来自 `getRecordTransferRealTimeResult` 的 `ttsPath`，按句子的 `requestId` 匹配——
     * ttsPath 由 SDK 在该句 TTS 流结束时写入数据库（onTTSStop → ttsCacheWriter.finish → 落库），
     * 所以点击时查一次是最实时的取法；查不到说明这句话的语音还没合成完。
     */
    private fun onPlayClicked(bubble: Bubble) {
        // 正在播这句 → 停止
        if (playingBubble === bubble) {
            stopTts()
            return
        }
        val rid = recordId
        if (TextUtils.isEmpty(rid) || TextUtils.isEmpty(bubble.requestId)) {
            toast(getString(R.string.native_f2f_tts_not_ready))
            return
        }
        manager.getRecordTransferRealTimeResult(
            null, rid, null,
            object : IRecordCallBack<MutableList<RecordTransferRealTimeResult?>?> {
                override fun onSuccess(result: MutableList<RecordTransferRealTimeResult?>?) {
                    val path = findTtsPath(result, bubble.requestId)
                    runOnUi {
                        if (TextUtils.isEmpty(path)) {
                            toast(getString(R.string.native_f2f_tts_not_ready))
                        } else {
                            path?.let { playTts(bubble, it) }
                        }
                    }
                }

                override fun onError(code: String?, error: String?) {
                    runOnUi { toast(getString(R.string.native_f2f_tts_not_ready)) }
                }
            })
    }

    /** 在查询结果里按 requestId 找该句的 ttsPath。  */
    private fun findTtsPath(
        result: MutableList<RecordTransferRealTimeResult?>?,
        requestId: String?
    ): String? {
        if (result == null || requestId == null) return null
        for (item in result) {
            if (item != null && requestId == item.requestId && !TextUtils.isEmpty(item.ttsPath)) {
                return item.ttsPath
            }
        }
        return null
    }

    /** 播放一句 TTS；播放前先停掉上一句，播完自动复位图标。  */
    private fun playTts(bubble: Bubble, path: String) {
        stopTts()
        try {
            val player = MediaPlayer()
            player.setDataSource(path)
            player.setOnCompletionListener { p: MediaPlayer -> releasePlayer(p) }
            player.setOnErrorListener { p: MediaPlayer, what: Int, extra: Int ->
                releasePlayer(p)
                toast(getString(R.string.native_f2f_tts_play_failed))
                true
            }
            player.prepare()
            player.start()
            ttsPlayer = player
            playingBubble = bubble
            setPlayIcon(bubble, true)
        } catch (e: Exception) {
            stopTts()
            toast(getString(R.string.native_f2f_tts_play_failed))
        }
    }

    private fun releasePlayer(player: MediaPlayer) {
        try {
            player.release()
        } catch (ignored: Exception) {
        }
        if (ttsPlayer === player) {
            ttsPlayer = null
            val bubble = playingBubble
            playingBubble = null
            if (bubble != null) {
                setPlayIcon(bubble, false)
            }
        }
    }

    private fun stopTts() {
        val player = ttsPlayer
        ttsPlayer = null
        if (player != null) {
            try {
                player.stop()
            } catch (ignored: Exception) {
            }
            try {
                player.release()
            } catch (ignored: Exception) {
            }
        }
        val bubble = playingBubble
        playingBubble = null
        if (bubble != null) {
            setPlayIcon(bubble, false)
        }
    }

    /** 播放中显示暂停图标，结束后复位为播放图标。 */
    private fun setPlayIcon(bubble: Bubble, playing: Boolean) {
        val btn = bubble.playBtn as? android.widget.ImageView ?: return
        btn.setImageResource(if (playing) R.drawable.ic_native_pause else R.drawable.ic_native_play)
    }

    /**
     * 按各通道当前的播放策略同步已有气泡的播放入口。
     * <p>
     * 只在暂停 / 空闲时可改播放模式（录音中配置锁定），因此这里不需要考虑正在播放时的增删。
     */
    private fun syncPlayButtons() {
        for (bubble in bubbles) {
            if (bubble == null) continue
            val wrapper = bubble.wrapper as? LinearLayout ?: continue
            val shouldShow = isCachedPlayback(bubble.channel)
            if (shouldShow && bubble.playBtn == null) {
                val btn = createPlayButton(bubble.channel)
                btn.setOnClickListener(View.OnClickListener { v: View? -> onPlayClicked(bubble) })
                if (bubble.channel == CHANNEL_FRIEND) {
                    // 左通道：气泡在前，按钮在后（朝屏幕中间）
                    wrapper.addView(btn)
                } else {
                    // 右通道：按钮在前（气泡左侧）
                    wrapper.addView(btn, 0)
                }
                bubble.playBtn = btn
            } else if (!shouldShow && bubble.playBtn != null) {
                wrapper.removeView(bubble.playBtn)
                bubble.playBtn = null
            }
        }
    }

    /** 气泡超过上限时从最旧的开始裁，防止长会话把内存吃穿。  */
    private fun trimOldBubbles() {
        while (bubbles.size > MAX_BUBBLES) {
            bubbles.removeAt(0)
            binding.f2fBubbleContainer.removeViewAt(0)
        }
    }

    private fun resetBubbles() {
        bubbles.clear()
        currentByChannel.clear()
        binding.f2fBubbleContainer.removeAllViews()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String?>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CODE_RECORD_AUDIO) return
        if (grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startDirection(CHANNEL_FRIEND)
        } else {
            toast(getString(R.string.native_toast_record_permission_denied))
        }
    }

    companion object {
        private const val REQ_CODE_RECORD_AUDIO = 1101

        /** 语言列表，与 `NativeRecordActivity.LANGS` 保持一致。  */
        private val LANGS = arrayOf<String?>("zh", "en", "de", "ja", "ko", "fr", "es")

        private const val RECORD_MODE_F2F = 3

        /** 手机麦音源（AudioSource.SYSTEM_PHONE_MIC_16K_MONO）。  */
        private const val AUDIO_SOURCE_PHONE_MIC = 1
        /** 入门版耳机音源（AudioSource.SYSTEM_BLUETOOTH_16K_MONO）。  */
        private const val AUDIO_SOURCE_EARPHONE_MIC = 0
        /** 译文输出：手机扬声器（TTSOutput.SYSTEM_MIC）。  */
        private const val OUTPUT_PHONE_SPEAKER = 0
        /** 译文输出：蓝牙耳机（TTSOutput.SYSTEM_BLUETOOTH，系统通道 devId 传空）。  */
        private const val OUTPUT_BT_EARPHONE = 1
        /** 业务类型：0 = AI Note（f2f 实测同为 0，翻译与 TTS 均正常）。  */
        private const val BUSINESS_TYPE_NOTE = 0

        /** channel 0 = 朋友（左 VAD）。  */
        private const val CHANNEL_FRIEND = 0

        /** channel 1 = 我（右 VAD）。  */
        private const val CHANNEL_MY = 1

        private const val DURATION_TICK_MS = 1000L
        private const val CONTROL_LOCK_MS = 600L

        private const val PHASE_ASR = 4
        private const val PHASE_TEXT = 5
        private const val PHASE_STATUS_FINISH = 2

        /** 气泡上限：超过时从最旧的开始清，防止长会话把内存吃穿。  */
        private const val MAX_BUBBLES = 100

        /**
         * 从模式弹窗进入。
         * 
         * @param context 上下文
         */
        fun start(context: Context) {
            context.startActivity(Intent(context, NativeFaceToFaceActivity::class.java))
        }

        private fun formatDuration(ms: Long): String {
            val totalSec = ms / 1000
            return String.format(Locale.getDefault(), "%02d:%02d", totalSec / 60, totalSec % 60)
        }
    }
}
