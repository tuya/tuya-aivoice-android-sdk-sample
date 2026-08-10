package com.tuya.smart.ai_voice.nativeui;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.thingclips.smart.earphone.enhance.api.bean.LauncherStateBean;
import com.thingclips.smart.earphone.enhance.api.enums.component.ComponentAddState;
import com.thingclips.smart.earphone.enhance.api.enums.component.RecordLauncherId;
import com.thingclips.smart.earphone.enhance.api.listener.IQuickEntryAddListener;
import com.thingclips.smart.earphone.enhance.api.listener.IRecordCallBack;
import com.thingclips.smart.sdk.api.IResultCallback;
import com.tuya.smart.ai_voice.R;
import com.tuya.smart.ai_voice.nativeui.base.NativeDemoBaseActivity;

import java.util.List;

/**
 * 模块 9 · 快捷入口演示页。
 * <p>
 * 查询与开关桌面小组件 / 快捷方式 / 磁贴等录音快捷入口。
 * <p>
 * <b>本页大概率「能调通但看不到效果」</b>：{@code getQuickEntryList} 返回的组件依赖宿主 App
 * 已注册对应的 AppWidget / QuickSettings Tile，本 Demo 工程未注册任何此类组件，
 * 因此返回空列表或全部为「隐藏添加」属正常现象。生产工程接入后即可看到真实状态。
 * <p>
 * 模块 9 的分享链接 {@code operateRecordShareLink} 不在本页——它是「对某条录音的操作」，
 * 放在 {@link NativeRecordDetailActivity} 的更多操作里。
 * 页面底部另附模块 7 的两个 no-op 接口，仅为接口对齐保留，Android 侧无实际行为。
 */
public class NativeQuickEntryActivity extends NativeDemoBaseActivity {

    /** 快捷入口开启。 */
    private static final int ENABLED = 1;
    /** 快捷入口关闭。 */
    private static final int DISABLED = 0;

    /** 桌面小组件，是唯一会回调 {@link IQuickEntryAddListener} 的类型。 */
    private static final int TYPE_WIDGET = 101;

    /**
     * 连续点击的锁定时长。
     * <p>
     * 开启快捷入口会拉起系统的添加确认弹窗，连点会重复拉起，故加防抖。
     */
    private static final long CLICK_LOCK_MS = 800L;

    /** {@code operateEventLimit} 的示例事件名，仅用于演示调用，Native 侧不做流控。 */
    private static final String SAMPLE_EVENT_NAME = "onRecordStatusUpdate";

    /** 快捷入口添加结果监听，用字段持有；注意 remove 方法无参（移除全部）。 */
    private IQuickEntryAddListener quickEntryAddListener;

    private LinearLayout entryContainer;

    /** 点击防抖标志，{@link #CLICK_LOCK_MS} 后自动释放。 */
    private boolean clickLocked = false;

    @Override
    protected int getContentLayoutId() {
        return R.layout.activity_native_quick_entry;
    }

    @Override
    protected int getTitleResId() {
        return R.string.native_quick_title;
    }

    @Override
    protected void onContentViewCreated() {
        entryContainer = findViewById(R.id.ll_entry_container);
        findViewById(R.id.btn_query_entry).setOnClickListener(v -> queryEntryList());
        findViewById(R.id.btn_event_limit).setOnClickListener(v -> callEventLimit());
        findViewById(R.id.btn_setup_channel).setOnClickListener(v -> callSetupChannel());
        queryEntryList();
    }

    // ===================== 事件监听 =====================

    @Override
    protected void registerListeners() {
        quickEntryAddListener = (type, id, success) -> runOnUi(() -> {
            // 点击锁到此释放：结果已回来，可以再次操作
            clickLocked = false;
            // 只有桌面小组件会走到这个回调；快捷方式 / 磁贴由系统直接处理，不回调
            if (type != TYPE_WIDGET) {
                return;
            }
            toast(success
                    ? getString(R.string.native_quick_toast_added)
                    : getString(R.string.native_quick_toast_add_failed));
            // 添加结果到达后状态已变，重新查询刷新展示
            queryEntryList();
        });
        manager.addQuickEntryAddListener(quickEntryAddListener);
    }

    @Override
    protected void unregisterListeners() {
        if (quickEntryAddListener != null) {
            // 注意：该 remove 无参，会移除全部已注册的快捷入口监听
            manager.removeQuickEntryAddListener();
            quickEntryAddListener = null;
        }
    }

    // ===================== 查询与开关 =====================

    /** 查询各快捷入口的添加状态。 */
    private void queryEntryList() {
        manager.getQuickEntryList(new IRecordCallBack<List<LauncherStateBean>>() {
            @Override
            public void onSuccess(List<LauncherStateBean> result) {
                runOnUi(() -> renderEntryList(result));
            }

            @Override
            public void onError(String code, String error) {
                toastError(code, error);
            }
        });
    }

    /**
     * 渲染入口列表。
     *
     * @param result 入口状态列表，可能为 null 或空
     */
    private void renderEntryList(@Nullable List<LauncherStateBean> result) {
        entryContainer.removeAllViews();
        if (result == null || result.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.native_quick_empty);
            empty.setTextSize(12f);
            empty.setPadding(0, 12, 0, 12);
            entryContainer.addView(empty);
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        for (LauncherStateBean bean : result) {
            entryContainer.addView(createEntryView(inflater, bean));
        }
    }

