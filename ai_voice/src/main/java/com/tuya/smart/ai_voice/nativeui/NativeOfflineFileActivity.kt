package com.tuya.smart.ai_voice.nativeui

import android.view.View
import android.widget.TextView
import com.thingclips.smart.earphone.enhance.api.bean.DeviceOfflineFileStatus
import com.thingclips.smart.earphone.enhance.api.bean.FileDigest
import com.thingclips.smart.earphone.enhance.api.bean.OfflineFilesResponse
import com.thingclips.smart.earphone.enhance.api.listener.IOfflineFilesProgress
import com.thingclips.smart.earphone.enhance.api.listener.IRecordCallBack
import com.tuya.smart.ai_voice.R
import com.tuya.smart.ai_voice.databinding.ActivityNativeOfflineFileBinding
import com.tuya.smart.ai_voice.nativeui.base.NativeDemoBaseActivity

/**
 * 模块 4 · 离线文件传输演示页。
 * 
 * 
 * 把**录音卡片**本地缓存的录音文件下载到手机，支持 BLE 与 AP（Wi-Fi 快传）两种通道。
 * 
 * 
 * **本模块只支持录音卡片**（`product_type == "card"`，即
 * `AudioDeviceCategory.CATEGORY_CARD`）：卡片没有屏幕也不常连手机，
 * 录下的音频先存在设备上、之后再回传；耳机类设备走实时链路，不产生设备侧缓存文件。
 * 故本页的设备下拉**已过滤掉手机与耳机**，见 [.acceptDevice]。
 * 页面按三步手动串联，便于逐步观察：
 * 
 *  1. 选设备 → [.queryStatus] 调 `getDeviceOfflineFileStatus` 查看待传文件数与会话
 *  1. [.loadOfflineFile] 调 `loadOfflineFile` 发起或续传（`sessionId` 非 0 即续传）
 *  1. [.switchChannel] 调 `switchModeLoadOfflineFile` 在 AP / BLE 间切换
 * 
 * **AI 笔记中这一步是自动的**：录音结束或首页可见时遍历在线卡片设备，
 * 逐个 `getDeviceOfflineFileStatus`，发现 `response.total > 0` 就直接以
 * `channel=BLE、sessionId=已有值或 0` 发起下载，全程无用户操作。
 * 
 * 
 * **进度回调有两条路径，本页两条都演示：**
 * 
 *  * `registerFileProgressCallback` 注册的全局回调 —— 主用。建议在 App 级注册一次，
 * 页面进出不影响，避免切页丢进度
 *  * `loadOfflineFile` / `switchModeLoadOfflineFile` 传入的回调 —— 只用来接
 * `onSuccess(sessionId)` / `onError` 的调用结果
 * 
 * 两处 `onProgress` 收到的数据一致，会重复回调，生产环境择一即可。
 * 
 * 
 * **AP 建链状态机的数据源是进度回调，不是接口返回值**：
 * `response.apConnectState`（0 热点未开启 / 1 已开启 / 2 已连接）与
 * `DeviceOfflineFileStatus.errorCode` 共同驱动 UI，见 [.renderProgress]。
 * 
 * 
 * 本页有意**不**实现的两点（属于产品交互，各家 App 自行决定）：
 * 
 *  * 建链失败重试计数 —— AI 笔记的策略是最多重试 3 次 `switchMode(AP)`，超限弹错误框
 *  * 退出拦截 —— 建链中 / AP 快传中返回时应弹确认框，确认后 `switchMode(BLE)`。
 * 生产环境若停留在 AP 模式，建议退出前切回 BLE，否则设备热点会一直占用
 * 
 */
class NativeOfflineFileActivity : NativeDemoBaseActivity() {
    /** 内容布局绑定。  */
    private lateinit var binding: ActivityNativeOfflineFileBinding


    /** 全局进度回调，用字段持有，`unRegisterFileProgressCallback` 时传同一引用。  */
    private var globalProgressCallback: IOfflineFilesProgress? = null
    /** 最近一次查询到的会话 ID，非 0 时 `loadOfflineFile` 表示续传。  */
    private var sessionId: Long = SESSION_ID_NEW_TASK

    /** 当前通道，由进度回调的 `response.channel` 更新。  */
    private var currentChannel: Int = CHANNEL_BLE

