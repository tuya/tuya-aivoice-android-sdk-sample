package com.tuya.smart.ai_voice.nativeui;

import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import com.alibaba.fastjson.JSONArray;
import com.thingclips.smart.ai.audio.sync.api.DownloadListener;
import com.thingclips.smart.ai.audio.sync.api.UploadListener;
import com.thingclips.smart.ai.audio.sync.api.ttt.SyncObserver;
import com.thingclips.smart.ai.db.entity.RecordFile;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.chip.ChipGroup;
import com.google.android.material.tabs.TabLayout;
import com.thingclips.smart.android.network.Business;
import com.thingclips.smart.android.network.http.BusinessResponse;
import com.thingclips.smart.api.service.MicroServiceManager;
import com.thingclips.smart.commonbiz.bizbundle.family.api.AbsBizBundleFamilyService;
import com.thingclips.smart.earphone.enhance.api.bean.RecordTransferRealTimeResult;
import com.thingclips.smart.earphone.enhance.api.bean.RecordTransferResultBean;
import com.thingclips.smart.earphone.enhance.api.bean.def.RecordOperateDef;
import com.thingclips.smart.earphone.enhance.api.bean.request.TranscribeParam;
import com.thingclips.smart.earphone.enhance.api.bean.ttt.RecordUpdateInfo;
import com.thingclips.smart.earphone.enhance.api.listener.IRecordCallBack;
import com.thingclips.smart.earphone.enhance.api.listener.IRecordFileUpdateCallback;
import com.thingclips.smart.earphone.enhance.api.listener.ITransferListener;
import com.thingclips.smart.sdk.api.IResultCallback;
import com.tuya.smart.ai_voice.R;
import com.tuya.smart.ai_voice.nativeui.base.NativeDemoBaseActivity;
import com.tuya.smart.ai_voice.nativeui.business.AudioContentBusiness;
import com.tuya.smart.ai_voice.nativeui.widget.RecordErrorCode;
import com.tuya.smart.ai_voice.nativeui.widget.RecordTagBinder;
import com.tuya.smart.ai_voice.nativeui.widget.TransferTextParser;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 模块 2 · 转写 / 总结演示页，兼作单条录音的操作入口。
 *
 * <h3>页面结构</h3>
 * 三 Tab 版式，对齐 AI 笔记的详情页：
 * <pre>
 * 固定头部    文件名 / 时长时间 / 刷新 / 更多 / 上传进度
 * Tab 条      转写 │ 总结 │ 思维导图
 * 内容区      三个容器切 visibility，各自独立滚动
 * </pre>
 * 参数按<b>归属</b>拆到对应 Tab：{@code enableSpeaker} 只影响转写，
 * {@code template} / {@code summaryLang} 只影响总结，挤在一起会让人分不清谁影响谁。
 * <p>
 * 「思维导图」Tab <b>没有独立的 SDK 接口</b>——它与「总结」共用同一份数据，
 * 展示的是总结 JSON 里 {@code outline} / {@code question} 解码后的结构，
 * 真实产品据此渲染成图。本页只展示结构化数据。
 *
 * <h3>主链路</h3>
 * <ol>
 *     <li>{@link #loadDetail()} → {@code getRecordTransferResultDetail(recordId)}，
 *         拿到 {@code recordTransferId} 与转写 / 总结状态</li>
 *     <li>按状态加载正文，<b>取数分两条互斥分支</b>，见 {@link #loadTransferResult()}</li>
 *     <li>「生成转写」/「生成总结」→ {@code processRecordTransferResult}，
 *         {@code onSuccess} 只代表任务已提交</li>
 *     <li>提交后不轮询：{@link ITransferListener} 推上传进度，
 *         {@link IRecordFileUpdateCallback} 推状态变化，状态变完成时自动重拉正文</li>
 * </ol>
 *
 * <h3>为什么会有「上传进度」</h3>
 * 未开启云同步时，音频还在本地，转写 / 总结前底层需要先把音频上传到云端。
 * 这段上传进度就是 {@link ITransferListener} 推送的，按 {@code fileId} 过滤出本文件即可。
 * 上传完成后状态随即进入「转写中」，后续再由文件更新事件驱动。
 *
 * <h3>转写正文的两条取数分支</h3>
 * <pre>
 * transferType==1(实时) 且 !cloudTranscription 且 !isFromCloud
 *     → getRecordTransferRealTimeResult(recordId)  返回带时间戳的句子数组
 * 其余（文件转写完成 / 云端转录 / 云同步下来的记录）
 *     → getRecordTransferRecognizeResult(recordTransferId)  返回 JSON 字符串，需解析
 * </pre>
 * 走错分支的表现是「明明转写完了，正文却是空的」。
 *
 * <h3>编辑保存要写两处</h3>
 * SDK 的 {@code save*} 系列<b>只写本地库</b>。人工纠错要跨端可见，还得再调一次业务云的
 * 编辑接口——这不是 SDK 能力，需接入方自行实现，见 {@link AudioContentBusiness}。
 * <pre>
 * 总结正文   saveRecordTransferSummaryResult   + atop m.wearable.audio.summary.edit  ✔ 本页已双写
 * 文件名     updateRecordTransferResult(name)  + atop m.wearable.audio.record.add    ✔ 已双写
 * 已读状态   updateRecordTransferResult(visit) + atop m.wearable.audio.record.read.update ✔ 已双写
 * 转写正文   saveRecordTransferRecognizeResult + atop m.wearable.audio.content.edit  ✔ 已双写
 * </pre>
 * 云端写入一律<b>不回滚本地</b>：本地已改好，回滚等于丢掉用户的编辑。
 *
 * <h3>转写正文为什么只能逐段编辑</h3>
 * 界面展示的是 {@code TransferTextParser.parseTranscript} 拼出的可读文案
 * （带时间戳与说话人），<b>它无法反解回原始 JSON 数组</b>。而
 * {@code saveRecordTransferRecognizeResult} 是整份覆盖写入——直接把界面文案存回去，
 * {@code timeOffset} / {@code speaker} / {@code translation} 会被一并抹掉。
 * <p>
 * 所以取数时<b>另留一份原始数组</b>（{@link #originRecognizeList}），
 * 编辑时只替换对应段落的 {@code transcript}、其余字段原样保留，再整份提交。
 * 这也是 AI 笔记的做法；
 * 逐段编辑同时保证了<b>段落数不变、序号对齐</b>，用户没机会增删段落把映射弄乱。
 * <p>
 * 两种转写模式的编辑入口因此统一为「长按 → 选一段 → 弹窗改」，
 * 差别只在保存接口：实时按 {@code asrId} 逐句存，文件转写整份存。
 *
 * <h3>按需下载音频的结果怎么拿</h3>
 * {@code syncDownloadNoteAudio}（在「更多操作」里）<b>同步返回且无回调</b>，
 * 下载结束只能靠 {@link SyncObserver} 的 {@code DownloadListener.onFinish} 感知——
 * 该组回调里只有 {@code onFinish} 是真正的结束，{@code downloadSuccess} /
 * {@code downloadError} 语义仍是「下载中」。
 * <p>
 * 回调是<b>批次级</b>的，需按 {@code recordId} 过滤出本条，
 * 见 {@link AudioDownloadListener}。{@link SyncObserver} 构造时上传监听也必须提供，
 * 本页不关心上传，故用 {@link NoopUploadListener} 占位；
 * 云同步的聚合状态由 {@link NativeCloudSyncActivity} 维护。
 *
 * <h3>标签</h3>
 * 转写 Tab 顶部展示 {@code tags}，<b>只读</b>；增删改在
 * {@link NativeRecordActionSheet} 的「更多操作」里，三种 {@code bizType} 并排可对照。
 * 标签变更走 {@code onUpdateWitheTags} <b>局部刷新</b>，不重拉详情——
 * 与状态变更走 {@code onUpdate} 重拉详情形成对照，见 {@link #handleTagsUpdate(List)}。
 *
 * <h3>进详情即已读</h3>
 * 详情加载完成后做一次单向的已读跃迁（{@code 0→1}、{@code 2→3}），
 * 本地与云端各写一次，见 {@link #markReadIfNeeded(Integer)}。
 *
 * <h3>单条录音的其他操作</h3>
 * 分享链接、按需下载音频、重命名、标签、彻底删除集中在
 * {@link NativeRecordActionSheet}，由「更多」按钮唤起。
 */
public class NativeRecordDetailActivity extends NativeDemoBaseActivity {

    public static final String EXTRA_RECORD_ID = "extra_record_id";

    // ===== RecordTransferResultBean.transfer =====
    /** 未转写。 */
    private static final int TRANSFER_NOT_STARTED = 0;
    /** 转写中。 */
    private static final int TRANSFER_RUNNING = 1;
    /** 已转写。 */
    private static final int TRANSFER_DONE = 2;

    // ===== RecordTransferResultBean.summary =====
    /** 总结中。 */
    private static final int SUMMARY_RUNNING = 2;
    /** 已总结。 */
    private static final int SUMMARY_DONE = 3;

    // ===== RecordTransferResultBean.transferType =====
    /** 实时转写。 */
    private static final int TRANSFER_TYPE_REALTIME = 1;

    // ===== TranscribeParam.transferType（任务类型）=====
    /** 任务：转写。 */
    private static final int TASK_TRANSCRIPTION = 0;
    /** 任务：总结。 */
    private static final int TASK_SUMMARY = 1;

    // ===== ITransferListener 的 status =====
    /** 等待上传。 */
    private static final int UPLOAD_WAITING = 0;
    /** 上传完成。 */
    private static final int UPLOAD_COMPLETED = 2;

    // ===== RecordTransferResultBean.visit，转写前后各有一组未读 / 已读 =====
    /** 未读。 */
    private static final int VISIT_UNREAD = 0;
    /** 已读。 */
    private static final int VISIT_READ = 1;
    /** 已转录未读。 */
    private static final int VISIT_TRANSCRIBED_UNREAD = 2;
    /** 已转录已读。 */
    private static final int VISIT_TRANSCRIBED_READ = 3;

    /**
     * 取结果接口的 {@code from} 入参。
     * <p>
     * ⚠️ <b>该参数已废弃、不参与任何判断</b>：底层固定「先查本地库，查不到或失败自动回退云端」。
     * 保留只为兼容既有签名，约定传 {@code 0}。
     */
    private static final int RESULT_FROM_LOCAL = 0;

    /** 详情接口的振幅采样上限，0 表示取全量。 */
    private static final int AMPLITUDE_FULL = 0;

    /** 未取到源语言时的兜底值。 */
    private static final String DEFAULT_LANGUAGE = "zh";

    private String recordId;
    /** 详情接口返回的业务主键，后续所有结果查询以它为入参。 */
    private long recordTransferId = 0L;
    private int transferStatus = -1;
    private int summaryStatus = -1;
    private String originalLanguage;
    private String currentName;
    /** 转写模式与来源，决定正文走哪条取数分支。 */
    private int transferType = 0;
    private boolean cloudTranscription = false;
    private boolean isFromCloud = false;
    private long durationMs = 0L;

    private TextView nameView;
    private TextView metaView;
    private TextView uploadView;
    private TextView transferBadge;
    private TextView summaryBadge;
    private Button btnTransfer;
    private Button btnSummary;
    private TextView transferText;
    private EditText summaryText;
    private EditText templateInput;
    private EditText summaryLangInput;
    private CheckBox enableSpeakerBox;
    private TextView mindMapJsonView;
    private ChipGroup tagGroup;

    /** 三个 Tab 的内容容器，靠 visibility 切换，索引与 TabLayout 的位置一一对应。 */
    private View[] tabContents;

    /**
     * 最近一次拉到的转写原始 JSON 数组。
     * <p>
     * <b>保存的模板。</b> 界面上展示的是它经 {@link TransferTextParser#parseTranscript} 拼出的
     * 可读文案（带时间戳与说话人），那份文案无法反解回结构；编辑时只替换本数组对应元素的
     * {@code transcript}，其余字段（{@code timeOffset} / {@code speaker} / {@code translation}）
     * 原样保留，再整份提交。
     */
    private JSONArray originRecognizeList;

    /** 录音所属设备 ID，转写正文写云端时要用。 */
    private String deviceId;

    /**
     * 最近一次拉到的总结原始 JSON。
     * <p>
     * 保存时必须把编辑后的正文写回它的 {@code summary} 字段再提交，
     * 否则整份覆盖会把 {@code outline} / {@code question} / {@code title} 全丢掉。
     */
    private String rawSummaryJson;

    /** 实时转写模式下的句子列表，编辑单句时需要其 asrId。 */
    private final List<RecordTransferRealTimeResult> realtimeSentences = new ArrayList<>();


    /** 文件数据变更监听，用字段持有，remove 时传同一引用。 */
    private IRecordFileUpdateCallback<List<RecordUpdateInfo>> updateCallback;
    /** 上传进度监听，用字段持有，remove 时传同一引用。 */
    private ITransferListener transferListener;
    /** 云同步观察者，本页只用它的下载回调感知「按需下载音频」何时结束。 */
    private SyncObserver syncObserver;

    /** 录音元信息的云端写入，改名时与 SDK 的本地写入配对使用。 */
    private final AudioContentBusiness contentBusiness = new AudioContentBusiness();

    private final SimpleDateFormat dateFmt =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    @Override
    protected int getContentLayoutId() {
        return R.layout.activity_native_record_detail;
    }

    @Override
    protected int getTitleResId() {
        return R.string.native_detail_title;
    }

    @Override
    protected boolean useScrollContainer() {
        // 头部与 Tab 条固定，三个 Tab 内容各自带 ScrollView，不能再套一层
        return false;
    }

    @Override
    protected void onContentViewCreated() {
        recordId = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_RECORD_ID);
        if (TextUtils.isEmpty(recordId)) {
            toast(getString(R.string.native_detail_toast_no_record_id));
            finish();
            return;
        }
        bindViews();
        loadDetail();
    }

    private void bindViews() {
        nameView = findViewById(R.id.native_detail_name);
        metaView = findViewById(R.id.native_detail_meta);
        uploadView = findViewById(R.id.native_detail_upload);
        transferBadge = findViewById(R.id.native_detail_transfer_badge);
        summaryBadge = findViewById(R.id.native_detail_summary_badge);
        btnTransfer = findViewById(R.id.native_detail_btn_transfer);
        btnSummary = findViewById(R.id.native_detail_btn_summary);
        transferText = findViewById(R.id.native_detail_transfer_text);
        summaryText = findViewById(R.id.native_detail_summary_text);
        templateInput = findViewById(R.id.native_detail_template);
        summaryLangInput = findViewById(R.id.native_detail_summary_lang);
        enableSpeakerBox = findViewById(R.id.native_detail_enable_speaker);
        mindMapJsonView = findViewById(R.id.native_detail_mindmap_json);
        tagGroup = findViewById(R.id.native_detail_tags);

        setupTabs();

        btnTransfer.setOnClickListener(v -> generateTransfer());
        btnSummary.setOnClickListener(v -> generateSummary());
        findViewById(R.id.native_detail_btn_refresh).setOnClickListener(v -> loadDetail());
        findViewById(R.id.native_detail_btn_more).setOnClickListener(v -> showMoreActions());
        findViewById(R.id.native_detail_btn_save_summary).setOnClickListener(v -> saveSummary());

        // 两种转写模式统一用「长按 → 选一段 → 弹窗改」，只是底层保存接口不同
        transferText.setOnLongClickListener(v -> {
            pickSegmentToEdit();
            return true;
        });
    }

    /**
     * 装配 Tab 条：转写 / 总结 / 思维导图，与 AI 笔记的详情页一致。
     * <p>
     * 三个内容容器都在同一份布局里，切 Tab 只改 {@code visibility}——本页刻意不引入
     * ViewPager2 + Fragment：三个 Tab 共享 {@code recordId} / {@code recordTransferId} /
     * 详情 bean，拆成 Fragment 后为了共享这些数据要额外引入 ViewModel 或回调，
     * 那是与 SDK 无关的架构复杂度，会破坏「单页可独立阅读」。
     */
    private void setupTabs() {
        tabContents = new View[]{
                findViewById(R.id.native_detail_tab_transfer),
                findViewById(R.id.native_detail_tab_summary),
                findViewById(R.id.native_detail_tab_mindmap)};

        TabLayout tabLayout = findViewById(R.id.native_detail_tabs);
        int[] titles = {R.string.native_detail_transfer,
                R.string.native_detail_summary,
                R.string.native_detail_tab_mindmap};
        for (int titleRes : titles) {
            tabLayout.addTab(tabLayout.newTab().setText(titleRes));
        }
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                showTab(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });
        showTab(0);
    }

    /**
     * 切换到指定 Tab。
     *
     * @param position Tab 位置：0 转写 / 1 总结 / 2 思维导图
     */
    private void showTab(int position) {
        for (int i = 0; i < tabContents.length; i++) {
            tabContents[i].setVisibility(i == position ? View.VISIBLE : View.GONE);
        }
    }

    // ===================== 事件监听 =====================

    @Override
    protected void registerListeners() {
        updateCallback = new IRecordFileUpdateCallback<List<RecordUpdateInfo>>() {
            @Override
            public void onUpdate(List<RecordUpdateInfo> infos) {
                handleUpdate(infos);
            }

            @Override
            public void onRecordOperate(@NonNull String operate, List<RecordUpdateInfo> infos) {
                // 只有本文件的 UPDATE 有意义；ADD / DELETE 属于列表页的关注范围
                if (RecordOperateDef.OPERATE_TYPE_UPDATE.equals(operate)) {
                    handleUpdate(infos);
                }
            }

            @Override
            public void onRecordListSyncSuccess() {
                runOnUi(NativeRecordDetailActivity.this::loadDetail);
            }

            @Override
            public void onUpdateWitheTags(List<RecordUpdateInfo> infos) {
                handleTagsUpdate(infos);
            }
        };
        manager.addFileRecordUpdateListener(updateCallback);

        transferListener = (fileId, progress, status) ->
                runOnUi(() -> handleUploadEvent(fileId, progress, status));
        manager.addTransferListener(transferListener);

        // syncDownloadNoteAudio 同步返回且无回调，下载结束只能靠它感知
        syncObserver = new SyncObserver(new AudioDownloadListener(), new NoopUploadListener());
        manager.addAudioSyncObserver(syncObserver);
    }

    @Override
    protected void unregisterListeners() {
        if (updateCallback != null) {
            manager.removeFileRecordUpdateListener(updateCallback);
            updateCallback = null;
        }
        if (transferListener != null) {
            manager.removeTransferListener(transferListener);
            transferListener = null;
        }
        if (syncObserver != null) {
            manager.removeAudioSyncObserver(syncObserver);
            syncObserver = null;
        }
        contentBusiness.onDestroy();
    }

    /**
     * 处理音频上传进度。
     * <p>
     * 事件是全局广播的，必须按 {@code fileId} 过滤出本文件。上传完成后转写随即开始，
     * 此处直接把本地状态标为「转写中 / 总结中」，避免界面在事件到达前显示成「未转写」。
     *
     * @param fileId   事件所属文件 ID
     * @param progress 进度 0-100
     * @param status   上传状态
     */
    private void handleUploadEvent(@Nullable String fileId, int progress, int status) {
        if (recordTransferId <= 0 || fileId == null) return;
        if (!fileId.equals(String.valueOf(recordTransferId))) return;

        uploadView.setVisibility(View.VISIBLE);
        if (status == UPLOAD_WAITING) {
            uploadView.setText(R.string.native_detail_upload_waiting);
            return;
        }
        if (status == UPLOAD_COMPLETED) {
            uploadView.setText(R.string.native_detail_upload_done);
            transferStatus = TRANSFER_RUNNING;
            summaryStatus = SUMMARY_RUNNING;
            refreshBadges();
            refreshButtons();
            return;
        }
        uploadView.setText(getString(R.string.native_detail_upload_progress, progress));
    }

    /** 按 recordId 匹配出本文件的变更，状态有变化时刷新界面并重拉正文。 */
    private void handleUpdate(@Nullable List<RecordUpdateInfo> infos) {
        if (infos == null || infos.isEmpty() || TextUtils.isEmpty(recordId)) return;
        RecordUpdateInfo mine = null;
        for (RecordUpdateInfo info : infos) {
            if (info != null && recordId.equals(info.getRecordId())) {
                mine = info;
                break;
            }
        }
        if (mine == null) return;
        int newTransfer = mine.getTransferStatus();
        int newSummary = mine.getSummaryStatus();
        runOnUi(() -> {
            boolean changed = transferStatus != newTransfer || summaryStatus != newSummary;
            transferStatus = newTransfer;
            summaryStatus = newSummary;
            refreshBadges();
            refreshButtons();
            if (changed) {
                // 重拉详情以同步 transferType / cloudTranscription 等分支判据，再按状态取正文
                loadDetail();
            }
        });
    }

    /**
     * 处理标签变更事件，<b>只刷新标签区，不重拉详情</b>。
     * <p>
     * 这是 {@code IRecordFileUpdateCallback} 四个回调按变更粒度分工的意义所在：
     * {@code onUpdate} / {@code onRecordOperate} 携带的是转写、总结等状态变化，
     * 需要重新取详情与正文；而 {@code onUpdateWitheTags} 只说明标签变了，
     * 事件里的 {@code tags} 已经是最新值，直接拿来渲染即可。收到它还去 {@code loadDetail()}
     * 就等于把这个回调当 {@code onUpdate} 用，白白多一次接口调用。
     *
     * @param infos 变更列表，需按 {@code recordId} 过滤出本条记录
     */
    private void handleTagsUpdate(@Nullable List<RecordUpdateInfo> infos) {
        if (infos == null || infos.isEmpty() || TextUtils.isEmpty(recordId)) return;
        for (RecordUpdateInfo info : infos) {
            if (info != null && recordId.equals(info.getRecordId())) {
                List<String> tags = info.getTags();
                runOnUi(() -> RecordTagBinder.bind(tagGroup, tags, RecordTagBinder.NO_LIMIT));
                return;
            }
        }
    }

    // ===================== 详情 =====================

    private void loadDetail() {
        manager.getRecordTransferResultDetail(recordId, AMPLITUDE_FULL,
                new IRecordCallBack<RecordTransferResultBean>() {
                    @Override
                    public void onSuccess(RecordTransferResultBean bean) {
                        runOnUi(() -> renderDetail(bean));
                    }

                    @Override
                    public void onError(String code, String error) {
                toastRecordError(code);
            }
                });
    }

    private void renderDetail(@Nullable RecordTransferResultBean bean) {
        if (bean == null) {
            return;
        }
        recordTransferId = bean.recordTransferId == null ? 0L : bean.recordTransferId;
        transferStatus = bean.transfer == null ? -1 : bean.transfer;
        summaryStatus = bean.summary == null ? -1 : bean.summary;
        originalLanguage = bean.originalLanguage;
        transferType = bean.transferType == null ? 0 : bean.transferType;
        cloudTranscription = bean.cloudTranscription;
        isFromCloud = bean.isFromCloud;
        durationMs = bean.duration == null ? 0L : bean.duration;
        deviceId = bean.deviceId;

        currentName = TextUtils.isEmpty(bean.name) ? getString(R.string.native_unnamed) : bean.name;
        nameView.setText(currentName);
        String time = bean.recordTime == null ? "" : dateFmt.format(new Date(bean.recordTime * 1000L));
        metaView.setText(getString(R.string.native_detail_meta_format,
                formatDuration(durationMs), time));

        refreshBadges();

        // 详情页空间充裕，标签全部展示
        RecordTagBinder.bind(tagGroup, bean.tags, RecordTagBinder.NO_LIMIT);

        markReadIfNeeded(bean.visit);

        if (transferStatus == TRANSFER_DONE || useRealtimeBranch()) {
            loadTransferResult();
        } else {
            transferText.setText(R.string.native_detail_empty_transfer);
        }
        if (summaryStatus == SUMMARY_DONE) {
            loadSummaryResult();
        } else {
            rawSummaryJson = null;
            summaryText.setText(R.string.native_detail_empty_summary);
            mindMapJsonView.setText(R.string.native_detail_mindmap_empty);
        }
        refreshButtons();
    }

    /**
     * 判断转写正文该走哪条分支。
     *
     * @return true 走实时句子接口，false 走文件转写结果接口
     */
    private boolean useRealtimeBranch() {
        return transferType == TRANSFER_TYPE_REALTIME && !cloudTranscription && !isFromCloud;
    }

    /**
     * 刷新两个生成按钮的可用性。
     * <p>
     * 只在该任务「进行中」时禁用，其余状态一律可点，由使用者决定是否重新触发。
     */
    private void refreshButtons() {
        boolean hasId = recordTransferId > 0;
        btnTransfer.setEnabled(hasId && transferStatus != TRANSFER_RUNNING);
        btnSummary.setEnabled(hasId && summaryStatus != SUMMARY_RUNNING);
    }

    private void refreshBadges() {
        transferBadge.setText(transferBadgeText(transferStatus));
        summaryBadge.setText(summaryBadgeText(summaryStatus));
    }

    // ===================== 转写 / 总结正文 =====================

    /** 按 {@link #useRealtimeBranch()} 选择取数分支加载转写正文。 */
    private void loadTransferResult() {
        if (useRealtimeBranch()) {
            loadRealtimeSentences();
        } else {
            loadRecognizeResult();
        }
    }

    /**
     * 实时转写模式的正文：一组带时间戳与声道的句子。
     * <p>
     * 三个入参都可为 null，此处按 {@code recordId} 查。单句纠错走
     * {@code saveRecordTransferRealTimeRecognizeResult(asrId, ...)}，见 {@link #editSentence}。
     */
    private void loadRealtimeSentences() {
        if (TextUtils.isEmpty(recordId)) return;
        manager.getRecordTransferRealTimeResult(null, recordId, null,
                new IRecordCallBack<List<RecordTransferRealTimeResult>>() {
                    @Override
                    public void onSuccess(List<RecordTransferRealTimeResult> result) {
                        runOnUi(() -> renderRealtimeSentences(result));
                    }

                    @Override
                    public void onError(String code, String error) {
                toastRecordError(code);
            }
                });
    }

    private void renderRealtimeSentences(@Nullable List<RecordTransferRealTimeResult> result) {
        realtimeSentences.clear();
        if (result == null || result.isEmpty()) {
            transferText.setText(R.string.native_detail_empty_transfer);
            return;
        }
        realtimeSentences.addAll(result);
        StringBuilder sb = new StringBuilder();
        for (RecordTransferRealTimeResult item : result) {
            long begin = item.beginOffset == null ? 0L : item.beginOffset;
            sb.append('[').append(formatDuration(begin)).append("] ");
            sb.append(TextUtils.isEmpty(item.text) ? nullToEmpty(item.asr) : item.text).append('\n');
            if (!TextUtils.isEmpty(item.translate)) {
                sb.append("  ➜ ").append(item.translate).append('\n');
            }
        }
        transferText.setText(sb.toString().trim());
    }

    // ===================== 编辑保存 =====================

    /**
     * 保存总结正文。同样只写本地。
     * <p>
     * <b>注意不能把界面上的文本直接存回去。</b> {@code saveRecordTransferSummaryResult} 是
     * <b>整份覆盖</b>写入，而云端下发的总结是一个 JSON 对象
     * （{@code summary} / {@code outline} / {@code question} / {@code title}）。
     * 直接存纯文本会把除正文外的字段全部抹掉，下次解析只能退化成原样输出。
     * <p>
     * 因此这里的做法是：界面只编辑 {@code summary} 正文，保存时把它写回
     * {@link #rawSummaryJson} 的对应字段，其余字段原样保留。
     */
    private void saveSummary() {
        if (recordTransferId <= 0) {
            toast(getString(R.string.native_detail_toast_no_record_id));
            return;
        }
        String body = summaryText.getText().toString();
        String payload = TransferTextParser.writeSummaryBody(rawSummaryJson, body);
        manager.saveRecordTransferSummaryResult(recordTransferId, payload, new IResultCallback() {
            @Override
            public void onSuccess() {
                runOnUi(() -> {
                    toast(getString(R.string.native_detail_toast_saved));
                    // 本地写成功后再写云端，两处内容必须是同一份 JSON
                    saveSummaryOnCloud(payload);
                    loadDetail();
                });
            }

            @Override
            public void onError(String code, String error) {
                toastRecordError(code);
            }
        });
    }

    /**
     * 手动改名后，把新名字同步进总结 JSON 的 {@code title}。
     * <p>
     * <b>不做这一步，改完的名字会自己变回去。</b> 总结里的 {@code title} 是文件名的另一个副本，
     * {@link #applySummaryTitle} 会在每次加载总结后用它回写文件名；
     * 只改文件名不改 {@code title}，下一次 {@link #loadDetail()} 就把旧名字盖回来了。
     * <p>
     * 顺序也有讲究：<b>先写 title，再刷新详情</b>。反过来的话，刷新触发的总结加载会读到旧
     * {@code title}，同样会把名字改回去。
     * <p>
     * AI 笔记也是这么做的：改名时「改文件名 + 重写总结 title」两件事一起做。
     *
     * @param newName 新文件名
     */
    private void syncTitleToSummary(String newName) {
        currentName = newName;
        nameView.setText(newName);

        String payload = TransferTextParser.writeSummaryTitle(rawSummaryJson, newName);
        if (payload == null || recordTransferId <= 0) {
            // 这条录音还没有总结，不存在 title 副本，直接刷新即可
            loadDetail();
            return;
        }
        rawSummaryJson = payload;
        manager.saveRecordTransferSummaryResult(recordTransferId, payload, new IResultCallback() {
            @Override
            public void onSuccess() {
                runOnUi(() -> {
                    saveSummaryOnCloud(payload);
                    loadDetail();
                });
            }

            @Override
            public void onError(String code, String error) {
                // title 没同步上，但文件名已经改好了，刷新后可能被总结标题覆盖回去
                runOnUi(() -> {
                    toastRecordError(code);
                    loadDetail();
                });
            }
        });
    }

    /**
     * 把总结正文同步到云端。本地写入成功后调用。
     * <p>
     * SDK 的 {@code saveRecordTransferSummaryResult} <b>只写本地库</b>，
     * 不补这一步，人工纠错的结果换台设备就看不到了——这是接入时最容易漏的一环。
     * <p>
     * 失败<b>不回滚本地</b>，只提示：本地已经改好了，回滚等于把用户的编辑丢掉；
     * 「本地已改、云端未同步」是真实存在的中间态，让它可见比藏起来好。
     *
     * @param payload 与写入本地完全相同的总结 JSON 字符串
     */
    private void saveSummaryOnCloud(String payload) {
        if (TextUtils.isEmpty(recordId)) return;
        contentBusiness.editSummary(recordId, payload, new Business.ResultListener<Boolean>() {
            @Override
            public void onSuccess(BusinessResponse response, Boolean result, String apiName) {
                // 云端写入成功，界面已在本地写入时刷新过，此处无需再动
            }

            @Override
            public void onFailure(BusinessResponse response, Boolean result, String apiName) {
                String code = response == null ? "-" : response.getErrorCode();
                String msg = response == null ? "" : response.getErrorMsg();
                runOnUi(() -> toast(getString(R.string.native_toast_api_failed, code, msg)));
            }
        });
    }

    /**
     * 渲染总结结果，并同步刷新「思维导图」Tab。
     * <p>
     * 正文区只放 {@code summary} 字段（可编辑、可原样写回）；
     * {@code outline} / {@code question} 是思维导图的数据源，放到第三个 Tab 展示结构。
     *
     * @param json 总结结果 JSON 字符串，可能为 null
     */
    private void renderSummary(@Nullable String json) {
        rawSummaryJson = json;
        summaryText.setText(TransferTextParser.parseSummaryBody(json));

        String outlineJson = TransferTextParser.parseSummaryOutlineJson(json);
        mindMapJsonView.setText(TextUtils.isEmpty(outlineJson)
                ? getString(R.string.native_detail_mindmap_empty) : outlineJson);
    }

    // ===================== 已读跃迁 =====================

    /**
     * 进入详情即视为已读，把未读态跃迁为对应的已读态。
     * <p>
     * {@code visit} 有四个取值，两两配对——转写前后各有一组未读 / 已读：
     * <pre>
     * 0 未读        → 1 已读
     * 2 已转录未读  → 3 已转录已读
     * </pre>
     * 已经是 {@code 1} 或 {@code 3} 时不做任何事，<b>跃迁是单向的</b>，不能把已读改回未读。
     * 分成两组是因为「转写完成」本身要在列表上提示用户，转写完成后红点会重新亮起。
     * <p>
     * 与改名一样要写两处：{@code updateRecordTransferResult} 改本地库让红点立刻消失，
     * atop 接口同步云端让其他端一致。云端这一路是 fire-and-forget——
     * 失败既不回滚本地也不提示用户，已读状态丢一次的代价远小于打断阅读。
     *
     * @param visit 详情返回的当前访问状态，可能为 null
     */
    private void markReadIfNeeded(@Nullable Integer visit) {
        if (visit == null || recordTransferId <= 0) return;
        int target;
        if (visit == VISIT_UNREAD) {
            target = VISIT_READ;
        } else if (visit == VISIT_TRANSCRIBED_UNREAD) {
            target = VISIT_TRANSCRIBED_READ;
        } else {
            return;
        }

        // 本地：visit 参数是 String，兼容 "true"/"false"/数字字符串，此处传数字字符串
        manager.updateRecordTransferResult(recordTransferId, null, null,
                String.valueOf(target), null, null, null, null,
                new IResultCallback() {
                    @Override
                    public void onSuccess() {
                    }

                    @Override
                    public void onError(String code, String error) {
                toastRecordError(code);
            }
                });
        markReadOnCloud(target);
    }

    /**
     * 同步已读状态到云端。fire-and-forget，失败只记日志。
     *
     * @param visit 目标已读态
     */
    private void markReadOnCloud(int visit) {
        if (TextUtils.isEmpty(recordId)) return;
        contentBusiness.markRecordRead(recordId, visit, new Business.ResultListener<Boolean>() {
            @Override
            public void onSuccess(BusinessResponse response, Boolean result, String apiName) {
            }

            @Override
            public void onFailure(BusinessResponse response, Boolean result, String apiName) {
                String code = response == null ? "-" : response.getErrorCode();
            }
        });
    }

    // ===================== 总结标题回写文件名 =====================

    /**
     * 总结 JSON 里若带 {@code title}，且与当前文件名不同，则把文件名改成它。
     * <p>
     * 这是「AI 给录音起名」的能力：录音刚生成时文件名多是时间戳之类的默认值，
     * 总结完成后用 AI 提炼的标题替换，列表里才好辨认。
     * <p>
     * 三点需要注意：
     * <ul>
     *     <li>文件名在本地库与云端各存一份，<b>两处都要写</b>——
     *         {@code updateRecordTransferResult} 改本地，{@link AudioContentBusiness} 改云端。
     *         这与「更多操作」里的手动重命名是同一套写法</li>
     *     <li>必须先比对再改。总结每次重新加载都会走到这里，不比对就会反复发起无意义的更新</li>
     *     <li>这条路径无需重写云端的总结内容（{@code title} 本就是从总结里读出来的），
     *         与文件名是否同步到云端是两回事</li>
     * </ul>
     *
     * @param title 总结里解析出的标题，可能为 null
     */
    private void applySummaryTitle(@Nullable String title) {
        if (TextUtils.isEmpty(title) || recordTransferId <= 0) return;
        if (TextUtils.equals(title, currentName)) return;

        manager.updateRecordTransferResult(recordTransferId, title,
                null, null, null, null, null, null,
                new IResultCallback() {
                    @Override
                    public void onSuccess() {
                        runOnUi(() -> {
                            currentName = title;
                            nameView.setText(title);
                            renameOnCloud(title);
                        });
                    }

                    @Override
                    public void onError(String code, String error) {
                toastRecordError(code);
            }
                });
    }

    /**
     * 把新文件名写到云端。本地写入成功后调用。
     * <p>
     * 失败<b>不回滚本地</b>，只记录日志——「本地已改、云端未同步」是真实存在的中间态，
     * 藏起来反而误导。
     *
     * @param name 新文件名
     */
    private void renameOnCloud(String name) {
        if (TextUtils.isEmpty(recordId)) return;
        long homeId = currentHomeId();
        if (homeId <= 0) {
            return;
        }
        contentBusiness.updateFileName(recordId, name, homeId,
                new Business.ResultListener<Boolean>() {
                    @Override
                    public void onSuccess(BusinessResponse response, Boolean result, String apiName) {
                    }

                    @Override
                    public void onFailure(BusinessResponse response, Boolean result, String apiName) {
                        String code = response == null ? "-" : response.getErrorCode();
                        String msg = response == null ? "" : response.getErrorMsg();
                    }
                });
    }

    /**
     * @return 当前家庭 ID；家庭服务不可用时返回 {@code 0}
     */
    private long currentHomeId() {
        try {
            AbsBizBundleFamilyService familyService = MicroServiceManager.getInstance()
                    .findServiceByInterface(AbsBizBundleFamilyService.class.getName());
            return familyService == null ? 0L : familyService.getCurrentHomeId();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 长按正文 → 列出各段 → 选一段编辑。<b>两种转写模式共用这一个入口。</b>
     * <p>
     * 之所以不让用户直接编辑整段正文：界面上是
     * {@link TransferTextParser#parseTranscript} 拼出的可读文案（带时间戳、说话人），
     * 用户一旦增删行，就无法安全地映射回原始结构。逐段编辑天然保证条数不变、序号对齐。
     */
    private void pickSegmentToEdit() {
        if (useRealtimeBranch()) {
            pickRealtimeSentence();
        } else {
            pickRecognizeSegment();
        }
    }

    /** 实时转写模式：按句列出，保存走 {@code saveRecordTransferRealTimeRecognizeResult}。 */
    private void pickRealtimeSentence() {
        if (realtimeSentences.isEmpty()) {
            toast(getString(R.string.native_detail_toast_no_sentence));
            return;
        }
        String[] items = new String[realtimeSentences.size()];
        for (int i = 0; i < realtimeSentences.size(); i++) {
            RecordTransferRealTimeResult item = realtimeSentences.get(i);
            items[i] = TextUtils.isEmpty(item.text) ? nullToEmpty(item.asr) : item.text;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.native_detail_pick_sentence)
                .setItems(items, (d, which) -> editSentence(realtimeSentences.get(which)))
                .show();
    }

    /** 文件转写模式：按段列出，保存走整份 {@code saveRecordTransferRecognizeResult}。 */
    private void pickRecognizeSegment() {
        if (originRecognizeList == null || originRecognizeList.isEmpty()) {
            toast(getString(R.string.native_detail_toast_no_sentence));
            return;
        }
        int size = originRecognizeList.size();
        String[] items = new String[size];
        for (int i = 0; i < size; i++) {
            items[i] = nullToEmpty(TransferTextParser.segmentTranscript(originRecognizeList, i));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.native_detail_pick_sentence)
                .setItems(items, (d, which) -> editSegment(which))
                .show();
    }

    /**
     * 编辑单句实时转写。
     * <p>
     * 三个内容字段按需传，{@code null} 表示不修改该字段——这里同时更新展示文案与原始 ASR，
     * 译文保持不变。
     */
    private void editSentence(@NonNull RecordTransferRealTimeResult sentence) {
        View inputView = createInputView();
        EditText input = inputView.findViewById(R.id.native_dialog_input);
        input.setText(TextUtils.isEmpty(sentence.text) ? nullToEmpty(sentence.asr) : sentence.text);
        new AlertDialog.Builder(this)
                .setTitle(R.string.native_detail_edit_sentence)
                .setView(inputView)
                .setPositiveButton(R.string.native_action_confirm, (d, w) -> {
                    String newText = input.getText().toString();
                    long asrId = sentence.asrId == null ? 0L : sentence.asrId;
                    manager.saveRecordTransferRealTimeRecognizeResult(
                            asrId, newText, newText, null,
                            saveCallback("saveRecordTransferRealTimeRecognizeResult"));
                })
                .setNegativeButton(R.string.native_list_delete_cancel, null)
                .show();
    }

    /**
     * 编辑文件转写的某一段。
     * <p>
     * 只改这一段的 {@code transcript}，其余字段与其余段落原样保留，再<b>整份</b>提交——
     * {@code saveRecordTransferRecognizeResult} 是覆盖写入，提交的必须是完整数组。
     *
     * @param index 段落序号，与 {@link #originRecognizeList} 的下标一致
     */
    private void editSegment(int index) {
        View inputView = createInputView();
        EditText input = inputView.findViewById(R.id.native_dialog_input);
        input.setText(nullToEmpty(TransferTextParser.segmentTranscript(originRecognizeList, index)));
        new AlertDialog.Builder(this)
                .setTitle(R.string.native_detail_edit_sentence)
                .setView(inputView)
                .setPositiveButton(R.string.native_action_confirm, (d, w) ->
                        saveSegment(index, input.getText().toString()))
                .setNegativeButton(R.string.native_list_delete_cancel, null)
                .show();
    }

    /**
     * 把某段的新文案写回原始数组并整份保存：先写本地，成功后再写云端。
     * <p>
     * 云端失败<b>不回滚本地</b>，只提示——「本地已改、云端未同步」是真实存在的中间态。
     *
     * @param index   段落序号
     * @param newText 编辑后的文案
     */
    private void saveSegment(int index, String newText) {
        if (recordTransferId <= 0 || originRecognizeList == null) {
            toast(getString(R.string.native_detail_toast_no_record_id));
            return;
        }
        String payload = TransferTextParser.writeSegmentTranscript(
                originRecognizeList, index, newText);
        manager.saveRecordTransferRecognizeResult(recordTransferId, payload,
                new IResultCallback() {
                    @Override
                    public void onSuccess() {
                        runOnUi(() -> {
                            toast(getString(R.string.native_detail_toast_saved));
                            saveTransferOnCloud(payload);
                            loadDetail();
                        });
                    }

                    @Override
                    public void onError(String code, String error) {
                        toastRecordError(code);
                    }
                });
    }

    /**
     * 把转写正文同步到云端。本地写入成功后调用。
     * <p>
     * 与总结那条链路一致：SDK 的 {@code save*} 只写本地，不补这一步换台设备就看不到编辑结果。
     * 本接口比总结多一个 {@code devId}。
     *
     * @param payload 与写入本地完全相同的转写 JSON 字符串
     */
    private void saveTransferOnCloud(String payload) {
        if (TextUtils.isEmpty(recordId) || TextUtils.isEmpty(deviceId)) return;
        contentBusiness.editContent(deviceId, recordId, payload,
                new Business.ResultListener<Boolean>() {
                    @Override
                    public void onSuccess(BusinessResponse response, Boolean result, String apiName) {
                        // 云端写入成功，界面已在本地写入时刷新过，此处无需再动
                    }

                    @Override
                    public void onFailure(BusinessResponse response, Boolean result, String apiName) {
                        String code = response == null ? "-" : response.getErrorCode();
                        String msg = response == null ? "" : response.getErrorMsg();
                        runOnUi(() -> toast(getString(R.string.native_toast_api_failed, code, msg)));
                    }
                });
    }

    /**
     * 创建对话框用的输入区。裸 {@code new EditText(context)} 没有背景与内边距，
     * 空输入时在对话框里几乎看不见，故统一用布局。
     * 取值用 {@code findViewById(R.id.native_dialog_input)}。
     *
     * @return 输入区根视图
     */
    private View createInputView() {
        return getLayoutInflater().inflate(R.layout.dialog_native_input, null);
    }

    /**
     * 按录音链路的错误码表给出可读提示。
     * <p>
     * 本模块<b>没有专属错误码</b>，转写 / 总结失败走的是录音链路那张表
     * （{@code 9006}、{@code 10001}~{@code 10101}、AI 基座 {@code 39001}~{@code 39012}）。
     * 详情页不涉及电话录音模式，故 {@code isCallMode} 固定传 {@code false}。
     * <p>
     * SDK 回调可能在子线程，内部已切回主线程。
     *
     * @param code 错误码
     */
    private void toastRecordError(String code) {
        runOnUi(() -> toast(RecordErrorCode.messageOf(this, code, false)));
    }

    /**
     * 构造保存类接口的统一回调：成功后执行续作并重拉正文，失败按错误码提示。
     *
     * @param action 动作名，用于日志区分
     */
    private IResultCallback saveCallback(String action) {
        return new IResultCallback() {
            @Override
            public void onSuccess() {
                runOnUi(() -> {
                    toast(getString(R.string.native_detail_toast_saved));
                    loadDetail();
                });
            }

            @Override
            public void onError(String code, String error) {
                toastRecordError(code);
            }
        };
    }

    /** 文件转写模式的正文：返回 JSON 字符串，交由 {@link TransferTextParser} 解析。 */
    private void loadRecognizeResult() {
        if (recordTransferId <= 0) return;
        manager.getRecordTransferRecognizeResult(recordTransferId, RESULT_FROM_LOCAL,
                new IRecordCallBack<String>() {
                    @Override
                    public void onSuccess(String text) {
                        runOnUi(() -> {
                            // 原始数组单独留一份，编辑保存时以它为模板
                            originRecognizeList = TransferTextParser.parseTranscriptArray(text);
                            transferText.setText(TransferTextParser.parseTranscript(text, durationMs));
                        });
                    }

                    @Override
                    public void onError(String code, String error) {
                toastRecordError(code);
            }
                });
    }

    private void loadSummaryResult() {
        if (recordTransferId <= 0) return;
        manager.getRecordTransferSummaryResult(recordTransferId, RESULT_FROM_LOCAL,
                new IRecordCallBack<String>() {
                    @Override
                    public void onSuccess(String text) {
                        runOnUi(() -> {
                            renderSummary(text);
                            // 总结里带的 AI 标题用来替换默认文件名
                            applySummaryTitle(TransferTextParser.parseSummaryTitle(text));
                        });
                    }

                    @Override
                    public void onError(String code, String error) {
                toastRecordError(code);
            }
                });
    }

    // ===================== 触发生成 =====================

    /** 触发转写。仅「转写中」拦截，其余状态允许重新触发。 */
    private void generateTransfer() {
        if (recordTransferId <= 0) {
            toast(getString(R.string.native_detail_toast_no_record_id));
            return;
        }
        if (transferStatus == TRANSFER_RUNNING) {
            toast(getString(R.string.native_detail_toast_transfer_running));
            return;
        }
        requestGenerate(TASK_TRANSCRIPTION);
    }

    /**
     * 触发总结。
     * <p>
     * <b>未转写过时会降级为转写任务</b>——总结以转写结果为输入，没有转写就没有可总结的内容，
     * 此时下发 {@code taskType=1} 不会产出任何东西。底层做转写时会连带产出总结，
     * 所以降级不会让用户少拿到东西。
     */
    private void generateSummary() {
        if (recordTransferId <= 0) {
            toast(getString(R.string.native_detail_toast_no_record_id));
            return;
        }
        if (summaryStatus == SUMMARY_RUNNING) {
            toast(getString(R.string.native_detail_toast_summary_running));
            return;
        }
        if (transferStatus == TRANSFER_NOT_STARTED) {
            toast(getString(R.string.native_detail_toast_downgrade));
            requestGenerate(TASK_TRANSCRIPTION);
            return;
        }
        requestGenerate(TASK_SUMMARY);
    }

    /**
     * 提交转写 / 总结任务。
     * <p>
     * {@code onSuccess} <b>只代表任务已提交</b>，不代表完成。未开云同步时底层会先上传音频，
     * 进度由 {@link ITransferListener} 推送；完成状态由文件更新事件推送。
     *
     * @param taskType {@link #TASK_TRANSCRIPTION} 或 {@link #TASK_SUMMARY}
     */
    private void requestGenerate(int taskType) {
        String lang = TextUtils.isEmpty(originalLanguage) ? DEFAULT_LANGUAGE : originalLanguage;
        String taskName = taskType == TASK_TRANSCRIPTION
                ? getString(R.string.native_detail_task_transcription)
                : getString(R.string.native_detail_task_summary);
        // 三个可选参数：模板留空即用默认；总结语言留空即跟随转写语言；说话人分离按需开
        String template = templateInput.getText().toString().trim();
        String summaryLang = summaryLangInput.getText().toString().trim();
        boolean enableSpeaker = enableSpeakerBox.isChecked();

        // 构造顺序：(fileId, template, transferType, audioLang, transLang, summaryLang, enableSpeaker)
        TranscribeParam param = new TranscribeParam(
                recordTransferId, template, taskType, lang, null,
                TextUtils.isEmpty(summaryLang) ? null : summaryLang, enableSpeaker);
        manager.processRecordTransferResult(param, new IResultCallback() {
            @Override
            public void onSuccess() {
                runOnUi(() -> {
                    toast(taskType == TASK_TRANSCRIPTION
                            ? getString(R.string.native_detail_toast_generating)
                            : getString(R.string.native_detail_toast_summary_generating));
                });
            }

            @Override
            public void onError(String code, String error) {
                toastRecordError(code);
            }
        });
    }

    // ===================== 更多操作 =====================

    /** 唤起单条录音的操作面板（分享 / 下载 / 重命名 / 标签 / 彻底删除）。 */
    private void showMoreActions() {
        if (TextUtils.isEmpty(recordId)) {
            toast(getString(R.string.native_detail_toast_no_record_id));
            return;
        }
        NativeRecordActionSheet.show(this, recordId, recordTransferId, currentName,
                new NativeRecordActionSheet.Callback() {

                    @Override
                    public void onDataChanged() {
                        loadDetail();
                    }

                    @Override
                    public void onRenamed(String newName) {
                        syncTitleToSummary(newName);
                    }

                    @Override
                    public void onRecordDeleted() {
                        // 记录已彻底删除，详情不再存在，直接返回列表
                        toast(getString(R.string.native_list_delete_success));
                        finish();
                    }
                });
    }

    // ===================== 辅助 =====================

    private String transferBadgeText(int t) {
        switch (t) {
            case 1: return getString(R.string.native_transfer_1);
            case 2: return getString(R.string.native_transfer_2);
            case 3: return getString(R.string.native_transfer_3);
            default: return getString(R.string.native_transfer_0);
        }
    }

    private String summaryBadgeText(int s) {
        switch (s) {
            case 2: return getString(R.string.native_summary_2);
            case 3: return getString(R.string.native_summary_3);
            case 4: return getString(R.string.native_summary_4);
            default: return getString(R.string.native_summary_1);
        }
    }

    private static String nullToEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }

    private static String formatDuration(long ms) {
        if (ms <= 0) return "00:00";
        long totalSec = ms / 1000;
        return String.format(Locale.getDefault(), "%02d:%02d", totalSec / 60, totalSec % 60);
    }

    /**
     * 下载回调，只为感知<b>本条录音的音频何时下完</b>。
     * <p>
     * {@code syncDownloadNoteAudio} 同步返回且没有回调，结果只能从这里拿。
     * 而这组回调里<b>只有 {@code onFinish} 是真正的结束</b>——
     * {@code downloadSuccess} / {@code downloadError} 语义仍是「下载中」，
     * 后者的错误码与消息还是无效的，都不能用来判完成。
     * <p>
     * 回调是<b>批次级</b>的：一次 {@code onFinish} 可能包含多条记录，
     * 因此要按 {@code recordId} 过滤出本条。批次里没有本条就静默忽略——
     * 那是云同步在后台补别的文件，与当前页面无关。
     */
    private class AudioDownloadListener implements DownloadListener {

        @Override
        public void onStart() {
        }

        @Override
        public void onDownloadTaskSizeMapReady(@NonNull Map<String, Long> taskSizeMap) {
            // Kotlin 接口的默认方法，Java 实现方需显式覆写，不要依赖默认实现
        }

        @Override
        public void onPause() {
        }

        @Override
        public void downloading(@NonNull RecordFile recordFile, long downloadedBytes,
                                long totalBytes, int progressPercent) {
        }

        @Override
        public void downloadSuccess(@NonNull RecordFile recordFile) {
            // 语义仍是「下载中」，不能当完成用
        }

        @Override
        public void downloadError(@NonNull RecordFile recordFile, @NonNull String errorCode,
                                  @NonNull String errorMsg) {
            // 同上，且这里的 errorCode / errorMsg 无效
        }

        @Override
        public void downloadErrorBatch(@NonNull List<RecordFile> recordFiles, int errorCode,
                                       @NonNull String errorMsg) {
        }

        @Override
        public void onFinish(@NonNull List<RecordFile> succeedRecords,
                             @NonNull List<RecordFile> failedRecords) {
            if (contains(succeedRecords)) {
                runOnUi(() -> toast(getString(R.string.native_action_toast_download_done)));
            } else if (contains(failedRecords)) {
                runOnUi(() -> toast(getString(R.string.native_action_toast_download_failed)));
            }
        }

        /**
         * @param records 批次里的记录
         * @return 是否包含当前详情页这条录音
         */
        private boolean contains(@Nullable List<RecordFile> records) {
            if (records == null || TextUtils.isEmpty(recordId)) return false;
            for (RecordFile file : records) {
                if (file != null && recordId.equals(file.getRecordId())) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 上传回调空实现。
     * <p>
     * {@link SyncObserver} 是持有下载与上传两个监听器的 data class，
     * <b>构造时两者都必须提供</b>，本页只关心下载，故上传侧全部留空。
     * 云同步的聚合状态在 {@link NativeCloudSyncActivity} 里维护，不由本页负责。
     */
    private static class NoopUploadListener implements UploadListener {

        @Override
        public void onStart() {
        }

        @Override
        public void onPause() {
        }

        @Override
        public void uploading(@NonNull RecordFile recordFile, int progress) {
        }

        @Override
        public void uploadSuccess(@NonNull RecordFile recordFile) {
        }

        @Override
        public void uploadError(@NonNull RecordFile recordFile, @NonNull String errorCode,
                                @NonNull String errorMsg) {
        }

        @Override
        public void onFinish(@NonNull List<RecordFile> succeedRecords,
                             @NonNull List<RecordFile> failedRecords) {
        }

        @Override
        public void onError(@Nullable Integer errorCode, @Nullable String errorMsg) {
        }
    }
}
