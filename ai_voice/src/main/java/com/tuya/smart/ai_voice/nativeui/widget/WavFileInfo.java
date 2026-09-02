package com.tuya.smart.ai_voice.nativeui.widget;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * WAV 头解析与灌流可用性判定。
 * <p>
 * <b>这是灌流最常见的失败原因</b>：底层把文件内容当作 16kHz / 16bit / 单声道的<b>整型 PCM</b>
 * 直接替换采集流，只支持 {@code audioFormat == 1}。喂进去一个 IEEE float 的 wav，
 * 表现是「灌流在跑，但一条 ASR 都没有」——不报错、不崩溃，只是什么都不出，
 * 跑批时这种静默失败的排查代价极高，所以选文件时就先验一遍。
 * <p>
 * 转换命令：
 * <pre>ffmpeg -i 输入.wav -ar 16000 -ac 1 -c:a pcm_s16le 输出.wav</pre>
 */
public final class WavFileInfo {

    /** 底层唯一支持的 WAV 编码：整型 PCM。 */
    public static final int AUDIO_FORMAT_PCM = 1;

    /** 底层期望的采样率。 */
    public static final int EXPECTED_SAMPLE_RATE = 16000;
    /** 底层期望的声道数。 */
    public static final int EXPECTED_CHANNELS = 1;
    /** 底层期望的位深。 */
    public static final int EXPECTED_BITS = 16;

    /** RIFF / WAVE / fmt 头至少要读到的字节数，超过还没找到 fmt 就认为不是合法 wav。 */
    private static final int MAX_SCAN_BYTES = 1024 * 1024;

    /** 灌流可用性分级。 */
    public enum Level {
        /** 完全符合，正常开始。 */
        OK,
        /** 是整型 PCM，但采样率 / 声道 / 位深与期望不符。底层未必真的不行，允许强行开始。 */
        WARN,
        /** 非整型 PCM 或根本不是 wav，灌流不会有任何输出，直接拦截。 */
        BLOCK
    }

    private final Level level;
    private final int audioFormat;
    private final int channels;
    private final int sampleRate;
    private final int bitsPerSample;
    private final long pcmDataBytes;

    private WavFileInfo(Level level, int audioFormat, int channels, int sampleRate,
                        int bitsPerSample, long pcmDataBytes) {
        this.level = level;
        this.audioFormat = audioFormat;
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.bitsPerSample = bitsPerSample;
        this.pcmDataBytes = pcmDataBytes;
    }

    /**
     * 解析 WAV 头。流由调用方关闭。
     * <p>
     * 阻塞 IO，<b>必须在子线程调用</b>。
     *
     * @param input wav 文件输入流
     * @return 解析结果；不是合法 wav 时返回 {@link Level#BLOCK} 的实例，不抛异常
     */
    @NonNull
    public static WavFileInfo parse(@NonNull InputStream input) {
        try {
            return doParse(new DataInputStream(input));
        } catch (IOException e) {
            return unreadable();
        }
    }

    @NonNull
    private static WavFileInfo unreadable() {
        return new WavFileInfo(Level.BLOCK, 0, 0, 0, 0, 0);
    }

