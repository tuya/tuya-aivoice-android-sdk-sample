package com.tuya.smart.ai_voice.nativeui;

import android.view.View;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.thingclips.smart.earphone.enhance.api.bean.DeviceOfflineFileStatus;
import com.thingclips.smart.earphone.enhance.api.bean.FileDigest;
import com.thingclips.smart.earphone.enhance.api.bean.OfflineFilesResponse;
import com.thingclips.smart.earphone.enhance.api.listener.IOfflineFilesProgress;
import com.thingclips.smart.earphone.enhance.api.listener.IRecordCallBack;
import com.tuya.smart.ai_voice.R;
import com.tuya.smart.ai_voice.nativeui.base.NativeDemoBaseActivity;

import java.util.Arrays;
import java.util.List;

/**
 * 模块 4 · 离线文件传输演示页。
 * <p>
 * 把<b>录音卡片</b>本地缓存的录音文件下载到手机，支持 BLE 与 AP（Wi-Fi 快传）两种通道。
 * <p>
 * <b>本模块只支持录音卡片</b>（{@code product_type == "card"}，即
 * {@code AudioDeviceCategory.CATEGORY_CARD}）：卡片没有屏幕也不常连手机，
 * 录下的音频先存在设备上、之后再回传；耳机类设备走实时链路，不产生设备侧缓存文件。
 * 故本页的设备下拉<b>已过滤掉手机与耳机</b>，见 {@link #acceptDevice(String)}。
 * 页面按三步手动串联，便于逐步观察：
 * <ol>
 *     <li>选设备 → {@link #queryStatus()} 调 {@code getDeviceOfflineFileStatus} 查看待传文件数与会话</li>
 *     <li>{@link #loadOfflineFile()} 调 {@code loadOfflineFile} 发起或续传（{@code sessionId} 非 0 即续传）</li>
 *     <li>{@link #switchChannel(int)} 调 {@code switchModeLoadOfflineFile} 在 AP / BLE 间切换</li>
 * </ol>
 * <b>AI 笔记中这一步是自动的</b>：录音结束或首页可见时遍历在线卡片设备，
 * 逐个 {@code getDeviceOfflineFileStatus}，发现 {@code response.total > 0} 就直接以
 * {@code channel=BLE、sessionId=已有值或 0} 发起下载，全程无用户操作。
 * <p>
 * <b>进度回调有两条路径，本页两条都演示：</b>
 * <ul>
 *     <li>{@code registerFileProgressCallback} 注册的全局回调 —— 主用。建议在 App 级注册一次，
 *         页面进出不影响，避免切页丢进度</li>
 *     <li>{@code loadOfflineFile} / {@code switchModeLoadOfflineFile} 传入的回调 —— 只用来接
 *         {@code onSuccess(sessionId)} / {@code onError} 的调用结果</li>
 * </ul>
 * 两处 {@code onProgress} 收到的数据一致，会重复回调，生产环境择一即可。
 * <p>
 * <b>AP 建链状态机的数据源是进度回调，不是接口返回值</b>：
 * {@code response.apConnectState}（0 热点未开启 / 1 已开启 / 2 已连接）与
 * {@code DeviceOfflineFileStatus.errorCode} 共同驱动 UI，见 {@link #renderProgress(DeviceOfflineFileStatus)}。
 * <p>
 * 本页有意<b>不</b>实现的两点（属于产品交互，各家 App 自行决定）：
 * <ul>
 *     <li>建链失败重试计数 —— AI 笔记的策略是最多重试 3 次 {@code switchMode(AP)}，超限弹错误框</li>
 *     <li>退出拦截 —— 建链中 / AP 快传中返回时应弹确认框，确认后 {@code switchMode(BLE)}。
 *         生产环境若停留在 AP 模式，建议退出前切回 BLE，否则设备热点会一直占用</li>
 * </ul>
 */
public class NativeOfflineFileActivity extends NativeDemoBaseActivity {

    // ===== 下载通道（loadOfflineFile / switchModeLoadOfflineFile 的 channel 入参）=====
    /** 未指定通道。 */
    private static final int CHANNEL_UNSPECIFIED = 0;
    /** 蓝牙 BLE 传输。 */
    private static final int CHANNEL_BLE = 1;
    /** Wi-Fi AP 快传。 */
    private static final int CHANNEL_AP = 2;

