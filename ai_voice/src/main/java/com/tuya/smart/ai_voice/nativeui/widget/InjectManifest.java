package com.tuya.smart.ai_voice.nativeui.widget;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 灌流跑批清单。描述每个音频用什么语言、开哪些能力。
 * <p>
 * 清单固定放在 assets 的 {@code audio_inject/manifest.json}，内容是<b>裸数组</b>：
 * <pre>
 * [
 *   {
 *     "name": "sample.wav",
 *     "originalLanguage": "de",
 *     "targetLanguage": "en",
 *     "enableRealtimeTranscription": true,
 *     "enableRealtimeTranslation": true,
 *     "needTts": false
 *   }
 * ]
 * </pre>
 * <p>
 * <b>目标语言只能填一个</b>：{@code RecordParamsV2.setTargetLanguage} 是单值，
 * 一次录音只能翻一种，所以这里刻意用单数字段名，避免误以为能填数组。
 * <p>
 * 清单只走 assets，不支持外部推送——它是配置、改动少，跟着包走可以保证
 * 跑批参数是确定的。参考答案（长文本、要反复校准）同样在 assets，见
 * {@link AudioInjectFileHelper#readReferenceText(Context, String)}。
 * <p>
 * <b>清单里没有条目的音频无法参与跑批</b>：源语言只能来自清单，缺了它就只能猜，
 * 猜错会让整轮数据不可信。这类文件在批量页会置灰并标注原因。
 */
public final class InjectManifest {

    /** 清单文件在 assets 中的完整路径。 */
    private static final String MANIFEST_PATH = "audio_inject/manifest.json";

    private InjectManifest() {
    }

    /** 单个音频的跑批配置。 */
    public static final class Entry {
        /** 音频文件名，与 wav 文件名完全一致。 */
        public final String name;
        /** 源语言，ASR 用。 */
        public final String originalLanguage;
        /** 目标语言；为空表示不翻译。 */
        @Nullable
        public final String targetLanguage;
        public final boolean enableRealtimeTranscription;
        public final boolean enableRealtimeTranslation;
        /** 是否把译文合成语音播回。无法自动评分，只记录开关状态供解释耗时。 */
        public final boolean needTts;

        Entry(String name, String originalLanguage, @Nullable String targetLanguage,
              boolean enableRealtimeTranscription, boolean enableRealtimeTranslation,
              boolean needTts) {
            this.name = name;
            this.originalLanguage = originalLanguage;
            this.targetLanguage = targetLanguage;
            this.enableRealtimeTranscription = enableRealtimeTranscription;
            this.enableRealtimeTranslation = enableRealtimeTranslation;
            this.needTts = needTts;
        }

        /**
         * 是否真的需要翻译。
         * <p>
         * 目标语言与源语言相同等于没翻，下发给 SDK 只会白跑一趟。
         *
         * @return true 本轮需要翻译
         */
        public boolean shouldTranslate() {
            return enableRealtimeTranslation
                    && targetLanguage != null
                    && !targetLanguage.isEmpty()
                    && !targetLanguage.equals(originalLanguage);
        }
    }

    /**
     * 读取清单。阻塞 IO，<b>必须在子线程调用</b>。
     * <p>
     * 三个布尔字段缺省一律为 {@code true}：清单是给跑批用的，写进来的音频就是要完整跑一遍，
     * 缺省关掉反而会让人以为「配了却没生效」。真要关某项就显式写 {@code false}。
     * 注意翻译与 TTS 还受 {@link Entry#shouldTranslate()} 约束——没填
     * {@code targetLanguage} 时它们自然不会生效。
     * <p>
     * 清单缺失、格式错误、条目缺 {@code name} 或 {@code originalLanguage} 时，
     * 该条目被丢弃而不是抛异常——跑批页会把「清单中无此条目」如实展示出来，
     * 比崩在解析阶段更有诊断价值。
     *
     * @param context 上下文
     * @return 音频名 → 配置，按清单原始顺序；读不到时返回空 Map
     */
    @NonNull
    public static Map<String, Entry> load(@NonNull Context context) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        String json = readAsset(context);
        if (json == null || json.trim().isEmpty()) {
            return entries;
        }
        JSONArray array;
        try {
            array = JSON.parseArray(json);
        } catch (Exception e) {
            // 格式不合法按「没有清单」处理，页面上所有文件都会显示为无配置
            return entries;
        }
        if (array == null) {
            return entries;
        }
        for (int i = 0; i < array.size(); i++) {
            JSONObject item = array.getJSONObject(i);
            if (item == null) {
                continue;
            }
            String name = trimToNull(item.getString("name"));
            String originalLanguage = trimToNull(item.getString("originalLanguage"));
            if (name == null || originalLanguage == null) {
                continue;
            }
            entries.put(name, new Entry(
                    name,
                    originalLanguage,
                    trimToNull(item.getString("targetLanguage")),
                    readBool(item, "enableRealtimeTranscription", true),
                    readBool(item, "enableRealtimeTranslation", true),
                    readBool(item, "needTts", true)));
        }
        return Collections.unmodifiableMap(entries);
    }

    private static boolean readBool(@NonNull JSONObject item, @NonNull String key, boolean fallback) {
        Boolean value = item.getBoolean(key);
        return value == null ? fallback : value;
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Nullable
    private static String readAsset(@NonNull Context context) {
        InputStream in = null;
        try {
            in = context.getAssets().open(MANIFEST_PATH);
            byte[] buffer = new byte[in.available()];
            int read = in.read(buffer);
            return read <= 0 ? null : new String(buffer, 0, read, "UTF-8");
        } catch (IOException e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
