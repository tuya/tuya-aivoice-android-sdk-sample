package com.tuya.smart.ai_voice.nativeui.widget

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/**
 * WAV 头解析与灌流可用性判定。
 * 
 * 
 * **这是灌流最常见的失败原因**：底层把文件内容当作 16kHz / 16bit / 单声道的**整型 PCM**
 * 直接替换采集流，只支持 `audioFormat == 1`。喂进去一个 IEEE float 的 wav，
 * 表现是「灌流在跑，但一条 ASR 都没有」——不报错、不崩溃，只是什么都不出，
 * 跑批时这种静默失败的排查代价极高，所以选文件时就先验一遍。
 * 
 * 
 * 转换命令：
 * <pre>ffmpeg -i 输入.wav -ar 16000 -ac 1 -c:a pcm_s16le 输出.wav</pre>
 */
class WavFileInfo private constructor(
    private val level: Level,
    private val audioFormat: Int,
    private val channels: Int,
    private val sampleRate: Int,
    private val bitsPerSample: Int,
    private val pcmDataBytes: Long
) {
    /** 灌流可用性分级。  */
    enum class Level {
        /** 完全符合，正常开始。  */
        OK,

        /** 是整型 PCM，但采样率 / 声道 / 位深与期望不符。底层未必真的不行，允许强行开始。  */
        WARN,

        /** 非整型 PCM 或根本不是 wav，灌流不会有任何输出，直接拦截。  */
        BLOCK
    }

    // ===================== 取值 =====================
    fun level(): Level {
        return level
    }

    val isDecodable: Boolean
        /** @return true 可以灌流（合规或仅参数不符）
         */
        get() = level != Level.BLOCK

    /** @return WAV 的 audioFormat 字段，1 为整型 PCM，3 为 IEEE float；无法解析时为 0
     */
    fun audioFormat(): Int {
        return audioFormat
    }

    fun channels(): Int {
        return channels
    }

    fun sampleRate(): Int {
        return sampleRate
    }

    fun bitsPerSample(): Int {
        return bitsPerSample
    }

    /**
     * 音频时长，用于推算单轮超时。
     * 
     * @return 毫秒；信息不足时返回 0
     */
    fun durationMs(): Long {
        val bytesPerSecond = sampleRate.toLong() * channels * (bitsPerSample / 8)
        if (bytesPerSecond <= 0 || pcmDataBytes <= 0) {
            return 0
        }
        return pcmDataBytes * 1000L / bytesPerSecond
    }

    /**
     * 供界面展示的一行格式摘要，如 `PCM 16.0kHz 单声道 16bit`。
     * 
     * @return 摘要文本；无法解析时返回 `null`
     */
    fun summary(): String? {
        if (audioFormat == 0 && sampleRate == 0) {
            return null
        }
        return String.format(
            Locale.US, "%s %.1fkHz %dch %dbit",
            if (audioFormat == AUDIO_FORMAT_PCM) "PCM" else ("format=" + audioFormat),
            sampleRate / 1000.0, channels, bitsPerSample
        )
    }

    companion object {
        /** 底层唯一支持的 WAV 编码：整型 PCM。  */
        const val AUDIO_FORMAT_PCM: Int = 1

        /** 底层期望的采样率。  */
        const val EXPECTED_SAMPLE_RATE: Int = 16000

        /** 底层期望的声道数。  */
        const val EXPECTED_CHANNELS: Int = 1

        /** 底层期望的位深。  */
        const val EXPECTED_BITS: Int = 16

        /** RIFF / WAVE / fmt 头至少要读到的字节数，超过还没找到 fmt 就认为不是合法 wav。  */
        private val MAX_SCAN_BYTES = 1024 * 1024

        /**
         * 解析 WAV 头。流由调用方关闭。
         * 
         * 
         * 阻塞 IO，**必须在子线程调用**。
         * 
         * @param input wav 文件输入流
         * @return 解析结果；不是合法 wav 时返回 [Level.BLOCK] 的实例，不抛异常
         */
        fun parse(input: InputStream): WavFileInfo {
            try {
                return doParse(DataInputStream(input))
            } catch (e: IOException) {
                return unreadable()
            }
        }

        private fun unreadable(): WavFileInfo {
            return WavFileInfo(Level.BLOCK, 0, 0, 0, 0, 0)
        }

        @Throws(IOException::class)
        private fun doParse(`in`: DataInputStream): WavFileInfo {
            if (readTag(`in`) != tagOf("RIFF")) {
                return unreadable()
            }
            readIntLe(`in`) // 整个文件大小，用不到
            if (readTag(`in`) != tagOf("WAVE")) {
                return unreadable()
            }

            var audioFormat = 0
            var channels = 0
            var sampleRate = 0
            var bitsPerSample = 0
            var dataBytes: Long = 0
            var fmtSeen = false
            var scanned: Long = 0

            // chunk 顺序不固定，且 fmt 与 data 之间可能夹着 LIST / fact 等，逐个跳过
            while (scanned < MAX_SCAN_BYTES) {
                val tag: Int
                try {
                    tag = readTag(`in`)
                } catch (e: EOFException) {
                    break
                }
                val size = readIntLe(`in`).toLong() and 0xFFFFFFFFL
                scanned += 8

                if (tag == tagOf("fmt ")) {
                    audioFormat = readShortLe(`in`)
                    channels = readShortLe(`in`)
                    sampleRate = readIntLe(`in`)
                    readIntLe(`in`) // byteRate
                    readShortLe(`in`) // blockAlign
                    bitsPerSample = readShortLe(`in`)
                    fmtSeen = true
                    skipFully(`in`, size - 16) // fmt 可能带扩展字段
                    scanned += size
                } else if (tag == tagOf("data")) {
                    dataBytes = size
                    break // data 之后就是音频本体，不用再读
                } else {
                    skipFully(`in`, size + (size % 2)) // chunk 按偶数字节对齐
                    scanned += size
                }
            }

            if (!fmtSeen) {
                return unreadable()
            }
            val level: Level?
            if (audioFormat != AUDIO_FORMAT_PCM) {
                level = Level.BLOCK
            } else if (sampleRate != EXPECTED_SAMPLE_RATE || channels != EXPECTED_CHANNELS || bitsPerSample != EXPECTED_BITS) {
                level = Level.WARN
            } else {
                level = Level.OK
            }
            return WavFileInfo(level, audioFormat, channels, sampleRate, bitsPerSample, dataBytes)
        }

        // ===================== 字节读取 =====================
        private fun tagOf(tag: String): Int {
            return (tag.get(0).code shl 24) or (tag.get(1).code shl 16) or (tag.get(2).code shl 8) or tag.get(
                3
            ).code
        }

        @Throws(IOException::class)
        private fun readTag(`in`: DataInputStream): Int {
            return `in`.readInt() // 大端读入，与 tagOf 的拼法一致
        }

        @Throws(IOException::class)
        private fun readIntLe(`in`: DataInputStream): Int {
            val b0 = `in`.readUnsignedByte()
            val b1 = `in`.readUnsignedByte()
            val b2 = `in`.readUnsignedByte()
            val b3 = `in`.readUnsignedByte()
            return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        }

        @Throws(IOException::class)
        private fun readShortLe(`in`: DataInputStream): Int {
            val b0 = `in`.readUnsignedByte()
            val b1 = `in`.readUnsignedByte()
            return b0 or (b1 shl 8)
        }

        @Throws(IOException::class)
        private fun skipFully(`in`: DataInputStream, count: Long) {
            var remaining = count
            while (remaining > 0) {
                val skipped = `in`.skip(remaining)
                if (skipped <= 0) {
                    if (`in`.read() < 0) {
                        throw EOFException()
                    }
                    remaining--
                    continue
                }
                remaining -= skipped
            }
        }
    }
}
