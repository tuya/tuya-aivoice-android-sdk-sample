package com.tuya.smart.ai_voice.nativeui;

import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.thingclips.smart.ai.audio.sync.api.DownloadListener;
import com.thingclips.smart.ai.audio.sync.api.UploadListener;
import com.thingclips.smart.ai.audio.sync.api.ttt.SyncObserver;
import com.thingclips.smart.ai.db.entity.RecordFile;
import com.thingclips.smart.android.network.Business;
import com.thingclips.smart.android.network.http.BusinessResponse;
import com.thingclips.smart.earphone.enhance.api.bean.CloudSyncSwitchParam;
import com.thingclips.smart.earphone.enhance.api.listener.CloudSyncRefreshType;
import com.thingclips.smart.earphone.enhance.api.listener.ICloudSwitchListener;
import com.thingclips.smart.earphone.enhance.api.listener.ICloudSyncSwitchCallBack;
import com.thingclips.smart.plugin.tuniaudiodetectmanager.bean.nativeapi.CloudSyncStatusInfo;
import com.thingclips.smart.sdk.api.IResultCallback;
import com.tuya.smart.ai_voice.R;
import com.tuya.smart.ai_voice.nativeui.base.NativeDemoBaseActivity;
import com.tuya.smart.ai_voice.nativeui.business.CloudSyncSwitchBusiness;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 模块 6 · 云同步演示页。
 *
 * <h3>状态从哪来</h3>
 * 云同步的聚合状态有<b>两个来源，缺一不可</b>：
 * <ul>
 *     <li><b>快照</b> —— {@code getCloudSyncSwitchStatus} 回调里的 {@link CloudSyncStatusInfo}。
 *         进页必须取一次，否则进页时若正有同步在跑，界面会一直显示错误状态直到下一个事件到达。
 *         这与模块 5 的 {@code getAudioImportStatus}、模块 8 的 {@code getFileMergeStatus} 是同一套模式</li>
 *     <li><b>增量</b> —— {@link SyncObserver} 的上传回调，由 {@link SyncAggregator} 收敛成同一套状态</li>
 * </ul>
 *
 * <h3>状态取值</h3>
 * <pre>
 * -1 未开启   开关关闭时恒为此值，优先级最高
 *  0 同步成功  同时涵盖「从未同步过」，要区分用 modifyTime <= 0 判断
 *  1 同步中
 *  2 同步失败  此时看 errorCode
 * </pre>
 * <b>状态只由上传决定</b>，下载的成功 / 失败不影响它，计数仅用于展示。
 *
 * <h3>三类结果信号，别混用</h3>
 * {@code syncNoteRecord} 的 {@code onSuccess} 只表示任务已启动，产出散落在三处：
 * <pre>
 * 本地录音有没有存上云   SyncObserver.UploadListener      → 本页的聚合状态
 * 云端记录有没有拉回本地  IRecordFileUpdateCallback         → 列表页全量重拉
 *                      .onRecordListSyncSuccess()
 *                      .onRecordOperate(ADD)
 * 某条录音的音频下完没有  SyncObserver.DownloadListener     → 单条状态
 *                      .onFinish
 * </pre>
 * 第二条是「拿到云端数据」的唯一信号，监听注册在 {@link NativeRecordListActivity}，本页不处理。
 * 注意<b>记录与音频不是一回事</b>：记录早就同步好了，音频可能还没下、
 * 甚至根本不下——要等用户点播放才按需拉（{@code syncDownloadNoteAudio}）。
 *
 * <h3>开关写入不是 SDK 能力</h3>
 * 架构上小程序自己实现一小部分功能（直调 atop 云接口），其余通过 wearkit 桥映射到 Native。
 * 云同步开关的<b>写入</b>属于前者，故 SDK 只有查询没有写入，需自行调 atop，
 * 见 {@link CloudSyncSwitchBusiness}。
 *
 * <h3>错误码</h3>
 * {@code status == 2} 时的 {@code errorCode}：{@code 10001} 网络中断 /
 * {@code 10002} 仅 Wi-Fi 下 Wi-Fi 断开 / {@code 10003} 服务器繁忙 / {@code 20001} 本月上限。
 * <p>
 * 其中 {@code 10002} 语义上不是失败而是「等待 Wi-Fi」，本页据此把标题显示为「同步已暂停」
 * 并引导用户改设置，而不是提示重试。
 * <p>
 * 另有 {@code 10213} 出现在 {@code syncNoteRecord} 的 {@code onError} 里，
 * 表示「重复发起同步」，<b>它不是失败</b>，而是「正在同步」的确定信号。
 * <p>
 * 单条录音的按需下载（{@code syncDownloadNoteAudio}）属于「对某条录音的操作」，
 * 在 {@link NativeRecordDetailActivity} 的更多操作里，不在本页。
 */
