package com.tuya.smart.ai_voice.nativeui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.thingclips.smart.ai.audio.utils.PhoneMicAudioInputFactory;
import com.thingclips.smart.earphone.enhance.api.bean.AudioFrontInputConfig;
import com.tuya.smart.ai_voice.R;
import com.tuya.smart.ai_voice.nativeui.base.NativeDemoBaseActivity;
import com.tuya.smart.ai_voice.nativeui.widget.AudioInjectFileHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 灌流（音频注入）设置页。测试用能力，把麦克风采集到的数据替换为指定音频文件。
 * <p>
 * 用途是让转写 / 翻译链路跑在<b>可复现的输入</b>上：真人对着麦克风念一遍，
 * 每次的音量、语速、环境噪声都不同，结果无法比对；灌一个固定文件进去才谈得上回归。
 *
 * <h3>三条必须知道的约束</h3>
 * <ol>
 *     <li><b>只作用于手机 MIC 通道。</b>底层只有手机麦克风的音频输入走
 *         {@code PhoneMicAudioInputFactory}，蓝牙耳机通道是另一个实现，本设置对其无效</li>
 *     <li><b>文件必须放在约定目录下。</b>{@code ReplaceCaptureData.fileName} 是相对
 *         {@link AudioInjectFileHelper#targetDir(Context)} 的路径，不是绝对路径也不是 Uri</li>
 *     <li><b>只在下次开始录音时生效。</b>配置在录音任务创建音频输入的那一刻被读取，
 *         录音中改配置对本次录音没有影响</li>
 * </ol>
 *
 * <h3>配置的生命周期</h3>
 * {@code setDebugStrategy} 写的是 SDK 里的一个内存字段，<b>进程重启即失效</b>，
 * 本页也刻意不做本地持久化——调试开关被持久化后「重启后还开着但没人记得」是最常见的事故。
 * 需要长期开启的场景，请在自己的工程里显式管理，不要依赖 SDK 侧留存。
 *
 * <h3>关于 3A</h3>
 * anc / agc / aec 随灌流配置一起下发给底层音频处理，降噪等级 SDK 未定义取值范围，
 * 固定使用默认值 {@link #ANC_LEVEL_DEFAULT}。
 * <b>这三个开关当前在布局中隐藏</b>（见 {@code activity_native_audio_inject.xml} 的
 * {@code ll_inject_3a}），未勾选即按 false 下发，与底层默认行为一致；
 * 需要放开时去掉该容器的 visibility 即可，本页代码无需改动。
 */
public class NativeAudioInjectActivity extends NativeDemoBaseActivity {

    private static final String EXTRA_DEVICE_ID = "extra_device_id";
    private static final String EXTRA_DEVICE_NAME = "extra_device_name";
    private static final String EXTRA_RECORDING = "extra_recording";

    /**
     * 降噪等级。SDK 未定义合法取值范围，各处默认值均为 1，此处沿用。
     */
    private static final int ANC_LEVEL_DEFAULT = 1;

    private TextView deviceWarningText;
    private CheckBox enabledSwitch;
    private RadioGroup fileGroup;
    private TextView fileEmptyText;
    private CheckBox ancSwitch;
    private CheckBox agcSwitch;
    private CheckBox aecSwitch;
    private TextView statusText;
    private Button saveButton;

    /** 文件拷贝与目录扫描都是阻塞 IO，统一丢到这里执行。 */
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    /** 当前可选的音频文件名，用于判断列表是否为空。 */
    private final List<String> fileNames = new ArrayList<>();

    /**
     * 打开灌流设置页，不带任何上下文。
     * <p>
     * 供 Native 能力总入口使用。三个 extra 只影响页面上的提示文案，不影响配置本身，
     * 缺省按「手机本地录音、当前未在录音」处理——灌流本就只对手机 MIC 通道生效，
     * 缺省成 {@code PHONE} 语义正确。
     *
     * @param context 上下文
     */
    public static void start(@NonNull Context context) {
        Intent intent = buildIntent(context, DEVICE_ID_PHONE, DEVICE_ID_PHONE, false);
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
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
    public static Intent buildIntent(@NonNull Context context, @NonNull String deviceId,
                                     @NonNull String deviceName, boolean recording) {
        Intent intent = new Intent(context, NativeAudioInjectActivity.class);
        intent.putExtra(EXTRA_DEVICE_ID, deviceId);
        intent.putExtra(EXTRA_DEVICE_NAME, deviceName);
        intent.putExtra(EXTRA_RECORDING, recording);
        return intent;
    }

    @Override
    protected int getContentLayoutId() {
        return R.layout.activity_native_audio_inject;
    }

    @Override
    protected int getTitleResId() {
        return R.string.native_inject_title;
    }

    @Override
    protected void onContentViewCreated() {
        bindViews();
        showDeviceWarning();
        restoreCurrentConfig();
        loadFileList();
        saveButton.setOnClickListener(v -> save());
    }

    /** 本页不涉及 SDK 事件监听，灌流配置是同步读写的内存状态。 */
    @Override
    protected void registerListeners() {
    }

    @Override
    protected void unregisterListeners() {
        io.shutdownNow();
    }

    private void bindViews() {
        deviceWarningText = findViewById(R.id.tv_inject_device_warning);
        enabledSwitch = findViewById(R.id.cb_inject_enabled);
        fileGroup = findViewById(R.id.rg_inject_file);
        fileEmptyText = findViewById(R.id.tv_inject_file_empty);
        ancSwitch = findViewById(R.id.cb_inject_anc);
        agcSwitch = findViewById(R.id.cb_inject_agc);
        aecSwitch = findViewById(R.id.cb_inject_aec);
        statusText = findViewById(R.id.tv_inject_status);
        saveButton = findViewById(R.id.btn_inject_save);
    }

    /**
     * 选中的不是手机时给出提示。
     * <p>
     * 不隐藏入口也不禁用表单——藏起来只会让人以为没这个能力，
     * 说清楚「对当前设备不生效」比事后排查录音为什么没被替换要省事得多。
     */
    private void showDeviceWarning() {
        String deviceId = getIntent().getStringExtra(EXTRA_DEVICE_ID);
        if (DEVICE_ID_PHONE.equals(deviceId)) {
            deviceWarningText.setVisibility(View.GONE);
            return;
        }
        String deviceName = getIntent().getStringExtra(EXTRA_DEVICE_NAME);
        deviceWarningText.setText(getString(R.string.native_inject_device_warning,
                TextUtils.isEmpty(deviceName) ? String.valueOf(deviceId) : deviceName));
        deviceWarningText.setVisibility(View.VISIBLE);
    }

    // ===================== 读取当前配置 =====================

    /**
     * 把 SDK 里当前生效的灌流配置回填到表单。
     * <p>
     * {@code getDebugStrategy()} 返回 null 表示未开启灌流，走真实麦克风。
     */
    private void restoreCurrentConfig() {
        AudioFrontInputConfig config = PhoneMicAudioInputFactory.INSTANCE.getDebugStrategy();
        enabledSwitch.setChecked(config != null);
        if (config != null) {
            ancSwitch.setChecked(config.getAnc().getEnable());
            agcSwitch.setChecked(config.getAgc());
            aecSwitch.setChecked(config.getAec());
        }
        refreshStatusText(config);
    }

    private void refreshStatusText(@Nullable AudioFrontInputConfig config) {
        if (config == null) {
            statusText.setText(R.string.native_inject_status_off);
            return;
        }
        statusText.setText(getString(R.string.native_inject_status_on,
                config.getReplaceCaptureData().getFileName(),
                onOff(config.getAnc().getEnable()),
                onOff(config.getAgc()),
                onOff(config.getAec())));
    }

    private String onOff(boolean value) {
        return getString(value ? R.string.native_inject_on : R.string.native_inject_off);
    }

    // ===================== 文件列表 =====================

    private void loadFileList() {
        io.execute(() -> {
            List<String> names = AudioInjectFileHelper.listAvailableFiles(this);
            runOnUi(() -> renderFileList(names));
        });
    }

    private void renderFileList(@NonNull List<String> names) {
        fileNames.clear();
        fileNames.addAll(names);
        fileGroup.removeAllViews();

        if (names.isEmpty()) {
            fileEmptyText.setVisibility(View.VISIBLE);
            return;
        }
        fileEmptyText.setVisibility(View.GONE);

        AudioFrontInputConfig config = PhoneMicAudioInputFactory.INSTANCE.getDebugStrategy();
        String selected = config == null ? null : config.getReplaceCaptureData().getFileName();

        for (String name : names) {
            RadioButton item = new RadioButton(this);
            // RadioGroup 靠 ID 管理选中态，动态创建的 View 必须显式分配。
            // 这里只能用 generateViewId：手写常量会撞进 aapt 分配的 R.id 区间，
            // 使 findViewById 可能命中这些临时 View。
            item.setId(View.generateViewId());
            item.setTag(name);
            item.setText(name);
            item.setTextSize(13f);
            item.setLayoutParams(new RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            fileGroup.addView(item);
            if (name.equals(selected)) {
                item.setChecked(true);
            }
        }
    }

    /**
     * @return 当前选中的文件名；未选中时返回 {@code null}
     */
    @Nullable
    private String selectedFileName() {
        int checkedId = fileGroup.getCheckedRadioButtonId();
        if (checkedId == View.NO_ID) {
            return null;
        }
        RadioButton checked = fileGroup.findViewById(checkedId);
        return checked == null ? null : (String) checked.getTag();
    }

    // ===================== 保存 =====================

    private void save() {
        if (!enabledSwitch.isChecked()) {
            // 关闭灌流：传 null 即恢复真实麦克风采集。
            // 已拷贝到目标目录的文件不删——留着下次开启即可直接使用，省一次拷贝。
            PhoneMicAudioInputFactory.INSTANCE.setDebugStrategy(null);
            toast(getString(R.string.native_inject_toast_disabled));
            finish();
            return;
        }

        String fileName = selectedFileName();
        if (fileName == null) {
            toast(getString(R.string.native_inject_toast_need_file));
            return;
        }

        boolean anc = ancSwitch.isChecked();
        boolean agc = agcSwitch.isChecked();
        boolean aec = aecSwitch.isChecked();

        saveButton.setEnabled(false);
        saveButton.setText(R.string.native_inject_btn_saving);
        io.execute(() -> {
            // assets 中的样例要先落到目标目录，底层才读得到
            boolean ready = AudioInjectFileHelper.ensureFileReady(this, fileName);
            runOnUi(() -> {
                saveButton.setEnabled(true);
                saveButton.setText(R.string.native_inject_btn_save);
                if (!ready) {
                    toast(getString(R.string.native_inject_toast_copy_failed, fileName));
                    return;
                }
                applyConfig(fileName, anc, agc, aec);
            });
        });
    }

    /**
     * 下发灌流配置。
     * <p>
     * 末位的 {@code enableAutoInject} 固定传 {@code false}：为 true 时走的是另一条
     * 「按帧定速读裸 PCM」的自灌流实现，不解析音频容器格式，本页不使用。
     *
     * @param fileName 相对目标目录的文件名
     * @param anc      降噪
     * @param agc      自动增益
     * @param aec      回声消除
     */
    private void applyConfig(@NonNull String fileName, boolean anc, boolean agc, boolean aec) {
        AudioFrontInputConfig config = new AudioFrontInputConfig(
                new AudioFrontInputConfig.AncConfig(anc, ANC_LEVEL_DEFAULT),
                agc,
                aec,
                new AudioFrontInputConfig.ReplaceCaptureData(fileName),
                false);
        PhoneMicAudioInputFactory.INSTANCE.setDebugStrategy(config);

        boolean recording = getIntent().getBooleanExtra(EXTRA_RECORDING, false);
        toast(getString(recording
                ? R.string.native_inject_toast_saved_recording
                : R.string.native_inject_toast_saved));
        finish();
    }
}
