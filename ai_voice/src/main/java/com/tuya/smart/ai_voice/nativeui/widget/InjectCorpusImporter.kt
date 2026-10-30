package com.tuya.smart.ai_voice.nativeui.widget

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 从手机本地目录导入灌流语料。
 * 
 * <h3>为什么必须走 SAF</h3>
 * 应用 `targetSdk` 已到 36，分区存储下**拿不到 `/sdcard/Download` 的直接 File 访问**，
 * 除非申请 `MANAGE_EXTERNAL_STORAGE`——那是敏感权限，调试工具不该用。
 * 
 * 
 * `MediaStore` 这条路也走不通：`READ_MEDIA_AUDIO` 只覆盖被索引为音频的文件，
 * 而参考答案是 `.txt`，**不是媒体文件，MediaStore 根本不暴露它**。
 * 
 * 
 * 所以只剩 SAF，好处是**完全不需要任何权限**。
 * 
 * <h3>为什么用多选文件而不是选目录</h3>
 * 从 Android 11（API 30）起，`ACTION_OPEN_DOCUMENT_TREE`
 * **禁止授权内部存储根目录与 `Download` 目录本身**，选中会提示
 * 「无法使用此文件夹，为保护您的隐私，请选择其他文件夹」。而语料通常就放在 `Download` 下。
 * 
 * 
 * `ACTION_OPEN_DOCUMENT` 是文件级选择，**不受这个 blocklist 限制**，
 * 可以直接从 `Download` 里挑。代价是要自己记得把 `.txt` 参考答案一起选上——
 * 只选了 wav 的语料照样能跑，只是不计算 WER。
 * 
 * <h3>为什么要拷贝落地</h3>
 * 灌流要求音频位于底层约定的目录下（见 [AudioInjectFileHelper.targetDir]），
 * 所以 wav 必须拷进去，这一条没有选择。参考答案跟着一起拷，是为了让读取路径与内置语料完全一致。
 * 
 * 
 * 不保留 Uri 现场读也是有意的：Uri 会失效（文件被删、SD 卡拔出、授权被撤销），
 * 跑批中途读不到远比导入时报错难查。落地之后导入语料就是应用私有目录里的普通文件，
 * 不依赖任何授权，重启进程仍在。
 */
object InjectCorpusImporter {
    /**
     * 构造多选文件的 Intent。
     * 
     * 
     * 类型给 `*&#47;*` 而不是 `audio/ *`：wav 在不同机型上的 MIME 猜测结果不一致，
     * 限死类型会让文件被灰掉选不了，而 `.txt` 参考答案本来也不是音频。
     * 落地时仍按扩展名过滤，多选了别的文件会被静默忽略。
     * 
     * @return 交给 `startActivityForResult` 的 Intent
     */
    @JvmStatic
    fun buildPickFilesIntent(): Intent {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.setType("*/*")
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return intent
    }

    /**
     * 导入用户选中的文件。
     * 
     * 
     * 只认 `.wav` 与 `.txt`，其余静默忽略。同名覆盖——重复导入同一份语料
     * 应当刷新而不是留两份。
     * 
     * 
     * 阻塞 IO，**必须在子线程调用**。
     * 
     * @param context 上下文
     * @param uris    选中的文件 Uri
     * @return 导入结果
     */
    fun importFiles(context: Context, uris: MutableList<Uri>): Result {
        val dir = AudioInjectFileHelper.importDir(context)
        if (dir == null) {
            return Result(0, 0, true)
        }
        val resolver = context.getContentResolver()
        var audio = 0
        var text = 0
        for (uri in uris) {
            val name = displayName(resolver, uri)
            if (name == null) {
                continue
            }
            val isAudio = AudioInjectFileHelper.isAudioCandidate(name)
            val isText = name.lowercase()
                .endsWith(AudioInjectFileHelper.TXT_SUFFIX)
            if (!isAudio && !isText) {
                continue
            }
            if (!copyOne(resolver, uri, File(dir, name))) {
                continue
            }
            if (isAudio) {
                audio++
            } else {
                text++
            }
        }
        return Result(audio, text, false)
    }

    /**
     * 从 Uri 取原始文件名。
     * 
     * 
     * **必须查 `DISPLAY_NAME`**：文件级 Uri 的 last path segment 往往是
     * `msf:1234` 这类文档 ID，拿它当文件名会让「wav 与同名 txt」的配对彻底失效。
     */
    private fun displayName(resolver: ContentResolver, uri: Uri): String? {
        var cursor: Cursor? = null
        try {
            cursor = resolver.query(
                uri, arrayOf<String>(OpenableColumns.DISPLAY_NAME),
                null, null, null
            )
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0)
            }
        } catch (e: Exception) {
            return null
        } finally {
            if (cursor != null) {
                cursor.close()
            }
        }
        return null
    }

    private fun copyOne(
        resolver: ContentResolver, source: Uri,
        target: File
    ): Boolean {
        var `in`: InputStream? = null
        var out: OutputStream? = null
        try {
            `in` = resolver.openInputStream(source)
            if (`in` == null) {
                return false
            }
            out = FileOutputStream(target)
            AudioInjectFileHelper.copy(`in`, out)
            return true
        } catch (e: IOException) {
            // 拷贝中断会留下半截文件，删掉以免下次被当成可用语料
            if (target.exists()) {
                target.delete()
            }
            return false
        } catch (e: SecurityException) {
            if (target.exists()) {
                target.delete()
            }
            return false
        } finally {
            AudioInjectFileHelper.closeQuietly(`in`)
            AudioInjectFileHelper.closeQuietly(out)
        }
    }

    /** 一次导入的结果。  */
    class Result internal constructor(
        /** 成功落地的 wav 数量。  */
        @JvmField val audioCount: Int,
        /** 成功落地的参考答案数量。  */
        @JvmField val textCount: Int,
        /** 导入目录不可用（外置存储异常）。  */
        @JvmField val unavailable: Boolean
    ) {
        val isEmpty: Boolean
            get() = audioCount == 0 && textCount == 0
    }
}
