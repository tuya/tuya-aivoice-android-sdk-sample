package com.tuya.smart.ai_voice.nativeui.widget

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * 跑批报告生成：单文件 HTML + 两份 CSV。
 * 
 * 
 * 产物落在 `getExternalFilesDir(DIRECTORY_DOCUMENTS)/inject_report/`——
 * 这个位置已在 `@xml/native_file_paths` 的 FileProvider 白名单里，
 * 分享时不用改 manifest，也不需要任何存储权限。
 * 
 * <h3>三份产物的分工</h3>
 * 
 *  * **HTML**：本次跑批的完整报告，含逐句与逐词对比，用于定位「错在哪」
 *  * **本次 CSV**：本次每轮一行，用于把这批结果丢进表格
 *  * **累积 CSV**：固定文件名追加，跨跑批累积，用于看同一音频的 WER 走势。
 * 它同时充当了持久化——本模块没有数据库，历史数据就靠这个文件
 * 
 * 
 * <h3>WER 口径</h3>
 * 见 [WerCalculator]，与 iOS 端一致。报告里「计算方式」章节会把规则原样写出来，
 * 让看报告的人不必翻代码就能判断某个差异是不是归一化造成的。
 */
object InjectReportBuilder {
    /** 报告目录名，挂在应用私有 Documents 下。  */
    private const val REPORT_DIR = "inject_report"

    /** 累积 CSV 的固定文件名。  */
    private const val CUMULATIVE_CSV = "inject_history.csv"

    /** 逐句对比的告警阈值：识别段数少于参考行数时，漏识判定会虚高。  */
    private val CSV_HEADER =
        ("batchId,time,file,repeat,repeatTotal,sourceLang,targetLang,tts,status,"
                + "N,S,D,I,wer,accuracy,durationMs,recordId\n")

    /**
     * 报告目录，不存在时创建。
     * 
     * @param context 上下文
     * @return 目录；外置存储不可用时返回 `null`
     */
    @JvmStatic
    fun reportDir(context: Context): File? {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        if (base == null) {
            return null
        }
        val dir = File(base, REPORT_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            return null
        }
        return dir
    }

    /**
     * 写出本次跑批的 HTML 报告。阻塞 IO，**必须在子线程调用**。
     * 
     * @param context 上下文
     * @param batchId 批次 ID
     * @param results 全部轮次结果
     * @return 生成的文件；失败返回 `null`
     */
    fun writeHtml(
        context: Context, batchId: String,
        results: MutableList<InjectRoundResult>
    ): File? {
        val dir = reportDir(context)
        if (dir == null) {
            return null
        }
        val file = File(dir, "inject_report_" + batchId + ".html")
        var writer: Writer? = null
        try {
            writer = OutputStreamWriter(FileOutputStream(file), "UTF-8")
            writeHtmlBody(writer, batchId, results)
            writer.flush()
            return file
        } catch (e: IOException) {
            return null
        } finally {
            closeQuietly(writer)
        }
    }

    /**
     * 写出本次跑批的 CSV，并把同样的行追加进累积 CSV。
     * 阻塞 IO，**必须在子线程调用**。
     * 
     * @param context 上下文
     * @param batchId 批次 ID
     * @param results 全部轮次结果
     * @return 本次 CSV 文件；失败返回 `null`
     */
    fun writeCsv(
        context: Context, batchId: String,
        results: MutableList<InjectRoundResult>
    ): File? {
        val dir = reportDir(context)
        if (dir == null) {
            return null
        }
        val time = timestamp()
        val rows = StringBuilder()
        for (r in results) {
            rows.append(csvRow(batchId, time, r))
        }

        val file = File(dir, "inject_" + batchId + ".csv")
        if (!write(file, CSV_HEADER + rows, false)) {
            return null
        }
        // 累积 CSV：文件不存在时先补表头，之后一律追加
        val cumulative = File(dir, CUMULATIVE_CSV)
        val exists = cumulative.isFile() && cumulative.length() > 0
        write(cumulative, if (exists) rows.toString() else CSV_HEADER + rows, true)
        return file
    }

