package com.tuya.smart.ai_voice.nativeui.widget;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 灌流跑批中一轮的结果。
 * <p>
 * 一轮 = 一个音频灌一遍：下发灌流配置 → 开始录音 → 等灌流播完 → 停止录音 →
 * 拉取转写结果 → 与参考答案算 WER。
 * <p>
 * 失败的轮次同样要保留在结果里——报告中显式标出「超时」「无识别结果」，
 * 比悄悄跳过更有诊断价值：连续多轮无结果通常意味着音频格式不对，而不是识别能力差。
 */
public final class InjectRoundResult {

    /** 一轮的终态。 */
    public enum Status {
        /** 正常拿到识别结果。 */
        SUCCESS,
        /** 超过单轮总超时仍未走完流程。 */
        TIMEOUT,
        /** 流程走完但一条识别结果都没有，最常见的原因是音频不是整型 PCM。 */
        NO_RESULT,
        /** SDK 接口报错。 */
        ERROR
    }

    /** 轮次序号，从 1 开始，跨文件连续。 */
    public int index;
    /** 音频文件名。 */
    public String fileName = "";
    /** 同一音频的第几次重复，从 1 开始。 */
    public int repeatIndex;
    /** 同一音频总共重复几次。 */
    public int repeatTotal;

    public String sourceLanguage = "";
    /** 目标语言；不翻译时为空串。 */
    public String targetLanguage = "";
    public boolean needTts;

    public Status status = Status.ERROR;
    /** 失败原因，成功时为空串。 */
    public String errorMessage = "";

    /** 本轮录音的 recordId，便于回到文件管理页查这条记录。 */
    public String recordId = "";
    /** 本轮耗时（下发配置到拿到结果）。 */
    public long durationMs;

    /** 识别分段原文，用于逐句对比。 */
    public List<String> asrSegments = new ArrayList<>();
    /** 译文分段原文，只记录不评分。 */
    public List<String> translateSegments = new ArrayList<>();

    /** 参考答案原文；无参考答案时为 null，此时不计算 WER。 */
    @Nullable
    public String referenceText;

    /** 全文 WER 结果；无参考答案时为 null。 */
    @Nullable
    public WerCalculator.Result wer;
    /** 逐句结果；无参考答案时为空列表。 */
    public List<WerCalculator.LineResult> lines = Collections.emptyList();

    /** @return 本轮是否计算了 WER */
    public boolean hasWer() {
        return wer != null && wer.referenceCount > 0;
    }

    /** @return 识别原文，分段以换行拼接 */
    @NonNull
    public String asrText() {
        return join(asrSegments);
    }

    /** @return 译文，分段以换行拼接 */
    @NonNull
    public String translateText() {
        return join(translateSegments);
    }

    @NonNull
    private static String join(@NonNull List<String> segments) {
        StringBuilder sb = new StringBuilder();
        for (String segment : segments) {
            if (segment == null || segment.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(segment.trim());
        }
        return sb.toString();
    }
}
