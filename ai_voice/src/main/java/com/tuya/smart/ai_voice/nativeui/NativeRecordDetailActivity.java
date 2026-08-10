package com.tuya.smart.ai_voice.nativeui;

import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
import com.tuya.smart.ai_voice.nativeui.widget.TransferTextParser;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 模块 2 · 转写 / 总结演示页，兼作单条录音的操作入口。
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

    /** {@code getRecordTransferRecognizeResult} 的来源：0 本地 / 1 云端。 */
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
    private EditText transferText;
    private EditText summaryText;
    private EditText templateInput;
    private EditText summaryLangInput;
    private CheckBox enableSpeakerBox;

    /** 实时转写模式下的句子列表，编辑单句时需要其 asrId。 */
    private final List<RecordTransferRealTimeResult> realtimeSentences = new ArrayList<>();


    /** 文件数据变更监听，用字段持有，remove 时传同一引用。 */
    private IRecordFileUpdateCallback<List<RecordUpdateInfo>> updateCallback;
    /** 上传进度监听，用字段持有，remove 时传同一引用。 */
    private ITransferListener transferListener;

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

        btnTransfer.setOnClickListener(v -> generateTransfer());
        btnSummary.setOnClickListener(v -> generateSummary());
        findViewById(R.id.native_detail_btn_refresh).setOnClickListener(v -> loadDetail());
        findViewById(R.id.native_detail_btn_more).setOnClickListener(v -> showMoreActions());
        findViewById(R.id.native_detail_btn_save_transfer).setOnClickListener(v -> saveTransfer());
        findViewById(R.id.native_detail_btn_save_summary).setOnClickListener(v -> saveSummary());

        // 实时转写模式下点正文可挑单句编辑，走 saveRecordTransferRealTimeRecognizeResult
        transferText.setOnLongClickListener(v -> {
            if (useRealtimeBranch()) {
                pickSentenceToEdit();
                return true;
            }
            return false;
        });
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
                // 仅标签变更，本页不展示标签，忽略
            }
        };
        manager.addFileRecordUpdateListener(updateCallback);

        transferListener = (fileId, progress, status) ->
                runOnUi(() -> handleUploadEvent(fileId, progress, status));
        manager.addTransferListener(transferListener);
        appendLog("addFileRecordUpdateListener + addTransferListener");
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

        appendLog(getString(R.string.native_detail_log_upload, progress, status));
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
            appendLog(getString(R.string.native_detail_log_event_update,
                    recordId, newTransfer, newSummary));
            boolean changed = transferStatus != newTransfer || summaryStatus != newSummary;
            transferStatus = newTransfer;
            summaryStatus = newSummary;
            refreshBadges();
            refreshButtons();
            if (changed) {
                // 重拉详情以同步 transferType / cloudTranscription 等分支判据，再按状态取正文
                appendLog(getString(R.string.native_detail_log_auto_reload));
                loadDetail();
            }
        });
    }

    // ===================== 详情 =====================

    private void loadDetail() {
        appendLog(getString(R.string.native_detail_log_load_detail, recordId));
        manager.getRecordTransferResultDetail(recordId, AMPLITUDE_FULL,
                new IRecordCallBack<RecordTransferResultBean>() {
                    @Override
                    public void onSuccess(RecordTransferResultBean bean) {
                        runOnUi(() -> renderDetail(bean));
                    }

                    @Override
                    public void onError(String code, String error) {
                        runOnUi(() -> appendLog(getString(
                                R.string.native_detail_log_detail_fail, code, error)));
                    }
                });
    }

    private void renderDetail(@Nullable RecordTransferResultBean bean) {
        if (bean == null) {
            appendLog("detail bean null");
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

        currentName = TextUtils.isEmpty(bean.name) ? getString(R.string.native_unnamed) : bean.name;
        nameView.setText(currentName);
        String time = bean.recordTime == null ? "" : dateFmt.format(new Date(bean.recordTime * 1000L));
        metaView.setText(getString(R.string.native_detail_meta_format,
                formatDuration(durationMs), time));

        refreshBadges();
        appendLog(getString(R.string.native_detail_log_detail_ok,
                transferStatus, summaryStatus, recordTransferId));

        markReadIfNeeded(bean.visit);

        if (transferStatus == TRANSFER_DONE || useRealtimeBranch()) {
            loadTransferResult();
        } else {
            transferText.setText(R.string.native_detail_empty_transfer);
        }
        if (summaryStatus == SUMMARY_DONE) {
            loadSummaryResult();
        } else {
            summaryText.setText(R.string.native_detail_empty_summary);
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
     * 三个入参都可为 null，此处按 {@code recordId} 查。单句纠错可用
     * {@code saveRecordTransferRealTimeRecognizeResult(asrId, ...)}，见 {@link #saveSentence}。
     */
    private void loadRealtimeSentences() {
        if (TextUtils.isEmpty(recordId)) return;
        appendLog(getString(R.string.native_detail_log_load_realtime, recordId));
        manager.getRecordTransferRealTimeResult(null, recordId, null,
                new IRecordCallBack<List<RecordTransferRealTimeResult>>() {
                    @Override
                    public void onSuccess(List<RecordTransferRealTimeResult> result) {
                        runOnUi(() -> renderRealtimeSentences(result));
                    }

                    @Override
                    public void onError(String code, String error) {
                        runOnUi(() -> appendLog(getString(
                                R.string.native_detail_log_result_fail, code, error)));
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
        appendLog(getString(R.string.native_detail_log_realtime_ok, result.size()));
    }

    // ===================== 编辑保存 =====================

    /**
     * 保存转写正文。<b>两种模式保存方式完全不同</b>：
     * <ul>
     *     <li>实时转写 —— 逐句调 {@code saveRecordTransferRealTimeRecognizeResult(asrId, ...)}，
     *         按 {@code asrId} 定位，见 {@link #pickSentenceToEdit()}</li>
     *     <li>文件转写 —— 整份 JSON 调 {@code saveRecordTransferRecognizeResult(recordTransferId, text)}</li>
     * </ul>
     * 用错方式会保存不上：实时转写的正文本就不是一份 JSON。
     * <p>
     * 注意 SDK 的 save 系列只写<b>本地</b>。人工纠错若要跨端可见，还需自行调业务云的编辑接口，
     * 用法见 {@code docs/modules/module-02-transcribe-summary.md}，本页不接入。
     */
    private void saveTransfer() {
        if (useRealtimeBranch()) {
            toast(getString(R.string.native_detail_toast_realtime_edit_hint));
            return;
        }
        if (recordTransferId <= 0) {
            toast(getString(R.string.native_detail_toast_no_record_id));
            return;
        }
        String text = transferText.getText().toString();
        appendLog(getString(R.string.native_detail_log_save_transfer, text.length()));
        manager.saveRecordTransferRecognizeResult(recordTransferId, text,
                saveCallback("saveRecordTransferRecognizeResult"));
    }

    /** 保存总结正文，整份内容覆盖写入。同样只写本地。 */
    private void saveSummary() {
        if (recordTransferId <= 0) {
            toast(getString(R.string.native_detail_toast_no_record_id));
            return;
        }
        String text = summaryText.getText().toString();
        appendLog(getString(R.string.native_detail_log_save_summary, text.length()));
        manager.saveRecordTransferSummaryResult(recordTransferId, text,
                saveCallback("saveRecordTransferSummaryResult"));
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

        appendLog(getString(R.string.native_detail_log_mark_read, visit, target));
        // 本地：visit 参数是 String，兼容 "true"/"false"/数字字符串，此处传数字字符串
        manager.updateRecordTransferResult(recordTransferId, null, null,
                String.valueOf(target), null, null, null, null,
                new IResultCallback() {
                    @Override
                    public void onSuccess() {
                        runOnUi(() -> appendLog(getString(R.string.native_detail_log_mark_read_ok)));
                    }

                    @Override
                    public void onError(String code, String error) {
                        runOnUi(() -> appendLog(getString(
                                R.string.native_detail_log_mark_read_fail, code, error)));
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
                runOnUi(() -> appendLog(getString(R.string.native_detail_log_mark_read_cloud_ok)));
            }

            @Override
            public void onFailure(BusinessResponse response, Boolean result, String apiName) {
                String code = response == null ? "-" : response.getErrorCode();
                runOnUi(() -> appendLog(getString(
                        R.string.native_detail_log_mark_read_cloud_fail, code)));
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
     *     <li>小程序在这条路径上带的 {@code updateCloud: false} 指的是<b>不重写云端的总结内容</b>，
     *         与文件名是否同步到云端是两回事</li>
     * </ul>
     *
     * @param title 总结里解析出的标题，可能为 null
     */
    private void applySummaryTitle(@Nullable String title) {
        if (TextUtils.isEmpty(title) || recordTransferId <= 0) return;
        if (TextUtils.equals(title, currentName)) return;

        appendLog(getString(R.string.native_detail_log_title_rename, title));
        manager.updateRecordTransferResult(recordTransferId, title,
                null, null, null, null, null, null,
                new IResultCallback() {
                    @Override
                    public void onSuccess() {
                        runOnUi(() -> {
                            currentName = title;
                            nameView.setText(title);
                            appendLog(getString(R.string.native_detail_log_title_rename_ok));
                            renameOnCloud(title);
                        });
                    }

                    @Override
                    public void onError(String code, String error) {
                        runOnUi(() -> appendLog(getString(
                                R.string.native_detail_log_title_rename_fail, code, error)));
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
            appendLog(getString(R.string.native_action_log_rename_no_home));
            return;
        }
        appendLog(getString(R.string.native_action_log_rename_cloud, name));
        contentBusiness.updateFileName(recordId, name, homeId,
                new Business.ResultListener<Boolean>() {
                    @Override
                    public void onSuccess(BusinessResponse response, Boolean result, String apiName) {
                        runOnUi(() -> appendLog(
                                getString(R.string.native_action_log_rename_cloud_ok)));
                    }

                    @Override
                    public void onFailure(BusinessResponse response, Boolean result, String apiName) {
                        String code = response == null ? "-" : response.getErrorCode();
                        String msg = response == null ? "" : response.getErrorMsg();
                        runOnUi(() -> appendLog(getString(
                                R.string.native_action_log_rename_cloud_fail, code, msg)));
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

    /** 实时转写模式：长按正文挑一句编辑。 */
    private void pickSentenceToEdit() {
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

    /**
     * 编辑单句实时转写。
     * <p>
     * 三个内容字段按需传，{@code null} 表示不修改该字段——这里同时更新展示文案与原始 ASR，
     * 译文保持不变。
     */
    private void editSentence(@NonNull RecordTransferRealTimeResult sentence) {
        EditText input = new EditText(this);
        input.setText(TextUtils.isEmpty(sentence.text) ? nullToEmpty(sentence.asr) : sentence.text);
        new AlertDialog.Builder(this)
                .setTitle(R.string.native_detail_edit_sentence)
                .setView(input)
                .setPositiveButton(R.string.native_action_confirm, (d, w) -> {
                    String newText = input.getText().toString();
                    long asrId = sentence.asrId == null ? 0L : sentence.asrId;
                    appendLog(getString(R.string.native_detail_log_save_sentence, asrId));
                    manager.saveRecordTransferRealTimeRecognizeResult(
                            asrId, newText, newText, null,
                            saveCallback("saveRecordTransferRealTimeRecognizeResult"));
                })
                .setNegativeButton(R.string.native_list_delete_cancel, null)
                .show();
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
                    appendLog(action + " onSuccess");
                    toast(getString(R.string.native_detail_toast_saved));
                    loadDetail();
                });
            }

            @Override
            public void onError(String code, String error) {
                runOnUi(() -> {
                    appendLog(action + " onError " + code + " " + error);
                    toast(RecordErrorCode.messageOf(NativeRecordDetailActivity.this, code, false));
                });
            }
        };
    }

    /** 文件转写模式的正文：返回 JSON 字符串，交由 {@link TransferTextParser} 解析。 */
    private void loadRecognizeResult() {
        if (recordTransferId <= 0) return;
        appendLog(getString(R.string.native_detail_log_load_transfer, recordTransferId));
        manager.getRecordTransferRecognizeResult(recordTransferId, RESULT_FROM_LOCAL,
                new IRecordCallBack<String>() {
                    @Override
                    public void onSuccess(String text) {
                        runOnUi(() -> {
                            appendLog(getString(R.string.native_detail_log_result_ok,
                                    text == null ? 0 : text.length()));
                            transferText.setText(TransferTextParser.parseTranscript(text, durationMs));
                        });
                    }

                    @Override
                    public void onError(String code, String error) {
                        runOnUi(() -> appendLog(getString(
                                R.string.native_detail_log_result_fail, code, error)));
                    }
                });
    }

    private void loadSummaryResult() {
        if (recordTransferId <= 0) return;
        appendLog(getString(R.string.native_detail_log_load_summary, recordTransferId));
        manager.getRecordTransferSummaryResult(recordTransferId, RESULT_FROM_LOCAL,
                new IRecordCallBack<String>() {
                    @Override
                    public void onSuccess(String text) {
                        runOnUi(() -> {
                            appendLog(getString(R.string.native_detail_log_result_ok,
                                    text == null ? 0 : text.length()));
                            summaryText.setText(TransferTextParser.parseSummary(text));
                            // 总结里带的 AI 标题用来替换默认文件名
                            applySummaryTitle(TransferTextParser.parseSummaryTitle(text));
                        });
                    }

                    @Override
                    public void onError(String code, String error) {
                        runOnUi(() -> appendLog(getString(
                                R.string.native_detail_log_result_fail, code, error)));
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
            appendLog(getString(R.string.native_detail_log_downgrade_to_transfer));
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

        appendLog(getString(R.string.native_detail_log_generate,
                recordTransferId, taskType, taskName, lang));
        appendLog(getString(R.string.native_detail_log_generate_param,
                TextUtils.isEmpty(template) ? "-" : template,
                TextUtils.isEmpty(summaryLang) ? "-" : summaryLang,
                enableSpeaker));
        // 构造顺序：(fileId, template, transferType, audioLang, transLang, summaryLang, enableSpeaker)
        TranscribeParam param = new TranscribeParam(
                recordTransferId, template, taskType, lang, null,
                TextUtils.isEmpty(summaryLang) ? null : summaryLang, enableSpeaker);
        manager.processRecordTransferResult(param, new IResultCallback() {
            @Override
            public void onSuccess() {
                runOnUi(() -> {
                    appendLog(getString(R.string.native_detail_log_generate_ok, taskName));
                    toast(taskType == TASK_TRANSCRIPTION
                            ? getString(R.string.native_detail_toast_generating)
                            : getString(R.string.native_detail_toast_summary_generating));
                });
            }

            @Override
            public void onError(String code, String error) {
                runOnUi(() -> {
                    appendLog(getString(R.string.native_detail_log_generate_fail,
                            taskName, code, error));
                    toast(RecordErrorCode.messageOf(
                            NativeRecordDetailActivity.this, code, false));
                });
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
                    public void onLog(String message) {
                        appendLog(message);
                    }

                    @Override
                    public void onDataChanged() {
                        loadDetail();
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
}
