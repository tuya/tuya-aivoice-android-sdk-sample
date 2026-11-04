package com.tuya.smart.ai_voice.nativeui.impt

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.thingclips.smart.earphone.enhance.api.bean.FailedFileInfo
import com.thingclips.smart.earphone.enhance.api.bean.FileImportStatusEventApp
import com.thingclips.smart.earphone.enhance.api.bean.FileImportedFileInfo
import com.thingclips.smart.earphone.enhance.api.listener.IAudioImportCallBack
import com.thingclips.smart.earphone.enhance.api.listener.IFileImportStatusListener
import com.thingclips.smart.plugin.tuniaudiodetectmanager.ThingAudioDetectManagerNative
import com.tuya.smart.ai_voice.R
import com.tuya.smart.ai_voice.nativeui.NativeRecordDetailActivity
import com.tuya.smart.ai_voice.nativeui.NativeRecordListActivity

abstract class NativeAudioImportBaseFragment : Fragment() {
    protected val manager: ThingAudioDetectManagerNative =
        ThingAudioDetectManagerNative.getInstance()

    private lateinit var rootView: View
    private lateinit var statusView: TextView
    private lateinit var failedFilesView: TextView
    protected var importing = false
    private var lastRenderedStatus = -1
    private var importStatusListener: IFileImportStatusListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    protected abstract val layoutId: Int

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        rootView = inflater.inflate(layoutId, container, false)
        return rootView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        statusView = rootView.findViewById(R.id.tv_import_status)
        failedFilesView = rootView.findViewById(R.id.tv_failed_files)
        onViewReady()
        loadStatusSnapshot()
    }

    protected abstract fun onViewReady()

    override fun onResume() {
        super.onResume()
        registerImportListener()
    }

    override fun onPause() {
        manager.removeFileImportStatusListener()
        importStatusListener = null
        super.onPause()
    }

    private fun registerImportListener() {
        if (importStatusListener != null) return
        importStatusListener = object : IFileImportStatusListener {
            override fun onStatusUpdate(event: FileImportStatusEventApp) {
                runOnUi { renderStatus(event) }
            }
        }
        importStatusListener?.let { manager.addFileImportStatusListener(it) }
    }

    protected fun loadStatusSnapshot() {
        renderStatus(manager.getAudioImportStatus(), fromSnapshot = true)
    }

    protected fun renderStatus(event: FileImportStatusEventApp?, fromSnapshot: Boolean = false) {
        if (event == null) {
            statusView.setText(R.string.native_import_status_empty)
            failedFilesView.setText(R.string.native_import_failed_empty)
            importing = false
            lastRenderedStatus = STATUS_NOT_STARTED
            return
        }
        val status = event.status ?: STATUS_NOT_STARTED
        val previousStatus = lastRenderedStatus
        lastRenderedStatus = status
        importing = status == STATUS_IMPORTING

        val total = event.totalFileCount ?: 0
        val success = event.successCount ?: 0
        val errorCode = event.errorCode ?: 0
        statusView.text = getString(
            R.string.native_import_status_format,
            importStatusName(status),
            total,
            success,
            percent(success, total),
            errorCode,
            if (errorCode == 0) "-" else taskErrorMessage(errorCode)
        )
        renderFailedFiles(event.failedFiles)

        val allSuccess = event.failedFiles.isNullOrEmpty()
        if (!fromSnapshot && status == STATUS_IMPORTED && previousStatus != status && allSuccess) {
            onImportCompleted(success, event.importedFiles.orEmpty())
        }
    }

    protected open fun onImportCompleted(
        successCount: Int,
        importedFiles: List<FileImportedFileInfo>
    ) {
    }

    protected fun showCompletionDialog(titleRes: Int, messageRes: Int, onEnter: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setPositiveButton(R.string.native_import_dialog_enter) { _, _ -> onEnter() }
            .setNegativeButton(R.string.native_import_dialog_stay, null)
            .show()
    }

    protected fun openDetail(recordId: String) {
        startActivity(
            Intent(requireContext(), NativeRecordDetailActivity::class.java)
                .putExtra(NativeRecordDetailActivity.EXTRA_RECORD_ID, recordId)
        )
    }

    protected fun openRecordList() {
        startActivity(Intent(requireContext(), NativeRecordListActivity::class.java))
    }

    protected fun simpleCallback(action: String): IAudioImportCallBack {
        return object : IAudioImportCallBack {
            override fun onSuccess() {
            }

            override fun onError(code: Int?, error: String?) {
                if (isAdded) showStartError(code, error)
            }
        }
    }

    protected fun showImportingToast() {
        showToast(getString(R.string.native_import_toast_importing))
    }

    protected fun showStartError(code: Int?, error: String?) {
        showToast(getString(R.string.native_import_log_start_fail, code.toString(), error))
    }

    protected fun showToast(message: String) {
        if (isAdded) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    protected fun runOnUi(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post {
                if (isAdded) action()
            }
        }
    }

    private fun percent(success: Int, total: Int): Int {
        return if (total <= 0) 0 else success * 100 / total
    }

    private fun renderFailedFiles(failedFiles: MutableList<FailedFileInfo>?) {
        if (failedFiles.isNullOrEmpty()) {
            failedFilesView.setText(R.string.native_import_failed_empty)
            return
        }
        val grouped = LinkedHashMap<String, MutableList<String>>()
        for (info in failedFiles) {
            val reason = fileErrorMessage(info.errorCode ?: 0)
            grouped.getOrPut(reason) { ArrayList() }
                .add(info.fileName ?: "-")
        }
        val text = buildString {
            append(getString(R.string.native_import_failed_count, failedFiles.size))
            append("\n\n")
            grouped.forEach { (reason, names) ->
                append(reason).append('\n')
                names.forEach { append("  · ").append(it).append('\n') }
                append('\n')
            }
        }
        failedFilesView.text = text.trim()
    }

    private fun fileErrorMessage(errorCode: Int): String = when (errorCode) {
        ERROR_FILE_FORMAT, ERROR_FILE_CANNOT_DECODE ->
            getString(R.string.native_import_err_format)
        ERROR_FILE_NOT_FOUND -> getString(R.string.native_import_err_file_not_found)
        ERROR_FILE_ATTR_CHANGED -> getString(R.string.native_import_err_attr_changed)
        ERROR_FILE_TOO_LONG -> getString(R.string.native_import_err_too_long)
        ERROR_FILE_DECODE_INTERRUPTED, ERROR_FILE_IMPORT_INTERRUPTED ->
            getString(R.string.native_import_err_interrupted)
        ERROR_FILE_SDK_INIT_FAILED, ERROR_FILE_DECODE_START_FAILED, ERROR_FILE_MP3_CREATE_FAILED ->
            getString(R.string.native_import_err_busy)
        else -> getString(R.string.native_import_err_unknown, errorCode)
    }

    private fun taskErrorMessage(errorCode: Int): String = when (errorCode) {
        ERROR_TASK_FILE_COUNT_EXCEEDED -> getString(R.string.native_import_err_file_limit)
        ERROR_TASK_FILE_TOO_LARGE -> getString(R.string.native_import_err_file_too_large)
        ERROR_TASK_NO_SPACE -> getString(R.string.native_import_err_no_space)
        ERROR_TASK_ALREADY_IMPORTING -> getString(R.string.native_import_err_already_importing)
        ERROR_TASK_RECORDING -> getString(R.string.native_import_err_recording)
        ERROR_TASK_RESUMABLE_EXISTS -> getString(R.string.native_import_err_resumable)
        ERROR_TASK_SHARE_FAILED -> getString(R.string.native_import_err_share_failed)
        else -> getString(R.string.native_import_err_unknown, errorCode)
    }

    private fun importStatusName(status: Int): String = when (status) {
        STATUS_IMPORTING -> getString(R.string.native_import_state_importing)
        STATUS_IMPORTED -> getString(R.string.native_import_state_imported)
        STATUS_FAILED -> getString(R.string.native_import_state_failed)
        STATUS_SHARE_FAILED -> getString(R.string.native_import_state_share_failed)
        else -> getString(R.string.native_import_state_not_started)
    }

    companion object {
        private const val STATUS_NOT_STARTED = 0
        private const val STATUS_IMPORTING = 1
        private const val STATUS_IMPORTED = 2
        private const val STATUS_FAILED = 3
        private const val STATUS_SHARE_FAILED = 4

        private const val ERROR_FILE_FORMAT = 20103
        private const val ERROR_FILE_CANNOT_DECODE = 20110
        private const val ERROR_FILE_NOT_FOUND = 20105
        private const val ERROR_FILE_ATTR_CHANGED = 20106
        private const val ERROR_FILE_TOO_LONG = 20107
        private const val ERROR_FILE_DECODE_INTERRUPTED = 20111
        private const val ERROR_FILE_IMPORT_INTERRUPTED = 20112
        private const val ERROR_FILE_SDK_INIT_FAILED = 20108
        private const val ERROR_FILE_DECODE_START_FAILED = 20109
        private const val ERROR_FILE_MP3_CREATE_FAILED = 20113
        private const val ERROR_TASK_FILE_COUNT_EXCEEDED = 20101
        private const val ERROR_TASK_FILE_TOO_LARGE = 20102
        private const val ERROR_TASK_NO_SPACE = 20104
        private const val ERROR_TASK_ALREADY_IMPORTING = 20115
        private const val ERROR_TASK_RECORDING = 20116
        private const val ERROR_TASK_RESUMABLE_EXISTS = 20117
        private const val ERROR_TASK_SHARE_FAILED = 20118
    }
}
