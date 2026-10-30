package com.tuya.smart.ai_voice.nativeui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.text.TextUtils
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.thingclips.smart.ai.audio.utils.PhoneMicAudioInputFactory
import com.thingclips.smart.earphone.enhance.api.bean.AudioFrontInputConfig
import com.thingclips.smart.earphone.enhance.api.bean.AudioFrontInputConfig.AncConfig
import com.thingclips.smart.earphone.enhance.api.bean.AudioFrontInputConfig.ReplaceCaptureData
import com.thingclips.smart.earphone.enhance.api.bean.RealTimeTransferStatus
import com.thingclips.smart.earphone.enhance.api.bean.RecordParamsV2
import com.thingclips.smart.earphone.enhance.api.bean.def.RecordStatusDef
import com.thingclips.smart.earphone.enhance.api.bean.RecordStatusBean
import com.thingclips.smart.earphone.enhance.api.bean.RecordTransferRealTimeResult
import com.thingclips.smart.earphone.enhance.api.bean.TTSConfig
import com.thingclips.smart.earphone.enhance.api.bean.TTSEncode
import com.thingclips.smart.earphone.enhance.api.bean.TTSOutput
import com.thingclips.smart.earphone.enhance.api.bean.TTSOutputChannel
import com.thingclips.smart.earphone.enhance.api.listener.IRecordCallBack
import com.thingclips.smart.earphone.enhance.api.listener.IRecordListener
import com.thingclips.smart.sdk.api.IResultCallback
import com.tuya.smart.ai_voice.R
import com.tuya.smart.ai_voice.databinding.ActivityNativeInjectBatchBinding
import com.tuya.smart.ai_voice.nativeui.base.NativeDemoBaseActivity
import com.tuya.smart.ai_voice.nativeui.widget.AudioInjectFileHelper.Corpus
import com.tuya.smart.ai_voice.nativeui.widget.AudioInjectFileHelper.clearImported
import com.tuya.smart.ai_voice.nativeui.widget.AudioInjectFileHelper.ensureFileReady
import com.tuya.smart.ai_voice.nativeui.widget.AudioInjectFileHelper.listCorpus
import com.tuya.smart.ai_voice.nativeui.widget.AudioInjectFileHelper.readReferenceText
import com.tuya.smart.ai_voice.nativeui.widget.AudioInjectFileHelper.readWavInfo
import com.tuya.smart.ai_voice.nativeui.widget.InjectCorpusImporter
import com.tuya.smart.ai_voice.nativeui.widget.InjectCorpusImporter.buildPickFilesIntent
import com.tuya.smart.ai_voice.nativeui.widget.InjectEndDispatcher
import com.tuya.smart.ai_voice.nativeui.widget.InjectEndDispatcher.Companion.get
import com.tuya.smart.ai_voice.nativeui.widget.InjectReportBuilder
import com.tuya.smart.ai_voice.nativeui.widget.InjectReportBuilder.reportDir
import com.tuya.smart.ai_voice.nativeui.widget.InjectRoundResult
import com.tuya.smart.ai_voice.nativeui.widget.TranscriptFileProvider
import com.tuya.smart.ai_voice.nativeui.widget.WavFileInfo
import com.tuya.smart.ai_voice.nativeui.widget.WerCalculator
import com.tuya.smart.ai_voice.nativeui.widget.WerCalculator.evaluate
import com.tuya.smart.ai_voice.nativeui.widget.WerCalculator.normalizeReferenceLines
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * 灌流批量测试。用固定音频连续跑多轮 ASR，自动算 WER 并导出报告。
 * 
 * 
 * 存在的意义是**让识别质量可度量**：真人对着麦克风念一遍，每次的音量、语速、
 * 环境噪声都不同，结果无法比对；灌固定文件进去、跑多轮、和参考答案算 WER，
 * 才谈得上「这次改动让识别变好了还是变差了」。
 * 
 * <h3>一轮的状态机</h3>
 * <pre>
 * 下发灌流配置 → startAudioRecording → 等灌流播完（AudioInjectEventManager）
 * → stopRecordTransfer → 等 status=STOP
 * → getRecordTransferRealTimeResult(recordId) → 算 WER → 下一轮
 * </pre>
 * 
 * 任一环节卡住由单轮超时兜底（音频时长 × [.TIMEOUT_FACTOR] +
 * [.TIMEOUT_PADDING_MS]），超时判该轮失败并**继续下一轮**——
 * 跑批的价值在于无人值守，一轮卡住就全盘中止等于白跑一夜。
 * 
 * <h3>数据从哪来</h3>
 * 
 *  * 音频：assets `audio_inject/` 内置，或用户经 SAF 从手机本地目录导入
 * （见 [InjectCorpusImporter]），只认 wav
 *  * 参考答案：与音频同名的 txt，查找顺序与音频一致——先导入目录、再 assets
 *  * 语言与能力开关：**来自本页的配置区，整批共用**（[BatchConfig]）。
 * 早前是逐文件写在 assets 清单里的，改成页面配置后不用为了换语言重新编包，
 * 代价是同一批只能一个源语言
 * 
 * 
 * <h3>识别结果的两个来源</h3>
 * 界面上滚动的实时文本来自 `onRealTimeStatusUpdate`，只为让人看出「还活着」；
 * **参与 WER 计算的一律是 `getRecordTransferRealTimeResult` 拉回来的结果**——
 * 实时推送有状态机、跑批时可能丢句，不能作为评分依据。
 */
class NativeInjectBatchActivity : NativeDemoBaseActivity() {
    /** 单轮流程所处的阶段，仅用于界面提示。  */
    private enum class Stage {
        IDLE,
        INJECTING,
        WAITING_FINISH,
        FETCHING,
        SCORING
    }

    /** 文件列表的一行。  */
    private class FileRow(val corpus: Corpus, val wav: WavFileInfo) {
        var box: CheckBox? = null

        /** @return 能否参与跑批。语言等配置来自页面，唯一门槛是音频格式
         */
        fun runnable(): Boolean {
            return wav.isDecodable
        }
    }

