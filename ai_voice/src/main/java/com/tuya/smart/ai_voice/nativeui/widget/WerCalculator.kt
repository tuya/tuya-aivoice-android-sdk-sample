package com.tuya.smart.ai_voice.nativeui.widget

import java.text.BreakIterator
import java.util.Arrays
import java.util.Collections
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * WER（词错误率）计算。
 * 
 * 
 * `WER = (S + D + I) / N`，`准确率 = 1 - WER`（下限截到 0）。
 * S 替换、D 删除（参考里有但没识别出来）、I 插入（识别多出来的）、N 参考词数。
 * 
 * <h3>口径与 iOS 端对齐</h3>
 * 本类是 iOS `PerfusionWERCalculator` 的逐行移植，两端结果必须一致，
 * **修改任何一条归一化规则或对齐策略前，必须先确认 iOS 侧同步修改**，否则两端数据无法比对。
 * 
 * 
 * 注意：本模块**不能**复用调试工程里那套 `WERCalculator`——那一套做语言自动检测、
 * 从资源读停用词表、且把阿拉伯数字转成中文数词（本类相反，是把 `two`/`zwei` 转成 `2`），
 * 两者算出的数不可能相同。
 * 
 * <h3>归一化（参考与识别套用同一套规则）</h3>
 * 
 *  1. 转小写、去首尾空白
 *  1. 剔除语气词 [.FILLER_CHARS]
 *  1. 中文逐字切分
 *  1. 标点**替换为空格**而非删除——直接删会把 `starkes.Kulturellen` 粘成一个词，凭空制造错误
 *  1. 千分位还原：`40 000` → `40000`
 *  1. 数词归一化：`two` / `zwei` → `2`
 * 
 */
object WerCalculator {
    /** 语气词（填充词），归一化时剔除，不参与 WER。  */
    private const val FILLER_CHARS = "啊额呃嗯唉哎哦喔呵哈儿"

    /** 行内 WER 达到该阈值即认为这一行没有被识别出来。  */
    private const val MISSED_LINE_WER = 0.85

    /** 一个参考行最多允许由多少个识别分段合并而成的下限，实际值按识别段数与参考行数的比例上调。  */
    private const val MIN_MAX_SPAN = 8

    /** 逐句对齐时只在对角线附近搜索的最小带宽，避免长文本退化。  */
    private const val MIN_BAND = 20

    /** 千分位还原时，后一段必须恰好 3 位数字才认为是分组。  */
    private const val THOUSAND_GROUP_LEN = 3

    /** 行首编号：纯数字，或字母 + 可选连接符 + 数字。只有这种形态才剥离，避免误删正文首词。  */
    private val LINE_INDEX: Pattern = Pattern.compile("^(?:\\d+|[A-Za-z]+[-_]?\\d+)$")

    /** 数词归一化表：参考文本写 two，ASR 常输出 2，不归一会被判成替换错误。  */
    private val NUMBER_MAP: MutableMap<String?, String?> = buildNumberMap()

    // ===================== 归一化 =====================
    /**
     * 文本归一化。参考答案与识别结果必须都走这里，否则比较没有意义。
     * 
     * @param text 原始文本，可为 null
     * @return 以单个空格分隔的归一化文本；输入为空时返回空串
     */
    fun normalize(text: String?): String {
        if (text == null || text.isEmpty()) {
            return ""
        }
        val lowercase = text.lowercase().trim { it <= ' ' }
        val buffer = StringBuilder(lowercase.length + 16)

        // 按字素簇遍历：带变音符号的字母（ö、ü 的分解形式）不会被拆坏
        val it = BreakIterator.getCharacterInstance(Locale.ROOT)
        it.setText(lowercase)
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            val cluster = lowercase.substring(start, end)
            if (cluster.isEmpty()) {
                start = end
                end = it.next()
                continue
            }
            val first = cluster.get(0)
            // 语气词直接丢弃，不参与 WER
            if (cluster.length == 1 && FILLER_CHARS.indexOf(first) >= 0) {
                start = end
                end = it.next()
                continue
            }
            if (isCjk(first)) {
                // 中文逐字比较：字与字之间补空格
                buffer.append(' ').append(cluster).append(' ')
                start = end
                end = it.next()
                continue
            }
            if (!containsKeepChar(cluster)) {
                buffer.append(' ') // 标点等 → 空格
                start = end
                end = it.next()
                continue
            }
            buffer.append(cluster)
            start = end
            end = it.next()
        }