public class NativeCloudSyncActivity extends NativeDemoBaseActivity {

    // ===== 聚合状态，取值同 CloudSyncStatusInfo =====
    /** 云同步开关未开启。 */
    private static final int SYNC_DISABLED = CloudSyncStatusInfo.STATUS_DISABLED;
    /** 同步成功，或从未同步过。 */
    private static final int SYNC_SUCCESS = CloudSyncStatusInfo.STATUS_SUCCESS;
    /** 同步中。 */
    private static final int SYNC_SYNCING = CloudSyncStatusInfo.STATUS_SYNCING;
    /** 同步失败。 */
    private static final int SYNC_FAILED = CloudSyncStatusInfo.STATUS_FAILED;

    // ===== CloudSyncSwitchParam.syncType =====
    /** 任意网络下同步。 */
    private static final int SYNC_TYPE_ALL_NETWORK = 0;
    /** 仅 Wi-Fi 下同步。 */
    private static final int SYNC_TYPE_WIFI_ONLY = 1;

    // ===== 状态错误码：出现在聚合状态的 errorCode 里 =====
    /** 网络中断。 */
    private static final int ERR_NETWORK = 10001;
    /** 仅 Wi-Fi 模式下 Wi-Fi 断开，据此判定「同步已暂停」。 */
    private static final int ERR_WIFI_ONLY_NO_WIFI = 10002;
    /** 同步超时。 */
    private static final int ERR_TIMEOUT = 10003;
    /** 超出本月同步配额。 */
    private static final int ERR_BEYOND_LIMIT = 20001;

    /** 接口返回码：重复发起同步。说明底层正在同步中，不是失败。 */
    private static final String CODE_ALREADY_SYNCING = "10213";

    /** 「从未同步过」时最近同步时间的取值。 */
    private static final long NEVER_SYNCED = 0L;

    /** 云同步开关变更监听，用字段持有，remove 时传同一引用。 */
    private ICloudSwitchListener<CloudSyncSwitchParam, Long> switchListener;

    /** 上传/下载观察者，用字段持有，remove 时传同一引用。 */
    private SyncObserver syncObserver;

    private final SyncAggregator aggregator = new SyncAggregator();
    private final CloudSyncSwitchBusiness switchBusiness = new CloudSyncSwitchBusiness();

    private CheckBox enabledCheckBox;
    private CheckBox wifiOnlyCheckBox;
    private TextView switchStatusText;
    private TextView syncTitleText;
    private TextView syncTipText;
    private TextView syncStatusText;

    /** 回写开关勾选态时置 true，抑制联动回调，避免把「渲染」当成「用户操作」。 */
    private boolean applyingSwitchState = false;

    /** 上一次已知的 SDK 侧开关状态，保存失败时据此回滚界面。 */
    private boolean knownEnabled = false;
    /** 上一次已知的 SDK 侧「仅 Wi-Fi」设置，保存失败时据此回滚界面。 */
    private boolean knownWifiOnly = false;

    /** 最近一次同步执行时间，来自快照，用于「最近同步：xxx」文案。 */
    private long lastSyncTime = NEVER_SYNCED;

    private final SimpleDateFormat timeFmt =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    @Override
    protected int getContentLayoutId() {
        return R.layout.activity_native_cloud_sync;
    }

    @Override
    protected int getTitleResId() {
        return R.string.native_cloud_title;
    }

