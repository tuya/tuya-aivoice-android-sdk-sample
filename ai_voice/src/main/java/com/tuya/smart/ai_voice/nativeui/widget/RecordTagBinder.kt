package com.tuya.smart.ai_voice.nativeui.widget

import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.tuya.smart.ai_voice.R
import kotlin.math.min

/**
 * 把 `RecordTransferResultBean.tags` 渲染到 [ChipGroup] 上。
 * 
 * 
 * 列表页与详情页共用一套渲染，差别只有**展示上限**：
 * 
 *  * 列表页只做扫读，超出 [.LIMIT_LIST] 个即折叠成 `+N`；
 *  * 详情页是细读，用 [.NO_LIMIT] 全部展示。
 * 
 * 标签**不可点击**：列表 item 里若 chip 消费了点击事件，用户点卡片进详情就会失灵。
 * 标签的增删改在 `NativeRecordActionSheet`（`updateRecordTagResult`）里，本类只负责展示。
 * 
 * 
 * ⚠️ [Chip] 是 Material 组件，要求宿主主题为 MaterialComponents 系。
 * 本工程的 `AppTheme` 为此继承了 `Theme.MaterialComponents...Bridge`，
 * 换回纯 AppCompat 主题会导致这里**运行时崩溃**。
 */
object RecordTagBinder {
    /** 列表页的标签展示上限，超出折叠为 `+N`。  */
    const val LIMIT_LIST: Int = 3

    /** 不限制展示数量。  */
    @JvmField
    val NO_LIMIT: Int = Int.MAX_VALUE

    private const val CHIP_MIN_HEIGHT_DP = 24f
    private const val CHIP_SIDE_PADDING_DP = 8f
    private const val CHIP_TEXT_SIZE_SP = 11f

    /**
     * 渲染标签。**无标签时整个 [ChipGroup] 置为 `GONE`**——
     * 大部分录音没有标签，留一块空白反而让列表参差不齐。
     * 
     * @param group 目标容器
     * @param tags  标签列表，可为 `null` 或空
     * @param limit 最多展示几个，超出部分折叠为 `+N`；不限制传 [.NO_LIMIT]
     */
    fun bind(group: ChipGroup, tags: List<String>?, limit: Int) {
        group.removeAllViews()
        if (tags == null || tags.isEmpty()) {
            group.setVisibility(View.GONE)
            return
        }
        group.setVisibility(View.VISIBLE)

        val shown = min(tags.size, limit)
        for (i in 0..<shown) {
            group.addView(createChip(group, tags.get(i)))
        }
        val hidden = tags.size - shown
        if (hidden > 0) {
            group.addView(
                createChip(
                    group,
                    group.getContext().getString(R.string.native_tag_more, hidden)
                )
            )
        }
    }

    /**
     * 构造一个标签 chip。样式在代码里设定而非 XML style——
     * chip 是动态创建的，XML style 需要额外套 `ContextThemeWrapper` 才能生效，
     * 不如直接设置来得直白。
     * 
     * @param parent 用于取 Context 与资源
     * @param text   标签文案
     * @return 已配置好的 chip
     */
    private fun createChip(parent: ChipGroup, text: String?): Chip {
        val chip = Chip(parent.getContext())
        chip.setText(text)
        chip.setTextSize(CHIP_TEXT_SIZE_SP)
        chip.setTextColor(
            ContextCompat.getColor(
                parent.getContext(),
                R.color.ai_voice_tab_selected
            )
        )
        chip.setChipBackgroundColor(
            ContextCompat.getColorStateList(parent.getContext(), R.color.ai_voice_tag_bg)
        )
        chip.setClickable(false)
        chip.setCheckable(false)
        chip.setEnsureMinTouchTargetSize(false)
        chip.setChipMinHeight(dp(parent, CHIP_MIN_HEIGHT_DP))
        chip.setChipStartPadding(dp(parent, CHIP_SIDE_PADDING_DP))
        chip.setChipEndPadding(dp(parent, CHIP_SIDE_PADDING_DP))
        chip.setTextStartPadding(0f)
        chip.setTextEndPadding(0f)
        return chip
    }

    private fun dp(v: View, value: Float): Float {
        return value * v.getResources().getDisplayMetrics().density
    }
}
