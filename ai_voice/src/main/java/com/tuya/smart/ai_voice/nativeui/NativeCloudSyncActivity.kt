package com.tuya.smart.ai_voice.nativeui

import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import com.thingclips.smart.ai.audio.sync.api.DownloadListener
import com.thingclips.smart.ai.audio.sync.api.UploadListener
import com.thingclips.smart.ai.audio.sync.api.ttt.SyncObserver
import com.thingclips.smart.ai.db.entity.RecordFile
import com.thingclips.smart.android.network.Business
import com.thingclips.smart.android.network.http.BusinessResponse
import com.thingclips.smart.earphone.enhance.api.bean.CloudSyncSwitchParam
import com.thingclips.smart.earphone.enhance.api.listener.CloudSyncRefreshType
import com.thingclips.smart.earphone.enhance.api.listener.ICloudSwitchListener
import com.thingclips.smart.earphone.enhance.api.listener.ICloudSyncSwitchCallBack
import com.thingclips.smart.plugin.tuniaudiodetectmanager.bean.nativeapi.CloudSyncStatusInfo
import com.thingclips.smart.sdk.api.IResultCallback
import com.tuya.smart.ai_voice.R
import com.tuya.smart.ai_voice.databinding.ActivityNativeCloudSyncBinding
import com.tuya.smart.ai_voice.nativeui.base.NativeDemoBaseActivity
import com.tuya.smart.ai_voice.nativeui.business.CloudSyncSwitchBusiness
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模块 6 · 云同步演示页。
 * 
 * <h3>状态从哪来</h3>
 * 云同步的聚合状态有**两个来源，缺一不可**：
 * 
 *  * **快照** —— `getCloudSyncSwitchStatus` 回调里的 [CloudSyncStatusInfo]。
 * 进页必须取一次，否则进页时若正有同步在跑，界面会一直显示错误状态直到下一个事件到达。
 * 这与模块 5 的 `getAudioImportStatus`、模块 8 的 `getFileMergeStatus` 是同一套模式
 *  * **增量** —— [SyncObserver] 的上传回调，由 [SyncAggregator] 收敛成同一套状态
 * 
 * 
 * <h3>状态取值</h3>
 * <pre>
 * -1 未开启   开关关闭时恒为此值，优先级最高
 * 0 同步成功  同时涵盖「从未同步过」，要区分用 modifyTime <= 0 判断
 * 1 同步中
 * 2 同步失败  此时看 errorCode
 * </pre>
 * 
 * **状态只由上传决定**，下载的成功 / 失败不影响它，计数仅用于展示。
 * 
 * <h3>三类结果信号，别混用</h3>
 * `syncNoteRecord` 的 `onSuccess` 只表示任务已启动，产出散落在三处：
 * <pre>
 * 本地录音有没有存上云   SyncObserver.UploadListener      → 本页的聚合状态
 * 云端记录有没有拉回本地  IRecordFileUpdateCallback         → 列表页全量重拉
 * .onRecordListSyncSuccess()
 * .onRecordOperate(ADD)
 * 某条录音的音频下完没有  SyncObserver.DownloadListener     → 单条状态
 * .onFinish
 * </pre>
 * 
 * 第二条是「拿到云端数据」的唯一信号，监听注册在 [NativeRecordListActivity]，本页不处理。
 * 注意**记录与音频不是一回事**：记录早就同步好了，音频可能还没下、
 * 甚至根本不下——要等用户点播放才按需拉（`syncDownloadNoteAudio`）。
 * 
 * <h3>开关写入不是 SDK 能力</h3>
 * SDK **只有查询没有写入**——开关的写入属于业务云能力，需自行调 atop，
 * 见 [CloudSyncSwitchBusiness]。
 * 
 * <h3>错误码</h3>
 * `status == 2` 时的 `errorCode`：`10001` 网络中断 /
 * `10002` 仅 Wi-Fi 下 Wi-Fi 断开 / `10003` 服务器繁忙 / `20001` 本月上限。
 * 
 * 
 * 其中 `10002` 语义上不是失败而是「等待 Wi-Fi」，本页据此把标题显示为「同步已暂停」
 * 并引导用户改设置，而不是提示重试。
 * 
 * 
 * 另有 `10213` 出现在 `syncNoteRecord` 的 `onError` 里，
 * 表示「重复发起同步」，**它不是失败**，而是「正在同步」的确定信号。
 * 
 * 
 * 单条录音的按需下载（`syncDownloadNoteAudio`）属于「对某条录音的操作」，
 * 在 [NativeRecordDetailActivity] 的更多操作里，不在本页。
 */