        val tokens = splitOnWhitespace(buffer.toString())

        // 千分位/小数点在上一步变成了空格，这里把 "40 000" 还原成 "40000"
        val merged: MutableList<String?> = ArrayList<String?>(tokens.size)
        for (token in tokens) {
            val previous = if (merged.isEmpty()) null else merged.get(merged.size - 1)
            if (previous != null && !previous.isEmpty() && token.length == THOUSAND_GROUP_LEN && isAllDigits(
                    previous
                ) && isAllDigits(token)
            ) {
                merged.set(merged.size - 1, previous + token)
                continue
            }
            merged.add(token)
        }

        val out = StringBuilder()
        for (i in merged.indices) {
            if (i > 0) {
                out.append(' ')
            }
            val token = merged.get(i)
            val mapped = NUMBER_MAP.get(token)
            out.append(if (mapped == null) token else mapped)
        }
        return out.toString()
    }

    /**
     * 把参考答案按行归一化，供逐句对比使用。
     * 
     * 
     * 行首的「编号 + 空格」会被剥离，但只认 [.LINE_INDEX] 那种形态，
     * 避免把正文首词当成编号删掉。空行丢弃。
     * 
     * @param text 参考答案全文
     * @return 归一化后的行列表
     */
    @JvmStatic
    fun normalizeReferenceLines(text: String?): MutableList<String> {
        val lines: MutableList<String> = ArrayList()
        if (text == null || text.isEmpty()) {
            return lines
        }
        for (rawLine in text.split("\\R".toRegex()).toTypedArray()) {
            var line = rawLine.trim { it <= ' ' }
            if (line.isEmpty()) {
                continue
            }
            val space = line.indexOf(' ')
            if (space > 0) {
                val head = line.substring(0, space)
                if (LINE_INDEX.matcher(head).matches()) {
                    line = line.substring(space + 1)
                }
            }
            val normalized = normalize(line)
            if (!normalized.isEmpty()) {
                lines.add(normalized)
            }
        }
        return lines
    }

    // ===================== 编辑距离 =====================
    /**
     * 计算 WER。内部会先对两侧做归一化。
     * 
     * @param reference  参考答案
     * @param hypothesis 识别结果
     * @return 计算结果
     */
    @JvmStatic
    fun evaluate(reference: String?, hypothesis: String?): Result {
        return evaluateNormalized(normalize(reference), normalize(hypothesis))
    }

    /**
     * 对已归一化的文本计算 WER。
     * 
     * 
     * 逐句对比会先做分段对齐，再对每行调用本方法，因此归一化不能重复做。
     * 
     * @param reference  已归一化的参考答案
     * @param hypothesis 已归一化的识别结果
     * @return 计算结果
     */
    fun evaluateNormalized(reference: String, hypothesis: String): Result {
        val refTokens = splitOnWhitespace(reference)
        val hypTokens = splitOnWhitespace(hypothesis)

        val result = Result()
        result.normalizedReference = reference
        result.normalizedHypothesis = hypothesis
        result.referenceCount = refTokens.size
        result.hypothesisCount = hypTokens.size

        val n = refTokens.size
        val m = hypTokens.size
        if (n == 0 && m == 0) {
            result.alignment = mutableListOf<Token?>()
            return result
        }
        // 一侧为空时无需 DP：要么全是删除，要么全是插入
        if (n == 0 || m == 0) {
            val alignment: MutableList<Token?> = ArrayList<Token?>(n + m)
            for (token in refTokens) {
                val item = Token()
                item.operation = Operation.DELETE
                item.referenceToken = token
                alignment.add(item)
            }
            for (token in hypTokens) {
                val item = Token()
                item.operation = Operation.INSERT
                item.hypothesisToken = token
                alignment.add(item)
            }
            result.deletions = n
            result.insertions = m
            result.alignment = alignment
            return result
        }

        // d[i][j] 为前 i 个参考词与前 j 个识别词的编辑距离，bt 记录回溯方向
        val width = m + 1
        val distance = IntArray((n + 1) * width)
        // 操作编码：0 命中 1 替换 2 删除 3 插入
        val backtrace = ByteArray((n + 1) * width)
        for (i in 0..n) {
            distance[i * width] = i
            backtrace[i * width] = 2
        }
        for (j in 0..m) {
            distance[j] = j
            backtrace[j] = 3
        }
        backtrace[0] = 0

        for (i in 1..n) {
            val refToken = refTokens.get(i - 1)
            for (j in 1..m) {
                if (refToken == hypTokens.get(j - 1)) {
                    distance[i * width + j] = distance[(i - 1) * width + (j - 1)]
                    backtrace[i * width + j] = 0
                    continue
                }
                val substitute = distance[(i - 1) * width + (j - 1)] + 1
                val delete = distance[(i - 1) * width + j] + 1
                val insert = distance[i * width + (j - 1)] + 1
                val best = min(substitute, min(delete, insert))
                distance[i * width + j] = best
                // 相等时优先替换，其次删除，与 iOS 及参考实现保持一致
                backtrace[i * width + j] =
                    (if (best == substitute) 1 else (if (best == delete) 2 else 3)).toByte()
            }
        }

        val reversed: MutableList<Token?> = ArrayList<Token?>()
        var i = n
        var j = m
        var s = 0
        var d = 0
        var ins = 0
        while (i > 0 || j > 0) {
            val op = backtrace[i * width + j]
            val item = Token()
            if (i > 0 && j > 0 && op.toInt() == 0) {
                item.operation = Operation.EQUAL
                item.referenceToken = refTokens.get(i - 1)
                item.hypothesisToken = hypTokens.get(j - 1)
                i--
                j--
            } else if (i > 0 && j > 0 && op.toInt() == 1) {
                item.operation = Operation.SUBSTITUTE
                item.referenceToken = refTokens.get(i - 1)
                item.hypothesisToken = hypTokens.get(j - 1)
                s++
                i--
                j--
            } else if (i > 0 && (op.toInt() == 2 || j == 0)) {
                item.operation = Operation.DELETE
                item.referenceToken = refTokens.get(i - 1)
                d++
                i--
            } else {
                item.operation = Operation.INSERT
                item.hypothesisToken = hypTokens.get(j - 1)
                ins++
                j--
            }
            reversed.add(item)
        }
        Collections.reverse(reversed)

        result.substitutions = s
        result.deletions = d
        result.insertions = ins
        result.alignment = reversed
        return result
    }

    // ===================== 逐句对齐 =====================
    /**
     * 逐句对比：把识别分段分配给参考行后逐行计算。
     * 
     * 
     * **固有限制**：一个识别分段不会被拆开分配给多个参考行。ASR 把多行参考合并成一段输出时
     * （识别段数 &lt; 参考行数），必然有参考行分不到内容而被判为漏识，属于对齐口径造成的虚高。
     * **全文 WER 不受此影响，任何情况下都以全文口径为准。**
     * 
     * @param referenceLines 已归一化的参考行，见 [.normalizeReferenceLines]
     * @param segments       识别分段原文，内部会归一化
     * @return 每行一个结果
     */
    fun evaluateLines(
        referenceLines: MutableList<String>,
        segments: MutableList<String?>
    ): MutableList<LineResult?> {
        val results: MutableList<LineResult?> = ArrayList<LineResult?>()
        if (referenceLines.isEmpty()) {
            return results
        }
        val normalizedSegments: MutableList<String> = ArrayList()
        for (segment in segments) {
            val normalized = normalize(segment)
            if (!normalized.isEmpty()) {
                normalizedSegments.add(normalized)
            }
        }
        val assignment = alignSegments(normalizedSegments, referenceLines)

        for (idx in referenceLines.indices) {
            val pieces = StringBuilder()
            for (segIndex in assignment.get(idx).orEmpty()) {
                if (segIndex >= 0 && segIndex < normalizedSegments.size) {
                    if (pieces.length > 0) {
                        pieces.append(' ')
                    }
                    pieces.append(normalizedSegments.get(segIndex))
                }
            }
            val line = LineResult()
            line.lineNumber = idx + 1
            line.reference = referenceLines.get(idx)
            line.hypothesis = pieces.toString()
            line.result = evaluateNormalized(line.reference, line.hypothesis)
            line.missed = line.result.wer() >= MISSED_LINE_WER
            results.add(line)
        }
        return results
    }

    /**
     * 把识别分段单调切分并对齐到参考行：动态规划，使总编辑距离最小。
     * 只在对角线附近搜索（band），避免长文本上退化。
     * 
     * @param segments       已归一化的识别分段
     * @param referenceLines 已归一化的参考行
     * @return 每个参考行分到的分段下标
     */
    private fun alignSegments(
        segments: MutableList<String>,
        referenceLines: MutableList<String>
    ): MutableList<MutableList<Int>?> {
        val nR = referenceLines.size
        val nH = segments.size

        val refTokens: MutableList<MutableList<String>> = ArrayList<MutableList<String>>(nR)
        for (line in referenceLines) {
            refTokens.add(splitOnWhitespace(line))
        }
        val hypTokens: MutableList<MutableList<String>> = ArrayList(nH)
        for (segment in segments) {
            hypTokens.add(splitOnWhitespace(segment))
        }

        val empty: MutableList<MutableList<Int>?> = ArrayList(nR)
        for (i in 0..<nR) {
            empty.add(mutableListOf())
        }
        if (nH == 0) {
            return empty
        }
        // 参考答案只有一行（整段文本）时无需对齐：所有识别分段都属于它。
        // 走 DP 反而会被单行合并段数的上限截断，把多余的识别内容丢掉。
        if (nR == 1) {
            val all: MutableList<Int> = ArrayList(nH)
            for (t in 0..<nH) {
                all.add(t)
            }
            return mutableListOf<MutableList<Int>?>(all)
        }

        val inf = Double.MAX_VALUE / 4
        val width = nH + 1
        val dp = DoubleArray((nR + 1) * width)
        val back = IntArray((nR + 1) * width)
        Arrays.fill(dp, inf)
        dp[0] = 0.0

        val ratio = nH.toDouble() / nR.toDouble()
        val band = max(MIN_BAND, (ratio * 6).toInt())
        // 识别分段数远多于参考行数时（ASR 把一句切得很碎），上限要跟着放大，
        // 否则尾部分段会分不到任何参考行而被丢弃，凭空判成漏识。
        val maxSpan = max(MIN_MAX_SPAN, ceil(ratio).toInt() + 4)

        for (i in 1..nR) {
            val lo = max(0, (i * ratio).toInt() - band)
            val hi = min(nH, (i * ratio).toInt() + band)
            val reference = refTokens.get(i - 1)
            for (j in lo..hi) {
                var best = inf
                var bestSpan = 0
                for (span in 0..maxSpan) {
                    val k = j - span
                    if (k < 0) {
                        break
                    }
                    val previous = dp[(i - 1) * width + k]
                    if (previous >= inf) {
                        continue
                    }
                    val merged: MutableList<String?> = ArrayList<String?>()
                    for (t in k..<j) {
                        merged.addAll(hypTokens.get(t).orEmpty())
                    }
                    val cost = previous + editDistance(reference, merged)
                    if (cost < best) {
                        best = cost
                        bestSpan = span
                    }
                }
                dp[i * width + j] = best
                back[i * width + j] = bestSpan
            }
        }

        val assignment: MutableList<MutableList<Int>?> = ArrayList(nR)
        for (i in 0..<nR) {
            assignment.add(mutableListOf())
        }
        var j = nH
        for (i in nR downTo 1) {
            val span = back[i * width + j]
            val ids: MutableList<Int> = ArrayList()
            for (t in j - span..<j) {
                if (t >= 0) {
                    ids.add(t)
                }
            }
            assignment.set(i - 1, ids)
            j -= span
            if (j < 0) {
                j = 0
            }
        }
        return assignment
    }

    /** 只求编辑距离数值，用于对齐打分。  */
    private fun editDistance(left: MutableList<String>, right: MutableList<String?>): Int {
        val n = left.size
        val m = right.size
        if (n == 0) {
            return m
        }
        if (m == 0) {
            return n
        }
        val previous = IntArray(m + 1)
        val current = IntArray(m + 1)
        for (j in 0..m) {
            previous[j] = j
        }
        for (i in 1..n) {
            current[0] = i
            val token = left.get(i - 1)
            for (j in 1..m) {
                if (token == right.get(j - 1)) {
                    current[j] = previous[j - 1]
                } else {
                    current[j] = min(previous[j - 1], min(previous[j], current[j - 1])) + 1
                }
            }
            System.arraycopy(current, 0, previous, 0, m + 1)
        }
        return previous[m]
    }

    // ===================== 工具 =====================
    /**
     * 按空白切分并丢弃空串。
     * 
     * @param text 待切分文本
     * @return 词列表
     */
    fun splitOnWhitespace(text: String?): MutableList<String> {
        val tokens: MutableList<String> = ArrayList<String>()
        if (text == null || text.isEmpty()) {
            return tokens
        }
        for (piece in text.split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()) {
            if (!piece.isEmpty()) {
                tokens.add(piece)
            }
        }
        return tokens
    }

    private fun isCjk(c: Char): Boolean {
        return c.code >= 0x4E00 && c.code <= 0x9FFF
    }

    /** 归一化保留的字符：字母、数字、下划线、空白。其余一律转空格。  */
    private fun containsKeepChar(cluster: String): Boolean {
        for (i in 0..<cluster.length) {
            val c = cluster.get(i)
            if (Character.isLetterOrDigit(c) || c == '_' || Character.isWhitespace(c)) {
                return true
            }
        }
        return false
    }

    private fun isAllDigits(text: String): Boolean {
        if (text.isEmpty()) {
            return false
        }
        for (i in 0..<text.length) {
            if (!Character.isDigit(text.get(i))) {
                return false
            }
        }
        return true
    }

    private fun buildNumberMap(): MutableMap<String?, String?> {
        val map: MutableMap<String?, String?> = HashMap<String?, String?>()
        map.put("null", "0")
        map.put("eins", "1")
        map.put("zwei", "2")
        map.put("drei", "3")
        map.put("vier", "4")
        map.put("fünf", "5")
        map.put("sechs", "6")
        map.put("sieben", "7")
        map.put("acht", "8")
        map.put("neun", "9")
        map.put("zehn", "10")
        map.put("zero", "0")
        map.put("one", "1")
        map.put("two", "2")
        map.put("three", "3")
        map.put("four", "4")
        map.put("five", "5")
        map.put("six", "6")
        map.put("seven", "7")
        map.put("eight", "8")
        map.put("nine", "9")
        map.put("ten", "10")
        return Collections.unmodifiableMap<String?, String?>(map)
    }
    // ===================== 对外数据结构 =====================
    /** 对齐操作。  */
    enum class Operation {
        /** 命中。  */
        EQUAL,

        /** 替换。  */
        SUBSTITUTE,

        /** 删除：参考里有，识别里没有。  */
        DELETE,

        /** 插入：识别里多出来的。  */
        INSERT
    }

    /** 对齐结果中的一个位置。  */
    class Token {
        var operation: Operation? = null
        var referenceToken: String? = null
        var hypothesisToken: String? = null
    }

    /** 一次 WER 计算的结果。  */
    class Result {
        var normalizedReference: String = ""
        var normalizedHypothesis: String = ""
        var referenceCount: Int = 0
        var hypothesisCount: Int = 0
        var substitutions: Int = 0
        var deletions: Int = 0
        var insertions: Int = 0
        var alignment: MutableList<Token?> = mutableListOf<Token?>()

        fun errorCount(): Int {
            return substitutions + deletions + insertions
        }

        fun wer(): Double {
            return if (referenceCount == 0) 0.0 else errorCount().toDouble() / referenceCount.toDouble()
        }

        fun accuracy(): Double {
            val value = 1.0 - wer()
            return if (value < 0) 0.0 else value
        }

        /** @return 命中词数
         */
        fun hits(): Int {
            val wrong = substitutions + deletions
            return if (referenceCount > wrong) referenceCount - wrong else 0
        }
    }

    /** 单行参考答案的评估结果。  */
    class LineResult {
        var lineNumber: Int = 0
        var reference: String = ""
        var hypothesis: String = ""
        var result: Result = Result()

        /** 行内 WER 超过阈值，视为整行漏识。  */
        var missed: Boolean = false
    }
}
