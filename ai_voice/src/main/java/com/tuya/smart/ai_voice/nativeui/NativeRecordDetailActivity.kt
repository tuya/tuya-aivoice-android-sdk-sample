package com.tuya.smart.ai_voice.nativeui

import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.alibaba.fastjson.JSONArray
import com.google.android.material.chip.ChipGroup
import com.google.android.material.tabs.TabLayout
import com.thingclips.smart.ai.audio.sync.api.DownloadListener
import com.thingclips.smart.ai.audio.sync.api.UploadListener
import com.thingclips.smart.ai.audio.sync.api.ttt.SyncObserver
import com.thingclips.smart.ai.db.entity.RecordFile
import com.thingclips.smart.android.network.Business
import com.thingclips.smart.android.network.http.BusinessResponse
import com.thingclips.smart.api.service.MicroServiceManager
import com.thingclips.smart.commonbiz.bizbundle.family.api.AbsBizBundleFamilyService
import com.thingclips.smart.earphone.enhance.api.bean.RecordTransferRealTimeResult
import com.thingclips.smart.earphone.enhance.api.bean.RecordTransferResultBean
import com.thingclips.smart.earphone.enhance.api.bean.def.RecordOperateDef
import com.thingclips.smart.earphone.enhance.api.bean.request.TranscribeParam
import com.thingclips.smart.earphone.enhance.api.bean.ttt.RecordUpdateInfo
import com.thingclips.smart.earphone.enhance.api.listener.IRecordCallBack
import com.thingclips.smart.earphone.enhance.api.listener.IRecordFileUpdateCallback
import com.thingclips.smart.earphone.enhance.api.listener.ITransferListener
import com.thingclips.smart.sdk.api.IResultCallback
import com.tuya.smart.ai_voice.R
import com.tuya.smart.ai_voice.databinding.ActivityNativeRecordDetailBinding
import com.tuya.smart.ai_voice.databinding.DialogNativeInputBinding
import com.tuya.smart.ai_voice.nativeui.base.NativeDemoBaseActivity
import com.tuya.smart.ai_voice.nativeui.business.AudioContentBusiness
import com.tuya.smart.ai_voice.nativeui.widget.RecordErrorCode.messageOf
import com.tuya.smart.ai_voice.nativeui.widget.RecordTagBinder
import com.tuya.smart.ai_voice.nativeui.widget.TranscriptFileProvider
import com.tuya.smart.ai_voice.nativeui.widget.TransferTextParser
import com.tuya.smart.ai_voice.nativeui.widget.TransferTextParser.parseSummaryOutlineJson
import com.tuya.smart.ai_voice.nativeui.widget.TransferTextParser.parseSummaryTitle
import com.tuya.smart.ai_voice.nativeui.widget.TransferTextParser.parseTranscript
import com.tuya.smart.ai_voice.nativeui.widget.TransferTextParser.parseTranscriptArray
import com.tuya.smart.ai_voice.nativeui.widget.TransferTextParser.segmentTranscript
import com.tuya.smart.ai_voice.nativeui.widget.TransferTextParser.writeSegmentTranscript
import com.tuya.smart.ai_voice.nativeui.widget.TransferTextParser.writeSummaryBody
import com.tuya.smart.ai_voice.nativeui.widget.TransferTextParser.writeSummaryTitle
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模块 2 · 转写 / 总结演示页，兼作单条录音的操作入口。
 *
 * <h3>页面结构</h3>
 * 三个 Tab（转写 / 总结 / 思维导图）用 visibility 切换，各自独立滚动；
 * 参数按归属拆到对应 Tab——`enableSpeaker` 只影响转写，`template` / `summaryLang` 只影响总结。
 *
 * <h3>转写取数有两个分支</h3>
 * <pre>
 * 实时转写（transferType=1 且非云端）→ getRecordTransferRealTimeResult(recordId)
 * 其余（文件转写 / 云端 / 云同步）    → getRecordTransferRecognizeResult(recordTransferId)
 * </pre>
 * 走错分支的表现是「明明转写完了，正文却是空的」。
 *
 * <h3>编辑保存要写两处</h3>
 * SDK 的 `save*` 只写本地库；要让纠错跨端可见，需再调业务云接口（见 [AudioContentBusiness]）。
 * 总结、文件名、已读状态、转写正文四处均已双写；云端写入失败不回滚本地。
 *
 * <h3>转写正文只能逐段编辑</h3>
 * 界面文案由 `TransferTextParser.parseTranscript` 拼成，无法反解回原始 JSON，
 * 而 `saveRecordTransferRecognizeResult` 是整份覆盖写入。因此取数时另留一份原始数组
 * （[.originRecognizeList]），编辑时只替换该段 `transcript` 再整份提交，保证段落数与序号不变。
 *
 * <h3>按需下载音频</h3>
 * `syncDownloadNoteAudio` 同步返回且无回调，结束只能靠 [SyncObserver] 的 `DownloadListener.onFinish`
 * 感知（`downloadSuccess` / `downloadError` 语义仍是「下载中」）。回调是批次级的，
 * 需按 `recordId` 过滤，见 [AudioDownloadListener]。
 *
 * <h3>标签与已读</h3>
 * 标签只读展示，增删改在 [NativeRecordActionSheet]；变更走 `onUpdateWitheTags` 局部刷新。
 * 详情加载完成后做一次单向已读跃迁，本地与云端各写一次，见 [.markReadIfNeeded]。
 */
class NativeRecordDetailActivity : NativeDemoBaseActivity() {
    /** 内容布局绑定。  */
    private lateinit var binding: ActivityNativeRecordDetailBinding


    private var recordId: String? = null

    /** 详情接口返回的业务主键，后续所有结果查询以它为入参。  */
    private var recordTransferId = 0L
    private var transferStatus = -1
    private var summaryStatus = -1
    private var originalLanguage: String? = null
    private var currentName: String? = null

    /** 转写模式与来源，决定正文走哪条取数分支。  */
    private var transferType = 0
    private var cloudTranscription = false
    private var isFromCloud = false
    private var durationMs = 0L
    /** 三个 Tab 的内容容器，靠 visibility 切换，索引与 TabLayout 的位置一一对应。  */
    private lateinit var tabContents: Array<View>

    /**
     * 最近一次拉到的转写原始 JSON 数组。
     * 
     * 
     * **保存的模板。** 界面上展示的是它经 [parseTranscript] 拼出的
     * 可读文案（带时间戳与说话人），那份文案无法反解回结构；编辑时只替换本数组对应元素的
     * `transcript`，其余字段（`timeOffset` / `speaker` / `translation`）
     * 原样保留，再整份提交。
     */
    private var originRecognizeList: JSONArray? = null

    /** 录音所属设备 ID，转写正文写云端时要用。  */
    private var deviceId: String? = null

    /**
     * 最近一次拉到的总结原始 JSON。
     * 
     * 
     * 保存时必须把编辑后的正文写回它的 `summary` 字段再提交，
     * 否则整份覆盖会把 `outline` / `question` / `title` 全丢掉。
     */
    private var rawSummaryJson: String? = null

    /** 实时转写模式下的句子列表，编辑单句时需要其 asrId。  */
    private val realtimeSentences: MutableList<RecordTransferRealTimeResult> =
        ArrayList<RecordTransferRealTimeResult>()


    /** 文件数据变更监听，用字段持有，remove 时传同一引用。  */
    private var updateCallback: IRecordFileUpdateCallback<MutableList<RecordUpdateInfo?>?>? = null

    /** 上传进度监听，用字段持有，remove 时传同一引用。  */
    private var transferListener: ITransferListener? = null

    /** 云同步观察者，本页只用它的下载回调感知「按需下载音频」何时结束。  */
    private var syncObserver: SyncObserver? = null

