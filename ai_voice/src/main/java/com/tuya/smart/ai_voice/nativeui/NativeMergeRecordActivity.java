package com.tuya.smart.ai_voice.nativeui;

import android.content.Intent;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.thingclips.smart.earphone.enhance.api.bean.FilesParam;
import com.thingclips.smart.earphone.enhance.api.bean.MergeStatusEvent;
import com.thingclips.smart.earphone.enhance.api.bean.RecordTransferResultBean;
import com.thingclips.smart.earphone.enhance.api.listener.IMergeStatusListener;
import com.thingclips.smart.earphone.enhance.api.listener.IRecordCallBack;
import com.thingclips.smart.sdk.api.IResultCallback;
import com.tuya.smart.ai_voice.R;
import com.tuya.smart.ai_voice.nativeui.base.NativeDemoBaseActivity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 模块 8 · 合并录音演示页。
 * <p>
 * 把多条录音按选定顺序合并为一条新录音。完整流程与 AI 笔记一致：
 * <ol>
 *     <li>拉一页录音列表作为候选（真实产品通常在首页列表里多选）</li>
 *     <li>勾选并调整顺序 —— <b>{@code recordIds} 的数组顺序就是音频拼接次序</b>，
 *         真实产品用拖拽排序，本页用上移/下移按钮，语义相同</li>
 *     <li>{@code mergeRecordList(recordIds)} 发起，进度由 {@link IMergeStatusListener} 推送</li>
 *     <li>进行中可 {@code cancelMergeRecordList()} 取消</li>
 * </ol>
 * <b>注意传的是业务 {@code recordId}（String），不是 {@code recordTransferId}（Long）</b>——
 * 与删除接口 {@code removeFileList} 用的 ID 类型不同，容易搞混。
 * <p>
 * 进度条这样映射：下载阶段（subStatus=10）最多显示到 90%，
 * 合并阶段（subStatus=20）直接锁定 90%，完成后才到 100%，避免进度条在合并阶段长时间不动。
 */
public class NativeMergeRecordActivity extends NativeDemoBaseActivity {

    /** 候选列表一次拉取的条数。 */
    private static final int CANDIDATE_PAGE_SIZE = 20;

    /** 列表排序字段：按录音时间。 */
    private static final int ORDER_BY_RECORD_TIME = 1;
    /** 倒序。 */
    private static final int ORDER_DESC = 0;

    /** 下载阶段进度条上限，留出合并阶段的空间。 */
    private static final int DOWNLOAD_PROGRESS_CAP = 90;
    /** 合并阶段固定进度。 */
    private static final int MERGING_PROGRESS = 90;
    /** 完成进度。 */
    private static final int FINISHED_PROGRESS = 100;

    // ===== 发起合并的前置约束 =====
    /** 至少选择的条数。 */
    private static final int MIN_MERGE_COUNT = 2;
    /** 一次最多合并的条数。 */
    private static final int MAX_MERGE_COUNT = 10;
    /** 合并总时长上限：5 小时。 */
    private static final long MAX_TOTAL_DURATION_MS = 5L * 60 * 60 * 1000;

    // ===== 不支持合并的录音类型（RecordTransferResultBean.recordType）=====
    /** 面对面翻译（Pro）。 */
    private static final int RECORD_TYPE_FACE_TO_FACE_PRO = 2;
    /** 面对面翻译（入门版）。 */
    private static final int RECORD_TYPE_FACE_TO_FACE_ENTRY = 3;

    // ===== 合并失败错误码（MergeStatusEvent.errorCode）=====
    /** 参数为空。 */
    private static final int ERR_EMPTY_PARAM = 20220;
    /** 通用合并失败。 */
    private static final int ERR_MERGE_FAILED = 20221;
    /** 网络不可用。 */
    private static final int ERR_NO_NETWORK = 20222;
    /** 本地文件不存在。 */
    private static final int ERR_FILE_NOT_FOUND = 20223;
    /** 上传记录失败。 */
    private static final int ERR_UPLOAD_FAILED = 20224;
    /** 合并结果上报失败。 */
    private static final int ERR_REPORT_FAILED = 20225;
    /** 录音记录未找到。 */
    private static final int ERR_RECORD_NOT_FOUND = 20226;
    /** 未知错误。 */
    private static final int ERR_UNKNOWN = 20227;
    /** 创建合并文件失败。 */
    private static final int ERR_CREATE_FILE_FAILED = 20229;
    /** 合并已被取消。 */
    private static final int ERR_CANCELLED = 20230;

