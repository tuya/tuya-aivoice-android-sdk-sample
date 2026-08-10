package com.tuya.smart.ai_voice.nativeui.widget;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tuya.smart.ai_voice.R;

import java.util.HashMap;
import java.util.Map;

/**
 * 录音相关错误码 → 可读提示。
 * <p>
 * 录音链路的错误码集中在 {@code 10001}~{@code 10101}，语义细分很多，
 * 直接把裸码或底层 {@code errorMsg} 抛给用户没有意义。
 * <p>
 * 两处特殊约定：
 * <ul>
 *     <li>{@code 39001}~{@code 39012} 是 AI 基座错误，整段统一提示「服务繁忙」，不逐个区分</li>
 *     <li>{@code 10061} 在<b>电话录音模式</b>下语义不同——表示「尚未进入通话」而非设备异常，
 *         需调用方按当前模式自行判断，见 {@link #messageOf(Context, String, boolean)}</li>
 * </ul>
 */
public final class RecordErrorCode {

    /** AI 基座错误码区间下界（含）。 */
    private static final int AI_BASE_MIN = 39001;
    /** AI 基座错误码区间上界（含）。 */
    private static final int AI_BASE_MAX = 39012;

    /** 设备处于录音中或异常状态；电话录音模式下表示尚未进入通话。 */
    public static final int CODE_DEVICE_BUSY_OR_NOT_IN_CALL = 10061;

    /** 错误码 → 文案资源 ID。 */
    private static final Map<Integer, Integer> MESSAGES = new HashMap<>();

    static {
        MESSAGES.put(9006, R.string.native_err_9006);
        MESSAGES.put(10001, R.string.native_err_10001);
        MESSAGES.put(10002, R.string.native_err_10002);
        MESSAGES.put(10011, R.string.native_err_10011);
        MESSAGES.put(10021, R.string.native_err_10021);
        MESSAGES.put(10031, R.string.native_err_10031);
        MESSAGES.put(10032, R.string.native_err_10031);
        MESSAGES.put(10033, R.string.native_err_10033);
        MESSAGES.put(10041, R.string.native_err_10041);
        MESSAGES.put(10042, R.string.native_err_10042);
        MESSAGES.put(10043, R.string.native_err_10043);
        MESSAGES.put(10044, R.string.native_err_10044);
        MESSAGES.put(10045, R.string.native_err_10045);
        MESSAGES.put(10046, R.string.native_err_10046);
        MESSAGES.put(10047, R.string.native_err_10042);
        MESSAGES.put(10048, R.string.native_err_10045);
        MESSAGES.put(10049, R.string.native_err_10049);
        MESSAGES.put(10050, R.string.native_err_10050);
        MESSAGES.put(CODE_DEVICE_BUSY_OR_NOT_IN_CALL, R.string.native_err_10061);
        MESSAGES.put(10062, R.string.native_err_10062);
        MESSAGES.put(10063, R.string.native_err_10002);
        MESSAGES.put(10064, R.string.native_err_10064);
        MESSAGES.put(10071, R.string.native_err_10071);
        MESSAGES.put(10072, R.string.native_err_10071);
        MESSAGES.put(10073, R.string.native_err_10071);
        MESSAGES.put(10081, R.string.native_err_10081);
        MESSAGES.put(10082, R.string.native_err_10082);
        MESSAGES.put(10083, R.string.native_err_10083);
        MESSAGES.put(10100, R.string.native_err_10001);
        MESSAGES.put(10101, R.string.native_err_10001);
    }

    private RecordErrorCode() {
    }

    /**
     * 把错误码转成可读提示。
     *
     * @param context   上下文
     * @param code      错误码，可能为 null 或非数字
     * @param isCallMode 当前是否为电话录音模式，影响 {@code 10061} 的解释
     * @return 可读提示；未收录的码返回带原始码的兜底文案
     */
    @NonNull
    public static String messageOf(@NonNull Context context, @Nullable String code,
                                   boolean isCallMode) {
        if (TextUtils.isEmpty(code)) {
            return context.getString(R.string.native_err_unknown, "-");
        }
        int value;
        try {
            value = Integer.parseInt(code.trim());
        } catch (NumberFormatException e) {
            return context.getString(R.string.native_err_unknown, code);
        }
        // 电话录音模式下 10061 表示「尚未进入通话」，与设备异常是两回事
        if (value == CODE_DEVICE_BUSY_OR_NOT_IN_CALL && isCallMode) {
            return context.getString(R.string.native_err_10061_call_mode);
        }
        if (value >= AI_BASE_MIN && value <= AI_BASE_MAX) {
            return context.getString(R.string.native_err_ai_base);
        }
        Integer resId = MESSAGES.get(value);
        return resId == null
                ? context.getString(R.string.native_err_unknown, code)
                : context.getString(resId);
    }
}