    /** 录音元信息的云端写入，改名时与 SDK 的本地写入配对使用。  */
    private val contentBusiness = AudioContentBusiness()

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override val contentLayoutId: Int
        get() = R.layout.activity_native_record_detail

    override val titleResId: Int
        get() = R.string.native_detail_title

    override fun useScrollContainer(): Boolean {
        // 头部与 Tab 条固定，三个 Tab 内容各自带 ScrollView，不能再套一层
        return false
    }

    override fun onContentViewCreated() {
        recordId = if (getIntent() == null) null else getIntent().getStringExtra(EXTRA_RECORD_ID)
        if (TextUtils.isEmpty(recordId)) {
            toast(getString(R.string.native_detail_toast_no_record_id))
            finish()
            return
        }
        bindViews()
        loadDetail()
    }

    private fun bindViews() {
        binding = ActivityNativeRecordDetailBinding.bind(contentView())
        setupTabs()

        binding.nativeDetailBtnTransfer.setOnClickListener(View.OnClickListener { v: View? -> generateTransfer() })
        binding.nativeDetailBtnSummary.setOnClickListener(View.OnClickListener { v: View? -> generateSummary() })
        binding.nativeDetailBtnRefresh.setOnClickListener(View.OnClickListener { v: View? -> loadDetail() })
        binding.nativeDetailBtnMore.setOnClickListener(View.OnClickListener { v: View? -> showMoreActions() })
        binding.nativeDetailBtnSaveSummary.setOnClickListener(View.OnClickListener { v: View? -> saveSummary() })

        // 两种转写模式统一用「长按 → 选一段 → 弹窗改」，只是底层保存接口不同
        binding.nativeDetailTransferText.setOnLongClickListener(View.OnLongClickListener { v: View? ->
            pickSegmentToEdit()
            true
        })
    }

