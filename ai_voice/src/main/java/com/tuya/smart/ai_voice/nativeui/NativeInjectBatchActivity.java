package com.tuya.smart.ai_voice.nativeui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.text.Layout;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.thingclips.smart.ai.audio.utils.PhoneMicAudioInputFactory;
import com.thingclips.smart.earphone.enhance.api.bean.AudioFrontInputConfig;
import com.thingclips.smart.earphone.enhance.api.bean.RealTimeTransferStatus;
import com.thingclips.smart.earphone.enhance.api.bean.RecordParamsV2;
import com.thingclips.smart.earphone.enhance.api.bean.RecordStatusBean;
import com.thingclips.smart.earphone.enhance.api.bean.RecordTransferRealTimeResult;
import com.thingclips.smart.earphone.enhance.api.bean.TTSConfig;
import com.thingclips.smart.earphone.enhance.api.bean.TTSEncode;
import com.thingclips.smart.earphone.enhance.api.bean.TTSOutput;
import com.thingclips.smart.earphone.enhance.api.bean.TTSOutputChannel;
import com.thingclips.smart.earphone.enhance.api.listener.IRecordCallBack;
import com.thingclips.smart.earphone.enhance.api.listener.IRecordListener;
import com.thingclips.smart.sdk.api.IResultCallback;
import com.tuya.smart.ai_voice.R;
import com.tuya.smart.ai_voice.nativeui.base.NativeDemoBaseActivity;
import com.tuya.smart.ai_voice.nativeui.widget.AudioInjectFileHelper;
import com.tuya.smart.ai_voice.nativeui.widget.InjectCorpusImporter;
import com.tuya.smart.ai_voice.nativeui.widget.InjectEndDispatcher;
import com.tuya.smart.ai_voice.nativeui.widget.InjectReportBuilder;
import com.tuya.smart.ai_voice.nativeui.widget.InjectRoundResult;
import com.tuya.smart.ai_voice.nativeui.widget.TranscriptFileProvider;
import com.tuya.smart.ai_voice.nativeui.widget.WavFileInfo;
import com.tuya.smart.ai_voice.nativeui.widget.WerCalculator;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 灌流批量测试。用固定音频连续跑多轮 ASR，自动算 WER 并导出报告。
 * <p>
 * 存在的意义是<b>让识别质量可度量</b>：真人对着麦克风念一遍，每次的音量、语速、
 * 环境噪声都不同，结果无法比对；灌固定文件进去、跑多轮、和参考答案算 WER，
 * 才谈得上「这次改动让识别变好了还是变差了」。
 *
 * <h3>一轮的状态机</h3>
 * <pre>
 * 下发灌流配置 → startAudioRecording → 等灌流播完（AudioInjectEventManager）
 *   → stopRecordTransfer → 等 onRecordFinish
 *   → getRecordTransferRealTimeResult(recordId) → 算 WER → 下一轮
 * </pre>
 * 任一环节卡住由单轮超时兜底（音频时长 × {@link #TIMEOUT_FACTOR} +
 * {@link #TIMEOUT_PADDING_MS}），超时判该轮失败并<b>继续下一轮</b>——
 * 跑批的价值在于无人值守，一轮卡住就全盘中止等于白跑一夜。
 *
 * <h3>数据从哪来</h3>
 * <ul>
 *     <li>音频：assets {@code audio_inject/} 内置，或用户经 SAF 从手机本地目录导入
 *         （见 {@link InjectCorpusImporter}），只认 wav</li>
 *     <li>参考答案：与音频同名的 txt，查找顺序与音频一致——先导入目录、再 assets</li>
 *     <li>语言与能力开关：<b>来自本页的配置区，整批共用</b>（{@link BatchConfig}）。
 *         早前是逐文件写在 assets 清单里的，改成页面配置后不用为了换语言重新编包，
 *         代价是同一批只能一个源语言</li>
 * </ul>
 *
 * <h3>识别结果的两个来源</h3>
 * 界面上滚动的实时文本来自 {@code onRealTimeStatusUpdate}，只为让人看出「还活着」；
 * <b>参与 WER 计算的一律是 {@code getRecordTransferRealTimeResult} 拉回来的结果</b>——
 * 实时推送有状态机、跑批时可能丢句，不能作为评分依据。
 */
public class NativeInjectBatchActivity extends NativeDemoBaseActivity {

    private static final int REQ_CODE_RECORD_AUDIO = 2001;
    private static final int REQ_CODE_PICK_FILES = 2002;

    /** 语言列表，与 {@code NativeRecordActivity.LANGS} 保持一致。 */
    private static final String[] LANGS = {"zh", "en", "de", "ja", "ko", "fr", "es"};
    private static final String DEFAULT_SOURCE_LANG = "zh";
    private static final String DEFAULT_TARGET_LANG = "en";

    /** FileProvider authority 后缀，与 AndroidManifest 中的声明保持一致。 */
    private static final String FILE_PROVIDER_SUFFIX = ".aivoice.fileprovider";
    private static final String MIME_HTML = "text/html";

    /** 单轮超时 = 音频时长 × 该系数 + {@link #TIMEOUT_PADDING_MS}。 */
    private static final int TIMEOUT_FACTOR = 3;
    /** 单轮超时的固定余量，覆盖识别与网络往返。 */
    private static final long TIMEOUT_PADDING_MS = 60_000L;
    /** 音频时长解析不出来时按此估算，避免超时设成 0。 */
    private static final long FALLBACK_AUDIO_MS = 60_000L;

    /** 停止录音后等待 {@code onRecordFinish} 的宽限时间。 */
    private static final long FINISH_GRACE_MS = 20_000L;
    /**
     * 两轮之间的间隔，给底层留出释放音频通道的时间。
     * <p>
     * {@code stopRecordTransfer} 返回不代表音频输入已经拆干净，紧接着开下一轮有概率抢不到通道。
     * 连续轮次偶发 {@code startAudioRecording} 失败时，优先调大这个值。
     */
    private static final long ROUND_GAP_MS = 1500L;

    /** 重复次数的合法范围。 */
    private static final int MIN_REPEAT = 1;
    private static final int MAX_REPEAT = 99;

    // ===== RecordParamsV2 取值，与 NativeRecordActivity 保持一致 =====
    /** AI 笔记。 */
    private static final int BUSINESS_TYPE_NOTE = 0;
    /** 现场录音。灌流只在这个模式下走手机 MIC 通道。 */
    private static final int RECORD_MODE_LIVE = 1;
    /** 系统麦克风 16K 单声道。灌流只对这个音源生效。 */
    private static final int AUDIO_SOURCE_SYSTEM_MIC = 1;

    // ===== RealTimeTransferStatus.phase / status =====
    /** 阶段：ASR 原文。 */
    private static final int PHASE_ASR = 4;
    /** 阶段：翻译文本。 */
    private static final int PHASE_TEXT = 5;
    /** 阶段状态：已结束，此时当前句可以封句。 */
    private static final int PHASE_STATUS_FINISH = 2;

    /** {@code RecordTransferRealTimeResult.status}：1 为转录成功。 */
    private static final int TRANSCRIBE_STATUS_OK = 1;
    /** 实时转写表里 recordId 的存储后缀，见 {@link #fetchWithVariant(int)}。 */
    private static final String RECORD_ID_DB_SUFFIX = "_0";

    /** 单轮流程所处的阶段，仅用于界面提示。 */
    private enum Stage {
        IDLE,
        INJECTING,
        WAITING_FINISH,
        FETCHING,
        SCORING
    }

    /** 文件列表的一行。 */
    private static final class FileRow {
        final AudioInjectFileHelper.Corpus corpus;
        final WavFileInfo wav;
        CheckBox box;

        FileRow(AudioInjectFileHelper.Corpus corpus, WavFileInfo wav) {
            this.corpus = corpus;
            this.wav = wav;
        }

        /** @return 能否参与跑批。语言等配置来自页面，唯一门槛是音频格式 */
        boolean runnable() {
            return wav.isDecodable();
        }
    }

    /**
     * 一次跑批的参数，来自页面配置，<b>整批共用</b>。
     * <p>
     * 原先这些是逐文件写在 assets 清单里的，改成页面配置后不用为了换个语言重新编包，
     * 代价是<b>同一批只能一个源语言</b>——混语种请分批跑，累积 CSV 里有 {@code sourceLang} 列，
     * 跨批次筛出来看趋势完全够用。
     */
    private static final class BatchConfig {
        final String sourceLanguage;
        /** 空串表示不翻译。 */
        final String targetLanguage;
        final boolean asr;
        final boolean translate;
        final boolean tts;

        BatchConfig(String sourceLanguage, String targetLanguage,
                    boolean asr, boolean translate, boolean tts) {
            this.sourceLanguage = sourceLanguage;
            this.targetLanguage = targetLanguage;
            this.asr = asr;
            this.translate = translate;
            this.tts = tts;
        }

        /** 目标语言与源语言相同等于没翻，下发给 SDK 只会白跑一趟。 */
        boolean shouldTranslate() {
            return translate && !targetLanguage.isEmpty()
                    && !targetLanguage.equals(sourceLanguage);
        }
    }

    /** 实时转写里的一句：原文与其译文。 */
    private static final class Sentence {
        String asr = "";
        String translate = "";
    }

    /** 队列里的一轮任务。 */
    private static final class Round {
        final AudioInjectFileHelper.Corpus corpus;
        final long audioDurationMs;
        final int repeatIndex;
        final int repeatTotal;

        Round(AudioInjectFileHelper.Corpus corpus, long audioDurationMs,
              int repeatIndex, int repeatTotal) {
            this.corpus = corpus;
            this.audioDurationMs = audioDurationMs;
            this.repeatIndex = repeatIndex;
            this.repeatTotal = repeatTotal;
        }
    }

    // ===== 视图 =====
    private LinearLayout fileContainer;
    private Spinner sourceLangSpinner;
    private Spinner targetLangSpinner;
    private CheckBox asrSwitch;
    private CheckBox translateSwitch;
    private CheckBox ttsSwitch;
    private Button importFilesButton;
    private Button clearImportButton;
    private EditText repeatInput;
    private Button startButton;
    private Button abortButton;
    private Button shareButton;
    private TextView progressText;
    private TextView stageText;
    private TextView realtimeText;
    private LinearLayout resultContainer;

    // ===== 数据 =====
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<FileRow> fileRows = new ArrayList<>();
    private final List<Round> queue = new ArrayList<>();
    private final List<InjectRoundResult> results = new ArrayList<>();

    private IRecordListener recordListener;
    private InjectEndDispatcher.Consumer injectEndConsumer;

    private boolean running;
    private int roundIndex = -1;
    private Stage stage = Stage.IDLE;
    private String batchId = "";
    /** 本批跑批参数，点「开始跑批」时从页面读一次，跑批期间不再变化。 */
    private BatchConfig config;

    /** 当前轮的累积状态。 */
    private InjectRoundResult current;
    private long roundStartMs;

    /** 实时转写的已封句与在写句，渲染时拼在一起。 */
    private final List<Sentence> finishedSentences = new ArrayList<>();
    @Nullable
    private Sentence currentSentence;

    /** 超时任务，每轮开始时投递、结束时撤销。 */
    private final Runnable timeoutTask = new Runnable() {
        @Override
        public void run() {
            failCurrentRound(InjectRoundResult.Status.TIMEOUT, getString(R.string.native_batch_timeout));
        }
    };

    /**
     * 打开批量测试页。
     *
     * @param context 上下文
     */
    public static void start(@NonNull Context context) {
        Intent intent = new Intent(context, NativeInjectBatchActivity.class);
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    @Override
    protected int getContentLayoutId() {
        return R.layout.activity_native_inject_batch;
    }

    @Override
    protected int getTitleResId() {
        return R.string.native_batch_title;
    }

    @Override
    protected void onContentViewCreated() {
        bindViews();
        setupConfig();
        importFilesButton.setOnClickListener(v -> pickImportFiles());
        clearImportButton.setOnClickListener(v -> confirmClearImported());
        startButton.setOnClickListener(v -> onStartClicked());
        abortButton.setOnClickListener(v -> confirmAbort());
        shareButton.setOnClickListener(v -> shareReport());
        loadFiles();
        refreshControls();
    }

    private void bindViews() {
        fileContainer = findViewById(R.id.ll_batch_files);
        repeatInput = findViewById(R.id.et_batch_repeat);
        startButton = findViewById(R.id.btn_batch_start);
        abortButton = findViewById(R.id.btn_batch_abort);
        shareButton = findViewById(R.id.btn_batch_share);
        progressText = findViewById(R.id.tv_batch_progress);
        stageText = findViewById(R.id.tv_batch_stage);
        realtimeText = findViewById(R.id.tv_batch_realtime);
        // 让这个固定高度的 TextView 能在内部滚动。整页已经在 NestedScrollView 里，
        // 再套一层 ScrollView 会抢滚动事件，因此用 MovementMethod 而不是嵌套滚动容器。
        realtimeText.setMovementMethod(new ScrollingMovementMethod());
        resultContainer = findViewById(R.id.ll_batch_results);
        sourceLangSpinner = findViewById(R.id.sp_batch_source_lang);
        targetLangSpinner = findViewById(R.id.sp_batch_target_lang);
        asrSwitch = findViewById(R.id.cb_batch_asr);
        translateSwitch = findViewById(R.id.cb_batch_translate);
        ttsSwitch = findViewById(R.id.cb_batch_tts);
        importFilesButton = findViewById(R.id.btn_batch_import_files);
        clearImportButton = findViewById(R.id.btn_batch_clear_import);
    }

    /**
     * 装配语言下拉与默认值。
     * <p>
     * 语言用下拉而不是输入框：填错语言的代价是整批数据废掉<b>且看不出原因</b>——
     * WER 会低得莫名其妙，你无从判断是识别差还是语种设错。
     * 默认 {@code zh → en} 与录音页一致，避免两页默认行为不同引起误会。
     */
    private void setupConfig() {
        ArrayAdapter<String> sourceAdapter = new ArrayAdapter<>(
                this, R.layout.item_spinner_native, LANGS);
        sourceAdapter.setDropDownViewResource(R.layout.item_spinner_native_dropdown);
        sourceLangSpinner.setAdapter(sourceAdapter);
        sourceLangSpinner.setSelection(indexOf(LANGS, DEFAULT_SOURCE_LANG));

        // 目标语言多一项「不翻译」，放在首位
        String[] targets = new String[LANGS.length + 1];
        targets[0] = getString(R.string.native_batch_target_none);
        System.arraycopy(LANGS, 0, targets, 1, LANGS.length);
        ArrayAdapter<String> targetAdapter = new ArrayAdapter<>(
                this, R.layout.item_spinner_native, targets);
        targetAdapter.setDropDownViewResource(R.layout.item_spinner_native_dropdown);
        targetLangSpinner.setAdapter(targetAdapter);
        targetLangSpinner.setSelection(indexOf(targets, DEFAULT_TARGET_LANG));

        asrSwitch.setChecked(true);
        translateSwitch.setChecked(true);
        ttsSwitch.setChecked(true);
    }

    /** @return 从页面读一次配置 */
    @NonNull
    private BatchConfig readConfig() {
        String source = (String) sourceLangSpinner.getSelectedItem();
        String target = targetLangSpinner.getSelectedItemPosition() == 0
                ? "" : (String) targetLangSpinner.getSelectedItem();
        return new BatchConfig(source, target, asrSwitch.isChecked(),
                translateSwitch.isChecked(), ttsSwitch.isChecked());
    }

    private static int indexOf(@NonNull String[] array, @NonNull String value) {
        for (int i = 0; i < array.length; i++) {
            if (value.equals(array[i])) {
                return i;
            }
        }
        return 0;
    }

    // ===================== 监听 =====================

    @Override
    protected void registerListeners() {
        recordListener = new IRecordListener() {
            @Override
            public void onRecordStatusUpdate(@NonNull String deviceId, @Nullable RecordStatusBean bean) {
                if (bean == null) return;
                // recordId 只能从状态事件里拿，后面拉转写结果要用
                runOnUi(() -> {
                    if (current != null && !TextUtils.isEmpty(bean.getRecordId())) {
                        current.recordId = bean.getRecordId();
                    }
                });
            }

            @Override
            public void onRecordAmplitudeUpdate(@NonNull String deviceId, int channel, double amplitude) {
                // 跑批不画波形，needAmplitude 已关，这里不会被调到
            }

            @Override
            public void onRealTimeStatusUpdate(@NonNull RealTimeTransferStatus s) {
                runOnUi(() -> appendRealtime(s));
            }

            @Override
            public void onRecordSwitchAudioSourceEvent(@NonNull String devId, int recordType, int audioSource) {
            }

            @Override
            public void onRecordFinish(@NonNull String deviceId) {
                runOnUi(() -> onRecordFinished(true, null));
            }

            @Override
            public void onRecordErrorFinish(@NonNull String deviceId, int errorCode, @NonNull String errorMsg) {
                runOnUi(() -> onRecordFinished(false, errorCode + " " + errorMsg));
            }
        };
        manager.addRecordListener(recordListener);
    }

    @Override
    protected void unregisterListeners() {
        if (recordListener != null) {
            manager.removeRecordListener(recordListener);
            recordListener = null;
        }
        io.shutdownNow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 订阅灌流结束事件，接管本次处置。跑批必须静默——弹窗会阻塞后续几十轮的自动流程，
        // 所以这里不能落到 InjectEndDispatcher 的兜底逻辑上。
        injectEndConsumer = deviceId -> onInjectEnd();
        InjectEndDispatcher.get().setPageConsumer(this, injectEndConsumer);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (injectEndConsumer != null) {
            InjectEndDispatcher.get().clearPageConsumer(injectEndConsumer);
            injectEndConsumer = null;
        }
        // 页面不在前台就中止：跑批不做后台化，留着只会让录音和灌流配置悬空
        if (running && !isChangingConfigurations()) {
            abort(getString(R.string.native_batch_abort_background));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cleanupInjectConfig();
    }

    @Override
    public void onBackPressed() {
        if (running) {
            confirmAbort();
            return;
        }
        super.onBackPressed();
    }

    // ===================== 语料导入 =====================

    /**
     * 拉起系统目录选择器。
     * <p>
     * 走 SAF 而不是直接读 {@code /sdcard/Download}：{@code targetSdk} 已到 36，
     * 分区存储下没有 {@code MANAGE_EXTERNAL_STORAGE} 拿不到那个目录，
     * 而 SAF <b>不需要任何权限</b>。原因详见 {@link InjectCorpusImporter}。
     */
    private void pickImportFiles() {
        if (running) {
            return;
        }
        try {
            startActivityForResult(InjectCorpusImporter.buildPickFilesIntent(),
                    REQ_CODE_PICK_FILES);
        } catch (Exception e) {
            toast(getString(R.string.native_batch_import_no_picker));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CODE_PICK_FILES || resultCode != RESULT_OK || data == null) {
            return;
        }
        List<Uri> uris = collectUris(data);
        if (uris.isEmpty()) {
            return;
        }
        importFilesButton.setEnabled(false);
        importFilesButton.setText(R.string.native_batch_btn_importing);
        io.execute(() -> {
            InjectCorpusImporter.Result result = InjectCorpusImporter.importFiles(this, uris);
            runOnUi(() -> {
                importFilesButton.setEnabled(true);
                importFilesButton.setText(R.string.native_batch_btn_import_files);
                onImportFinished(result);
            });
        });
    }

    /**
     * 从多选结果里取出全部 Uri。
     * <p>
     * 多选返回的是 {@code ClipData}，单选返回的是 {@code getData()}——
     * 只处理其中一种会让「只选了一个文件」或「选了多个文件」之一静默失败。
     */
    @NonNull
    private static List<Uri> collectUris(@NonNull Intent data) {
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) {
                    uris.add(uri);
                }
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        return uris;
    }

    private void onImportFinished(@NonNull InjectCorpusImporter.Result result) {
        if (result.unavailable) {
            // 外置私有目录不可用，与「选了但一个都没落地」是同一类结果，用同一句提示
            toast(getString(R.string.native_batch_import_empty));
            return;
        }
        if (result.isEmpty()) {
            toast(getString(R.string.native_batch_import_empty));
            return;
        }
        toast(getString(R.string.native_batch_import_done,
                result.audioCount, result.textCount));
        loadFiles();
    }


    private void confirmClearImported() {
        if (running) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.native_batch_clear_title)
                .setMessage(R.string.native_batch_clear_message)
                .setPositiveButton(R.string.native_batch_clear_confirm, (d, which) -> clearImported())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void clearImported() {
        io.execute(() -> {
            int removed = AudioInjectFileHelper.clearImported(this);
            runOnUi(() -> {
                toast(getString(R.string.native_batch_clear_done, removed));
                loadFiles();
            });
        });
    }

    // ===================== 文件列表 =====================

    private void loadFiles() {
        io.execute(() -> {
            List<AudioInjectFileHelper.Corpus> corpora = AudioInjectFileHelper.listCorpus(this);
            List<FileRow> rows = new ArrayList<>(corpora.size());
            for (AudioInjectFileHelper.Corpus corpus : corpora) {
                rows.add(new FileRow(corpus, AudioInjectFileHelper.readWavInfo(this, corpus)));
            }
            runOnUi(() -> renderFiles(rows));
        });
    }

    private void renderFiles(@NonNull List<FileRow> rows) {
        fileRows.clear();
        fileRows.addAll(rows);
        fileContainer.removeAllViews();
        if (rows.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.native_batch_no_file);
            empty.setTextSize(13f);
            fileContainer.addView(empty);
            return;
        }
        for (FileRow row : rows) {
            CheckBox box = new CheckBox(this);
            box.setTextSize(13f);
            box.setText(fileLabel(row));
            box.setEnabled(row.runnable());
            if (!row.runnable()) {
                box.setAlpha(0.45f);
            }
            box.setOnCheckedChangeListener((b, checked) -> refreshControls());
            row.box = box;
            fileContainer.addView(box);
        }
        refreshControls();
    }

    /**
     * 文件行文案：文件名 + 格式摘要 + 不可用原因。
     * <p>
     * 不可用的文件照样列出来——直接隐藏会让人以为文件没 push 成功。
     */
    @NonNull
    private String fileLabel(@NonNull FileRow row) {
        StringBuilder sb = new StringBuilder();
        sb.append(getString(row.corpus.imported
                        ? R.string.native_inject_file_item_imported
                        : R.string.native_inject_file_item_builtin,
                row.corpus.baseName()));
        String summary = row.wav.summary();
        if (summary != null) {
            sb.append("  ").append(summary);
        }
        if (row.wav.level() == WavFileInfo.Level.BLOCK) {
            sb.append('\n').append(getString(R.string.native_batch_file_bad_format));
        } else if (row.wav.level() == WavFileInfo.Level.WARN) {
            sb.append('\n').append(getString(R.string.native_batch_file_warn_format));
        }
        return sb.toString();
    }

    // ===================== 跑批控制 =====================

    private void onStartClicked() {
        if (running) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_CODE_RECORD_AUDIO);
            return;
        }
        List<FileRow> selected = selectedRows();
        if (selected.isEmpty()) {
            toast(getString(R.string.native_batch_toast_need_file));
            return;
        }
        int repeat = parseRepeat();
        if (repeat < MIN_REPEAT) {
            toast(getString(R.string.native_batch_toast_bad_repeat, MIN_REPEAT, MAX_REPEAT));
            return;
        }
        boolean hasWarn = false;
        for (FileRow row : selected) {
            if (row.wav.level() == WavFileInfo.Level.WARN) {
                hasWarn = true;
                break;
            }
        }
        if (hasWarn) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.native_batch_warn_title)
                    .setMessage(R.string.native_batch_warn_message)
                    .setPositiveButton(R.string.native_batch_warn_continue,
                            (d, which) -> beginBatch(selected, repeat))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        beginBatch(selected, repeat);
    }

    /** 队列按「文件外层、重复内层」展开：同一文件的多次重复在时间上连续，看抖动不受干扰。 */
    private void beginBatch(@NonNull List<FileRow> selected, int repeat) {
        queue.clear();
        results.clear();
        resultContainer.removeAllViews();
        for (FileRow row : selected) {
            long duration = row.wav.durationMs();
            for (int i = 1; i <= repeat; i++) {
                queue.add(new Round(row.corpus, duration, i, repeat));
            }
        }
        config = readConfig();
        batchId = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        roundIndex = -1;
        running = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        refreshControls();
        nextRound();
    }

    private void confirmAbort() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.native_batch_abort_title)
                .setMessage(R.string.native_batch_abort_message)
                .setPositiveButton(R.string.native_batch_abort_confirm,
                        (d, which) -> abort(getString(R.string.native_batch_abort_manual)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 中止跑批：停掉当前录音、清掉灌流配置、把已完成的结果出成报告。
     *
     * @param reason 中止原因，写进当前轮的失败信息
     */
    private void abort(@NonNull String reason) {
        if (!running) {
            return;
        }
        main.removeCallbacks(timeoutTask);
        if (current != null) {
            current.status = InjectRoundResult.Status.ERROR;
            current.errorMessage = reason;
            current.durationMs = System.currentTimeMillis() - roundStartMs;
            results.add(current);
            appendResultRow(current);
            current = null;
        }
        stopRecordingQuietly();
        finishBatch(true);
    }

    private void nextRound() {
        if (!running) {
            return;
        }
        roundIndex++;
        if (roundIndex >= queue.size()) {
            finishBatch(false);
            return;
        }
        Round round = queue.get(roundIndex);
        current = new InjectRoundResult();
        current.index = roundIndex + 1;
        current.fileName = round.corpus.fileName;
        current.repeatIndex = round.repeatIndex;
        current.repeatTotal = round.repeatTotal;
        current.sourceLanguage = config.sourceLanguage;
        current.targetLanguage = config.shouldTranslate() ? config.targetLanguage : "";
        current.needTts = config.tts && config.shouldTranslate();
        finishedSentences.clear();
        currentSentence = null;
        realtimeText.setText("");
        realtimeText.scrollTo(0, 0);
        roundStartMs = System.currentTimeMillis();
        setStage(Stage.INJECTING);
        refreshProgress();

        long audioMs = round.audioDurationMs > 0 ? round.audioDurationMs : FALLBACK_AUDIO_MS;
        main.postDelayed(timeoutTask, audioMs * TIMEOUT_FACTOR + TIMEOUT_PADDING_MS);

        // 拷贝与读参考答案是阻塞 IO，放子线程；回到主线程才下发配置并开录
        io.execute(() -> {
            boolean ready = AudioInjectFileHelper.ensureFileReady(this, round.corpus);
            String reference = AudioInjectFileHelper.readReferenceText(this, round.corpus);
            runOnUi(() -> {
                if (!running || current == null) {
                    return;
                }
                current.referenceText = reference;
                if (!ready) {
                    failCurrentRound(InjectRoundResult.Status.ERROR,
                            getString(R.string.native_batch_file_not_ready));
                    return;
                }
                applyInjectConfig(round.corpus.fileName);
                startRecording();
            });
        });
    }

    /**
     * 下发本轮的灌流配置。
     * <p>
     * 末位的 {@code enableAutoInject} 固定 false：为 true 时走的是另一条按帧定速读裸 PCM 的
     * 实现，不解析 wav 容器，与本页的 wav 语料不匹配。
     */
    private void applyInjectConfig(@NonNull String fileName) {
        AudioFrontInputConfig config = new AudioFrontInputConfig(
                new AudioFrontInputConfig.AncConfig(false, 1),
                false,
                false,
                new AudioFrontInputConfig.ReplaceCaptureData(fileName),
                false);
        PhoneMicAudioInputFactory.INSTANCE.setDebugStrategy(config);
    }

    private void startRecording() {
        RecordParamsV2 params = new RecordParamsV2();
        params.setUpdateParams(false);
        params.setBusinessType(BUSINESS_TYPE_NOTE);
        params.setRecordMode(RECORD_MODE_LIVE);
        params.setAudioSource(AUDIO_SOURCE_SYSTEM_MIC);
        List<Integer> sourceList = new ArrayList<>();
        sourceList.add(AUDIO_SOURCE_SYSTEM_MIC);
        params.setAudioSourceList(sourceList);
        params.setNeedAsr(config.asr);
        // 跑批不画波形，关掉省电
        params.setNeedAmplitude(false);
        boolean translate = config.shouldTranslate();
        params.setNeedTranslate(translate);
        params.setNeedTts(config.tts && translate);
        params.setOriginalLanguage(config.sourceLanguage);
        if (translate) {
            params.setTargetLanguage(config.targetLanguage);
        }
        if (params.getNeedTts()) {
            // 手机 MIC 链路：输出走系统 MIC 通道，devId 必须传空
            params.setTtsConfig(new TTSConfig("", TTSOutput.SYSTEM_MIC,
                    TTSEncode.DEFAULT, TTSOutputChannel.DEFAULT));
        }

        manager.startAudioRecording(DEVICE_ID_PHONE, params, new IResultCallback() {
            @Override
            public void onSuccess() {
            }

            @Override
            public void onError(String code, String error) {
                runOnUi(() -> failCurrentRound(InjectRoundResult.Status.ERROR, code + " " + error));
            }
        });
    }

    // ===================== 单轮流程 =====================

    /** 灌流文件播完：文件放完后麦克风通道不再有数据，继续录只会得到静音，直接停。 */
    private void onInjectEnd() {
        if (!running || current == null || stage != Stage.INJECTING) {
            return;
        }
        setStage(Stage.WAITING_FINISH);
        manager.stopRecordTransfer(DEVICE_ID_PHONE, new IResultCallback() {
            @Override
            public void onSuccess() {
            }

            @Override
            public void onError(String code, String error) {
                runOnUi(() -> failCurrentRound(InjectRoundResult.Status.ERROR, code + " " + error));
            }
        });
        // onRecordFinish 迟迟不来时的兜底，避免整轮吊死在这一步。
        // 必须绑定到当轮实例：这个延时任务不会随本轮结束被撤销，
        // 短音频时下一轮可能已经进到同一阶段，不校验就会把下一轮提前收掉。
        final InjectRoundResult pending = current;
        main.postDelayed(() -> {
            if (running && current == pending && stage == Stage.WAITING_FINISH) {
                fetchResult();
            }
        }, FINISH_GRACE_MS);
    }

    private void onRecordFinished(boolean success, @Nullable String error) {
        if (!running || current == null) {
            return;
        }
        if (!success) {
            failCurrentRound(InjectRoundResult.Status.ERROR,
                    error == null ? getString(R.string.native_batch_record_failed) : error);
            return;
        }
        if (stage == Stage.WAITING_FINISH) {
            fetchResult();
        }
    }

    /**
     * 拉取本轮的识别结果。
     * <p>
     * <b>WER 只认这份数据</b>，界面上滚动的实时文本仅用于观察进度。
     */
    private void fetchResult() {
        if (current == null || stage == Stage.FETCHING || stage == Stage.SCORING) {
            return;
        }
        setStage(Stage.FETCHING);
        if (TextUtils.isEmpty(current.recordId)) {
            failCurrentRound(InjectRoundResult.Status.NO_RESULT,
                    getString(R.string.native_batch_no_record_id));
            return;
        }
        fetchWithVariant(0);
    }

    /**
     * 按 recordId 的几种写法依次尝试拉取。
     * <p>
     * <b>状态事件给的 recordId 与实时转写表里存的键不一定是同一个</b>：底层存的是
     * {@code realRecordId = recordId + "_0"}，直接拿事件里的原始 recordId 去查会返回空列表，
     * 表现就是「实时转写明明有文字，拉回来却一条都没有」。
     * 这里把原值、加后缀、去后缀三种都试一遍，取第一个有内容的。
     *
     * @param variantIndex {@link #recordIdVariants()} 中的下标
     */
    private void fetchWithVariant(int variantIndex) {
        final InjectRoundResult pending = current;
        if (pending == null) {
            return;
        }
        List<String> variants = recordIdVariants();
        if (variantIndex >= variants.size()) {
            failCurrentRound(InjectRoundResult.Status.NO_RESULT,
                    getString(R.string.native_batch_no_asr));
            return;
        }
        manager.getRecordTransferRealTimeResult(null, variants.get(variantIndex), null,
                new IRecordCallBack<List<RecordTransferRealTimeResult>>() {
                    @Override
                    public void onSuccess(List<RecordTransferRealTimeResult> result) {
                        runOnUi(() -> {
                            if (current != pending) {
                                return;   // 本轮已被超时等路径收尾
                            }
                            if (result == null || result.isEmpty()) {
                                fetchWithVariant(variantIndex + 1);
                                return;
                            }
                            onResultFetched(result);
                        });
                    }

                    @Override
                    public void onError(String code, String error) {
                        runOnUi(() -> {
                            if (current != pending) {
                                return;
                            }
                            failCurrentRound(InjectRoundResult.Status.ERROR, code + " " + error);
                        });
                    }
                });
    }

    /** @return recordId 的候选写法，按尝试顺序排列且已去重 */
    @NonNull
    private List<String> recordIdVariants() {
        String raw = current == null ? "" : current.recordId;
        List<String> variants = new ArrayList<>(3);
        variants.add(raw);
        String withSuffix = raw.endsWith(RECORD_ID_DB_SUFFIX) ? raw : raw + RECORD_ID_DB_SUFFIX;
        if (!variants.contains(withSuffix)) {
            variants.add(withSuffix);
        }
        String withoutSuffix = raw.endsWith(RECORD_ID_DB_SUFFIX)
                ? raw.substring(0, raw.length() - RECORD_ID_DB_SUFFIX.length()) : raw;
        if (!variants.contains(withoutSuffix)) {
            variants.add(withoutSuffix);
        }
        return variants;
    }

    private void onResultFetched(@Nullable List<RecordTransferRealTimeResult> result) {
        if (!running || current == null) {
            return;
        }
        if (result != null) {
            List<RecordTransferRealTimeResult> valid = sortedTranscribed(result);
            current.asrSegments.addAll(dedupe(valid, true));
            current.translateSegments.addAll(dedupe(valid, false));
        }
        if (current.asrSegments.isEmpty()) {
            failCurrentRound(InjectRoundResult.Status.NO_RESULT,
                    getString(R.string.native_batch_no_asr));
            return;
        }
        setStage(Stage.SCORING);
        InjectRoundResult scoring = current;
        io.execute(() -> {
            score(scoring);
            runOnUi(() -> {
                if (!running) {
                    return;
                }
                scoring.status = InjectRoundResult.Status.SUCCESS;
                completeRound(scoring);
            });
        });
    }


    /**
     * 只保留转录成功的行并按时间排序。
     * <p>
     * 表里同时存着转写中与转写失败的行（{@code status} 0 / 2），把它们算进 WER 会引入
     * 半句或空串。返回结果按 {@code beginOffset} 升序，拼接出来才是正常语序。
     *
     * @param result 原始查询结果
     * @return 过滤排序后的列表
     */
    @NonNull
    private static List<RecordTransferRealTimeResult> sortedTranscribed(
            @NonNull List<RecordTransferRealTimeResult> result) {
        List<RecordTransferRealTimeResult> valid = new ArrayList<>(result.size());
        for (RecordTransferRealTimeResult item : result) {
            if (item != null && item.status != null && item.status == TRANSCRIBE_STATUS_OK) {
                valid.add(item);
            }
        }
        Collections.sort(valid, (a, b) -> {
            long left = a.beginOffset == null ? 0 : a.beginOffset;
            long right = b.beginOffset == null ? 0 : b.beginOffset;
            return Long.compare(left, right);
        });
        return valid;
    }

    /**
     * 按包含关系去重。
     * <p>
     * <b>同一个时间段会有多行结果</b>：识别过程中每次修正都落一行，后来的那行包含先前的。
     * 不去重直接拼接会得到「今天 / 今天天气 / 今天天气很好」这样层层堆叠的文本，
     * 参考词数不变而插入数暴涨，WER 会虚高得没法看。
     *
     * @param valid 已过滤排序的结果
     * @param asr   true 取 ASR 原文，false 取译文
     * @return 去重后的分段
     */
    @NonNull
    private static List<String> dedupe(@NonNull List<RecordTransferRealTimeResult> valid,
                                       boolean asr) {
        List<String> segments = new ArrayList<>();
        for (RecordTransferRealTimeResult item : valid) {
            String text = asr ? preferAsr(item) : item.translate;
            if (TextUtils.isEmpty(text)) {
                continue;
            }
            if (segments.isEmpty()) {
                segments.add(text);
                continue;
            }
            String last = segments.get(segments.size() - 1);
            if (text.contains(last) && text.length() > last.length()) {
                segments.set(segments.size() - 1, text);   // 同一段的更完整版本
            } else if (!last.contains(text)) {
                segments.add(text);                        // 新的时间段
            }
        }
        return segments;
    }

    /** ASR 原文优先取 {@code asr} 字段，为空时退回 {@code text}。 */
    @Nullable
    private static String preferAsr(@NonNull RecordTransferRealTimeResult item) {
        return TextUtils.isEmpty(item.asr) ? item.text : item.asr;
    }

    /** WER 计算在子线程做：长文本的逐句对齐是 DP，主线程跑会卡。 */
    private void score(@NonNull InjectRoundResult round) {
        if (round.referenceText == null) {
            return;
        }
        String hypothesis = round.asrText();
        round.wer = WerCalculator.evaluate(round.referenceText, hypothesis);
        List<String> refLines = WerCalculator.normalizeReferenceLines(round.referenceText);
        round.lines = WerCalculator.evaluateLines(refLines, round.asrSegments);
    }

    private void failCurrentRound(@NonNull InjectRoundResult.Status status, @NonNull String message) {
        if (current == null) {
            return;
        }
        InjectRoundResult round = current;
        round.status = status;
        round.errorMessage = message;
        stopRecordingQuietly();
        completeRound(round);
    }

    private void completeRound(@NonNull InjectRoundResult round) {
        main.removeCallbacks(timeoutTask);
        if (current != round) {
            return;   // 已经被别的路径收尾过，避免同一轮记两次
        }
        current = null;
        round.durationMs = System.currentTimeMillis() - roundStartMs;
        results.add(round);
        appendResultRow(round);
        setStage(Stage.IDLE);
        PhoneMicAudioInputFactory.INSTANCE.setDebugStrategy(null);
        main.postDelayed(this::nextRound, ROUND_GAP_MS);
    }

    /** 停录音，忽略结果——收尾路径不关心是否成功，失败通常只是「本来就没在录」。 */
    private void stopRecordingQuietly() {
        manager.stopRecordTransfer(DEVICE_ID_PHONE, new IResultCallback() {
            @Override
            public void onSuccess() {
            }

            @Override
            public void onError(String code, String error) {
            }
        });
    }

    // ===================== 收尾与报告 =====================

    private void finishBatch(boolean aborted) {
        running = false;
        stage = Stage.IDLE;
        main.removeCallbacks(timeoutTask);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        cleanupInjectConfig();
        refreshControls();
        if (results.isEmpty()) {
            progressText.setText(R.string.native_batch_finished_empty);
            return;
        }
        String id = batchId;
        List<InjectRoundResult> snapshot = new ArrayList<>(results);
        io.execute(() -> {
            File html = InjectReportBuilder.writeHtml(this, id, snapshot);
            InjectReportBuilder.writeCsv(this, id, snapshot);
            runOnUi(() -> {
                progressText.setText(getString(aborted
                                ? R.string.native_batch_aborted_format
                                : R.string.native_batch_finished_format,
                        snapshot.size(),
                        html == null ? getString(R.string.native_batch_report_failed)
                                : html.getName()));
                shareButton.setVisibility(html == null ? View.GONE : View.VISIBLE);
            });
        });
    }

    /**
     * 清掉灌流配置。
     * <p>
     * 漏掉这一步的后果是：跑完批之后正常录音仍然在灌文件，而界面上没有任何提示。
     */
    private void cleanupInjectConfig() {
        PhoneMicAudioInputFactory.INSTANCE.setDebugStrategy(null);
    }

    private void shareReport() {
        File dir = InjectReportBuilder.reportDir(this);
        if (dir == null || batchId.isEmpty()) {
            return;
        }
        File html = new File(dir, "inject_report_" + batchId + ".html");
        if (!html.isFile()) {
            toast(getString(R.string.native_batch_report_failed));
            return;
        }
        shareFile(html);
    }

    /**
     * 用系统分享把报告交出去。
     * <p>
     * 私有目录的 {@code file://} 不能跨进程传递，必须经 {@link TranscriptFileProvider}
     * 换成 {@code content://} 并附带读权限，否则接收方读取时会被拒绝。
     * 报告目录在 {@code @xml/native_file_paths} 的白名单内，无需改 manifest。
     *
     * @param file 已写好的报告文件
     */
    private void shareFile(@NonNull File file) {
        try {
            Uri uri = TranscriptFileProvider.getUriForFile(this,
                    getPackageName() + FILE_PROVIDER_SUFFIX, file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(MIME_HTML);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_SUBJECT, file.getName());
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent,
                    getString(R.string.native_batch_share_chooser)));
        } catch (Exception e) {
            toast(getString(R.string.native_batch_report_failed));
        }
    }

    // ===================== 界面刷新 =====================

    /**
     * 累积实时转写，仅供观察本轮是否还在推进。
     * <p>
     * <b>{@code s.text} 是当前这句的完整内容而不是增量</b>：同一句在识别过程中会被反复推送、
     * 逐次修正，因此对当前句要<b>覆盖</b>而不是追加，直到 {@code status} 为已结束才封句。
     * 按增量追加会得到「今天 / 今天天气 / 今天天气很好」这样层层堆叠的文本。
     * <p>
     * 这里的内容不参与 WER，评分一律用 {@code getRecordTransferRealTimeResult} 拉回来的结果。
     */
    private void appendRealtime(@Nullable RealTimeTransferStatus s) {
        if (s == null || current == null) {
            return;
        }
        int phase = s.phase == null ? -1 : s.phase;
        int phaseStatus = s.status == null ? -1 : s.status;

        if (phase == PHASE_ASR) {
            if (!TextUtils.isEmpty(s.text)) {
                ensureCurrentSentence().asr = s.text;
            }
            if (phaseStatus == PHASE_STATUS_FINISH && currentSentence != null) {
                finishedSentences.add(currentSentence);
                currentSentence = null;
            }
        } else if (phase == PHASE_TEXT) {
            if (!TextUtils.isEmpty(s.translateText)) {
                lastSentence().translate = s.translateText;
            }
        } else {
            return;
        }
        renderRealtime();
    }

    private Sentence ensureCurrentSentence() {
        if (currentSentence == null) {
            currentSentence = new Sentence();
        }
        return currentSentence;
    }

    /** 译文可能在本句已封句后才到，此时要回填到最后一句上。 */
    private Sentence lastSentence() {
        if (currentSentence != null) {
            return currentSentence;
        }
        if (!finishedSentences.isEmpty()) {
            return finishedSentences.get(finishedSentences.size() - 1);
        }
        return ensureCurrentSentence();
    }

    private void renderRealtime() {
        StringBuilder sb = new StringBuilder();
        for (Sentence sentence : finishedSentences) {
            appendSentence(sb, sentence);
        }
        if (currentSentence != null) {
            appendSentence(sb, currentSentence);
        }
        realtimeText.setText(sb.toString());
        scrollRealtimeToBottom();
    }

    /**
     * 把实时转写窗口滚到底部，让最新一句始终可见。
     * <p>
     * 必须 post 到下一帧：{@code setText} 之后 Layout 还没重建，此时读到的行数与高度都是旧的。
     */
    private void scrollRealtimeToBottom() {
        realtimeText.post(() -> {
            Layout layout = realtimeText.getLayout();
            if (layout == null) {
                return;
            }
            int contentHeight = layout.getLineBottom(layout.getLineCount() - 1);
            int visibleHeight = realtimeText.getHeight()
                    - realtimeText.getPaddingTop() - realtimeText.getPaddingBottom();
            int offset = contentHeight - visibleHeight;
            realtimeText.scrollTo(0, Math.max(offset, 0));
        });
    }

    private void appendSentence(@NonNull StringBuilder sb, @NonNull Sentence sentence) {
        if (!TextUtils.isEmpty(sentence.asr)) {
            sb.append(sentence.asr).append('\n');
        }
        if (!TextUtils.isEmpty(sentence.translate)) {
            sb.append("  \u279C ").append(sentence.translate).append('\n');
        }
    }

    private void appendResultRow(@NonNull InjectRoundResult round) {
        TextView row = new TextView(this);
        row.setTextSize(12f);
        row.setPadding(0, 6, 0, 6);
        String wer = round.hasWer()
                ? String.format(Locale.US, "%.2f%%", round.wer.wer() * 100)
                : "—";
        row.setText(getString(R.string.native_batch_result_row_format,
                round.index, round.fileName, round.repeatIndex, round.repeatTotal,
                wer, statusLabel(round)));
        row.setTextColor(getResources().getColor(
                round.status == InjectRoundResult.Status.SUCCESS
                        ? R.color.ai_voice_text_primary
                        : R.color.ai_voice_logout_text, getTheme()));
        resultContainer.addView(row, 0);   // 最新的一轮放最上面
    }

    /**
     * 结果行的状态文案。
     * <p>
     * 有 {@code errorMessage} 时一律优先显示它：同一个状态可能出自完全不同的原因
     * （例如「无识别结果」既可能是没拿到 recordId，也可能是拉回来的转写是空的），
     * 只显示状态名会把排查线索丢掉。
     */
    @NonNull
    private String statusLabel(@NonNull InjectRoundResult round) {
        if (round.status != InjectRoundResult.Status.SUCCESS && !round.errorMessage.isEmpty()) {
            return round.errorMessage;
        }
        switch (round.status) {
            case SUCCESS:
                return getString(R.string.native_batch_status_success);
            case TIMEOUT:
                return getString(R.string.native_batch_status_timeout);
            case NO_RESULT:
                return getString(R.string.native_batch_status_no_result);
            case ERROR:
            default:
                return getString(R.string.native_batch_status_error);
        }
    }

    private void setStage(@NonNull Stage next) {
        stage = next;
        int res;
        switch (next) {
            case INJECTING:
                res = R.string.native_batch_stage_injecting;
                break;
            case WAITING_FINISH:
                res = R.string.native_batch_stage_finishing;
                break;
            case FETCHING:
                res = R.string.native_batch_stage_fetching;
                break;
            case SCORING:
                res = R.string.native_batch_stage_scoring;
                break;
            case IDLE:
            default:
                res = R.string.native_batch_stage_idle;
                break;
        }
        stageText.setText(res);
    }

    private void refreshProgress() {
        Round round = queue.get(roundIndex);
        progressText.setText(getString(R.string.native_batch_progress_format,
                round.corpus.baseName(), round.repeatIndex, round.repeatTotal,
                roundIndex + 1, queue.size()));
    }

    private void refreshControls() {
        boolean idle = !running;
        startButton.setEnabled(idle && !selectedRows().isEmpty());
        abortButton.setVisibility(running ? View.VISIBLE : View.GONE);
        repeatInput.setEnabled(idle);
        importFilesButton.setEnabled(idle);
        clearImportButton.setEnabled(idle);
        sourceLangSpinner.setEnabled(idle);
        targetLangSpinner.setEnabled(idle);
        asrSwitch.setEnabled(idle);
        translateSwitch.setEnabled(idle);
        ttsSwitch.setEnabled(idle);
        for (FileRow row : fileRows) {
            if (row.box != null) {
                row.box.setEnabled(idle && row.runnable());
            }
        }
    }

    @NonNull
    private List<FileRow> selectedRows() {
        List<FileRow> selected = new ArrayList<>();
        for (FileRow row : fileRows) {
            if (row.box != null && row.box.isChecked() && row.runnable()) {
                selected.add(row);
            }
        }
        return selected;
    }

    private int parseRepeat() {
        try {
            int value = Integer.parseInt(repeatInput.getText().toString().trim());
            return value >= MIN_REPEAT && value <= MAX_REPEAT ? value : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CODE_RECORD_AUDIO
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            onStartClicked();
        }
    }
}
