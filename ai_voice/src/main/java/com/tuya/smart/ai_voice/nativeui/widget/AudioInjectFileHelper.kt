package com.tuya.smart.ai_voice.nativeui.widget

import android.content.Context
import android.content.res.AssetFileDescriptor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections

/**
 * 灌流语料的定位与落地。
 * 
 * 
 * 灌流生效有一个硬性前提：**音频文件必须先落到底层约定的目录里**，
 * `AudioFrontInputConfig.ReplaceCaptureData.fileName` 传的是相对该目录的路径，
 * 而不是任意的绝对路径或 Uri。目录见 [.targetDir]。
 * 
 * <h3>两种语料来源</h3>
 * 
 *  * **内置**：`assets/audio_inject/` 下的 wav，灌流前拷进目标目录根下
 *  * **导入**：用户从手机本地目录（如 Download）经 SAF 导入的 wav，
 * 落在目标目录的 [.IMPORT_SUB] 子目录里，见 [InjectCorpusImporter]
 * 
 * 两种来源合并展示，同名时导入优先——导入是用户的显式动作，理应盖过内置样例。
 * 
 * <h3>参考答案</h3>
 * 一律是「与 wav 同名的 txt」。查找顺序与音频一致：先导入目录，再 assets。
 * 
 * 
 * 本类只做文件搬运，不碰任何 SDK 接口——SDK 调用留在页面内，保证单页可独立阅读。
 * 
 * 
 * 所有方法都是阻塞 IO，**必须在子线程调用**。
 */
object AudioInjectFileHelper {
    /** 样例音频在 assets 中的目录。  */
    const val ASSET_DIR: String = "audio_inject"

    /** 底层读取灌流文件的根目录：`getExternalFilesDir(EXTERNAL_ROOT)/EXTERNAL_SUB`。  */
    private const val EXTERNAL_ROOT = "LocalAudioFiles"
    private const val EXTERNAL_SUB = "AvLogger"

    /**
     * 导入语料在目标目录下的子目录。
     * 
     * 
     * 单独开一层的原因：目标目录同时是音频链路的 dump 目录，导入的语料混进去之后
     * 列表、清理、`adb pull` 都会被 dump 产物干扰。
     */
    const val IMPORT_SUB: String = "inject_import"

    /** 候选文件的扩展名。灌流走 native 替换采集，由底层解容器，这里只放 wav。  */
    private const val WAV_SUFFIX = ".wav"

    /** 参考答案的扩展名。  */
    const val TXT_SUFFIX: String = ".txt"

    /** 拷贝缓冲区大小。音频文件可达数十 MB，只能分块流式拷贝，不能整体读进内存。  */
    val BUFFER_SIZE: Int = 64 * 1024

    // ===================== 目录 =====================
    /**
     * 底层读取灌流文件的目录，目录不存在时会创建。
     * 
     * 
     * 这个路径不是随便选的，底层写死在音频通道内部，改成别处灌流不会生效。
     * 
     * @param context 上下文
     * @return 目标目录；外置存储不可用或创建失败时返回 `null`
     */
    @JvmStatic
    fun targetDir(context: Context): File? {
        val baseDir = context.getExternalFilesDir(EXTERNAL_ROOT)
        if (baseDir == null) {
            return null
        }
        val dir = File(baseDir, EXTERNAL_SUB)
        if (!dir.exists() && !dir.mkdirs()) {
            return null
        }
        return dir
    }

    /**
     * 导入语料所在的子目录，不存在时会创建。
     * 
     * @param context 上下文
     * @return 目录；不可用时返回 `null`
     */
    fun importDir(context: Context): File? {
        val parent = targetDir(context)
        if (parent == null) {
            return null
        }
        val dir = File(parent, IMPORT_SUB)
        if (!dir.exists() && !dir.mkdirs()) {
            return null
        }
        return dir
    }

    /**
     * 目标目录的绝对路径，用于在界面上告知使用者文件落在哪。
     * 
     * @param context 上下文
     * @return 绝对路径；不可用时返回空串
     */
    fun targetDirPath(context: Context): String {
        val dir = targetDir(context)
        return if (dir == null) "" else dir.getAbsolutePath()
    }