    /** 合并状态监听，用字段持有；注意 remove 方法无参（移除全部）。 */
    private IMergeStatusListener mergeStatusListener;

    private LinearLayout candidateContainer;
    private LinearLayout selectedContainer;
    private TextView mergeStatusText;
    private View btnOpenResult;

    /** 合并成功后的结果 recordId，用于跳转详情。 */
    private String mergedRecordId;

    /** 候选录音：recordId → 条目信息。用 LinkedHashMap 保持列表顺序。 */
    private final Map<String, Candidate> candidates = new LinkedHashMap<>();

    /** 合并候选项，保留发起前校验所需的时长与类型。 */
    private static class Candidate {
        final String label;
        final long durationMs;
        final int recordType;

        Candidate(String label, long durationMs, int recordType) {
            this.label = label;
            this.durationMs = durationMs;
            this.recordType = recordType;
        }

        /** @return 面对面 / 对话类录音不支持合并 */
        boolean isMergeUnsupported() {
            return recordType == RECORD_TYPE_FACE_TO_FACE_PRO
                    || recordType == RECORD_TYPE_FACE_TO_FACE_ENTRY;
        }
    }

    /** 已选录音的 recordId，<b>顺序即合并次序</b>。 */
    private final List<String> selectedIds = new ArrayList<>();

    @Override
    protected int getContentLayoutId() {
        return R.layout.activity_native_merge_record;
    }

    @Override
    protected int getTitleResId() {
        return R.string.native_merge_title;
    }

    @Override
    protected void onContentViewCreated() {
        candidateContainer = findViewById(R.id.ll_candidate_container);
        selectedContainer = findViewById(R.id.ll_selected_container);
        mergeStatusText = findViewById(R.id.tv_merge_status);

        btnOpenResult = findViewById(R.id.btn_open_result);
        btnOpenResult.setEnabled(false);

        findViewById(R.id.btn_load_list).setOnClickListener(v -> loadCandidates());
        findViewById(R.id.btn_merge).setOnClickListener(v -> startMerge());
        findViewById(R.id.btn_cancel_merge).setOnClickListener(v -> cancelMerge());
        btnOpenResult.setOnClickListener(v -> openMergedRecord());

        loadStatusSnapshot();
        loadCandidates();
    }

    // ===================== 事件监听 =====================

    @Override
    protected void registerListeners() {
        mergeStatusListener = event -> runOnUi(() -> renderMergeStatus(event));
        manager.addMergeStatusListener(mergeStatusListener);
    }

    @Override
    protected void unregisterListeners() {
        if (mergeStatusListener != null) {
            // 注意：该 remove 无参，会移除全部已注册的合并状态监听
            manager.removeMergeStatusListener();
            mergeStatusListener = null;
        }
    }

    /**
     * 进页取一次合并状态快照，补上进页前已经在跑的任务。
     * 与模块 5 的 {@code getAudioImportStatus} 是同一套模式：快照 + 增量监听。
     */
    private void loadStatusSnapshot() {
        MergeStatusEvent snapshot = manager.getFileMergeStatus();
        renderMergeStatus(snapshot);
    }

    // ===================== 候选列表 =====================