    @NonNull
    private static WavFileInfo doParse(@NonNull DataInputStream in) throws IOException {
        if (readTag(in) != tagOf("RIFF")) {
            return unreadable();
        }
        readIntLe(in);                       // 整个文件大小，用不到
        if (readTag(in) != tagOf("WAVE")) {
            return unreadable();
        }

        int audioFormat = 0;
        int channels = 0;
        int sampleRate = 0;
        int bitsPerSample = 0;
        long dataBytes = 0;
        boolean fmtSeen = false;
        long scanned = 0;

        // chunk 顺序不固定，且 fmt 与 data 之间可能夹着 LIST / fact 等，逐个跳过
        while (scanned < MAX_SCAN_BYTES) {
            int tag;
            try {
                tag = readTag(in);
            } catch (EOFException e) {
                break;
            }
            long size = readIntLe(in) & 0xFFFFFFFFL;
            scanned += 8;

            if (tag == tagOf("fmt ")) {
                audioFormat = readShortLe(in);
                channels = readShortLe(in);
                sampleRate = readIntLe(in);
                readIntLe(in);               // byteRate
                readShortLe(in);             // blockAlign
                bitsPerSample = readShortLe(in);
                fmtSeen = true;
                skipFully(in, size - 16);    // fmt 可能带扩展字段
                scanned += size;
            } else if (tag == tagOf("data")) {
                dataBytes = size;
                break;                       // data 之后就是音频本体，不用再读
            } else {
                skipFully(in, size + (size % 2));  // chunk 按偶数字节对齐
                scanned += size;
            }
        }

        if (!fmtSeen) {
            return unreadable();
        }
        Level level;
        if (audioFormat != AUDIO_FORMAT_PCM) {
            level = Level.BLOCK;
        } else if (sampleRate != EXPECTED_SAMPLE_RATE
                || channels != EXPECTED_CHANNELS
                || bitsPerSample != EXPECTED_BITS) {
            level = Level.WARN;
        } else {
            level = Level.OK;
        }
        return new WavFileInfo(level, audioFormat, channels, sampleRate, bitsPerSample, dataBytes);
    }

    // ===================== 取值 =====================

    @NonNull
    public Level level() {
        return level;
    }

    /** @return true 可以灌流（合规或仅参数不符） */
    public boolean isDecodable() {
        return level != Level.BLOCK;
    }

    /** @return WAV 的 audioFormat 字段，1 为整型 PCM，3 为 IEEE float；无法解析时为 0 */
    public int audioFormat() {
        return audioFormat;
    }

    public int channels() {
        return channels;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int bitsPerSample() {
        return bitsPerSample;
    }

    /**
     * 音频时长，用于推算单轮超时。
     *
     * @return 毫秒；信息不足时返回 0
     */
    public long durationMs() {
        long bytesPerSecond = (long) sampleRate * channels * (bitsPerSample / 8);
        if (bytesPerSecond <= 0 || pcmDataBytes <= 0) {
            return 0;
        }
        return pcmDataBytes * 1000L / bytesPerSecond;
    }

    /**
     * 供界面展示的一行格式摘要，如 {@code PCM 16.0kHz 单声道 16bit}。
     *
     * @return 摘要文本；无法解析时返回 {@code null}
     */
    @Nullable
    public String summary() {
        if (audioFormat == 0 && sampleRate == 0) {
            return null;
        }
        return String.format(java.util.Locale.US, "%s %.1fkHz %dch %dbit",
                audioFormat == AUDIO_FORMAT_PCM ? "PCM" : ("format=" + audioFormat),
                sampleRate / 1000.0, channels, bitsPerSample);
    }

    // ===================== 字节读取 =====================

    private static int tagOf(@NonNull String tag) {
        return (tag.charAt(0) << 24) | (tag.charAt(1) << 16) | (tag.charAt(2) << 8) | tag.charAt(3);
    }

    private static int readTag(@NonNull DataInputStream in) throws IOException {
        return in.readInt();   // 大端读入，与 tagOf 的拼法一致
    }

    private static int readIntLe(@NonNull DataInputStream in) throws IOException {
        int b0 = in.readUnsignedByte();
        int b1 = in.readUnsignedByte();
        int b2 = in.readUnsignedByte();
        int b3 = in.readUnsignedByte();
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static int readShortLe(@NonNull DataInputStream in) throws IOException {
        int b0 = in.readUnsignedByte();
        int b1 = in.readUnsignedByte();
        return b0 | (b1 << 8);
    }

    private static void skipFully(@NonNull DataInputStream in, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw new EOFException();
                }
                remaining--;
                continue;
            }
            remaining -= skipped;
        }
    }
}
