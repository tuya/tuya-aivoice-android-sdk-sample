package com.tuya.smart.ai_voice.nativeui;

import android.content.Intent;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.thingclips.smart.earphone.enhance.api.bean.FailedFileInfo;
import com.thingclips.smart.earphone.enhance.api.bean.FileImportStatusEventApp;
import com.thingclips.smart.earphone.enhance.api.listener.IAudioImportCallBack;
import com.thingclips.smart.earphone.enhance.api.listener.IFileImportStatusListener;
import com.tuya.smart.ai_voice.R;
import com.tuya.smart.ai_voice.nativeui.base.NativeDemoBaseActivity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模块 5 · 音频导入演示页。
 * <p>
 * 把手机本地音频文件导入为录音记录，导入后可像普通录音一样转写、总结。
 * <p>
 * <b>接入必读：{@code startImport} 会拉起系统文件选择器，宿主 Activity 必须重写
 * {@code onActivityResult} 并调用 {@code handleImportActivityResult} 把结果回灌</b>，
 * 否则用户选完文件后毫无反应——见 {@link #onActivityResult(int, int, Intent)}。
 * 这一步是 Native 接入独有的，其他容器由宿主代为转交。
 * <p>
 * 完整流程（与 AI 笔记一致）：
 * <ol>
 *     <li>进页 {@code getAudioImportStatus()} 同步取一次状态快照（可能为 null），补上进页前已发生的进度</li>
 *     <li>注册 {@link IFileImportStatusListener} 接收后续增量状态</li>
 *     <li>点击导入前先判「是否正在导入」，是则拦截提示，避免重复发起</li>
 *     <li>{@code startImport} → 文件选择器 → {@code handleImportActivityResult} 回灌</li>
 *     <li>状态为「导入完成」且 {@code failedFiles} 非空时，可 {@code retryImport} 重试或
 *         {@code cancelRetry} 放弃；导入过程中可 {@code cancelImport} 取消</li>
 * </ol>
 */
public class NativeAudioImportActivity extends NativeDemoBaseActivity {

    // ===== FileImportStatusEventApp.status =====
    /** 未开始导入。 */
    private static final int IMPORT_NOT_STARTED = 0;
    /** 导入中。 */
    private static final int IMPORTING = 1;
    /** 导入完成（可能含失败文件）。 */
    private static final int IMPORTED = 2;
    /** 导入中断 / 失败。 */
    private static final int IMPORT_FAILED = 3;
    /** 分享导入异常。 */
    private static final int SHARE_IMPORT_FAILED = 4;

    // ===== 单文件失败码（FailedFileInfo.errorCode），多个码归为同一可读原因 =====
    /** 格式异常。 */
    private static final int ERR_FILE_FORMAT = 20103;
    /** 文件格式错误，无法解码。 */
    private static final int ERR_FILE_CANNOT_DECODE = 20110;
    /** 文件不存在。 */
    private static final int ERR_FILE_NOT_FOUND = 20105;
    /** 文件属性已改变。 */
    private static final int ERR_FILE_ATTR_CHANGED = 20106;
    /** 文件时长过长。 */
    private static final int ERR_FILE_TOO_LONG = 20107;
    /** 因文件错误导致解码中断。 */
    private static final int ERR_FILE_DECODE_INTERRUPTED = 20111;
    /** 导入异常中断。 */
    private static final int ERR_FILE_IMPORT_INTERRUPTED = 20112;
    /** SDK 初始化失败。 */
    private static final int ERR_FILE_SDK_INIT_FAILED = 20108;
    /** 启动音频解码失败。 */
    private static final int ERR_FILE_DECODE_START_FAILED = 20109;
    /** mp3 创建失败。 */
    private static final int ERR_FILE_MP3_CREATE_FAILED = 20113;

    // ===== 任务级失败码（FileImportStatusEventApp.errorCode）=====
    /** 文件数量超限。 */
    private static final int ERR_TASK_FILE_COUNT_EXCEEDED = 20101;
    /** 单个文件过大。 */
    private static final int ERR_TASK_FILE_TOO_LARGE = 20102;
    /** 手机存储空间不足。 */
    private static final int ERR_TASK_NO_SPACE = 20104;
    /** 导入已经开始，无法重复导入。 */
    private static final int ERR_TASK_ALREADY_IMPORTING = 20115;
    /** 录音进行中，无法导入。 */
    private static final int ERR_TASK_RECORDING = 20116;
    /** 存在可恢复的导入任务，无法重复导入。 */
    private static final int ERR_TASK_RESUMABLE_EXISTS = 20117;
    /** 存在导入失败的记录，兜底码。 */
    private static final int ERR_TASK_SHARE_FAILED = 20118;

    /** 状态监听，用字段持有；注意 remove 方法是无参的（移除全部）。 */
    private IFileImportStatusListener importStatusListener;

    private TextView statusText;
    private TextView failedFilesText;

    /** 当前是否正在导入，用于发起前拦截。 */
    private boolean importing = false;

    @Override
    protected int getContentLayoutId() {
        return R.layout.activity_native_audio_import;
    }

    @Override
    protected int getTitleResId() {
        return R.string.native_import_title;
    }

    @Override
    protected void onContentViewCreated() {
        statusText = findViewById(R.id.tv_import_status);
        failedFilesText = findViewById(R.id.tv_failed_files);

        findViewById(R.id.btn_start_import).setOnClickListener(v -> startImport());
        findViewById(R.id.btn_cancel_import).setOnClickListener(v -> cancelImport());
        findViewById(R.id.btn_retry_import).setOnClickListener(v -> retryImport());
        findViewById(R.id.btn_cancel_retry).setOnClickListener(v -> cancelRetry());

        loadStatusSnapshot();
    }

    // ===================== 事件监听 =====================

    @Override
    protected void registerListeners() {
        importStatusListener = event -> runOnUi(() -> renderStatus(event));
        manager.addFileImportStatusListener(importStatusListener);
    }

    @Override
    protected void unregisterListeners() {
        if (importStatusListener != null) {
            // 注意：该 remove 无参，会移除全部已注册的导入状态监听
            manager.removeFileImportStatusListener();
            importStatusListener = null;
        }
    }

    // ===================== 状态快照 =====================

    /**
     * 取一次导入状态快照。同步返回，可能为 {@code null}（从未导入过）。
     * <p>
     * 只在进页调用一次，后续变化靠 {@link IFileImportStatusListener} 增量推送。
     */
    private void loadStatusSnapshot() {
        FileImportStatusEventApp snapshot = manager.getAudioImportStatus();
        renderStatus(snapshot);
    }

    // ===================== 导入操作 =====================

    /**
     * 发起导入。会拉起系统文件选择器，结果必须经
     * {@link #onActivityResult(int, int, Intent)} 回灌，流程才能继续。
     */
    private void startImport() {
        if (importing) {
            toast(getString(R.string.native_import_toast_importing));
            return;
        }
        manager.startImport(new IAudioImportCallBack() {
            @Override
            public void onSuccess() {
            }

            @Override
            public void onError(Integer code, String error) {
                runOnUi(() -> {
                    toast(getString(R.string.native_import_log_start_fail, String.valueOf(code), error));
                });
            }
        });
    }

    /** 取消进行中的导入任务。 */
    private void cancelImport() {
        manager.cancelImport(simpleCallback("cancelImport"));
    }

    /** 重试导入失败的文件。同样会拉起文件选择器，结果需回灌。 */
    private void retryImport() {
        manager.retryImport(simpleCallback("retryImport"));
    }

    /** 放弃重试，清理失败列表。 */
    private void cancelRetry() {
        manager.cancelRetry(simpleCallback("cancelRetry"));
    }

    /**
     * 构造只打日志的导入回调。
     *
     * @param action 动作名，用于日志区分
     * @return 回调实例
     */
    private IAudioImportCallBack simpleCallback(String action) {
        return new IAudioImportCallBack() {
            @Override
            public void onSuccess() {
            }

            @Override
            public void onError(Integer code, String error) {
                toastError(String.valueOf(code), error);
            }
        };
    }

    // ===================== 文件选择器结果回灌 =====================

    /**
     * 把系统文件选择器的结果透传给 SDK。
     * <p>
     * <b>这是接入音频导入的必要环节</b>：{@code startImport} / {@code retryImport} 内部
     * 通过宿主 Activity 拉起选择器，但拿不到 {@code onActivityResult}，必须由宿主转交。
     * 漏掉这一步的表现是「点了导入、选完文件、没有任何状态事件」。
     * <p>
     * 无需判断 requestCode，SDK 内部会自行识别属于自己的请求。
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        manager.handleImportActivityResult(requestCode, resultCode, data);
    }

    // ===================== 渲染 =====================

    /**
     * 渲染导入状态。
     *
     * @param event 状态事件，可能为 null
     */
    private void renderStatus(@Nullable FileImportStatusEventApp event) {
        if (event == null) {
            statusText.setText(R.string.native_import_status_empty);
            failedFilesText.setText(R.string.native_import_failed_empty);
            importing = false;
            return;
        }
        int status = event.status == null ? IMPORT_NOT_STARTED : event.status;
        importing = status == IMPORTING;

        int total = event.totalFileCount == null ? 0 : event.totalFileCount;
        int success = event.successCount == null ? 0 : event.successCount;
        int errorCode = event.errorCode == null ? 0 : event.errorCode;

        statusText.setText(getString(R.string.native_import_status_format,
                importStatusName(status),
                total,
                success,
                percent(success, total),
                errorCode,
                errorCode == 0 ? "-" : taskErrorMessage(errorCode)));

        renderFailedFiles(event.failedFiles);

        if (status == SHARE_IMPORT_FAILED) {
            // 分享导入失败：由系统分享入口触发的导入，本 Demo 未注册分享入口，正常不会收到
            toast(taskErrorMessage(errorCode));
        }
    }

    /**
     * 计算导入进度百分比。
     *
     * @param success 已成功数
     * @param total   文件总数
     * @return 0-100
     */
    private int percent(int success, int total) {
        return total <= 0 ? 0 : success * 100 / total;
    }

    /**
     * 渲染失败文件列表，按失败原因归类展示——同一原因往往对应多个文件，
     * 逐条罗列裸错误码对使用者没有意义。
     *
     * @param failedFiles 失败文件，可能为 null
     */
    private void renderFailedFiles(@Nullable List<FailedFileInfo> failedFiles) {
        if (failedFiles == null || failedFiles.isEmpty()) {
            failedFilesText.setText(R.string.native_import_failed_empty);
            return;
        }
        // 原因 → 文件名列表，用 LinkedHashMap 保持首次出现的顺序
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (FailedFileInfo info : failedFiles) {
            int code = info.errorCode == null ? 0 : info.errorCode;
            String reason = fileErrorMessage(code);
            List<String> names = grouped.get(reason);
            if (names == null) {
                names = new ArrayList<>();
                grouped.put(reason, names);
            }
            names.add(info.fileName == null ? "-" : info.fileName);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.native_import_failed_count, failedFiles.size())).append("\n\n");
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            sb.append(entry.getKey()).append('\n');
            for (String name : entry.getValue()) {
                sb.append("  · ").append(name).append('\n');
            }
            sb.append('\n');
        }
        failedFilesText.setText(sb.toString().trim());
    }

    /**
     * 单个文件的导入失败原因。
     * <p>
     * 多个错误码归为同一原因——使用者只需要知道「为什么失败、能不能重试」。
     *
     * @param errorCode {@code FailedFileInfo.errorCode}
     * @return 可读原因
     */
    private String fileErrorMessage(int errorCode) {
        switch (errorCode) {
            case ERR_FILE_FORMAT:
            case ERR_FILE_CANNOT_DECODE:
                return getString(R.string.native_import_err_format);
            case ERR_FILE_NOT_FOUND:
                return getString(R.string.native_import_err_file_not_found);
            case ERR_FILE_ATTR_CHANGED:
                return getString(R.string.native_import_err_attr_changed);
            case ERR_FILE_TOO_LONG:
                return getString(R.string.native_import_err_too_long);
            case ERR_FILE_DECODE_INTERRUPTED:
            case ERR_FILE_IMPORT_INTERRUPTED:
                return getString(R.string.native_import_err_interrupted);
            case ERR_FILE_SDK_INIT_FAILED:
            case ERR_FILE_DECODE_START_FAILED:
            case ERR_FILE_MP3_CREATE_FAILED:
                return getString(R.string.native_import_err_busy);
            default:
                return getString(R.string.native_import_err_unknown, errorCode);
        }
    }

    /**
     * 整个导入任务失败的原因（{@code FileImportStatusEventApp.errorCode}）。
     *
     * @param errorCode 任务级错误码
     * @return 可读原因
     */
    private String taskErrorMessage(int errorCode) {
        switch (errorCode) {
            case ERR_TASK_FILE_COUNT_EXCEEDED: return getString(R.string.native_import_err_file_limit);
            case ERR_TASK_FILE_TOO_LARGE: return getString(R.string.native_import_err_file_too_large);
            case ERR_TASK_NO_SPACE: return getString(R.string.native_import_err_no_space);
            case ERR_TASK_ALREADY_IMPORTING: return getString(R.string.native_import_err_already_importing);
            case ERR_TASK_RECORDING: return getString(R.string.native_import_err_recording);
            case ERR_TASK_RESUMABLE_EXISTS: return getString(R.string.native_import_err_resumable);
            case ERR_TASK_SHARE_FAILED: return getString(R.string.native_import_err_share_failed);
            default: return getString(R.string.native_import_err_unknown, errorCode);
        }
    }

    private String importStatusName(int status) {
        switch (status) {
            case IMPORTING: return getString(R.string.native_import_state_importing);
            case IMPORTED: return getString(R.string.native_import_state_imported);
            case IMPORT_FAILED: return getString(R.string.native_import_state_failed);
            case SHARE_IMPORT_FAILED: return getString(R.string.native_import_state_share_failed);
            case IMPORT_NOT_STARTED:
            default: return getString(R.string.native_import_state_not_started);
        }
    }
}