    /**
     * 拉一页录音作为合并候选。
     * <p>
     * 这是模块 3 的接口，此处直接调用而非藏进基类——「选哪些录音去合并」本就是合并流程的一部分。
     */
    private void loadCandidates() {
        FilesParam param = new FilesParam(
                null,                       // directoryId：null 查全部目录
                null,                       // recordType：null 查全部类型
                null,                       // deviceId：null 查全部设备
                null,                       // transfer：null 查全部转写状态
                null,                       // source：null 查全部来源
                Boolean.FALSE,              // remove：排除回收站
                ORDER_BY_RECORD_TIME,
                ORDER_DESC,
                null,                       // lastFileId：首页
                CANDIDATE_PAGE_SIZE);
        manager.getRecordTransferResultList(param, new IRecordCallBack<List<RecordTransferResultBean>>() {
            @Override
            public void onSuccess(List<RecordTransferResultBean> result) {
                runOnUi(() -> renderCandidates(result));
            }

            @Override
            public void onError(String code, String error) {
                runOnUi(() -> {
                    toast(getString(R.string.native_merge_log_load_fail, code, error));
                });
            }
        });
    }

    /**
     * 渲染候选列表。只保留有 {@code recordId} 的条目——合并接口按 recordId 定位文件。
     *
     * @param result 列表数据，可能为 null
     */
    private void renderCandidates(@Nullable List<RecordTransferResultBean> result) {
        candidates.clear();
        candidateContainer.removeAllViews();
        selectedIds.clear();
        renderSelected();

        if (result == null || result.isEmpty()) {
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        for (RecordTransferResultBean bean : result) {
            if (bean == null || TextUtils.isEmpty(bean.recordId)) continue;
            Candidate candidate = new Candidate(
                    displayName(bean),
                    bean.duration == null ? 0L : bean.duration,
                    bean.recordType == null ? 0 : bean.recordType);
            candidates.put(bean.recordId, candidate);
            candidateContainer.addView(createCandidateView(inflater, bean.recordId, candidate));
        }
    }

    private View createCandidateView(LayoutInflater inflater, String recordId, Candidate candidate) {
        CheckBox cb = (CheckBox) inflater.inflate(
                R.layout.item_native_merge_candidate, candidateContainer, false);
        // 不支持合并的类型直接置灰，避免选中后才被拦截
        if (candidate.isMergeUnsupported()) {
            cb.setText(getString(R.string.native_merge_candidate_unsupported, candidate.label));
            cb.setEnabled(false);
            return cb;
        }
        cb.setText(candidate.label);
        cb.setOnCheckedChangeListener((button, checked) -> {
            if (checked) {
                if (!selectedIds.contains(recordId)) {
                    selectedIds.add(recordId);
                }
            } else {
                selectedIds.remove(recordId);
            }
            renderSelected();
        });
        return cb;
    }

    private String displayName(RecordTransferResultBean bean) {
        String name = TextUtils.isEmpty(bean.name) ? getString(R.string.native_unnamed) : bean.name;
        long durationMs = bean.duration == null ? 0L : bean.duration;
        return getString(R.string.native_merge_candidate_format, name, formatDuration(durationMs));
    }

    // ===================== 已选顺序 =====================

    /** 重绘已选列表。序号从 1 开始，即最终的音频拼接次序。 */
    private void renderSelected() {
        selectedContainer.removeAllViews();
        if (selectedIds.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.native_merge_selected_empty);
            empty.setTextSize(12f);
            empty.setPadding(0, 8, 0, 8);
            selectedContainer.addView(empty);
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < selectedIds.size(); i++) {
            selectedContainer.addView(createSelectedView(inflater, i));
        }
    }

    private View createSelectedView(LayoutInflater inflater, int index) {
        View item = inflater.inflate(R.layout.item_native_merge_selected, selectedContainer, false);
        String recordId = selectedIds.get(index);
        ((TextView) item.findViewById(R.id.tv_index))
                .setText(String.format(Locale.getDefault(), "%d", index + 1));
        Candidate candidate = candidates.get(recordId);
        ((TextView) item.findViewById(R.id.tv_name))
                .setText(candidate == null ? recordId : candidate.label);

        View up = item.findViewById(R.id.btn_up);
        View down = item.findViewById(R.id.btn_down);
        up.setEnabled(index > 0);
        down.setEnabled(index < selectedIds.size() - 1);
        up.setOnClickListener(v -> swapSelected(index, index - 1));
        down.setOnClickListener(v -> swapSelected(index, index + 1));
        return item;
    }