class NativeCloudSyncActivity : NativeDemoBaseActivity() {
    /** 内容布局绑定。  */
    private lateinit var binding: ActivityNativeCloudSyncBinding


    /** 云同步开关变更监听，用字段持有，remove 时传同一引用。  */
    private var switchListener: ICloudSwitchListener<CloudSyncSwitchParam?, Long?>? = null

    /** 上传/下载观察者，用字段持有，remove 时传同一引用。  */
    private var syncObserver: SyncObserver? = null

    private val aggregator = SyncAggregator()
    private val switchBusiness = CloudSyncSwitchBusiness()
    /** 回写开关勾选态时置 true，抑制联动回调，避免把「渲染」当成「用户操作」。  */
    private var applyingSwitchState = false

    /** 上一次已知的 SDK 侧开关状态，保存失败时据此回滚界面。  */
    private var knownEnabled = false

    /** 上一次已知的 SDK 侧「仅 Wi-Fi」设置，保存失败时据此回滚界面。  */
    private var knownWifiOnly = false

    /** 最近一次同步执行时间，来自快照，用于「最近同步：xxx」文案。  */
    private var lastSyncTime: Long = NEVER_SYNCED

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override val contentLayoutId: Int
        get() = R.layout.activity_native_cloud_sync

    override val titleResId: Int
        get() = R.string.native_cloud_title

    override fun onContentViewCreated() {
        binding = ActivityNativeCloudSyncBinding.bind(contentView())
        binding.btnQuerySwitch.setOnClickListener { querySwitchStatus() }
        binding.btnSyncNow.setOnClickListener { syncNow() }

        binding.cbSyncEnabled.setOnCheckedChangeListener { _, checked ->
            if (!applyingSwitchState) {
                onEnabledToggled(checked)
            }
        }
        binding.cbWifiOnly.setOnCheckedChangeListener { _, checked ->
            if (!applyingSwitchState) {
                // 全量提交：改 syncType 也必须带上当前 enabled
                saveSwitch(binding.cbSyncEnabled.isChecked(), checked)
            }
        }

        querySwitchStatus()
        renderSyncStatus()
    }

    // ===================== 事件监听 =====================
    override fun registerListeners() {
        switchListener = object : ICloudSwitchListener<CloudSyncSwitchParam?, Long?> {
            override fun onRefreshSwitchState(
                param: CloudSyncSwitchParam?, time: Long?,
                type: CloudSyncRefreshType
            ) {
                runOnUi {
                    renderSwitchState(param, time)
                    // 开关一旦关闭，聚合状态立即置「未开启」，不等上传回调
                    if (param != null && !param.enabled) {
                        aggregator.markDisabled()
                        renderSyncStatus()
                    }
                }
            }

            override fun onError(code: String?, error: String?) {
                toastError(code, error)
            }
        }
        switchListener?.let { manager.addCloudSwitchListener(it) }

        syncObserver = SyncObserver(AggregatingDownloadListener(), AggregatingUploadListener())
        syncObserver?.let { manager.addAudioSyncObserver(it) }
    }

    override fun unregisterListeners() {
        if (switchListener != null) {
            switchListener?.let { manager.removeCloudSwitchListener(it) }
            switchListener = null
        }
        if (syncObserver != null) {
            syncObserver?.let { manager.removeAudioSyncObserver(it) }
            syncObserver = null
        }
        switchBusiness.onDestroy()
    }

    // ===================== 开关查询 =====================
    /**
     * 查询云同步开关，并**一并取回聚合状态快照**。
     * 
     * 
     * 快照是进页时状态正确的唯一保证：[SyncObserver] 只在同步发生时才回调，
     * 不取快照就无法知道「进页那一刻是否正在同步」。
     */
    private fun querySwitchStatus() {
        manager.getCloudSyncSwitchStatus(
            object : ICloudSyncSwitchCallBack<CloudSyncSwitchParam?, CloudSyncStatusInfo?> {
                override fun onSuccess(
                    param: CloudSyncSwitchParam?,
                    statusInfo: CloudSyncStatusInfo?
                ) {
                    runOnUi {
                        renderSwitchState(
                            param,
                            if (statusInfo == null) null else statusInfo.modifyTime
                        )
                        applySnapshot(statusInfo)
                    }
                }

                override fun onError(code: String?, error: String?) {
                    toastError(code, error)
                }
            })
    }