    @Override
    protected void onContentViewCreated() {
        enabledCheckBox = findViewById(R.id.cb_sync_enabled);
        wifiOnlyCheckBox = findViewById(R.id.cb_wifi_only);
        switchStatusText = findViewById(R.id.tv_switch_status);
        syncTitleText = findViewById(R.id.tv_sync_title);
        syncTipText = findViewById(R.id.tv_sync_tip);
        syncStatusText = findViewById(R.id.tv_sync_status);

        findViewById(R.id.btn_query_switch).setOnClickListener(v -> querySwitchStatus());
        findViewById(R.id.btn_sync_now).setOnClickListener(v -> syncNow());

        enabledCheckBox.setOnCheckedChangeListener((button, checked) -> {
            if (applyingSwitchState) return;
            onEnabledToggled(checked);
        });
        wifiOnlyCheckBox.setOnCheckedChangeListener((button, checked) -> {
            if (applyingSwitchState) return;
            // 全量提交：改 syncType 也必须带上当前 enabled
            saveSwitch(enabledCheckBox.isChecked(), checked);
        });

        querySwitchStatus();
        renderSyncStatus();
    }

    // ===================== 事件监听 =====================

    @Override
    protected void registerListeners() {
        switchListener = new ICloudSwitchListener<CloudSyncSwitchParam, Long>() {
            @Override
            public void onRefreshSwitchState(CloudSyncSwitchParam param, Long time,
                                             CloudSyncRefreshType type) {
                runOnUi(() -> {
                    appendLog(getString(R.string.native_cloud_log_switch_event,
                            String.valueOf(type),
                            param == null ? "null" : String.valueOf(param.getEnabled())));
                    renderSwitchState(param, time);
                    // 开关一旦关闭，聚合状态立即置「未开启」，不等上传回调
                    if (param != null && !param.getEnabled()) {
                        aggregator.markDisabled();
                        renderSyncStatus();
                    }
                });
            }

            @Override
            public void onError(String code, String error) {
                runOnUi(() -> appendLog(getString(R.string.native_cloud_log_switch_error, code, error)));
            }
        };
        manager.addCloudSwitchListener(switchListener);

        syncObserver = new SyncObserver(new AggregatingDownloadListener(), new AggregatingUploadListener());
        manager.addAudioSyncObserver(syncObserver);
        appendLog("addCloudSwitchListener + addAudioSyncObserver");
    }

    @Override
    protected void unregisterListeners() {
        if (switchListener != null) {
            manager.removeCloudSwitchListener(switchListener);
            switchListener = null;
        }
        if (syncObserver != null) {
            manager.removeAudioSyncObserver(syncObserver);
            syncObserver = null;
        }
        switchBusiness.onDestroy();
    }

    // ===================== 开关查询 =====================

    /**
     * 查询云同步开关，并<b>一并取回聚合状态快照</b>。
     * <p>
     * 快照是进页时状态正确的唯一保证：{@link SyncObserver} 只在同步发生时才回调，
     * 不取快照就无法知道「进页那一刻是否正在同步」。
     */
    private void querySwitchStatus() {
        appendLog("getCloudSyncSwitchStatus()");
        manager.getCloudSyncSwitchStatus(
                new ICloudSyncSwitchCallBack<CloudSyncSwitchParam, CloudSyncStatusInfo>() {
                    @Override
                    public void onSuccess(CloudSyncSwitchParam param, CloudSyncStatusInfo statusInfo) {
                        runOnUi(() -> {
                            renderSwitchState(param, statusInfo == null ? null : statusInfo.modifyTime);
                            applySnapshot(statusInfo);
                        });
                    }

                    @Override
                    public void onError(String code, String error) {
                        runOnUi(() -> appendLog(getString(
                                R.string.native_cloud_log_switch_error, code, error)));
                    }
                });
    }

    /**
     * 用快照重置聚合状态。
     *
     * @param statusInfo 快照，可能为 null
     */
    private void applySnapshot(@Nullable CloudSyncStatusInfo statusInfo) {
        if (statusInfo == null) return;
        lastSyncTime = statusInfo.modifyTime;
        aggregator.applySnapshot(statusInfo.status, statusInfo.errorCode);
        appendLog(getString(R.string.native_cloud_log_snapshot,
                syncStatusName(statusInfo.status), statusInfo.errorCode, statusInfo.modifyTime));
        renderSyncStatus();
    }

