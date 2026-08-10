package com.tuya.smart.ai_voice.nativeui.widget;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.tuya.smart.ai_voice.R;

import java.util.List;

/**
 * 把 {@code RecordTransferResultBean.tags} 渲染到 {@link ChipGroup} 上。
 * <p>
 * 列表页与详情页共用一套渲染，差别只有<b>展示上限</b>：
 * <ul>
 *     <li>列表页只做扫读，超出 {@link #LIMIT_LIST} 个即折叠成 {@code +N}；</li>
 *     <li>详情页是细读，用 {@link #NO_LIMIT} 全部展示。</li>
 * </ul>
 * 标签<b>不可点击</b>：列表 item 里若 chip 消费了点击事件，用户点卡片进详情就会失灵。
 * 标签的增删改在 {@code NativeRecordActionSheet}（{@code updateRecordTagResult}）里，本类只负责展示。
 * <p>
 * ⚠️ {@link Chip} 是 Material 组件，要求宿主主题为 MaterialComponents 系。
 * 本工程的 {@code AppTheme} 为此继承了 {@code Theme.MaterialComponents...Bridge}，
 * 换回纯 AppCompat 主题会导致这里<b>运行时崩溃</b>。
 */
public final class RecordTagBinder {

    /** 列表页的标签展示上限，超出折叠为 {@code +N}。 */
    public static final int LIMIT_LIST = 3;

    /** 不限制展示数量。 */
    public static final int NO_LIMIT = Integer.MAX_VALUE;

    private static final float CHIP_MIN_HEIGHT_DP = 24f;
    private static final float CHIP_SIDE_PADDING_DP = 8f;
    private static final float CHIP_TEXT_SIZE_SP = 11f;

    private RecordTagBinder() {
    }

    /**
     * 渲染标签。<b>无标签时整个 {@link ChipGroup} 置为 {@code GONE}</b>——
     * 大部分录音没有标签，留一块空白反而让列表参差不齐。
     *
     * @param group 目标容器
     * @param tags  标签列表，可为 {@code null} 或空
     * @param limit 最多展示几个，超出部分折叠为 {@code +N}；不限制传 {@link #NO_LIMIT}
     */
    public static void bind(@NonNull ChipGroup group, @Nullable List<String> tags, int limit) {
        group.removeAllViews();
        if (tags == null || tags.isEmpty()) {
            group.setVisibility(View.GONE);
            return;
        }
        group.setVisibility(View.VISIBLE);

        int shown = Math.min(tags.size(), limit);
        for (int i = 0; i < shown; i++) {
            group.addView(createChip(group, tags.get(i)));
        }
        int hidden = tags.size() - shown;
        if (hidden > 0) {
            group.addView(createChip(group,
                    group.getContext().getString(R.string.native_tag_more, hidden)));
        }
    }

    /**
     * 构造一个标签 chip。样式在代码里设定而非 XML style——
     * chip 是动态创建的，XML style 需要额外套 {@code ContextThemeWrapper} 才能生效，
     * 不如直接设置来得直白。
     *
     * @param parent 用于取 Context 与资源
     * @param text   标签文案
     * @return 已配置好的 chip
     */
    private static Chip createChip(@NonNull ChipGroup parent, String text) {
        Chip chip = new Chip(parent.getContext());
        chip.setText(text);
        chip.setTextSize(CHIP_TEXT_SIZE_SP);
        chip.setTextColor(ContextCompat.getColor(parent.getContext(), R.color.ai_voice_tab_selected));
        chip.setChipBackgroundColor(
                ContextCompat.getColorStateList(parent.getContext(), R.color.ai_voice_tag_bg));
        chip.setClickable(false);
        chip.setCheckable(false);
        chip.setEnsureMinTouchTargetSize(false);
        chip.setChipMinHeight(dp(parent, CHIP_MIN_HEIGHT_DP));
        chip.setChipStartPadding(dp(parent, CHIP_SIDE_PADDING_DP));
        chip.setChipEndPadding(dp(parent, CHIP_SIDE_PADDING_DP));
        chip.setTextStartPadding(0f);
        chip.setTextEndPadding(0f);
        return chip;
    }

    private static float dp(@NonNull View v, float value) {
        return value * v.getResources().getDisplayMetrics().density;
    }
}