    /**
     * 用快照重置聚合状态。
     * 
     * @param statusInfo 快照，可能为 null
     */
    private fun applySnapshot(statusInfo: CloudSyncStatusInfo?) {
        if (statusInfo == null) return
        lastSyncTime = statusInfo.modifyTime
        aggregator.applySnapshot(statusInfo.status, statusInfo.errorCode)
        renderSyncStatus()
    }

    /**
     * 把 SDK 返回的开关状态回写到界面，并记为「已知的 SDK 状态」。
     * 
     * 
     * 该状态是保存失败时的回滚基准，见 [.saveSwitch]。
     * 
     * @param param 开关参数，可能为 null
     * @param time  最近同步时间，可能为 null
     */
    private fun renderSwitchState(param: CloudSyncSwitchParam?, time: Long?) {
        if (time != null) {
            lastSyncTime = time
        }
        if (param == null) {
            binding.tvSwitchStatus.setText(R.string.native_cloud_switch_empty)
            return
        }
        knownEnabled = param.enabled
        knownWifiOnly = param.syncType != null && param.syncType == SYNC_TYPE_WIFI_ONLY
        applySwitchUi(knownEnabled, knownWifiOnly)

        binding.tvSwitchStatus.setText(
            getString(
                R.string.native_cloud_switch_format,
                if (param.enabled)
                    getString(R.string.native_cloud_switch_on)
                else
                    getString(R.string.native_cloud_switch_off),
                syncTypeName(param.syncType),
                formatTime(param.modifyTime),
                formatTime(lastSyncTime)
            )
        )
    }

    /**
     * 回写勾选态，期间抑制联动回调，避免把「渲染」当成「用户操作」而触发保存。
     * 
     * @param enabled  云同步开关
     * @param wifiOnly 仅 Wi-Fi 同步
     */
    private fun applySwitchUi(enabled: Boolean, wifiOnly: Boolean) {
        applyingSwitchState = true
        binding.cbSyncEnabled.setChecked(enabled)
        binding.cbWifiOnly.setChecked(wifiOnly)
        applyingSwitchState = false
    }

    private fun syncTypeName(syncType: Int?): String {
        if (syncType == null) return getString(R.string.native_status_unknown)
        when (syncType) {
            SYNC_TYPE_ALL_NETWORK -> return getString(R.string.native_cloud_sync_type_all)
            SYNC_TYPE_WIFI_ONLY -> return getString(R.string.native_cloud_sync_type_wifi)
            else -> return getString(R.string.native_status_unknown)
        }
    }

    // ===================== 开关写入 =====================
    /**
     * 用户切换云同步总开关，直接保存，不做二次确认。
     * 
     * 
     * 与 AI 笔记的两处差异，都是 Demo 为了少绕弯路做的简化：
     * 
     *  * 关闭时不弹确认框。**生产环境建议保留提示**——关闭云同步后
     * 「卸载 App 会丢录音」这个后果对用户不可见
     *  * 开启时不强制把 `syncType` 设为「仅 Wi-Fi」，
     * 保留用户当前选择，避免两个开关互相干扰
     * 
     * 
     * @param checked 目标状态
     */
    private fun onEnabledToggled(checked: Boolean) {
        saveSwitch(checked, binding.cbWifiOnly.isChecked())
    }

