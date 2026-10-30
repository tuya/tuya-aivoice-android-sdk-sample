package com.tuya.smart.ai_voice.nativeui.widget

import android.text.TextUtils
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import java.util.Locale

/**
 * 录音转写/总结结果纯文本解析工具。
 * 
 * 
 * SDK 返回的转写/总结结果是 JSON 字符串（`getRecordTransferRecognizeResult` /
 * `getRecordTransferSummaryResult` 的 `text` 字段）。本类将其解析为可读多行文本，
 * 供详情页 TextView 直接展示。解析失败一律降级为原样返回，保证内容不丢。
 * 
 * 
 * 字段结构：
 * 
 *  * 转写：JSON 数组，元素 `{transcript, translation, timeOffset, speaker}`。
 * `timeOffset` 形如 "1000"(毫秒) 或 "1s"(秒)。
 *  * 总结：JSON 对象 `{summary, outline, question, title?, imageUrl?}`，
 * 其中 `outline` / `question` 本身是 JSON 编码的字符串，需再解析一次。
 * 
 */
object TransferTextParser {
    /**
     * 解析转写结果 JSON 数组为可读文本。
     * 
     * 
     * 每段输出形如 `[mm:ss] transcript`，若带说话人则前缀 `说话人:`，
     * 若带译文则换行追加 `➜ translation`。解析失败原样返回。
     * 
     * @param text       SDK 返回的 text 字段（JSON 数组字符串）
     * @param durationMs 录音总时长（毫秒），用于最后一段无结束时间时的兜底（当前仅展示起始时间）
     * @return 可读多行文本
     */
    @JvmStatic
    fun parseTranscript(text: String, durationMs: Long): String {
        if (TextUtils.isEmpty(text)) return ""
        try {
            val arr = JSON.parseArray(text)
            if (arr == null || arr.isEmpty()) return ""
            // 整批 timeOffset 单位判定：含 's' 后缀按秒，否则按毫秒
            var secondUnit = false
            if (!arr.isEmpty()) {
                val firstOffset = if (arr.getJSONObject(0) == null)
                    null
                else
                    arr.getJSONObject(0).get("timeOffset")
                if (firstOffset != null && firstOffset.toString().contains("s")) {
                    secondUnit = true
                }
            }
            val sb = StringBuilder()
            for (i in arr.indices) {
                val seg = arr.getJSONObject(i)
                if (seg == null) continue
                val offset = seg.getString("timeOffset")
                val speaker = seg.getString("speaker")
                val transcript = seg.getString("transcript")
                val translation = seg.getString("translation")

                val stamp = formatStamp(offset, secondUnit)
                sb.append("[").append(stamp).append("] ")
                if (!TextUtils.isEmpty(speaker)) {
                    sb.append(speaker).append(": ")
                }
                if (!TextUtils.isEmpty(transcript)) {
                    sb.append(transcript)
                }
                sb.append("\n")
                if (!TextUtils.isEmpty(translation)) {
                    sb.append("  ➜ ").append(translation).append("\n")
                }
            }
            return sb.toString().trim { it <= ' ' }
        } catch (e: Exception) {
            return text
        }
    }

