package com.tuya.smart.ai_voice.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.thingclips.smart.api.service.MicroServiceManager;
import com.thingclips.smart.commonbiz.bizbundle.family.api.AbsBizBundleFamilyService;
import com.thingclips.smart.earphone.enhance.api.bean.RealTimeTransferStatus;
import com.thingclips.smart.earphone.enhance.api.bean.RecordParamsV2;
import com.thingclips.smart.earphone.enhance.api.bean.RecordStatusBean;
import com.thingclips.smart.earphone.enhance.api.bean.def.RecordStatusDef;
import com.thingclips.smart.earphone.enhance.api.listener.IRecordListener;
import com.thingclips.smart.home.sdk.ThingHomeSdk;
import com.thingclips.smart.plugin.tuniaudiodetectmanager.ThingAudioDetectManagerNative;
import com.thingclips.smart.sdk.api.IResultCallback;
import com.thingclips.smart.sdk.bean.DeviceBean;
import com.tuya.smart.ai_voice.R;
import com.tuya.smart.ai_voice.ui.widget.AmplitudeWaveView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Native 录音实时流演示页。
 * <p>
 * 基于单例 {@link ThingAudioDetectManagerNative}，串联完整的录音流程：
 * <ul>
 *     <li>注册/注销 {@link IRecordListener}（add/remove 传同一实例）</li>
 *     <li>进页 {@link #checkExistingTask()} 恢复进行中任务</li>
 *     <li>{@link #startRecord()} → {@link #pause()} / {@link #resume()} /
 *         {@link #stopRecord()} 完整生命周期</li>
 *     <li>{@link #updateParams()} 录音中动态更新参数（无需 stop/restart）</li>
 *     <li>{@link #switchChannel()} 切换收音通道</li>
 * </ul>
 * 所有 SDK 回调均在子线程，更新 UI 一律切回主线程。
 */
public class NativeRecordActivity extends AppCompatActivity {

    private static final String DEFAULT_DEVICE_ID = "PHONE";
    private static final String[] LANGS = {"zh", "en", "ja", "ko", "fr", "es"};

    private final ThingAudioDetectManagerNative manager =
            ThingAudioDetectManagerNative.getInstance();
    private final Handler main = new Handler(Looper.getMainLooper());

    /** 监听器用字段持有，remove 时传同一引用。 */
    private IRecordListener recordListener;

    // ===== 当前配置态（构造 start 参数用） =====
    private boolean optAsr = true;
    private boolean optAmplitude = true;
    private boolean optTranslate = false;
    private String sourceLang = "zh";
    private String targetLang = "en";

    // ===== 当前录音态（UI 启用/禁用用） =====
    private int status = RecordStatusDef.IDLE; // 0 idle / 1 recording / 2 paused / 3 stopped
    private int currentChannel = 0; // switchRecordChannel 循环 0/1/2

    private Spinner deviceSpinner;
    /** 设备列表：index 0 固定为 PHONE，其余为过滤后的家庭设备。 */
    private final List<DeviceItem> deviceItems = new ArrayList<>();
    private CheckBox optAsrSwitch;
    private CheckBox optAmplitudeSwitch;
    private CheckBox optTranslateSwitch;
    private Spinner sourceLangSpinner;
    private Spinner targetLangSpinner;
    private TextView statusText;
    private TextView durationText;
    private TextView summaryText;
    private AmplitudeWaveView waveView;
    private TextView realtimeText;
    private TextView logText;
    private ScrollView logScroll;
    private View btnStart, btnPause, btnResume, btnStop, btnSwitchChannel;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_native_record);
        bindViews();
        setupConfigViews();
        loadDevices();
        setupButtons();
        updateSummary();
        registerRecordListener();  // ①
        checkExistingTask();        // ②
    }

    private void bindViews() {
        deviceSpinner = findViewById(R.id.native_device_id);
        optAsrSwitch = findViewById(R.id.native_opt_asr);
        optAmplitudeSwitch = findViewById(R.id.native_opt_amplitude);
        optTranslateSwitch = findViewById(R.id.native_opt_translate);
        sourceLangSpinner = findViewById(R.id.native_source_lang);
        targetLangSpinner = findViewById(R.id.native_target_lang);
        statusText = findViewById(R.id.native_status);
        durationText = findViewById(R.id.native_duration);
        summaryText = findViewById(R.id.native_summary);
        waveView = findViewById(R.id.native_wave);
        realtimeText = findViewById(R.id.native_realtime_text);
        logText = findViewById(R.id.native_log_text);
        logScroll = findViewById(R.id.native_log_scroll);
        btnStart = findViewById(R.id.native_btn_start);
        btnPause = findViewById(R.id.native_btn_pause);
        btnResume = findViewById(R.id.native_btn_resume);
        btnStop = findViewById(R.id.native_btn_stop);
        btnSwitchChannel = findViewById(R.id.native_btn_switch_channel);

        findViewById(R.id.native_back).setOnClickListener(v -> finish());
    }

    private void setupConfigViews() {
        ArrayAdapter<String> langAdapter = new ArrayAdapter<>(
                this, R.layout.item_spinner_native, LANGS);
        langAdapter.setDropDownViewResource(R.layout.item_spinner_native_dropdown);
        sourceLangSpinner.setAdapter(langAdapter);
        targetLangSpinner.setAdapter(langAdapter);
        sourceLangSpinner.setSelection(indexOf(LANGS, sourceLang));
        targetLangSpinner.setSelection(indexOf(LANGS, targetLang));

        // 选择语言后刷新摘要 + 日志，录音中变更触发 updateParams（值以 Spinner 为准，buildParams 直接读）
        AdapterView.OnItemSelectedListener langListener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                readLangs();
                updateSummary();
                appendLog(getString(R.string.native_log_lang, sourceLang, targetLang));
                if (status == RecordStatusDef.RECORDING || status == RecordStatusDef.PAUSING) {
                    updateParams();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        sourceLangSpinner.setOnItemSelectedListener(langListener);
        targetLangSpinner.setOnItemSelectedListener(langListener);

        // 开关变更：录音中变更会触发 updateParams
        CompoundButton.OnCheckedChangeListener listener = (button, checked) -> {
            optAsr = optAsrSwitch.isChecked();
            optAmplitude = optAmplitudeSwitch.isChecked();
            optTranslate = optTranslateSwitch.isChecked();
            if (status == RecordStatusDef.RECORDING
                    || status == RecordStatusDef.PAUSING) {
                updateParams();
            }
        };
        optAsrSwitch.setOnCheckedChangeListener(listener);
        optAmplitudeSwitch.setOnCheckedChangeListener(listener);
        optTranslateSwitch.setOnCheckedChangeListener(listener);
    }

    private void setupButtons() {
        btnStart.setOnClickListener(v -> startRecord());
        btnPause.setOnClickListener(v -> pause());
        btnResume.setOnClickListener(v -> resume());
        btnStop.setOnClickListener(v -> stopRecord());
        btnSwitchChannel.setOnClickListener(v -> switchChannel());
    }

    // ===================== 监听器 =====================

    private void registerRecordListener() {
        recordListener = new IRecordListener() {
            @Override
            public void onRecordStatusUpdate(@NonNull String deviceId, @Nullable RecordStatusBean bean) {
                if (bean == null) return;
                main.post(() -> onStatusUpdate(bean));
            }

            @Override
            public void onRecordAmplitudeUpdate(@NonNull String deviceId, int channel, double amplitude) {
                // 高频回调，View 内部对 invalidate 节流
                waveView.push(normalizeAmp(amplitude));
            }

            @Override
            public void onRealTimeStatusUpdate(@NonNull RealTimeTransferStatus s) {
                main.post(() -> appendRealtime(s));
            }

            @Override
            public void onRecordSwitchAudioSourceEvent(@NonNull String devId, int recordType, int audioSource) {
                main.post(() -> appendLog(getString(R.string.native_log_audio_source_switch, recordType, audioSource)));
            }

            @Override
            public void onRecordFinish(@NonNull String deviceId) {
                main.post(() -> onRecordFinished(true, 0, null));
            }

            @Override
            public void onRecordErrorFinish(@NonNull String deviceId, int errorCode, @NonNull String errorMsg) {
                main.post(() -> onRecordFinished(false, errorCode, errorMsg));
            }
        };
        manager.addRecordListener(recordListener);
        appendLog("addRecordListener");
    }

    private void onStatusUpdate(RecordStatusBean bean) {
        status = bean.getStatus();
        statusText.setText(statusText(bean.getStatus()));
        statusText.setTextColor(getResources().getColor(
                status == RecordStatusDef.RECORDING
                        ? R.color.ai_voice_status_recording
                        : R.color.ai_voice_text_primary, getTheme()));
        if (bean.getDuration() > 0) {
            durationText.setText(formatDuration(bean.getDuration()));
        }
        refreshButtonState();
    }

    private void onRecordFinished(boolean success, int code, String msg) {
        appendLog(success ? getString(R.string.native_log_record_finish)
                : getString(R.string.native_log_record_error_finish, code, msg));
        status = RecordStatusDef.STOP;
        statusText.setText(R.string.native_status_3);
        statusText.setTextColor(getResources().getColor(
                R.color.ai_voice_text_primary, getTheme()));
        durationText.setText("00:00");
        waveView.reset();
        resetRealtime();
        refreshButtonState();
        if (!success) {
            toast(getString(R.string.native_record_error_toast, msg));
        }
    }

    // ---- 实时转写渲染 ----
    // SDK 事件模型：同一录音片段 managerId/asrId 固定，text/translateText 为累积文案。
    // phase: TASK=0 / ASR=4 / TEXT=5；status: 0 未开始 / 1 进行中 / 2 结束 / 3 取消。
    // 已结束句固定到 finished 列表，当前句用 currentSentence 实时覆盖。
    private static final int PHASE_TASK = 0;
    private static final int PHASE_ASR = 4;
    private static final int PHASE_TEXT = 5;
    private static final int STATUS_FINISH = 2;

    private static class Sentence {
        String asr = "";
        String translate = "";
    }

    private final List<Sentence> finishedSentences = new ArrayList<>();
    private Sentence currentSentence = null;

    private void appendRealtime(RealTimeTransferStatus s) {
        if (s == null) return;
        int phase = s.phase == null ? -1 : s.phase;
        int status = s.status == null ? -1 : s.status;

        if (phase == PHASE_ASR) {
            // 原文：进行中覆盖当前句，结束时锁定当前句
            if (!TextUtils.isEmpty(s.text)) {
                ensureCurrent().asr = s.text;
            }
            if (status == STATUS_FINISH && currentSentence != null) {
                finishedSentences.add(currentSentence);
                currentSentence = null;
            }
        } else if (phase == PHASE_TEXT) {
            // 译文：写进最后一句（可能已完成，也可能就是当前句）
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
            sb.append(st.asr).append("\n");
        }
        if (!TextUtils.isEmpty(st.translate)) {
            sb.append("  ➜ ").append(st.translate).append("\n");
        }
        sb.append("\n");
    }

    private void resetRealtime() {
        finishedSentences.clear();
        currentSentence = null;
        realtimeText.setText("");
    }

    // ===================== 恢复检查 =====================

    private void checkExistingTask() {
        String deviceId = currentDeviceId();
        try {
            RecordStatusBean bean = manager.recordTransferTask(deviceId);
            appendLog("recordTransferTask(" + deviceId + ") -> " + (bean == null ? "null" : "status=" + bean.getStatus()));
            if (bean != null && bean.getStatus() == RecordStatusDef.RECORDING) {
                onStatusUpdate(bean);
            }
        } catch (Exception e) {
            appendLog(getString(R.string.native_log_record_task_exception, e.getMessage()));
        }
    }

    // ===================== 录音控制 =====================

    private void startRecord() {
        // ① 参数校验
        String deviceId = currentDeviceId();
        if (TextUtils.isEmpty(deviceId)) {
            toast(getString(R.string.native_toast_select_device));
            return;
        }
        if (status == RecordStatusDef.RECORDING) {
            toast(getString(R.string.native_toast_already_recording));
            return;
        }
        RecordParamsV2 params = buildParams(true);
        if (params.getRecordMode() == null || params.getAudioSourceList() == null
                || params.getAudioSourceList().isEmpty()) {
            toast(getString(R.string.native_toast_param_incomplete));
            return;
        }
        if ((optAsr || optTranslate)
                && TextUtils.isEmpty(params.getOriginalLanguage())) {
            toast(getString(R.string.native_toast_need_source_lang));
            return;
        }
        if (params.getNeedTranslate() && TextUtils.isEmpty(params.getTargetLanguage())) {
            toast(getString(R.string.native_toast_need_target_lang));
            return;
        }
        appendLog("startAudioRecording(" + deviceId + ") params=" + describe(params));
        manager.startAudioRecording(deviceId, params, new IResultCallback() {
            @Override
            public void onSuccess() {
                main.post(() -> {
                    appendLog("startAudioRecording onSuccess");
                    status = RecordStatusDef.RECORDING;
                    statusText.setText(R.string.native_status_1);
                    statusText.setTextColor(getResources().getColor(
                            R.color.ai_voice_status_recording, getTheme()));
                    waveView.reset();
                    resetRealtime();
                    refreshButtonState();
                });
            }

            @Override
            public void onError(String code, String error) {
                main.post(() -> {
                    appendLog("startAudioRecording onError " + code + " " + error);
                    toast(getString(R.string.native_log_start_fail, error));
                });
            }
        });
    }

    private void pause() {
        String deviceId = currentDeviceId();
        appendLog("pauseRecordTransfer(" + deviceId + ")");
        manager.pauseRecordTransfer(deviceId, new IResultCallback() {
            @Override
            public void onSuccess() {
                main.post(() -> {
                    appendLog("pause onSuccess");
                    status = RecordStatusDef.PAUSING;
                    statusText.setText(R.string.native_status_2);
                    refreshButtonState();
                });
            }

            @Override
            public void onError(String code, String error) {
                main.post(() -> appendLog("pause onError " + code + " " + error));
            }
        });
    }

    private void resume() {
        String deviceId = currentDeviceId();
        appendLog("resumeRecordTransfer(" + deviceId + ")");
        manager.resumeRecordTransfer(deviceId, new IResultCallback() {
            @Override
            public void onSuccess() {
                main.post(() -> {
                    appendLog("resume onSuccess");
                    status = RecordStatusDef.RECORDING;
                    statusText.setText(R.string.native_status_1);
                    refreshButtonState();
                });
            }

            @Override
            public void onError(String code, String error) {
                main.post(() -> appendLog("resume onError " + code + " " + error));
            }
        });
    }

    private void stopRecord() {
        String deviceId = currentDeviceId();
        appendLog("stopRecordTransfer(" + deviceId + ")");
        manager.stopRecordTransfer(deviceId, new IResultCallback() {
            @Override
            public void onSuccess() {
                main.post(() -> {
                    appendLog("stop onSuccess");
                    // 真正结束以 onRecordFinish 为准，这里先禁用按钮
                    refreshButtonState();
                });
            }

            @Override
            public void onError(String code, String error) {
                main.post(() -> appendLog("stop onError " + code + " " + error));
            }
        });
    }

    /** 录音中动态更新参数（无需 stop/restart）。onSuccess 立即返回，真正生效看 onRecordStatusUpdate。 */
    private void updateParams() {
        String deviceId = currentDeviceId();
        RecordParamsV2 params = buildParams(false);
        appendLog("updateParams(" + deviceId + ") params=" + describe(params));
        manager.updateParams(deviceId, params, new IResultCallback() {
            @Override
            public void onSuccess() {
                main.post(() -> appendLog(getString(R.string.native_log_update_params_success)));
            }

            @Override
            public void onError(String code, String error) {
                main.post(() -> appendLog("updateParams onError " + code + " " + error));
            }
        });
    }

    /** 切换收音通道：0 未指定 / 1 BT / 2 Micro，循环。 */
    private void switchChannel() {
        String deviceId = currentDeviceId();
        currentChannel = (currentChannel + 1) % 3;
        appendLog("switchRecordChannel(" + deviceId + ", " + currentChannel + ")");
        manager.switchRecordChannel(deviceId, currentChannel, new IResultCallback() {
            @Override
            public void onSuccess() {
                main.post(() -> appendLog("switchChannel onSuccess -> " + channelName(currentChannel)));
            }

            @Override
            public void onError(String code, String error) {
                main.post(() -> appendLog("switchChannel onError " + code + " " + error));
            }
        });
    }

    // ===================== 参数构造 =====================

    /**
     * 会议录音模式参数（写死）：recordMode=现场录音、audioSource=系统 MIC、businessType=AI Note。
     * 可变项仅由开关/语言决定。
     *
     * @param forStart true=startAudioRecording 入参；false=updateParams 入参
     */
    private RecordParamsV2 buildParams(boolean forStart) {
        RecordParamsV2 params = new RecordParamsV2();
        // 会议录音：LIVE_RECORDING(1) + 系统 MIC 16K 单声道
        int audioSource = 1;
        params.setRecordMode(1);
        params.setAudioSource(audioSource);
        // RecordExecutorManager.getRecordType 必须读 audioSourceList[0]，不能为空
        List<Integer> sourceList = new ArrayList<>();
        sourceList.add(audioSource);
        params.setAudioSourceList(sourceList);
        params.setBusinessType(0); // AI Note

        params.setNeedAsr(optAsr);
        params.setNeedAmplitude(optAmplitude);
        params.setNeedTranslate(optTranslate);
        // 以 Spinner 当前值为准回写，保证 UI 选择一定生效
        readLangs();
        if (optAsr || optTranslate) {
            params.setOriginalLanguage(sourceLang);
        }
        if (params.getNeedTranslate()) {
            params.setTargetLanguage(targetLang);
        }
        return params;
    }

    /** 直接从 Spinner 读取语言，作为唯一数据源。 */
    private void readLangs() {
        Object src = sourceLangSpinner != null ? sourceLangSpinner.getSelectedItem() : null;
        Object tgt = targetLangSpinner != null ? targetLangSpinner.getSelectedItem() : null;
        if (src != null) sourceLang = src.toString();
        if (tgt != null) targetLang = tgt.toString();
    }

    /** 刷新"当前选择"摘要，让设备/语言的选中状态在 UI 上可见。 */
    private void updateSummary() {
        readLangs();
        String text = getString(R.string.native_summary_format,
                currentDeviceName(), getString(R.string.native_mode_meeting),
                sourceLang, targetLang);
        summaryText.setText(text);
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
        if (p.getOriginalLanguage() != null) parts.add("src=" + p.getOriginalLanguage());
        if (p.getTargetLanguage() != null) parts.add("tgt=" + p.getTargetLanguage());
        parts.add("biz=" + p.getBusinessType());
        return TextUtils.join(", ", parts);
    }

    // ===================== UI 辅助 =====================

    private void refreshButtonState() {
        boolean recording = status == RecordStatusDef.RECORDING;
        boolean paused = status == RecordStatusDef.PAUSING;
        btnStart.setEnabled(!recording && !paused);
        btnPause.setEnabled(recording);
        btnResume.setEnabled(paused);
        btnStop.setEnabled(recording || paused);
        btnSwitchChannel.setEnabled(recording || paused);
    }

    private String currentDeviceId() {
        int pos = deviceSpinner.getSelectedItemPosition();
        if (pos >= 0 && pos < deviceItems.size()) {
            return deviceItems.get(pos).devId;
        }
        return DEFAULT_DEVICE_ID;
    }

    /** 当前选中设备名称（日志/UI 展示用）。 */
    private String currentDeviceName() {
        int pos = deviceSpinner.getSelectedItemPosition();
        if (pos >= 0 && pos < deviceItems.size()) {
            return deviceItems.get(pos).displayName;
        }
        return DEFAULT_DEVICE_ID;
    }

    /**
     * 加载设备列表：index 0 固定为 PHONE（手机本地录音），其余取当前家庭下
     * 符合音频能力（Pro 耳机 / 录音卡片）的设备，按名称展示。
     * 过滤逻辑参考 AudioDeviceSilentRegister#register。
     */
    private void loadDevices() {
        deviceItems.clear();
        deviceItems.add(new DeviceItem(DEFAULT_DEVICE_ID, getString(R.string.native_device_phone), null));

        List<DeviceBean> homeDevices = getHomeDevices();
        if (homeDevices != null) {
            for (DeviceBean dev : homeDevices) {
                String category = getAudioProductType(dev);
                if (category == null) continue;
                String name = TextUtils.isEmpty(dev.getName()) ? dev.getDevId() : dev.getName();
                String display = getString(R.string.native_device_name_with_category, name, category);
                deviceItems.add(new DeviceItem(dev.getDevId(), display, category));
            }
        }

        List<String> names = new ArrayList<>();
        for (DeviceItem item : deviceItems) {
            names.add(item.displayName);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, R.layout.item_spinner_native, names);
        adapter.setDropDownViewResource(R.layout.item_spinner_native_dropdown);
        deviceSpinner.setAdapter(adapter);
        deviceSpinner.setSelection(0);

        deviceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                DeviceItem item = deviceItems.get(position);
                updateSummary();
                appendLog(getString(R.string.native_log_select_device, item.displayName, item.devId));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    /** 取当前家庭设备列表。 */
    private List<DeviceBean> getHomeDevices() {
        try {
            AbsBizBundleFamilyService familyService = MicroServiceManager.getInstance()
                    .findServiceByInterface(AbsBizBundleFamilyService.class.getName());
            if (familyService == null) return null;
            return ThingHomeSdk.newHomeInstance(familyService.getCurrentHomeId())
                    .getHomeBean().getDeviceList();
        } catch (Exception e) {
            appendLog(getString(R.string.native_log_get_home_devices_exception, e.getMessage()));
            return null;
        }
    }

    /**
     * 读取设备的音频产品类型。参考 AudioDeviceSilentRegister / DeviceUtils.getProductType：
     * 解析 productRefBean.configMetas["tyabiw4jrd"] 中的 product_type 字段。
     * 仅保留 Pro 耳机（pro_version）和录音卡片（card）。
     *
     * @return 展示用类别名；非音频设备返回 null
     */
    private String getAudioProductType(DeviceBean dev) {
        try {
            if (dev == null || dev.getProductRefBean() == null) return null;
            Map<String, Object> configMetas = dev.getProductRefBean().getConfigMetas();
            if (configMetas == null) return null;
            Object meta = configMetas.get("tyabiw4jrd");
            if (meta == null) return null;
            JSONObject json = JSON.parseObject(meta.toString());
            if (json == null) return null;
            String productType = json.getString("product_type");
            if (TextUtils.isEmpty(productType)) return null;
                switch (productType) {
                case "pro_version": return getString(R.string.native_category_pro_earphone);
                case "card": return getString(R.string.native_category_card);
                case "entry_version": return getString(R.string.native_category_entry_earphone);
                case "os_buds": return getString(R.string.native_category_os_buds);
                default: return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 设备列表项。 */
    private static class DeviceItem {
        final String devId;
        final String displayName;
        final String category; // 可空，PHONE 时为 null

        DeviceItem(String devId, String displayName, String category) {
            this.devId = devId;
            this.displayName = displayName;
            this.category = category;
        }
    }

    private void appendLog(String msg) {
        String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
        logText.append("[" + time + "] " + msg + "\n\n");
        logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private String statusText(int s) {
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

    private double normalizeAmp(double amp) {
        // SDK 的 AmplitudeCalculator 已对 PCM 16bit 归一化到 0~1（除以 32768），
        // RMS 模式下正常说话音量通常只有 0.02~0.15，线性放大后仍偏小。
        // 用幂函数映射 x^0.4 做非线性拉伸（小值放大、大值压缩），再 clamp 到 [0,1]。
        // 例如 0.05 -> 0.30，0.1 -> 0.40，0.2 -> 0.53，视觉上更灵敏。
        double v = Math.abs(amp);
        v = Math.pow(v, 0.4);
        return Math.min(1d, Math.max(0d, v));
    }

    private static String formatDuration(long ms) {
        long totalSec = ms / 1000;
        long m = totalSec / 60;
        long s = totalSec % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", m, s);
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(v)) return i;
        }
        return 0;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (recordListener != null) {
            manager.removeRecordListener(recordListener);
            appendLog("removeRecordListener");
            recordListener = null;
        }
        main.removeCallbacksAndMessages(null);
    }
}