    /**
     * 保存开关。**全量提交**：两个字段每次都要一起传。
     * 
     * 
     * **保存成功后不要立刻回查 `getCloudSyncSwitchStatus`。**
     * 该接口读的是 SDK 的本地缓存，缓存要等 MQTT 推送或后台校验才更新——
     * 保存刚成功时它仍是旧值，回查会把用户刚做的选择覆盖回去，
     * 表现为「点了开关又自己弹回原状」。
     * 
     * 
     * 正确做法：成功后保持乐观更新的界面，等 [ICloudSwitchListener.onRefreshSwitchState]
     * 到达时自然校正；失败则回滚到 [.knownEnabled] / [.knownWifiOnly]
     * 记录的上一次已知状态。想立刻看 SDK 侧的值，手动点「查询开关状态与同步快照」。
     * 
     * @param enabled  是否开启云同步
     * @param wifiOnly 是否仅 Wi-Fi 同步
     */
    private fun saveSwitch(enabled: Boolean, wifiOnly: Boolean) {
        val syncType = if (wifiOnly)
            CloudSyncSwitchBusiness.SYNC_TYPE_WIFI
        else
            CloudSyncSwitchBusiness.SYNC_TYPE_ALL
        switchBusiness.saveCloudSyncSwitch(
            enabled,
            syncType,
            object : Business.ResultListener<Boolean?> {
                override fun onSuccess(
                    response: BusinessResponse?,
                    result: Boolean?,
                    apiName: String?
                ) {
                    runOnUi {
                        knownEnabled = enabled
                        knownWifiOnly = wifiOnly
                        if (!enabled) {
                            aggregator.markDisabled()
                        } else if (aggregator.status == SYNC_DISABLED) {
                            aggregator.applySnapshot(SYNC_SUCCESS, 0)
                        }
                        renderSyncStatus()
                    }
                }

                override fun onFailure(
                    response: BusinessResponse?,
                    result: Boolean?,
                    apiName: String?
                ) {
                    runOnUi {
                        val code = if (response == null) "-" else response.getErrorCode()
                        val msg = if (response == null) "" else response.getErrorMsg()
                        toast(getString(R.string.native_cloud_log_save_fail, code, msg))
                        // 保存失败，回滚到上一次已知的 SDK 状态
                        applySwitchUi(knownEnabled, knownWifiOnly)
                    }
                }
            })
    }

    // ===================== 触发同步 =====================
    /**
     * 触发一次同步。**双向**：上传本地 Note 与音频，同时下载云端 Note。
     * 
     * 
     * **本页做成按钮只是为了便于观察，不是推荐的交互。** AI 笔记的做法是不给用户
     * 「立即同步」入口，改在两个时机各自动调一次：
     * 
     *  * 首页首次加载 —— 无条件调，且早于 `getCloudSyncSwitchStatus`
     *  * 录音列表下拉刷新 —— 需先判「当前不在同步中」且「上次调用未在进行中」
     * 
     * 同步是后台行为、用户没有显式发起，因此失败**不必弹提示**（本页为演示仍然提示）。
     * 
     * 
     * 双重防重：本地状态先拦一道；即便漏过，底层也会以 `onError("10213")` 拒绝——
     * 那个错误码**不是失败**，而是「正在同步中」的确定信号，据此把本地状态纠正为「同步中」。
     * 
     * 
     * **`onSuccess` 不代表数据已同步**，只表示任务已启动。真正的结果分三路，
     * 见类注释「三类结果信号」。
     */
    private fun syncNow() {
        if (aggregator.status == SYNC_DISABLED) {
            toast(getString(R.string.native_cloud_toast_disabled))
            return
        }
        if (aggregator.status == SYNC_SYNCING) {
            toast(getString(R.string.native_cloud_toast_syncing))
            return
        }
        manager.syncNoteRecord(object : IResultCallback {
            override fun onSuccess() {
            }

            override fun onError(code: String?, error: String?) {
                runOnUi {
                    if (CODE_ALREADY_SYNCING == code) {
                        // 底层已在同步，纠正本地状态而非报错
                        aggregator.markSyncing()
                        renderSyncStatus()
                        toast(getString(R.string.native_cloud_toast_syncing))
                        return@runOnUi
                    }
                    toast(getString(R.string.native_cloud_log_sync_fail, code, error))
                }
            }
        })
    }

    // ===================== 聚合状态渲染 =====================
    private fun renderSyncStatus() {
        binding.tvSyncTitle.setText(syncTitle())
        binding.tvSyncTip.setText(syncTip())
        binding.tvSyncStatus.setText(
            getString(
                R.string.native_cloud_sync_format,
                syncStatusName(aggregator.status),
                aggregator.errorCode,
                aggregator.uploadedCount,
                aggregator.uploadFailedCount,
                aggregator.downloadedCount,
                aggregator.downloadFailedCount
            )
        )
    }

