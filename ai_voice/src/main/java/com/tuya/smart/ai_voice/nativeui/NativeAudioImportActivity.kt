package com.tuya.smart.ai_voice.nativeui

import android.content.Intent
import android.view.View
import android.widget.TextView
import com.thingclips.smart.earphone.enhance.api.bean.FailedFileInfo
import com.thingclips.smart.earphone.enhance.api.bean.FileImportStatusEventApp
import com.thingclips.smart.earphone.enhance.api.listener.IAudioImportCallBack
import com.thingclips.smart.earphone.enhance.api.listener.IFileImportStatusListener
import com.tuya.smart.ai_voice.R
import com.tuya.smart.ai_voice.databinding.ActivityNativeAudioImportBinding
import com.tuya.smart.ai_voice.nativeui.base.NativeDemoBaseActivity

/**
 * 模块 5 · 音频导入演示页。
 * 
 * 
 * 把手机本地音频文件导入为录音记录，导入后可像普通录音一样转写、总结。
 * 
 * 
 * **接入必读：`startImport` 会拉起系统文件选择器，宿主 Activity 必须重写
 * `onActivityResult` 并调用 `handleImportActivityResult` 把结果回灌**，
 * 否则用户选完文件后毫无反应——见 [.onActivityResult]。
 * 这一步是 Native 接入独有的，其他容器由宿主代为转交。
 * 
 * 
 * 完整流程（与 AI 笔记一致）：
 * 
 *  1. 进页 `getAudioImportStatus()` 同步取一次状态快照（可能为 null），补上进页前已发生的进度
 *  1. 注册 [IFileImportStatusListener] 接收后续增量状态
 *  1. 点击导入前先判「是否正在导入」，是则拦截提示，避免重复发起
 *  1. `startImport` → 文件选择器 → `handleImportActivityResult` 回灌
 *  1. 状态为「导入完成」且 `failedFiles` 非空时，可 `retryImport` 重试或
 * `cancelRetry` 放弃；导入过程中可 `cancelImport` 取消
 * 
 */
class NativeAudioImportActivity : NativeDemoBaseActivity() {
    /** 内容布局绑定。  */
    private lateinit var binding: ActivityNativeAudioImportBinding


    /** 状态监听，用字段持有；注意 remove 方法是无参的（移除全部）。  */
    private var importStatusListener: IFileImportStatusListener? = null
    /** 当前是否正在导入，用于发起前拦截。  */
    private var importing = false

    override val contentLayoutId: Int
        get() = R.layout.activity_native_audio_import

    override val titleResId: Int
        get() = R.string.native_import_title

    override fun onContentViewCreated() {
        binding = ActivityNativeAudioImportBinding.bind(contentView())
        binding.btnStartImport.setOnClickListener(View.OnClickListener { v: View? -> startImport() })
        binding.btnCancelImport.setOnClickListener(View.OnClickListener { v: View? -> cancelImport() })
        binding.btnRetryImport.setOnClickListener(View.OnClickListener { v: View? -> retryImport() })
        binding.btnCancelRetry.setOnClickListener(View.OnClickListener { v: View? -> cancelRetry() })

        loadStatusSnapshot()
    }

    // ===================== 事件监听 =====================
    override fun registerListeners() {
        importStatusListener = object : IFileImportStatusListener {
            override fun onStatusUpdate(event: FileImportStatusEventApp) {
                runOnUi { renderStatus(event) }
            }
        }
        importStatusListener?.let { manager.addFileImportStatusListener(it) }
    }

    override fun unregisterListeners() {
        if (importStatusListener != null) {
            // 注意：该 remove 无参，会移除全部已注册的导入状态监听
            manager.removeFileImportStatusListener()
            importStatusListener = null
        }
    }

    // ===================== 状态快照 =====================
    /**
     * 取一次导入状态快照。同步返回，可能为 `null`（从未导入过）。
     * 
     * 
     * 只在进页调用一次，后续变化靠 [IFileImportStatusListener] 增量推送。
     */
    private fun loadStatusSnapshot() {
        val snapshot = manager.getAudioImportStatus()
        renderStatus(snapshot)
    }

    // ===================== 导入操作 =====================
    /**
     * 发起导入。会拉起系统文件选择器，结果必须经
     * [.onActivityResult] 回灌，流程才能继续。
     */
    private fun startImport() {
        if (importing) {
            toast(getString(R.string.native_import_toast_importing))
            return
        }
        manager.startImport(object : IAudioImportCallBack {
            override fun onSuccess() {
            }

            override fun onError(code: Int?, error: String?) {
                runOnUi {
                    toast(getString(R.string.native_import_log_start_fail, code.toString(), error))
                }
            }
        })
    }

    /** 取消进行中的导入任务。  */
    private fun cancelImport() {
        manager.cancelImport(simpleCallback("cancelImport"))
    }