    // ===================== 列表 =====================
    /**
     * 列出全部可选语料：assets 内置 + 用户导入，同名时导入优先。
     * 
     * 
     * 只列 wav，判定见 [.isAudioCandidate]。
     * 
     * @param context 上下文
     * @return 按文件名排序的语料列表；无可用语料时返回空列表
     */
    @JvmStatic
    fun listCorpus(context: Context): MutableList<Corpus> {
        // 用 baseName 作为键去重：同名时后放入的导入语料覆盖内置的
        val byName: MutableMap<String, Corpus> = LinkedHashMap<String, Corpus>()
        try {
            val assets = context.getAssets().list(ASSET_DIR)
            if (assets != null) {
                for (name in assets) {
                    if (isAudioCandidate(name)) {
                        byName.put(name, Corpus(name, false))
                    }
                }
            }
        } catch (e: IOException) {
            // assets 目录不存在时按「没有内置样例」处理
        }
        val dir = importDir(context)
        val imported = if (dir == null) null else dir.listFiles()
        if (imported != null) {
            for (file in imported) {
                if (file.isFile() && isAudioCandidate(file.getName())) {
                    byName.put(
                        file.getName(),
                        Corpus(IMPORT_SUB + "/" + file.getName(), true)
                    )
                }
            }
        }
        val list: MutableList<Corpus> = ArrayList<Corpus>(byName.values)
        Collections.sort(
            list,
            Comparator { a: Corpus, b: Corpus -> a.baseName().compareTo(b.baseName()) })
        return list
    }

    /**
     * 只认 wav 文件。
     * 
     * 
     * 目标目录同时是音频链路的 dump 目录，assets 里也可能混入 `.DS_Store`、
     * `.gitkeep` 这类占位文件。按扩展名收口最省事，顺带把隐藏文件也挡掉了。
     * 
     * @param name 文件名
     * @return true 可作为灌流音频的候选
     */
    fun isAudioCandidate(name: String): Boolean {
        return name.lowercase().endsWith(WAV_SUFFIX)
    }

    // ===================== 读取 =====================
    /**
     * 读取某条语料的参考答案，用于计算 WER。
     * 
     * 
     * 约定是「与 wav 同名的 txt」：`sample.wav` 对应 `sample.txt`，UTF-8，
     * 建议每行一句、与音频的自然停顿一致——逐句对比的准确度完全取决于这一点。
     * 
     * 
     * 导入语料查导入目录，内置语料查 assets。
     * 
     * 
     * 阻塞 IO，**必须在子线程调用**。
     * 
     * @param context 上下文
     * @param corpus  语料
     * @return 参考答案全文；没有对应 txt 时返回 `null`
     */
    @JvmStatic
    fun readReferenceText(context: Context, corpus: Corpus): String? {
        val base = stripExtension(corpus.baseName()) + TXT_SUFFIX
        var `in`: InputStream? = null
        try {
            if (corpus.imported) {
                val dir = importDir(context)
                val txt = if (dir == null) null else File(dir, base)
                if (txt == null || !txt.isFile()) {
                    return null
                }
                `in` = FileInputStream(txt)
            } else {
                `in` = context.getAssets().open(ASSET_DIR + "/" + base)
            }
            val text = readAll(`in`).trim { it <= ' ' }
            return if (text.isEmpty()) null else text
        } catch (e: IOException) {
            return null
        } finally {
            closeQuietly(`in`)
        }
    }

    /**
     * 解析语料的 WAV 头，判断能否灌流。
     * 
     * 
     * 导入语料读导入目录里的文件；内置语料优先读已拷到目标目录的那份，没有再读 assets。
     * 
     * 
     * 阻塞 IO，**必须在子线程调用**。
     * 
     * @param context 上下文
     * @param corpus  语料
     * @return 解析结果；读不到时返回 [WavFileInfo.Level.BLOCK] 的实例
     */
    @JvmStatic
    fun readWavInfo(context: Context, corpus: Corpus): WavFileInfo {
        val dir = targetDir(context)
        val local = if (dir == null) null else File(dir, corpus.fileName)
        var `in`: InputStream? = null
        try {
            if (local != null && local.isFile()) {
                `in` = FileInputStream(local)
            } else if (corpus.imported) {
                // 导入语料只可能在导入目录里，读不到就是真的没有
                return WavFileInfo.Companion.parse(ByteArrayInputStream(ByteArray(0)))
            } else {
                `in` = context.getAssets().open(ASSET_DIR + "/" + corpus.baseName())
            }
            return WavFileInfo.Companion.parse(`in`)
        } catch (e: IOException) {
            return WavFileInfo.Companion.parse(ByteArrayInputStream(ByteArray(0)))
        } finally {
            closeQuietly(`in`)
        }
    }

