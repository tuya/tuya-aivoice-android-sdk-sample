package com.tuya.smart.ai_voice.nativeui.widget;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * WER（词错误率）计算。
 * <p>
 * {@code WER = (S + D + I) / N}，{@code 准确率 = 1 - WER}（下限截到 0）。
 * S 替换、D 删除（参考里有但没识别出来）、I 插入（识别多出来的）、N 参考词数。
 *
 * <h3>口径与 iOS 端对齐</h3>
 * 本类是 iOS {@code PerfusionWERCalculator} 的逐行移植，两端结果必须一致，
 * <b>修改任何一条归一化规则或对齐策略前，必须先确认 iOS 侧同步修改</b>，否则两端数据无法比对。
 * <p>
 * 注意：本模块<b>不能</b>复用调试工程里那套 {@code WERCalculator}——那一套做语言自动检测、
 * 从资源读停用词表、且把阿拉伯数字转成中文数词（本类相反，是把 {@code two}/{@code zwei} 转成 {@code 2}），
 * 两者算出的数不可能相同。
 *
 * <h3>归一化（参考与识别套用同一套规则）</h3>
 * <ol>
 *     <li>转小写、去首尾空白</li>
 *     <li>剔除语气词 {@link #FILLER_CHARS}</li>
 *     <li>中文逐字切分</li>
 *     <li>标点<b>替换为空格</b>而非删除——直接删会把 {@code starkes.Kulturellen} 粘成一个词，凭空制造错误</li>
 *     <li>千分位还原：{@code 40 000} → {@code 40000}</li>
 *     <li>数词归一化：{@code two} / {@code zwei} → {@code 2}</li>
 * </ol>
 */
public final class WerCalculator {

    /** 语气词（填充词），归一化时剔除，不参与 WER。 */
    private static final String FILLER_CHARS = "啊额呃嗯唉哎哦喔呵哈儿";

    /** 行内 WER 达到该阈值即认为这一行没有被识别出来。 */
    private static final double MISSED_LINE_WER = 0.85;

    /** 一个参考行最多允许由多少个识别分段合并而成的下限，实际值按识别段数与参考行数的比例上调。 */
    private static final int MIN_MAX_SPAN = 8;

    /** 逐句对齐时只在对角线附近搜索的最小带宽，避免长文本退化。 */
    private static final int MIN_BAND = 20;

    /** 千分位还原时，后一段必须恰好 3 位数字才认为是分组。 */
    private static final int THOUSAND_GROUP_LEN = 3;

    /** 行首编号：纯数字，或字母 + 可选连接符 + 数字。只有这种形态才剥离，避免误删正文首词。 */
    private static final Pattern LINE_INDEX = Pattern.compile("^(?:\\d+|[A-Za-z]+[-_]?\\d+)$");

    /** 数词归一化表：参考文本写 two，ASR 常输出 2，不归一会被判成替换错误。 */
    private static final Map<String, String> NUMBER_MAP = buildNumberMap();

    private WerCalculator() {
    }

    // ===================== 对外数据结构 =====================

    /** 对齐操作。 */
    public enum Operation {
        /** 命中。 */
        EQUAL,
        /** 替换。 */
        SUBSTITUTE,
        /** 删除：参考里有，识别里没有。 */
        DELETE,
        /** 插入：识别里多出来的。 */
        INSERT
    }

    /** 对齐结果中的一个位置。 */
    public static class Token {
        public Operation operation;
        @Nullable
        public String referenceToken;
        @Nullable
        public String hypothesisToken;
    }

    /** 一次 WER 计算的结果。 */
    public static class Result {
        public String normalizedReference = "";
        public String normalizedHypothesis = "";
        public int referenceCount;
        public int hypothesisCount;
        public int substitutions;
        public int deletions;
        public int insertions;
        public List<Token> alignment = Collections.emptyList();

        public int errorCount() {
            return substitutions + deletions + insertions;
        }

        public double wer() {
            return referenceCount == 0 ? 0 : (double) errorCount() / (double) referenceCount;
        }

        public double accuracy() {
            double value = 1.0 - wer();
            return value < 0 ? 0 : value;
        }

        /** @return 命中词数 */
        public int hits() {
            int wrong = substitutions + deletions;
            return referenceCount > wrong ? referenceCount - wrong : 0;
        }
    }

    /** 单行参考答案的评估结果。 */
    public static class LineResult {
        public int lineNumber;
        public String reference = "";
        public String hypothesis = "";
        public Result result = new Result();
        /** 行内 WER 超过阈值，视为整行漏识。 */
        public boolean missed;
    }

    // ===================== 归一化 =====================

    /**
     * 文本归一化。参考答案与识别结果必须都走这里，否则比较没有意义。
     *
     * @param text 原始文本，可为 null
     * @return 以单个空格分隔的归一化文本；输入为空时返回空串
     */
    @NonNull
    public static String normalize(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String lowercase = text.toLowerCase(Locale.ROOT).trim();
        StringBuilder buffer = new StringBuilder(lowercase.length() + 16);

        // 按字素簇遍历：带变音符号的字母（ö、ü 的分解形式）不会被拆坏
        BreakIterator it = BreakIterator.getCharacterInstance(Locale.ROOT);
        it.setText(lowercase);
        int start = it.first();
        for (int end = it.next(); end != BreakIterator.DONE; start = end, end = it.next()) {
            String cluster = lowercase.substring(start, end);
            if (cluster.isEmpty()) {
                continue;
            }
            char first = cluster.charAt(0);
            // 语气词直接丢弃，不参与 WER
            if (cluster.length() == 1 && FILLER_CHARS.indexOf(first) >= 0) {
                continue;
            }
            if (isCjk(first)) {
                // 中文逐字比较：字与字之间补空格
                buffer.append(' ').append(cluster).append(' ');
                continue;
            }
            if (!containsKeepChar(cluster)) {
                buffer.append(' ');   // 标点等 → 空格
                continue;
            }
            buffer.append(cluster);
        }

        List<String> tokens = splitOnWhitespace(buffer.toString());

        // 千分位/小数点在上一步变成了空格，这里把 "40 000" 还原成 "40000"
        List<String> merged = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            String previous = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (previous != null && !previous.isEmpty()
                    && token.length() == THOUSAND_GROUP_LEN
                    && isAllDigits(previous) && isAllDigits(token)) {
                merged.set(merged.size() - 1, previous + token);
                continue;
            }
            merged.add(token);
        }

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < merged.size(); i++) {
            if (i > 0) {
                out.append(' ');
            }
            String token = merged.get(i);
            String mapped = NUMBER_MAP.get(token);
            out.append(mapped == null ? token : mapped);
        }
        return out.toString();
    }

    /**
     * 把参考答案按行归一化，供逐句对比使用。
     * <p>
     * 行首的「编号 + 空格」会被剥离，但只认 {@link #LINE_INDEX} 那种形态，
     * 避免把正文首词当成编号删掉。空行丢弃。
     *
     * @param text 参考答案全文
     * @return 归一化后的行列表
     */
    @NonNull
    public static List<String> normalizeReferenceLines(@Nullable String text) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        for (String rawLine : text.split("\\R", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            int space = line.indexOf(' ');
            if (space > 0) {
                String head = line.substring(0, space);
                if (LINE_INDEX.matcher(head).matches()) {
                    line = line.substring(space + 1);
                }
            }
            String normalized = normalize(line);
            if (!normalized.isEmpty()) {
                lines.add(normalized);
            }
        }
        return lines;
    }

    // ===================== 编辑距离 =====================

    /**
     * 计算 WER。内部会先对两侧做归一化。
     *
     * @param reference  参考答案
     * @param hypothesis 识别结果
     * @return 计算结果
     */
    @NonNull
    public static Result evaluate(@Nullable String reference, @Nullable String hypothesis) {
        return evaluateNormalized(normalize(reference), normalize(hypothesis));
    }

    /**
     * 对已归一化的文本计算 WER。
     * <p>
     * 逐句对比会先做分段对齐，再对每行调用本方法，因此归一化不能重复做。
     *
     * @param reference  已归一化的参考答案
     * @param hypothesis 已归一化的识别结果
     * @return 计算结果
     */
    @NonNull
    public static Result evaluateNormalized(@NonNull String reference, @NonNull String hypothesis) {
        List<String> refTokens = splitOnWhitespace(reference);
        List<String> hypTokens = splitOnWhitespace(hypothesis);

        Result result = new Result();
        result.normalizedReference = reference;
        result.normalizedHypothesis = hypothesis;
        result.referenceCount = refTokens.size();
        result.hypothesisCount = hypTokens.size();

        int n = refTokens.size();
        int m = hypTokens.size();
        if (n == 0 && m == 0) {
            result.alignment = Collections.emptyList();
            return result;
        }
        // 一侧为空时无需 DP：要么全是删除，要么全是插入
        if (n == 0 || m == 0) {
            List<Token> alignment = new ArrayList<>(n + m);
            for (String token : refTokens) {
                Token item = new Token();
                item.operation = Operation.DELETE;
                item.referenceToken = token;
                alignment.add(item);
            }
            for (String token : hypTokens) {
                Token item = new Token();
                item.operation = Operation.INSERT;
                item.hypothesisToken = token;
                alignment.add(item);
            }
            result.deletions = n;
            result.insertions = m;
            result.alignment = alignment;
            return result;
        }

        // d[i][j] 为前 i 个参考词与前 j 个识别词的编辑距离，bt 记录回溯方向
        int width = m + 1;
        int[] distance = new int[(n + 1) * width];
        // 操作编码：0 命中 1 替换 2 删除 3 插入
        byte[] backtrace = new byte[(n + 1) * width];
        for (int i = 0; i <= n; i++) {
            distance[i * width] = i;
            backtrace[i * width] = 2;
        }
        for (int j = 0; j <= m; j++) {
            distance[j] = j;
            backtrace[j] = 3;
        }
        backtrace[0] = 0;

        for (int i = 1; i <= n; i++) {
            String refToken = refTokens.get(i - 1);
            for (int j = 1; j <= m; j++) {
                if (refToken.equals(hypTokens.get(j - 1))) {
                    distance[i * width + j] = distance[(i - 1) * width + (j - 1)];
                    backtrace[i * width + j] = 0;
                    continue;
                }
                int substitute = distance[(i - 1) * width + (j - 1)] + 1;
                int delete = distance[(i - 1) * width + j] + 1;
                int insert = distance[i * width + (j - 1)] + 1;
                int best = Math.min(substitute, Math.min(delete, insert));
                distance[i * width + j] = best;
                // 相等时优先替换，其次删除，与 iOS 及参考实现保持一致
                backtrace[i * width + j] = (byte) (best == substitute ? 1 : (best == delete ? 2 : 3));
            }
        }

        List<Token> reversed = new ArrayList<>();
        int i = n;
        int j = m;
        int s = 0;
        int d = 0;
        int ins = 0;
        while (i > 0 || j > 0) {
            byte op = backtrace[i * width + j];
            Token item = new Token();
            if (i > 0 && j > 0 && op == 0) {
                item.operation = Operation.EQUAL;
                item.referenceToken = refTokens.get(i - 1);
                item.hypothesisToken = hypTokens.get(j - 1);
                i--;
                j--;
            } else if (i > 0 && j > 0 && op == 1) {
                item.operation = Operation.SUBSTITUTE;
                item.referenceToken = refTokens.get(i - 1);
                item.hypothesisToken = hypTokens.get(j - 1);
                s++;
                i--;
                j--;
            } else if (i > 0 && (op == 2 || j == 0)) {
                item.operation = Operation.DELETE;
                item.referenceToken = refTokens.get(i - 1);
                d++;
                i--;
            } else {
                item.operation = Operation.INSERT;
                item.hypothesisToken = hypTokens.get(j - 1);
                ins++;
                j--;
            }
            reversed.add(item);
        }
        Collections.reverse(reversed);

        result.substitutions = s;
        result.deletions = d;
        result.insertions = ins;
        result.alignment = reversed;
        return result;
    }

    // ===================== 逐句对齐 =====================

    /**
     * 逐句对比：把识别分段分配给参考行后逐行计算。
     * <p>
     * <b>固有限制</b>：一个识别分段不会被拆开分配给多个参考行。ASR 把多行参考合并成一段输出时
     * （识别段数 &lt; 参考行数），必然有参考行分不到内容而被判为漏识，属于对齐口径造成的虚高。
     * <b>全文 WER 不受此影响，任何情况下都以全文口径为准。</b>
     *
     * @param referenceLines 已归一化的参考行，见 {@link #normalizeReferenceLines(String)}
     * @param segments       识别分段原文，内部会归一化
     * @return 每行一个结果
     */
    @NonNull
    public static List<LineResult> evaluateLines(@NonNull List<String> referenceLines,
                                                 @NonNull List<String> segments) {
        List<LineResult> results = new ArrayList<>();
        if (referenceLines.isEmpty()) {
            return results;
        }
        List<String> normalizedSegments = new ArrayList<>();
        for (String segment : segments) {
            String normalized = normalize(segment);
            if (!normalized.isEmpty()) {
                normalizedSegments.add(normalized);
            }
        }
        List<List<Integer>> assignment = alignSegments(normalizedSegments, referenceLines);

        for (int idx = 0; idx < referenceLines.size(); idx++) {
            StringBuilder pieces = new StringBuilder();
            for (Integer segIndex : assignment.get(idx)) {
                if (segIndex >= 0 && segIndex < normalizedSegments.size()) {
                    if (pieces.length() > 0) {
                        pieces.append(' ');
                    }
                    pieces.append(normalizedSegments.get(segIndex));
                }
            }
            LineResult line = new LineResult();
            line.lineNumber = idx + 1;
            line.reference = referenceLines.get(idx);
            line.hypothesis = pieces.toString();
            line.result = evaluateNormalized(line.reference, line.hypothesis);
            line.missed = line.result.wer() >= MISSED_LINE_WER;
            results.add(line);
        }
        return results;
    }

    /**
     * 把识别分段单调切分并对齐到参考行：动态规划，使总编辑距离最小。
     * 只在对角线附近搜索（band），避免长文本上退化。
     *
     * @param segments       已归一化的识别分段
     * @param referenceLines 已归一化的参考行
     * @return 每个参考行分到的分段下标
     */
    @NonNull
    private static List<List<Integer>> alignSegments(@NonNull List<String> segments,
                                                     @NonNull List<String> referenceLines) {
        int nR = referenceLines.size();
        int nH = segments.size();

        List<List<String>> refTokens = new ArrayList<>(nR);
        for (String line : referenceLines) {
            refTokens.add(splitOnWhitespace(line));
        }
        List<List<String>> hypTokens = new ArrayList<>(nH);
        for (String segment : segments) {
            hypTokens.add(splitOnWhitespace(segment));
        }

        List<List<Integer>> empty = new ArrayList<>(nR);
        for (int i = 0; i < nR; i++) {
            empty.add(Collections.<Integer>emptyList());
        }
        if (nH == 0) {
            return empty;
        }
        // 参考答案只有一行（整段文本）时无需对齐：所有识别分段都属于它。
        // 走 DP 反而会被单行合并段数的上限截断，把多余的识别内容丢掉。
        if (nR == 1) {
            List<Integer> all = new ArrayList<>(nH);
            for (int t = 0; t < nH; t++) {
                all.add(t);
            }
            return Collections.singletonList(all);
        }

        final double inf = Double.MAX_VALUE / 4;
        int width = nH + 1;
        double[] dp = new double[(nR + 1) * width];
        int[] back = new int[(nR + 1) * width];
        Arrays.fill(dp, inf);
        dp[0] = 0;

        double ratio = (double) nH / (double) nR;
        int band = Math.max(MIN_BAND, (int) (ratio * 6));
        // 识别分段数远多于参考行数时（ASR 把一句切得很碎），上限要跟着放大，
        // 否则尾部分段会分不到任何参考行而被丢弃，凭空判成漏识。
        int maxSpan = Math.max(MIN_MAX_SPAN, (int) Math.ceil(ratio) + 4);

        for (int i = 1; i <= nR; i++) {
            int lo = Math.max(0, (int) (i * ratio) - band);
            int hi = Math.min(nH, (int) (i * ratio) + band);
            List<String> reference = refTokens.get(i - 1);
            for (int j = lo; j <= hi; j++) {
                double best = inf;
                int bestSpan = 0;
                for (int span = 0; span <= maxSpan; span++) {
                    int k = j - span;
                    if (k < 0) {
                        break;
                    }
                    double previous = dp[(i - 1) * width + k];
                    if (previous >= inf) {
                        continue;
                    }
                    List<String> merged = new ArrayList<>();
                    for (int t = k; t < j; t++) {
                        merged.addAll(hypTokens.get(t));
                    }
                    double cost = previous + editDistance(reference, merged);
                    if (cost < best) {
                        best = cost;
                        bestSpan = span;
                    }
                }
                dp[i * width + j] = best;
                back[i * width + j] = bestSpan;
            }
        }

        List<List<Integer>> assignment = new ArrayList<>(nR);
        for (int i = 0; i < nR; i++) {
            assignment.add(Collections.<Integer>emptyList());
        }
        int j = nH;
        for (int i = nR; i >= 1; i--) {
            int span = back[i * width + j];
            List<Integer> ids = new ArrayList<>();
            for (int t = j - span; t < j; t++) {
                if (t >= 0) {
                    ids.add(t);
                }
            }
            assignment.set(i - 1, ids);
            j -= span;
            if (j < 0) {
                j = 0;
            }
        }
        return assignment;
    }

    /** 只求编辑距离数值，用于对齐打分。 */
    private static int editDistance(@NonNull List<String> left, @NonNull List<String> right) {
        int n = left.size();
        int m = right.size();
        if (n == 0) {
            return m;
        }
        if (m == 0) {
            return n;
        }
        int[] previous = new int[m + 1];
        int[] current = new int[m + 1];
        for (int j = 0; j <= m; j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            current[0] = i;
            String token = left.get(i - 1);
            for (int j = 1; j <= m; j++) {
                if (token.equals(right.get(j - 1))) {
                    current[j] = previous[j - 1];
                } else {
                    current[j] = Math.min(previous[j - 1], Math.min(previous[j], current[j - 1])) + 1;
                }
            }
            System.arraycopy(current, 0, previous, 0, m + 1);
        }
        return previous[m];
    }

    // ===================== 工具 =====================

    /**
     * 按空白切分并丢弃空串。
     *
     * @param text 待切分文本
     * @return 词列表
     */
    @NonNull
    public static List<String> splitOnWhitespace(@Nullable String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        for (String piece : text.split("\\s+")) {
            if (!piece.isEmpty()) {
                tokens.add(piece);
            }
        }
        return tokens;
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    /** 归一化保留的字符：字母、数字、下划线、空白。其余一律转空格。 */
    private static boolean containsKeepChar(@NonNull String cluster) {
        for (int i = 0; i < cluster.length(); i++) {
            char c = cluster.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || Character.isWhitespace(c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAllDigits(@NonNull String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, String> buildNumberMap() {
        Map<String, String> map = new HashMap<>();
        map.put("null", "0");
        map.put("eins", "1");
        map.put("zwei", "2");
        map.put("drei", "3");
        map.put("vier", "4");
        map.put("fünf", "5");
        map.put("sechs", "6");
        map.put("sieben", "7");
        map.put("acht", "8");
        map.put("neun", "9");
        map.put("zehn", "10");
        map.put("zero", "0");
        map.put("one", "1");
        map.put("two", "2");
        map.put("three", "3");
        map.put("four", "4");
        map.put("five", "5");
        map.put("six", "6");
        map.put("seven", "7");
        map.put("eight", "8");
        map.put("nine", "9");
        map.put("ten", "10");
        return Collections.unmodifiableMap(map);
    }
}