    /** 重试导入失败的文件。同样会拉起文件选择器，结果需回灌。  */
    private fun retryImport() {
        manager.retryImport(simpleCallback("retryImport"))
    }

    /** 放弃重试，清理失败列表。  */
    private fun cancelRetry() {
        manager.cancelRetry(simpleCallback("cancelRetry"))
    }

    /**
     * 构造只打日志的导入回调。
     * 
     * @param action 动作名，用于日志区分
     * @return 回调实例
     */
    private fun simpleCallback(action: String?): IAudioImportCallBack {
        return object : IAudioImportCallBack {
            override fun onSuccess() {
            }

            override fun onError(code: Int?, error: String?) {
                toastError(code.toString(), error)
            }
        }
    }

    // ===================== 文件选择器结果回灌 =====================
    /**
     * 把系统文件选择器的结果透传给 SDK。
     * 
     * 
     * **这是接入音频导入的必要环节**：`startImport` / `retryImport` 内部
     * 通过宿主 Activity 拉起选择器，但拿不到 `onActivityResult`，必须由宿主转交。
     * 漏掉这一步的表现是「点了导入、选完文件、没有任何状态事件」。
     * 
     * 
     * 无需判断 requestCode，SDK 内部会自行识别属于自己的请求。
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        manager.onActivityResult(requestCode, resultCode, data)
    }

    // ===================== 渲染 =====================
    /**
     * 渲染导入状态。
     * 
     * @param event 状态事件，可能为 null
     */
    private fun renderStatus(event: FileImportStatusEventApp?) {
        if (event == null) {
            binding.tvImportStatus.setText(R.string.native_import_status_empty)
            binding.tvFailedFiles.setText(R.string.native_import_failed_empty)
            importing = false
            return
        }
        val status = if (event.status == null) IMPORT_NOT_STARTED else event.status
        importing = status == IMPORTING

        val total: Int = event.totalFileCount ?: 0
        val success: Int = event.successCount ?: 0
        val errorCode: Int = event.errorCode ?: 0

        binding.tvImportStatus.setText(
            getString(
                R.string.native_import_status_format,
                importStatusName(status),
                total,
                success,
                percent(success, total),
                errorCode,
                if (errorCode == 0) "-" else taskErrorMessage(errorCode)
            )
        )

        renderFailedFiles(event.failedFiles)

        if (status == SHARE_IMPORT_FAILED) {
            // 分享导入失败：由系统分享入口触发的导入，本 Demo 未注册分享入口，正常不会收到
            toast(taskErrorMessage(errorCode))
        }
    }

    /**
     * 计算导入进度百分比。
     * 
     * @param success 已成功数
     * @param total   文件总数
     * @return 0-100
     */
    private fun percent(success: Int, total: Int): Int {
        return if (total <= 0) 0 else success * 100 / total
    }

    /**
     * 渲染失败文件列表，按失败原因归类展示——同一原因往往对应多个文件，
     * 逐条罗列裸错误码对使用者没有意义。
     * 
     * @param failedFiles 失败文件，可能为 null
     */
    private fun renderFailedFiles(failedFiles: MutableList<FailedFileInfo>?) {
        if (failedFiles == null || failedFiles.isEmpty()) {
            binding.tvFailedFiles.setText(R.string.native_import_failed_empty)
            return
        }
        // 原因 → 文件名列表，用 LinkedHashMap 保持首次出现的顺序
        val grouped: MutableMap<String?, MutableList<String?>?> =
            LinkedHashMap<String?, MutableList<String?>?>()
        for (info in failedFiles) {
            val code = if (info.errorCode == null) 0 else info.errorCode
            val reason = fileErrorMessage(code)
            var names = grouped.get(reason)
            if (names == null) {
                names = ArrayList<String?>()
                grouped.put(reason, names)
            }
            names.add(if (info.fileName == null) "-" else info.fileName)
        }

        val sb = StringBuilder()
        sb.append(getString(R.string.native_import_failed_count, failedFiles.size)).append("\n\n")
        for (entry in grouped.entries) {
            sb.append(entry.key).append('\n')
            for (name in entry.value.orEmpty()) {
                sb.append("  · ").append(name).append('\n')
            }
            sb.append('\n')
        }
        binding.tvFailedFiles.setText(sb.toString().trim { it <= ' ' })
    }

