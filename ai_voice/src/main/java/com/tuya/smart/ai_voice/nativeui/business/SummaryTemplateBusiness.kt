package com.tuya.smart.ai_voice.nativeui.business

import com.thingclips.smart.android.base.ApiParams
import com.thingclips.smart.android.network.Business

/** 拉取推荐总结模板。 */
class SummaryTemplateBusiness : Business() {
    fun getTemplates(
        deviceId: String?,
        bizType: String,
        listener: ResultListener<String?>?
    ) {
        val params = ApiParams(API_SUMMARY_TEMPLATE_RECOMMEND, API_VERSION)
        params.isSessionRequire = true
        if (!deviceId.isNullOrEmpty() && deviceId != PHONE_DEVICE_ID) {
            params.putPostData(FIELD_DEV_ID, deviceId)
        }
        params.putPostData(FIELD_BIZ_TYPE, bizType)
        asyncRequest(params, String::class.java, listener)
    }

    companion object {
        private const val API_SUMMARY_TEMPLATE_RECOMMEND =
            "m.wearable.summary.template.recommend.list.get"
        private const val API_VERSION = "1.0"
        private const val PHONE_DEVICE_ID = "PHONE"
        private const val FIELD_DEV_ID = "devId"
        private const val FIELD_BIZ_TYPE = "bizType"
    }
}
