package com.tuya.smart.ai_voice.nativeui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.tuya.smart.ai_voice.R
import java.util.ArrayDeque
import java.util.Deque
import kotlin.math.max
import kotlin.math.min

/**
 * 音频振幅波形 View。
 * 
 * 
 * 通过 [.push] 推入归一化振幅（建议 0~1），内部维护固定长度的环形缓冲，
 * 以竖条柱状图形式从右向左滚动渲染。振幅回调频率较高，[.push]
 * 内部对 [.invalidate] 做了节流（约 50ms 一帧），避免过度绘制。
 */
class AmplitudeWaveView @JvmOverloads constructor(
    context: Context?,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val samples: Deque<Float?> = ArrayDeque<Float?>(MAX_BARS)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var barWidth = 0f
    private var barGap = 0f
    private var lastInvalidateTime = 0L

    init {
        init()
    }

    private fun init() {
        barWidth = dpToPx(2.5f)
        barGap = dpToPx(2f)

        barPaint.setStyle(Paint.Style.FILL)
        barPaint.setColor(ContextCompat.getColor(getContext(), R.color.ai_voice_wave_track))

        baselinePaint.setStyle(Paint.Style.STROKE)
        baselinePaint.setStrokeWidth(dpToPx(1f))
        baselinePaint.setColor(ContextCompat.getColor(getContext(), R.color.ai_voice_divider))
    }

    /**
     * 推入一帧振幅。值会被 clamp 到 [0,1]。
     * 
     * @param amplitude 原始振幅，一般 &gt;=0；SDK 返回值范围不固定时建议调用方归一化。
     */
    fun push(amplitude: Double) {
        val normalized = min(1.0, max(0.0, amplitude)).toFloat()
        synchronized(samples) {
            samples.addLast(normalized)
            while (samples.size > MAX_BARS) {
                samples.removeFirst()
            }
        }
        throttledInvalidate()
    }

    /** 清空波形。  */
    fun reset() {
        synchronized(samples) {
            samples.clear()
        }
        invalidate()
    }

    /** 设置柱体颜色（双色循环用）。  */
    fun setBarColors(@ColorInt primary: Int, @ColorInt secondary: Int) {
        barPaint.setColor(primary)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = getWidth()
        val height = getHeight()
        if (width <= 0 || height <= 0) {
            return
        }
        val cy = height / 2f

        // 中线
        canvas.drawLine(0f, cy, width.toFloat(), cy, baselinePaint)

        val snapshot: Array<Float?>?
        synchronized(samples) {
            if (samples.isEmpty()) {
                return
            }
            snapshot = samples.toTypedArray<Float?>()
        }

        val step = barWidth + barGap
        // 从右向左绘制，最新的在最右侧
        val startX = width - barWidth
        val data = snapshot ?: return
        for (i in data.indices.reversed()) {
            val amp: Float = data[i] ?: 0f
            val barH = max(barWidth, amp * (height * 0.9f))
            val left = startX - (data.size - 1 - i) * step
            val top = cy - barH / 2f
            // 用透明度模拟远近
            val alpha = (60 + 195 * ((i + 1).toFloat() / data.size)).toInt()
            barPaint.setAlpha(alpha)
            canvas.drawRoundRect(
                left, top, left + barWidth, top + barH,
                barWidth / 2f, barWidth / 2f, barPaint
            )
        }
        barPaint.setAlpha(255)
    }

    private fun throttledInvalidate() {
        val now = System.currentTimeMillis()
        if (now - lastInvalidateTime >= MIN_INVALIDATE_INTERVAL_MS) {
            lastInvalidateTime = now
            postInvalidateOnAnimation()
        }
    }

    private fun dpToPx(dp: Float): Float {
        return dp * getResources().getDisplayMetrics().density
    }

    companion object {
        /** 缓冲帧数，越多波形越长。  */
        private const val MAX_BARS = 90

        /** 刷新节流间隔（毫秒）。  */
        private const val MIN_INVALIDATE_INTERVAL_MS = 50L
    }
}
