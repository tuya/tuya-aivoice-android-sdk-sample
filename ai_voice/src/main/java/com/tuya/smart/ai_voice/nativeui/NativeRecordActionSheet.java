package com.tuya.smart.ai_voice.nativeui;

import android.app.Activity;
import android.text.TextUtils;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.thingclips.smart.android.network.Business;
import com.thingclips.smart.android.network.http.BusinessResponse;
import com.thingclips.smart.api.service.MicroServiceManager;
import com.thingclips.smart.commonbiz.bizbundle.family.api.AbsBizBundleFamilyService;
import com.thingclips.smart.earphone.enhance.api.bean.UpdateRecordTagResultParams;
import com.thingclips.smart.earphone.enhance.api.listener.IOperateRecordShareLinkResult;
import com.thingclips.smart.plugin.tuniaudiodetectmanager.ThingAudioDetectManagerNative;
import com.thingclips.smart.sdk.api.IResultCallback;
import com.tuya.smart.ai_voice.R;
import com.tuya.smart.ai_voice.nativeui.business.AudioContentBusiness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 录音「更多操作」面板：针对单条录音的一次性 API 调用集合。
 * <p>
 * 这些能力分属不同模块，但共同点是「作用于某一条录音」，因此集中在详情页而非各自独立成页：
 * <ul>
 *     <li>模块 9 · 分享链接 —— {@code operateRecordShareLink}，开启与关闭</li>
 *     <li>模块 6 · 按需下载音频 —— {@code syncDownloadNoteAudio}</li>
 *     <li>模块 3 · 重命名 —— {@code updateRecordTransferResult}</li>
 *     <li>模块 3 · 标签增删 —— {@code updateRecordTagResult}</li>
 *     <li>模块 3 · 彻底删除 —— {@code removeFileList(ids, isDeleteAll=true)}</li>
 * </ul>
 * 用系统 {@code AlertDialog} 承载，不引入额外布局，便于整段复制。
 */
public final class NativeRecordActionSheet {

    // ===== operateRecordShareLink.shareStatus =====
    /** 开启分享。 */
    private static final int SHARE_STATUS_ON = 1;
    /** 关闭分享。 */
    private static final int SHARE_STATUS_OFF = 2;

    /** 分享类型，接口约定为数组，此处使用链接分享。 */
    private static final List<String> SHARE_TYPE_LINK = Collections.singletonList("link");

    /** 分享链接有效期，7 天。 */
    private static final long SHARE_EXPIRE_MILLIS = 7L * 24 * 60 * 60 * 1000;

    // ===== UpdateRecordTagResultParams.bizType =====
    /** 新增标签。 */
    private static final int TAG_BIZ_ADD = 0;
    /** 删除标签。 */
    private static final int TAG_BIZ_REMOVE = 1;
    /** 重排标签顺序，{@code tags} 传完整的新顺序。 */
    private static final int TAG_BIZ_REORDER = 2;

    /** {@code syncDownloadNoteAudio} 的 fileId 缺省值。 */
    private static final long FILE_ID_ABSENT = -1L;

    /** 录音元信息的云端写入，重命名时与 SDK 的本地写入配对使用。 */
    private static final AudioContentBusiness CONTENT_BUSINESS = new AudioContentBusiness();

    private NativeRecordActionSheet() {
    }

    /**
     * 操作结果回调，供详情页刷新界面与打日志。
     */
    public interface Callback {
        /**
         * @param message 可直接展示给用户的结果描述
         */
        void onLog(String message);

        /** 数据已变更，调用方应重新拉取详情。 */
        void onDataChanged();

        /** 录音已被彻底删除，详情已不存在，调用方应关闭页面。 */
        void onRecordDeleted();
    }