    private val isPaused: Boolean
        /**
         * 是否处于「同步已暂停」派生态。
         * 
         * 
         * 判据是错误码 [.ERR_WIFI_ONLY_NO_WIFI]——它精确表示「仅 Wi-Fi 模式下 Wi-Fi 断开」，
         * 这种情况不是失败，而是等待条件满足，应引导用户改设置而非重试。
         * 
         * @return true 表示应显示为「已暂停」而非「同步失败」
         */
        get() = aggregator.status == SYNC_FAILED && aggregator.errorCode == ERR_WIFI_ONLY_NO_WIFI

    private fun syncTitle(): String {
        if (this.isPaused) return getString(R.string.native_cloud_state_paused)
        return syncStatusName(aggregator.status)
    }

    /** 状态副文案：未开启给引导语，成功给最近同步时间，失败给错误码含义。  */
    private fun syncTip(): String {
        when (aggregator.status) {
            SYNC_DISABLED -> return getString(R.string.native_cloud_tip_disabled)
            SYNC_SUCCESS -> return if (lastSyncTime > NEVER_SYNCED)
                getString(R.string.native_cloud_tip_last_sync, formatTime(lastSyncTime))
            else
                getString(R.string.native_cloud_tip_never_synced)

            SYNC_SYNCING -> return ""
            SYNC_FAILED -> return errorMessage(aggregator.errorCode)
            else -> return ""
        }
    }

    /**
     * 状态错误码转可读提示。
     * 
     * @param errorCode 聚合状态里的错误码
     * @return 可读文案
     */
    private fun errorMessage(errorCode: Int): String {
        when (errorCode) {
            ERR_NETWORK -> return getString(R.string.native_cloud_err_10001)
            ERR_WIFI_ONLY_NO_WIFI -> return getString(R.string.native_cloud_err_10002)
            ERR_TIMEOUT -> return getString(R.string.native_cloud_err_10003)
            ERR_BEYOND_LIMIT -> return getString(R.string.native_cloud_err_20001)
            else -> return getString(R.string.native_cloud_err_unknown, errorCode)
        }
    }

    private fun syncStatusName(status: Int): String {
        when (status) {
            SYNC_DISABLED -> return getString(R.string.native_cloud_state_disabled)
            SYNC_SYNCING -> return getString(R.string.native_cloud_state_syncing)
            SYNC_FAILED -> return getString(R.string.native_cloud_state_failed)
            SYNC_SUCCESS -> return getString(R.string.native_cloud_state_success)
            else -> return getString(R.string.native_cloud_state_success)
        }
    }

    /**
     * 时间戳格式化。`<= 0` 表示「从未发生」。
     * 
     * @param millis 毫秒时间戳
     * @return 可读时间，或占位符
     */
    private fun formatTime(millis: Long): String {
        if (millis <= NEVER_SYNCED) return getString(R.string.native_cloud_time_never)
        return timeFmt.format(Date(millis))
    }

    /**
     * 云同步状态聚合器。
     * 
     * 
     * 把快照与 [UploadListener] 的离散回调收敛成一套可直接绑定 UI 的状态。
     * 
     * 
     * **状态只由上传决定**，下载仅计数。
     */
    private class SyncAggregator {
        var status: Int = SYNC_SUCCESS
        var errorCode: Int = 0
        var uploadedCount: Int = 0
        var uploadFailedCount: Int = 0
        var downloadedCount: Int = 0
        var downloadFailedCount: Int = 0

        /** 用快照重置状态，进页时调用一次。  */
        fun applySnapshot(snapshotStatus: Int, snapshotErrorCode: Int) {
            status = snapshotStatus
            errorCode = snapshotErrorCode
        }

        fun markSyncing() {
            status = SYNC_SYNCING
            errorCode = 0
        }

        /** 批次结束。有个别文件失败不算整体失败，整体失败只由全局异常决定。  */
        fun markFinished() {
            status = SYNC_SUCCESS
            errorCode = 0
        }

        fun markError(code: Int?) {
            status = SYNC_FAILED
            errorCode = if (code == null) 0 else code
        }

        fun markDisabled() {
            status = SYNC_DISABLED
            errorCode = 0
        }

        fun resetCounters() {
            uploadedCount = 0
            uploadFailedCount = 0
            downloadedCount = 0
            downloadFailedCount = 0
        }
    }

