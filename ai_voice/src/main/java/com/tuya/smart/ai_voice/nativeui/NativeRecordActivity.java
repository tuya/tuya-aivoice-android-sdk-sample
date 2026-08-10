package com.tuya.smart.ai_voice.nativeui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.thingclips.smart.earphone.enhance.api.bean.RealTimeTransferStatus;
import com.thingclips.smart.earphone.enhance.api.bean.RecordParamsV2;
import com.thingclips.smart.earphone.enhance.api.bean.RecordStatusBean;
import com.thingclips.smart.earphone.enhance.api.bean.RecordTransferRealTimeResult;
import com.thingclips.smart.earphone.enhance.api.bean.TTSConfig;
import com.thingclips.smart.earphone.enhance.api.bean.TTSEncode;
import com.thingclips.smart.earphone.enhance.api.bean.TTSOutput;
import com.thingclips.smart.earphone.enhance.api.bean.TTSOutputChannel;
import com.thingclips.smart.earphone.enhance.api.bean.def.RecordStatusDef;
import com.thingclips.smart.earphone.enhance.api.bean.ttt.BTConnectedStatus;
import com.thingclips.smart.earphone.enhance.api.bean.ttt.PhoneBatteryInfo;
import com.thingclips.smart.earphone.enhance.api.bean.ttt.RecordQualityInfo;
import com.thingclips.smart.earphone.enhance.api.listener.INativeAbilityListener;
import com.thingclips.smart.earphone.enhance.api.listener.IRecordCallBack;
import com.thingclips.smart.earphone.enhance.api.listener.IRecordListener;
import com.thingclips.smart.sdk.api.IResultCallback;
import com.tuya.smart.ai_voice.R;
import com.tuya.smart.ai_voice.nativeui.base.NativeDemoBaseActivity;
import com.tuya.smart.ai_voice.nativeui.widget.AmplitudeWaveView;
import com.tuya.smart.ai_voice.nativeui.widget.RecordErrorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 模块 1 · 录音控制 + 模块 7 · 设备能力，演示页。
 * <p>
 * 完整串联一次录音的生命周期：
 * <ul>
 *     <li>进页注册 {@link IRecordListener} 与 {@link INativeAbilityListener}，退出时注销</li>
 *     <li>{@link #restoreExistingTask()} 恢复进行中的任务，并补拉已转写的历史句子</li>
 *     <li>{@link #startRecord()} → {@link #pause()} / {@link #resume()} / {@link #stopRecord()}</li>
 *     <li>{@link #updateParams()} 录音中动态改参，无需 stop/restart</li>
 *     <li>{@link #switchChannel()} 切换收音通道</li>
 * </ul>
 *
 * <h3>录音模式与参数组合</h3>
 * AI 笔记小程序有 4 种模式，参数组合各不相同，由 {@link RecordModeOption} 固化：
 * <pre>
 * 模式          recordMode  needAsr  needAmplitude  needTranslate
 * 现场录音          1        false       true          false
 * 电话录音          0        false       true          false
 * 实时转写          1        true        false         false
 * 同声传译          1        true        false         true（可选 needTts）
 * </pre>
 * 选中模式后自动套用该组合，「高级」区的开关允许在此基础上手动覆盖。
 * <b>{@code needAmplitude} 是唯一的例外</b>：本页各模式一律默认开启以便观察波形，
 * 与上表不符，理由见 {@link #applyModeDefaults()}。
 *
 * <h3>audioSource 的推导</h3>
 * 音源不是固定值，由<b>设备类型 + 是否电话模式</b>共同决定，见 {@link #resolveAudioSource()}。
 * 取错会直接录不出声，是接入时最容易卡住的地方。
 * 另外 Native 侧底层要读 {@code audioSourceList[0]}，因此 {@code audioSource} 与
 * {@code audioSourceList} 必须同时设置。
 *
 * <h3>模块 7 · 设备能力</h3>
 * 这三个能力不是独立功能，而是录音流程的组成部分：
 * <ul>
 *     <li>BT 连接状态 —— 开录前置校验，耳机没连上直接拦截</li>
 *     <li>电量、信噪比 —— 录音中的状态提示</li>
 * </ul>
 * 进页先用 {@code getEarPhoneBTConntectedStatus} 拉一次初值，后续靠监听增量更新。
 * <p>
 * 面对面翻译（{@code recordMode=2} + {@code f2fChannel}）属于 AI Translate 业务，本页不实现。
 * {@code Audio3AConfig}、{@code autoRecognize}、{@code startLivingStatus} 三个字段
 * AI 笔记小程序未使用，本页也不做 UI，用法见 {@link #buildParams(boolean)} 注释。
 */
public class NativeRecordActivity extends NativeDemoBaseActivity {

    private static final String[] LANGS = {"zh", "en", "ja", "ko", "fr", "es"};
    private static final int REQ_CODE_RECORD_AUDIO = 1001;

    // ===== RecordParamsV2.recordMode =====
    /** 电话录音。 */
    private static final int RECORD_MODE_PHONE = 0;
    /** 现场录音 / 会议。 */
    private static final int RECORD_MODE_LIVE = 1;

    // ===== RecordParamsV2.businessType =====
    /** AI 笔记。 */
    private static final int BUSINESS_TYPE_NOTE = 0;

    // ===== audioSource 取值 =====
    /** 系统蓝牙 16K 单声道（入门耳机 / OS 耳机）。 */
    private static final int AUDIO_SOURCE_SYSTEM_BT = 0;
    /** 系统麦克风 16K 单声道（手机本地录音）。 */
    private static final int AUDIO_SOURCE_SYSTEM_MIC = 1;
    /** Pro 耳机现场音源。 */
    private static final int AUDIO_SOURCE_PRO_LIVE = 20;
    /** Pro 耳机电话音源。 */
    private static final int AUDIO_SOURCE_PRO_CALL = 21;
    /** 录音卡片立体声音源。 */
    private static final int AUDIO_SOURCE_CARD = 41;

    // ===== switchRecordChannel 的通道取值 =====
    /** 通道总数，用于循环切换。 */
    private static final int CHANNEL_COUNT = 3;

    // ===== BTConnectedStatus.connectedStatus =====
    /** 耳机蓝牙已连接。 */
    private static final int BT_CONNECTED = 1;

    /** 录音时长自增间隔。 */
    private static final long DURATION_TICK_MS = 1000L;

    /**
     * 控制操作的锁定时长。
     * <p>
     * 开始 / 暂停 / 恢复 / 停止都是异步生效，连点会重复下发导致状态错乱；
     * 锁定期结束或状态事件到达时释放。
     */
    private static final long CONTROL_LOCK_MS = 1000L;

    // ===== RealTimeTransferStatus.phase / status =====
    /** 阶段：ASR 原文。 */
    private static final int PHASE_ASR = 4;
    /** 阶段：翻译文本。 */
    private static final int PHASE_TEXT = 5;
    /** 阶段状态：已结束。 */
    private static final int PHASE_STATUS_FINISH = 2;

    /** 录音事件监听，用字段持有，remove 时传同一引用。 */
    private IRecordListener recordListener;
    /** 设备能力监听，用字段持有，remove 时传同一引用。 */
    private INativeAbilityListener abilityListener;

    // ===== 视图 =====
    private Spinner modeSpinner;
    private Spinner deviceSpinner;
    private CheckBox optAsrSwitch;
    private CheckBox optAmplitudeSwitch;
    private CheckBox optTranslateSwitch;
    private CheckBox optTtsSwitch;
    private Spinner sourceLangSpinner;
    private Spinner targetLangSpinner;
    private TextView statusText;
    private TextView durationText;
    private TextView summaryText;
    private TextView abilityText;
    private AmplitudeWaveView waveView;
    private TextView realtimeText;
    private View btnStart, btnPause, btnResume, btnStop, btnSwitchChannel;

    // ===== 状态 =====
    private int status = RecordStatusDef.IDLE;
    private int currentChannel = 0;

    /**
     * 本地累计的录音时长（毫秒）。
     * <p>
     * {@code onRecordStatusUpdate} 只在状态变化时才推送，两次之间界面会静止，
     * 因此本地按秒自增，收到事件时再用 {@code bean.getDuration()} 校准。
     */
    private long recordedMs = 0L;

    /** 控制操作进行中，用于拦截连点。 */
    private boolean controlLocked = false;
    /** 覆写模式默认值时置 true，避免模式切换回写又触发一次 updateParams。 */
    private boolean applyingModeDefaults = false;

    // ===== 模块 7 缓存的设备能力 =====
    private int btConnectedStatus = -1;
    private String batteryDesc = "-";
    private String qualityDesc = "-";

    @Override
    protected int getContentLayoutId() {
        return R.layout.activity_native_record;
    }

    @Override
    protected int getTitleResId() {
        return R.string.native_record_title;
    }

    @Override
    protected void onContentViewCreated() {
        bindViews();
        setupModeSpinner();
        setupDeviceSpinner(deviceSpinner);
        setupLangSpinners();
        setupAdvancedSwitches();
        setupButtons();
        applyModeDefaults();
        updateSummary();
        refreshAbilityText();
    }

    private void bindViews() {
        modeSpinner = findViewById(R.id.native_record_mode);
        deviceSpinner = findViewById(R.id.native_device_id);
        optAsrSwitch = findViewById(R.id.native_opt_asr);
        optAmplitudeSwitch = findViewById(R.id.native_opt_amplitude);
        optTranslateSwitch = findViewById(R.id.native_opt_translate);
        optTtsSwitch = findViewById(R.id.native_opt_tts);
        sourceLangSpinner = findViewById(R.id.native_source_lang);
        targetLangSpinner = findViewById(R.id.native_target_lang);
        statusText = findViewById(R.id.native_status);
        durationText = findViewById(R.id.native_duration);
        summaryText = findViewById(R.id.native_summary);
        abilityText = findViewById(R.id.native_device_ability);
        waveView = findViewById(R.id.native_wave);
        realtimeText = findViewById(R.id.native_realtime_text);
        btnStart = findViewById(R.id.native_btn_start);
        btnPause = findViewById(R.id.native_btn_pause);
        btnResume = findViewById(R.id.native_btn_resume);
        btnStop = findViewById(R.id.native_btn_stop);
        btnSwitchChannel = findViewById(R.id.native_btn_switch_channel);
    }

    // ===================== 模式与参数 =====================

    /**
     * 录音模式与其默认参数组合。取自 AI 笔记小程序的 4 种模式。
     */
    private enum RecordModeOption {
        /** 现场录音：只录音不转写。 */
        LIVE(R.string.native_mode_live, RECORD_MODE_LIVE, false, false),
        /** 电话录音：{@code recordMode=0}，未在通话中开录会返回错误码 10061。 */
        PHONE(R.string.native_mode_phone, RECORD_MODE_PHONE, false, false),
        /** 实时转写：边录边出文字。 */
        REALTIME(R.string.native_mode_realtime, RECORD_MODE_LIVE, true, false),
        /** 同声传译：实时转写 + 翻译，可选 TTS 把译文播回耳机。 */
        SIMULTANEOUS(R.string.native_mode_simultaneous, RECORD_MODE_LIVE, true, true);

        final int labelRes;
        final int recordMode;
        final boolean needAsr;
        final boolean needTranslate;

        RecordModeOption(int labelRes, int recordMode, boolean needAsr, boolean needTranslate) {
            this.labelRes = labelRes;
            this.recordMode = recordMode;
            this.needAsr = needAsr;
            this.needTranslate = needTranslate;
        }

        /** @return 是否为电话录音模式，影响 Pro 耳机的音源取值 */
        boolean isCall() {
            return recordMode == RECORD_MODE_PHONE;
        }
    }

    private void setupModeSpinner() {
        RecordModeOption[] options = RecordModeOption.values();
        List<String> labels = new ArrayList<>();
        for (RecordModeOption option : options) {
            labels.add(getString(option.labelRes));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.item_spinner_native, labels);
        adapter.setDropDownViewResource(R.layout.item_spinner_native_dropdown);
        modeSpinner.setAdapter(adapter);
        modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                applyModeDefaults();
                updateSummary();
                appendLog(getString(R.string.native_log_mode_selected,
                        getString(currentMode().labelRes)));
                if (isRecordingOrPaused()) {
                    updateParams();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private RecordModeOption currentMode() {
        int pos = modeSpinner.getSelectedItemPosition();
        RecordModeOption[] options = RecordModeOption.values();
        return pos >= 0 && pos < options.length ? options[pos] : RecordModeOption.LIVE;
    }

    /**
     * 把当前模式的默认参数组合回写到「高级」开关上。
     * <p>
     * 期间置 {@link #applyingModeDefaults} 抑制开关的联动回调，避免重复触发 updateParams。
     */
    private void applyModeDefaults() {
        RecordModeOption mode = currentMode();
        applyingModeDefaults = true;
        optAsrSwitch.setChecked(mode.needAsr);
        // 振幅回调各模式一律默认开启，便于观察波形。
        // 小程序在实时转写 / 同声传译模式下关掉了它（needAmplitude=false）以省电，
        // 生产环境若不画波形，建议照此关闭。
        optAmplitudeSwitch.setChecked(true);
        optTranslateSwitch.setChecked(mode.needTranslate);
        optTtsSwitch.setChecked(false);
        // TTS 只在需要翻译时才有意义（把译文播回耳机）
        optTtsSwitch.setEnabled(mode.needTranslate);
        applyingModeDefaults = false;
    }

    private void setupLangSpinners() {
        ArrayAdapter<String> langAdapter = new ArrayAdapter<>(
                this, R.layout.item_spinner_native, LANGS);
        langAdapter.setDropDownViewResource(R.layout.item_spinner_native_dropdown);
        sourceLangSpinner.setAdapter(langAdapter);
        targetLangSpinner.setAdapter(langAdapter);
        sourceLangSpinner.setSelection(indexOf(LANGS, "zh"));
        targetLangSpinner.setSelection(indexOf(LANGS, "en"));

        AdapterView.OnItemSelectedListener langListener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateSummary();
                appendLog(getString(R.string.native_log_lang, sourceLang(), targetLang()));
                if (isRecordingOrPaused()) {
                    updateParams();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        sourceLangSpinner.setOnItemSelectedListener(langListener);
        targetLangSpinner.setOnItemSelectedListener(langListener);
    }

    private void setupAdvancedSwitches() {
        CompoundButton.OnCheckedChangeListener listener = (button, checked) -> {
            if (applyingModeDefaults) return;
            optTtsSwitch.setEnabled(optTranslateSwitch.isChecked());
            if (!optTranslateSwitch.isChecked()) {
                optTtsSwitch.setChecked(false);
            }
            updateSummary();
            if (isRecordingOrPaused()) {
                updateParams();
            }
        };
        optAsrSwitch.setOnCheckedChangeListener(listener);
        optAmplitudeSwitch.setOnCheckedChangeListener(listener);
        optTranslateSwitch.setOnCheckedChangeListener(listener);
        optTtsSwitch.setOnCheckedChangeListener(listener);
    }

    private void setupButtons() {
        btnStart.setOnClickListener(v -> startRecord());
        btnPause.setOnClickListener(v -> pause());
        btnResume.setOnClickListener(v -> resume());
        btnStop.setOnClickListener(v -> stopRecord());
        btnSwitchChannel.setOnClickListener(v -> switchChannel());
    }

    /**
     * 推导音源。<b>取错会直接录不出声。</b>
     * <p>
     * 规则取自小程序 {@code getAudioSource(设备类型, recordType, isCall)}：
     * 手机走系统 MIC；入门耳机与 OS 耳机走系统蓝牙；Pro 耳机按是否电话模式分现场 / 电话音源；
     * 录音卡片走卡片音源。
     *
     * @return audioSource 取值
     */
    private int resolveAudioSource() {
        DeviceItem device = currentDeviceItem();
        if (device == null || device.isPhone()) {
            return AUDIO_SOURCE_SYSTEM_MIC;
        }
        String type = device.productType;
        if (type == null) {
            return AUDIO_SOURCE_SYSTEM_MIC;
        }
        switch (type) {
            case DeviceItem.TYPE_PRO:
                return currentMode().isCall() ? AUDIO_SOURCE_PRO_CALL : AUDIO_SOURCE_PRO_LIVE;
            case DeviceItem.TYPE_CARD:
                return AUDIO_SOURCE_CARD;
            case DeviceItem.TYPE_ENTRY:
            case DeviceItem.TYPE_OS_BUDS:
            default:
                return AUDIO_SOURCE_SYSTEM_BT;
        }
    }

    /**
     * 构造录音参数。
     * <p>
     * 未在 UI 中暴露、AI 笔记小程序也未使用的三个字段，用法如下：
     * <ul>
     *     <li>{@code recordTransfer3AConfig} —— 降噪 / 自动增益 / 回声消除开关，
     *         默认由底层按设备能力决定，需要定制音频处理时才设置</li>
     *     <li>{@code autoRecognize} —— 是否自动识别语种，置 true 后 {@code originalLanguage} 可不传</li>
     *     <li>{@code startLivingStatus} —— 直播场景状态机（0 仅同传 / 1 开始直播 / 2 直播中变更）</li>
     * </ul>
     *
     * @param forStart true 为 {@code startAudioRecording} 入参，false 为 {@code updateParams} 入参
     * @return 参数对象
     */
    private RecordParamsV2 buildParams(boolean forStart) {
        RecordModeOption mode = currentMode();
        RecordParamsV2 params = new RecordParamsV2();
        params.setUpdateParams(!forStart);
        params.setBusinessType(BUSINESS_TYPE_NOTE);
        params.setRecordMode(mode.recordMode);

        int audioSource = resolveAudioSource();
        params.setAudioSource(audioSource);
        // 底层 RecordExecutorManager.getRecordType 读的是 audioSourceList[0]，不能为空
        List<Integer> sourceList = new ArrayList<>();
        sourceList.add(audioSource);
        params.setAudioSourceList(sourceList);

        params.setNeedAsr(optAsrSwitch.isChecked());
        params.setNeedAmplitude(optAmplitudeSwitch.isChecked());
        params.setNeedTranslate(optTranslateSwitch.isChecked());
        params.setNeedTts(optTtsSwitch.isChecked());

        if (params.getNeedAsr() || params.getNeedTranslate()) {
            params.setOriginalLanguage(sourceLang());
        }
        if (params.getNeedTranslate()) {
            params.setTargetLanguage(targetLang());
        }
        if (params.getNeedTts()) {
            params.setTtsConfig(buildTtsConfig(audioSource));
        }
        return params;
    }

    /**
     * 构造 TTS 输出配置：把译文合成语音后播回指定通道。
     * <p>
     * 三处非直觉的取值规则，均取自小程序：
     * <ul>
     *     <li>{@code devId} —— 输出到设备时才下发；手机 / 入门耳机 / 卡片走系统通道，必须传空</li>
     *     <li>{@code output} —— 由音源反推：系统 MIC 音源配 MIC 输出，系统蓝牙音源配 BT 输出，
     *         Pro / 卡片等设备音源配设备输出</li>
     *     <li>{@code encode} —— Pro 耳机用 opus_silk，其余用默认；opus_celt 为特定固件才支持</li>
     * </ul>
     *
     * @param audioSource 当前音源
     * @return TTS 配置
     */
    private TTSConfig buildTtsConfig(int audioSource) {
        DeviceItem device = currentDeviceItem();
        boolean isPro = device != null && DeviceItem.TYPE_PRO.equals(device.productType);

        TTSOutput output;
        if (audioSource == AUDIO_SOURCE_SYSTEM_MIC) {
            output = TTSOutput.SYSTEM_MIC;
        } else if (audioSource == AUDIO_SOURCE_SYSTEM_BT) {
            output = TTSOutput.SYSTEM_BLUETOOTH;
        } else {
            output = TTSOutput.DEVICE;
        }

        // 走系统通道时 devId 必须传空，否则底层会尝试往设备写流
        String devId = output == TTSOutput.DEVICE ? currentDeviceId() : "";
        TTSEncode encode = isPro ? TTSEncode.OPUS_SILK : TTSEncode.DEFAULT;

        return new TTSConfig(devId, output, encode, TTSOutputChannel.DEFAULT);
    }

    // ===================== 事件监听 =====================

    @Override
    protected void registerListeners() {
        recordListener = new IRecordListener() {
            @Override
            public void onRecordStatusUpdate(@NonNull String deviceId, @Nullable RecordStatusBean bean) {
                if (bean == null) return;
                runOnUi(() -> onStatusUpdate(bean));
            }

            @Override
            public void onRecordAmplitudeUpdate(@NonNull String deviceId, int channel, double amplitude) {
                // 高频回调，View 内部对 invalidate 做了节流，无需切主线程
                waveView.push(normalizeAmp(amplitude));
            }

            @Override
            public void onRealTimeStatusUpdate(@NonNull RealTimeTransferStatus s) {
                runOnUi(() -> appendRealtime(s));
            }

            @Override
            public void onRecordSwitchAudioSourceEvent(@NonNull String devId, int recordType, int audioSource) {
                runOnUi(() -> appendLog(getString(
                        R.string.native_log_audio_source_switch, recordType, audioSource)));
            }

            @Override
            public void onRecordFinish(@NonNull String deviceId) {
                runOnUi(() -> onRecordFinished(true, 0, null));
            }

            @Override
            public void onRecordErrorFinish(@NonNull String deviceId, int errorCode, @NonNull String errorMsg) {
                runOnUi(() -> onRecordFinished(false, errorCode, errorMsg));
            }
        };
        manager.addRecordListener(recordListener);

        abilityListener = new INativeAbilityListener() {
            @Override
            public void onBTConnectChange(BTConnectedStatus status) {
                runOnUi(() -> {
                    btConnectedStatus = status == null ? -1 : status.getConnectedStatus();
                    appendLog(getString(R.string.native_log_bt_change, btConnectedStatus));
                    refreshAbilityText();
                });
            }

            @Override
            public void onPhoneBatteryChange(PhoneBatteryInfo info) {
                runOnUi(() -> {
                    batteryDesc = info == null || !info.getNeedShow()
                            ? "-" : String.format(Locale.getDefault(), "%.0f%%", info.getBatteryValue());
                    refreshAbilityText();
                });
            }

            @Override
            public void onRecordQualityChange(RecordQualityInfo info) {
                runOnUi(() -> {
                    qualityDesc = info == null || !info.getNeedShow()
                            ? "-" : String.format(Locale.getDefault(), "%.1f dB", info.getSnrValue());
                    refreshAbilityText();
                });
            }
        };
        manager.addNativeAbilityListener(abilityListener);
        appendLog("addRecordListener + addNativeAbilityListener");

        restoreExistingTask();
        queryBtStatus();
    }

    @Override
    protected void unregisterListeners() {
        if (recordListener != null) {
            manager.removeRecordListener(recordListener);
            recordListener = null;
        }
        if (abilityListener != null) {
            manager.removeNativeAbilityListener(abilityListener);
            abilityListener = null;
        }
    }

    // ===================== 模块 7 · 设备能力 =====================

    /**
     * 拉取耳机蓝牙连接状态初值。后续变化由 {@link INativeAbilityListener} 推送。
     * PHONE 走手机麦克风，无蓝牙概念，跳过。
     */
    private void queryBtStatus() {
        String deviceId = currentDeviceId();
        if (DEVICE_ID_PHONE.equals(deviceId)) {
            btConnectedStatus = -1;
            refreshAbilityText();
            return;
        }
        appendLog("getEarPhoneBTConntectedStatus(" + deviceId + ")");
        manager.getEarPhoneBTConntectedStatus(deviceId, new IRecordCallBack<BTConnectedStatus>() {
            @Override
            public void onSuccess(BTConnectedStatus result) {
                runOnUi(() -> {
                    btConnectedStatus = result == null ? -1 : result.getConnectedStatus();
                    appendLog(getString(R.string.native_log_bt_query_ok, btConnectedStatus));
                    refreshAbilityText();
                });
            }

            @Override
            public void onError(String code, String error) {
                runOnUi(() -> appendLog(getString(R.string.native_log_bt_query_fail, code, error)));
            }
        });
    }

    private void refreshAbilityText() {
        abilityText.setText(getString(R.string.native_ability_format,
                btStatusName(), batteryDesc, qualityDesc));
    }

    private String btStatusName() {
        if (btConnectedStatus == BT_CONNECTED) return getString(R.string.native_bt_connected);
        if (btConnectedStatus == 0) return getString(R.string.native_bt_disconnected);
        return getString(R.string.native_bt_unknown);
    }

    // ===================== 恢复进行中的任务 =====================

    /**
     * 恢复进行中的录音任务。
     * <p>
     * {@code recordTransferTask} 同步返回当前任务，无任务时返回 null。
     * 除了状态，<b>还要把已转写的历史句子补回来</b>——小程序在页面重进 / 从快捷方式进入时
     * 也会调 {@code getRecordTransferRealTimeResult({recordId})} 回填，否则界面上是空白的。
     */
    private void restoreExistingTask() {
        String deviceId = currentDeviceId();
        try {
            RecordStatusBean bean = manager.recordTransferTask(deviceId);
            appendLog("recordTransferTask(" + deviceId + ") -> "
                    + (bean == null ? "null" : "status=" + bean.getStatus()));
            if (bean == null) return;
            onStatusUpdate(bean);
            if (bean.getStatus() == RecordStatusDef.RECORDING
                    || bean.getStatus() == RecordStatusDef.PAUSING) {
                restoreRealtimeSentences(bean.getRecordId());
            }
        } catch (Exception e) {
            appendLog(getString(R.string.native_log_record_task_exception, e.getMessage()));
        }
    }

    /**
     * 补拉已转写的实时句子，回填到界面。
     *
     * @param recordId 实时转写记录 ID，来自 {@code RecordStatusBean.recordId}
     */
    private void restoreRealtimeSentences(@Nullable String recordId) {
        if (TextUtils.isEmpty(recordId)) return;
        appendLog(getString(R.string.native_log_restore_realtime, recordId));
        manager.getRecordTransferRealTimeResult(null, recordId, null,
                new IRecordCallBack<List<RecordTransferRealTimeResult>>() {
                    @Override
                    public void onSuccess(List<RecordTransferRealTimeResult> result) {
                        runOnUi(() -> {
                            if (result == null || result.isEmpty()) return;
                            finishedSentences.clear();
                            for (RecordTransferRealTimeResult item : result) {
                                Sentence s = new Sentence();
                                s.asr = item.text == null ? item.asr : item.text;
                                s.translate = item.translate;
                                finishedSentences.add(s);
                            }
                            renderRealtime();
                            appendLog(getString(R.string.native_log_restore_realtime_ok, result.size()));
                        });
                    }

                    @Override
                    public void onError(String code, String error) {
                        runOnUi(() -> appendLog(getString(
                                R.string.native_log_restore_realtime_fail, code, error)));
                    }
                });
    }

    // ===================== 录音控制 =====================

    /**
     * 开始录音。依次做四重校验：操作锁 → 录音权限 → 设备可用性（在线 + BT）→ 参数完整性。
     */
    private void startRecord() {
        if (controlLocked) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
                toast(getString(R.string.native_toast_record_permission_rationale));
            }
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_CODE_RECORD_AUDIO);
            return;
        }
        if (status == RecordStatusDef.RECORDING) {
            toast(getString(R.string.native_toast_already_recording));
            return;
        }
        DeviceItem device = currentDeviceItem();
        // 设备离线时开录必然失败，先拦一道
        if (device != null && !device.isPhone() && !device.online) {
            toast(getString(R.string.native_toast_device_offline));
            return;
        }
        // 模块 7 的前置校验：选了真实耳机但蓝牙未连接，开录必然失败
        if (device != null && !device.isPhone() && btConnectedStatus == 0) {
            toast(getString(R.string.native_toast_bt_disconnected));
            return;
        }
        RecordParamsV2 params = buildParams(true);
        if ((params.getNeedAsr() || params.getNeedTranslate())
                && TextUtils.isEmpty(params.getOriginalLanguage())) {
            toast(getString(R.string.native_toast_need_source_lang));
            return;
        }
        if (params.getNeedTranslate() && TextUtils.isEmpty(params.getTargetLanguage())) {
            toast(getString(R.string.native_toast_need_target_lang));
            return;
        }

        String deviceId = currentDeviceId();
        lockControl();
        appendLog("startAudioRecording(" + deviceId + ") params=" + describe(params));
        manager.startAudioRecording(deviceId, params, new IResultCallback() {
            @Override
            public void onSuccess() {
                runOnUi(() -> {
                    appendLog("startAudioRecording onSuccess");
                    status = RecordStatusDef.RECORDING;
                    statusText.setText(R.string.native_status_1);
                    statusText.setTextColor(getResources().getColor(
                            R.color.ai_voice_status_recording, getTheme()));
                    waveView.reset();
                    resetRealtime();
                    resetDuration();
                    startTicking();
                    refreshButtonState();
                });
            }

            @Override
            public void onError(String code, String error) {
                runOnUi(() -> onControlError("startAudioRecording", code, error));
            }
        });
    }

    private void pause() {
        if (controlLocked) return;
        String deviceId = currentDeviceId();
        lockControl();
        appendLog("pauseRecordTransfer(" + deviceId + ")");
        manager.pauseRecordTransfer(deviceId, new IResultCallback() {
            @Override
            public void onSuccess() {
                runOnUi(() -> {
                    appendLog("pause onSuccess");
                    status = RecordStatusDef.PAUSING;
                    statusText.setText(R.string.native_status_2);
                    stopTicking();
                    refreshButtonState();
                });
            }

            @Override
            public void onError(String code, String error) {
                runOnUi(() -> onControlError("pauseRecordTransfer", code, error));
            }
        });
    }

    private void resume() {
        if (controlLocked) return;
        String deviceId = currentDeviceId();
        lockControl();
        appendLog("resumeRecordTransfer(" + deviceId + ")");
        manager.resumeRecordTransfer(deviceId, new IResultCallback() {
            @Override
            public void onSuccess() {
                runOnUi(() -> {
                    appendLog("resume onSuccess");
                    status = RecordStatusDef.RECORDING;
                    statusText.setText(R.string.native_status_1);
                    startTicking();
                    refreshButtonState();
                });
            }

            @Override
            public void onError(String code, String error) {
                runOnUi(() -> onControlError("resumeRecordTransfer", code, error));
            }
        });
    }

    /** 停止录音。仅在录音中 / 暂停中允许，与小程序的前置判断一致。 */
    private void stopRecord() {
        if (controlLocked || !isRecordingOrPaused()) return;
        String deviceId = currentDeviceId();
        lockControl();
        appendLog("stopRecordTransfer(" + deviceId + ")");
        manager.stopRecordTransfer(deviceId, new IResultCallback() {
            @Override
            public void onSuccess() {
                // 真正结束以 onRecordFinish 事件为准，这里只刷新按钮态
                runOnUi(NativeRecordActivity.this::refreshButtonState);
            }

            @Override
            public void onError(String code, String error) {
                runOnUi(() -> onControlError("stopRecordTransfer", code, error));
            }
        });
    }

    /** 上锁并在 {@link #CONTROL_LOCK_MS} 后自动释放，避免连点重复下发。 */
    private void lockControl() {
        controlLocked = true;
        refreshButtonState();
        main.postDelayed(() -> {
            controlLocked = false;
            refreshButtonState();
        }, CONTROL_LOCK_MS);
    }

    /**
     * 控制类接口失败的统一处理：按错误码给可读提示，并立即释放操作锁。
     *
     * @param action 动作名，用于日志区分
     * @param code   错误码
     * @param error  底层错误描述
     */
    private void onControlError(String action, String code, String error) {
        controlLocked = false;
        refreshButtonState();
        appendLog(action + " onError " + code + " " + error);
        toast(RecordErrorCode.messageOf(this, code, currentMode().isCall()));
    }

    /**
     * 录音中动态更新参数，无需 stop / restart。
     * <p>
     * {@code onSuccess} 立即返回，<b>不代表参数已生效</b>，真正生效以
     * {@code onRecordStatusUpdate} 推送的状态为准。
     */
    private void updateParams() {
        String deviceId = currentDeviceId();
        RecordParamsV2 params = buildParams(false);
        appendLog("updateParams(" + deviceId + ") params=" + describe(params));
        manager.updateParams(deviceId, params, new IResultCallback() {
            @Override
            public void onSuccess() {
                runOnUi(() -> appendLog(getString(R.string.native_log_update_params_success)));
            }

            @Override
            public void onError(String code, String error) {
                runOnUi(() -> appendLog("updateParams onError " + code + " " + error));
            }
        });
    }

    /** 切换收音通道：0 未指定 / 1 BT / 2 Micro，循环切换。 */
    private void switchChannel() {
        String deviceId = currentDeviceId();
        currentChannel = (currentChannel + 1) % CHANNEL_COUNT;
        appendLog("switchRecordChannel(" + deviceId + ", " + currentChannel + ")");
        manager.switchRecordChannel(deviceId, currentChannel, new IResultCallback() {
            @Override
            public void onSuccess() {
                runOnUi(() -> appendLog("switchChannel onSuccess -> " + channelName(currentChannel)));
            }

            @Override
            public void onError(String code, String error) {
                runOnUi(() -> appendLog("switchChannel onError " + code + " " + error));
            }
        });
    }

    // ===================== 状态渲染 =====================

    private void onStatusUpdate(RecordStatusBean bean) {
        status = bean.getStatus();
        statusText.setText(statusName(bean.getStatus()));
        statusText.setTextColor(getResources().getColor(
                status == RecordStatusDef.RECORDING
                        ? R.color.ai_voice_status_recording
                        : R.color.ai_voice_text_primary, getTheme()));
        // 事件带的时长是权威值，用它校准本地计时
        if (bean.getDuration() > 0) {
            recordedMs = bean.getDuration();
            durationText.setText(formatDuration(recordedMs));
        }
        if (status == RecordStatusDef.RECORDING) {
            startTicking();
        } else {
            stopTicking();
        }
        refreshButtonState();
    }

    // ===================== 录音时长计时 =====================

    /**
     * 每秒自增一次录音时长。
     * <p>
     * 状态事件只在状态变化时到达，仅靠它会让计时停在开始那一刻，因此本地自增，
     * 由 {@link #onStatusUpdate} 用事件里的 {@code duration} 校准，避免长时间累积漂移。
     */
    private final Runnable durationTicker = new Runnable() {
        @Override
        public void run() {
            recordedMs += DURATION_TICK_MS;
            durationText.setText(formatDuration(recordedMs));
            main.postDelayed(this, DURATION_TICK_MS);
        }
    };

    private void startTicking() {
        main.removeCallbacks(durationTicker);
        main.postDelayed(durationTicker, DURATION_TICK_MS);
    }

    private void stopTicking() {
        main.removeCallbacks(durationTicker);
    }

    /** 重置计时并刷新显示，用于开始新录音与录音结束。 */
    private void resetDuration() {
        stopTicking();
        recordedMs = 0L;
        durationText.setText(R.string.native_duration_zero);
    }

    private void onRecordFinished(boolean success, int code, String msg) {
        appendLog(success ? getString(R.string.native_log_record_finish)
                : getString(R.string.native_log_record_error_finish, code, msg));
        status = RecordStatusDef.STOP;
        statusText.setText(R.string.native_status_3);
        statusText.setTextColor(getResources().getColor(
                R.color.ai_voice_text_primary, getTheme()));
        resetDuration();
        waveView.reset();
        resetRealtime();
        refreshButtonState();
        if (!success) {
            // 异常结束同样按错误码给可读提示
            toast(RecordErrorCode.messageOf(this, String.valueOf(code), currentMode().isCall()));
        }
    }

    private void refreshButtonState() {
        boolean recording = status == RecordStatusDef.RECORDING;
        boolean paused = status == RecordStatusDef.PAUSING;
        boolean idle = !controlLocked;
        btnStart.setEnabled(idle && !recording && !paused);
        btnPause.setEnabled(idle && recording);
        btnResume.setEnabled(idle && paused);
        btnStop.setEnabled(idle && (recording || paused));
        btnSwitchChannel.setEnabled(idle && (recording || paused));
        // 录音中不允许改模式与设备，避免参数与底层状态不一致
        modeSpinner.setEnabled(!recording && !paused);
        deviceSpinner.setEnabled(!recording && !paused);
    }

    private boolean isRecordingOrPaused() {
        return status == RecordStatusDef.RECORDING || status == RecordStatusDef.PAUSING;
    }

    // ===================== 实时转写渲染 =====================

    /**
     * 一句实时转写。原文与译文分两次事件到达，需就地合并。
     */
    private static class Sentence {
        String asr = "";
        String translate = "";
    }

    private final List<Sentence> finishedSentences = new ArrayList<>();
    private Sentence currentSentence = null;

    /**
     * 处理实时转写事件。
     * <p>
     * 事件模型：同一句的 {@code text} / {@code translateText} 是<b>累积文案</b>而非增量，
     * 因此进行中直接覆盖当前句；{@code phase=ASR 且 status=结束} 时把当前句定稿。
     * 译文（{@code phase=TEXT}）可能晚于定稿到达，因此要写进「最后一句」而不是「当前句」。
     *
     * @param s 实时转写事件
     */
    private void appendRealtime(RealTimeTransferStatus s) {
        if (s == null) return;
        int phase = s.phase == null ? -1 : s.phase;
        int phaseStatus = s.status == null ? -1 : s.status;

        if (phase == PHASE_ASR) {
            if (!TextUtils.isEmpty(s.text)) {
                ensureCurrent().asr = s.text;
            }
            if (phaseStatus == PHASE_STATUS_FINISH && currentSentence != null) {
                finishedSentences.add(currentSentence);
                currentSentence = null;
            }
        } else if (phase == PHASE_TEXT) {
            if (!TextUtils.isEmpty(s.translateText)) {
                lastSentence().translate = s.translateText;
            }
        }
        renderRealtime();
    }

    private Sentence ensureCurrent() {
        if (currentSentence == null) {
            currentSentence = new Sentence();
        }
        return currentSentence;
    }

    private Sentence lastSentence() {
        if (currentSentence != null) {
            return currentSentence;
        }
        if (!finishedSentences.isEmpty()) {
            return finishedSentences.get(finishedSentences.size() - 1);
        }
        return ensureCurrent();
    }

    private void renderRealtime() {
        StringBuilder sb = new StringBuilder();
        for (Sentence st : finishedSentences) {
            appendSentence(sb, st);
        }
        if (currentSentence != null) {
            appendSentence(sb, currentSentence);
        }
        realtimeText.setText(sb.toString());
    }

    private void appendSentence(StringBuilder sb, Sentence st) {
        if (!TextUtils.isEmpty(st.asr)) {
            sb.append(st.asr).append('\n');
        }
        if (!TextUtils.isEmpty(st.translate)) {
            sb.append("  ➜ ").append(st.translate).append('\n');
        }
        sb.append('\n');
    }

    private void resetRealtime() {
        finishedSentences.clear();
        currentSentence = null;
        realtimeText.setText("");
    }

    // ===================== 辅助 =====================

    @Override
    protected void onDeviceSelected(@NonNull DeviceItem item) {
        super.onDeviceSelected(item);
        updateSummary();
        queryBtStatus();
    }

    /** 刷新参数摘要，让模式 / 设备 / 音源的推导结果在界面上直接可见。 */
    private void updateSummary() {
        if (summaryText == null) return;
        summaryText.setText(getString(R.string.native_summary_format,
                currentDeviceName(),
                getString(currentMode().labelRes),
                resolveAudioSource(),
                sourceLang(),
                targetLang()));
    }

    private String describe(RecordParamsV2 p) {
        List<String> parts = new ArrayList<>();
        parts.add("dev=" + currentDeviceName());
        parts.add("mode=" + p.getRecordMode());
        parts.add("source=" + p.getAudioSource());
        parts.add("sources=" + p.getAudioSourceList());
        parts.add("asr=" + p.getNeedAsr());
        parts.add("amp=" + p.getNeedAmplitude());
        parts.add("translate=" + p.getNeedTranslate());
        parts.add("tts=" + p.getNeedTts());
        if (p.getOriginalLanguage() != null) parts.add("src=" + p.getOriginalLanguage());
        if (p.getTargetLanguage() != null) parts.add("tgt=" + p.getTargetLanguage());
        parts.add("biz=" + p.getBusinessType());
        return TextUtils.join(", ", parts);
    }

    private String sourceLang() {
        Object v = sourceLangSpinner == null ? null : sourceLangSpinner.getSelectedItem();
        return v == null ? LANGS[0] : v.toString();
    }

    private String targetLang() {
        Object v = targetLangSpinner == null ? null : targetLangSpinner.getSelectedItem();
        return v == null ? LANGS[1] : v.toString();
    }

    private String statusName(int s) {
        switch (s) {
            case 0: return getString(R.string.native_status_0);
            case 1: return getString(R.string.native_status_1);
            case 2: return getString(R.string.native_status_2);
            case 3: return getString(R.string.native_status_3);
            default: return getString(R.string.native_status_unknown);
        }
    }

    private String channelName(int ch) {
        switch (ch) {
            case 1: return getString(R.string.native_channel_bt);
            case 2: return getString(R.string.native_channel_micro);
            default: return getString(R.string.native_channel_unspecified);
        }
    }

    /**
     * 把 SDK 的振幅值映射到便于观察的区间。
     * <p>
     * 底层已把 PCM 16bit 归一化到 0~1，但 RMS 模式下正常说话通常只有 0.02~0.15，
     * 线性显示几乎看不出波形，故用幂函数做非线性拉伸。
     *
     * @param amp 原始振幅
     * @return 0~1 的展示值
     */
    private double normalizeAmp(double amp) {
        double v = Math.pow(Math.abs(amp), 0.4);
        return Math.min(1d, Math.max(0d, v));
    }

    private static String formatDuration(long ms) {
        long totalSec = ms / 1000;
        return String.format(Locale.getDefault(), "%02d:%02d", totalSec / 60, totalSec % 60);
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(v)) return i;
        }
        return 0;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_CODE_RECORD_AUDIO) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            appendLog("RECORD_AUDIO granted");
            startRecord();
        } else {
            if (!ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.RECORD_AUDIO)) {
                toast(getString(R.string.native_toast_record_permission_never_ask));
            } else {
                toast(getString(R.string.native_toast_record_permission_denied));
            }
            appendLog("RECORD_AUDIO denied");
        }
    }
}