    /**
     * 一次跑批的参数，来自页面配置，**整批共用**。
     * 
     * 
     * 原先这些是逐文件写在 assets 清单里的，改成页面配置后不用为了换个语言重新编包，
     * 代价是**同一批只能一个源语言**——混语种请分批跑，累积 CSV 里有 `sourceLang` 列，
     * 跨批次筛出来看趋势完全够用。
     */
    private class BatchConfig(
        val sourceLanguage: String,
        /** 空串表示不翻译。  */
        val targetLanguage: String,
        val asr: Boolean, val translate: Boolean, val tts: Boolean
    ) {
        /** 目标语言与源语言相同等于没翻，下发给 SDK 只会白跑一趟。  */
        fun shouldTranslate(): Boolean {
            return translate && !targetLanguage.isEmpty() && (targetLanguage != sourceLanguage)
        }
    }

    /** 实时转写里的一句：原文与其译文。  */
    private class Sentence {
        var asr: String = ""
        var translate: String = ""
    }

    /** 队列里的一轮任务。  */
    private class Round(
        val corpus: Corpus, val audioDurationMs: Long,
        val repeatIndex: Int, val repeatTotal: Int
    )

    /** 当前轮次（非空访问器，替代满屏的 `current!!`）。  */
    private val currentRound: InjectRoundResult
        get() = current ?: error("no active round")

    /** 本批参数（非空访问器）。  */
    private val batchConfig: BatchConfig
        get() = config ?: error("batch config not initialized")

    // ===== 视图 =====
    // ===== 数据 =====
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val fileRows: MutableList<FileRow> = ArrayList<FileRow>()
    private val queue: MutableList<Round> = ArrayList<Round>()
    private val results: MutableList<InjectRoundResult?> = ArrayList<InjectRoundResult?>()

    /** 内容布局绑定。  */
    private lateinit var binding: ActivityNativeInjectBatchBinding

    private var recordListener: IRecordListener? = null
    private var injectEndConsumer: InjectEndDispatcher.Consumer? = null

    private var running = false
    private var roundIndex = -1
    private var stage = Stage.IDLE
    private var batchId = ""

    /** 本批跑批参数，点「开始跑批」时从页面读一次，跑批期间不再变化。  */
    private var config: BatchConfig? = null

    /** 当前轮的累积状态。  */
    private var current: InjectRoundResult? = null
    private var roundStartMs: Long = 0L

    /** 实时转写的已封句与在写句，渲染时拼在一起。  */
    private val finishedSentences: MutableList<Sentence> = ArrayList<Sentence>()
    private var currentSentence: Sentence? = null

    /** 超时任务，每轮开始时投递、结束时撤销。  */
    private val timeoutTask: Runnable = object : Runnable {
        override fun run() {
            failCurrentRound(
                InjectRoundResult.Status.TIMEOUT,
                getString(R.string.native_batch_timeout)
            )
        }
    }

    override val contentLayoutId: Int
        get() = R.layout.activity_native_inject_batch

    override val titleResId: Int
        get() = R.string.native_batch_title

    override fun onContentViewCreated() {
        bindViews()
        setupConfig()
        binding.btnBatchImportFiles.setOnClickListener(View.OnClickListener { v: View? -> pickImportFiles() })
        binding.btnBatchClearImport.setOnClickListener(View.OnClickListener { v: View? -> confirmClearImported() })
        binding.btnBatchStart.setOnClickListener(View.OnClickListener { v: View? -> onStartClicked() })
        binding.btnBatchAbort.setOnClickListener(View.OnClickListener { v: View? -> confirmAbort() })
        binding.btnBatchShare.setOnClickListener(View.OnClickListener { v: View? -> shareReport() })
        loadFiles()
        refreshControls()
    }

    private fun bindViews() {
        binding = ActivityNativeInjectBatchBinding.bind(contentView())
        // 让这个固定高度的 TextView 能在内部滚动。整页已经在 NestedScrollView 里，
        // 再套一层 ScrollView 会抢滚动事件，因此用 MovementMethod 而不是嵌套滚动容器。
        binding.tvBatchRealtime.setMovementMethod(ScrollingMovementMethod())
    }

    /**
     * 装配语言下拉与默认值。
     * 
     * 
     * 语言用下拉而不是输入框：填错语言的代价是整批数据废掉**且看不出原因**——
     * WER 会低得莫名其妙，你无从判断是识别差还是语种设错。
     * 默认 `zh → en` 与录音页一致，避免两页默认行为不同引起误会。
     */
    private fun setupConfig() {
        val sourceAdapter: ArrayAdapter<String?> = ArrayAdapter<String?>(
            this, R.layout.item_spinner_native, LANGS
        )
        sourceAdapter.setDropDownViewResource(R.layout.item_spinner_native_dropdown)
        binding.spBatchSourceLang.setAdapter(sourceAdapter)
        binding.spBatchSourceLang.setSelection(indexOf(LANGS, DEFAULT_SOURCE_LANG))

        // 目标语言多一项「不翻译」，放在首位
        val targets = arrayOfNulls<String>(LANGS.size + 1)
        targets[0] = getString(R.string.native_batch_target_none)
        System.arraycopy(LANGS, 0, targets, 1, LANGS.size)
        val targetAdapter = ArrayAdapter<String?>(
            this, R.layout.item_spinner_native, targets
        )
        targetAdapter.setDropDownViewResource(R.layout.item_spinner_native_dropdown)
        binding.spBatchTargetLang.setAdapter(targetAdapter)
        binding.spBatchTargetLang.setSelection(indexOf(targets, DEFAULT_TARGET_LANG))

        binding.cbBatchAsr.setChecked(true)
        binding.cbBatchTranslate.setChecked(true)
        binding.cbBatchTts.setChecked(true)
    }

    /** @return 从页面读一次配置
     */
    private fun readConfig(): BatchConfig {
        val source = binding.spBatchSourceLang.getSelectedItem() as String
        val target: String = if (binding.spBatchTargetLang.getSelectedItemPosition() == 0) {
            ""
        } else {
            binding.spBatchTargetLang.getSelectedItem() as? String ?: ""
        }
        return BatchConfig(
            source, target, binding.cbBatchAsr.isChecked(),
            binding.cbBatchTranslate.isChecked(), binding.cbBatchTts.isChecked()
        )
    }