    /** 上传回调 → 聚合状态。上传是云同步三态的唯一来源。  */
    private inner class AggregatingUploadListener : UploadListener {
        override fun onStart() {
            runOnUi {
                aggregator.resetCounters()
                aggregator.markSyncing()
                renderSyncStatus()
            }
        }

        override fun onPause() {
        }

        override fun uploading(recordFile: RecordFile, progress: Int) {
            runOnUi {
                aggregator.markSyncing()
                renderSyncStatus()
            }
        }

        override fun uploadError(
            recordFile: RecordFile, errorCode: String,
            errorMsg: String
        ) {
            runOnUi {
                aggregator.uploadFailedCount++
                renderSyncStatus()
            }
        }

        override fun uploadSuccess(recordFile: RecordFile) {
            runOnUi {
                aggregator.uploadedCount++
                renderSyncStatus()
            }
        }

        override fun onFinish(
            succeedRecords: List<RecordFile>,
            failedRecords: List<RecordFile>
        ) {
            runOnUi {
                aggregator.markFinished()
                lastSyncTime = System.currentTimeMillis()
                renderSyncStatus()
            }
        }

        override fun onError(errorCode: Int?, errorMsg: String?) {
            runOnUi {
                aggregator.markError(errorCode)
                renderSyncStatus()
            }
        }
    }

    /**
     * 下载回调 → 仅计数与日志。
     * 
     * 
     * 注意：`downloadError` / `downloadSuccess` 语义上仍是「下载中」，
     * 其错误码无效；真正的结束以 `onFinish` 为准。
     */
    private inner class AggregatingDownloadListener : DownloadListener {
        override fun onStart() {
        }

        override fun onDownloadTaskSizeMapReady(taskSizeMap: Map<String, Long>) {
        }

        override fun onPause() {
        }

        override fun downloading(
            recordFile: RecordFile, downloadedBytes: Long,
            totalBytes: Long, progressPercent: Int
        ) {
        }

        override fun downloadError(
            recordFile: RecordFile, errorCode: String,
            errorMsg: String
        ) {
            runOnUi {
                aggregator.downloadFailedCount++
                renderSyncStatus()
            }
        }

        override fun downloadErrorBatch(
            recordFiles: List<RecordFile>, errorCode: Int,
            errorMsg: String
        ) {
            runOnUi {
                aggregator.downloadFailedCount += recordFiles.size
                renderSyncStatus()
            }
        }

        override fun downloadSuccess(recordFile: RecordFile) {
            runOnUi {
                aggregator.downloadedCount++
                renderSyncStatus()
            }
        }

        override fun onFinish(
            succeedRecords: List<RecordFile>,
            failedRecords: List<RecordFile>
        ) {
        }
    }

    companion object {
        // ===== 聚合状态，取值同 CloudSyncStatusInfo =====
        /** 云同步开关未开启。  */
        private val SYNC_DISABLED = CloudSyncStatusInfo.STATUS_DISABLED

        /** 同步成功，或从未同步过。  */
        private val SYNC_SUCCESS = CloudSyncStatusInfo.STATUS_SUCCESS

        /** 同步中。  */
        private val SYNC_SYNCING = CloudSyncStatusInfo.STATUS_SYNCING

        /** 同步失败。  */
        private val SYNC_FAILED = CloudSyncStatusInfo.STATUS_FAILED

        // ===== CloudSyncSwitchParam.syncType =====
        /** 任意网络下同步。  */
        private const val SYNC_TYPE_ALL_NETWORK = 0

        /** 仅 Wi-Fi 下同步。  */
        private const val SYNC_TYPE_WIFI_ONLY = 1

        // ===== 状态错误码：出现在聚合状态的 errorCode 里 =====
        /** 网络中断。  */
        private const val ERR_NETWORK = 10001

        /** 仅 Wi-Fi 模式下 Wi-Fi 断开，据此判定「同步已暂停」。  */
        private const val ERR_WIFI_ONLY_NO_WIFI = 10002

        /** 同步超时。  */
        private const val ERR_TIMEOUT = 10003

        /** 超出本月同步配额。  */
        private const val ERR_BEYOND_LIMIT = 20001

        /** 接口返回码：重复发起同步。说明底层正在同步中，不是失败。  */
        private const val CODE_ALREADY_SYNCING = "10213"

        /** 「从未同步过」时最近同步时间的取值。  */
        private const val NEVER_SYNCED = 0L
    }
}