    override val contentLayoutId: Int
        get() = R.layout.activity_native_offline_file

    override val titleResId: Int
        get() = R.string.native_offline_title

    /**
     * 不列出「手机」。手机上不存在设备侧缓存，本模块对它无意义。
     */
    override fun includePhoneDevice(): Boolean {
        return false
    }

    /**
     * **只列出录音卡片。**
     * 
     * 
     * 离线文件传输是卡片类设备独有的能力——它没有屏幕也不常连手机，录下的音频先存在本机，
     * 之后再回传；耳机类设备走的是实时链路，不产生设备侧缓存文件。
     * 
     * 
     * 判据是产品配置里的 `product_type == "card"`，与
     * `AudioDeviceCategory.CATEGORY_CARD` 一致，解析逻辑见基类
     * `getAudioProductType`。
     * 
     * 
     * 直接过滤而不是选中后再提示「该设备不支持」——用户根本选不到，少一次试错。
     */
    override fun acceptDevice(productType: String): Boolean {
        return DeviceItem.TYPE_CARD == productType
    }

    override fun onContentViewCreated() {
        binding = ActivityNativeOfflineFileBinding.bind(contentView())
        setupDeviceSpinner(binding.spDevice)

        binding.btnQueryStatus.setOnClickListener(View.OnClickListener { v: View? -> queryStatus() })
        binding.btnLoad.setOnClickListener(View.OnClickListener { v: View? -> loadOfflineFile() })
        binding.btnSwitchAp.setOnClickListener(View.OnClickListener { v: View? ->
            switchChannel(
                CHANNEL_AP
            )
        })
        binding.btnSwitchBle.setOnClickListener(View.OnClickListener { v: View? ->
            switchChannel(
                CHANNEL_BLE
            )
        })

        applyNoDeviceState()
    }

    /**
     * 当前家庭没有录音卡片时，禁用全部操作并给出说明。
     * 
     * 
     * 不这么做的话，按钮点下去只会得到一个底层错误码，使用者无从判断是环境问题还是接入问题。
     */
    private fun applyNoDeviceState() {
        if (!hasNoUsableDevice()) {
            return
        }
        binding.tvStatus.setText(R.string.native_offline_no_card_device)
        binding.btnQueryStatus.setEnabled(false)
        binding.btnLoad.setEnabled(false)
        binding.btnSwitchAp.setEnabled(false)
        binding.btnSwitchBle.setEnabled(false)
    }

    // ===================== 事件监听 =====================
    override fun registerListeners() {
        globalProgressCallback = object : IOfflineFilesProgress {
            override fun onProgress(progress: DeviceOfflineFileStatus) {
                runOnUi { renderProgress(progress) }
            }

            override fun onSuccess(sessionId: Long) {
            }

            override fun onError(code: String?, error: String?) {
                toastError(code, error)
            }
        }
        globalProgressCallback?.let { manager.registerFileProgressCallback(it) }
    }

    override fun unregisterListeners() {
        if (globalProgressCallback != null) {
            globalProgressCallback?.let { manager.unRegisterFileProgressCallback(it) }
            globalProgressCallback = null
        }
    }

    // ===================== 步骤 1：查询状态 =====================
    /**
     * 查询设备的离线文件列表与下载会话状态。
     * 
     * 
     * 关键判据：`response.total > 0` 表示设备上有待传文件，可以发起下载；
     * `status == 1` 表示已有任务在跑，此时应续传而非新建。
     */
    private fun queryStatus() {
        if (hasNoUsableDevice()) {
            toast(getString(R.string.native_offline_no_card_device))
            return
        }
        val deviceId = currentDeviceId()
        manager.getDeviceOfflineFileStatus(
            deviceId ?: DEVICE_ID_PHONE,
            object : IRecordCallBack<DeviceOfflineFileStatus?> {
                override fun onSuccess(result: DeviceOfflineFileStatus?) {
                    runOnUi { renderStatus(result) }
                }

                override fun onError(code: String?, error: String?) {
                    runOnUi {
                        toast(getString(R.string.native_offline_log_query_fail, code, error))
                    }
                }
            })
    }