    /**
     * 确保语料的音频已就位于目标目录，可以下发给底层。
     * 
     * 
     * 导入语料本来就在目标目录的子目录里，只校验存在性；内置语料需要从 assets 拷过去，
     * 已存在且大小与 assets 一致时跳过拷贝。
     * 
     * @param context 上下文
     * @param corpus  语料
     * @return true 已就位
     */
    @JvmStatic
    fun ensureFileReady(context: Context, corpus: Corpus): Boolean {
        val dir = targetDir(context)
        if (dir == null) {
            return false
        }
        val target = File(dir, corpus.fileName)
        if (corpus.imported) {
            return target.isFile() && target.length() > 0
        }
        val assetLength = assetLength(context, corpus.baseName())
        if (target.isFile() && target.length() > 0 && (assetLength < 0 || target.length() == assetLength)) {
            return true
        }
        return copyFromAssets(context, corpus.baseName(), target)
    }

    /**
     * 删除全部导入语料（音频与参考答案）。
     * 
     * 
     * 导入是累积的，跑久了会堆一堆早就不用的文件，而目标目录在应用私有区、
     * 用户很难自己去清，所以提供一个显式的清理入口。
     * 
     * @param context 上下文
     * @return 删除的文件数
     */
    @JvmStatic
    fun clearImported(context: Context): Int {
        val dir = importDir(context)
        val files = if (dir == null) null else dir.listFiles()
        if (files == null) {
            return 0
        }
        var removed = 0
        for (file in files) {
            if (file.isFile() && file.delete()) {
                removed++
            }
        }
        return removed
    }

    // ===================== 内部 =====================
    fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    /**
     * assets 里该文件的长度，用于判断目标目录里的副本是否已过期。
     * 
     * @return 字节数；不存在或拿不到时返回 -1
     */
    private fun assetLength(context: Context, fileName: String): Long {
        var fd: AssetFileDescriptor? = null
        try {
            fd = context.getAssets().openFd(ASSET_DIR + "/" + fileName)
            return fd.getLength()
        } catch (e: IOException) {
            return -1
        } finally {
            if (fd != null) {
                try {
                    fd.close()
                } catch (ignored: IOException) {
                }
            }
        }
    }

    private fun copyFromAssets(
        context: Context, fileName: String,
        target: File
    ): Boolean {
        var `in`: InputStream? = null
        var out: OutputStream? = null
        try {
            `in` = context.getAssets().open(ASSET_DIR + "/" + fileName)
            out = FileOutputStream(target)
            copy(`in`, out)
            return true
        } catch (e: IOException) {
            // 拷贝中断会留下半截文件，删掉以免下次被当成「已就绪」
            if (target.exists()) {
                target.delete()
            }
            return false
        } finally {
            closeQuietly(`in`)
            closeQuietly(out)
        }
    }

    @Throws(IOException::class)
    fun copy(`in`: InputStream, out: OutputStream) {
        val buffer = ByteArray(BUFFER_SIZE)
        var read: Int
        while ((`in`.read(buffer).also { read = it }) != -1) {
            out.write(buffer, 0, read)
        }
        out.flush()
    }

    @Throws(IOException::class)
    private fun readAll(`in`: InputStream): String {
        val out = ByteArrayOutputStream()
        copy(`in`, out)
        return out.toString("UTF-8")
    }

    fun closeQuietly(closeable: Closeable?) {
        if (closeable == null) {
            return
        }
        try {
            closeable.close()
        } catch (ignored: IOException) {
        }
    }

    /**
     * 一条可选语料。
     * 
     * 
     * [.fileName] 是**下发给 SDK 的相对路径**：内置语料是裸文件名，
     * 导入语料带 `inject_import/` 前缀。展示与报告一律用它，
     * 保证界面上看到的名字和磁盘上的一致，`adb pull` 时对得上号。
     */
    class Corpus internal constructor(
        /** 相对目标目录的路径，直接用作 `ReplaceCaptureData.fileName`。  */
        @JvmField val fileName: String,
        /** 是否来自用户导入。  */
        @JvmField val imported: Boolean
    ) {
        /** @return 不含目录前缀的文件名，用于配对同名 txt
         */
        fun baseName(): String {
            val slash = fileName.lastIndexOf('/')
            return if (slash < 0) fileName else fileName.substring(slash + 1)
        }
    }
}