    /**
     * 解析总结结果 JSON 对象为可读文本。
     * 
     * 
     * 输出分「摘要」「大纲」「待办/问题」三段。`outline`/`question` 为 JSON 编码字符串，
     * 再次解析后逐项列出。解析失败原样返回。
     */
    /**
     * 取出总结 JSON 里的 `title`。
     * 
     * 
     * 这是 AI 为这条录音提炼的标题，业务上用来替换默认文件名。
     * 解析失败或字段缺失时返回 `null`，调用方据此跳过改名。
     * 
     * @param text 总结结果 JSON 字符串
     * @return 标题；无标题时为 `null`
     */
    @JvmStatic
    fun parseSummaryTitle(text: String?): String? {
        if (TextUtils.isEmpty(text)) return null
        try {
            val obj = JSON.parseObject(text)
            return if (obj == null) null else obj.getString("title")
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 把转写结果解析成原始 JSON 数组，供编辑保存时当模板用。
     * 
     * 
     * [.parseTranscript] 产出的是给人看的文案，**不能反解回结构**；
     * 要保存就必须另留一份原始数组，改完再整份提交。
     * 
     * @param text SDK 返回的转写 JSON 字符串
     * @return 原始数组；解析失败或无内容时返回 `null`
     */
    @JvmStatic
    fun parseTranscriptArray(text: String?): JSONArray? {
        if (TextUtils.isEmpty(text)) return null
        try {
            val arr = JSON.parseArray(text)
            return if (arr == null || arr.isEmpty()) null else arr
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 取某一段的 `transcript`。
     * 
     * @param list  原始转写数组
     * @param index 段落序号
     * @return 该段文案；越界或字段缺失时返回空串
     */
    @JvmStatic
    fun segmentTranscript(list: JSONArray?, index: Int): String {
        if (list == null || index < 0 || index >= list.size) return ""
        val seg = list.getJSONObject(index)
        if (seg == null) return ""
        val transcript = seg.getString("transcript")
        return if (transcript == null) "" else transcript
    }

    /**
     * 把某段的新文案写回数组并整份序列化。
     * 
     * 
     * **只替换这一段的 `transcript`**，`timeOffset` / `speaker` /
     * `translation` 以及其余段落全部原样保留——
     * `saveRecordTransferRecognizeResult` 是整份覆盖写入，
     * 提交残缺的数组会把时间轴与说话人信息一并抹掉。
     * 
     * @param list    原始转写数组，**会被就地修改**
     * @param index   段落序号
     * @param newText 编辑后的文案
     * @return 可直接写回 SDK 与云端的 JSON 字符串
     */
    @JvmStatic
    fun writeSegmentTranscript(list: JSONArray?, index: Int, newText: String?): String? {
        if (list == null || index < 0 || index >= list.size) return ""
        val seg = list.getJSONObject(index)
        if (seg != null) {
            seg.put("transcript", newText)
        }
        return list.toJSONString()
    }

    /**
     * 只取总结 JSON 里的 `summary` 正文，不含大纲与待办。
     * 
     * 
     * 供**可编辑**的总结正文使用：编辑后要能原样写回 `summary` 字段，
     * 所以展示的必须是纯正文，不能混入 [.parseSummary] 那种拼接文案。
     * 
     * @param text 总结结果 JSON 字符串
     * @return 总结正文；解析失败时原样返回入参
     */
    @JvmStatic
    fun parseSummaryBody(text: String): String {
        if (TextUtils.isEmpty(text)) return ""
        try {
            val obj = JSON.parseObject(text)
            if (obj == null) return text
            val summary = obj.getString("summary")
            return if (summary == null) "" else summary
        } catch (e: Exception) {
            return text
        }
    }

    /**
     * 把编辑后的正文写回总结 JSON 的 `summary` 字段，其余字段原样保留。
     * 
     * 
     * **不这么做会破坏数据**：`saveRecordTransferSummaryResult` 是整份覆盖写入，
     * 直接把界面上的文本存回去，云端下发的 JSON 结构（`outline` / `question` /
     * `title`）就全丢了，下次解析只能退化成纯文本。
     * 
     * @param rawJson 原始总结 JSON；为空或非 JSON 时直接返回 `newBody`
     * @param newBody 编辑后的正文
     * @return 可直接写回 SDK 的 JSON 字符串
     */
    @JvmStatic
    fun writeSummaryBody(rawJson: String?, newBody: String?): String? {
        if (TextUtils.isEmpty(rawJson)) return newBody
        try {
            val obj = JSON.parseObject(rawJson)
            if (obj == null) return newBody
            obj.put("summary", newBody)
            return obj.toJSONString()
        } catch (e: Exception) {
            return newBody
        }
    }

    /**
     * 把文件名写回总结 JSON 的 `title` 字段，其余字段原样保留。
     * 
     * 
     * **手动改名后必须调一次。** 总结里的 `title` 是文件名的另一个副本，
     * 加载总结时会用它回写文件名（见调用方的 `applySummaryTitle`）——
     * 只改文件名不改这里，下次加载总结就会把名字覆盖回旧值，
     * 表现为「刚改完名字又自己变回去了」。
     * 
     * @param rawJson  原始总结 JSON；为空时返回 `null`，表示无总结可写
     * @param newTitle 新文件名
     * @return 可写回 SDK 与云端的 JSON 字符串；无总结时为 `null`
     */
    @JvmStatic
    fun writeSummaryTitle(rawJson: String?, newTitle: String?): String? {
        if (TextUtils.isEmpty(rawJson)) return null
        try {
            val obj = JSON.parseObject(rawJson)
            if (obj == null) return null
            obj.put("title", newTitle)
            return obj.toJSONString()
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 取出总结 JSON 里的 `outline` 与 `question`，解一层编码后格式化输出。
     * 
     * 
     * 这两个字段是思维导图的数据源：`outline` 是层级大纲，真实产品据此渲染成图。
     * 它们在原始 JSON 里是**二次编码的字符串**，必须先解一层才能看到数组结构。
     * 
     * @param text 总结结果 JSON 字符串
     * @return 格式化后的 JSON 文本；无数据时返回空串
     */
    @JvmStatic
    fun parseSummaryOutlineJson(text: String?): String {
        if (TextUtils.isEmpty(text)) return ""
        try {
            val obj = JSON.parseObject(text)
            if (obj == null) return ""
            val result = JSONObject(true)
            val outline = decodeNestedJson(obj.getString("outline"))
            if (outline != null) {
                result.put("outline", outline)
            }
            val question = decodeNestedJson(obj.getString("question"))
            if (question != null) {
                result.put("question", question)
            }
            return if (result.isEmpty()) "" else JSON.toJSONString(result, true)
        } catch (e: Exception) {
            return ""
        }
    }

    /**
     * 把二次编码的 JSON 字符串解成对象，便于格式化输出。
     * 
     * @param jsonArrayStr JSON 编码的字符串
     * @return 解析后的数组；无内容或解析失败返回 `null`
     */
    private fun decodeNestedJson(jsonArrayStr: String?): Any? {
        if (TextUtils.isEmpty(jsonArrayStr)) return null
        try {
            val list = JSON.parseArray(jsonArrayStr)
            return if (list == null || list.isEmpty()) null else list
        } catch (e: Exception) {
            return null
        }
    }

    fun parseSummary(text: String): String {
        if (TextUtils.isEmpty(text)) return ""
        try {
            val obj = JSON.parseObject(text)
            if (obj == null) return text
            val sb = StringBuilder()

            val summary = obj.getString("summary")
            if (!TextUtils.isEmpty(summary)) {
                sb.append(summary)
            }

            val outlineJson = obj.getString("outline")
            appendListSection(sb, "大纲", outlineJson)

            val questionJson = obj.getString("question")
            appendListSection(sb, "待办/问题", questionJson)

            if (sb.length == 0) {
                return text
            }
            return sb.toString().trim { it <= ' ' }
        } catch (e: Exception) {
            return text
        }
    }

    private fun appendListSection(sb: StringBuilder, title: String?, jsonArrayStr: String?) {
        if (TextUtils.isEmpty(jsonArrayStr)) return
        try {
            val list = JSON.parseArray(jsonArrayStr)
            if (list == null || list.isEmpty()) return
            sb.append("\n\n").append("【").append(title).append("】").append("\n")
            for (i in list.indices) {
                var item = list.getString(i)
                if (TextUtils.isEmpty(item)) continue
                // 大纲/问题元素可能是字符串，也可能是 {content: "..."} 对象
                if (item?.startsWith("{") == true) {
                    try {
                        val o = JSON.parseObject(item)
                        if (o != null) {
                            val c = o.getString("content")
                            item = if (TextUtils.isEmpty(c)) o.toJSONString() else c
                        }
                    } catch (ignore: Exception) {
                    }
                }
                sb.append("• ").append(item).append("\n")
            }
        } catch (ignore: Exception) {
        }
    }

    private fun formatStamp(timeOffset: String?, secondUnit: Boolean): String {
        var sec: Long = 0
        try {
            if (!TextUtils.isEmpty(timeOffset)) {
                if (secondUnit) {
                    sec = (timeOffset ?: "").replace("s", "").trim { it <= ' ' }.toLong()
                } else {
                    sec = (timeOffset ?: "").trim { it <= ' ' }.toLong() / 1000L
                }
            }
        } catch (ignore: NumberFormatException) {
        }
        val m = sec / 60
        val s = sec % 60
        return String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }
}
