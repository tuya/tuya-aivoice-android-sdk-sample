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
 * 字段结构：
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

    /**
     * 把转写结果解析成原始 JSON 数组，供编辑保存时当模板用。
     * <p>
     * {@link #parseTranscript} 产出的是给人看的文案，<b>不能反解回结构</b>；
     * 要保存就必须另留一份原始数组，改完再整份提交。
     *
     * @param text SDK 返回的转写 JSON 字符串
     * @return 原始数组；解析失败或无内容时返回 {@code null}
     */
    public static JSONArray parseTranscriptArray(String text) {
        if (TextUtils.isEmpty(text)) return null;
        try {
            JSONArray arr = JSON.parseArray(text);
            return arr == null || arr.isEmpty() ? null : arr;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 取某一段的 {@code transcript}。
     *
     * @param list  原始转写数组
     * @param index 段落序号
     * @return 该段文案；越界或字段缺失时返回空串
     */
    public static String segmentTranscript(JSONArray list, int index) {
        if (list == null || index < 0 || index >= list.size()) return "";
        JSONObject seg = list.getJSONObject(index);
        if (seg == null) return "";
        String transcript = seg.getString("transcript");
        return transcript == null ? "" : transcript;
    }

    /**
     * 把某段的新文案写回数组并整份序列化。
     * <p>
     * <b>只替换这一段的 {@code transcript}</b>，{@code timeOffset} / {@code speaker} /
     * {@code translation} 以及其余段落全部原样保留——
     * {@code saveRecordTransferRecognizeResult} 是整份覆盖写入，
     * 提交残缺的数组会把时间轴与说话人信息一并抹掉。
     *
     * @param list    原始转写数组，<b>会被就地修改</b>
     * @param index   段落序号
     * @param newText 编辑后的文案
     * @return 可直接写回 SDK 与云端的 JSON 字符串
     */
    public static String writeSegmentTranscript(JSONArray list, int index, String newText) {
        if (list == null || index < 0 || index >= list.size()) return "";
        JSONObject seg = list.getJSONObject(index);
        if (seg != null) {
            seg.put("transcript", newText);
        }
        return list.toJSONString();
    }

    /**
     * 只取总结 JSON 里的 {@code summary} 正文，不含大纲与待办。
     * <p>
     * 供<b>可编辑</b>的总结正文使用：编辑后要能原样写回 {@code summary} 字段，
     * 所以展示的必须是纯正文，不能混入 {@link #parseSummary} 那种拼接文案。
     *
     * @param text 总结结果 JSON 字符串
     * @return 总结正文；解析失败时原样返回入参
     */
    public static String parseSummaryBody(String text) {
        if (TextUtils.isEmpty(text)) return "";
        try {
            JSONObject obj = JSON.parseObject(text);
            if (obj == null) return text;
            String summary = obj.getString("summary");
            return summary == null ? "" : summary;
        } catch (Exception e) {
            return text;
        }
    }

    /**
     * 把编辑后的正文写回总结 JSON 的 {@code summary} 字段，其余字段原样保留。
     * <p>
     * <b>不这么做会破坏数据</b>：{@code saveRecordTransferSummaryResult} 是整份覆盖写入，
     * 直接把界面上的文本存回去，云端下发的 JSON 结构（{@code outline} / {@code question} /
     * {@code title}）就全丢了，下次解析只能退化成纯文本。
     *
     * @param rawJson 原始总结 JSON；为空或非 JSON 时直接返回 {@code newBody}
     * @param newBody 编辑后的正文
     * @return 可直接写回 SDK 的 JSON 字符串
     */
    public static String writeSummaryBody(String rawJson, String newBody) {
        if (TextUtils.isEmpty(rawJson)) return newBody;
        try {
            JSONObject obj = JSON.parseObject(rawJson);
            if (obj == null) return newBody;
            obj.put("summary", newBody);
            return obj.toJSONString();
        } catch (Exception e) {
            return newBody;
        }
    }

    /**
     * 把文件名写回总结 JSON 的 {@code title} 字段，其余字段原样保留。
     * <p>
     * <b>手动改名后必须调一次。</b> 总结里的 {@code title} 是文件名的另一个副本，
     * 加载总结时会用它回写文件名（见调用方的 {@code applySummaryTitle}）——
     * 只改文件名不改这里，下次加载总结就会把名字覆盖回旧值，
     * 表现为「刚改完名字又自己变回去了」。
     *
     * @param rawJson  原始总结 JSON；为空时返回 {@code null}，表示无总结可写
     * @param newTitle 新文件名
     * @return 可写回 SDK 与云端的 JSON 字符串；无总结时为 {@code null}
     */
    public static String writeSummaryTitle(String rawJson, String newTitle) {
        if (TextUtils.isEmpty(rawJson)) return null;
        try {
            JSONObject obj = JSON.parseObject(rawJson);
            if (obj == null) return null;
            obj.put("title", newTitle);
            return obj.toJSONString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 取出总结 JSON 里的 {@code outline} 与 {@code question}，解一层编码后格式化输出。
     * <p>
     * 这两个字段是思维导图的数据源：{@code outline} 是层级大纲，真实产品据此渲染成图。
     * 它们在原始 JSON 里是<b>二次编码的字符串</b>，必须先解一层才能看到数组结构。
     *
     * @param text 总结结果 JSON 字符串
     * @return 格式化后的 JSON 文本；无数据时返回空串
     */
    public static String parseSummaryOutlineJson(String text) {
        if (TextUtils.isEmpty(text)) return "";
        try {
            JSONObject obj = JSON.parseObject(text);
            if (obj == null) return "";
            JSONObject result = new JSONObject(true);
            Object outline = decodeNestedJson(obj.getString("outline"));
            if (outline != null) {
                result.put("outline", outline);
            }
            Object question = decodeNestedJson(obj.getString("question"));
            if (question != null) {
                result.put("question", question);
            }
            return result.isEmpty() ? "" : JSON.toJSONString(result, true);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 把二次编码的 JSON 字符串解成对象，便于格式化输出。
     *
     * @param jsonArrayStr JSON 编码的字符串
     * @return 解析后的数组；无内容或解析失败返回 {@code null}
     */
    private static Object decodeNestedJson(String jsonArrayStr) {
        if (TextUtils.isEmpty(jsonArrayStr)) return null;
        try {
            JSONArray list = JSON.parseArray(jsonArrayStr);
            return list == null || list.isEmpty() ? null : list;
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