    /**
     * 把 SDK 返回的开关状态回写到界面，并记为「已知的 SDK 状态」。
     * <p>
     * 该状态是保存失败时的回滚基准，见 {@link #saveSwitch(boolean, boolean)}。
     *
     * @param param 开关参数，可能为 null
     * @param time  最近同步时间，可能为 null
     */
    private void renderSwitchState(@Nullable CloudSyncSwitchParam param, @Nullable Long time) {
        if (time != null) {
            lastSyncTime = time;
        }
        if (param == null) {
            switchStatusText.setText(R.string.native_cloud_switch_empty);
            return;
        }
        knownEnabled = param.getEnabled();
        knownWifiOnly = param.getSyncType() != null && param.getSyncType() == SYNC_TYPE_WIFI_ONLY;
        applySwitchUi(knownEnabled, knownWifiOnly);

        switchStatusText.setText(getString(R.string.native_cloud_switch_format,
                param.getEnabled()
                        ? getString(R.string.native_cloud_switch_on)
                        : getString(R.string.native_cloud_switch_off),
                syncTypeName(param.getSyncType()),
                formatTime(param.getModifyTime()),
                formatTime(lastSyncTime)));
    }

    /**
     * 回写勾选态，期间抑制联动回调，避免把「渲染」当成「用户操作」而触发保存。
     *
     * @param enabled  云同步开关
     * @param wifiOnly 仅 Wi-Fi 同步
     */
    private void applySwitchUi(boolean enabled, boolean wifiOnly) {
        applyingSwitchState = true;
        enabledCheckBox.setChecked(enabled);
        wifiOnlyCheckBox.setChecked(wifiOnly);
        applyingSwitchState = false;
    }

    private String syncTypeName(@Nullable Integer syncType) {
        if (syncType == null) return getString(R.string.native_status_unknown);
        switch (syncType) {
            case SYNC_TYPE_ALL_NETWORK: return getString(R.string.native_cloud_sync_type_all);
            case SYNC_TYPE_WIFI_ONLY: return getString(R.string.native_cloud_sync_type_wifi);
            default: return getString(R.string.native_status_unknown);
        }
    }

    // ===================== 开关写入 =====================

    /**
     * 用户切换云同步总开关，直接保存，不做二次确认。
     * <p>
     * 与小程序的两处差异：
     * <ul>
     *     <li>小程序关闭时会弹确认框提示「卸载 App 后录音丢失」，本页直接保存。
     *         生产环境建议保留该提示，关闭云同步的后果对用户不可见</li>
     *     <li>小程序开启时会强制把 {@code syncType} 设为「仅 Wi-Fi」，
     *         本页保留用户当前选择，避免两个开关互相干扰</li>
     * </ul>
     *
     * @param checked 目标状态
     */
    private void onEnabledToggled(boolean checked) {
        saveSwitch(checked, wifiOnlyCheckBox.isChecked());
    }

    /**
     * 保存开关。<b>全量提交</b>：两个字段每次都要一起传。
     * <p>
     * <b>保存成功后不要立刻回查 {@code getCloudSyncSwitchStatus}。</b>
     * 该接口读的是 SDK 的本地缓存，缓存要等 MQTT 推送或后台校验才更新——
     * 保存刚成功时它仍是旧值，回查会把用户刚做的选择覆盖回去，
     * 表现为「点了开关又自己弹回原状」。
     * <p>
     * 正确做法：成功后保持乐观更新的界面，等 {@link ICloudSwitchListener#onRefreshSwitchState}
     * 到达时自然校正；失败则回滚到 {@link #knownEnabled} / {@link #knownWifiOnly}
     * 记录的上一次已知状态。想立刻看 SDK 侧的值，手动点「查询开关状态与同步快照」。
     *
     * @param enabled  是否开启云同步
     * @param wifiOnly 是否仅 Wi-Fi 同步
     */
    private void saveSwitch(boolean enabled, boolean wifiOnly) {
        String syncType = wifiOnly
                ? CloudSyncSwitchBusiness.SYNC_TYPE_WIFI
                : CloudSyncSwitchBusiness.SYNC_TYPE_ALL;
        appendLog(getString(R.string.native_cloud_log_save, enabled, syncType));
        switchBusiness.saveCloudSyncSwitch(enabled, syncType, new Business.ResultListener<Boolean>() {
            @Override
            public void onSuccess(BusinessResponse response, Boolean result, String apiName) {
                runOnUi(() -> {
                    appendLog(getString(R.string.native_cloud_log_save_ok));
                    knownEnabled = enabled;
                    knownWifiOnly = wifiOnly;
                    if (!enabled) {
                        aggregator.markDisabled();
                    } else if (aggregator.status == SYNC_DISABLED) {
                        aggregator.applySnapshot(SYNC_SUCCESS, 0);
                    }
                    renderSyncStatus();
                });
            }

            @Override
            public void onFailure(BusinessResponse response, Boolean result, String apiName) {
                runOnUi(() -> {
                    String code = response == null ? "-" : response.getErrorCode();
                    String msg = response == null ? "" : response.getErrorMsg();
                    appendLog(getString(R.string.native_cloud_log_save_fail, code, msg));
                    toast(getString(R.string.native_cloud_log_save_fail, code, msg));
                    // 保存失败，回滚到上一次已知的 SDK 状态
                    applySwitchUi(knownEnabled, knownWifiOnly);
                });
            }
        });
    }