    /**
     * 构建单个入口视图，并<b>按 {@code state} 决定按钮可用性</b>：
     * <ul>
     *     <li>{@code HIDE_ADD(0)} —— 当前系统不支持程序化添加，只能引导用户手动添加，
     *         「开启」置灰</li>
     *     <li>{@code ADDED(1)} —— 已添加，「开启」置灰，避免重复拉起系统弹窗</li>
     *     <li>{@code NOT_ADDED(2)} —— 可添加</li>
     * </ul>
     */
    private View createEntryView(LayoutInflater inflater, LauncherStateBean bean) {
        View item = inflater.inflate(R.layout.item_native_quick_entry, entryContainer, false);
        int componentId = bean.getId() == null ? 0 : bean.getId().getId();
        ComponentAddState state = bean.getState();

        ((TextView) item.findViewById(R.id.tv_entry_name))
                .setText(launcherName(bean.getId()));
        ((TextView) item.findViewById(R.id.tv_entry_state))
                .setText(getString(R.string.native_quick_entry_state_format,
                        bean.getType(), componentId, addStateName(state)));

        View btnEnable = item.findViewById(R.id.btn_enable);
        View btnDisable = item.findViewById(R.id.btn_disable);
        btnEnable.setEnabled(state == ComponentAddState.NOT_ADDED);
        btnDisable.setEnabled(state == ComponentAddState.ADDED);

        btnEnable.setOnClickListener(v -> setEnabled(bean.getType(), componentId, ENABLED));
        btnDisable.setOnClickListener(v -> setEnabled(bean.getType(), componentId, DISABLED));

        // 系统不支持程序化添加时，给出手动添加的引导
        TextView hint = item.findViewById(R.id.tv_entry_hint);
        if (state == ComponentAddState.HIDE_ADD) {
            hint.setVisibility(View.VISIBLE);
            hint.setText(R.string.native_quick_hint_manual_add);
        } else {
            hint.setVisibility(View.GONE);
        }
        return item;
    }

    /**
     * 开启或关闭指定入口。
     * <p>
     * 开启桌面组件时系统会弹出添加确认，结果通过 {@link IQuickEntryAddListener} 异步回调，
     * 接口本身的 {@code onSuccess} 只代表请求已下发。
     *
     * @param type        组件类型（Android：101 widget / 102 快捷图标 / 103 磁贴）
     * @param componentId 组件 ID
     * @param enabled     1 开启 / 0 关闭
     */
    private void setEnabled(int type, int componentId, int enabled) {
        // 开启会拉起系统的添加确认弹窗，连点会重复拉起，先上锁
        if (clickLocked) {
            return;
        }
        clickLocked = true;
        main.postDelayed(() -> clickLocked = false, CLICK_LOCK_MS);

        manager.setQuickEntryEnabled(type, componentId, enabled, new IResultCallback() {
            @Override
            public void onSuccess() {
                runOnUi(() -> {
                    if (type == TYPE_WIDGET && enabled == ENABLED) {
                        // 小组件的最终结果由 IQuickEntryAddListener 给出，这里不下结论
                        return;
                    }
                    // 其余类型没有结果事件，只能提示「已请求」并重新查询状态
                    toast(getString(R.string.native_quick_toast_requested));
                    queryEntryList();
                });
            }

            @Override
            public void onError(String code, String error) {
                runOnUi(() -> {
                    clickLocked = false;
                    toast(getString(R.string.native_quick_toast_add_failed));
                });
            }
        });
    }

    private String launcherName(@Nullable RecordLauncherId id) {
        if (id == null) return getString(R.string.native_status_unknown);
        switch (id) {
            case RECORD_WIDGET_1X1: return getString(R.string.native_quick_widget_1x1);
            case RECORD_WIDGET_2X2: return getString(R.string.native_quick_widget_2x2);
            case RECORD_SHORTCUT: return getString(R.string.native_quick_shortcut);
            case RECORD_TILE: return getString(R.string.native_quick_tile);
            case RECORD_LONG_PRESS_MENU: return getString(R.string.native_quick_long_press);
            default: return id.name();
        }
    }

    private String addStateName(@Nullable ComponentAddState state) {
        if (state == null) return getString(R.string.native_status_unknown);
        switch (state) {
            case ADDED: return getString(R.string.native_quick_state_added);
            case NOT_ADDED: return getString(R.string.native_quick_state_not_added);
            case HIDE_ADD: return getString(R.string.native_quick_state_hide);
            default: return state.name();
        }
    }

    // ===================== 模块 7 的两个 no-op 接口 =====================

    /**
     * 事件流控。<b>Native 版为 no-op</b>：不做任何节流，直接回 {@code onSuccess}，
     * 仅为接口对齐而保留，原用于在页面不可见时降低事件频率。
     */
    private void callEventLimit() {
        manager.operateEventLimit(SAMPLE_EVENT_NAME, true, new IResultCallback() {
            @Override
            public void onSuccess() {
            }

            @Override
            public void onError(String code, String error) {
                toastError(code, error);
            }
        });
    }

    /**
     * 建立原生通道。<b>Android 侧为 no-op</b>（仅 iOS 需要），直接回 {@code onSuccess}。
     */
    private void callSetupChannel() {
        manager.readyToSetupNativeChannel(new IResultCallback() {
            @Override
            public void onSuccess() {
            }

            @Override
            public void onError(String code, String error) {
                toastError(code, error);
            }
        });
    }
}
