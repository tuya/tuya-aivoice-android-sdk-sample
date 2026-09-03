package com.tuya.smart.ai_voice.nativeui.widget;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.thingclips.smart.ai.audio.utils.AudioInjectEventManager;
import com.thingclips.smart.ai.audio.utils.PhoneMicAudioInputFactory;
import com.thingclips.smart.earphone.enhance.api.bean.AudioFrontInputConfig;
import com.thingclips.smart.home.sdk.ThingHomeSdk;
import com.thingclips.smart.plugin.tuniaudiodetectmanager.ThingAudioDetectManagerNative;
import com.thingclips.smart.sdk.api.IResultCallback;
import com.tuya.smart.ai_voice.R;

import java.lang.ref.WeakReference;

/**
 * 灌流播放结束事件的唯一持有者与分发器。
 *
 * <h3>为什么需要它</h3>
 * {@code AudioInjectEventManager.setOnAudioInjectEndListener} 是<b>覆盖式单槽</b>，
 * 不是 add / remove 列表。多处直接注册会互相顶掉：后注册的顶掉先注册的，
 * 而先退出的那一方置 null 时又会把后者一起清掉。
 * <p>
 * 因此这里由本单例<b>独占</b>那个槽位，页面改为向本单例订阅。
 *
 * <h3>分流规则</h3>
 * <ul>
 *     <li>有页面订阅者（{@link #setPageConsumer}）→ 事件转给它，兜底不介入。
 *         Demo 的录音页要停录音并弹窗、批量页要静默进下一轮，两种行为都与兜底不同</li>
 *     <li>无订阅者且灌流处于开启状态 → 走兜底：停掉该设备的录音，并在栈顶 Activity 上弹窗告知</li>
 *     <li>无订阅者且灌流未开启 → 什么都不做</li>
 * </ul>
 *
 * <h3>兜底存在的意义</h3>
 * 灌流配置是全局的，接入方在灌流设置页开启后，回到<b>自己的业务页面</b>录音同样会被灌流。
 * 但他的页面没有任何灌流状态提示（Demo 录音页那条横幅只在 Demo 里），
 * 音频播完后采集通道就没有数据了，界面上看不出任何异常。兜底弹窗就是为这种情况准备的。
 *
 * <h3>安装时机</h3>
 * {@link #install(Context)} 由 {@code NativeEntryActivity.start()} 调用——所有能开启灌流的
 * 路径都必经那个总入口，所以只要有灌流的可能，本单例就一定已经装好了。
 * 接入方没打开过调试入口时，本组件不会往他的进程里塞任何全局监听。
 */
public final class InjectEndDispatcher {

    /** 灌流播放结束的消费者。页面订阅后自行处置，兜底不再介入。 */
    public interface Consumer {
        /**
         * 灌流音频播放完毕。
         * <p>
         * 回调在主线程。
         *
         * @param deviceId 发生灌流的设备 ID
         */
        void onInjectEnd(@NonNull String deviceId);
    }

    private static final InjectEndDispatcher INSTANCE = new InjectEndDispatcher();

    private final Handler main = new Handler(Looper.getMainLooper());

    /** Application Context，供拿不到 Activity 时的 toast 兜底使用。 */
    @Nullable
    private Context appContext;

    /** 栈顶 Activity。用弱引用持有，避免 Activity 被这个进程级单例吊住。 */
    @Nullable
    private WeakReference<Activity> topActivity;

    /** 当前的页面级消费者。非空时兜底不执行。 */
    @Nullable
    private Consumer pageConsumer;

    private boolean installed;

    private InjectEndDispatcher() {
    }

    public static InjectEndDispatcher get() {
        return INSTANCE;
    }

    /**
     * 安装：注册 Activity 生命周期回调以追踪栈顶页面，并占住 SDK 的灌流结束事件槽位。
     * <p>
     * 幂等，重复调用只有第一次生效。生命周期回调注册后<b>不注销</b>——它只用弱引用记一个
     * 栈顶 Activity，开销可忽略，而按灌流开关去注销会引入「关了又开、监听没了」的时序坑。
     *
     * @param context 任意 Context，内部取 {@code getApplicationContext()}
     */
    public void install(@NonNull Context context) {
        if (installed) {
            return;
        }
        Context app = context.getApplicationContext();
        if (!(app instanceof Application)) {
            return;
        }
        installed = true;
        appContext = app;
        ((Application) app).registerActivityLifecycleCallbacks(new TopActivityTracker());
        AudioInjectEventManager.INSTANCE.setOnAudioInjectEndListener(
                deviceId -> main.post(() -> dispatch(deviceId)));
        //tuya log
        ThingHomeSdk.setDebugMode(true);
    }