    // ===================== 监听 =====================
    override fun registerListeners() {
        recordListener = object : IRecordListener {
            override fun onRecordStatusUpdate(deviceId: String, bean: RecordStatusBean?) {
                if (bean == null) return
                // recordId 只能从状态事件里拿，后面拉转写结果要用
                runOnUi {
                    bean.recordId?.let { id ->
                        if (!TextUtils.isEmpty(id)) {
                            currentRound.recordId = id
                        }
                    }
                    // 本轮结束以 status=STOP 判定（onRecordFinish 事件当前 SDK 不予触发）
                    if (bean.status == RecordStatusDef.STOP) {
                        onRecordFinished(true, null)
                    }
                }
            }

            override fun onRecordAmplitudeUpdate(
                deviceId: String,
                channel: Int,
                amplitude: Double
            ) {
                // 跑批不画波形，needAmplitude 已关，这里不会被调到
            }

            override fun onRealTimeStatusUpdate(s: RealTimeTransferStatus) {
                runOnUi { appendRealtime(s) }
            }

            override fun onRecordSwitchAudioSourceEvent(
                devId: String,
                recordType: Int,
                audioSource: Int
            ) {
            }

            override fun onRecordFinish(deviceId: String) {
                // 结束以 onRecordStatusUpdate 的 status=STOP 为准，这里不做处理
            }

            override fun onRecordErrorFinish(deviceId: String, errorCode: Int, errorMsg: String) {
                runOnUi { onRecordFinished(false, errorCode.toString() + " " + errorMsg) }
            }
        }
        manager.addRecordListener(recordListener)
    }

    override fun unregisterListeners() {
        if (recordListener != null) {
            manager.removeRecordListener(recordListener)
            recordListener = null
        }
        io.shutdownNow()
    }

    override fun onResume() {
        super.onResume()
        // 订阅灌流结束事件，接管本次处置。跑批必须静默——弹窗会阻塞后续几十轮的自动流程，
        // 所以这里不能落到 InjectEndDispatcher 的兜底逻辑上。
        val consumer = object : InjectEndDispatcher.Consumer {
            override fun onInjectEnd(deviceId: String) {
                onInjectEnd()
            }
        }
        injectEndConsumer = consumer
        get().setPageConsumer(this, consumer)
    }

    override fun onPause() {
        super.onPause()
        injectEndConsumer?.let {
            get().clearPageConsumer(it)
            injectEndConsumer = null
        }
        // 页面不在前台就中止：跑批不做后台化，留着只会让录音和灌流配置悬空
        if (running && !isChangingConfigurations()) {
            abort(getString(R.string.native_batch_abort_background))
        }
    }

    protected override fun onDestroy() {
        super.onDestroy()
        cleanupInjectConfig()
    }

    override fun onBackPressed() {
        if (running) {
            confirmAbort()
            return
        }
        super.onBackPressed()
    }

    // ===================== 语料导入 =====================
    /**
     * 拉起系统目录选择器。
     * 
     * 
     * 走 SAF 而不是直接读 `/sdcard/Download`：`targetSdk` 已到 36，
     * 分区存储下没有 `MANAGE_EXTERNAL_STORAGE` 拿不到那个目录，
     * 而 SAF **不需要任何权限**。原因详见 [InjectCorpusImporter]。
     */
    private fun pickImportFiles() {
        if (running) {
            return
        }
        try {
            startActivityForResult(
                buildPickFilesIntent(),
                REQ_CODE_PICK_FILES
            )
        } catch (e: Exception) {
            toast(getString(R.string.native_batch_import_no_picker))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CODE_PICK_FILES || resultCode != RESULT_OK || data == null) {
            return
        }
        val uris: MutableList<Uri> = collectUris(data)
        if (uris.isEmpty()) {
            return
        }
        binding.btnBatchImportFiles.setEnabled(false)
        binding.btnBatchImportFiles.setText(R.string.native_batch_btn_importing)
        io.execute {
            val result = InjectCorpusImporter.importFiles(this, uris)
            runOnUi {
                binding.btnBatchImportFiles.setEnabled(true)
                binding.btnBatchImportFiles.setText(R.string.native_batch_btn_import_files)
                onImportFinished(result)
            }
        }
    }

    private fun onImportFinished(result: InjectCorpusImporter.Result) {
        if (result.unavailable) {
            // 外置私有目录不可用，与「选了但一个都没落地」是同一类结果，用同一句提示
            toast(getString(R.string.native_batch_import_empty))
            return
        }
        if (result.isEmpty) {
            toast(getString(R.string.native_batch_import_empty))
            return
        }
        toast(
            getString(
                R.string.native_batch_import_done,
                result.audioCount, result.textCount
            )
        )
        loadFiles()
    }


    private fun confirmClearImported() {
        if (running) {
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.native_batch_clear_title)
            .setMessage(R.string.native_batch_clear_message)
            .setPositiveButton(
                R.string.native_batch_clear_confirm,
                DialogInterface.OnClickListener { d: DialogInterface?, which: Int -> clearImported() })
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clearImported() {
        io.execute {
            val removed = clearImported(this)
            runOnUi {
                toast(getString(R.string.native_batch_clear_done, removed))
                loadFiles()
            }
        }
    }

    // ===================== 文件列表 =====================
    private fun loadFiles() {
        io.execute {
            val corpora: MutableList<Corpus> = listCorpus(this)
            val rows: MutableList<FileRow> = ArrayList<FileRow>(corpora.size)
            for (corpus in corpora) {
                rows.add(FileRow(corpus, readWavInfo(this, corpus)))
            }
            runOnUi { renderFiles(rows) }
        }
    }

    private fun renderFiles(rows: MutableList<FileRow>) {
        fileRows.clear()
        fileRows.addAll(rows)
        binding.llBatchFiles.removeAllViews()
        if (rows.isEmpty()) {
            val empty = TextView(this)
            empty.setText(R.string.native_batch_no_file)
            empty.setTextSize(13f)
            binding.llBatchFiles.addView(empty)
            return
        }
        for (row in rows) {
            val box = CheckBox(this)
            box.setTextSize(13f)
            box.setText(fileLabel(row))
            box.setEnabled(row.runnable())
            if (!row.runnable()) {
                box.setAlpha(0.45f)
            }
            box.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { b: CompoundButton?, checked: Boolean -> refreshControls() })
            row.box = box
            binding.llBatchFiles.addView(box)
        }
        refreshControls()
    }