    // ===================== 触发同步 =====================

    /**
     * 触发一次同步。<b>双向</b>：上传本地 Note 与音频，同时下载云端 Note。
     * <p>
     * <b>本页做成按钮只是为了便于观察，不是推荐的交互。</b> AI 笔记的做法是不给用户
     * 「立即同步」入口，改在两个时机各自动调一次：
     * <ul>
     *     <li>首页首次加载 —— 无条件调，且早于 {@code getCloudSyncSwitchStatus}</li>
     *     <li>录音列表下拉刷新 —— 需先判「当前不在同步中」且「上次调用未在进行中」</li>
     * </ul>
     * 同步是后台行为、用户没有显式发起，因此失败<b>不必弹提示</b>（本页为演示仍然提示）。
     * <p>
     * 双重防重：本地状态先拦一道；即便漏过，底层也会以 {@code onError("10213")} 拒绝——
     * 那个错误码<b>不是失败</b>，而是「正在同步中」的确定信号，据此把本地状态纠正为「同步中」。
     * <p>
     * <b>{@code onSuccess} 不代表数据已同步</b>，只表示任务已启动。真正的结果分三路，
     * 见类注释「三类结果信号」。
     */
    private void syncNow() {
        if (aggregator.status == SYNC_DISABLED) {
            toast(getString(R.string.native_cloud_toast_disabled));
            return;
        }
        if (aggregator.status == SYNC_SYNCING) {
            toast(getString(R.string.native_cloud_toast_syncing));
            return;
        }
        appendLog("syncNoteRecord()");
        manager.syncNoteRecord(new IResultCallback() {
            @Override
            public void onSuccess() {
                runOnUi(() -> appendLog(getString(R.string.native_cloud_log_sync_ok)));
            }

            @Override
            public void onError(String code, String error) {
                runOnUi(() -> {
                    if (CODE_ALREADY_SYNCING.equals(code)) {
                        // 底层已在同步，纠正本地状态而非报错
                        appendLog(getString(R.string.native_cloud_log_sync_already));
                        aggregator.markSyncing();
                        renderSyncStatus();
                        toast(getString(R.string.native_cloud_toast_syncing));
                        return;
                    }
                    appendLog(getString(R.string.native_cloud_log_sync_fail, code, error));
                    toast(getString(R.string.native_cloud_log_sync_fail, code, error));
                });
            }
        });
    }

    // ===================== 聚合状态渲染 =====================

    private void renderSyncStatus() {
        syncTitleText.setText(syncTitle());
        syncTipText.setText(syncTip());
        syncStatusText.setText(getString(R.string.native_cloud_sync_format,
                syncStatusName(aggregator.status),
                aggregator.errorCode,
                aggregator.uploadedCount,
                aggregator.uploadFailedCount,
                aggregator.downloadedCount,
                aggregator.downloadFailedCount));
    }

    /**
     * 是否处于「同步已暂停」派生态。
     * <p>
     * 判据是错误码 {@link #ERR_WIFI_ONLY_NO_WIFI}——它精确表示「仅 Wi-Fi 模式下 Wi-Fi 断开」，
     * 这种情况不是失败，而是等待条件满足，应引导用户改设置而非重试。
     *
     * @return true 表示应显示为「已暂停」而非「同步失败」
     */
    private boolean isPaused() {
        return aggregator.status == SYNC_FAILED && aggregator.errorCode == ERR_WIFI_ONLY_NO_WIFI;
    }

