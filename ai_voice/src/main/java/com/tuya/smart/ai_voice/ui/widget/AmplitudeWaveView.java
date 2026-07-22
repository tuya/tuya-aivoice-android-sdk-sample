package com.tuya.smart.ai_voice.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.tuya.smart.ai_voice.R;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 音频振幅波形 View。
 * <p>
 * 通过 {@link #push(double)} 推入归一化振幅（建议 0~1），内部维护固定长度的环形缓冲，
 * 以竖条柱状图形式从右向左滚动渲染。振幅回调频率较高，{@link #push(double)}
 * 内部对 {@link #invalidate()} 做了节流（约 50ms 一帧），避免过度绘制。
 */
public class AmplitudeWaveView extends View {

    /** 缓冲帧数，越多波形越长。 */
    private static final int MAX_BARS = 90;
    /** 刷新节流间隔（毫秒）。 */
    private static final long MIN_INVALIDATE_INTERVAL_MS = 50L;

    private final Deque<Float> samples = new ArrayDeque<>(MAX_BARS);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint baselinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float barWidth;
    private float barGap;
    private long lastInvalidateTime = 0L;

    public AmplitudeWaveView(Context context) {
        this(context, null);
    }

    public AmplitudeWaveView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AmplitudeWaveView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        barWidth = dpToPx(2.5f);
        barGap = dpToPx(2f);

        barPaint.setStyle(Paint.Style.FILL);
        barPaint.setColor(ContextCompat.getColor(getContext(), R.color.ai_voice_wave_track));

        baselinePaint.setStyle(Paint.Style.STROKE);
        baselinePaint.setStrokeWidth(dpToPx(1f));
        baselinePaint.setColor(ContextCompat.getColor(getContext(), R.color.ai_voice_divider));
    }

    /**
     * 推入一帧振幅。值会被 clamp 到 [0,1]。
     *
     * @param amplitude 原始振幅，一般 &gt;=0；SDK 返回值范围不固定时建议调用方归一化。
     */
    public void push(double amplitude) {
        float normalized = (float) Math.min(1d, Math.max(0d, amplitude));
        synchronized (samples) {
            samples.addLast(normalized);
            while (samples.size() > MAX_BARS) {
                samples.removeFirst();
            }
        }
        throttledInvalidate();
    }

    /** 清空波形。 */
    public void reset() {
        synchronized (samples) {
            samples.clear();
        }
        invalidate();
    }

    /** 设置柱体颜色（双色循环用）。 */
    public void setBarColors(@ColorInt int primary, @ColorInt int secondary) {
        barPaint.setColor(primary);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        float cy = height / 2f;

        // 中线
        canvas.drawLine(0, cy, width, cy, baselinePaint);

        Float[] snapshot;
        synchronized (samples) {
            if (samples.isEmpty()) {
                return;
            }
            snapshot = samples.toArray(new Float[0]);
        }

        float step = barWidth + barGap;
        // 从右向左绘制，最新的在最右侧
        float startX = width - barWidth;
        for (int i = snapshot.length - 1; i >= 0; i--) {
            float amp = snapshot[i];
            float barH = Math.max(barWidth, amp * (height * 0.9f));
            float left = startX - (snapshot.length - 1 - i) * step;
            float top = cy - barH / 2f;
            // 用透明度模拟远近
            int alpha = (int) (60 + 195 * ((float) (i + 1) / snapshot.length));
            barPaint.setAlpha(alpha);
            canvas.drawRoundRect(left, top, left + barWidth, top + barH,
                    barWidth / 2f, barWidth / 2f, barPaint);
        }
        barPaint.setAlpha(255);
    }

    private void throttledInvalidate() {
        long now = System.currentTimeMillis();
        if (now - lastInvalidateTime >= MIN_INVALIDATE_INTERVAL_MS) {
            lastInvalidateTime = now;
            postInvalidateOnAnimation();
        }
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }
}
