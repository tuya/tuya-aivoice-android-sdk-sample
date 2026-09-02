package com.tuya.smart.ai_voice.nativeui.widget;

import android.content.Context;
import android.os.Environment;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 跑批报告生成：单文件 HTML + 两份 CSV。
 * <p>
 * 产物落在 {@code getExternalFilesDir(DIRECTORY_DOCUMENTS)/inject_report/}——
 * 这个位置已在 {@code @xml/native_file_paths} 的 FileProvider 白名单里，
 * 分享时不用改 manifest，也不需要任何存储权限。
 *
 * <h3>三份产物的分工</h3>
 * <ul>
 *     <li><b>HTML</b>：本次跑批的完整报告，含逐句与逐词对比，用于定位「错在哪」</li>
 *     <li><b>本次 CSV</b>：本次每轮一行，用于把这批结果丢进表格</li>
 *     <li><b>累积 CSV</b>：固定文件名追加，跨跑批累积，用于看同一音频的 WER 走势。
 *         它同时充当了持久化——本模块没有数据库，历史数据就靠这个文件</li>
 * </ul>
 *
 * <h3>WER 口径</h3>
 * 见 {@link WerCalculator}，与 iOS 端一致。报告里「计算方式」章节会把规则原样写出来，
 * 让看报告的人不必翻代码就能判断某个差异是不是归一化造成的。
 */
public final class InjectReportBuilder {

    /** 报告目录名，挂在应用私有 Documents 下。 */
    private static final String REPORT_DIR = "inject_report";

    /** 累积 CSV 的固定文件名。 */
    private static final String CUMULATIVE_CSV = "inject_history.csv";

    /** 逐句对比的告警阈值：识别段数少于参考行数时，漏识判定会虚高。 */
    private static final String CSV_HEADER =
            "batchId,time,file,repeat,repeatTotal,sourceLang,targetLang,tts,status,"
                    + "N,S,D,I,wer,accuracy,durationMs,recordId\n";

    private InjectReportBuilder() {
    }

    /**
     * 报告目录，不存在时创建。
     *
     * @param context 上下文
     * @return 目录；外置存储不可用时返回 {@code null}
     */
    @Nullable
    public static File reportDir(@NonNull Context context) {
        File base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (base == null) {
            return null;
        }
        File dir = new File(base, REPORT_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        return dir;
    }

    /**
     * 写出本次跑批的 HTML 报告。阻塞 IO，<b>必须在子线程调用</b>。
     *
     * @param context 上下文
     * @param batchId 批次 ID
     * @param results 全部轮次结果
     * @return 生成的文件；失败返回 {@code null}
     */
    @Nullable
    public static File writeHtml(@NonNull Context context, @NonNull String batchId,
                                 @NonNull List<InjectRoundResult> results) {
        File dir = reportDir(context);
        if (dir == null) {
            return null;
        }
        File file = new File(dir, "inject_report_" + batchId + ".html");
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
            writeHtmlBody(writer, batchId, results);
            writer.flush();
            return file;
        } catch (IOException e) {
            return null;
        } finally {
            closeQuietly(writer);
        }
    }

    /**
     * 写出本次跑批的 CSV，并把同样的行追加进累积 CSV。
     * 阻塞 IO，<b>必须在子线程调用</b>。
     *
     * @param context 上下文
     * @param batchId 批次 ID
     * @param results 全部轮次结果
     * @return 本次 CSV 文件；失败返回 {@code null}
     */
    @Nullable
    public static File writeCsv(@NonNull Context context, @NonNull String batchId,
                                @NonNull List<InjectRoundResult> results) {
        File dir = reportDir(context);
        if (dir == null) {
            return null;
        }
        String time = timestamp();
        StringBuilder rows = new StringBuilder();
        for (InjectRoundResult r : results) {
            rows.append(csvRow(batchId, time, r));
        }

        File file = new File(dir, "inject_" + batchId + ".csv");
        if (!write(file, CSV_HEADER + rows, false)) {
            return null;
        }
        // 累积 CSV：文件不存在时先补表头，之后一律追加
        File cumulative = new File(dir, CUMULATIVE_CSV);
        boolean exists = cumulative.isFile() && cumulative.length() > 0;
        write(cumulative, exists ? rows.toString() : CSV_HEADER + rows, true);
        return file;
    }