    /** {@code sessionId} 传 0 表示发起新任务，非 0 表示续传已有任务。 */
    private static final long SESSION_ID_NEW_TASK = 0L;

    // ===== DeviceOfflineFileStatus.status =====
    /** 未开始下载。 */
    private static final int DOWNLOAD_NOT_STARTED = 0;
    /** 下载中。 */
    private static final int DOWNLOADING = 1;
    /** 下载结束。 */
    private static final int DOWNLOAD_FINISHED = 2;

    // ===== OfflineFilesResponse.apConnectState =====
    /** 热点未开启（初始态）。 */
    private static final int AP_PENDING = 0;
    /** 设备热点已开启，手机尚未连上。 */
    private static final int AP_OPENED = 1;
    /** 手机已连上设备热点，可以走 AP 快传。 */
    private static final int AP_CONNECTED = 2;

    // ===== AP 建链错误码 =====
    /** Wi-Fi 打开失败。 */
    private static final int ERR_WIFI_OPEN = 10091;
    /** 热点连接失败类错误码。 */
    private static final List<Integer> ERR_AP_CONNECT =
            Arrays.asList(10092, 10096, 10300, 10301, 10302, 10303, 10304, 10305);

    /** 全局进度回调，用字段持有，{@code unRegisterFileProgressCallback} 时传同一引用。 */
    private IOfflineFilesProgress globalProgressCallback;

    private TextView statusText;
    private TextView channelText;
    private TextView progressText;
    private View btnLoad;

    /** 最近一次查询到的会话 ID，非 0 时 {@code loadOfflineFile} 表示续传。 */
    private long sessionId = SESSION_ID_NEW_TASK;

    /** 当前通道，由进度回调的 {@code response.channel} 更新。 */
    private int currentChannel = CHANNEL_BLE;

    @Override
    protected int getContentLayoutId() {
        return R.layout.activity_native_offline_file;
    }

    @Override
    protected int getTitleResId() {
        return R.string.native_offline_title;
    }

    /**
     * 不列出「手机」。手机上不存在设备侧缓存，本模块对它无意义。
     */
    @Override
    protected boolean includePhoneDevice() {
        return false;
    }

    /**
     * <b>只列出录音卡片。</b>
     * <p>
     * 离线文件传输是卡片类设备独有的能力——它没有屏幕也不常连手机，录下的音频先存在本机，
     * 之后再回传；耳机类设备走的是实时链路，不产生设备侧缓存文件。
     * <p>
     * 判据是产品配置里的 {@code product_type == "card"}，与
     * {@code AudioDeviceCategory.CATEGORY_CARD} 一致，解析逻辑见基类
     * {@code getAudioProductType}。
     * <p>
     * 直接过滤而不是选中后再提示「该设备不支持」——用户根本选不到，少一次试错。
     */
    @Override
    protected boolean acceptDevice(@NonNull String productType) {
        return DeviceItem.TYPE_CARD.equals(productType);
    }

    @Override
    protected void onContentViewCreated() {
        statusText = findViewById(R.id.tv_status);
        channelText = findViewById(R.id.tv_channel);
        progressText = findViewById(R.id.tv_progress);
        btnLoad = findViewById(R.id.btn_load);

        setupDeviceSpinner((Spinner) findViewById(R.id.sp_device));

        findViewById(R.id.btn_query_status).setOnClickListener(v -> queryStatus());
        btnLoad.setOnClickListener(v -> loadOfflineFile());
        findViewById(R.id.btn_switch_ap).setOnClickListener(v -> switchChannel(CHANNEL_AP));
        findViewById(R.id.btn_switch_ble).setOnClickListener(v -> switchChannel(CHANNEL_BLE));

        applyNoDeviceState();
    }

    /**
     * 当前家庭没有录音卡片时，禁用全部操作并给出说明。
     * <p>
     * 不这么做的话，按钮点下去只会得到一个底层错误码，使用者无从判断是环境问题还是接入问题。
     */
    private void applyNoDeviceState() {
        if (!hasNoUsableDevice()) {
            return;
        }
        statusText.setText(R.string.native_offline_no_card_device);
        findViewById(R.id.btn_query_status).setEnabled(false);
        btnLoad.setEnabled(false);
        findViewById(R.id.btn_switch_ap).setEnabled(false);
        findViewById(R.id.btn_switch_ble).setEnabled(false);
    }