    /**
     * 单个文件的导入失败原因。
     * 
     * 
     * 多个错误码归为同一原因——使用者只需要知道「为什么失败、能不能重试」。
     * 
     * @param errorCode `FailedFileInfo.errorCode`
     * @return 可读原因
     */
    private fun fileErrorMessage(errorCode: Int): String {
        when (errorCode) {
            ERR_FILE_FORMAT, ERR_FILE_CANNOT_DECODE -> return getString(R.string.native_import_err_format)
            ERR_FILE_NOT_FOUND -> return getString(R.string.native_import_err_file_not_found)
            ERR_FILE_ATTR_CHANGED -> return getString(R.string.native_import_err_attr_changed)
            ERR_FILE_TOO_LONG -> return getString(R.string.native_import_err_too_long)
            ERR_FILE_DECODE_INTERRUPTED, ERR_FILE_IMPORT_INTERRUPTED -> return getString(R.string.native_import_err_interrupted)
            ERR_FILE_SDK_INIT_FAILED, ERR_FILE_DECODE_START_FAILED, ERR_FILE_MP3_CREATE_FAILED -> return getString(
                R.string.native_import_err_busy
            )

            else -> return getString(R.string.native_import_err_unknown, errorCode)
        }
    }

    /**
     * 整个导入任务失败的原因（`FileImportStatusEventApp.errorCode`）。
     * 
     * @param errorCode 任务级错误码
     * @return 可读原因
     */
    private fun taskErrorMessage(errorCode: Int): String {
        when (errorCode) {
            ERR_TASK_FILE_COUNT_EXCEEDED -> return getString(R.string.native_import_err_file_limit)
            ERR_TASK_FILE_TOO_LARGE -> return getString(R.string.native_import_err_file_too_large)
            ERR_TASK_NO_SPACE -> return getString(R.string.native_import_err_no_space)
            ERR_TASK_ALREADY_IMPORTING -> return getString(R.string.native_import_err_already_importing)
            ERR_TASK_RECORDING -> return getString(R.string.native_import_err_recording)
            ERR_TASK_RESUMABLE_EXISTS -> return getString(R.string.native_import_err_resumable)
            ERR_TASK_SHARE_FAILED -> return getString(R.string.native_import_err_share_failed)
            else -> return getString(R.string.native_import_err_unknown, errorCode)
        }
    }

    private fun importStatusName(status: Int): String {
        when (status) {
            IMPORTING -> return getString(R.string.native_import_state_importing)
            IMPORTED -> return getString(R.string.native_import_state_imported)
            IMPORT_FAILED -> return getString(R.string.native_import_state_failed)
            SHARE_IMPORT_FAILED -> return getString(R.string.native_import_state_share_failed)
            IMPORT_NOT_STARTED -> return getString(R.string.native_import_state_not_started)
            else -> return getString(R.string.native_import_state_not_started)
        }
    }

    companion object {
        // ===== FileImportStatusEventApp.status =====
        /** 未开始导入。  */
        private const val IMPORT_NOT_STARTED = 0

        /** 导入中。  */
        private const val IMPORTING = 1

        /** 导入完成（可能含失败文件）。  */
        private const val IMPORTED = 2

        /** 导入中断 / 失败。  */
        private const val IMPORT_FAILED = 3

        /** 分享导入异常。  */
        private const val SHARE_IMPORT_FAILED = 4

        // ===== 单文件失败码（FailedFileInfo.errorCode），多个码归为同一可读原因 =====
        /** 格式异常。  */
        private const val ERR_FILE_FORMAT = 20103

        /** 文件格式错误，无法解码。  */
        private const val ERR_FILE_CANNOT_DECODE = 20110

        /** 文件不存在。  */
        private const val ERR_FILE_NOT_FOUND = 20105

        /** 文件属性已改变。  */
        private const val ERR_FILE_ATTR_CHANGED = 20106

        /** 文件时长过长。  */
        private const val ERR_FILE_TOO_LONG = 20107

        /** 因文件错误导致解码中断。  */
        private const val ERR_FILE_DECODE_INTERRUPTED = 20111

        /** 导入异常中断。  */
        private const val ERR_FILE_IMPORT_INTERRUPTED = 20112

        /** SDK 初始化失败。  */
        private const val ERR_FILE_SDK_INIT_FAILED = 20108

        /** 启动音频解码失败。  */
        private const val ERR_FILE_DECODE_START_FAILED = 20109

        /** mp3 创建失败。  */
        private const val ERR_FILE_MP3_CREATE_FAILED = 20113

        // ===== 任务级失败码（FileImportStatusEventApp.errorCode）=====
        /** 文件数量超限。  */
        private const val ERR_TASK_FILE_COUNT_EXCEEDED = 20101

        /** 单个文件过大。  */
        private const val ERR_TASK_FILE_TOO_LARGE = 20102

        /** 手机存储空间不足。  */
        private const val ERR_TASK_NO_SPACE = 20104

        /** 导入已经开始，无法重复导入。  */
        private const val ERR_TASK_ALREADY_IMPORTING = 20115

        /** 录音进行中，无法导入。  */
        private const val ERR_TASK_RECORDING = 20116

        /** 存在可恢复的导入任务，无法重复导入。  */
        private const val ERR_TASK_RESUMABLE_EXISTS = 20117

        /** 存在导入失败的记录，兜底码。  */
        private const val ERR_TASK_SHARE_FAILED = 20118
    }
}