    /**
     * 累积 CSV 的路径，供界面展示与分享。
     * 
     * @param context 上下文
     * @return 文件；目录不可用时返回 `null`
     */
    fun cumulativeCsv(context: Context): File? {
        val dir = reportDir(context)
        return if (dir == null) null else File(dir, CUMULATIVE_CSV)
    }

    // ===================== CSV =====================
    private fun csvRow(
        batchId: String, time: String,
        r: InjectRoundResult
    ): String {
        // 没有评分（超时 / 无结果）时 wer 为 null，CSV 留空
        val wer = r.wer
        val scored = wer != null
        return (csv(batchId) + ',' + csv(time) + ',' + csv(r.fileName) + ','
                + r.repeatIndex + ',' + r.repeatTotal + ','
                + csv(r.sourceLanguage) + ',' + csv(r.targetLanguage) + ','
                + (if (r.needTts) "1" else "0") + ',' + r.status.name + ','
                + (if (scored) wer.referenceCount.toString() else "") + ','
                + (if (scored) wer.substitutions.toString() else "") + ','
                + (if (scored) wer.deletions.toString() else "") + ','
                + (if (scored) wer.insertions.toString() else "") + ','
                + (if (scored) ratio(wer.wer()) else "") + ','
                + (if (scored) ratio(wer.accuracy()) else "") + ','
                + r.durationMs + ',' + csv(r.recordId) + '\n')
    }

    /**
     * CSV 字段转义。
     * 
     * 
     * 文件名和错误信息里可能带逗号、引号或换行，不转义会把整个表格错位。
     */
    private fun csv(value: String?): String {
        if (value == null || value.isEmpty()) {
            return ""
        }
        val escaped = value.replace("\"", "\"\"")
        return "\"" + escaped.replace("\r", " ").replace("\n", " ") + "\""
    }

    private fun ratio(value: Double): String {
        return String.format(Locale.US, "%.6f", value)
    }

