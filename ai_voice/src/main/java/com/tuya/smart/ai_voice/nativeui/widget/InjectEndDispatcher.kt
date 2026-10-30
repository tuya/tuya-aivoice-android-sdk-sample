package com.tuya.smart.ai_voice.nativeui.widget

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.thingclips.smart.ai.audio.utils.AudioInjectEventManager.OnAudioInjectEndListener
import com.thingclips.smart.ai.audio.utils.AudioInjectEventManager
import com.thingclips.smart.ai.audio.utils.AudioInjectEventManager.setOnAudioInjectEndListener
import com.thingclips.smart.ai.audio.utils.PhoneMicAudioInputFactory
import com.thingclips.smart.home.sdk.ThingHomeSdk

/**
 * 灌流播放结束事件的唯一持有者与分发器。
 * 
 * <h3>为什么需要它</h3>
 * `AudioInjectEventManager.setOnAudioInjectEndListener` 是**覆盖式单槽**，
 * 不是 add / remove 列表。多处直接注册会互相顶掉：后注册的顶掉先注册的，
 * 而先退出的那一方置 null 时又会把后者一起清掉。
 * 
 * 
 * 因此这里由本单例**独占**那个槽位，页面改为向本单例订阅。
 * 
 * <h3>分流规则</h3>
 * 
 *  * 有页面订阅者（[.setPageConsumer]）→ 事件转给它，兜底不介入。
 * Demo 的录音页要停录音并弹窗、批量页要静默进下一轮，两种行为都与兜底不同
 *  * 无订阅者且灌流处于开启状态 → 走兜底：弹个 toast 告知
 *  * 无订阅者且灌流未开启 → 什么都不做
 * 
 * 
 * <h3>兜底存在的意义</h3>
 * 灌流配置是全局的，接入方在灌流设置页开启后，回到**自己的业务页面**录音同样会被灌流。
 * 但他的页面没有任何灌流状态提示（Demo 录音页那条横幅只在 Demo 里），
 * 音频播完后采集通道就没有数据了，界面上看不出任何异常。兜底 toast 就是为这种情况准备的。
 * 
 * <h3>安装时机</h3>
 * [.install] 由 `NativeEntryActivity.start()` 调用——所有能开启灌流的
 * 路径都必经那个总入口，所以只要有灌流的可能，本单例就一定已经装好了。
 * 接入方没打开过调试入口时，本组件不会往他的进程里塞任何全局监听。
 */
class InjectEndDispatcher private constructor() {
    /** 灌流播放结束的消费者。页面订阅后自行处置，兜底不再介入。  */
    interface Consumer {
        /**
         * 灌流音频播放完毕。
         * 
         * 
         * 回调在主线程。
         * 
         * @param deviceId 发生灌流的设备 ID
         */
        fun onInjectEnd(deviceId: String)
    }

    private val main = Handler(Looper.getMainLooper())

    /** Application Context。兜底 toast 不需要 window token，无须挂到某个 Activity 上。  */
    private var appContext: Context? = null

    /** 当前的页面级消费者。非空时兜底不执行。  */
    private var pageConsumer: Consumer? = null

    private var installed = false

    /**
     * 安装：占住 SDK 的灌流结束事件槽位。
     * 
     * 
     * 幂等，重复调用只有第一次生效。监听注册后**不注销**——
     * 按灌流开关去注销会引入「关了又开、监听没了」的时序坑。
     * 
     * @param context 任意 Context，内部取 `getApplicationContext()`
     */
    fun install(context: Context) {
        if (installed) {
            return
        }
        installed = true
        appContext = context.getApplicationContext()
        setOnAudioInjectEndListener(object : AudioInjectEventManager.OnAudioInjectEndListener {
            override fun onAudioInjectEnd(deviceId: String) {
                main.post { dispatch(deviceId) }
            }
        })
        //tuya log
        ThingHomeSdk.setDebugMode(true)
    }

    /**
     * 订阅灌流结束事件，接管本次处置。
     * 
     * 
     * 与 [.clearPageConsumer] 成对使用，建议放在
     * `onResume` / `onPause`——事件在页面不可见时到达没有处理的意义。
     * 
     * 
     * 若尚未 [.install]，这里会自我安装一次：防的是 Demo 里直接
     * `startActivity` 拉起录音页、没走过总入口，导致槽位没人占、连页面自己的处置都收不到。
     * 
     * @param context  用于必要时自我安装
     * @param consumer 消费者
     */
    fun setPageConsumer(context: Context, consumer: Consumer) {
        install(context)
        pageConsumer = consumer
    }

    /**
     * 取消订阅。**必须传同一个引用**，否则可能把别的页面刚设上的消费者清掉。
     * 
     * @param consumer 之前订阅的实例
     */
    fun clearPageConsumer(consumer: Consumer) {
        if (pageConsumer === consumer) {
            pageConsumer = null
        }
    }

    // ===================== 分流 =====================
    private fun dispatch(deviceId: String) {
        val consumer = pageConsumer
        if (consumer != null) {
            consumer.onInjectEnd(deviceId)
            return
        }
        // 灌流没开时这个回调本不该来；真来了也不做任何事，避免干扰正常录音
        val config = PhoneMicAudioInputFactory.getDebugStrategy()
        if (config == null) {
            return
        }
        if (appContext != null) {
            Toast.makeText(appContext, "Record End", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private val INSTANCE = InjectEndDispatcher()

        @JvmStatic
        fun get(): InjectEndDispatcher {
            return INSTANCE
        }
    }
}
