package com.tuya.smart.ai_voice.nativeui.widget;

import android.content.Context;
import android.content.res.AssetFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 灌流音频文件的定位与落地。
 * <p>
 * 灌流生效有一个硬性前提：<b>音频文件必须先落到底层约定的目录里</b>，
 * {@code AudioFrontInputConfig.ReplaceCaptureData.fileName} 传的是相对该目录的路径，
 * 而不是任意的绝对路径或 Uri。目录见 {@link #targetDir(Context)}。
 * <p>
 * 因此 assets 里的样例音频要在灌流前先拷贝过去。可选音频只来自 assets，
 * 自备音频需放进 {@code assets/audio_inject/} 并重新编包。
 * <p>
 * 本类只做文件搬运，不碰任何 SDK 接口——SDK 调用留在
 * {@code NativeAudioInjectActivity} 内，保证那一页可独立阅读。
 * <p>
 * 所有方法都是阻塞 IO，<b>必须在子线程调用</b>。
 */
public final class AudioInjectFileHelper {

    /** 样例音频在 assets 中的目录。 */
    private static final String ASSET_DIR = "audio_inject";

    /** 底层读取灌流文件的根目录：{@code getExternalFilesDir(EXTERNAL_ROOT)/EXTERNAL_SUB}。 */
    private static final String EXTERNAL_ROOT = "LocalAudioFiles";
    private static final String EXTERNAL_SUB = "AvLogger";

    /** 候选文件的扩展名。灌流走 native 替换采集，由底层解容器，这里只放 wav。 */
    private static final String WAV_SUFFIX = ".wav";

    /** 拷贝缓冲区大小。音频文件可达数十 MB，只能分块流式拷贝，不能整体读进内存。 */
    private static final int BUFFER_SIZE = 64 * 1024;

    private AudioInjectFileHelper() {
    }

    /**
     * 底层读取灌流文件的目录，目录不存在时会创建。
     * <p>
     * 这个路径不是随便选的，底层写死在音频通道内部，改成别处灌流不会生效。
     *
     * @param context 上下文
     * @return 目标目录；外置存储不可用或创建失败时返回 {@code null}
     */
    @Nullable
    public static File targetDir(@NonNull Context context) {
        File baseDir = context.getExternalFilesDir(EXTERNAL_ROOT);
        if (baseDir == null) {
            return null;
        }
        File dir = new File(baseDir, EXTERNAL_SUB);
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        return dir;
    }

    /**
     * 列出可选的灌流音频，只取 assets {@code audio_inject/} 下的样例。
     * <p>
     * 不扫目标目录：那里同时是音频链路的 dump 目录，且推送进去的音频没有参考答案，
     * 列出来只会得到无法评分的轮次。所以音频与参考答案统一以 assets 为唯一来源。
     * <p>
     * 只列 wav，判定见 {@link #isAudioCandidate(String)}。
     *
     * @param context 上下文
     * @return 文件名列表，已排序；无可用文件时返回空列表
     */
    @NonNull
    public static List<String> listAvailableFiles(@NonNull Context context) {
        List<String> names = new ArrayList<>();
        try {
            String[] assets = context.getAssets().list(ASSET_DIR);
            if (assets != null) {
                for (String name : assets) {
                    if (isAudioCandidate(name)) {
                        names.add(name);
                    }
                }
            }
        } catch (IOException e) {
            // assets 目录不存在时按「没有内置样例」处理
        }
        Collections.sort(names);
        return names;
    }

    /**
     * 读取某个音频的参考答案，用于计算 WER。
     * <p>
     * 约定是「与 wav 同名的 txt」：{@code sample.wav} 对应 {@code sample.txt}，UTF-8，
     * 建议每行一句、与音频的自然停顿一致——逐句对比的准确度完全取决于这一点。
     * <p>
     * 参考答案与音频一样只放 assets，<b>改动需要重新编包</b>。
     * <p>
     * 阻塞 IO，<b>必须在子线程调用</b>。
     *
     * @param context   上下文
     * @param audioName 音频文件名，含扩展名
     * @return 参考答案全文；没有对应 txt 时返回 {@code null}
     */
    @Nullable
    public static String readReferenceText(@NonNull Context context, @NonNull String audioName) {
        int dot = audioName.lastIndexOf('.');
        String base = dot > 0 ? audioName.substring(0, dot) : audioName;
        InputStream in = null;
        try {
            in = context.getAssets().open(ASSET_DIR + File.separator + base + ".txt");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            String text = out.toString("UTF-8").trim();
            return text.isEmpty() ? null : text;
        } catch (IOException e) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    /**
     * 解析音频的 WAV 头，判断能否灌流。
     * <p>
     * 只读 assets 里的原始样例：目标目录中的同名文件是它的拷贝，读哪一份结果一致，
     * 读 assets 可避免拷贝中断留下的半截文件干扰判定。
     * <p>
     * 阻塞 IO，<b>必须在子线程调用</b>。
     *
     * @param context  上下文
     * @param fileName 音频文件名
     * @return 解析结果；读不到时返回 {@link WavFileInfo.Level#BLOCK} 的实例
     */
    @NonNull
    public static WavFileInfo readWavInfo(@NonNull Context context, @NonNull String fileName) {
        InputStream in = null;
        try {
            in = context.getAssets().open(ASSET_DIR + File.separator + fileName);
            return WavFileInfo.parse(in);
        } catch (IOException e) {
            return WavFileInfo.parse(new ByteArrayInputStream(new byte[0]));
        } finally {
            closeQuietly(in);
        }
    }

    /**
     * 只认 wav 文件。
     * <p>
     * assets 里可能混入 {@code .DS_Store}、{@code .gitkeep} 这类占位文件，
     * 参考答案 txt 也在同一目录。按扩展名收口最省事，顺带把隐藏文件也挡掉了。
     *
     * @param name 文件名
     * @return true 可作为灌流音频的候选
     */
    private static boolean isAudioCandidate(@NonNull String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(WAV_SUFFIX);
    }

    /**
     * 确保指定文件已存在于目标目录：目录中的拷贝与 assets 一致则复用，否则重新拷贝。
     * <p>
     * 以 assets 为准。长度不一致说明目录里躺着的是旧版本或此前手动推送的同名文件，
     * 直接复用会导致灌进去的音频与参考答案对不上，评分结果无从解释，故一律重拷。
     *
     * @param context  上下文
     * @param fileName 文件名
     * @return true 目标目录中已具备该文件
     */
    public static boolean ensureFileReady(@NonNull Context context, @NonNull String fileName) {
        File dir = targetDir(context);
        if (dir == null) {
            return false;
        }
        File target = new File(dir, fileName);
        long assetLength = assetLength(context, fileName);
        if (target.isFile() && target.length() > 0
                && (assetLength < 0 || target.length() == assetLength)) {
            return true;
        }
        return copyFromAssets(context, fileName, target);
    }

    /**
     * assets 中音频的字节数，用于判断目标目录里的拷贝是否仍然一致。
     *
     * @param context  上下文
     * @param fileName 文件名
     * @return 字节数；无法取得（文件不存在或被压缩存储）时返回 -1
     */
    private static long assetLength(@NonNull Context context, @NonNull String fileName) {
        AssetFileDescriptor fd = null;
        try {
            fd = context.getAssets().openFd(ASSET_DIR + File.separator + fileName);
            return fd.getLength();
        } catch (IOException e) {
            return -1L;
        } finally {
            closeQuietly(fd);
        }
    }

    private static boolean copyFromAssets(@NonNull Context context, @NonNull String fileName,
                                          @NonNull File target) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = context.getAssets().open(ASSET_DIR + File.separator + fileName);
            out = new FileOutputStream(target);
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
            return true;
        } catch (IOException e) {
            // 拷贝中断会留下半截文件，删掉以免下次被当成「已就绪」
            if (target.exists()) {
                target.delete();
            }
            return false;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static void closeQuietly(@Nullable java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }
}
