package com.tuya.smart.ai_voice.nativeui

import android.content.Intent
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.TextView
import com.thingclips.smart.earphone.enhance.api.bean.FilesParam
import com.thingclips.smart.earphone.enhance.api.bean.MergeStatusEvent
import com.thingclips.smart.earphone.enhance.api.bean.RecordTransferResultBean
import com.thingclips.smart.earphone.enhance.api.listener.IMergeStatusListener
import com.thingclips.smart.earphone.enhance.api.listener.IRecordCallBack
import com.thingclips.smart.sdk.api.IResultCallback
import com.tuya.smart.ai_voice.R
import com.tuya.smart.ai_voice.databinding.ActivityNativeMergeRecordBinding
import com.tuya.smart.ai_voice.databinding.ItemNativeMergeSelectedBinding
import com.tuya.smart.ai_voice.nativeui.base.NativeDemoBaseActivity
import java.util.Locale
import kotlin.math.min

/**
 * 模块 8 · 合并录音演示页。
 * 
 * 
 * 把多条录音按选定顺序合并为一条新录音。完整流程与 AI 笔记一致：
 * 
 *  1. 拉一页录音列表作为候选（真实产品通常在首页列表里多选）
 *  1. 勾选并调整顺序 —— **`recordIds` 的数组顺序就是音频拼接次序**，
 * 真实产品用拖拽排序，本页用上移/下移按钮，语义相同
 *  1. `mergeRecordList(recordIds)` 发起，进度由 [IMergeStatusListener] 推送
 *  1. 进行中可 `cancelMergeRecordList()` 取消
 * 
 * **注意传的是业务 `recordId`（String），不是 `recordTransferId`（Long）**——
 * 与删除接口 `removeFileList` 用的 ID 类型不同，容易搞混。
 * 
 * 
 * 进度条这样映射：下载阶段（subStatus=10）最多显示到 90%，
 * 合并阶段（subStatus=20）直接锁定 90%，完成后才到 100%，避免进度条在合并阶段长时间不动。
 */
class NativeMergeRecordActivity : NativeDemoBaseActivity() {
    /** 内容布局绑定。  */
    private lateinit var binding: ActivityNativeMergeRecordBinding


    /** 合并状态监听，用字段持有；注意 remove 方法无参（移除全部）。  */
    private var mergeStatusListener: IMergeStatusListener? = null
    /** 合并成功后的结果 recordId，用于跳转详情。  */
    private var mergedRecordId: String? = null

    /** 候选录音：recordId → 条目信息。用 LinkedHashMap 保持列表顺序。  */
    private val candidates: MutableMap<String?, Candidate?> = LinkedHashMap<String?, Candidate?>()

    /** 合并候选项，保留发起前校验所需的时长与类型。  */
    private class Candidate(val label: String?, val durationMs: Long, val recordType: Int) {
        val isMergeUnsupported: Boolean
            /** @return 面对面 / 对话类录音不支持合并
             */
            get() = recordType == RECORD_TYPE_FACE_TO_FACE_PRO
                    || recordType == RECORD_TYPE_FACE_TO_FACE_ENTRY
    }

    /** 已选录音的 recordId，**顺序即合并次序**。  */
    private val selectedIds: MutableList<String?> = ArrayList<String?>()

    override val contentLayoutId: Int
        get() = R.layout.activity_native_merge_record

    override val titleResId: Int
        get() = R.string.native_merge_title

    override fun onContentViewCreated() {
        binding = ActivityNativeMergeRecordBinding.bind(contentView())
        binding.btnOpenResult.setEnabled(false)

        binding.btnLoadList.setOnClickListener(View.OnClickListener { v: View? -> loadCandidates() })
        binding.btnMerge.setOnClickListener(View.OnClickListener { v: View? -> startMerge() })
        binding.btnCancelMerge.setOnClickListener(View.OnClickListener { v: View? -> cancelMerge() })
        binding.btnOpenResult.setOnClickListener(View.OnClickListener { v: View? -> openMergedRecord() })

        loadStatusSnapshot()
        loadCandidates()
    }

