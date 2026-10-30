package com.tuya.smart.ai_voice.nativeui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.thingclips.smart.ai.audio.utils.PhoneMicAudioInputFactory
import com.thingclips.smart.earphone.enhance.api.bean.AudioFrontInputConfig
import com.thingclips.smart.earphone.enhance.api.bean.AudioFrontInputConfig.AncConfig
import com.thingclips.smart.earphone.enhance.api.bean.AudioFrontInputConfig.ReplaceCaptureData
import com.tuya.smart.ai_voice.R
import com.tuya.smart.ai_voice.databinding.ActivityNativeAudioInjectBinding
import com.tuya.smart.ai_voice.nativeui.base.NativeDemoBaseActivity
import com.tuya.smart.ai_voice.nativeui.widget.AudioInjectFileHelper
import com.tuya.smart.ai_voice.nativeui.widget.AudioInjectFileHelper.Corpus
import com.tuya.smart.ai_voice.nativeui.widget.AudioInjectFileHelper.ensureFileReady
import com.tuya.smart.ai_voice.nativeui.widget.AudioInjectFileHelper.listCorpus
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 灌流（音频注入）设置页。测试用能力，把麦克风采集到的数据替换为指定音频文件。
 * 
 * 
 * 用途是让转写 / 翻译链路跑在**可复现的输入**上：真人对着麦克风念一遍，
 * 每次的音量、语速、环境噪声都不同，结果无法比对；灌一个固定文件进去才谈得上回归。
 * 
 * <h3>三条必须知道的约束</h3>
 * 
 *  1. **只作用于手机 MIC 通道。**底层只有手机麦克风的音频输入走
 * `PhoneMicAudioInputFactory`，蓝牙耳机通道是另一个实现，本设置对其无效
 *  1. **文件必须放在约定目录下。**`ReplaceCaptureData.fileName` 是相对
 * [AudioInjectFileHelper.targetDir] 的路径，不是绝对路径也不是 Uri
 *  1. **只在下次开始录音时生效。**配置在录音任务创建音频输入的那一刻被读取，
 * 录音中改配置对本次录音没有影响
 * 
 * 
 * <h3>配置的生命周期</h3>
 * `setDebugStrategy` 写的是 SDK 里的一个内存字段，**进程重启即失效**，
 * 本页也刻意不做本地持久化——调试开关被持久化后「重启后还开着但没人记得」是最常见的事故。
 * 需要长期开启的场景，请在自己的工程里显式管理，不要依赖 SDK 侧留存。
 * 
 * <h3>关于 3A</h3>
 * anc / agc / aec 随灌流配置一起下发给底层音频处理，降噪等级 SDK 未定义取值范围，
 * 固定使用默认值 [.ANC_LEVEL_DEFAULT]。
 * **这三个开关当前在布局中隐藏**（见 `activity_native_audio_inject.xml` 的
 * `ll_inject_3a`），未勾选即按 false 下发，与底层默认行为一致；
 * 需要放开时去掉该容器的 visibility 即可，本页代码无需改动。
 */
class NativeAudioInjectActivity : NativeDemoBaseActivity() {
    /** 内容布局绑定。  */
    private lateinit var binding: ActivityNativeAudioInjectBinding
    /** 文件拷贝与目录扫描都是阻塞 IO，统一丢到这里执行。  */
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    /** 当前可选的语料，用于判断列表是否为空。  */
    private val corpusList: MutableList<Corpus> = ArrayList<Corpus>()

    override val contentLayoutId: Int
        get() = R.layout.activity_native_audio_inject

    override val titleResId: Int
        get() = R.string.native_inject_title

    override fun onContentViewCreated() {
        bindViews()
        showDeviceWarning()
        restoreCurrentConfig()
        loadFileList()
        binding.btnInjectSave.setOnClickListener(View.OnClickListener { v: View? -> save() })
    }

    /** 本页不涉及 SDK 事件监听，灌流配置是同步读写的内存状态。  */
    override fun registerListeners() {
    }

    override fun unregisterListeners() {
        io.shutdownNow()
    }

    private fun bindViews() {
        binding = ActivityNativeAudioInjectBinding.bind(contentView())
    }

