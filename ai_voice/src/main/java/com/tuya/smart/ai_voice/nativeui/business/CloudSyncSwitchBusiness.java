package com.tuya.smart.ai_voice.nativeui.business;

import com.thingclips.smart.android.base.ApiParams;
import com.thingclips.smart.android.network.Business;

/**
 * 云同步开关写入业务类。
 * <p>
 * <b>这不是 TTT SDK 能力。</b> 整体架构上，AI 笔记小程序自己实现一小部分功能（直调 atop 云接口），
 * 其余通过 wearkit 桥映射到 Native 方法——云同步开关的<b>写入</b>正属于前者，
 * 因此 {@code ThingAudioDetectManagerNative} <b>只有查询没有写入</b>。
 * Native 接入者要开关云同步，必须像本类这样自行调用 atop 接口。
 * <p>
 * 调用范式取自 {@code earphone-enhance} 组件的 {@code CloudSyncBusiness.kt}：
 * {@code Business} + {@code ApiParams} + {@code asyncRequest}，需要登录态故 {@code isSessionRequire = true}。
 * <p>
 * <b>接口是全量提交</b>：{@code enabled} 与 {@code syncType} 每次都必须一起传，
 * 只想改其中一个也要把另一个的当前值带上，否则会被覆盖成默认值。
 * <p>
 * ⚠️ 接口名在不同代码位置存在出入，本类采用小程序生产环境实际调用的那个：
 * <ul>
 *     <li>小程序 {@code api/common.ts}：{@code m.wearable.sync.switch.save}（本类采用）</li>
 *     <li>{@code CloudSyncSwitchData.kt} 注释：{@code m.wearable.cloud.sync.switch.save}</li>
 *     <li>{@code CloudSyncBusiness.kt} 常量名为 SAVE、值却是 {@code m.wearable.sync.switch.get}</li>
 * </ul>
 * 接入时请以自家云端网关的实际配置为准。
 */
public class CloudSyncSwitchBusiness extends Business {

    /** 保存云同步开关。 */
    private static final String API_SYNC_SWITCH_SAVE = "m.wearable.sync.switch.save";

    private static final String API_VERSION = "1.0";

    /** 同步类型：任意网络。对应 SDK 侧 {@code CloudSyncSwitchParam.syncType == 0}。 */
    public static final String SYNC_TYPE_ALL = "all";
    /** 同步类型：仅 Wi-Fi。对应 SDK 侧 {@code CloudSyncSwitchParam.syncType == 1}。 */
    public static final String SYNC_TYPE_WIFI = "wifi";

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_SYNC_TYPE = "syncType";

    /**
     * 保存云同步开关状态。
     * <p>
     * 注意 {@code syncType} 在 atop 层是字符串（{@code "all"} / {@code "wifi"}），
     * 而在 SDK 层是整型（{@code 0} / {@code 1}），两侧表示不同，转换见
     * {@code SyncType.fromString}。
     * <p>
     * 保存成功后 SDK 侧的开关状态<b>不会立刻更新</b>——它由 MQTT 事件或后台定时拉取驱动，
     * 因此界面应先做乐观更新，再等 {@code ICloudSwitchListener} 回调校正。
     *
     * @param enabled  是否开启云同步
     * @param syncType {@link #SYNC_TYPE_ALL} 或 {@link #SYNC_TYPE_WIFI}
     * @param listener 结果回调
     */
    public void saveCloudSyncSwitch(boolean enabled, String syncType,
                                    ResultListener<Boolean> listener) {
        ApiParams apiParams = new ApiParams(API_SYNC_SWITCH_SAVE, API_VERSION);
        apiParams.setSessionRequire(true);
        // 全量提交：两个字段缺一不可
        apiParams.putPostData(KEY_ENABLED, enabled);
        apiParams.putPostData(KEY_SYNC_TYPE, syncType);
        asyncRequest(apiParams, Boolean.class, listener);
    }
}
