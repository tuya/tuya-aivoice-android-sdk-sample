package com.tuya.smart.ai_voice.nativeui.widget;

import android.text.TextUtils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.Locale;

/**
 * 录音转写/总结结果纯文本解析工具。
 * <p>
 * SDK 返回的转写/总结结果是 JSON 字符串（{@code getRecordTransferRecognizeResult} /
 * {@code getRecordTransferSummaryResult} 的 {@code text} 字段）。本类将其解析为可读多行文本，
 * 供详情页 TextView 直接展示。解析失败一律降级为原样返回，保证内容不丢。
 * <p>
 * 字段结构参考小程序 {@code useTransferResult} / {@code useSummaryResult} 的解析逻辑：
 * <ul>
 *     <li>转写：JSON 数组，元素 {@code {transcript, translation, timeOffset, speaker}}。
 *         {@code timeOffset} 形如 "1000"(毫秒) 或 "1s"(秒)。</li>
 *     <li>总结：JSON 对象 {@code {summary, outline, question, title?, imageUrl?}}，
 *         其中 {@code outline} / {@code question} 本身是 JSON 编码的字符串，需再解析一次。</li>
 * </ul>
 */
public final class TransferTextParser {

    private TransferTextParser() {
    }

    /**
     * 解析转写结果 JSON 数组为可读文本。
     * <p>
     * 每段输出形如 {@code [mm:ss] transcript}，若带说话人则前缀 {@code 说话人:}，
     * 若带译文则换行追加 {@code   ➜ translation}。解析失败原样返回。
     *
     * @param text       SDK 返回的 text 字段（JSON 数组字符串）
     * @param durationMs 录音总时长（毫秒），用于最后一段无结束时间时的兜底（当前仅展示起始时间）
     * @return 可读多行文本
     */
    public static String parseTranscript(String text, long durationMs) {
        if (TextUtils.isEmpty(text)) return "";
        try {
            JSONArray arr = JSON.parseArray(text);
            if (arr == null || arr.isEmpty()) return "";
            // 整批 timeOffset 单位判定：含 's' 后缀按秒，否则按毫秒
            boolean secondUnit = false;
            if (!arr.isEmpty()) {
                Object firstOffset = arr.getJSONObject(0) == null
                        ? null : arr.getJSONObject(0).get("timeOffset");
                if (firstOffset != null && firstOffset.toString().contains("s")) {
                    secondUnit = true;
                }
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.size(); i++) {
                JSONObject seg = arr.getJSONObject(i);
                if (seg == null) continue;
                String offset = seg.getString("timeOffset");
                String speaker = seg.getString("speaker");
                String transcript = seg.getString("transcript");
                String translation = seg.getString("translation");

                String stamp = formatStamp(offset, secondUnit);
                sb.append("[").append(stamp).append("] ");
                if (!TextUtils.isEmpty(speaker)) {
                    sb.append(speaker).append(": ");
                }
                if (!TextUtils.isEmpty(transcript)) {
                    sb.append(transcript);
                }
                sb.append("\n");
                if (!TextUtils.isEmpty(translation)) {
                    sb.append("  ➜ ").append(translation).append("\n");
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return text;
        }
    }

    /**
     * 解析总结结果 JSON 对象为可读文本。
     * <p>
     * 输出分「摘要」「大纲」「待办/问题」三段。{@code outline}/{@code question} 为 JSON 编码字符串，
     * 再次解析后逐项列出。解析失败原样返回。
     */
    /**
     * 取出总结 JSON 里的 {@code title}。
     * <p>
     * 这是 AI 为这条录音提炼的标题，业务上用来替换默认文件名。
     * 解析失败或字段缺失时返回 {@code null}，调用方据此跳过改名。
     *
     * @param text 总结结果 JSON 字符串
     * @return 标题；无标题时为 {@code null}
     */
    public static String parseSummaryTitle(String text) {
        if (TextUtils.isEmpty(text)) return null;
        try {
            JSONObject obj = JSON.parseObject(text);
            return obj == null ? null : obj.getString("title");
        } catch (Exception e) {
            return null;
        }
    }

    public static String parseSummary(String text) {
        if (TextUtils.isEmpty(text)) return "";
        try {
            JSONObject obj = JSON.parseObject(text);
            if (obj == null) return text;
            StringBuilder sb = new StringBuilder();

            String summary = obj.getString("summary");
            if (!TextUtils.isEmpty(summary)) {
                sb.append(summary);
            }

            String outlineJson = obj.getString("outline");
            appendListSection(sb, "大纲", outlineJson);

            String questionJson = obj.getString("question");
            appendListSection(sb, "待办/问题", questionJson);

            if (sb.length() == 0) {
                return text;
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return text;
        }
    }

    private static void appendListSection(StringBuilder sb, String title, String jsonArrayStr) {
        if (TextUtils.isEmpty(jsonArrayStr)) return;
        try {
            JSONArray list = JSON.parseArray(jsonArrayStr);
            if (list == null || list.isEmpty()) return;
            sb.append("\n\n").append("【").append(title).append("】").append("\n");
            for (int i = 0; i < list.size(); i++) {
                String item = list.getString(i);
                if (TextUtils.isEmpty(item)) continue;
                // 大纲/问题元素可能是字符串，也可能是 {content: "..."} 对象
                if (item.startsWith("{")) {
                    try {
                        JSONObject o = JSON.parseObject(item);
                        if (o != null) {
                            String c = o.getString("content");
                            item = TextUtils.isEmpty(c) ? o.toJSONString() : c;
                        }
                    } catch (Exception ignore) {
                    }
                }
                sb.append("• ").append(item).append("\n");
            }
        } catch (Exception ignore) {
        }
    }

    private static String formatStamp(String timeOffset, boolean secondUnit) {
        long sec = 0;
        try {
            if (!TextUtils.isEmpty(timeOffset)) {
                if (secondUnit) {
                    sec = Long.parseLong(timeOffset.replace("s", "").trim());
                } else {
                    sec = Long.parseLong(timeOffset.trim()) / 1000L;
                }
            }
        } catch (NumberFormatException ignore) {
        }
        long m = sec / 60;
        long s = sec % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", m, s);
    }
}