    /**
     * 订阅灌流结束事件，接管本次处置。
     * <p>
     * 与 {@link #clearPageConsumer(Consumer)} 成对使用，建议放在
     * {@code onResume} / {@code onPause}——事件在页面不可见时到达没有处理的意义。
     * <p>
     * 若尚未 {@link #install(Context)}，这里会自我安装一次：防的是 Demo 里直接
     * {@code startActivity} 拉起录音页、没走过总入口，导致槽位没人占、连页面自己的处置都收不到。
     *
     * @param context  用于必要时自我安装
     * @param consumer 消费者
     */
    public void setPageConsumer(@NonNull Context context, @NonNull Consumer consumer) {
        install(context);
        pageConsumer = consumer;
    }

    /**
     * 取消订阅。<b>必须传同一个引用</b>，否则可能把别的页面刚设上的消费者清掉。
     *
     * @param consumer 之前订阅的实例
     */
    public void clearPageConsumer(@NonNull Consumer consumer) {
        if (pageConsumer == consumer) {
            pageConsumer = null;
        }
    }

    // ===================== 分流 =====================

    private void dispatch(@NonNull String deviceId) {
        Consumer consumer = pageConsumer;
        if (consumer != null) {
            consumer.onInjectEnd(deviceId);
            return;
        }
        // 灌流没开时这个回调本不该来；真来了也不做任何事，避免干扰正常录音
        AudioFrontInputConfig config = PhoneMicAudioInputFactory.INSTANCE.getDebugStrategy();
        if (config == null) {
            return;
        }
        stopRecording(deviceId);
        showEndDialog(config.getReplaceCaptureData().getFileName());
    }

    /** 文件播完后采集通道不再有数据，继续录只会得到静音。 */
    private void stopRecording(@NonNull String deviceId) {
        ThingAudioDetectManagerNative.getInstance().stopRecordTransfer(deviceId,
                new IResultCallback() {
                    @Override
                    public void onSuccess() {
                    }

                    @Override
                    public void onError(String code, String error) {
                        // 多数情况是「本来就没在录」，无需打扰使用者
                    }
                });
    }

    /**
     * 在栈顶 Activity 上弹窗告知。
     * <p>
     * <b>刻意使用 framework 的 {@link AlertDialog} 而不是 AppCompat 版</b>：
     * 这个弹窗会出现在接入方的 Activity 上，宿主主题不可控，
     * AppCompat 版在非 {@code Theme.AppCompat} 主题上会直接抛
     * {@code IllegalStateException}。样式差异对一个调试提示无所谓，不崩才是第一位的。
     *
     * @param fileName 当前灌流的文件名，用于在多语料轮测时确认灌的是哪个
     */
    private void showEndDialog(@Nullable String fileName) {
        String name = TextUtils.isEmpty(fileName) ? "-" : fileName;
        Activity activity = topActivity == null ? null : topActivity.get();
        if (activity == null || activity.isFinishing() || isDestroyed(activity)) {
            // 没有可用的窗口（应用在后台，或栈顶页面正在销毁）。
            // 弹窗需要有效的 window token，此时 show 会抛 BadTokenException，退回 toast。
            if (appContext != null) {
                Toast.makeText(appContext,
                        appContext.getString(R.string.native_inject_end_toast, name),
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.native_inject_end_dialog_title)
                .setMessage(activity.getString(R.string.native_inject_end_dialog_message, name))
                .setPositiveButton(R.string.native_inject_end_dialog_ok, null)
                .setCancelable(true)
                .show();
    }

    private static boolean isDestroyed(@NonNull Activity activity) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed();
    }

    // ===================== 栈顶追踪 =====================

    /**
     * 只为拿到「现在哪个 Activity 在前台」，用于弹窗的宿主。
     * <p>
     * 弱引用持有，Activity 销毁后自然可回收，本单例不会造成泄漏。
     */
    private final class TopActivityTracker implements Application.ActivityLifecycleCallbacks {

        @Override
        public void onActivityResumed(@NonNull Activity activity) {
            topActivity = new WeakReference<>(activity);
        }

        @Override
        public void onActivityPaused(@NonNull Activity activity) {
            if (topActivity != null && topActivity.get() == activity) {
                topActivity = null;
            }
        }

        @Override
        public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        }

        @Override
        public void onActivityStarted(@NonNull Activity activity) {
        }

        @Override
        public void onActivityStopped(@NonNull Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(@NonNull Activity activity) {
        }
    }
}