    /**
     * 交换两个已选项的位置，改变合并次序。
     *
     * @param from 原位置
     * @param to   目标位置
     */
    private void swapSelected(int from, int to) {
        if (to < 0 || to >= selectedIds.size()) return;
        String tmp = selectedIds.get(from);
        selectedIds.set(from, selectedIds.get(to));
        selectedIds.set(to, tmp);
        renderSelected();
    }

    // ===================== 合并 =====================

    /**
     * 发起合并前的四道校验，全部通过后再查一次任务状态。
     * <p>
     * 这些约束底层不会替你兜：条数超限或含不支持的类型时，接口不一定报错，
     * 但结果不可预期。校验在客户端做完再发起。
     */
    private void startMerge() {
        int count = selectedIds.size();
        if (count < MIN_MERGE_COUNT) {
            toast(getString(R.string.native_merge_toast_need_min, MIN_MERGE_COUNT));
            return;
        }
        if (count > MAX_MERGE_COUNT) {
            toast(getString(R.string.native_merge_toast_exceed_max, MAX_MERGE_COUNT));
            return;
        }
        long totalDuration = totalSelectedDuration();
        if (totalDuration > MAX_TOTAL_DURATION_MS) {
            toast(getString(R.string.native_merge_toast_exceed_duration,
                    MAX_TOTAL_DURATION_MS / (60 * 60 * 1000)));
            return;
        }
        if (hasUnsupportedSelected()) {
            toast(getString(R.string.native_merge_toast_unsupported_type));
            return;
        }
        // 上一个合并任务未结束时发起会互相干扰，先查一次快照
        MergeStatusEvent running = manager.getFileMergeStatus();
        if (running != null && running.getStatus() == MergeStatusEvent.STATUS_IN_PROGRESS) {
            toast(getString(R.string.native_merge_toast_task_running));
            return;
        }
        doMerge();
    }

    /** @return 已选录音的总时长（毫秒） */
    private long totalSelectedDuration() {
        long total = 0L;
        for (String recordId : selectedIds) {
            Candidate candidate = candidates.get(recordId);
            if (candidate != null) {
                total += candidate.durationMs;
            }
        }
        return total;
    }

    /** @return 已选中是否包含不支持合并的类型 */
    private boolean hasUnsupportedSelected() {
        for (String recordId : selectedIds) {
            Candidate candidate = candidates.get(recordId);
            if (candidate != null && candidate.isMergeUnsupported()) {
                return true;
            }
        }
        return false;
    }

    /** 校验通过后真正下发合并。 */
    private void doMerge() {
        manager.mergeRecordList(new ArrayList<>(selectedIds), new IResultCallback() {
            @Override
            public void onSuccess() {
            }

            @Override
            public void onError(String code, String error) {
                runOnUi(() -> {
                    toast(getString(R.string.native_merge_log_merge_fail, code, error));
                });
            }
        });
    }

    /** 取消进行中的合并任务。 */
    private void cancelMerge() {
        manager.cancelMergeRecordList(new IResultCallback() {
            @Override
            public void onSuccess() {
            }

            @Override
            public void onError(String code, String error) {
                toastError(code, error);
            }
        });
    }

    /**
     * 渲染合并进度。
     * <p>
     * 进度映射：下载阶段最多 90%，合并阶段固定 90%，完成 100%。
     *
     * @param event 合并状态，可能为 null
     */
    private void renderMergeStatus(@Nullable MergeStatusEvent event) {
        if (event == null) {
            mergeStatusText.setText(R.string.native_merge_status_empty);
            return;
        }
        int status = event.getStatus();
        Integer subStatus = event.getSubStatus();
        int rawProgress = event.getProgress() == null ? 0 : event.getProgress();
        int shownProgress = mapProgress(status, subStatus, rawProgress);
        int errorCode = event.getErrorCode() == null ? 0 : event.getErrorCode();

        mergeStatusText.setText(getString(R.string.native_merge_status_format,
                mergeStatusName(status),
                subStatusName(subStatus),
                shownProgress,
                errorCode,
                errorCode == 0 ? "-" : mergeErrorMessage(errorCode),
                TextUtils.isEmpty(event.getRecordId()) ? "-" : event.getRecordId()));


        if (status == MergeStatusEvent.STATUS_FINISHED) {
            mergedRecordId = event.getRecordId();
            btnOpenResult.setEnabled(!TextUtils.isEmpty(mergedRecordId));
            toast(getString(R.string.native_merge_toast_finished, mergedRecordId));
        } else if (status == MergeStatusEvent.STATUS_ERROR) {
            toast(mergeErrorMessage(errorCode));
        }
    }

