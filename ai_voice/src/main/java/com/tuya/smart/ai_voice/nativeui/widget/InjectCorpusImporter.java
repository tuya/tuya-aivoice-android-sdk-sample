package com.tuya.smart.ai_voice.nativeui.widget;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

/**
 * 从手机本地目录导入灌流语料。
 *
 * <h3>为什么必须走 SAF</h3>
 * 应用 {@code targetSdk} 已到 36，分区存储下<b>拿不到 {@code /sdcard/Download} 的直接 File 访问</b>，
 * 除非申请 {@code MANAGE_EXTERNAL_STORAGE}——那是敏感权限，调试工具不该用。
 * <p>
 * {@code MediaStore} 这条路也走不通：{@code READ_MEDIA_AUDIO} 只覆盖被索引为音频的文件，
 * 而参考答案是 {@code .txt}，<b>不是媒体文件，MediaStore 根本不暴露它</b>。
 * <p>
 * 所以只剩 SAF，好处是<b>完全不需要任何权限</b>。
 *
 * <h3>为什么用多选文件而不是选目录</h3>
 * 从 Android 11（API 30）起，{@code ACTION_OPEN_DOCUMENT_TREE}
 * <b>禁止授权内部存储根目录与 {@code Download} 目录本身</b>，选中会提示
 * 「无法使用此文件夹，为保护您的隐私，请选择其他文件夹」。而语料通常就放在 {@code Download} 下。
 * <p>
 * {@code ACTION_OPEN_DOCUMENT} 是文件级选择，<b>不受这个 blocklist 限制</b>，
 * 可以直接从 {@code Download} 里挑。代价是要自己记得把 {@code .txt} 参考答案一起选上——
 * 只选了 wav 的语料照样能跑，只是不计算 WER。
 *
 * <h3>为什么要拷贝落地</h3>
 * 灌流要求音频位于底层约定的目录下（见 {@link AudioInjectFileHelper#targetDir}），
 * 所以 wav 必须拷进去，这一条没有选择。参考答案跟着一起拷，是为了让读取路径与内置语料完全一致。
 * <p>
 * 不保留 Uri 现场读也是有意的：Uri 会失效（文件被删、SD 卡拔出、授权被撤销），
 * 跑批中途读不到远比导入时报错难查。落地之后导入语料就是应用私有目录里的普通文件，
 * 不依赖任何授权，重启进程仍在。
 */
public final class InjectCorpusImporter {

    private InjectCorpusImporter() {
    }

    /** 一次导入的结果。 */
    public static final class Result {
        /** 成功落地的 wav 数量。 */
        public final int audioCount;
        /** 成功落地的参考答案数量。 */
        public final int textCount;
        /** 导入目录不可用（外置存储异常）。 */
        public final boolean unavailable;

        Result(int audioCount, int textCount, boolean unavailable) {
            this.audioCount = audioCount;
            this.textCount = textCount;
            this.unavailable = unavailable;
        }

        public boolean isEmpty() {
            return audioCount == 0 && textCount == 0;
        }
    }

    /**
     * 构造多选文件的 Intent。
     * <p>
     * 类型给 {@code *&#47;*} 而不是 {@code audio/*}：wav 在不同机型上的 MIME 猜测结果不一致，
     * 限死类型会让文件被灰掉选不了，而 {@code .txt} 参考答案本来也不是音频。
     * 落地时仍按扩展名过滤，多选了别的文件会被静默忽略。
     *
     * @return 交给 {@code startActivityForResult} 的 Intent
     */
    @NonNull
    public static Intent buildPickFilesIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    /**
     * 导入用户选中的文件。
     * <p>
     * 只认 {@code .wav} 与 {@code .txt}，其余静默忽略。同名覆盖——重复导入同一份语料
     * 应当刷新而不是留两份。
     * <p>
     * 阻塞 IO，<b>必须在子线程调用</b>。
     *
     * @param context 上下文
     * @param uris    选中的文件 Uri
     * @return 导入结果
     */
    @NonNull
    public static Result importFiles(@NonNull Context context, @NonNull List<Uri> uris) {
        File dir = AudioInjectFileHelper.importDir(context);
        if (dir == null) {
            return new Result(0, 0, true);
        }
        ContentResolver resolver = context.getContentResolver();
        int audio = 0;
        int text = 0;
        for (Uri uri : uris) {
            String name = displayName(resolver, uri);
            if (name == null) {
                continue;
            }
            boolean isAudio = AudioInjectFileHelper.isAudioCandidate(name);
            boolean isText = name.toLowerCase(Locale.ROOT)
                    .endsWith(AudioInjectFileHelper.TXT_SUFFIX);
            if (!isAudio && !isText) {
                continue;
            }
            if (!copyOne(resolver, uri, new File(dir, name))) {
                continue;
            }
            if (isAudio) {
                audio++;
            } else {
                text++;
            }
        }
        return new Result(audio, text, false);
    }

    /**
     * 从 Uri 取原始文件名。
     * <p>
     * <b>必须查 {@code DISPLAY_NAME}</b>：文件级 Uri 的 last path segment 往往是
     * {@code msf:1234} 这类文档 ID，拿它当文件名会让「wav 与同名 txt」的配对彻底失效。
     */
    @Nullable
    private static String displayName(@NonNull ContentResolver resolver, @NonNull Uri uri) {
        Cursor cursor = null;
        try {
            cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    private static boolean copyOne(@NonNull ContentResolver resolver, @NonNull Uri source,
                                   @NonNull File target) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = resolver.openInputStream(source);
            if (in == null) {
                return false;
            }
            out = new FileOutputStream(target);
            AudioInjectFileHelper.copy(in, out);
            return true;
        } catch (IOException | SecurityException e) {
            // 拷贝中断会留下半截文件，删掉以免下次被当成可用语料
            if (target.exists()) {
                target.delete();
            }
            return false;
        } finally {
            AudioInjectFileHelper.closeQuietly(in);
            AudioInjectFileHelper.closeQuietly(out);
        }
    }
}