    /**
     * 渲染查询结果，并记录 `sessionId` 供续传使用。
     * 
     * @param result 查询结果，可能为 null
     */
    private fun renderStatus(result: DeviceOfflineFileStatus?) {
        if (result == null) {
            binding.tvStatus.setText(R.string.native_offline_status_empty)
            return
        }
        sessionId = if (result.sessionId == null) SESSION_ID_NEW_TASK else result.sessionId
        val resp = result.response
        val total = if (resp == null || resp.total == null) 0 else resp.total
        val done = if (resp == null || resp.size == null) 0 else resp.size

        val text = getString(
            R.string.native_offline_status_format,
            downloadStatusName(result.status),
            sessionId,
            total,
            done,
            if (result.errorCode == null) 0 else result.errorCode
        )
        binding.tvStatus.setText(text)

        // total > 0 才有东西可传；自动流程正是以此为触发条件
        binding.btnLoad.setEnabled(total > 0)
        if (total == 0) {
            toast(getString(R.string.native_offline_toast_no_file))
        }
    }

    // ===================== 步骤 2：发起 / 续传 =====================
    /**
     * 发起或续传离线文件下载。
     * 
     * 
     * `sessionId` 为 0 时新建任务，非 0 时续传上次会话。这里传入的回调只用于接收
     * 启动结果；真正的进度以全局回调为准（两者的 `onProgress` 数据一致）。
     */
    private fun loadOfflineFile() {
        val deviceId = currentDeviceId()
        manager.loadOfflineFile(deviceId ?: DEVICE_ID_PHONE, CHANNEL_BLE, sessionId, object : IOfflineFilesProgress {
            override fun onProgress(progress: DeviceOfflineFileStatus) {
                // 与全局回调重复，生产环境择一即可，这里仅记录一条以示存在
            }

            override fun onSuccess(newSessionId: Long) {
                runOnUi {
                    sessionId = newSessionId
                }
            }

            override fun onError(code: String?, error: String?) {
                runOnUi {
                    toast(getString(R.string.native_offline_log_load_fail, code, error))
                }
            }
        })
    }

    // ===================== 步骤 3：通道切换 =====================
    /**
     * 切换传输通道。切到 AP 后进入建链流程，建链进度只能从进度回调的
     * `apConnectState` / `errorCode` 读取，接口本身不返回建链状态。
     * 
     * @param channel [.CHANNEL_AP] 或 [.CHANNEL_BLE]
     */
    private fun switchChannel(channel: Int) {
        val deviceId = currentDeviceId()
        manager.switchModeLoadOfflineFile(deviceId ?: DEVICE_ID_PHONE, channel, object : IOfflineFilesProgress {
            override fun onProgress(progress: DeviceOfflineFileStatus) {
                // 同上，以全局回调为准
            }

            override fun onSuccess(newSessionId: Long) {
            }

            override fun onError(code: String?, error: String?) {
                toastError(code, error)
            }
        })
    }

    // ===================== 进度与建链状态渲染 =====================
    /**
     * 渲染全局进度回调。这是 AP 建链状态机的唯一数据源。
     * 
     * @param status 进度事件
     */
    private fun renderProgress(status: DeviceOfflineFileStatus?) {
        if (status == null) return
        val resp = status.response
        if (resp != null && resp.channel != null) {
            currentChannel = resp.channel
        }
        val apState =
            if (resp == null || resp.apConnectState == null) AP_PENDING else resp.apConnectState
        val errorCode = if (status.errorCode == null) 0 else status.errorCode

        binding.tvChannel.setText(
            getString(
                R.string.native_offline_channel_format,
                channelName(currentChannel), apStateName(apState)
            )
        )

        val cur: FileDigest? = resp?.curFile
        if (cur == null) return
        val curName = if (cur == null || cur.fileName == null)
            getString(R.string.native_offline_no_current_file)
        else
            cur.fileName
        val curProgress = if (cur == null || cur.progress == null) 0.0 else cur.progress

        binding.tvProgress.setText(
            getString(
                R.string.native_offline_progress_format,
                downloadStatusName(status.status),
                if (resp == null || resp.size == null) 0 else resp.size,
                if (resp == null || resp.total == null) 0 else resp.total,
                curName,
                curProgress,
                if (resp == null || resp.speed == null) 0.0 else resp.speed,
                if (resp == null || resp.remainingDownloadTime == null) 0 else resp.remainingDownloadTime,
                waitingCount(resp),
                failedCount(resp)
            )
        )

        if (errorCode != 0) {
        }
    }