    /**
     * 选中的不是手机时给出提示。
     * 
     * 
     * 不隐藏入口也不禁用表单——藏起来只会让人以为没这个能力，
     * 说清楚「对当前设备不生效」比事后排查录音为什么没被替换要省事得多。
     */
    private fun showDeviceWarning() {
        val deviceId = getIntent().getStringExtra(EXTRA_DEVICE_ID)
        if (DEVICE_ID_PHONE == deviceId) {
            binding.tvInjectDeviceWarning.setVisibility(View.GONE)
            return
        }
        val deviceName = getIntent().getStringExtra(EXTRA_DEVICE_NAME)
        binding.tvInjectDeviceWarning.setText(
            getString(
                R.string.native_inject_device_warning,
                if (TextUtils.isEmpty(deviceName)) deviceId.toString() else deviceName
            )
        )
        binding.tvInjectDeviceWarning.setVisibility(View.VISIBLE)
    }

    // ===================== 读取当前配置 =====================
    /**
     * 把 SDK 里当前生效的灌流配置回填到表单。
     * 
     * 
     * `getDebugStrategy()` 返回 null 表示未开启灌流，走真实麦克风。
     */
    private fun restoreCurrentConfig() {
        val config = PhoneMicAudioInputFactory.getDebugStrategy()
        binding.cbInjectEnabled.setChecked(config != null)
        if (config != null) {
            binding.cbInjectAnc.setChecked(config.anc.enable)
            binding.cbInjectAgc.setChecked(config.agc)
            binding.cbInjectAec.setChecked(config.aec)
        }
        refreshStatusText(config)
    }

    private fun refreshStatusText(config: AudioFrontInputConfig?) {
        if (config == null) {
            binding.tvInjectStatus.setText(R.string.native_inject_status_off)
            return
        }
        binding.tvInjectStatus.setText(
            getString(
                R.string.native_inject_status_on,
                config.replaceCaptureData.fileName,
                onOff(config.anc.enable),
                onOff(config.agc),
                onOff(config.aec)
            )
        )
    }

    private fun onOff(value: Boolean): String {
        return getString(if (value) R.string.native_inject_on else R.string.native_inject_off)
    }

    // ===================== 文件列表 =====================
    private fun loadFileList() {
        io.execute {
            val names: MutableList<Corpus> = listCorpus(this)
            runOnUi { renderFileList(names) }
        }
    }