    // ===================== 事件监听 =====================
    override fun registerListeners() {
        mergeStatusListener = object : IMergeStatusListener {
            override fun onStatusUpdate(event: MergeStatusEvent) {
            runOnUi {
                renderMergeStatus(
                    event
                )
            }
            }
        }
        mergeStatusListener?.let { manager.addMergeStatusListener(it) }
    }

    override fun unregisterListeners() {
        if (mergeStatusListener != null) {
            // 注意：该 remove 无参，会移除全部已注册的合并状态监听
            manager.removeMergeStatusListener()
            mergeStatusListener = null
        }
    }

    /**
     * 进页取一次合并状态快照，补上进页前已经在跑的任务。
     * 与模块 5 的 `getAudioImportStatus` 是同一套模式：快照 + 增量监听。
     */
    private fun loadStatusSnapshot() {
        val snapshot = manager.getFileMergeStatus()
        renderMergeStatus(snapshot)
    }

    // ===================== 候选列表 =====================
    /**
     * 拉一页录音作为合并候选。
     * 
     * 
     * 这是模块 3 的接口，此处直接调用而非藏进基类——「选哪些录音去合并」本就是合并流程的一部分。
     */
    private fun loadCandidates() {
        val param = FilesParam(
            null,  // directoryId：null 查全部目录
            null,  // recordType：null 查全部类型
            null,  // deviceId：null 查全部设备
            null,  // transfer：null 查全部转写状态
            null,  // source：null 查全部来源
            java.lang.Boolean.FALSE,  // remove：排除回收站
            ORDER_BY_RECORD_TIME,
            ORDER_DESC,
            null,  // lastFileId：首页
            CANDIDATE_PAGE_SIZE
        )
        manager.getRecordTransferResultList(
            param,
            object : IRecordCallBack<MutableList<RecordTransferResultBean?>?> {
                override fun onSuccess(result: MutableList<RecordTransferResultBean?>?) {
                    runOnUi { renderCandidates(result) }
                }

                override fun onError(code: String?, error: String?) {
                    runOnUi {
                        toast(getString(R.string.native_merge_log_load_fail, code, error))
                    }
                }
            })
    }

    /**
     * 渲染候选列表。只保留有 `recordId` 的条目——合并接口按 recordId 定位文件。
     * 
     * @param result 列表数据，可能为 null
     */
    private fun renderCandidates(result: MutableList<RecordTransferResultBean?>?) {
        candidates.clear()
        binding.llCandidateContainer.removeAllViews()
        selectedIds.clear()
        renderSelected()

        if (result == null || result.isEmpty()) {
            return
        }
        val inflater = LayoutInflater.from(this)
        for (bean in result) {
            if (bean == null || TextUtils.isEmpty(bean.recordId)) continue
            val candidate = Candidate(
                displayName(bean),
                if (bean.duration == null) 0L else bean.duration,
                if (bean.recordType == null) 0 else bean.recordType
            )
            candidates.put(bean.recordId, candidate)
            binding.llCandidateContainer.addView(createCandidateView(inflater, bean.recordId, candidate))
        }
    }