    /**
     * AP 建链错误码归类：Wi-Fi 打开失败与热点连接失败要给不同引导。
     * 
     * @param errorCode 错误码
     * @return 可读提示
     */
    private fun apErrorHint(errorCode: Int): String {
        if (errorCode == ERR_WIFI_OPEN) {
            return getString(R.string.native_offline_err_wifi_open)
        }
        if (ERR_AP_CONNECT.contains(errorCode)) {
            return getString(R.string.native_offline_err_ap_connect)
        }
        return getString(R.string.native_offline_err_other)
    }

    private fun waitingCount(resp: OfflineFilesResponse?): Int {
        return if (resp == null || resp.files_waiting == null) 0 else resp.files_waiting.size
    }

    private fun failedCount(resp: OfflineFilesResponse?): Int {
        return if (resp == null || resp.files_failed == null) 0 else resp.files_failed.size
    }

    private fun downloadStatusName(status: Int?): String {
        if (status == null) return getString(R.string.native_status_unknown)
        when (status) {
            DOWNLOAD_NOT_STARTED -> return getString(R.string.native_offline_state_not_started)
            DOWNLOADING -> return getString(R.string.native_offline_state_downloading)
            DOWNLOAD_FINISHED -> return getString(R.string.native_offline_state_finished)
            else -> return getString(R.string.native_status_unknown)
        }
    }

    private fun channelName(channel: Int): String {
        when (channel) {
            CHANNEL_BLE -> return getString(R.string.native_offline_channel_ble)
            CHANNEL_AP -> return getString(R.string.native_offline_channel_ap)
            CHANNEL_UNSPECIFIED -> return getString(R.string.native_channel_unspecified)
            else -> return getString(R.string.native_channel_unspecified)
        }
    }

    private fun apStateName(apState: Int): String {
        when (apState) {
            AP_OPENED -> return getString(R.string.native_offline_ap_opened)
            AP_CONNECTED -> return getString(R.string.native_offline_ap_connected)
            AP_PENDING -> return getString(R.string.native_offline_ap_pending)
            else -> return getString(R.string.native_offline_ap_pending)
        }
    }

    override fun onDeviceSelected(item: DeviceItem) {
        super.onDeviceSelected(item)
        // 换设备后原会话失效，重置为新建任务
        sessionId = SESSION_ID_NEW_TASK
        binding.tvStatus.setText(R.string.native_offline_status_empty)
        binding.btnLoad.setEnabled(false)
    }

    companion object {
        // ===== 下载通道（loadOfflineFile / switchModeLoadOfflineFile 的 channel 入参）=====
        /** 未指定通道。  */
        private const val CHANNEL_UNSPECIFIED = 0

        /** 蓝牙 BLE 传输。  */
        private const val CHANNEL_BLE = 1

        /** Wi-Fi AP 快传。  */
        private const val CHANNEL_AP = 2

        /** `sessionId` 传 0 表示发起新任务，非 0 表示续传已有任务。  */
        private const val SESSION_ID_NEW_TASK = 0L

        // ===== DeviceOfflineFileStatus.status =====
        /** 未开始下载。  */
        private const val DOWNLOAD_NOT_STARTED = 0

        /** 下载中。  */
        private const val DOWNLOADING = 1

        /** 下载结束。  */
        private const val DOWNLOAD_FINISHED = 2

        // ===== OfflineFilesResponse.apConnectState =====
        /** 热点未开启（初始态）。  */
        private const val AP_PENDING = 0

        /** 设备热点已开启，手机尚未连上。  */
        private const val AP_OPENED = 1

        /** 手机已连上设备热点，可以走 AP 快传。  */
        private const val AP_CONNECTED = 2

        // ===== AP 建链错误码 =====
        /** Wi-Fi 打开失败。  */
        private const val ERR_WIFI_OPEN = 10091

        /** 热点连接失败类错误码。  */
        private val ERR_AP_CONNECT =
            mutableListOf<Int?>(10092, 10096, 10300, 10301, 10302, 10303, 10304, 10305)
    }
}