    private fun renderFileList(corpora: MutableList<Corpus>) {
        corpusList.clear()
        corpusList.addAll(corpora)
        binding.rgInjectFile.removeAllViews()

        if (corpora.isEmpty()) {
            binding.tvInjectFileEmpty.setVisibility(View.VISIBLE)
            return
        }
        binding.tvInjectFileEmpty.setVisibility(View.GONE)

        val config = PhoneMicAudioInputFactory.getDebugStrategy()
        val selected = if (config == null) null else config.replaceCaptureData.fileName

        for (corpus in corpora) {
            val item = RadioButton(this)
            // RadioGroup 靠 ID 管理选中态，动态创建的 View 必须显式分配。
            // 这里只能用 generateViewId：手写常量会撞进 aapt 分配的 R.id 区间，
            // 使 findViewById 可能命中这些临时 View。
            item.setId(View.generateViewId())
            item.setTag(corpus)
            item.setText(
                getString(
                    if (corpus.imported)
                        R.string.native_inject_file_item_imported
                    else
                        R.string.native_inject_file_item_builtin,
                    corpus.baseName()
                )
            )
            item.setTextSize(13f)
            item.setLayoutParams(
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            binding.rgInjectFile.addView(item)
            if (corpus.fileName == selected) {
                item.setChecked(true)
            }
        }
    }

    /**
     * @return 当前选中的语料；未选中时返回 `null`
     */
    private fun selectedCorpus(): Corpus? {
        val checkedId = binding.rgInjectFile.getCheckedRadioButtonId()
        if (checkedId == View.NO_ID) {
            return null
        }
        val checked = binding.rgInjectFile.findViewById<RadioButton?>(checkedId)
        return if (checked == null) null else checked.getTag() as Corpus?
    }

    // ===================== 保存 =====================
    private fun save() {
        if (!binding.cbInjectEnabled.isChecked()) {
            // 关闭灌流：传 null 即恢复真实麦克风采集。
            // 已拷贝到目标目录的文件不删——留着下次开启即可直接使用，省一次拷贝。
            PhoneMicAudioInputFactory.setDebugStrategy(null)
            toast(getString(R.string.native_inject_toast_disabled))
            finish()
            return
        }

        val corpus = selectedCorpus()
        if (corpus == null) {
            toast(getString(R.string.native_inject_toast_need_file))
            return
        }

        val anc = binding.cbInjectAnc.isChecked()
        val agc = binding.cbInjectAgc.isChecked()
        val aec = binding.cbInjectAec.isChecked()

        binding.btnInjectSave.setEnabled(false)
        binding.btnInjectSave.setText(R.string.native_inject_btn_saving)
        io.execute {
            // assets 中的样例要先落到目标目录，底层才读得到；导入语料本来就在那里
            val ready = ensureFileReady(this, corpus)
            runOnUi {
                binding.btnInjectSave.setEnabled(true)
                binding.btnInjectSave.setText(R.string.native_inject_btn_save)
                if (!ready) {
                    toast(getString(R.string.native_inject_toast_copy_failed, corpus.baseName()))
                } else {
                    applyConfig(corpus.fileName, anc, agc, aec)
                }
            }
        }
    }

    /**
     * 下发灌流配置。
     * 
     * 
     * 末位的 `enableAutoInject` 固定传 `false`：为 true 时走的是另一条
     * 「按帧定速读裸 PCM」的自灌流实现，不解析音频容器格式，本页不使用。
     * 
     * @param fileName 相对目标目录的文件名
     * @param anc      降噪
     * @param agc      自动增益
     * @param aec      回声消除
     */
    private fun applyConfig(fileName: String, anc: Boolean, agc: Boolean, aec: Boolean) {
        val config = AudioFrontInputConfig(
            AncConfig(anc, ANC_LEVEL_DEFAULT),
            agc,
            aec,
            ReplaceCaptureData(fileName),
            false
        )
        PhoneMicAudioInputFactory.setDebugStrategy(config)

        val recording = getIntent().getBooleanExtra(EXTRA_RECORDING, false)
        toast(
            getString(
                if (recording)
                    R.string.native_inject_toast_saved_recording
                else
                    R.string.native_inject_toast_saved
            )
        )
        finish()
    }

    companion object {
        private const val EXTRA_DEVICE_ID = "extra_device_id"
        private const val EXTRA_DEVICE_NAME = "extra_device_name"
        private const val EXTRA_RECORDING = "extra_recording"

        /**
         * 降噪等级。SDK 未定义合法取值范围，各处默认值均为 1，此处沿用。
         */
        private const val ANC_LEVEL_DEFAULT = 1

        /**
         * 打开灌流设置页，不带任何上下文。
         * 
         * 
         * 供 Native 能力总入口使用。三个 extra 只影响页面上的提示文案，不影响配置本身，
         * 缺省按「手机本地录音、当前未在录音」处理——灌流本就只对手机 MIC 通道生效，
         * 缺省成 `PHONE` 语义正确。
         * 
         * @param context 上下文
         */
        fun start(context: Context) {
            val intent: Intent = buildIntent(context, DEVICE_ID_PHONE, DEVICE_ID_PHONE, false)
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        /**
         * 构造跳转 Intent。
         * 
         * @param context    上下文
         * @param deviceId   录音页当前选中的设备 ID，用于判断灌流是否对其生效
         * @param deviceName 设备展示名，仅用于提示文案
         * @param recording  录音页当前是否正在录音 / 暂停中
         * @return 跳转 Intent
         */
        fun buildIntent(
            context: Context, deviceId: String,
            deviceName: String, recording: Boolean
        ): Intent {
            val intent = Intent(context, NativeAudioInjectActivity::class.java)
            intent.putExtra(EXTRA_DEVICE_ID, deviceId)
            intent.putExtra(EXTRA_DEVICE_NAME, deviceName)
            intent.putExtra(EXTRA_RECORDING, recording)
            return intent
        }
    }
}