    // ===================== 事件监听 =====================

    @Override
    protected void registerListeners() {
        globalProgressCallback = new IOfflineFilesProgress() {
            @Override
            public void onProgress(DeviceOfflineFileStatus status) {
                runOnUi(() -> renderProgress(status));
            }

            @Override
            public void onSuccess(long sessionId) {
            }

            @Override
            public void onError(String code, String error) {
                toastError(code, error);
            }
        };
        manager.registerFileProgressCallback(globalProgressCallback);
    }

    @Override
    protected void unregisterListeners() {
        if (globalProgressCallback != null) {
            manager.unRegisterFileProgressCallback(globalProgressCallback);
            globalProgressCallback = null;
        }
    }

    // ===================== 步骤 1：查询状态 =====================

    /**
     * 查询设备的离线文件列表与下载会话状态。
     * <p>
     * 关键判据：{@code response.total > 0} 表示设备上有待传文件，可以发起下载；
     * {@code status == 1} 表示已有任务在跑，此时应续传而非新建。
     */
    private void queryStatus() {
        if (hasNoUsableDevice()) {
            toast(getString(R.string.native_offline_no_card_device));
            return;
        }
        String deviceId = currentDeviceId();
        manager.getDeviceOfflineFileStatus(deviceId, new IRecordCallBack<DeviceOfflineFileStatus>() {
            @Override
            public void onSuccess(DeviceOfflineFileStatus result) {
                runOnUi(() -> renderStatus(result));
            }

            @Override
            public void onError(String code, String error) {
                runOnUi(() -> {
                    toast(getString(R.string.native_offline_log_query_fail, code, error));
                });
            }
        });
    }

    /**
     * 渲染查询结果，并记录 {@code sessionId} 供续传使用。
     *
     * @param result 查询结果，可能为 null
     */
    private void renderStatus(@Nullable DeviceOfflineFileStatus result) {
        if (result == null) {
            statusText.setText(R.string.native_offline_status_empty);
            return;
        }
        sessionId = result.sessionId == null ? SESSION_ID_NEW_TASK : result.sessionId;
        OfflineFilesResponse resp = result.response;
        int total = resp == null || resp.total == null ? 0 : resp.total;
        int done = resp == null || resp.size == null ? 0 : resp.size;

        String text = getString(R.string.native_offline_status_format,
                downloadStatusName(result.status),
                sessionId,
                total,
                done,
                result.errorCode == null ? 0 : result.errorCode);
        statusText.setText(text);

        // total > 0 才有东西可传；自动流程正是以此为触发条件
        btnLoad.setEnabled(total > 0);
        if (total == 0) {
            toast(getString(R.string.native_offline_toast_no_file));
        }
    }

    // ===================== 步骤 2：发起 / 续传 =====================

    /**
     * 发起或续传离线文件下载。
     * <p>
     * {@code sessionId} 为 0 时新建任务，非 0 时续传上次会话。这里传入的回调只用于接收
     * 启动结果；真正的进度以全局回调为准（两者的 {@code onProgress} 数据一致）。
     */
    private void loadOfflineFile() {
        String deviceId = currentDeviceId();
        manager.loadOfflineFile(deviceId, CHANNEL_BLE, sessionId, new IOfflineFilesProgress() {
            @Override
            public void onProgress(DeviceOfflineFileStatus status) {
                // 与全局回调重复，生产环境择一即可，这里仅记录一条以示存在
            }

            @Override
            public void onSuccess(long newSessionId) {
                runOnUi(() -> {
                    sessionId = newSessionId;
                });
            }

            @Override
            public void onError(String code, String error) {
                runOnUi(() -> {
                    toast(getString(R.string.native_offline_log_load_fail, code, error));
                });
            }
        });
    }

    // ===================== 步骤 3：通道切换 =====================

