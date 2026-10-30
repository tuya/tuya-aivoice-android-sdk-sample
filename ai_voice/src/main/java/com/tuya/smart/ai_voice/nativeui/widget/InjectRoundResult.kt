package com.tuya.smart.ai_voice.nativeui.widget

import com.tuya.smart.ai_voice.nativeui.widget.WerCalculator.LineResult

/**
 * 灌流跑批中一轮的结果。
 * 
 * 
 * 一轮 = 一个音频灌一遍：下发灌流配置 → 开始录音 → 等灌流播完 → 停止录音 →
 * 拉取转写结果 → 与参考答案算 WER。
 * 
 * 
 * 失败的轮次同样要保留在结果里——报告中显式标出「超时」「无识别结果」，
 * 比悄悄跳过更有诊断价值：连续多轮无结果通常意味着音频格式不对，而不是识别能力差。
 */
class InjectRoundResult {
    /** 一轮的终态。  */
    enum class Status {
        /** 正常拿到识别结果。  */
        SUCCESS,

        /** 超过单轮总超时仍未走完流程。  */
        TIMEOUT,

        /** 流程走完但一条识别结果都没有，最常见的原因是音频不是整型 PCM。  */
        NO_RESULT,

        /** SDK 接口报错。  */
        ERROR
    }

    /** 轮次序号，从 1 开始，跨文件连续。  */
    @JvmField
    var index: Int = 0

    /** 音频文件名。  */
    @JvmField
    var fileName: String = ""

    /** 同一音频的第几次重复，从 1 开始。  */
    @JvmField
    var repeatIndex: Int = 0

    /** 同一音频总共重复几次。  */
    @JvmField
    var repeatTotal: Int = 0

    @JvmField
    var sourceLanguage: String = ""

    /** 目标语言；不翻译时为空串。  */
    @JvmField
    var targetLanguage: String = ""
    @JvmField
    var needTts: Boolean = false

    @JvmField
    var status: Status = Status.ERROR

    /** 失败原因，成功时为空串。  */
    @JvmField
    var errorMessage: String = ""

    /** 本轮录音的 recordId，便于回到文件管理页查这条记录。  */
    @JvmField
    var recordId: String = ""

    /** 本轮耗时（下发配置到拿到结果）。  */
    @JvmField
    var durationMs: Long = 0

    /** 识别分段原文，用于逐句对比。  */
    @JvmField
    var asrSegments: MutableList<String?> = ArrayList<String?>()

    /** 译文分段原文，只记录不评分。  */
    @JvmField
    var translateSegments: MutableList<String?> = ArrayList<String?>()

    /** 参考答案原文；无参考答案时为 null，此时不计算 WER。  */
    @JvmField
    var referenceText: String? = null

    /** 全文 WER 结果；无参考答案时为 null。  */
    @JvmField
    var wer: WerCalculator.Result? = null

    /** 逐句结果；无参考答案时为空列表。  */
    @JvmField
    var lines: MutableList<LineResult?> = mutableListOf<LineResult?>()

    /** @return 本轮是否计算了 WER
     */
    fun hasWer(): Boolean {
        return (wer?.referenceCount ?: 0) > 0
    }

    /** @return 识别原文，分段以换行拼接
     */
    fun asrText(): String {
        return join(asrSegments)
    }

    /** @return 译文，分段以换行拼接
     */
    fun translateText(): String {
        return join(translateSegments)
    }

    companion object {
        private fun join(segments: MutableList<String?>): String {
            val sb = StringBuilder()
            for (segment in segments) {
                if (segment == null || segment.trim { it <= ' ' }.isEmpty()) {
                    continue
                }
                if (sb.length > 0) {
                    sb.append('\n')
                }
                sb.append(segment.trim { it <= ' ' })
            }
            return sb.toString()
        }
    }
}
