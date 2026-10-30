package com.tuya.smart.ai_voice.nativeui.business

import com.alibaba.fastjson.JSONObject
import com.thingclips.smart.android.base.ApiParams
import com.thingclips.smart.android.network.Business

/**
 * 录音内容的云端写入业务类。
 * 
 * 
 * **这不是 TTT SDK 能力。** 录音元信息的**云端写入**需要业务层直接调 atop 云接口，
 * SDK 只负责本地库。
 * 
 * 
 * 与 SDK 的 `updateRecordTransferResult` 的分工：
 * 
 *  * `updateRecordTransferResult` —— 改的是**本地库**，改完界面立刻能看到，
 * 但云端仍是旧值
 *  * 本类 —— 改的是**云端**，其他端与重装后能看到，但本地库不会自动跟着变
 * 
 * 因此「重命名」「已读状态」这类需要跨端一致的操作**两处都要写**：
 * 
 *  * 重命名 —— [.updateFileName]，用法见 `NativeRecordActionSheet#rename`
 *  * 已读状态 —— [.markRecordRead]，用法见
 * `NativeRecordDetailActivity#markReadIfNeeded`
 * 
 * 两个接口的**入参形态并不一致**，见各自方法注释。
 */
class AudioContentBusiness : Business() {
    /**
     * 更新云端的录音文件名。
     * 
     * 
     * 注意入参形态：业务字段先拼成 JSON 字符串，再整体放进 `audioRecordRequest` 一个字段，
     * 不是把 `recordId` / `name` 平铺成多个 post 参数。
     * 
     * @param recordId 业务录音 ID（String），不是 `recordTransferId`
     * @param name     新文件名
     * @param ownerId  当前家庭 ID
     * @param listener 结果回调
     */
    fun updateFileName(
        recordId: String?, name: String?, ownerId: Long,
        listener: ResultListener<Boolean?>?
    ) {
        val request = JSONObject()
        request.put(FIELD_RECORD_ID, recordId)
        request.put(FIELD_NAME, name)
        request.put(FIELD_OWNER_ID, ownerId)

        val apiParams = ApiParams(API_AUDIO_RECORD_ADD, API_VERSION)
        apiParams.setSessionRequire(true)
        apiParams.putPostData(KEY_AUDIO_RECORD_REQUEST, request.toJSONString())
        asyncRequest<Boolean?>(apiParams, Boolean::class.java, listener)
    }

    /**
     * 把编辑后的总结正文写到云端。
     * 
     * 
     * SDK 的 `saveRecordTransferSummaryResult` **只写本地库**，人工纠错要跨端可见
     * 必须再调一次本接口。两处都写才算完整，只写其中一处就会两端不一致。
     * 
     * 
     * 三处容易踩：
     * 
     *  * 定位用的是**业务 `recordId`**，参数名却叫 `key`，
     * 不是 `recordTransferId`；
     *  * `content` 必须与写本地时**同一份 JSON 字符串**，
     * 不能是界面上的展示文案，否则云端结构会被破坏；
     *  * 与 [.updateFileName] 不同，本接口字段是**平铺**的，
     * 不套 `audioRecordRequest` 那层 JSON。
     * 
     * 转写正文另有一个 `m.wearable.audio.content.edit`，入参多一个 `devId`。
     * 
     * @param recordId 业务录音 ID
     * @param content  完整的总结 JSON 字符串，与写入本地的内容一致
     * @param listener 结果回调
     */
    fun editSummary(recordId: String?, content: String?, listener: ResultListener<Boolean?>?) {
        val apiParams = ApiParams(API_AUDIO_SUMMARY_EDIT, API_VERSION)
        apiParams.setSessionRequire(true)
        apiParams.putPostData(FIELD_KEY, recordId)
        apiParams.putPostData(FIELD_CONTENT, content)
        asyncRequest<Boolean?>(apiParams, Boolean::class.java, listener)
    }

    /**
     * 把编辑后的转写正文写到云端。
     * 
     * 
     * 与 [.editSummary] 是同一套路，区别只有一个：**本接口多一个 `devId`**。
     * 
     * 
     * `content` 必须是完整的转写 JSON 数组字符串，且与写本地时是同一份——
     * 界面上展示的可读文案（带时间戳、说话人）**不能**直接拿来提交。
     * 
     * @param devId    设备 ID。传录音自身的 `deviceId`；
     * （AI 笔记传的是当前选中设备，语义上不如前者贴切）
     * @param recordId 业务录音 ID
     * @param content  完整的转写 JSON 字符串，与写入本地的内容一致
     * @param listener 结果回调
     */
    fun editContent(
        devId: String?, recordId: String?, content: String?,
        listener: ResultListener<Boolean?>?
    ) {
        val apiParams = ApiParams(API_AUDIO_CONTENT_EDIT, API_VERSION)
        apiParams.setSessionRequire(true)
        apiParams.putPostData(FIELD_DEV_ID, devId)
        apiParams.putPostData(FIELD_KEY, recordId)
        apiParams.putPostData(FIELD_CONTENT, content)
        asyncRequest<Boolean?>(apiParams, Boolean::class.java, listener)
    }

    /**
     * 把录音标记为已读。
     * 
     * 
     * 与 [.updateFileName] 的入参形态不同——这个接口的字段是**平铺**的，
     * 不套 `audioRecordRequest` 那层 JSON 字符串。
     * 
     * 
     * 只用于「未读 → 已读」的单向跃迁，不能反向。业务上是 fire-and-forget：
     * 失败不影响本地已读状态，也不需要提示用户。
     * 
     * @param recordId 业务录音 ID
     * @param visit    目标已读态：`1` 已读 / `3` 已转录已读
     * @param listener 结果回调
     */
    fun markRecordRead(recordId: String?, visit: Int, listener: ResultListener<Boolean?>?) {
        val apiParams = ApiParams(API_AUDIO_RECORD_READ_UPDATE, API_VERSION)
        apiParams.setSessionRequire(true)
        apiParams.putPostData(FIELD_RECORD_ID, recordId)
        apiParams.putPostData(FIELD_VISIT, visit)
        asyncRequest<Boolean?>(apiParams, Boolean::class.java, listener)
    }

    companion object {
        /** 新增 / 编辑录音文件信息。  */
        private const val API_AUDIO_RECORD_ADD = "m.wearable.audio.record.add"

        /** 标记录音已读。  */
        private const val API_AUDIO_RECORD_READ_UPDATE = "m.wearable.audio.record.read.update"

        /** 编辑总结正文。  */
        private const val API_AUDIO_SUMMARY_EDIT = "m.wearable.audio.summary.edit"

        /** 编辑转写正文。  */
        private const val API_AUDIO_CONTENT_EDIT = "m.wearable.audio.content.edit"

        private const val API_VERSION = "1.0"

        /** 业务参数整体作为一个 JSON 字符串放在该字段里，而不是平铺成多个 post 字段。  */
        private const val KEY_AUDIO_RECORD_REQUEST = "audioRecordRequest"

        private const val FIELD_RECORD_ID = "recordId"
        private const val FIELD_NAME = "name"
        private const val FIELD_OWNER_ID = "ownerId"
        private const val FIELD_VISIT = "visit"

        /** 总结编辑接口用 `key` 承载业务录音 ID，不是 `recordId`。  */
        private const val FIELD_KEY = "key"
        private const val FIELD_CONTENT = "content"
        private const val FIELD_DEV_ID = "devId"
    }
}