    /**
     * 切换传输通道。切到 AP 后进入建链流程，建链进度只能从进度回调的
     * {@code apConnectState} / {@code errorCode} 读取，接口本身不返回建链状态。
     *
     * @param channel {@link #CHANNEL_AP} 或 {@link #CHANNEL_BLE}
     */
    private void switchChannel(int channel) {
        String deviceId = currentDeviceId();
        manager.switchModeLoadOfflineFile(deviceId, channel, new IOfflineFilesProgress() {
            @Override
            public void onProgress(DeviceOfflineFileStatus status) {
                // 同上，以全局回调为准
            }

            @Override
            public void onSuccess(long newSessionId) {
            }

            @Override
            public void onError(String code, String error) {
                toastError(code, error);
            }
        });
    }

    // ===================== 进度与建链状态渲染 =====================

    /**
     * 渲染全局进度回调。这是 AP 建链状态机的唯一数据源。
     *
     * @param status 进度事件
     */
    private void renderProgress(@Nullable DeviceOfflineFileStatus status) {
        if (status == null) return;
        OfflineFilesResponse resp = status.response;
        if (resp != null && resp.channel != null) {
            currentChannel = resp.channel;
        }
        int apState = resp == null || resp.apConnectState == null ? AP_PENDING : resp.apConnectState;
        int errorCode = status.errorCode == null ? 0 : status.errorCode;

        channelText.setText(getString(R.string.native_offline_channel_format,
                channelName(currentChannel), apStateName(apState)));

        FileDigest cur = resp == null ? null : resp.curFile;
        String curName = cur == null || cur.fileName == null
                ? getString(R.string.native_offline_no_current_file) : cur.fileName;
        double curProgress = cur == null || cur.progress == null ? 0d : cur.progress;

        progressText.setText(getString(R.string.native_offline_progress_format,
                downloadStatusName(status.status),
                resp == null || resp.size == null ? 0 : resp.size,
                resp == null || resp.total == null ? 0 : resp.total,
                curName,
                curProgress,
                resp == null || resp.speed == null ? 0d : resp.speed,
                resp == null || resp.remainingDownloadTime == null ? 0 : resp.remainingDownloadTime,
                waitingCount(resp),
                failedCount(resp)));

        if (errorCode != 0) {
        }
    }

    /**
     * AP 建链错误码归类：Wi-Fi 打开失败与热点连接失败要给不同引导。
     *
     * @param errorCode 错误码
     * @return 可读提示
     */
    private String apErrorHint(int errorCode) {
        if (errorCode == ERR_WIFI_OPEN) {
            return getString(R.string.native_offline_err_wifi_open);
        }
        if (ERR_AP_CONNECT.contains(errorCode)) {
            return getString(R.string.native_offline_err_ap_connect);
        }
        return getString(R.string.native_offline_err_other);
    }

    private int waitingCount(@Nullable OfflineFilesResponse resp) {
        return resp == null || resp.files_waiting == null ? 0 : resp.files_waiting.size();
    }

    private int failedCount(@Nullable OfflineFilesResponse resp) {
        return resp == null || resp.files_failed == null ? 0 : resp.files_failed.size();
    }

    private String downloadStatusName(@Nullable Integer status) {
        if (status == null) return getString(R.string.native_status_unknown);
        switch (status) {
            case DOWNLOAD_NOT_STARTED: return getString(R.string.native_offline_state_not_started);
            case DOWNLOADING: return getString(R.string.native_offline_state_downloading);
            case DOWNLOAD_FINISHED: return getString(R.string.native_offline_state_finished);
            default: return getString(R.string.native_status_unknown);
        }
    }

    private String channelName(int channel) {
        switch (channel) {
            case CHANNEL_BLE: return getString(R.string.native_offline_channel_ble);
            case CHANNEL_AP: return getString(R.string.native_offline_channel_ap);
            case CHANNEL_UNSPECIFIED:
            default: return getString(R.string.native_channel_unspecified);
        }
    }

    private String apStateName(int apState) {
        switch (apState) {
            case AP_OPENED: return getString(R.string.native_offline_ap_opened);
            case AP_CONNECTED: return getString(R.string.native_offline_ap_connected);
            case AP_PENDING:
            default: return getString(R.string.native_offline_ap_pending);
        }
    }

    @Override
    protected void onDeviceSelected(@NonNull DeviceItem item) {
        super.onDeviceSelected(item);
        // 换设备后原会话失效，重置为新建任务
        sessionId = SESSION_ID_NEW_TASK;
        statusText.setText(R.string.native_offline_status_empty);
        btnLoad.setEnabled(false);
    }
}