    private fun createCandidateView(
        inflater: LayoutInflater,
        recordId: String?,
        candidate: Candidate
    ): View {
        val cb = inflater.inflate(
            R.layout.item_native_merge_candidate, binding.llCandidateContainer, false
        ) as CheckBox
        // 不支持合并的类型直接置灰，避免选中后才被拦截
        if (candidate.isMergeUnsupported) {
            cb.setText(getString(R.string.native_merge_candidate_unsupported, candidate.label))
            cb.setEnabled(false)
            return cb
        }
        cb.setText(candidate.label)
        cb.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { button: CompoundButton?, checked: Boolean ->
            if (checked) {
                if (!selectedIds.contains(recordId)) {
                    selectedIds.add(recordId)
                }
            } else {
                selectedIds.remove(recordId)
            }
            renderSelected()
        })
        return cb
    }

    private fun displayName(bean: RecordTransferResultBean): String {
        val name =
            if (TextUtils.isEmpty(bean.name)) getString(R.string.native_unnamed) else bean.name
        val durationMs = if (bean.duration == null) 0L else bean.duration
        return getString(R.string.native_merge_candidate_format, name, formatDuration(durationMs))
    }

    // ===================== 已选顺序 =====================
    /** 重绘已选列表。序号从 1 开始，即最终的音频拼接次序。  */
    private fun renderSelected() {
        binding.llSelectedContainer.removeAllViews()
        if (selectedIds.isEmpty()) {
            val empty = TextView(this)
            empty.setText(R.string.native_merge_selected_empty)
            empty.setTextSize(12f)
            empty.setPadding(0, 8, 0, 8)
            binding.llSelectedContainer.addView(empty)
            return
        }
        val inflater = LayoutInflater.from(this)
        for (i in selectedIds.indices) {
            binding.llSelectedContainer.addView(createSelectedView(inflater, i))
        }
    }

    private fun createSelectedView(inflater: LayoutInflater, index: Int): View {
        val itemBinding = ItemNativeMergeSelectedBinding.inflate(inflater, binding.llSelectedContainer, false)
        val item = itemBinding.root
        val recordId = selectedIds.get(index)
        itemBinding.tvIndex.setText(String.format(Locale.getDefault(), "%d", index + 1))
        val candidate = candidates.get(recordId)
        itemBinding.tvName.setText(if (candidate == null) recordId else candidate.label)

        val up = itemBinding.btnUp
        val down = itemBinding.btnDown
        up.setEnabled(index > 0)
        down.setEnabled(index < selectedIds.size - 1)
        up.setOnClickListener(View.OnClickListener { v: View? -> swapSelected(index, index - 1) })
        down.setOnClickListener(View.OnClickListener { v: View? -> swapSelected(index, index + 1) })
        return item
    }

    /**
     * 交换两个已选项的位置，改变合并次序。
     * 
     * @param from 原位置
     * @param to   目标位置
     */
    private fun swapSelected(from: Int, to: Int) {
        if (to < 0 || to >= selectedIds.size) return
        val tmp = selectedIds.get(from)
        selectedIds.set(from, selectedIds.get(to))
        selectedIds.set(to, tmp)
        renderSelected()
    }

    // ===================== 合并 =====================
    /**
     * 发起合并前的四道校验，全部通过后再查一次任务状态。
     * 
     * 
     * 这些约束底层不会替你兜：条数超限或含不支持的类型时，接口不一定报错，
     * 但结果不可预期。校验在客户端做完再发起。
     */
    private fun startMerge() {
        val count = selectedIds.size
        if (count < MIN_MERGE_COUNT) {
            toast(getString(R.string.native_merge_toast_need_min, MIN_MERGE_COUNT))
            return
        }
        if (count > MAX_MERGE_COUNT) {
            toast(getString(R.string.native_merge_toast_exceed_max, MAX_MERGE_COUNT))
            return
        }
        val totalDuration = totalSelectedDuration()
        if (totalDuration > MAX_TOTAL_DURATION_MS) {
            toast(
                getString(
                    R.string.native_merge_toast_exceed_duration,
                    MAX_TOTAL_DURATION_MS / (60 * 60 * 1000)
                )
            )
            return
        }
        if (hasUnsupportedSelected()) {
            toast(getString(R.string.native_merge_toast_unsupported_type))
            return
        }
        // 上一个合并任务未结束时发起会互相干扰，先查一次快照
        val running = manager.getFileMergeStatus()
        if (running != null && running.status == MergeStatusEvent.STATUS_IN_PROGRESS) {
            toast(getString(R.string.native_merge_toast_task_running))
            return
        }
        doMerge()
    }

    /** @return 已选录音的总时长（毫秒）
     */
    private fun totalSelectedDuration(): Long {
        var total = 0L
        for (recordId in selectedIds) {
            val candidate = candidates.get(recordId)
            if (candidate != null) {
                total += candidate.durationMs
            }
        }
        return total
    }

    /** @return 已选中是否包含不支持合并的类型
     */
    private fun hasUnsupportedSelected(): Boolean {
        for (recordId in selectedIds) {
            val candidate = candidates.get(recordId)
            if (candidate != null && candidate.isMergeUnsupported) {
                return true
            }
        }
        return false
    }

    /** 校验通过后真正下发合并。  */
    private fun doMerge() {
        manager.mergeRecordList(ArrayList<String?>(selectedIds), object : IResultCallback {
            override fun onSuccess() {
            }

            override fun onError(code: String?, error: String?) {
                runOnUi {
                    toast(getString(R.string.native_merge_log_merge_fail, code, error))
                }
            }
        })
    }

    /** 取消进行中的合并任务。  */
    private fun cancelMerge() {
        manager.cancelMergeRecordList(object : IResultCallback {
            override fun onSuccess() {
            }

            override fun onError(code: String?, error: String?) {
                toastError(code, error)
            }
        })
    }

    /**
     * 渲染合并进度。
     * 
     * 
     * 进度映射：下载阶段最多 90%，合并阶段固定 90%，完成 100%。
     * 
     * @param event 合并状态，可能为 null
     */
    private fun renderMergeStatus(event: MergeStatusEvent?) {
        if (event == null) {
            binding.tvMergeStatus.setText(R.string.native_merge_status_empty)
            return
        }
        val status = event.status
        val subStatus = event.subStatus
        val rawProgress: Int = event.progress ?: 0
        val shownProgress = mapProgress(status, subStatus, rawProgress)
        val errorCode: Int = event.errorCode ?: 0

        binding.tvMergeStatus.setText(
            getString(
                R.string.native_merge_status_format,
                mergeStatusName(status),
                subStatusName(subStatus),
                shownProgress,
                errorCode,
                if (errorCode == 0) "-" else mergeErrorMessage(errorCode),
                if (TextUtils.isEmpty(event.recordId)) "-" else event.recordId
            )
        )


        if (status == MergeStatusEvent.STATUS_FINISHED) {
            mergedRecordId = event.recordId
            binding.btnOpenResult.setEnabled(!TextUtils.isEmpty(mergedRecordId))
            toast(getString(R.string.native_merge_toast_finished, mergedRecordId))
        } else if (status == MergeStatusEvent.STATUS_ERROR) {
            toast(mergeErrorMessage(errorCode))
        }
    }

    /**
     * 合并失败原因。
     * 
     * @param errorCode `MergeStatusEvent.errorCode`
     * @return 可读原因
     */
    private fun mergeErrorMessage(errorCode: Int): String {
        when (errorCode) {
            ERR_EMPTY_PARAM -> return getString(R.string.native_merge_err_20220)
            ERR_MERGE_FAILED -> return getString(R.string.native_merge_err_20221)
            ERR_NO_NETWORK -> return getString(R.string.native_merge_err_20222)
            ERR_FILE_NOT_FOUND -> return getString(R.string.native_merge_err_20223)
            ERR_UPLOAD_FAILED -> return getString(R.string.native_merge_err_20224)
            ERR_REPORT_FAILED -> return getString(R.string.native_merge_err_20225)
            ERR_RECORD_NOT_FOUND -> return getString(R.string.native_merge_err_20226)
            ERR_UNKNOWN -> return getString(R.string.native_merge_err_20227)
            ERR_CREATE_FILE_FAILED -> return getString(R.string.native_merge_err_20229)
            ERR_CANCELLED -> return getString(R.string.native_merge_err_20230)
            else -> return getString(R.string.native_merge_err_unknown, errorCode)
        }
    }

    /** 打开合并结果的详情页。  */
    private fun openMergedRecord() {
        if (TextUtils.isEmpty(mergedRecordId)) return
        startActivity(
            Intent(this, NativeRecordDetailActivity::class.java)
                .putExtra(NativeRecordDetailActivity.Companion.EXTRA_RECORD_ID, mergedRecordId)
        )
    }

    /**
     * 把原始进度映射为展示进度。
     * 
     * @param status      主状态
     * @param subStatus   子状态
     * @param rawProgress 原始进度 0-100
     * @return 展示进度 0-100
     */
    private fun mapProgress(status: Int, subStatus: Int?, rawProgress: Int): Int {
        if (status == MergeStatusEvent.STATUS_FINISHED) {
            return FINISHED_PROGRESS
        }
        if (status != MergeStatusEvent.STATUS_IN_PROGRESS || subStatus == null) {
            return 0
        }
        if (subStatus == MergeStatusEvent.SUB_STATUS_MERGING) {
            return MERGING_PROGRESS
        }
        if (subStatus == MergeStatusEvent.SUB_STATUS_DOWNLOADING) {
            return min(rawProgress, DOWNLOAD_PROGRESS_CAP)
        }
        return rawProgress
    }

    private fun mergeStatusName(status: Int): String {
        if (status == MergeStatusEvent.STATUS_IN_PROGRESS) {
            return getString(R.string.native_merge_state_running)
        }
        if (status == MergeStatusEvent.STATUS_FINISHED) {
            return getString(R.string.native_merge_state_finished)
        }
        if (status == MergeStatusEvent.STATUS_ERROR) {
            return getString(R.string.native_merge_state_error)
        }
        return getString(R.string.native_merge_state_not_started)
    }

    private fun subStatusName(subStatus: Int?): String {
        if (subStatus == null) return "-"
        if (subStatus == MergeStatusEvent.SUB_STATUS_DOWNLOADING) {
            return getString(R.string.native_merge_sub_downloading)
        }
        if (subStatus == MergeStatusEvent.SUB_STATUS_MERGING) {
            return getString(R.string.native_merge_sub_merging)
        }
        return subStatus.toString()
    }

    companion object {
        /** 候选列表一次拉取的条数。  */
        private const val CANDIDATE_PAGE_SIZE = 20

        /** 列表排序字段：按录音时间。  */
        private const val ORDER_BY_RECORD_TIME = 1

        /** 倒序。  */
        private const val ORDER_DESC = 0

        /** 下载阶段进度条上限，留出合并阶段的空间。  */
        private const val DOWNLOAD_PROGRESS_CAP = 90

        /** 合并阶段固定进度。  */
        private const val MERGING_PROGRESS = 90

        /** 完成进度。  */
        private const val FINISHED_PROGRESS = 100

        // ===== 发起合并的前置约束 =====
        /** 至少选择的条数。  */
        private const val MIN_MERGE_COUNT = 2

        /** 一次最多合并的条数。  */
        private const val MAX_MERGE_COUNT = 10

        /** 合并总时长上限：5 小时。  */
        private val MAX_TOTAL_DURATION_MS = 5L * 60 * 60 * 1000

        // ===== 不支持合并的录音类型（RecordTransferResultBean.recordType）=====
        /** 面对面翻译（Pro）。  */
        private const val RECORD_TYPE_FACE_TO_FACE_PRO = 2

        /** 面对面翻译（入门版）。  */
        private const val RECORD_TYPE_FACE_TO_FACE_ENTRY = 3

        // ===== 合并失败错误码（MergeStatusEvent.errorCode）=====
        /** 参数为空。  */
        private const val ERR_EMPTY_PARAM = 20220

        /** 通用合并失败。  */
        private const val ERR_MERGE_FAILED = 20221

        /** 网络不可用。  */
        private const val ERR_NO_NETWORK = 20222

        /** 本地文件不存在。  */
        private const val ERR_FILE_NOT_FOUND = 20223

        /** 上传记录失败。  */
        private const val ERR_UPLOAD_FAILED = 20224

        /** 合并结果上报失败。  */
        private const val ERR_REPORT_FAILED = 20225

        /** 录音记录未找到。  */
        private const val ERR_RECORD_NOT_FOUND = 20226

        /** 未知错误。  */
        private const val ERR_UNKNOWN = 20227

        /** 创建合并文件失败。  */
        private const val ERR_CREATE_FILE_FAILED = 20229

        /** 合并已被取消。  */
        private const val ERR_CANCELLED = 20230

        private fun formatDuration(ms: Long): String {
            val totalSec = ms / 1000
            return String.format(Locale.getDefault(), "%02d:%02d", totalSec / 60, totalSec % 60)
        }
    }
}
