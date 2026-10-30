package com.tuya.smart.ai_voice.nativeui.business

import com.thingclips.smart.android.base.ApiParams
import com.thingclips.smart.android.network.Business

/**
 * 云同步开关写入业务类。
 * 
 * 
 * **这不是 TTT SDK 能力。** 云同步开关的**写入**属于业务云，
 * `ThingAudioDetectManagerNative` **只有查询没有写入**，
 * 接入方要开关云同步必须像本类这样自行调用 atop 接口。
 * 
 * 
 * 调用范式：`Business` + `ApiParams` + `asyncRequest`，
 * 需要登录态故 `isSessionRequire = true`。
 * 
 * 
 * **接口是全量提交**：`enabled` 与 `syncType` 每次都必须一起传，
 * 只想改其中一个也要把另一个的当前值带上，否则会被覆盖成默认值。
 * 
 * 
 * ⚠️ 该接口名存在多个版本（`m.wearable.cloud.sync.switch.save` 等），
 * 本类采用生产环境实际在用的 `m.wearable.sync.switch.save`。
 * 接入时请以自家云端网关的实际配置为准。
 */
class CloudSyncSwitchBusiness : Business() {
    /**
     * 保存云同步开关状态。
     * 
     * 
     * 注意 `syncType` 在 atop 层是字符串（`"all"` / `"wifi"`），
     * 而在 SDK 层是整型（`0` / `1`），两侧表示不同，转换见
     * `SyncType.fromString`。
     * 
     * 
     * 保存成功后 SDK 侧的开关状态**不会立刻更新**——它由 MQTT 事件或后台定时拉取驱动，
     * 因此界面应先做乐观更新，再等 `ICloudSwitchListener` 回调校正。
     * 
     * @param enabled  是否开启云同步
     * @param syncType [.SYNC_TYPE_ALL] 或 [.SYNC_TYPE_WIFI]
     * @param listener 结果回调
     */
    fun saveCloudSyncSwitch(
        enabled: Boolean, syncType: String?,
        listener: ResultListener<Boolean?>?
    ) {
        val apiParams = ApiParams(API_SYNC_SWITCH_SAVE, API_VERSION)
        apiParams.setSessionRequire(true)
        // 全量提交：两个字段缺一不可
        apiParams.putPostData(KEY_ENABLED, enabled)
        apiParams.putPostData(KEY_SYNC_TYPE, syncType)
        asyncRequest<Boolean?>(apiParams, Boolean::class.java, listener)
    }

    companion object {
        /** 保存云同步开关。  */
        private const val API_SYNC_SWITCH_SAVE = "m.wearable.sync.switch.save"

        private const val API_VERSION = "1.0"

        /** 同步类型：任意网络。对应 SDK 侧 `CloudSyncSwitchParam.syncType == 0`。  */
        const val SYNC_TYPE_ALL: String = "all"

        /** 同步类型：仅 Wi-Fi。对应 SDK 侧 `CloudSyncSwitchParam.syncType == 1`。  */
        const val SYNC_TYPE_WIFI: String = "wifi"

        private const val KEY_ENABLED = "enabled"
        private const val KEY_SYNC_TYPE = "syncType"
    }
}