    private String syncTitle() {
        if (isPaused()) return getString(R.string.native_cloud_state_paused);
        return syncStatusName(aggregator.status);
    }

    /** 状态副文案：未开启给引导语，成功给最近同步时间，失败给错误码含义。 */
    private String syncTip() {
        switch (aggregator.status) {
            case SYNC_DISABLED:
                return getString(R.string.native_cloud_tip_disabled);
            case SYNC_SUCCESS:
                return lastSyncTime > NEVER_SYNCED
                        ? getString(R.string.native_cloud_tip_last_sync, formatTime(lastSyncTime))
                        : getString(R.string.native_cloud_tip_never_synced);
            case SYNC_SYNCING:
                return "";
            case SYNC_FAILED:
                return errorMessage(aggregator.errorCode);
            default:
                return "";
        }
    }

    /**
     * 状态错误码转可读提示。
     *
     * @param errorCode 聚合状态里的错误码
     * @return 可读文案
     */
    private String errorMessage(int errorCode) {
        switch (errorCode) {
            case ERR_NETWORK: return getString(R.string.native_cloud_err_10001);
            case ERR_WIFI_ONLY_NO_WIFI: return getString(R.string.native_cloud_err_10002);
            case ERR_TIMEOUT: return getString(R.string.native_cloud_err_10003);
            case ERR_BEYOND_LIMIT: return getString(R.string.native_cloud_err_20001);
            default: return getString(R.string.native_cloud_err_unknown, errorCode);
        }
    }

    private String syncStatusName(int status) {
        switch (status) {
            case SYNC_DISABLED: return getString(R.string.native_cloud_state_disabled);
            case SYNC_SYNCING: return getString(R.string.native_cloud_state_syncing);
            case SYNC_FAILED: return getString(R.string.native_cloud_state_failed);
            case SYNC_SUCCESS:
            default: return getString(R.string.native_cloud_state_success);
        }
    }

    /**
     * 时间戳格式化。{@code <= 0} 表示「从未发生」。
     *
     * @param millis 毫秒时间戳
     * @return 可读时间，或占位符
     */
    private String formatTime(long millis) {
        if (millis <= NEVER_SYNCED) return getString(R.string.native_cloud_time_never);
        return timeFmt.format(new Date(millis));
    }

    /**
     * 云同步状态聚合器。
     * <p>
     * 把快照与 {@link UploadListener} 的离散回调收敛成一套可直接绑定 UI 的状态。
     * <p>
     * <b>状态只由上传决定</b>，下载仅计数。
     */
    private static class SyncAggregator {
        int status = SYNC_SUCCESS;
        int errorCode = 0;
        int uploadedCount = 0;
        int uploadFailedCount = 0;
        int downloadedCount = 0;
        int downloadFailedCount = 0;

        /** 用快照重置状态，进页时调用一次。 */
        void applySnapshot(int snapshotStatus, int snapshotErrorCode) {
            status = snapshotStatus;
            errorCode = snapshotErrorCode;
        }

        void markSyncing() {
            status = SYNC_SYNCING;
            errorCode = 0;
        }

        /** 批次结束。有个别文件失败不算整体失败，整体失败只由全局异常决定。 */
        void markFinished() {
            status = SYNC_SUCCESS;
            errorCode = 0;
        }

        void markError(@Nullable Integer code) {
            status = SYNC_FAILED;
            errorCode = code == null ? 0 : code;
        }

        void markDisabled() {
            status = SYNC_DISABLED;
            errorCode = 0;
        }

        void resetCounters() {
            uploadedCount = 0;
            uploadFailedCount = 0;
            downloadedCount = 0;
            downloadFailedCount = 0;
        }
    }

    /** 上传回调 → 聚合状态。上传是云同步三态的唯一来源。 */
    private class AggregatingUploadListener implements UploadListener {

        @Override
        public void onStart() {
            runOnUi(() -> {
                aggregator.resetCounters();
                aggregator.markSyncing();
                appendLog(getString(R.string.native_cloud_log_upload_start));
                renderSyncStatus();
            });
        }