    // ===================== HTML =====================
    @Throws(IOException::class)
    private fun writeHtmlBody(
        w: Writer, batchId: String,
        results: MutableList<InjectRoundResult>
    ) {
        val total = results.size
        var success = 0
        var scored = 0
        var totalDuration: Long = 0
        var sumN = 0
        var sumS = 0
        var sumD = 0
        var sumI = 0
        for (r in results) {
            if (r.status == InjectRoundResult.Status.SUCCESS) {
                success++
            }
            totalDuration += r.durationMs
            r.wer?.let {
                scored++
                sumN += it.referenceCount
                sumS += it.substitutions
                sumD += it.deletions
                sumI += it.insertions
            }
        }
        // 汇总 WER 用「词数加权」而不是各轮 WER 求平均：长音频的权重本就该更高
        val overallWer = if (sumN == 0) 0.0 else (sumS + sumD + sumI).toDouble() / sumN.toDouble()
        val overallAccuracy = max(0.0, 1 - overallWer)

        w.write("<!doctype html><html lang=\"zh\"><head><meta charset=\"utf-8\">")
        w.write("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        w.write("<title>灌流批量测试报告 " + esc(batchId) + "</title>")
        w.write("<style>" + css() + "</style></head><body>")

        w.write("<h1>灌流批量测试报告</h1>")
        w.write("<p class=\"sub\">批次 " + esc(batchId) + " · " + esc(timestamp()) + "</p>")

        w.write("<div class=\"kpis\">")
        w.write(kpi("总体准确率", percent(overallAccuracy)))
        w.write(kpi("总体 WER", percent(overallWer)))
        w.write(kpi("轮次", success.toString() + " / " + total))
        w.write(kpi("参与评分轮次", scored.toString()))
        w.write(kpi("参考词数", sumN.toString()))
        w.write(kpi("错误合计", (sumS + sumD + sumI).toString()))
        w.write(kpi("总耗时", duration(totalDuration)))
        w.write("</div>")

        if (sumN > 0) {
            w.write("<div class=\"bar\">")
            w.write(barSeg("s", sumS, sumS + sumD + sumI, "替换 " + sumS))
            w.write(barSeg("d", sumD, sumS + sumD + sumI, "删除 " + sumD))
            w.write(barSeg("i", sumI, sumS + sumD + sumI, "插入 " + sumI))
            w.write("</div>")
        }

        // 概览表：一眼看出哪几轮掉了
        w.write(
            ("<h2>轮次概览</h2><table><thead><tr>"
                    + "<th>#</th><th>音频</th><th>重复</th><th>语言</th><th>状态</th>"
                    + "<th>N</th><th>S</th><th>D</th><th>I</th><th>WER</th><th>耗时</th>"
                    + "</tr></thead><tbody>")
        )
        for (r in results) {
            w.write("<tr class=\"" + (if (r.status == InjectRoundResult.Status.SUCCESS) "" else "bad") + "\">")
            w.write(td(r.index.toString()))
            w.write(td(r.fileName))
            w.write(td(r.repeatIndex.toString() + "/" + r.repeatTotal))
            w.write(td(langLabel(r)))
            w.write(td(statusLabel(r)))
            val wer = r.wer
            if (wer != null) {
                w.write(td(wer.referenceCount.toString()))
                w.write(td(wer.substitutions.toString()))
                w.write(td(wer.deletions.toString()))
                w.write(td(wer.insertions.toString()))
                w.write(td(percent(wer.wer())))
            } else {
                w.write(td("—") + td("—") + td("—") + td("—") + td("—"))
            }
            w.write(td(duration(r.durationMs)))
            w.write("</tr>")
        }
        w.write("</tbody></table>")

        for (r in results) {
            writeRoundSection(w, r)
        }

        writeMethodSection(w)
        w.write("</body></html>")
    }

    @Throws(IOException::class)
    private fun writeRoundSection(w: Writer, r: InjectRoundResult) {
        w.write(
            ("<h2>#" + r.index + " " + esc(r.fileName)
                    + " <span class=\"tag\">" + esc(r.repeatIndex.toString() + "/" + r.repeatTotal) + "</span></h2>")
        )

        w.write("<table class=\"cond\"><tbody>")
        w.write(condRow("状态", statusLabel(r)))
        w.write(condRow("源语言", r.sourceLanguage))
        w.write(condRow("目标语言", if (r.targetLanguage.isEmpty()) "不翻译" else r.targetLanguage))
        w.write(condRow("TTS", if (r.needTts) "开" else "关"))
        w.write(condRow("耗时", duration(r.durationMs)))
        w.write(condRow("recordId", r.recordId))
        if (!r.errorMessage.isEmpty()) {
            w.write(condRow("失败原因", r.errorMessage))
        }
        w.write("</tbody></table>")

        if (!r.hasWer()) {
            w.write(
                ("<p class=\"note\">"
                        + (if (r.referenceText == null)
                    "没有找到与音频同名的参考答案 txt，本轮不计算 WER。"
                else
                    "本轮没有取到识别结果，不计算 WER。")
                        + "</p>")
            )
        } else {
            val wer = r.wer ?: return
            run {
                w.write("<div class=\"kpis small\">")
                w.write(kpi("准确率", percent(wer.accuracy())))
                w.write(kpi("WER", percent(wer.wer())))
                w.write(
                    kpi(
                        "N / S / D / I",
                        (wer.referenceCount.toString() + " / " + wer.substitutions
                                + " / " + wer.deletions + " / " + wer.insertions)
                    )
                )
                w.write(kpi("命中词数", wer.hits().toString()))
                w.write("</div>")
            }

            if (!r.lines.isEmpty()) {
                val segments = r.asrSegments.size
                if (segments > 0 && segments < r.lines.size) {
                    w.write(
                        ("<p class=\"warn\">识别分段数（" + segments + "）少于参考行数（"
                                + r.lines.size + "）。一个识别分段不会被拆给多个参考行，"
                                + "必然有行分不到内容而被判漏识，<b>逐句结果偏低属于对齐口径造成的虚高，"
                                + "请以上方全文 WER 为准</b>。</p>")
                    )
                }
                w.write(
                    "<h3>逐句对比</h3><table><thead><tr><th>#</th><th>参考</th><th>识别</th>"
                            + "<th>N</th><th>S</th><th>D</th><th>I</th><th>WER</th></tr></thead><tbody>"
                )
                for (line in r.lines) {
                    if (line == null) continue
                    w.write("<tr class=\"" + (if (line.missed) "bad" else "") + "\">")
                    w.write(td(line.lineNumber.toString()))
                    w.write(td(line.reference))
                    w.write(td(if (line.hypothesis.isEmpty()) "（未识别）" else line.hypothesis))
                    w.write(td(line.result.referenceCount.toString()))
                    w.write(td(line.result.substitutions.toString()))
                    w.write(td(line.result.deletions.toString()))
                    w.write(td(line.result.insertions.toString()))
                    w.write(td(percent(line.result.wer())))
                    w.write("</tr>")
                }
                w.write("</tbody></table>")
            }

            w.write("<h3>全文逐词对比</h3><p class=\"diff\">")
            for (token in wer.alignment) {
                if (token == null) continue
                when (token.operation) {
                    WerCalculator.Operation.EQUAL -> w.write("<span>" + esc(token.hypothesisToken) + "</span> ")
                    WerCalculator.Operation.SUBSTITUTE -> w.write(
                        ("<span class=\"s\" title=\"应为 " + esc(token.referenceToken) + "\">"
                                + esc(token.hypothesisToken) + "</span> ")
                    )

                    WerCalculator.Operation.INSERT -> w.write("<span class=\"i\">" + esc(token.hypothesisToken) + "</span> ")
                    WerCalculator.Operation.DELETE -> w.write("<span class=\"d\">" + esc(token.referenceToken) + "</span> ")
                    else -> w.write("<span class=\"d\">" + esc(token.referenceToken) + "</span> ")
                }
            }
            w.write(
                "</p><p class=\"legend\"><span class=\"s\">替换</span>"
                        + "<span class=\"i\">插入</span><span class=\"d\">删除（漏识）</span></p>"
            )

            w.write("<h3>归一化后文本（实际参与计算的内容）</h3>")
            w.write("<p class=\"mono\"><b>参考</b><br>" + esc(wer.normalizedReference) + "</p>")
            w.write("<p class=\"mono\"><b>识别</b><br>" + esc(wer.normalizedHypothesis) + "</p>")
        }

        if (r.referenceText != null) {
            w.write("<h3>参考答案原文</h3><pre>" + esc(r.referenceText) + "</pre>")
        }
        val asr = r.asrText()
        if (!asr.isEmpty()) {
            w.write("<h3>识别原文</h3><pre>" + esc(asr) + "</pre>")
        }
        val translate = r.translateText()
        if (!translate.isEmpty()) {
            w.write("<h3>译文（仅记录，不评分）</h3><pre>" + esc(translate) + "</pre>")
        }
    }

    @Throws(IOException::class)
    private fun writeMethodSection(w: Writer) {
        w.write("<h2>计算方式</h2>")
        w.write(
            ("<p><b>WER = (S + D + I) / N，准确率 = 1 − WER（下限截到 0）。</b>"
                    + "S 替换、D 删除（参考有但没识别出来）、I 插入（识别多出来的）、N 参考词数。"
                    + "总体 WER 按词数加权汇总，不是各轮 WER 的算术平均。</p>")
        )
        w.write(
            ("<p>归一化（参考与识别套用同一套规则）：</p><ol>"
                    + "<li>统一转小写、去首尾空白</li>"
                    + "<li>剔除语气词 啊额呃嗯唉哎哦喔呵哈儿</li>"
                    + "<li>中文逐字切分，英文等按词切分</li>"
                    + "<li>标点<b>替换为空格</b>而非删除，避免把相邻两词粘成一个</li>"
                    + "<li>千分位还原：40 000 → 40000</li>"
                    + "<li>数词归一化：two / zwei → 2</li></ol>")
        )
        w.write(
            ("<p>对齐：Levenshtein 动态规划 + 回溯，代价相同时优先判替换、其次删除。"
                    + "逐句对比把识别分段单调切分后分配给参考行，使各行编辑距离之和最小；"
                    + "一个识别分段不会被拆给多个参考行，因此 ASR 合并输出时逐句结果会虚高，"
                    + "全文 WER 不受影响。</p>")
        )
        w.write("<p class=\"note\">本口径与 iOS 端 PerfusionWERCalculator 一致。</p>")
    }

    // ===================== 片段 =====================
    private fun kpi(label: String, value: String): String {
        return ("<div class=\"kpi\"><div class=\"v\">" + esc(value) + "</div><div class=\"l\">"
                + esc(label) + "</div></div>")
    }

    private fun barSeg(cls: String, value: Int, total: Int, label: String): String {
        if (total <= 0 || value <= 0) {
            return ""
        }
        val width = max(1, value * 100 / total)
        return "<div class=\"seg " + cls + "\" style=\"width:" + width + "%\">" + esc(label) + "</div>"
    }

    private fun td(value: String?): String {
        return "<td>" + esc(value) + "</td>"
    }

    private fun condRow(key: String, value: String?): String {
        return "<tr><th>" + esc(key) + "</th><td>" + esc(value) + "</td></tr>"
    }

    private fun langLabel(r: InjectRoundResult): String {
        return if (r.targetLanguage.isEmpty())
            r.sourceLanguage
        else
            r.sourceLanguage + " → " + r.targetLanguage
    }

    private fun statusLabel(r: InjectRoundResult): String {
        when (r.status) {
            InjectRoundResult.Status.SUCCESS -> return "成功"
            InjectRoundResult.Status.TIMEOUT -> return "超时"
            InjectRoundResult.Status.NO_RESULT -> return "无识别结果"
            InjectRoundResult.Status.ERROR -> return if (r.errorMessage.isEmpty()) "失败" else "失败：" + r.errorMessage
            else -> return if (r.errorMessage.isEmpty()) "失败" else "失败：" + r.errorMessage
        }
    }

    private fun percent(value: Double): String {
        return String.format(Locale.US, "%.2f%%", value * 100)
    }

    private fun duration(ms: Long): String {
        if (ms < 1000) {
            return ms.toString() + "ms"
        }
        val seconds = ms / 1000
        return if (seconds < 60)
            seconds.toString() + "s"
        else String.format(Locale.US, "%dm%02ds", seconds / 60, seconds % 60)
    }

    private fun timestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    }

    /**
     * HTML 转义。识别结果是外部数据，直接拼进 HTML 会破坏结构。
     */
    private fun esc(text: String?): String {
        if (text == null || text.isEmpty()) {
            return ""
        }
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    private fun css(): String {
        return (":root{color-scheme:light dark;--bg:#fff;--fg:#1e1e1e;--sub:#888;--line:#e6e6e6;"
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
                + ".tag{color:var(--sub);font-size:12px;font-weight:400}")
    }

    // ===================== 落盘 =====================
    private fun write(file: File, content: String, append: Boolean): Boolean {
        var writer: Writer? = null
        try {
            writer = OutputStreamWriter(FileOutputStream(file, append), "UTF-8")
            writer.write(content)
            writer.flush()
            return true
        } catch (e: IOException) {
            return false
        } finally {
            closeQuietly(writer)
        }
    }

    private fun closeQuietly(writer: Writer?) {
        if (writer == null) {
            return
        }
        try {
            writer.close()
        } catch (ignored: IOException) {
        }
    }
}