    /**
     * 文件行文案：文件名 + 格式摘要 + 不可用原因。
     * 
     * 
     * 不可用的文件照样列出来——直接隐藏会让人以为文件没 push 成功。
     */
    private fun fileLabel(row: FileRow): String {
        val sb = StringBuilder()
        sb.append(
            getString(
                if (row.corpus.imported)
                    R.string.native_inject_file_item_imported
                else
                    R.string.native_inject_file_item_builtin,
                row.corpus.baseName()
            )
        )
        val summary = row.wav.summary()
        if (summary != null) {
            sb.append("  ").append(summary)
        }
        if (row.wav.level() == WavFileInfo.Level.BLOCK) {
            sb.append('\n').append(getString(R.string.native_batch_file_bad_format))
        } else if (row.wav.level() == WavFileInfo.Level.WARN) {
            sb.append('\n').append(getString(R.string.native_batch_file_warn_format))
        }
        return sb.toString()
    }

    // ===================== 跑批控制 =====================
    private fun onStartClicked() {
        if (running) {
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf<String>(Manifest.permission.RECORD_AUDIO), REQ_CODE_RECORD_AUDIO
            )
            return
        }
        val selected = selectedRows()
        if (selected.isEmpty()) {
            toast(getString(R.string.native_batch_toast_need_file))
            return
        }
        val repeat = parseRepeat()
        if (repeat < MIN_REPEAT) {
            toast(getString(R.string.native_batch_toast_bad_repeat, MIN_REPEAT, MAX_REPEAT))
            return
        }
        var hasWarn = false
        for (row in selected) {
            if (row.wav.level() == WavFileInfo.Level.WARN) {
                hasWarn = true
                break
            }
        }
        if (hasWarn) {
            AlertDialog.Builder(this)
                .setTitle(R.string.native_batch_warn_title)
                .setMessage(R.string.native_batch_warn_message)
                .setPositiveButton(
                    R.string.native_batch_warn_continue,
                    DialogInterface.OnClickListener { d: DialogInterface?, which: Int ->
                        beginBatch(
                            selected,
                            repeat
                        )
                    })
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        beginBatch(selected, repeat)
    }

    /** 队列按「文件外层、重复内层」展开：同一文件的多次重复在时间上连续，看抖动不受干扰。  */
    private fun beginBatch(selected: MutableList<FileRow>, repeat: Int) {
        queue.clear()
        results.clear()
        binding.llBatchResults.removeAllViews()
        for (row in selected) {
            val duration = row.wav.durationMs()
            for (i in 1..repeat) {
                queue.add(Round(row.corpus, duration, i, repeat))
            }
        }
        config = readConfig()
        batchId = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        roundIndex = -1
        running = true
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshControls()
        nextRound()
    }