    /**
     * 合并失败原因。
     *
     * @param errorCode {@code MergeStatusEvent.errorCode}
     * @return 可读原因
     */
    private String mergeErrorMessage(int errorCode) {
        switch (errorCode) {
            case ERR_EMPTY_PARAM: return getString(R.string.native_merge_err_20220);
            case ERR_MERGE_FAILED: return getString(R.string.native_merge_err_20221);
            case ERR_NO_NETWORK: return getString(R.string.native_merge_err_20222);
            case ERR_FILE_NOT_FOUND: return getString(R.string.native_merge_err_20223);
            case ERR_UPLOAD_FAILED: return getString(R.string.native_merge_err_20224);
            case ERR_REPORT_FAILED: return getString(R.string.native_merge_err_20225);
            case ERR_RECORD_NOT_FOUND: return getString(R.string.native_merge_err_20226);
            case ERR_UNKNOWN: return getString(R.string.native_merge_err_20227);
            case ERR_CREATE_FILE_FAILED: return getString(R.string.native_merge_err_20229);
            case ERR_CANCELLED: return getString(R.string.native_merge_err_20230);
            default: return getString(R.string.native_merge_err_unknown, errorCode);
        }
    }

    /** 打开合并结果的详情页。 */
    private void openMergedRecord() {
        if (TextUtils.isEmpty(mergedRecordId)) return;
        startActivity(new Intent(this, NativeRecordDetailActivity.class)
                .putExtra(NativeRecordDetailActivity.EXTRA_RECORD_ID, mergedRecordId));
    }

    /**
     * 把原始进度映射为展示进度。
     *
     * @param status      主状态
     * @param subStatus   子状态
     * @param rawProgress 原始进度 0-100
     * @return 展示进度 0-100
     */
    private int mapProgress(int status, @Nullable Integer subStatus, int rawProgress) {
        if (status == MergeStatusEvent.STATUS_FINISHED) {
            return FINISHED_PROGRESS;
        }
        if (status != MergeStatusEvent.STATUS_IN_PROGRESS || subStatus == null) {
            return 0;
        }
        if (subStatus == MergeStatusEvent.SUB_STATUS_MERGING) {
            return MERGING_PROGRESS;
        }
        if (subStatus == MergeStatusEvent.SUB_STATUS_DOWNLOADING) {
            return Math.min(rawProgress, DOWNLOAD_PROGRESS_CAP);
        }
        return rawProgress;
    }

    private String mergeStatusName(int status) {
        if (status == MergeStatusEvent.STATUS_IN_PROGRESS) {
            return getString(R.string.native_merge_state_running);
        }
        if (status == MergeStatusEvent.STATUS_FINISHED) {
            return getString(R.string.native_merge_state_finished);
        }
        if (status == MergeStatusEvent.STATUS_ERROR) {
            return getString(R.string.native_merge_state_error);
        }
        return getString(R.string.native_merge_state_not_started);
    }

    private String subStatusName(@Nullable Integer subStatus) {
        if (subStatus == null) return "-";
        if (subStatus == MergeStatusEvent.SUB_STATUS_DOWNLOADING) {
            return getString(R.string.native_merge_sub_downloading);
        }
        if (subStatus == MergeStatusEvent.SUB_STATUS_MERGING) {
            return getString(R.string.native_merge_sub_merging);
        }
        return String.valueOf(subStatus);
    }

    private static String formatDuration(long ms) {
        long totalSec = ms / 1000;
        return String.format(Locale.getDefault(), "%02d:%02d", totalSec / 60, totalSec % 60);
    }
}
