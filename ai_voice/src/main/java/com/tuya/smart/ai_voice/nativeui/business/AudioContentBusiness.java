package com.tuya.smart.ai_voice.nativeui.business;

import com.alibaba.fastjson.JSONObject;
import com.thingclips.smart.android.base.ApiParams;
import com.thingclips.smart.android.network.Business;

/**
 * 录音内容的云端写入业务类。
 * <p>
 * <b>这不是 TTT SDK 能力。</b> 录音元信息的<b>云端写入</b>需要业务层直接调 atop 云接口，
 * SDK 只负责本地库。
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

    /** 编辑总结正文。 */
    private static final String API_AUDIO_SUMMARY_EDIT = "m.wearable.audio.summary.edit";

    /** 编辑转写正文。 */
    private static final String API_AUDIO_CONTENT_EDIT = "m.wearable.audio.content.edit";

    private static final String API_VERSION = "1.0";

    /** 业务参数整体作为一个 JSON 字符串放在该字段里，而不是平铺成多个 post 字段。 */
    private static final String KEY_AUDIO_RECORD_REQUEST = "audioRecordRequest";

    private static final String FIELD_RECORD_ID = "recordId";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_OWNER_ID = "ownerId";
    private static final String FIELD_VISIT = "visit";

    /** 总结编辑接口用 {@code key} 承载业务录音 ID，不是 {@code recordId}。 */
    private static final String FIELD_KEY = "key";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_DEV_ID = "devId";

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
     * 把编辑后的总结正文写到云端。
     * <p>
     * SDK 的 {@code saveRecordTransferSummaryResult} <b>只写本地库</b>，人工纠错要跨端可见
     * 必须再调一次本接口。两处都写才算完整，只写其中一处就会两端不一致。
     * <p>
     * 三处容易踩：
     * <ul>
     *     <li>定位用的是<b>业务 {@code recordId}</b>，参数名却叫 {@code key}，
     *         不是 {@code recordTransferId}；</li>
     *     <li>{@code content} 必须与写本地时<b>同一份 JSON 字符串</b>，
     *         不能是界面上的展示文案，否则云端结构会被破坏；</li>
     *     <li>与 {@link #updateFileName} 不同，本接口字段是<b>平铺</b>的，
     *         不套 {@code audioRecordRequest} 那层 JSON。</li>
     * </ul>
     * 转写正文另有一个 {@code m.wearable.audio.content.edit}，入参多一个 {@code devId}。
     *
     * @param recordId 业务录音 ID
     * @param content  完整的总结 JSON 字符串，与写入本地的内容一致
     * @param listener 结果回调
     */
    public void editSummary(String recordId, String content, ResultListener<Boolean> listener) {
        ApiParams apiParams = new ApiParams(API_AUDIO_SUMMARY_EDIT, API_VERSION);
        apiParams.setSessionRequire(true);
        apiParams.putPostData(FIELD_KEY, recordId);
        apiParams.putPostData(FIELD_CONTENT, content);
        asyncRequest(apiParams, Boolean.class, listener);
    }

    /**
     * 把编辑后的转写正文写到云端。
     * <p>
     * 与 {@link #editSummary} 是同一套路，区别只有一个：<b>本接口多一个 {@code devId}</b>。
     * <p>
     * {@code content} 必须是完整的转写 JSON 数组字符串，且与写本地时是同一份——
     * 界面上展示的可读文案（带时间戳、说话人）<b>不能</b>直接拿来提交。
     *
     * @param devId    设备 ID。传录音自身的 {@code deviceId}；
     *                 （AI 笔记传的是当前选中设备，语义上不如前者贴切）
     * @param recordId 业务录音 ID
     * @param content  完整的转写 JSON 字符串，与写入本地的内容一致
     * @param listener 结果回调
     */
    public void editContent(String devId, String recordId, String content,
                            ResultListener<Boolean> listener) {
        ApiParams apiParams = new ApiParams(API_AUDIO_CONTENT_EDIT, API_VERSION);
        apiParams.setSessionRequire(true);
        apiParams.putPostData(FIELD_DEV_ID, devId);
        apiParams.putPostData(FIELD_KEY, recordId);
        apiParams.putPostData(FIELD_CONTENT, content);
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
