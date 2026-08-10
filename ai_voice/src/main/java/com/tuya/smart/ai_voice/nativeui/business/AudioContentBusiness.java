package com.tuya.smart.ai_voice.nativeui.business;

import com.alibaba.fastjson.JSONObject;
import com.thingclips.smart.android.base.ApiParams;
import com.thingclips.smart.android.network.Business;

/**
 * 录音内容的云端写入业务类。
 * <p>
 * <b>这不是 TTT SDK 能力。</b> 架构上小程序自己实现一小部分功能（直调 atop 云接口），
 * 其余通过 wearkit 桥映射到 Native；录音元信息的<b>云端写入</b>属于前者。
 * <p>
 * 与 SDK 的 {@code updateRecordTransferResult} 的分工：
 * <ul>
 *     <li>{@code updateRecordTransferResult} —— 改的是<b>本地库</b>，改完界面立刻能看到，
 *         但云端仍是旧值</li>
 *     <li>本类 —— 改的是<b>云端</b>，其他端与重装后能看到，但本地库不会自动跟着变</li>
 * </ul>
 * 因此「重命名」「已读状态」这类需要跨端一致的操作<b>两处都要写</b>：
 * <ul>
 *     <li>重命名 —— {@link #updateFileName}，用法见 {@code NativeRecordActionSheet#rename}</li>
 *     <li>已读状态 —— {@link #markRecordRead}，用法见
 *         {@code NativeRecordDetailActivity#markReadIfNeeded}</li>
 * </ul>
 * 两个接口的<b>入参形态并不一致</b>，见各自方法注释。
 */
public class AudioContentBusiness extends Business {

    /** 新增 / 编辑录音文件信息。 */
    private static final String API_AUDIO_RECORD_ADD = "m.wearable.audio.record.add";

    /** 标记录音已读。 */
    private static final String API_AUDIO_RECORD_READ_UPDATE = "m.wearable.audio.record.read.update";

    private static final String API_VERSION = "1.0";

    /** 业务参数整体作为一个 JSON 字符串放在该字段里，而不是平铺成多个 post 字段。 */
    private static final String KEY_AUDIO_RECORD_REQUEST = "audioRecordRequest";

    private static final String FIELD_RECORD_ID = "recordId";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_OWNER_ID = "ownerId";
    private static final String FIELD_VISIT = "visit";

    /**
     * 更新云端的录音文件名。
     * <p>
     * 注意入参形态：业务字段先拼成 JSON 字符串，再整体放进 {@code audioRecordRequest} 一个字段，
     * 不是把 {@code recordId} / {@code name} 平铺成多个 post 参数。
     *
     * @param recordId 业务录音 ID（String），不是 {@code recordTransferId}
     * @param name     新文件名
     * @param ownerId  当前家庭 ID
     * @param listener 结果回调
     */
    public void updateFileName(String recordId, String name, long ownerId,
                               ResultListener<Boolean> listener) {
        JSONObject request = new JSONObject();
        request.put(FIELD_RECORD_ID, recordId);
        request.put(FIELD_NAME, name);
        request.put(FIELD_OWNER_ID, ownerId);

        ApiParams apiParams = new ApiParams(API_AUDIO_RECORD_ADD, API_VERSION);
        apiParams.setSessionRequire(true);
        apiParams.putPostData(KEY_AUDIO_RECORD_REQUEST, request.toJSONString());
        asyncRequest(apiParams, Boolean.class, listener);
    }

    /**
     * 把录音标记为已读。
     * <p>
     * 与 {@link #updateFileName} 的入参形态不同——这个接口的字段是<b>平铺</b>的，
     * 不套 {@code audioRecordRequest} 那层 JSON 字符串。
     * <p>
     * 只用于「未读 → 已读」的单向跃迁，不能反向。业务上是 fire-and-forget：
     * 失败不影响本地已读状态，也不需要提示用户。
     *
     * @param recordId 业务录音 ID
     * @param visit    目标已读态：{@code 1} 已读 / {@code 3} 已转录已读
     * @param listener 结果回调
     */
    public void markRecordRead(String recordId, int visit, ResultListener<Boolean> listener) {
        ApiParams apiParams = new ApiParams(API_AUDIO_RECORD_READ_UPDATE, API_VERSION);
        apiParams.setSessionRequire(true);
        apiParams.putPostData(FIELD_RECORD_ID, recordId);
        apiParams.putPostData(FIELD_VISIT, visit);
        asyncRequest(apiParams, Boolean.class, listener);
    }
}
