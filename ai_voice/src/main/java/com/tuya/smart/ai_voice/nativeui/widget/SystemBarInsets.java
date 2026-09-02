package com.tuya.smart.ai_voice.nativeui.widget;

import android.app.Activity;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * 系统栏（状态栏 / 导航栏）避让。
 * <p>
 * 应用 {@code targetSdk} 已到 36，系统强制 edge-to-edge：窗口铺满整个屏幕，
 * 内容默认画在状态栏和导航栏<b>下面</b>。Android 15 上还能用
 * {@code android:windowOptOutEdgeToEdgeEnforcement} 逃，Android 16 上该标记已被忽略，
 * 所以只能老实消费 WindowInsets。
 * <p>
 * 取的是 {@code systemBars() | displayCutout()} 的逐边最大值：竖屏下与只取 systemBars 结果一致，
 * 横屏时能避开刘海 / 挖孔。
 * <p>
 * <b>不处理键盘（IME）</b>。edge-to-edge 下窗口是否仍会被 IME 顶起各 ROM 表现不一，
 * 盲目并入 {@code ime()} 在会 resize 的机型上会变成双倍留白。确有输入框被遮挡时再单独处理。
 */
public final class SystemBarInsets {

    private SystemBarInsets() {
    }

    /**
     * 把系统栏高度作为内边距加到指定 View 上。
     * <p>
     * View 原有的 padding 会被记为基线，每次回调都在基线上重新计算，
     * 因此监听重复触发（旋转、多窗口、手势导航切换）也不会累加。
     * <p>
     * <b>不消费 insets</b>，原样向下传递——同一页里可以把上边给一个 View、下边给另一个 View。
     *
     * @param view   目标 View
     * @param top    是否避让顶部（状态栏）
     * @param bottom 是否避让底部（导航栏）
     */
    public static void applyPadding(@NonNull View view, boolean top, boolean bottom) {
        final int basePaddingTop = view.getPaddingTop();
        final int basePaddingBottom = view.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(view, (target, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            target.setPadding(
                    target.getPaddingLeft(),
                    top ? basePaddingTop + bars.top : basePaddingTop,
                    target.getPaddingRight(),
                    bottom ? basePaddingBottom + bars.bottom : basePaddingBottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    /**
     * 把状态栏与导航栏的图标设为深色。
     * <p>
     * edge-to-edge 后系统栏是透明的，本模块页面均为浅色背景，不设置的话浅色图标会看不见。
     * <b>只对调用方所在 Activity 生效</b>，不动全局主题——业务包页面有自己的深浅配色。
     *
     * @param activity 目标页面
     */
    public static void setLightSystemBars(@NonNull Activity activity) {
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                activity.getWindow(), activity.getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(true);
        controller.setAppearanceLightNavigationBars(true);
    }
}