    /**
     * 装配 Tab 条：转写 / 总结 / 思维导图，与 AI 笔记的详情页一致。
     * 
     * 
     * 三个内容容器都在同一份布局里，切 Tab 只改 `visibility`——本页刻意不引入
     * ViewPager2 + Fragment：三个 Tab 共享 `recordId` / `recordTransferId` /
     * 详情 bean，拆成 Fragment 后为了共享这些数据要额外引入 ViewModel 或回调，
     * 那是与 SDK 无关的架构复杂度，会破坏「单页可独立阅读」。
     */
    private fun setupTabs() {
        tabContents = arrayOf(
            binding.nativeDetailTabTransfer,
            binding.nativeDetailTabSummary,
            binding.nativeDetailTabMindmap
        )

        val tabLayout = binding.nativeDetailTabs
        val titles = intArrayOf(
            R.string.native_detail_transfer,
            R.string.native_detail_summary,
            R.string.native_detail_tab_mindmap
        )
        for (titleRes in titles) {
            tabLayout.addTab(tabLayout.newTab().setText(titleRes))
        }
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showTab(tab.getPosition())
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {
            }

            override fun onTabReselected(tab: TabLayout.Tab?) {
            }
        })
        showTab(0)
    }

    /**
     * 切换到指定 Tab。
     * 
     * @param position Tab 位置：0 转写 / 1 总结 / 2 思维导图
     */
    private fun showTab(position: Int) {
        for (i in tabContents.indices) {
            tabContents[i].setVisibility(if (i == position) View.VISIBLE else View.GONE)
        }
    }

    // ===================== 事件监听 =====================
    override fun registerListeners() {
        updateCallback = object : IRecordFileUpdateCallback<MutableList<RecordUpdateInfo?>?> {
            override fun onUpdate(infos: MutableList<RecordUpdateInfo?>?) {
                handleUpdate(infos)
            }

            override fun onRecordOperate(operate: String, infos: MutableList<RecordUpdateInfo?>?) {
                // 只有本文件的 UPDATE 有意义；ADD / DELETE 属于列表页的关注范围
                if (RecordOperateDef.OPERATE_TYPE_UPDATE == operate) {
                    handleUpdate(infos)
                }
            }

            override fun onRecordListSyncSuccess() {
                runOnUi { this@NativeRecordDetailActivity.loadDetail() }
            }

            override fun onUpdateWitheTags(infos: MutableList<RecordUpdateInfo?>?) {
                handleTagsUpdate(infos)
            }
        }
        updateCallback?.let { manager.addFileRecordUpdateListener(it) }

        transferListener = object : ITransferListener {
            override fun onRecordTransferFileUploadEvent(fileId: String, progress: Int, uploadingStatus: Int) {
                runOnUi { handleUploadEvent(fileId, progress, uploadingStatus) }
            }
        }
        manager.addTransferListener(transferListener)

        // syncDownloadNoteAudio 同步返回且无回调，下载结束只能靠它感知
        val observer = SyncObserver(AudioDownloadListener(), NoopUploadListener())
        syncObserver = observer
        manager.addAudioSyncObserver(observer)
    }

    override fun unregisterListeners() {
        updateCallback?.let {
            manager.removeFileRecordUpdateListener(it)
            updateCallback = null
        }
        if (transferListener != null) {
            manager.removeTransferListener(transferListener)
            transferListener = null
        }
        syncObserver?.let {
            manager.removeAudioSyncObserver(it)
            syncObserver = null
        }
        contentBusiness.onDestroy()
    }

    /**
     * 处理音频上传进度。
     * 
     * 
     * 事件是全局广播的，必须按 `fileId` 过滤出本文件。上传完成后转写随即开始，
     * 此处直接把本地状态标为「转写中 / 总结中」，避免界面在事件到达前显示成「未转写」。
     * 
     * @param fileId   事件所属文件 ID
     * @param progress 进度 0-100
     * @param status   上传状态
     */
    private fun handleUploadEvent(fileId: String?, progress: Int, status: Int) {
        if (recordTransferId <= 0 || fileId == null) return
        if (fileId != recordTransferId.toString()) return

        binding.nativeDetailUpload.setVisibility(View.VISIBLE)
        if (status == UPLOAD_WAITING) {
            binding.nativeDetailUpload.setText(R.string.native_detail_upload_waiting)
            return
        }
        if (status == UPLOAD_COMPLETED) {
            binding.nativeDetailUpload.setText(R.string.native_detail_upload_done)
            transferStatus = TRANSFER_RUNNING
            summaryStatus = SUMMARY_RUNNING
            refreshBadges()
            refreshButtons()
            return
        }
        binding.nativeDetailUpload.setText(getString(R.string.native_detail_upload_progress, progress))
    }

    /** 按 recordId 匹配出本文件的变更，状态有变化时刷新界面并重拉正文。  */
    private fun handleUpdate(infos: MutableList<RecordUpdateInfo?>?) {
        if (infos == null || infos.isEmpty() || TextUtils.isEmpty(recordId)) return
        var mine: RecordUpdateInfo? = null
        for (info in infos) {
            if (info != null && recordId == info.recordId) {
                mine = info
                break
            }
        }
        if (mine == null) return
        val newTransfer = mine.transferStatus
        val newSummary = mine.summaryStatus
        runOnUi {
            val changed = transferStatus != newTransfer || summaryStatus != newSummary
            transferStatus = newTransfer
            summaryStatus = newSummary
            refreshBadges()
            refreshButtons()
            if (changed) {
                // 重拉详情以同步 transferType / cloudTranscription 等分支判据，再按状态取正文
                loadDetail()
            }
        }
    }

    /**
     * 处理标签变更事件，**只刷新标签区，不重拉详情**。
     * 
     * 
     * 这是 `IRecordFileUpdateCallback` 四个回调按变更粒度分工的意义所在：
     * `onUpdate` / `onRecordOperate` 携带的是转写、总结等状态变化，
     * 需要重新取详情与正文；而 `onUpdateWitheTags` 只说明标签变了，
     * 事件里的 `tags` 已经是最新值，直接拿来渲染即可。收到它还去 `loadDetail()`
     * 就等于把这个回调当 `onUpdate` 用，白白多一次接口调用。
     * 
     * @param infos 变更列表，需按 `recordId` 过滤出本条记录
     */
    private fun handleTagsUpdate(infos: MutableList<RecordUpdateInfo?>?) {
        if (infos == null || infos.isEmpty() || TextUtils.isEmpty(recordId)) return
        for (info in infos) {
            if (info != null && recordId == info.recordId) {
                val tags = info.tags
                runOnUi {
                    RecordTagBinder.bind(
                        binding.nativeDetailTags,
                        tags,
                        RecordTagBinder.NO_LIMIT
                    )
                }
                return
            }
        }
    }

    // ===================== 详情 =====================
    private fun loadDetail() {
        val id = recordId ?: return
        manager.getRecordTransferResultDetail(
            id, AMPLITUDE_FULL,
            object : IRecordCallBack<RecordTransferResultBean?> {
                override fun onSuccess(bean: RecordTransferResultBean?) {
                    runOnUi { renderDetail(bean) }
                }

                override fun onError(code: String?, error: String?) {
                    toastRecordError(code)
                }
            })
    }

    private fun renderDetail(bean: RecordTransferResultBean?) {
        if (bean == null) {
            return
        }
        recordTransferId = if (bean.recordTransferId == null) 0L else bean.recordTransferId
        transferStatus = if (bean.transfer == null) -1 else bean.transfer
        summaryStatus = if (bean.summary == null) -1 else bean.summary
        originalLanguage = bean.originalLanguage
        transferType = if (bean.transferType == null) 0 else bean.transferType
        cloudTranscription = bean.cloudTranscription
        isFromCloud = bean.isFromCloud
        durationMs = if (bean.duration == null) 0L else bean.duration
        deviceId = bean.deviceId

        currentName =
            if (TextUtils.isEmpty(bean.name)) getString(R.string.native_unnamed) else bean.name
        binding.nativeDetailName.setText(currentName)
        val time =
            if (bean.recordTime == null) "" else dateFmt.format(Date(bean.recordTime * 1000L))
        binding.nativeDetailMeta.setText(
            getString(
                R.string.native_detail_meta_format,
                formatDuration(durationMs), time
            )
        )

        refreshBadges()

        // 详情页空间充裕，标签全部展示
        RecordTagBinder.bind(binding.nativeDetailTags, bean.tags, RecordTagBinder.NO_LIMIT)

        markReadIfNeeded(bean.visit)

        if (transferStatus == TRANSFER_DONE || useRealtimeBranch()) {
            loadTransferResult()
        } else {
            binding.nativeDetailTransferText.setText(R.string.native_detail_empty_transfer)
        }
        if (summaryStatus == SUMMARY_DONE) {
            loadSummaryResult()
        } else {
            rawSummaryJson = null
            binding.nativeDetailSummaryText.setText(R.string.native_detail_empty_summary)
            binding.nativeDetailMindmapJson.setText(R.string.native_detail_mindmap_empty)
        }
        refreshButtons()
    }

    /**
     * 判断转写正文该走哪条分支。
     * 
     * @return true 走实时句子接口，false 走文件转写结果接口
     */
    private fun useRealtimeBranch(): Boolean {
        return transferType == TRANSFER_TYPE_REALTIME && !cloudTranscription && !isFromCloud
    }

    /**
     * 刷新两个生成按钮的可用性。
     * 
     * 
     * 只在该任务「进行中」时禁用，其余状态一律可点，由使用者决定是否重新触发。
     */
    private fun refreshButtons() {
        val hasId = recordTransferId > 0
        binding.nativeDetailBtnTransfer.setEnabled(hasId && transferStatus != TRANSFER_RUNNING)
        binding.nativeDetailBtnSummary.setEnabled(hasId && summaryStatus != SUMMARY_RUNNING)
    }

    private fun refreshBadges() {
        binding.nativeDetailTransferBadge.setText(transferBadgeText(transferStatus))
        binding.nativeDetailSummaryBadge.setText(summaryBadgeText(summaryStatus))
    }

    // ===================== 转写 / 总结正文 =====================
    /** 按 [.useRealtimeBranch] 选择取数分支加载转写正文。  */
    private fun loadTransferResult() {
        if (useRealtimeBranch()) {
            loadRealtimeSentences()
        } else {
            loadRecognizeResult()
        }
    }

    /**
     * 实时转写模式的正文：一组带时间戳与声道的句子。
     * 
     * 
     * 三个入参都可为 null，此处按 `recordId` 查。单句纠错走
     * `saveRecordTransferRealTimeRecognizeResult(asrId, ...)`，见 [.editSentence]。
     */
    private fun loadRealtimeSentences() {
        if (TextUtils.isEmpty(recordId)) return
        manager.getRecordTransferRealTimeResult(
            null, recordId, null,
            object : IRecordCallBack<MutableList<RecordTransferRealTimeResult?>?> {
                override fun onSuccess(result: MutableList<RecordTransferRealTimeResult?>?) {
                    runOnUi { renderRealtimeSentences(result) }
                }

                override fun onError(code: String?, error: String?) {
                    toastRecordError(code)
                }
            })
    }

    private fun renderRealtimeSentences(result: MutableList<RecordTransferRealTimeResult?>?) {
        realtimeSentences.clear()
        if (result == null || result.isEmpty()) {
            binding.nativeDetailTransferText.setText(R.string.native_detail_empty_transfer)
            return
        }
        realtimeSentences.addAll(result.filterNotNull())
        binding.nativeDetailTransferText.setText(buildRealtimeText(result.filterNotNull()))
    }

    /**
     * 把实时转写的句子列表拼成可读文案，格式与文件转写分支
     * （[parseTranscript]）保持一致：`[mm:ss] 正文`，
     * 有译文时换行追加译文。
     * 
     * 
     * 界面渲染与导出共用这一份实现，避免两处格式漂移。
     * 
     * @param result 实时转写句子列表
     * @return 多行可读文本，列表为空时返回空串
     */
    private fun buildRealtimeText(result: List<RecordTransferRealTimeResult>?): String {
        if (result == null || result.isEmpty()) return ""
        val sb = StringBuilder()
        for (item in result) {
            val begin = if (item.beginOffset == null) 0L else item.beginOffset
            sb.append('[').append(formatDuration(begin)).append("] ")
            sb.append(if (TextUtils.isEmpty(item.text)) nullToEmpty(item.asr) else item.text)
                .append('\n')
            if (!TextUtils.isEmpty(item.translate)) {
                sb.append("  ➜ ").append(item.translate).append('\n')
            }
        }
        return sb.toString().trim { it <= ' ' }
    }

    // ===================== 编辑保存 =====================
    /**
     * 保存总结正文。同样只写本地。
     * 
     * 
     * **注意不能把界面上的文本直接存回去。** `saveRecordTransferSummaryResult` 是
     * **整份覆盖**写入，而云端下发的总结是一个 JSON 对象
     * （`summary` / `outline` / `question` / `title`）。
     * 直接存纯文本会把除正文外的字段全部抹掉，下次解析只能退化成原样输出。
     * 
     * 
     * 因此这里的做法是：界面只编辑 `summary` 正文，保存时把它写回
     * [.rawSummaryJson] 的对应字段，其余字段原样保留。
     */
    private fun saveSummary() {
        if (recordTransferId <= 0) {
            toast(getString(R.string.native_detail_toast_no_record_id))
            return
        }
        val body = binding.nativeDetailSummaryText.getText().toString()
        val payload = writeSummaryBody(rawSummaryJson, body) ?: return
        manager.saveRecordTransferSummaryResult(recordTransferId, payload,
            object : IResultCallback {
                override fun onSuccess() {
                    runOnUi {
                        toast(getString(R.string.native_detail_toast_saved))
                        // 本地写成功后再写云端，两处内容必须是同一份 JSON
                        saveSummaryOnCloud(payload)
                        loadDetail()
                    }
                }

                override fun onError(code: String?, error: String?) {
                    toastRecordError(code)
                }
            })
    }

    /**
     * 手动改名后，把新名字同步进总结 JSON 的 `title`。
     * 
     * 
     * **不做这一步，改完的名字会自己变回去。** 总结里的 `title` 是文件名的另一个副本，
     * [.applySummaryTitle] 会在每次加载总结后用它回写文件名；
     * 只改文件名不改 `title`，下一次 [.loadDetail] 就把旧名字盖回来了。
     * 
     * 
     * 顺序也有讲究：**先写 title，再刷新详情**。反过来的话，刷新触发的总结加载会读到旧
     * `title`，同样会把名字改回去。
     * 
     * 
     * AI 笔记也是这么做的：改名时「改文件名 + 重写总结 title」两件事一起做。
     * 
     * @param newName 新文件名
     */
    private fun syncTitleToSummary(newName: String?) {
        currentName = newName
        binding.nativeDetailName.setText(newName)

        val payload = writeSummaryTitle(rawSummaryJson, newName)
        if (payload == null || recordTransferId <= 0) {
            // 这条录音还没有总结，不存在 title 副本，直接刷新即可
            loadDetail()
            return
        }
        rawSummaryJson = payload
        // 只写本地：拉锯问题的根源是本地那份 title（取总结优先读本地库）。
        // 不调 m.wearable.audio.summary.edit——那个接口语义是「用户编辑了总结正文」，
        // 改名时调它会把这条记录误标成总结被人工改过。
        manager.saveRecordTransferSummaryResult(
            recordTransferId,
            payload,
            object : IResultCallback {
                override fun onSuccess() {
                    runOnUi { this@NativeRecordDetailActivity.loadDetail() }
                }

                override fun onError(code: String?, error: String?) {
                    // title 没同步上，但文件名已经改好了，刷新后可能被总结标题覆盖回去
                    runOnUi {
                        toastRecordError(code)
                        loadDetail()
                    }
                }
            })
    }

    /**
     * 把总结正文同步到云端。本地写入成功后调用。
     * 
     * 
     * SDK 的 `saveRecordTransferSummaryResult` **只写本地库**，
     * 不补这一步，人工纠错的结果换台设备就看不到了——这是接入时最容易漏的一环。
     * 
     * 
     * 失败**不回滚本地**，只提示：本地已经改好了，回滚等于把用户的编辑丢掉；
     * 「本地已改、云端未同步」是真实存在的中间态，让它可见比藏起来好。
     * 
     * @param payload 与写入本地完全相同的总结 JSON 字符串
     */
    private fun saveSummaryOnCloud(payload: String?) {
        if (TextUtils.isEmpty(recordId)) return
        contentBusiness.editSummary(recordId, payload, object : Business.ResultListener<Boolean?> {
            override fun onSuccess(
                response: BusinessResponse?,
                result: Boolean?,
                apiName: String?
            ) {
                // 云端写入成功，界面已在本地写入时刷新过，此处无需再动
            }

            override fun onFailure(
                response: BusinessResponse?,
                result: Boolean?,
                apiName: String?
            ) {
                val code = if (response == null) "-" else response.getErrorCode()
                val msg = if (response == null) "" else response.getErrorMsg()
                runOnUi { toast(getString(R.string.native_toast_api_failed, code, msg)) }
            }
        })
    }

    /**
     * 渲染总结结果，并同步刷新「思维导图」Tab。
     * 
     * 
     * 正文区只放 `summary` 字段（可编辑、可原样写回）；
     * `outline` / `question` 是思维导图的数据源，放到第三个 Tab 展示结构。
     * 
     * @param json 总结结果 JSON 字符串，可能为 null
     */
    private fun renderSummary(json: String?) {
        rawSummaryJson = json
        binding.nativeDetailSummaryText.setText(TransferTextParser.parseSummaryBody(json ?: ""))

        val outlineJson = parseSummaryOutlineJson(json)
        binding.nativeDetailMindmapJson.setText(
            if (TextUtils.isEmpty(outlineJson))
                getString(R.string.native_detail_mindmap_empty)
            else
                outlineJson
        )
    }

    // ===================== 已读跃迁 =====================
    /**
     * 进入详情即视为已读，把未读态跃迁为对应的已读态。
     * 
     * 
     * `visit` 有四个取值，两两配对——转写前后各有一组未读 / 已读：
     * <pre>
     * 0 未读        → 1 已读
     * 2 已转录未读  → 3 已转录已读
     * </pre>
 * 
     * 已经是 `1` 或 `3` 时不做任何事，**跃迁是单向的**，不能把已读改回未读。
     * 分成两组是因为「转写完成」本身要在列表上提示用户，转写完成后红点会重新亮起。
     * 
     * 
     * 与改名一样要写两处：`updateRecordTransferResult` 改本地库让红点立刻消失，
     * atop 接口同步云端让其他端一致。云端这一路是 fire-and-forget——
     * 失败既不回滚本地也不提示用户，已读状态丢一次的代价远小于打断阅读。
     * 
     * @param visit 详情返回的当前访问状态，可能为 null
     */
    private fun markReadIfNeeded(visit: Int?) {
        if (visit == null || recordTransferId <= 0) return
        val target: Int
        if (visit == VISIT_UNREAD) {
            target = VISIT_READ
        } else if (visit == VISIT_TRANSCRIBED_UNREAD) {
            target = VISIT_TRANSCRIBED_READ
        } else {
            return
        }

        // 本地：visit 参数是 String，兼容 "true"/"false"/数字字符串，此处传数字字符串
        manager.updateRecordTransferResult(
            recordTransferId, null, null,
            target.toString(), null, null, null, null,
            object : IResultCallback {
                override fun onSuccess() {
                }

                override fun onError(code: String?, error: String?) {
                    toastRecordError(code)
                }
            })
        markReadOnCloud(target)
    }

    /**
     * 同步已读状态到云端。fire-and-forget，失败只记日志。
     * 
     * @param visit 目标已读态
     */
    private fun markReadOnCloud(visit: Int) {
        if (TextUtils.isEmpty(recordId)) return
        contentBusiness.markRecordRead(recordId, visit, object : Business.ResultListener<Boolean?> {
            override fun onSuccess(
                response: BusinessResponse?,
                result: Boolean?,
                apiName: String?
            ) {
            }

            override fun onFailure(
                response: BusinessResponse?,
                result: Boolean?,
                apiName: String?
            ) {
                val code = if (response == null) "-" else response.getErrorCode()
            }
        })
    }

    // ===================== 总结标题回写文件名 =====================
    /**
     * 总结 JSON 里若带 `title`，且与当前文件名不同，则把文件名改成它。
     * 
     * 
     * 这是「AI 给录音起名」的能力：录音刚生成时文件名多是时间戳之类的默认值，
     * 总结完成后用 AI 提炼的标题替换，列表里才好辨认。
     * 
     * 
     * 三点需要注意：
     * 
     *  * 文件名在本地库与云端各存一份，**两处都要写**——
     * `updateRecordTransferResult` 改本地，[AudioContentBusiness] 改云端。
     * 这与「更多操作」里的手动重命名是同一套写法
     *  * 必须先比对再改。总结每次重新加载都会走到这里，不比对就会反复发起无意义的更新
     *  * 这条路径无需重写云端的总结内容（`title` 本就是从总结里读出来的），
     * 与文件名是否同步到云端是两回事
     * 
     * 
     * @param title 总结里解析出的标题，可能为 null
     */
    private fun applySummaryTitle(title: String?) {
        if (TextUtils.isEmpty(title) || recordTransferId <= 0) return
        if (TextUtils.equals(title, currentName)) return

        manager.updateRecordTransferResult(
            recordTransferId, title,
            null, null, null, null, null, null,
            object : IResultCallback {
                override fun onSuccess() {
                    runOnUi {
                        currentName = title
                        binding.nativeDetailName.setText(title)
                        renameOnCloud(title)
                    }
                }

                override fun onError(code: String?, error: String?) {
                    toastRecordError(code)
                }
            })
    }

    /**
     * 把新文件名写到云端。本地写入成功后调用。
     * 
     * 
     * 失败**不回滚本地**，只记录日志——「本地已改、云端未同步」是真实存在的中间态，
     * 藏起来反而误导。
     * 
     * @param name 新文件名
     */
    private fun renameOnCloud(name: String?) {
        if (TextUtils.isEmpty(recordId)) return
        val homeId = currentHomeId()
        if (homeId <= 0) {
            return
        }
        contentBusiness.updateFileName(
            recordId, name, homeId,
            object : Business.ResultListener<Boolean?> {
                override fun onSuccess(
                    response: BusinessResponse?,
                    result: Boolean?,
                    apiName: String?
                ) {
                }

                override fun onFailure(
                    response: BusinessResponse?,
                    result: Boolean?,
                    apiName: String?
                ) {
                    val code = if (response == null) "-" else response.getErrorCode()
                    val msg = if (response == null) "" else response.getErrorMsg()
                }
            })
    }

    /**
     * @return 当前家庭 ID；家庭服务不可用时返回 `0`
     */
    private fun currentHomeId(): Long {
        try {
            val familyService = MicroServiceManager.getInstance()
                .findServiceByInterface<AbsBizBundleFamilyService?>(AbsBizBundleFamilyService::class.java.getName())
            return if (familyService == null) 0L else familyService.getCurrentHomeId()
        } catch (e: Exception) {
            return 0L
        }
    }

    /**
     * 长按正文 → 列出各段 → 选一段编辑。**两种转写模式共用这一个入口。**
     * 
     * 
     * 之所以不让用户直接编辑整段正文：界面上是
     * [parseTranscript] 拼出的可读文案（带时间戳、说话人），
     * 用户一旦增删行，就无法安全地映射回原始结构。逐段编辑天然保证条数不变、序号对齐。
     */
    private fun pickSegmentToEdit() {
        if (useRealtimeBranch()) {
            pickRealtimeSentence()
        } else {
            pickRecognizeSegment()
        }
    }

    /** 实时转写模式：按句列出，保存走 `saveRecordTransferRealTimeRecognizeResult`。  */
    private fun pickRealtimeSentence() {
        if (realtimeSentences.isEmpty()) {
            toast(getString(R.string.native_detail_toast_no_sentence))
            return
        }
        val items = arrayOfNulls<String>(realtimeSentences.size)
        for (i in realtimeSentences.indices) {
            val item = realtimeSentences.get(i)
            items[i] = if (TextUtils.isEmpty(item.text)) nullToEmpty(item.asr) else item.text
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.native_detail_pick_sentence)
            .setItems(
                items,
                DialogInterface.OnClickListener { d: DialogInterface?, which: Int ->
                    editSentence(realtimeSentences.get(which))
                })
            .show()
    }

    /** 文件转写模式：按段列出，保存走整份 `saveRecordTransferRecognizeResult`。  */
    private fun pickRecognizeSegment() {
        val list = originRecognizeList
        if (list == null || list.isEmpty()) {
            toast(getString(R.string.native_detail_toast_no_sentence))
            return
        }
        val size = list.size
        val items = arrayOfNulls<String>(size)
        for (i in 0..<size) {
            items[i] = nullToEmpty(segmentTranscript(originRecognizeList, i))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.native_detail_pick_sentence)
            .setItems(
                items,
                DialogInterface.OnClickListener { d: DialogInterface?, which: Int ->
                    editSegment(which)
                })
            .show()
    }

    /**
     * 编辑单句实时转写。
     * 
     * 
     * 三个内容字段按需传，`null` 表示不修改该字段——这里同时更新展示文案与原始 ASR，
     * 译文保持不变。
     */
    private fun editSentence(sentence: RecordTransferRealTimeResult) {
        val inputBinding = DialogNativeInputBinding.inflate(layoutInflater)
        val inputView = inputBinding.root
        val input = inputBinding.nativeDialogInput
        input.setText(if (TextUtils.isEmpty(sentence.text)) nullToEmpty(sentence.asr) else sentence.text)
        AlertDialog.Builder(this)
            .setTitle(R.string.native_detail_edit_sentence)
            .setView(inputView)
            .setPositiveButton(
                R.string.native_action_confirm,
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    val newText = input.getText().toString()
                    val asrId = if (sentence.asrId == null) 0L else sentence.asrId
                    manager.saveRecordTransferRealTimeRecognizeResult(
                        asrId, newText, newText, null,
                        saveCallback("saveRecordTransferRealTimeRecognizeResult")
                    )
                })
            .setNegativeButton(R.string.native_list_delete_cancel, null)
            .show()
    }

    /**
     * 编辑文件转写的某一段。
     * 
     * 
     * 只改这一段的 `transcript`，其余字段与其余段落原样保留，再**整份**提交——
     * `saveRecordTransferRecognizeResult` 是覆盖写入，提交的必须是完整数组。
     * 
     * @param index 段落序号，与 [.originRecognizeList] 的下标一致
     */
    private fun editSegment(index: Int) {
        val inputBinding = DialogNativeInputBinding.inflate(layoutInflater)
        val inputView = inputBinding.root
        val input = inputBinding.nativeDialogInput
        input.setText(nullToEmpty(segmentTranscript(originRecognizeList, index)))
        AlertDialog.Builder(this)
            .setTitle(R.string.native_detail_edit_sentence)
            .setView(inputView)
            .setPositiveButton(
                R.string.native_action_confirm,
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    saveSegment(
                        index,
                        input.getText().toString()
                    )
                })
            .setNegativeButton(R.string.native_list_delete_cancel, null)
            .show()
    }

    /**
     * 把某段的新文案写回原始数组并整份保存：先写本地，成功后再写云端。
     * 
     * 
     * 云端失败**不回滚本地**，只提示——「本地已改、云端未同步」是真实存在的中间态。
     * 
     * @param index   段落序号
     * @param newText 编辑后的文案
     */
    private fun saveSegment(index: Int, newText: String?) {
        if (recordTransferId <= 0 || originRecognizeList == null) {
            toast(getString(R.string.native_detail_toast_no_record_id))
            return
        }
        val payload = writeSegmentTranscript(originRecognizeList, index, newText) ?: return
        manager.saveRecordTransferRecognizeResult(recordTransferId, payload,
            object : IResultCallback {
                override fun onSuccess() {
                    runOnUi {
                        toast(getString(R.string.native_detail_toast_saved))
                        saveTransferOnCloud(payload)
                        loadDetail()
                    }
                }

                override fun onError(code: String?, error: String?) {
                    toastRecordError(code)
                }
            })
    }

    /**
     * 把转写正文同步到云端。本地写入成功后调用。
     * 
     * 
     * 与总结那条链路一致：SDK 的 `save*` 只写本地，不补这一步换台设备就看不到编辑结果。
     * 本接口比总结多一个 `devId`。
     * 
     * @param payload 与写入本地完全相同的转写 JSON 字符串
     */
    private fun saveTransferOnCloud(payload: String?) {
        if (TextUtils.isEmpty(recordId) || TextUtils.isEmpty(deviceId)) return
        contentBusiness.editContent(
            deviceId, recordId, payload,
            object : Business.ResultListener<Boolean?> {
                override fun onSuccess(
                    response: BusinessResponse?,
                    result: Boolean?,
                    apiName: String?
                ) {
                    // 云端写入成功，界面已在本地写入时刷新过，此处无需再动
                }

                override fun onFailure(
                    response: BusinessResponse?,
                    result: Boolean?,
                    apiName: String?
                ) {
                    val code = if (response == null) "-" else response.getErrorCode()
                    val msg = if (response == null) "" else response.getErrorMsg()
                    runOnUi {
                        toast(
                            getString(
                                R.string.native_toast_api_failed,
                                code,
                                msg
                            )
                        )
                    }
                }
            })
    }

    /**
     * 创建对话框用的输入区。裸 `new EditText(context)` 没有背景与内边距，
     * 空输入时在对话框里几乎看不见，故统一用布局。
     * 取值用 `findViewById(R.id.native_dialog_input)`。
     * 
     * @return 输入区根视图
     */
    private fun createInputView(): View {
        return getLayoutInflater().inflate(R.layout.dialog_native_input, null)
    }

    /**
     * 按录音链路的错误码表给出可读提示。
     * 
     * 
     * 本模块**没有专属错误码**，转写 / 总结失败走的是录音链路那张表
     * （`9006`、`10001`~`10101`、AI 基座 `39001`~`39012`）。
     * 详情页不涉及电话录音模式，故 `isCallMode` 固定传 `false`。
     * 
     * 
     * SDK 回调可能在子线程，内部已切回主线程。
     * 
     * @param code 错误码
     */
    private fun toastRecordError(code: String?) {
        runOnUi { toast(messageOf(this, code, false)) }
    }

    /**
     * 构造保存类接口的统一回调：成功后执行续作并重拉正文，失败按错误码提示。
     * 
     * @param action 动作名，用于日志区分
     */
    private fun saveCallback(action: String?): IResultCallback {
        return object : IResultCallback {
            override fun onSuccess() {
                runOnUi {
                    toast(getString(R.string.native_detail_toast_saved))
                    loadDetail()
                }
            }

            override fun onError(code: String?, error: String?) {
                toastRecordError(code)
            }
        }
    }

    /** 文件转写模式的正文：返回 JSON 字符串，交由 [TransferTextParser] 解析。  */
    private fun loadRecognizeResult() {
        if (recordTransferId <= 0) return
        manager.getRecordTransferRecognizeResult(
            recordTransferId, RESULT_FROM_LOCAL,
            object : IRecordCallBack<String?> {
                override fun onSuccess(text: String?) {
                    runOnUi {
                        // 原始数组单独留一份，编辑保存时以它为模板
                        originRecognizeList = parseTranscriptArray(text ?: "")
                        binding.nativeDetailTransferText.setText(parseTranscript(text ?: "", durationMs))
                    }
                }

                override fun onError(code: String?, error: String?) {
                    toastRecordError(code)
                }
            })
    }

    private fun loadSummaryResult() {
        if (recordTransferId <= 0) return
        manager.getRecordTransferSummaryResult(
            recordTransferId, RESULT_FROM_LOCAL,
            object : IRecordCallBack<String?> {
                override fun onSuccess(text: String?) {
                    runOnUi {
                        renderSummary(text)
                        // 总结里带的 AI 标题用来替换默认文件名
                        applySummaryTitle(parseSummaryTitle(text))
                    }
                }

                override fun onError(code: String?, error: String?) {
                    toastRecordError(code)
                }
            })
    }

    // ===================== 触发生成 =====================
    /** 触发转写。仅「转写中」拦截，其余状态允许重新触发。  */
    private fun generateTransfer() {
        if (recordTransferId <= 0) {
            toast(getString(R.string.native_detail_toast_no_record_id))
            return
        }
        if (transferStatus == TRANSFER_RUNNING) {
            toast(getString(R.string.native_detail_toast_transfer_running))
            return
        }
        requestGenerate(TASK_TRANSCRIPTION)
    }

    /**
     * 触发总结。
     * 
     * 
     * **未转写过时会降级为转写任务**——总结以转写结果为输入，没有转写就没有可总结的内容，
     * 此时下发 `taskType=1` 不会产出任何东西。底层做转写时会连带产出总结，
     * 所以降级不会让用户少拿到东西。
     */
    private fun generateSummary() {
        if (recordTransferId <= 0) {
            toast(getString(R.string.native_detail_toast_no_record_id))
            return
        }
        if (summaryStatus == SUMMARY_RUNNING) {
            toast(getString(R.string.native_detail_toast_summary_running))
            return
        }
        if (transferStatus == TRANSFER_NOT_STARTED) {
            toast(getString(R.string.native_detail_toast_downgrade))
            requestGenerate(TASK_TRANSCRIPTION)
            return
        }
        requestGenerate(TASK_SUMMARY)
    }

    /**
     * 提交转写 / 总结任务。
     * 
     * 
     * `onSuccess` **只代表任务已提交**，不代表完成。未开云同步时底层会先上传音频，
     * 进度由 [ITransferListener] 推送；完成状态由文件更新事件推送。
     * 
     * @param taskType [.TASK_TRANSCRIPTION] 或 [.TASK_SUMMARY]
     */
    private fun requestGenerate(taskType: Int) {
        val lang = if (TextUtils.isEmpty(originalLanguage)) DEFAULT_LANGUAGE else originalLanguage
        val taskName = if (taskType == TASK_TRANSCRIPTION)
            getString(R.string.native_detail_task_transcription)
        else
            getString(R.string.native_detail_task_summary)
        // 三个可选参数：模板留空即用默认；总结语言留空即跟随转写语言；说话人分离按需开
        val template = binding.nativeDetailTemplate.getText().toString().trim { it <= ' ' }
        val summaryLang = binding.nativeDetailSummaryLang.getText().toString().trim { it <= ' ' }
        val enableSpeaker = binding.nativeDetailEnableSpeaker.isChecked()

        // 构造顺序：(fileId, template, transferType, audioLang, transLang, summaryLang, enableSpeaker)
        val param = TranscribeParam(
            recordTransferId, template, taskType, lang, null,
            if (TextUtils.isEmpty(summaryLang)) null else summaryLang, enableSpeaker
        )
        manager.processRecordTransferResult(param, object : IResultCallback {
            override fun onSuccess() {
                runOnUi {
                    toast(
                        if (taskType == TASK_TRANSCRIPTION)
                            getString(R.string.native_detail_toast_generating)
                        else
                            getString(R.string.native_detail_toast_summary_generating)
                    )
                }
            }

            override fun onError(code: String?, error: String?) {
                toastRecordError(code)
            }
        })
    }

    // ===================== 更多操作 =====================
    /** 唤起单条录音的操作面板（分享 / 下载 / 重命名 / 标签 / 彻底删除）。  */
    private fun showMoreActions() {
        if (TextUtils.isEmpty(recordId)) {
            toast(getString(R.string.native_detail_toast_no_record_id))
            return
        }
        NativeRecordActionSheet.show(
            this, recordId ?: return, recordTransferId, currentName,
            object : NativeRecordActionSheet.Callback {
                override fun onDataChanged() {
                    loadDetail()
                }

                override fun onRenamed(newName: String?) {
                    syncTitleToSummary(newName)
                }

                override fun onRecordDeleted() {
                    // 记录已彻底删除，详情不再存在，直接返回列表
                    toast(getString(R.string.native_list_delete_success))
                    finish()
                }

                override fun onExportTranscript() {
                    export(ExportKind.ASR)
                }

                override fun onExportTranslation() {
                    export(ExportKind.TRANSLATION)
                }
            })
    }

    // ===================== 导出转写 / 翻译 =====================
    /**
     * 导出内容的种类。转写与译文各导一份独立文件，下游通常只要其中一份。
     */
    private enum class ExportKind(
        /** 文件名里的类型后缀。  */
        val fileSuffix: String
    ) {
        /** ASR 原文。  */
        ASR("_asr"),

        /** 译文。  */
        TRANSLATION("_translation")
    }

    /**
     * 把当前已加载到内存的转写正文导出成 `.txt`，再交给系统分享。
     * 
     * 
     * 导出的是**本地这一份**转写内容：数据就是详情页正在展示的那份，不再请求云端，
     * 段落若被编辑过也会原样带出。文件落在应用私有目录
     * （`getExternalFilesDir(DIRECTORY_DOCUMENTS)`），读写**不需要任何存储权限**，
     * 用户经系统分享把它发到别处；私有目录里的文件在应用卸载时随之清除，此处不做清理。
     * 
     * 
     * **复制本方法到自己的工程时**，AndroidManifest 里的 `FileProvider` 节点与
     * `res/xml/native_file_paths.xml`、[TranscriptFileProvider] 必须一并带上，否则
     * [TranscriptFileProvider.getUriForFile] 会抛 `IllegalArgumentException`。
     */
    private fun export(kind: ExportKind) {
        val content = buildExportText(kind)
        if (TextUtils.isEmpty(content)) {
            toast(
                getString(
                    if (kind == ExportKind.ASR)
                        R.string.native_detail_toast_export_empty
                    else
                        R.string.native_detail_toast_export_translation_empty
                )
            )
            return
        }
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        if (dir == null) {
            toastExportFailed("external files dir unavailable")
            return
        }
        if (!dir.exists() && !dir.mkdirs()) {
            toastExportFailed("create dir failed")
            return
        }
        val file = File(dir, exportFileName(kind))
        // 同名覆盖：一条记录始终只保留最新的一份导出结果
        try {
            OutputStreamWriter(FileOutputStream(file), "UTF-8").use { writer ->
                writer.write(content)
            }
        } catch (e: Exception) {
            toastExportFailed(e.message.toString())
            return
        }
        shareTranscriptFile(file)
    }

    /**
     * 取出待导出的转写正文。
     * 
     * 
     * 两种转写模式的正文存放形态不同，这里按 [.useRealtimeBranch] 分流——
     * 与取数、编辑保存的分流口径完全一致：实时模式用句子列表，文件模式用原始 JSON 数组。
     * 
     * @return 可读多行文本；尚无转写内容时返回空串
     */
    private fun buildExportText(kind: ExportKind): String {
        if (useRealtimeBranch()) {
            return buildRealtimeExportText(realtimeSentences, kind)
        }
        val list = originRecognizeList
        if (list == null || list.isEmpty()) {
            return ""
        }
        return buildOfflineExportText(list, kind)
    }

    /**
     * 实时转写分支的导出正文，一行一段。
     * 
     * 
     * 与界面用的 [.buildRealtimeText] 的差别就是**不带时间戳、不带译文标识符**：
     * 那两样在界面上有用（知道某句在第几分钟、哪行是译文），导出给下游做语料或对比时是噪声。
     * 
     * 
     * 这条分支**拿不到说话人**——`RecordTransferRealTimeResult` 里没有该字段。
     * 
     * @param result 实时转写结果
     * @param kind   导出原文还是译文
     * @return 导出正文；无内容时返回空串
     */
    private fun buildRealtimeExportText(
        result: MutableList<RecordTransferRealTimeResult>?,
        kind: ExportKind
    ): String {
        if (result == null || result.isEmpty()) {
            return ""
        }
        val sb = StringBuilder()
        for (item in result) {
            val line = if (kind == ExportKind.ASR)
                (if (TextUtils.isEmpty(item.text)) nullToEmpty(item.asr) else item.text)
            else
                nullToEmpty(item.translate)
            if (TextUtils.isEmpty(line)) {
                continue
            }
            sb.append(line.trim { it <= ' ' }).append('\n')
        }
        return sb.toString().trim { it <= ' ' }
    }

    /**
     * 离线 / 云端分支的导出正文，一行一段，保留说话人前缀。
     * 
     * 
     * 不复用 [parseTranscript]：那个方法产出的是给界面看的文案
     * （带时间戳、原文与译文交错），用正则把装饰剥回来是最脆的做法——正文里本来就可能出现
     * `[00:12]` 或 `➜` 这类字符，剥错了不报错，只会静默损坏数据。
     * 
     * 
     * **某个分段没有译文时该行直接跳过**，因此译文文件的行数不一定与原文文件对齐。
     * 
     * @param list 转写分段的原始 JSON
     * @param kind 导出原文还是译文
     * @return 导出正文；无内容时返回空串
     */
    private fun buildOfflineExportText(list: JSONArray, kind: ExportKind): String {
        val sb = StringBuilder()
        for (i in list.indices) {
            val seg = list.getJSONObject(i)
            if (seg == null) {
                continue
            }
            val body = if (kind == ExportKind.ASR)
                seg.getString("transcript")
            else
                seg.getString("translation")
            if (TextUtils.isEmpty(body)) {
                continue
            }
            val speaker = seg.getString("speaker")
            if (!TextUtils.isEmpty(speaker)) {
                sb.append(speaker).append(": ")
            }
            sb.append((body ?: "").trim { it <= ' ' }).append('\n')
        }
        return sb.toString().trim { it <= ' ' }
    }

    /**
     * 拼出导出文件名：文件名取自当前记录名，非法字符已过滤。
     * 
     * @return 形如 `周会纪要.txt` 的文件名
     */
    private fun exportFileName(kind: ExportKind): String {
        var name: String = sanitizeFileName(currentName)
        if (TextUtils.isEmpty(name)) {
            // 记录名可以为空，此时用默认名加主键，保证不同记录导出的文件不互相覆盖
            name = (getString(R.string.native_detail_export_default_name)
                    + (if (recordTransferId > 0) "_" + recordTransferId else ""))
        }
        // 类型后缀用英文：这份文件通常要发到 PC 上做后续处理，中文名跨平台传递容易出问题
        return name + kind.fileSuffix + EXPORT_FILE_SUFFIX
    }

    /**
     * 用系统分享把导出的文件交给用户。
     * 
     * 
     * 私有目录的 `file://` 路径不能直接跨进程传递，必须经 [TranscriptFileProvider]
     * 换成 `content://` 并附带 [Intent.FLAG_GRANT_READ_URI_PERMISSION]，
     * 否则接收方读取时会被拒绝。
     * 
     * @param file 已写好的导出文件
     */
    private fun shareTranscriptFile(file: File) {
        try {
            val uri: Uri? = FileProvider.getUriForFile(
                this,
                getPackageName() + FILE_PROVIDER_SUFFIX, file
            )
            val intent = Intent(Intent.ACTION_SEND)
            intent.setType(MIME_TEXT_PLAIN)
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.putExtra(Intent.EXTRA_SUBJECT, file.getName())
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(
                Intent.createChooser(
                    intent,
                    getString(R.string.native_detail_export_chooser)
                )
            )
        } catch (e: Exception) {
            toastExportFailed(e.message.toString())
        }
    }

    private fun toastExportFailed(reason: String?) {
        toast(getString(R.string.native_detail_toast_export_failed, reason))
    }

    // ===================== 辅助 =====================
    private fun transferBadgeText(t: Int): String {
        when (t) {
            1 -> return getString(R.string.native_transfer_1)
            2 -> return getString(R.string.native_transfer_2)
            3 -> return getString(R.string.native_transfer_3)
            else -> return getString(R.string.native_transfer_0)
        }
    }

    private fun summaryBadgeText(s: Int): String {
        when (s) {
            2 -> return getString(R.string.native_summary_2)
            3 -> return getString(R.string.native_summary_3)
            4 -> return getString(R.string.native_summary_4)
            else -> return getString(R.string.native_summary_1)
        }
    }

    /**
     * 下载回调，只为感知**本条录音的音频何时下完**。
     * 
     * 
     * `syncDownloadNoteAudio` 同步返回且没有回调，结果只能从这里拿。
     * 而这组回调里**只有 `onFinish` 是真正的结束**——
     * `downloadSuccess` / `downloadError` 语义仍是「下载中」，
     * 后者的错误码与消息还是无效的，都不能用来判完成。
     * 
     * 
     * 回调是**批次级**的：一次 `onFinish` 可能包含多条记录，
     * 因此要按 `recordId` 过滤出本条。批次里没有本条就静默忽略——
     * 那是云同步在后台补别的文件，与当前页面无关。
     */
    private inner class AudioDownloadListener : DownloadListener {
        override fun onStart() {
        }

        override fun onPause() {
        }

        override fun downloading(
            recordFile: RecordFile, downloadedBytes: Long,
            totalBytes: Long, progressPercent: Int
        ) {
        }

        override fun downloadSuccess(recordFile: RecordFile) {
            // 语义仍是「下载中」，不能当完成用
        }

        override fun downloadError(
            recordFile: RecordFile, errorCode: String,
            errorMsg: String
        ) {
            // 同上，且这里的 errorCode / errorMsg 无效
        }

        override fun downloadErrorBatch(
            recordFiles: List<RecordFile>, errorCode: Int,
            errorMsg: String
        ) {
        }

        override fun onFinish(
            succeedRecords: List<RecordFile>,
            failedRecords: List<RecordFile>
        ) {
            if (contains(succeedRecords)) {
                runOnUi { toast(getString(R.string.native_action_toast_download_done)) }
            } else if (contains(failedRecords)) {
                runOnUi { toast(getString(R.string.native_action_toast_download_failed)) }
            }
        }

        /**
         * @param records 批次里的记录
         * @return 是否包含当前详情页这条录音
         */
        fun contains(records: List<RecordFile>?): Boolean {
            if (records == null || TextUtils.isEmpty(recordId)) return false
            for (file in records) {
                if (file != null && recordId == file.recordId) {
                    return true
                }
            }
            return false
        }
    }

    /**
     * 上传回调空实现。
     * 
     * 
     * [SyncObserver] 是持有下载与上传两个监听器的 data class，
     * **构造时两者都必须提供**，本页只关心下载，故上传侧全部留空。
     * 云同步的聚合状态在 [NativeCloudSyncActivity] 里维护，不由本页负责。
     */
    private class NoopUploadListener : UploadListener {
        override fun onStart() {
        }

        override fun onPause() {
        }

        override fun uploading(recordFile: RecordFile, progress: Int) {
        }

        override fun uploadSuccess(recordFile: RecordFile) {
        }

        override fun uploadError(
            recordFile: RecordFile, errorCode: String,
            errorMsg: String
        ) {
        }

        override fun onFinish(
            succeedRecords: List<RecordFile>,
            failedRecords: List<RecordFile>
        ) {
        }

        override fun onError(errorCode: Int?, errorMsg: String?) {
        }
    }

    companion object {
        const val EXTRA_RECORD_ID: String = "extra_record_id"

        // ===== RecordTransferResultBean.transfer =====
        /** 未转写。  */
        private const val TRANSFER_NOT_STARTED = 0

        /** 转写中。  */
        private const val TRANSFER_RUNNING = 1

        /** 已转写。  */
        private const val TRANSFER_DONE = 2

        // ===== RecordTransferResultBean.summary =====
        /** 总结中。  */
        private const val SUMMARY_RUNNING = 2

        /** 已总结。  */
        private const val SUMMARY_DONE = 3

        // ===== RecordTransferResultBean.transferType =====
        /** 实时转写。  */
        private const val TRANSFER_TYPE_REALTIME = 1

        // ===== TranscribeParam.transferType（任务类型）=====
        /** 任务：转写。  */
        private const val TASK_TRANSCRIPTION = 0

        /** 任务：总结。  */
        private const val TASK_SUMMARY = 1

        // ===== ITransferListener 的 status =====
        /** 等待上传。  */
        private const val UPLOAD_WAITING = 0

        /** 上传完成。  */
        private const val UPLOAD_COMPLETED = 2

        // ===== RecordTransferResultBean.visit，转写前后各有一组未读 / 已读 =====
        /** 未读。  */
        private const val VISIT_UNREAD = 0

        /** 已读。  */
        private const val VISIT_READ = 1

        /** 已转录未读。  */
        private const val VISIT_TRANSCRIBED_UNREAD = 2

        /** 已转录已读。  */
        private const val VISIT_TRANSCRIBED_READ = 3

        /**
         * 取结果接口的 `from` 入参。
         * 
         * 
         * ⚠️ **该参数已废弃、不参与任何判断**：底层固定「先查本地库，查不到或失败自动回退云端」。
         * 保留只为兼容既有签名，约定传 `0`。
         */
        private const val RESULT_FROM_LOCAL = 0

        /** 详情接口的振幅采样上限，0 表示取全量。  */
        private const val AMPLITUDE_FULL = 0

        /** 未取到源语言时的兜底值。  */
        private const val DEFAULT_LANGUAGE = "zh"

        /**
         * 导出转写用的 FileProvider authority 后缀，与 AndroidManifest 中的
         * `${applicationId}.aivoice.fileprovider` 保持一致。
         */
        private const val FILE_PROVIDER_SUFFIX = ".aivoice.fileprovider"

        /** 导出文件的 MIME，纯文本，接收方（微信、邮件等）可直接预览。  */
        private const val MIME_TEXT_PLAIN = "text/plain"

        /** 导出文件名的最大字符数，超出截断，避免超过文件系统上限。  */
        private const val MAX_EXPORT_NAME_LENGTH = 60

        /** 导出文件扩展名。  */
        private const val EXPORT_FILE_SUFFIX = ".txt"

        /**
         * 过滤文件名中的非法字符。
         * 
         * 
         * 记录名是用户可自由编辑的，其中只要含一个 `/` 就会让文件创建失败，
         * 这里统一替换成下划线并限长。
         * 
         * @param raw 原始记录名，可为 `null`
         * @return 可安全用作文件名的字符串，可能为空串
         */
        private fun sanitizeFileName(raw: String?): String {
            if (raw == null) return ""
            val cleaned = raw.replace("[\\\\/:*?\"<>|\\r\\n]".toRegex(), "_").trim { it <= ' ' }
            return if (cleaned.length > MAX_EXPORT_NAME_LENGTH)
                cleaned.substring(0, MAX_EXPORT_NAME_LENGTH)
            else
                cleaned
        }

        private fun nullToEmpty(s: String?): String {
            return if (s == null) "" else s
        }

        private fun formatDuration(ms: Long): String {
            if (ms <= 0) return "00:00"
            val totalSec = ms / 1000
            return String.format(Locale.getDefault(), "%02d:%02d", totalSec / 60, totalSec % 60)
        }
    }
}