    /**
     * 弹出操作面板。
     *
     * @param activity         宿主
     * @param recordId         业务录音 ID（分享、标签、下载用）
     * @param recordTransferId 文件 ID（重命名、删除用）
     * @param currentName      当前文件名，作为重命名输入框默认值
     * @param callback         结果回调
     */
    public static void show(@NonNull Activity activity, @NonNull String recordId,
                            long recordTransferId, @Nullable String currentName,
                            @NonNull Callback callback) {
        String[] items = {
                activity.getString(R.string.native_action_share_on),
                activity.getString(R.string.native_action_share_off),
                activity.getString(R.string.native_action_download_audio),
                activity.getString(R.string.native_action_rename),
                activity.getString(R.string.native_action_add_tag),
                activity.getString(R.string.native_action_remove_tag),
                activity.getString(R.string.native_action_reorder_tag),
                activity.getString(R.string.native_action_delete),
        };
        new AlertDialog.Builder(activity)
                .setTitle(R.string.native_detail_more)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0: operateShare(activity, recordId, SHARE_STATUS_ON, callback); break;
                        case 1: operateShare(activity, recordId, SHARE_STATUS_OFF, callback); break;
                        case 2: downloadAudio(activity, recordTransferId, recordId, callback); break;
                        case 3: rename(activity, recordId, recordTransferId, currentName, callback); break;
                        case 4: editTag(activity, recordId, TAG_BIZ_ADD, callback); break;
                        case 5: editTag(activity, recordId, TAG_BIZ_REMOVE, callback); break;
                        case 6: editTag(activity, recordId, TAG_BIZ_REORDER, callback); break;
                        case 7: deleteRecord(activity, recordTransferId, currentName, callback); break;
                        default: break;
                    }
                })
                .show();
    }

    // ===================== 模块 9 · 分享链接 =====================

    /**
     * 开启或关闭分享链接。
     * <p>
     * 开启时返回可分享的链接地址；关闭后原链接立即失效。
     * {@code password} 传 null 表示不设访问密码；{@code expireTime} 是绝对到期时间戳。
     *
     * @param shareStatus {@link #SHARE_STATUS_ON} 或 {@link #SHARE_STATUS_OFF}
     */
    private static void operateShare(Activity activity, String recordId, int shareStatus,
                                     Callback callback) {
        long expireTime = System.currentTimeMillis() + SHARE_EXPIRE_MILLIS;
        callback.onLog(activity.getString(R.string.native_action_log_share, shareStatus));
        ThingAudioDetectManagerNative.getInstance().operateRecordShareLink(
                recordId, new ArrayList<>(SHARE_TYPE_LINK), expireTime, shareStatus, null,
                new IOperateRecordShareLinkResult() {
                    @Override
                    public void onSuccess(String link) {
                        activity.runOnUiThread(() -> {
                            callback.onLog(activity.getString(
                                    R.string.native_action_log_share_ok,
                                    TextUtils.isEmpty(link) ? "-" : link));
                            callback.onDataChanged();
                        });
                    }

                    @Override
                    public void onError(String code, String error) {
                        activity.runOnUiThread(() -> callback.onLog(activity.getString(
                                R.string.native_action_log_share_fail, code, error)));
                    }
                });
    }

    // ===================== 模块 6 · 按需下载音频 =====================

    /**
     * 下载这条录音的云端音频到本地。
     * <p>
     * <b>该接口同步返回且无回调</b>——下载进度要通过云同步的 {@code SyncObserver}
     * 观察（见 {@link NativeCloudSyncActivity}），单条状态变化则由
     * {@code IRecordFileUpdateCallback} 的 {@code cloudSyncStatus} 反映。
     *
     * @param recordTransferId 文件 ID；无效时按接口约定传 -1
     */
    private static void downloadAudio(Activity activity, long recordTransferId, String recordId,
                                      Callback callback) {
        long fileId = recordTransferId > 0 ? recordTransferId : FILE_ID_ABSENT;
        ThingAudioDetectManagerNative.getInstance().syncDownloadNoteAudio(fileId, recordId);
        callback.onLog(activity.getString(R.string.native_action_log_download, fileId, recordId));
    }

    // ===================== 模块 3 · 元信息更新 =====================

    /**
     * 重命名。<b>本地与云端要分别写一次</b>：
     * <ol>
     *     <li>SDK 的 {@code updateRecordTransferResult} 改本地库——界面立刻能看到新名字，
     *         但云端仍是旧值。该接口的可选参数传 null 表示「不修改该字段」，故只填 {@code name}</li>
     *     <li>{@link AudioContentBusiness#updateFileName} 改云端——其他端与重装后能看到，
     *         但本地库不会自动跟着变</li>
     * </ol>
     * 只写其中一处都会导致两端不一致。本地成功后再写云端，云端失败时提示但不回滚本地，
     * 让使用者能看到「本地已改、云端未同步」这个中间态。
     */
    private static void rename(Activity activity, String recordId, long recordTransferId,
                               @Nullable String currentName, Callback callback) {
        EditText input = new EditText(activity);
        input.setText(currentName == null ? "" : currentName);
        new AlertDialog.Builder(activity)
                .setTitle(R.string.native_action_rename)
                .setView(input)
                .setPositiveButton(R.string.native_action_confirm, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (TextUtils.isEmpty(name)) return;
                    callback.onLog(activity.getString(R.string.native_action_log_rename, name));
                    ThingAudioDetectManagerNative.getInstance().updateRecordTransferResult(
                            recordTransferId, name, null, null, null, null, null, null,
                            new IResultCallback() {
                                @Override
                                public void onSuccess() {
                                    activity.runOnUiThread(() -> {
                                        callback.onLog("updateRecordTransferResult onSuccess");
                                        callback.onDataChanged();
                                        renameOnCloud(activity, recordId, name, callback);
                                    });
                                }

                                @Override
                                public void onError(String code, String error) {
                                    activity.runOnUiThread(() -> callback.onLog(
                                            "updateRecordTransferResult onError " + code + " " + error));
                                }
                            });
                })
                .setNegativeButton(R.string.native_list_delete_cancel, null)
                .show();
    }

    /**
     * 把新文件名写到云端。
     *
     * @param recordId 业务录音 ID
     * @param name     新文件名
     */
    private static void renameOnCloud(Activity activity, String recordId, String name,
                                      Callback callback) {
        long homeId = currentHomeId();
        if (homeId <= 0) {
            callback.onLog(activity.getString(R.string.native_action_log_rename_no_home));
            return;
        }
        callback.onLog(activity.getString(R.string.native_action_log_rename_cloud, name));
        CONTENT_BUSINESS.updateFileName(recordId, name, homeId,
                new Business.ResultListener<Boolean>() {
                    @Override
                    public void onSuccess(BusinessResponse response, Boolean result, String apiName) {
                        activity.runOnUiThread(() -> callback.onLog(
                                activity.getString(R.string.native_action_log_rename_cloud_ok)));
                    }

                    @Override
                    public void onFailure(BusinessResponse response, Boolean result, String apiName) {
                        String code = response == null ? "-" : response.getErrorCode();
                        String msg = response == null ? "" : response.getErrorMsg();
                        activity.runOnUiThread(() -> callback.onLog(activity.getString(
                                R.string.native_action_log_rename_cloud_fail, code, msg)));
                    }
                });
    }

    /**
     * @return 当前家庭 ID；家庭服务不可用时返回 {@code 0}
     */
    private static long currentHomeId() {
        try {
            AbsBizBundleFamilyService familyService = MicroServiceManager.getInstance()
                    .findServiceByInterface(AbsBizBundleFamilyService.class.getName());
            return familyService == null ? 0L : familyService.getCurrentHomeId();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 彻底删除这条录音：{@code removeFileList([id], isDeleteAll=true)}，本地与云端都会删掉，不可恢复。
     * <p>
     * 删除后详情已不存在，通过 {@link Callback#onRecordDeleted()} 通知调用方关闭页面。
     */
    private static void deleteRecord(Activity activity, long recordTransferId,
                                     @Nullable String currentName, Callback callback) {
        String name = TextUtils.isEmpty(currentName)
                ? activity.getString(R.string.native_unnamed) : currentName;
        new AlertDialog.Builder(activity)
                .setTitle(R.string.native_list_delete_title)
                .setMessage(activity.getString(R.string.native_list_delete_msg, name))
                .setPositiveButton(R.string.native_list_delete_confirm, (d, w) -> {
                    callback.onLog(activity.getString(
                            R.string.native_action_log_delete, recordTransferId));
                    ThingAudioDetectManagerNative.getInstance().removeFileList(
                            Collections.singletonList(recordTransferId), true,
                            new IResultCallback() {
                                @Override
                                public void onSuccess() {
                                    activity.runOnUiThread(() -> {
                                        callback.onLog("removeFileList onSuccess");
                                        callback.onRecordDeleted();
                                    });
                                }

                                @Override
                                public void onError(String code, String error) {
                                    activity.runOnUiThread(() -> callback.onLog(
                                            "removeFileList onError " + code + " " + error));
                                }
                            });
                })
                .setNegativeButton(R.string.native_list_delete_cancel, null)
                .show();
    }

    /**
     * 标签增删改。三种 {@code bizType} 的入参含义不同：
     * <ul>
     *     <li>{@link #TAG_BIZ_ADD} —— {@code tags} 是要新增的标签</li>
     *     <li>{@link #TAG_BIZ_REMOVE} —— {@code tags} 是要删除的标签</li>
     *     <li>{@link #TAG_BIZ_REORDER} —— {@code tags} 必须是<b>完整的新顺序</b>，
     *         而不是被移动的那几个；漏传会导致缺失的标签被丢弃</li>
     * </ul>
     *
     * @param bizType 操作类型
     */
    private static void editTag(Activity activity, String recordId, int bizType, Callback callback) {
        EditText input = new EditText(activity);
        input.setHint(bizType == TAG_BIZ_REORDER
                ? R.string.native_action_tag_reorder_hint : R.string.native_action_tag_hint);
        new AlertDialog.Builder(activity)
                .setTitle(tagDialogTitle(bizType))
                .setView(input)
                .setPositiveButton(R.string.native_action_confirm, (d, w) -> {
                    String tag = input.getText().toString().trim();
                    if (TextUtils.isEmpty(tag)) return;
                    UpdateRecordTagResultParams param = new UpdateRecordTagResultParams(
                            recordId, bizType, new ArrayList<>(Arrays.asList(tag.split(","))));
                    callback.onLog(activity.getString(R.string.native_action_log_tag, bizType, tag));
                    ThingAudioDetectManagerNative.getInstance().updateRecordTagResult(param,
                            resultCallback(activity, callback, "updateRecordTagResult"));
                })
                .setNegativeButton(R.string.native_list_delete_cancel, null)
                .show();
    }

    /**
     * @param bizType 标签操作类型
     * @return 弹窗标题资源 ID
     */
    private static int tagDialogTitle(int bizType) {
        switch (bizType) {
            case TAG_BIZ_REMOVE: return R.string.native_action_remove_tag;
            case TAG_BIZ_REORDER: return R.string.native_action_reorder_tag;
            case TAG_BIZ_ADD:
            default: return R.string.native_action_add_tag;
        }
    }

    /**
     * 构造统一的结果回调：成功打日志并通知刷新，失败只打日志。
     *
     * @param action 动作名，用于日志区分
     */
    private static IResultCallback resultCallback(Activity activity, Callback callback,
                                                  String action) {
        return new IResultCallback() {
            @Override
            public void onSuccess() {
                activity.runOnUiThread(() -> {
                    callback.onLog(action + " onSuccess");
                    callback.onDataChanged();
                });
            }

            @Override
            public void onError(String code, String error) {
                activity.runOnUiThread(() ->
                        callback.onLog(action + " onError " + code + " " + error));
            }
        };
    }
}