    /**
     * 累积 CSV 的路径，供界面展示与分享。
     *
     * @param context 上下文
     * @return 文件；目录不可用时返回 {@code null}
     */
    @Nullable
    public static File cumulativeCsv(@NonNull Context context) {
        File dir = reportDir(context);
        return dir == null ? null : new File(dir, CUMULATIVE_CSV);
    }

    // ===================== CSV =====================

    @NonNull
    private static String csvRow(@NonNull String batchId, @NonNull String time,
                                 @NonNull InjectRoundResult r) {
        WerCalculator.Result wer = r.wer;
        boolean scored = r.hasWer();
        return csv(batchId) + ',' + csv(time) + ',' + csv(r.fileName) + ','
                + r.repeatIndex + ',' + r.repeatTotal + ','
                + csv(r.sourceLanguage) + ',' + csv(r.targetLanguage) + ','
                + (r.needTts ? "1" : "0") + ',' + r.status.name() + ','
                + (scored ? String.valueOf(wer.referenceCount) : "") + ','
                + (scored ? String.valueOf(wer.substitutions) : "") + ','
                + (scored ? String.valueOf(wer.deletions) : "") + ','
                + (scored ? String.valueOf(wer.insertions) : "") + ','
                + (scored ? ratio(wer.wer()) : "") + ','
                + (scored ? ratio(wer.accuracy()) : "") + ','
                + r.durationMs + ',' + csv(r.recordId) + '\n';
    }

    /**
     * CSV 字段转义。
     * <p>
     * 文件名和错误信息里可能带逗号、引号或换行，不转义会把整个表格错位。
     */
    @NonNull
    private static String csv(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped.replace("\r", " ").replace("\n", " ") + "\"";
    }

    @NonNull
    private static String ratio(double value) {
        return String.format(Locale.US, "%.6f", value);
    }

    // ===================== HTML =====================