        @Override
        public void onPause() {
            runOnUi(() -> appendLog(getString(R.string.native_cloud_log_upload_pause)));
        }

        @Override
        public void uploading(@NonNull RecordFile recordFile, int progress) {
            runOnUi(() -> {
                aggregator.markSyncing();
                appendLog(getString(R.string.native_cloud_log_uploading, progress));
                renderSyncStatus();
            });
        }

        @Override
        public void uploadError(@NonNull RecordFile recordFile, @NonNull String errorCode,
                                @NonNull String errorMsg) {
            runOnUi(() -> {
                aggregator.uploadFailedCount++;
                appendLog(getString(R.string.native_cloud_log_upload_error, errorCode, errorMsg));
                renderSyncStatus();
            });
        }

        @Override
        public void uploadSuccess(@NonNull RecordFile recordFile) {
            runOnUi(() -> {
                aggregator.uploadedCount++;
                renderSyncStatus();
            });
        }

        @Override
        public void onFinish(@NonNull List<RecordFile> succeedRecords,
                             @NonNull List<RecordFile> failedRecords) {
            runOnUi(() -> {
                aggregator.markFinished();
                lastSyncTime = System.currentTimeMillis();
                appendLog(getString(R.string.native_cloud_log_upload_finish,
                        succeedRecords.size(), failedRecords.size()));
                renderSyncStatus();
            });
        }

        @Override
        public void onError(@Nullable Integer errorCode, @Nullable String errorMsg) {
            runOnUi(() -> {
                aggregator.markError(errorCode);
                appendLog(getString(R.string.native_cloud_log_upload_global_error,
                        String.valueOf(errorCode), errorMsg == null ? "" : errorMsg));
                renderSyncStatus();
            });
        }
    }

    /**
     * 下载回调 → 仅计数与日志。
     * <p>
     * 注意：{@code downloadError} / {@code downloadSuccess} 语义上仍是「下载中」，
     * 其错误码无效；真正的结束以 {@code onFinish} 为准。
     */
    private class AggregatingDownloadListener implements DownloadListener {

        @Override
        public void onStart() {
            runOnUi(() -> appendLog(getString(R.string.native_cloud_log_download_start)));
        }

        @Override
        public void onDownloadTaskSizeMapReady(@NonNull Map<String, Long> taskSizeMap) {
            runOnUi(() -> appendLog(getString(R.string.native_cloud_log_download_task_ready,
                    taskSizeMap.size())));
        }

        @Override
        public void onPause() {
            runOnUi(() -> appendLog(getString(R.string.native_cloud_log_download_pause)));
        }

        @Override
        public void downloading(@NonNull RecordFile recordFile, long downloadedBytes,
                                long totalBytes, int progressPercent) {
            runOnUi(() -> appendLog(getString(R.string.native_cloud_log_downloading,
                    downloadedBytes, totalBytes, progressPercent)));
        }

        @Override
        public void downloadError(@NonNull RecordFile recordFile, @NonNull String errorCode,
                                  @NonNull String errorMsg) {
            runOnUi(() -> {
                aggregator.downloadFailedCount++;
                appendLog(getString(R.string.native_cloud_log_download_error, errorCode, errorMsg));
                renderSyncStatus();
            });
        }

        @Override
        public void downloadErrorBatch(@NonNull List<RecordFile> recordFiles, int errorCode,
                                       @NonNull String errorMsg) {
            runOnUi(() -> {
                aggregator.downloadFailedCount += recordFiles.size();
                appendLog(getString(R.string.native_cloud_log_download_error_batch,
                        recordFiles.size(), errorCode));
                renderSyncStatus();
            });
        }

        @Override
        public void downloadSuccess(@NonNull RecordFile recordFile) {
            runOnUi(() -> {
                aggregator.downloadedCount++;
                renderSyncStatus();
            });
        }

        @Override
        public void onFinish(@NonNull List<RecordFile> succeedRecords,
                             @NonNull List<RecordFile> failedRecords) {
            runOnUi(() -> appendLog(getString(R.string.native_cloud_log_download_finish,
                    succeedRecords.size(), failedRecords.size())));
        }
    }
}