    private fun confirmAbort() {
        AlertDialog.Builder(this)
            .setTitle(R.string.native_batch_abort_title)
            .setMessage(R.string.native_batch_abort_message)
            .setPositiveButton(
                R.string.native_batch_abort_confirm,
                DialogInterface.OnClickListener { d: DialogInterface?, which: Int ->
                    abort(
                        getString(
                            R.string.native_batch_abort_manual
                        )
                    )
                })
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 中止跑批：停掉当前录音、清掉灌流配置、把已完成的结果出成报告。
     * 
     * @param reason 中止原因，写进当前轮的失败信息
     */
    private fun abort(reason: String) {
        if (!running) {
            return
        }
        main.removeCallbacks(timeoutTask)
        if (current != null) {
            currentRound.status = InjectRoundResult.Status.ERROR
            currentRound.errorMessage = reason
            currentRound.durationMs = System.currentTimeMillis() - roundStartMs
            results.add(current)
            appendResultRow(currentRound)
            current = null
        }
        stopRecordingQuietly()
        finishBatch(true)
    }

    private fun nextRound() {
        if (!running) {
            return
        }
        roundIndex++
        if (roundIndex >= queue.size) {
            finishBatch(false)
            return
        }
        val round = queue.get(roundIndex)
        current = InjectRoundResult()
        currentRound.index = roundIndex + 1
        currentRound.fileName = round.corpus.fileName
        currentRound.repeatIndex = round.repeatIndex
        currentRound.repeatTotal = round.repeatTotal
        currentRound.sourceLanguage = batchConfig.sourceLanguage
        currentRound.targetLanguage = if (batchConfig.shouldTranslate()) batchConfig.targetLanguage else ""
        currentRound.needTts = batchConfig.tts && batchConfig.shouldTranslate()
        finishedSentences.clear()
        currentSentence = null
        binding.tvBatchRealtime.setText("")
        binding.tvBatchRealtime.scrollTo(0, 0)
        roundStartMs = System.currentTimeMillis()
        setStage(Stage.INJECTING)
        refreshProgress()

        val audioMs = if (round.audioDurationMs > 0) round.audioDurationMs else FALLBACK_AUDIO_MS
        main.postDelayed(timeoutTask, audioMs * TIMEOUT_FACTOR + TIMEOUT_PADDING_MS)

        // 拷贝与读参考答案是阻塞 IO，放子线程；回到主线程才下发配置并开录
        io.execute {
            val ready = ensureFileReady(this, round.corpus)
            val reference = readReferenceText(this, round.corpus)
            runOnUi {
                if (!running || current == null) {
                    return@runOnUi
                }
                currentRound.referenceText = reference
                if (!ready) {
                    failCurrentRound(
                        InjectRoundResult.Status.ERROR,
                        getString(R.string.native_batch_file_not_ready)
                    )
                    return@runOnUi
                }
                applyInjectConfig(round.corpus.fileName)
                startRecording()
            }
        }
    }

    /**
     * 下发本轮的灌流配置。
     * 
     * 
     * 末位的 `enableAutoInject` 固定 false：为 true 时走的是另一条按帧定速读裸 PCM 的
     * 实现，不解析 wav 容器，与本页的 wav 语料不匹配。
     */
    private fun applyInjectConfig(fileName: String) {
        val config = AudioFrontInputConfig(
            AncConfig(false, 1),
            false,
            false,
            ReplaceCaptureData(fileName),
            false
        )
        PhoneMicAudioInputFactory.setDebugStrategy(config)
    }

    private fun startRecording() {
        val params = RecordParamsV2()
        params.updateParams = false
        params.businessType = BUSINESS_TYPE_NOTE
        params.recordMode = RECORD_MODE_LIVE
        params.audioSource = AUDIO_SOURCE_SYSTEM_MIC
        val sourceList: MutableList<Int> = ArrayList()
        sourceList.add(AUDIO_SOURCE_SYSTEM_MIC)
        params.audioSourceList = sourceList
        params.needAsr = batchConfig.asr
        // 跑批不画波形，关掉省电
        params.needAmplitude = false
        val translate = batchConfig.shouldTranslate()
        params.needTranslate = translate
        params.needTts = batchConfig.tts && translate
        params.originalLanguage = batchConfig.sourceLanguage
        if (translate) {
            params.targetLanguage = batchConfig.targetLanguage
        }
        if (params.needTts) {
            // 手机 MIC 链路：输出走系统 MIC 通道，devId 必须传空
            params.ttsConfig = TTSConfig(
                "", TTSOutput.SYSTEM_MIC,
                TTSEncode.DEFAULT, TTSOutputChannel.DEFAULT
            )
        }

        manager.startAudioRecording(DEVICE_ID_PHONE, params, object : IResultCallback {
            override fun onSuccess() {
            }

            override fun onError(code: String?, error: String?) {
                runOnUi {
                    failCurrentRound(
                        InjectRoundResult.Status.ERROR,
                        code + " " + error
                    )
                }
            }
        })
    }

    // ===================== 单轮流程 =====================
    /** 灌流文件播完：文件放完后麦克风通道不再有数据，继续录只会得到静音，直接停。  */
    private fun onInjectEnd() {
        if (!running || current == null || stage != Stage.INJECTING) {
            return
        }
        setStage(Stage.WAITING_FINISH)
        manager.stopRecordTransfer(DEVICE_ID_PHONE, object : IResultCallback {
            override fun onSuccess() {
            }

            override fun onError(code: String?, error: String?) {
                runOnUi {
                    failCurrentRound(
                        InjectRoundResult.Status.ERROR,
                        code + " " + error
                    )
                }
            }
        })
        // status=STOP 迟迟不来时的兜底，避免整轮吊死在这一步。
        // 必须绑定到当轮实例：这个延时任务不会随本轮结束被撤销，
        // 短音频时下一轮可能已经进到同一阶段，不校验就会把下一轮提前收掉。
        val pending = current
        main.postDelayed({
            if (running && current == pending && stage == Stage.WAITING_FINISH) {
                fetchResult()
            }
        }, FINISH_GRACE_MS)
    }

    private fun onRecordFinished(success: Boolean, error: String?) {
        if (!running || current == null) {
            return
        }
        if (!success) {
            failCurrentRound(
                InjectRoundResult.Status.ERROR,
                if (error == null) getString(R.string.native_batch_record_failed) else error
            )
            return
        }
        if (stage == Stage.WAITING_FINISH) {
            fetchResult()
        }
    }

    /**
     * 拉取本轮的识别结果。
     * 
     * 
     * **WER 只认这份数据**，界面上滚动的实时文本仅用于观察进度。
     */
    private fun fetchResult() {
        if (current == null || stage == Stage.FETCHING || stage == Stage.SCORING) {
            return
        }
        setStage(Stage.FETCHING)
        if (TextUtils.isEmpty(currentRound.recordId)) {
            failCurrentRound(
                InjectRoundResult.Status.NO_RESULT,
                getString(R.string.native_batch_no_record_id)
            )
            return
        }
        fetchWithVariant(0)
    }

    /**
     * 按 recordId 的几种写法依次尝试拉取。
     * 
     * 
     * **状态事件给的 recordId 与实时转写表里存的键不一定是同一个**：底层存的是
     * `realRecordId = recordId + "_0"`，直接拿事件里的原始 recordId 去查会返回空列表，
     * 表现就是「实时转写明明有文字，拉回来却一条都没有」。
     * 这里把原值、加后缀、去后缀三种都试一遍，取第一个有内容的。
     * 
     * @param variantIndex [.recordIdVariants] 中的下标
     */
    private fun fetchWithVariant(variantIndex: Int) {
        val pending = current
        if (pending == null) {
            return
        }
        val variants = recordIdVariants()
        if (variantIndex >= variants.size) {
            failCurrentRound(
                InjectRoundResult.Status.NO_RESULT,
                getString(R.string.native_batch_no_asr)
            )
            return
        }
        manager.getRecordTransferRealTimeResult(
            null, variants.get(variantIndex), null,
            object : IRecordCallBack<MutableList<RecordTransferRealTimeResult?>?> {
                override fun onSuccess(result: MutableList<RecordTransferRealTimeResult?>?) {
                    runOnUi {
                        if (current != pending) {
                            return@runOnUi  // 本轮已被超时等路径收尾
                        }
                        if (result == null || result.isEmpty()) {
                            fetchWithVariant(variantIndex + 1)
                            return@runOnUi
                        }
                        onResultFetched(result)
                    }
                }

                override fun onError(code: String?, error: String?) {
                    runOnUi {
                        if (current != pending) {
                            return@runOnUi
                        }
                        failCurrentRound(InjectRoundResult.Status.ERROR, code + " " + error)
                    }
                }
            })
    }

    /** @return recordId 的候选写法，按尝试顺序排列且已去重
     */
    private fun recordIdVariants(): MutableList<String?> {
        val raw = if (current == null) "" else currentRound.recordId
        val variants: MutableList<String?> = ArrayList<String?>(3)
        variants.add(raw)
        val withSuffix = if (raw.endsWith(RECORD_ID_DB_SUFFIX)) raw else raw + RECORD_ID_DB_SUFFIX
        if (!variants.contains(withSuffix)) {
            variants.add(withSuffix)
        }
        val withoutSuffix = if (raw.endsWith(RECORD_ID_DB_SUFFIX))
            raw.substring(0, raw.length - RECORD_ID_DB_SUFFIX.length)
        else
            raw
        if (!variants.contains(withoutSuffix)) {
            variants.add(withoutSuffix)
        }
        return variants
    }

    private fun onResultFetched(result: MutableList<RecordTransferRealTimeResult?>?) {
        if (!running || current == null) {
            return
        }
        if (result != null) {
            val valid: MutableList<RecordTransferRealTimeResult> = sortedTranscribed(result)
            currentRound.asrSegments.addAll(dedupe(valid, true))
            currentRound.translateSegments.addAll(dedupe(valid, false))
        }
        if (currentRound.asrSegments.isEmpty()) {
            failCurrentRound(
                InjectRoundResult.Status.NO_RESULT,
                getString(R.string.native_batch_no_asr)
            )
            return
        }
        setStage(Stage.SCORING)
        val scoring = current ?: return
        io.execute {
            score(scoring)
            runOnUi {
                if (!running) {
                    return@runOnUi
                }
                scoring.status = InjectRoundResult.Status.SUCCESS
                completeRound(scoring)
            }
        }
    }


    /** WER 计算在子线程做：长文本的逐句对齐是 DP，主线程跑会卡。  */
    private fun score(round: InjectRoundResult) {
        if (round.referenceText == null) {
            return
        }
        val hypothesis = round.asrText()
        round.wer = evaluate(round.referenceText, hypothesis)
        val refLines = normalizeReferenceLines(round.referenceText)
        round.lines = WerCalculator.evaluateLines(refLines, round.asrSegments)
    }

    private fun failCurrentRound(status: InjectRoundResult.Status, message: String) {
        if (current == null) {
            return
        }
        val round = current ?: return
        round.status = status
        round.errorMessage = message
        stopRecordingQuietly()
        completeRound(round)
    }

    private fun completeRound(round: InjectRoundResult) {
        main.removeCallbacks(timeoutTask)
        if (current != round) {
            return  // 已经被别的路径收尾过，避免同一轮记两次
        }
        current = null
        round.durationMs = System.currentTimeMillis() - roundStartMs
        results.add(round)
        appendResultRow(round)
        setStage(Stage.IDLE)
        PhoneMicAudioInputFactory.setDebugStrategy(null)
        main.postDelayed({ this.nextRound() }, ROUND_GAP_MS)
    }

    /** 停录音，忽略结果——收尾路径不关心是否成功，失败通常只是「本来就没在录」。  */
    private fun stopRecordingQuietly() {
        manager.stopRecordTransfer(DEVICE_ID_PHONE, object : IResultCallback {
            override fun onSuccess() {
            }

            override fun onError(code: String?, error: String?) {
            }
        })
    }

    // ===================== 收尾与报告 =====================
    private fun finishBatch(aborted: Boolean) {
        running = false
        stage = Stage.IDLE
        main.removeCallbacks(timeoutTask)
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cleanupInjectConfig()
        refreshControls()
        if (results.isEmpty()) {
            binding.tvBatchProgress.setText(R.string.native_batch_finished_empty)
            return
        }
        val id = batchId
        val snapshot: MutableList<InjectRoundResult> = ArrayList(results.filterNotNull())
        io.execute {
            val html = InjectReportBuilder.writeHtml(this, id, snapshot)
            InjectReportBuilder.writeCsv(this, id, snapshot)
            runOnUi {
                binding.tvBatchProgress.setText(
                    getString(
                        if (aborted)
                            R.string.native_batch_aborted_format
                        else
                            R.string.native_batch_finished_format,
                        snapshot.size,
                        if (html == null)
                            getString(R.string.native_batch_report_failed)
                        else
                            html.getName()
                    )
                )
                binding.btnBatchShare.setVisibility(if (html == null) View.GONE else View.VISIBLE)
            }
        }
    }

    /**
     * 清掉灌流配置。
     * 
     * 
     * 漏掉这一步的后果是：跑完批之后正常录音仍然在灌文件，而界面上没有任何提示。
     */
    private fun cleanupInjectConfig() {
        PhoneMicAudioInputFactory.setDebugStrategy(null)
    }

    private fun shareReport() {
        val dir = reportDir(this)
        if (dir == null || batchId.isEmpty()) {
            return
        }
        val html = File(dir, "inject_report_" + batchId + ".html")
        if (!html.isFile()) {
            toast(getString(R.string.native_batch_report_failed))
            return
        }
        shareFile(html)
    }

    /**
     * 用系统分享把报告交出去。
     * 
     * 
     * 私有目录的 `file://` 不能跨进程传递，必须经 [TranscriptFileProvider]
     * 换成 `content://` 并附带读权限，否则接收方读取时会被拒绝。
     * 报告目录在 `@xml/native_file_paths` 的白名单内，无需改 manifest。
     * 
     * @param file 已写好的报告文件
     */
    private fun shareFile(file: File) {
        try {
            val uri: Uri? = FileProvider.getUriForFile(
                this,
                getPackageName() + FILE_PROVIDER_SUFFIX, file
            )
            val intent = Intent(Intent.ACTION_SEND)
            intent.setType(MIME_HTML)
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.putExtra(Intent.EXTRA_SUBJECT, file.getName())
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(
                Intent.createChooser(
                    intent,
                    getString(R.string.native_batch_share_chooser)
                )
            )
        } catch (e: Exception) {
            toast(getString(R.string.native_batch_report_failed))
        }
    }

    // ===================== 界面刷新 =====================
    /**
     * 累积实时转写，仅供观察本轮是否还在推进。
     * 
     * 
     * **`s.text` 是当前这句的完整内容而不是增量**：同一句在识别过程中会被反复推送、
     * 逐次修正，因此对当前句要**覆盖**而不是追加，直到 `status` 为已结束才封句。
     * 按增量追加会得到「今天 / 今天天气 / 今天天气很好」这样层层堆叠的文本。
     * 
     * 
     * 这里的内容不参与 WER，评分一律用 `getRecordTransferRealTimeResult` 拉回来的结果。
     */
    private fun appendRealtime(s: RealTimeTransferStatus?) {
        if (s == null || current == null) {
            return
        }
        val phase = if (s.phase == null) -1 else s.phase
        val phaseStatus = if (s.status == null) -1 else s.status

        if (phase == PHASE_ASR) {
            if (!TextUtils.isEmpty(s.text)) {
                ensureCurrentSentence().asr = s.text
            }
            if (phaseStatus == PHASE_STATUS_FINISH) {
                currentSentence?.let {
                    finishedSentences.add(it)
                    currentSentence = null
                }
            }
        } else if (phase == PHASE_TEXT) {
            if (!TextUtils.isEmpty(s.translateText)) {
                lastSentence().translate = s.translateText
            }
        } else {
            return
        }
        renderRealtime()
    }

    private fun ensureCurrentSentence(): Sentence {
        return currentSentence ?: Sentence().also { currentSentence = it }
    }

    /** 译文可能在本句已封句后才到，此时要回填到最后一句上。  */
    private fun lastSentence(): Sentence {
        return currentSentence
            ?: if (finishedSentences.isNotEmpty()) finishedSentences.last() else ensureCurrentSentence()
    }

    private fun renderRealtime() {
        val sb = StringBuilder()
        for (sentence in finishedSentences) {
            appendSentence(sb, sentence)
        }
        currentSentence?.let { appendSentence(sb, it) }
        binding.tvBatchRealtime.setText(sb.toString())
        scrollRealtimeToBottom()
    }

    /**
     * 把实时转写窗口滚到底部，让最新一句始终可见。
     * 
     * 
     * 必须 post 到下一帧：`setText` 之后 Layout 还没重建，此时读到的行数与高度都是旧的。
     */
    private fun scrollRealtimeToBottom() {
        binding.tvBatchRealtime.post {
            val layout = binding.tvBatchRealtime.getLayout() ?: return@post
            val contentHeight = layout.getLineBottom(layout.getLineCount() - 1)
            val visibleHeight = (binding.tvBatchRealtime.getHeight()
                    - binding.tvBatchRealtime.getPaddingTop() - binding.tvBatchRealtime.getPaddingBottom())
            val offset = contentHeight - visibleHeight
            binding.tvBatchRealtime.scrollTo(0, max(offset, 0))
        }
    }

    private fun appendSentence(sb: StringBuilder, sentence: Sentence) {
        if (!TextUtils.isEmpty(sentence.asr)) {
            sb.append(sentence.asr).append('\n')
        }
        if (!TextUtils.isEmpty(sentence.translate)) {
            sb.append("  \u279C ").append(sentence.translate).append('\n')
        }
    }

    private fun appendResultRow(round: InjectRoundResult) {
        val row = TextView(this)
        row.setTextSize(12f)
        row.setPadding(0, 6, 0, 6)
        val wer =
            if (round.hasWer()) String.format(Locale.US, "%.2f%%", (round.wer?.wer() ?: 0.0) * 100) else
                "—"
        row.setText(
            getString(
                R.string.native_batch_result_row_format,
                round.index, round.fileName, round.repeatIndex, round.repeatTotal,
                wer, statusLabel(round)
            )
        )
        row.setTextColor(
            getResources().getColor(
                if (round.status == InjectRoundResult.Status.SUCCESS)
                    R.color.ai_voice_text_primary
                else
                    R.color.ai_voice_logout_text, getTheme()
            )
        )
        binding.llBatchResults.addView(row, 0) // 最新的一轮放最上面
    }

    /**
     * 结果行的状态文案。
     * 
     * 
     * 有 `errorMessage` 时一律优先显示它：同一个状态可能出自完全不同的原因
     * （例如「无识别结果」既可能是没拿到 recordId，也可能是拉回来的转写是空的），
     * 只显示状态名会把排查线索丢掉。
     */
    private fun statusLabel(round: InjectRoundResult): String {
        if (round.status != InjectRoundResult.Status.SUCCESS && !round.errorMessage.isEmpty()) {
            return round.errorMessage
        }
        when (round.status) {
            InjectRoundResult.Status.SUCCESS -> return getString(R.string.native_batch_status_success)
            InjectRoundResult.Status.TIMEOUT -> return getString(R.string.native_batch_status_timeout)
            InjectRoundResult.Status.NO_RESULT -> return getString(R.string.native_batch_status_no_result)
            InjectRoundResult.Status.ERROR -> return getString(R.string.native_batch_status_error)
            else -> return getString(R.string.native_batch_status_error)
        }
    }

    private fun setStage(next: Stage) {
        stage = next
        val res: Int
        when (next) {
            Stage.INJECTING -> res = R.string.native_batch_stage_injecting
            Stage.WAITING_FINISH -> res = R.string.native_batch_stage_finishing
            Stage.FETCHING -> res = R.string.native_batch_stage_fetching
            Stage.SCORING -> res = R.string.native_batch_stage_scoring
            Stage.IDLE -> res = R.string.native_batch_stage_idle
            else -> res = R.string.native_batch_stage_idle
        }
        binding.tvBatchStage.setText(res)
    }

    private fun refreshProgress() {
        val round = queue.get(roundIndex)
        binding.tvBatchProgress.setText(
            getString(
                R.string.native_batch_progress_format,
                round.corpus.baseName(), round.repeatIndex, round.repeatTotal,
                roundIndex + 1, queue.size
            )
        )
    }

    private fun refreshControls() {
        val idle = !running
        binding.btnBatchStart.setEnabled(idle && !selectedRows().isEmpty())
        binding.btnBatchAbort.setVisibility(if (running) View.VISIBLE else View.GONE)
        binding.etBatchRepeat.setEnabled(idle)
        binding.btnBatchImportFiles.setEnabled(idle)
        binding.btnBatchClearImport.setEnabled(idle)
        binding.spBatchSourceLang.setEnabled(idle)
        binding.spBatchTargetLang.setEnabled(idle)
        binding.cbBatchAsr.setEnabled(idle)
        binding.cbBatchTranslate.setEnabled(idle)
        binding.cbBatchTts.setEnabled(idle)
        for (row in fileRows) {
            row.box?.setEnabled(idle && row.runnable())
        }
    }

    private fun selectedRows(): MutableList<FileRow> {
        val selected: MutableList<FileRow> = ArrayList<FileRow>()
        for (row in fileRows) {
            if (row.box?.isChecked() == true && row.runnable()) {
                selected.add(row)
            }
        }
        return selected
    }

    private fun parseRepeat(): Int {
        try {
            val value = binding.etBatchRepeat.getText().toString().trim { it <= ' ' }.toInt()
            return if (value >= MIN_REPEAT && value <= MAX_REPEAT) value else 0
        } catch (e: NumberFormatException) {
            return 0
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String?>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CODE_RECORD_AUDIO && grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            onStartClicked()
        }
    }

    companion object {
        private const val REQ_CODE_RECORD_AUDIO = 2001
        private const val REQ_CODE_PICK_FILES = 2002

        /** 语言列表，与 `NativeRecordActivity.LANGS` 保持一致。  */
        private val LANGS = arrayOf<String?>("zh", "en", "de", "ja", "ko", "fr", "es")
        private const val DEFAULT_SOURCE_LANG = "zh"
        private const val DEFAULT_TARGET_LANG = "en"

        /** FileProvider authority 后缀，与 AndroidManifest 中的声明保持一致。  */
        private const val FILE_PROVIDER_SUFFIX = ".aivoice.fileprovider"
        private const val MIME_HTML = "text/html"

        /** 单轮超时 = 音频时长 × 该系数 + [.TIMEOUT_PADDING_MS]。  */
        private const val TIMEOUT_FACTOR = 3

        /** 单轮超时的固定余量，覆盖识别与网络往返。  */
        private const val TIMEOUT_PADDING_MS = 60000L

        /** 音频时长解析不出来时按此估算，避免超时设成 0。  */
        private const val FALLBACK_AUDIO_MS = 60000L

        /** 停止录音后等待 `onRecordFinish` 的宽限时间。  */
        private const val FINISH_GRACE_MS = 20000L

        /**
         * 两轮之间的间隔，给底层留出释放音频通道的时间。
         * 
         * 
         * `stopRecordTransfer` 返回不代表音频输入已经拆干净，紧接着开下一轮有概率抢不到通道。
         * 连续轮次偶发 `startAudioRecording` 失败时，优先调大这个值。
         */
        private const val ROUND_GAP_MS = 1500L

        /** 重复次数的合法范围。  */
        private const val MIN_REPEAT = 1
        private const val MAX_REPEAT = 99

        // ===== RecordParamsV2 取值，与 NativeRecordActivity 保持一致 =====
        /** AI 笔记。  */
        private const val BUSINESS_TYPE_NOTE = 0

        /** 现场录音。灌流只在这个模式下走手机 MIC 通道。  */
        private const val RECORD_MODE_LIVE = 1

        /** 系统麦克风 16K 单声道。灌流只对这个音源生效。  */
        private const val AUDIO_SOURCE_SYSTEM_MIC = 1

        // ===== RealTimeTransferStatus.phase / status =====
        /** 阶段：ASR 原文。  */
        private const val PHASE_ASR = 4

        /** 阶段：翻译文本。  */
        private const val PHASE_TEXT = 5

        /** 阶段状态：已结束，此时当前句可以封句。  */
        private const val PHASE_STATUS_FINISH = 2

        /** `RecordTransferRealTimeResult.status`：1 为转录成功。  */
        private const val TRANSCRIBE_STATUS_OK = 1

        /** 实时转写表里 recordId 的存储后缀，见 [.fetchWithVariant]。  */
        private const val RECORD_ID_DB_SUFFIX = "_0"

        /**
         * 打开批量测试页。
         * 
         * @param context 上下文
         */
        fun start(context: Context) {
            val intent = Intent(context, NativeInjectBatchActivity::class.java)
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        private fun indexOf(array: Array<String?>, value: String): Int {
            for (i in array.indices) {
                if (value == array[i]) {
                    return i
                }
            }
            return 0
        }

        /**
         * 从多选结果里取出全部 Uri。
         * 
         * 
         * 多选返回的是 `ClipData`，单选返回的是 `getData()`——
         * 只处理其中一种会让「只选了一个文件」或「选了多个文件」之一静默失败。
         */
        private fun collectUris(data: Intent): MutableList<Uri> {
            val uris: MutableList<Uri> = ArrayList()
            val clip = data.getClipData()
            if (clip != null) {
                for (i in 0..<clip.getItemCount()) {
                    val uri = clip.getItemAt(i).getUri()
                    if (uri != null) {
                        uris.add(uri)
                    }
                }
            } else {
                data.data?.let { uris.add(it) }
            }
            return uris
        }

        /**
         * 只保留转录成功的行并按时间排序。
         * 
         * 
         * 表里同时存着转写中与转写失败的行（`status` 0 / 2），把它们算进 WER 会引入
         * 半句或空串。返回结果按 `beginOffset` 升序，拼接出来才是正常语序。
         * 
         * @param result 原始查询结果
         * @return 过滤排序后的列表
         */
        private fun sortedTranscribed(
            result: MutableList<RecordTransferRealTimeResult?>
        ): MutableList<RecordTransferRealTimeResult> {
            val valid: MutableList<RecordTransferRealTimeResult> =
                ArrayList<RecordTransferRealTimeResult>(result.size)
            for (item in result) {
                if (item != null && item.status != null && item.status == TRANSCRIBE_STATUS_OK) {
                    valid.add(item)
                }
            }
            Collections.sort<RecordTransferRealTimeResult?>(
                valid,
                Comparator { a: RecordTransferRealTimeResult?, b: RecordTransferRealTimeResult? ->
                    val left = a?.beginOffset ?: 0
                    val right = b?.beginOffset ?: 0
                    java.lang.Long.compare(left, right)
                })
            return valid
        }

        /**
         * 按包含关系去重。
         * 
         * 
         * **同一个时间段会有多行结果**：识别过程中每次修正都落一行，后来的那行包含先前的。
         * 不去重直接拼接会得到「今天 / 今天天气 / 今天天气很好」这样层层堆叠的文本，
         * 参考词数不变而插入数暴涨，WER 会虚高得没法看。
         * 
         * @param valid 已过滤排序的结果
         * @param asr   true 取 ASR 原文，false 取译文
         * @return 去重后的分段
         */
        private fun dedupe(
            valid: MutableList<RecordTransferRealTimeResult>,
            asr: Boolean
        ): MutableList<String> {
            val segments: MutableList<String> = ArrayList<String>()
            for (item in valid) {
                val text = (if (asr) preferAsr(item) else item.translate) ?: continue
                if (text.isEmpty()) {
                    continue
                }
                if (segments.isEmpty()) {
                    segments.add(text)
                    continue
                }
                val last = segments.get(segments.size - 1)
                if (text.contains(last) && text.length > last.length) {
                    segments.set(segments.size - 1, text) // 同一段的更完整版本
                } else if (!last.contains(text)) {
                    segments.add(text) // 新的时间段
                }
            }
            return segments
        }

        /** ASR 原文优先取 `asr` 字段，为空时退回 `text`。  */
        private fun preferAsr(item: RecordTransferRealTimeResult): String? {
            return if (TextUtils.isEmpty(item.asr)) item.text else item.asr
        }
    }
}