    private static void writeHtmlBody(@NonNull Writer w, @NonNull String batchId,
                                      @NonNull List<InjectRoundResult> results) throws IOException {
        int total = results.size();
        int success = 0;
        int scored = 0;
        long totalDuration = 0;
        int sumN = 0;
        int sumS = 0;
        int sumD = 0;
        int sumI = 0;
        for (InjectRoundResult r : results) {
            if (r.status == InjectRoundResult.Status.SUCCESS) {
                success++;
            }
            totalDuration += r.durationMs;
            if (r.hasWer()) {
                scored++;
                sumN += r.wer.referenceCount;
                sumS += r.wer.substitutions;
                sumD += r.wer.deletions;
                sumI += r.wer.insertions;
            }
        }
        // 汇总 WER 用「词数加权」而不是各轮 WER 求平均：长音频的权重本就该更高
        double overallWer = sumN == 0 ? 0 : (double) (sumS + sumD + sumI) / (double) sumN;
        double overallAccuracy = Math.max(0, 1 - overallWer);

        w.write("<!doctype html><html lang=\"zh\"><head><meta charset=\"utf-8\">");
        w.write("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        w.write("<title>灌流批量测试报告 " + esc(batchId) + "</title>");
        w.write("<style>" + css() + "</style></head><body>");

        w.write("<h1>灌流批量测试报告</h1>");
        w.write("<p class=\"sub\">批次 " + esc(batchId) + " · " + esc(timestamp()) + "</p>");

        w.write("<div class=\"kpis\">");
        w.write(kpi("总体准确率", percent(overallAccuracy)));
        w.write(kpi("总体 WER", percent(overallWer)));
        w.write(kpi("轮次", success + " / " + total));
        w.write(kpi("参与评分轮次", String.valueOf(scored)));
        w.write(kpi("参考词数", String.valueOf(sumN)));
        w.write(kpi("错误合计", String.valueOf(sumS + sumD + sumI)));
        w.write(kpi("总耗时", duration(totalDuration)));
        w.write("</div>");

        if (sumN > 0) {
            w.write("<div class=\"bar\">");
            w.write(barSeg("s", sumS, sumS + sumD + sumI, "替换 " + sumS));
            w.write(barSeg("d", sumD, sumS + sumD + sumI, "删除 " + sumD));
            w.write(barSeg("i", sumI, sumS + sumD + sumI, "插入 " + sumI));
            w.write("</div>");
        }

        // 概览表：一眼看出哪几轮掉了
        w.write("<h2>轮次概览</h2><table><thead><tr>"
                + "<th>#</th><th>音频</th><th>重复</th><th>语言</th><th>状态</th>"
                + "<th>N</th><th>S</th><th>D</th><th>I</th><th>WER</th><th>耗时</th>"
                + "</tr></thead><tbody>");
        for (InjectRoundResult r : results) {
            w.write("<tr class=\"" + (r.status == InjectRoundResult.Status.SUCCESS ? "" : "bad") + "\">");
            w.write(td(String.valueOf(r.index)));
            w.write(td(r.fileName));
            w.write(td(r.repeatIndex + "/" + r.repeatTotal));
            w.write(td(langLabel(r)));
            w.write(td(statusLabel(r)));
            if (r.hasWer()) {
                w.write(td(String.valueOf(r.wer.referenceCount)));
                w.write(td(String.valueOf(r.wer.substitutions)));
                w.write(td(String.valueOf(r.wer.deletions)));
                w.write(td(String.valueOf(r.wer.insertions)));
                w.write(td(percent(r.wer.wer())));
            } else {
                w.write(td("—") + td("—") + td("—") + td("—") + td("—"));
            }
            w.write(td(duration(r.durationMs)));
            w.write("</tr>");
        }
        w.write("</tbody></table>");

        for (InjectRoundResult r : results) {
            writeRoundSection(w, r);
        }

        writeMethodSection(w);
        w.write("</body></html>");
    }

    private static void writeRoundSection(@NonNull Writer w, @NonNull InjectRoundResult r)
            throws IOException {
        w.write("<h2>#" + r.index + " " + esc(r.fileName)
                + " <span class=\"tag\">" + esc(r.repeatIndex + "/" + r.repeatTotal) + "</span></h2>");

        w.write("<table class=\"cond\"><tbody>");
        w.write(condRow("状态", statusLabel(r)));
        w.write(condRow("源语言", r.sourceLanguage));
        w.write(condRow("目标语言", r.targetLanguage.isEmpty() ? "不翻译" : r.targetLanguage));
        w.write(condRow("TTS", r.needTts ? "开" : "关"));
        w.write(condRow("耗时", duration(r.durationMs)));
        w.write(condRow("recordId", r.recordId));
        if (!r.errorMessage.isEmpty()) {
            w.write(condRow("失败原因", r.errorMessage));
        }
        w.write("</tbody></table>");

        if (!r.hasWer()) {
            w.write("<p class=\"note\">"
                    + (r.referenceText == null
                    ? "没有找到与音频同名的参考答案 txt，本轮不计算 WER。"
                    : "本轮没有取到识别结果，不计算 WER。")
                    + "</p>");
        } else {
            w.write("<div class=\"kpis small\">");
            w.write(kpi("准确率", percent(r.wer.accuracy())));
            w.write(kpi("WER", percent(r.wer.wer())));
            w.write(kpi("N / S / D / I", r.wer.referenceCount + " / " + r.wer.substitutions
                    + " / " + r.wer.deletions + " / " + r.wer.insertions));
            w.write(kpi("命中词数", String.valueOf(r.wer.hits())));
            w.write("</div>");

            if (!r.lines.isEmpty()) {
                int segments = r.asrSegments.size();
                if (segments > 0 && segments < r.lines.size()) {
                    w.write("<p class=\"warn\">识别分段数（" + segments + "）少于参考行数（"
                            + r.lines.size() + "）。一个识别分段不会被拆给多个参考行，"
                            + "必然有行分不到内容而被判漏识，<b>逐句结果偏低属于对齐口径造成的虚高，"
                            + "请以上方全文 WER 为准</b>。</p>");
                }
                w.write("<h3>逐句对比</h3><table><thead><tr><th>#</th><th>参考</th><th>识别</th>"
                        + "<th>N</th><th>S</th><th>D</th><th>I</th><th>WER</th></tr></thead><tbody>");
                for (WerCalculator.LineResult line : r.lines) {
                    w.write("<tr class=\"" + (line.missed ? "bad" : "") + "\">");
                    w.write(td(String.valueOf(line.lineNumber)));
                    w.write(td(line.reference));
                    w.write(td(line.hypothesis.isEmpty() ? "（未识别）" : line.hypothesis));
                    w.write(td(String.valueOf(line.result.referenceCount)));
                    w.write(td(String.valueOf(line.result.substitutions)));
                    w.write(td(String.valueOf(line.result.deletions)));
                    w.write(td(String.valueOf(line.result.insertions)));
                    w.write(td(percent(line.result.wer())));
                    w.write("</tr>");
                }
                w.write("</tbody></table>");
            }

            w.write("<h3>全文逐词对比</h3><p class=\"diff\">");
            for (WerCalculator.Token token : r.wer.alignment) {
                switch (token.operation) {
                    case EQUAL:
                        w.write("<span>" + esc(token.hypothesisToken) + "</span> ");
                        break;
                    case SUBSTITUTE:
                        w.write("<span class=\"s\" title=\"应为 " + esc(token.referenceToken) + "\">"
                                + esc(token.hypothesisToken) + "</span> ");
                        break;
                    case INSERT:
                        w.write("<span class=\"i\">" + esc(token.hypothesisToken) + "</span> ");
                        break;
                    case DELETE:
                    default:
                        w.write("<span class=\"d\">" + esc(token.referenceToken) + "</span> ");
                        break;
                }
            }
            w.write("</p><p class=\"legend\"><span class=\"s\">替换</span>"
                    + "<span class=\"i\">插入</span><span class=\"d\">删除（漏识）</span></p>");

            w.write("<h3>归一化后文本（实际参与计算的内容）</h3>");
            w.write("<p class=\"mono\"><b>参考</b><br>" + esc(r.wer.normalizedReference) + "</p>");
            w.write("<p class=\"mono\"><b>识别</b><br>" + esc(r.wer.normalizedHypothesis) + "</p>");
        }

        if (r.referenceText != null) {
            w.write("<h3>参考答案原文</h3><pre>" + esc(r.referenceText) + "</pre>");
        }
        String asr = r.asrText();
        if (!asr.isEmpty()) {
            w.write("<h3>识别原文</h3><pre>" + esc(asr) + "</pre>");
        }
        String translate = r.translateText();
        if (!translate.isEmpty()) {
            w.write("<h3>译文（仅记录，不评分）</h3><pre>" + esc(translate) + "</pre>");
        }
    }

    private static void writeMethodSection(@NonNull Writer w) throws IOException {
        w.write("<h2>计算方式</h2>");
        w.write("<p><b>WER = (S + D + I) / N，准确率 = 1 − WER（下限截到 0）。</b>"
                + "S 替换、D 删除（参考有但没识别出来）、I 插入（识别多出来的）、N 参考词数。"
                + "总体 WER 按词数加权汇总，不是各轮 WER 的算术平均。</p>");
        w.write("<p>归一化（参考与识别套用同一套规则）：</p><ol>"
                + "<li>统一转小写、去首尾空白</li>"
                + "<li>剔除语气词 啊额呃嗯唉哎哦喔呵哈儿</li>"
                + "<li>中文逐字切分，英文等按词切分</li>"
                + "<li>标点<b>替换为空格</b>而非删除，避免把相邻两词粘成一个</li>"
                + "<li>千分位还原：40 000 → 40000</li>"
                + "<li>数词归一化：two / zwei → 2</li></ol>");
        w.write("<p>对齐：Levenshtein 动态规划 + 回溯，代价相同时优先判替换、其次删除。"
                + "逐句对比把识别分段单调切分后分配给参考行，使各行编辑距离之和最小；"
                + "一个识别分段不会被拆给多个参考行，因此 ASR 合并输出时逐句结果会虚高，"
                + "全文 WER 不受影响。</p>");
        w.write("<p class=\"note\">本口径与 iOS 端 PerfusionWERCalculator 一致。</p>");
    }

    // ===================== 片段 =====================

    @NonNull
    private static String kpi(@NonNull String label, @NonNull String value) {
        return "<div class=\"kpi\"><div class=\"v\">" + esc(value) + "</div><div class=\"l\">"
                + esc(label) + "</div></div>";
    }

    @NonNull
    private static String barSeg(@NonNull String cls, int value, int total, @NonNull String label) {
        if (total <= 0 || value <= 0) {
            return "";
        }
        int width = Math.max(1, value * 100 / total);
        return "<div class=\"seg " + cls + "\" style=\"width:" + width + "%\">" + esc(label) + "</div>";
    }

    @NonNull
    private static String td(@Nullable String value) {
        return "<td>" + esc(value) + "</td>";
    }

    @NonNull
    private static String condRow(@NonNull String key, @Nullable String value) {
        return "<tr><th>" + esc(key) + "</th><td>" + esc(value) + "</td></tr>";
    }

    @NonNull
    private static String langLabel(@NonNull InjectRoundResult r) {
        return r.targetLanguage.isEmpty()
                ? r.sourceLanguage
                : r.sourceLanguage + " → " + r.targetLanguage;
    }

    @NonNull
    private static String statusLabel(@NonNull InjectRoundResult r) {
        switch (r.status) {
            case SUCCESS:
                return "成功";
            case TIMEOUT:
                return "超时";
            case NO_RESULT:
                return "无识别结果";
            case ERROR:
            default:
                return r.errorMessage.isEmpty() ? "失败" : "失败：" + r.errorMessage;
        }
    }

    @NonNull
    private static String percent(double value) {
        return String.format(Locale.US, "%.2f%%", value * 100);
    }

    @NonNull
    private static String duration(long ms) {
        if (ms < 1000) {
            return ms + "ms";
        }
        long seconds = ms / 1000;
        return seconds < 60
                ? seconds + "s"
                : String.format(Locale.US, "%dm%02ds", seconds / 60, seconds % 60);
    }

    @NonNull
    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    /**
     * HTML 转义。识别结果是外部数据，直接拼进 HTML 会破坏结构。
     */
    @NonNull
    private static String esc(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    @NonNull
    private static String css() {
        return ":root{color-scheme:light dark;--bg:#fff;--fg:#1e1e1e;--sub:#888;--line:#e6e6e6;"
                + "--card:#f7f8fa;--s:#e45b5b;--i:#e0a92b;--d:#3ec176}"
                + "@media(prefers-color-scheme:dark){:root{--bg:#161719;--fg:#e8e8ea;--sub:#9a9aa0;"
                + "--line:#2c2d31;--card:#1f2023}}"
                + "body{margin:0;padding:20px;background:var(--bg);color:var(--fg);"
                + "font:14px/1.6 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif}"
                + "h1{font-size:22px;margin:0 0 4px}h2{font-size:17px;margin:28px 0 10px;"
                + "padding-top:14px;border-top:1px solid var(--line)}h3{font-size:14px;margin:18px 0 6px}"
                + ".sub{color:var(--sub);margin:0 0 18px}"
                + ".kpis{display:flex;flex-wrap:wrap;gap:10px}"
                + ".kpi{background:var(--card);border-radius:10px;padding:12px 16px;min-width:110px}"
                + ".kpi .v{font-size:19px;font-weight:700}.kpis.small .kpi .v{font-size:15px}"
                + ".kpi .l{color:var(--sub);font-size:12px;margin-top:2px}"
                + ".bar{display:flex;height:10px;border-radius:5px;overflow:hidden;margin:14px 0}"
                + ".seg{font-size:0}.seg.s{background:var(--s)}.seg.i{background:var(--i)}"
                + ".seg.d{background:var(--d)}"
                + "table{width:100%;border-collapse:collapse;margin:8px 0;font-size:13px;"
                + "display:block;overflow-x:auto}"
                + "th,td{border-bottom:1px solid var(--line);padding:7px 9px;text-align:left;"
                + "vertical-align:top}"
                + "th{color:var(--sub);font-weight:600;white-space:nowrap}"
                + "tr.bad td{background:rgba(228,91,91,.12)}"
                + "table.cond{max-width:520px}table.cond th{width:100px}"
                + ".diff span{padding:1px 2px;border-radius:3px}"
                + ".diff .s{background:rgba(228,91,91,.28)}.diff .i{background:rgba(224,169,43,.3)}"
                + ".diff .d{background:rgba(62,193,118,.28);text-decoration:line-through}"
                + ".legend span{margin-right:10px;padding:2px 6px;border-radius:3px;font-size:12px}"
                + ".legend .s{background:rgba(228,91,91,.28)}.legend .i{background:rgba(224,169,43,.3)}"
                + ".legend .d{background:rgba(62,193,118,.28)}"
                + ".mono,pre{background:var(--card);border-radius:8px;padding:10px 12px;"
                + "white-space:pre-wrap;word-break:break-word;font-size:12px}"
                + "pre{font-family:ui-monospace,Menlo,Consolas,monospace}"
                + ".note{color:var(--sub);font-size:12px}"
                + ".warn{background:rgba(224,169,43,.16);border-left:3px solid var(--i);"
                + "padding:8px 12px;border-radius:0 6px 6px 0;font-size:12px}"
                + ".tag{color:var(--sub);font-size:12px;font-weight:400}";
    }

    // ===================== 落盘 =====================

    private static boolean write(@NonNull File file, @NonNull String content, boolean append) {
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(file, append), "UTF-8");
            writer.write(content);
            writer.flush();
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            closeQuietly(writer);
        }
    }

    private static void closeQuietly(@Nullable Writer writer) {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException ignored) {
        }
    }
}
