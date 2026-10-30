package com.tuya.smart.ai_voice.nativeui

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.thingclips.smart.earphone.enhance.api.bean.LauncherStateBean
import com.thingclips.smart.earphone.enhance.api.enums.component.ComponentAddState
import com.thingclips.smart.earphone.enhance.api.enums.component.RecordLauncherId
import com.thingclips.smart.earphone.enhance.api.listener.IQuickEntryAddListener
import com.thingclips.smart.earphone.enhance.api.listener.IRecordCallBack
import com.thingclips.smart.sdk.api.IResultCallback
import com.tuya.smart.ai_voice.R
import com.tuya.smart.ai_voice.databinding.ActivityNativeQuickEntryBinding
import com.tuya.smart.ai_voice.databinding.ItemNativeQuickEntryBinding
import com.tuya.smart.ai_voice.nativeui.base.NativeDemoBaseActivity

/**
 * 模块 9 · 快捷入口演示页。
 * 
 * 
 * 查询与开关桌面小组件 / 快捷方式 / 磁贴等录音快捷入口。
 * 
 * 
 * **本页大概率「能调通但看不到效果」**：`getQuickEntryList` 返回的组件依赖宿主 App
 * 已注册对应的 AppWidget / QuickSettings Tile，本 Demo 工程未注册任何此类组件，
 * 因此返回空列表或全部为「隐藏添加」属正常现象。生产工程接入后即可看到真实状态。
 * 
 * 
 * 模块 9 的分享链接 `operateRecordShareLink` 不在本页——它是「对某条录音的操作」，
 * 放在 [NativeRecordDetailActivity] 的更多操作里。
 * 页面底部另附模块 7 的两个 no-op 接口，仅为接口对齐保留，Android 侧无实际行为。
 */
class NativeQuickEntryActivity : NativeDemoBaseActivity() {
    /** 内容布局绑定。  */
    private lateinit var binding: ActivityNativeQuickEntryBinding


    /** 快捷入口添加结果监听，用字段持有；注意 remove 方法无参（移除全部）。  */
    private var quickEntryAddListener: IQuickEntryAddListener? = null
    /** 点击防抖标志，[.CLICK_LOCK_MS] 后自动释放。  */
    private var clickLocked = false

    override val contentLayoutId: Int
        get() = R.layout.activity_native_quick_entry

    override val titleResId: Int
        get() = R.string.native_quick_title

    override fun onContentViewCreated() {
        binding = ActivityNativeQuickEntryBinding.bind(contentView())
        binding.btnQueryEntry.setOnClickListener(View.OnClickListener { v: View? -> queryEntryList() })
        binding.btnEventLimit.setOnClickListener(View.OnClickListener { v: View? -> callEventLimit() })
        binding.btnSetupChannel.setOnClickListener(View.OnClickListener { v: View? -> callSetupChannel() })
        queryEntryList()
    }

    // ===================== 事件监听 =====================
    override fun registerListeners() {
        quickEntryAddListener = object : IQuickEntryAddListener {
            override fun onAddResult(type: Int, id: Int, success: Boolean) {
                runOnUi {
                    // 点击锁到此释放：结果已回来，可以再次操作
                    clickLocked = false
                    // 只有桌面小组件会走到这个回调；快捷方式 / 磁贴由系统直接处理，不回调
                    if (type != TYPE_WIDGET) {
                        return@runOnUi
                    }
                    toast(
                        if (success)
                            getString(R.string.native_quick_toast_added)
                        else
                            getString(R.string.native_quick_toast_add_failed)
                    )
                    // 添加结果到达后状态已变，重新查询刷新展示
                    queryEntryList()
                }
            }
        }
        quickEntryAddListener?.let { manager.addQuickEntryAddListener(it) }
    }

    override fun unregisterListeners() {
        if (quickEntryAddListener != null) {
            // 注意：该 remove 无参，会移除全部已注册的快捷入口监听
            manager.removeQuickEntryAddListener()
            quickEntryAddListener = null
        }
    }

    // ===================== 查询与开关 =====================
    /** 查询各快捷入口的添加状态。  */
    private fun queryEntryList() {
        manager.getQuickEntryList(object : IRecordCallBack<MutableList<LauncherStateBean?>?> {
            override fun onSuccess(result: MutableList<LauncherStateBean?>?) {
                runOnUi { renderEntryList(result) }
            }

            override fun onError(code: String?, error: String?) {
                toastError(code, error)
            }
        })
    }

    /**
     * 渲染入口列表。
     * 
     * @param result 入口状态列表，可能为 null 或空
     */
    private fun renderEntryList(result: MutableList<LauncherStateBean?>?) {
        binding.llEntryContainer.removeAllViews()
        if (result == null || result.isEmpty()) {
            val empty = TextView(this)
            empty.setText(R.string.native_quick_empty)
            empty.setTextSize(12f)
            empty.setPadding(0, 12, 0, 12)
            binding.llEntryContainer.addView(empty)
            return
        }
        val inflater = LayoutInflater.from(this)
        for (bean in result) {
            if (bean == null) continue
            binding.llEntryContainer.addView(createEntryView(inflater, bean))
        }
    }

    /**
     * 构建单个入口视图，并**按 `state` 决定按钮可用性**：
     * 
     *  * `HIDE_ADD(0)` —— 当前系统不支持程序化添加，只能引导用户手动添加，
     * 「开启」置灰
     *  * `ADDED(1)` —— 已添加，「开启」置灰，避免重复拉起系统弹窗
     *  * `NOT_ADDED(2)` —— 可添加
     * 
     */
    private fun createEntryView(inflater: LayoutInflater, bean: LauncherStateBean): View {
        val itemBinding = ItemNativeQuickEntryBinding.inflate(inflater, binding.llEntryContainer, false)
        val item = itemBinding.root
        val componentId = if (bean.id == null) 0 else bean.id.id
        val state = bean.state

        itemBinding.tvEntryName.setText(launcherName(bean.id))
        itemBinding.tvEntryState.setText(
            getString(
                R.string.native_quick_entry_state_format,
                bean.type, componentId, addStateName(state)
            )
        )

        val btnEnable = itemBinding.btnEnable
        val btnDisable = itemBinding.btnDisable
        btnEnable.setEnabled(state == ComponentAddState.NOT_ADDED)
        btnDisable.setEnabled(state == ComponentAddState.ADDED)

        btnEnable.setOnClickListener(View.OnClickListener { v: View? ->
            setEnabled(
                bean.type,
                componentId,
                ENABLED
            )
        })
        btnDisable.setOnClickListener(View.OnClickListener { v: View? ->
            setEnabled(
                bean.type,
                componentId,
                DISABLED
            )
        })

        // 系统不支持程序化添加时，给出手动添加的引导
        val hint = itemBinding.tvEntryHint
        if (state == ComponentAddState.HIDE_ADD) {
            hint.setVisibility(View.VISIBLE)
            hint.setText(R.string.native_quick_hint_manual_add)
        } else {
            hint.setVisibility(View.GONE)
        }
        return item
    }

    /**
     * 开启或关闭指定入口。
     * 
     * 
     * 开启桌面组件时系统会弹出添加确认，结果通过 [IQuickEntryAddListener] 异步回调，
     * 接口本身的 `onSuccess` 只代表请求已下发。
     * 
     * @param type        组件类型（Android：101 widget / 102 快捷图标 / 103 磁贴）
     * @param componentId 组件 ID
     * @param enabled     1 开启 / 0 关闭
     */
    private fun setEnabled(type: Int, componentId: Int, enabled: Int) {
        // 开启会拉起系统的添加确认弹窗，连点会重复拉起，先上锁
        if (clickLocked) {
            return
        }
        clickLocked = true
        main.postDelayed({ clickLocked = false }, CLICK_LOCK_MS)

        manager.setQuickEntryEnabled(type, componentId, enabled, object : IResultCallback {
            override fun onSuccess() {
                runOnUi {
                    if (type == TYPE_WIDGET && enabled == ENABLED) {
                        // 小组件的最终结果由 IQuickEntryAddListener 给出，这里不下结论
                        return@runOnUi
                    }
                    // 其余类型没有结果事件，只能提示「已请求」并重新查询状态
                    toast(getString(R.string.native_quick_toast_requested))
                    queryEntryList()
                }
            }

            override fun onError(code: String?, error: String?) {
                runOnUi {
                    clickLocked = false
                    toast(getString(R.string.native_quick_toast_add_failed))
                }
            }
        })
    }

    private fun launcherName(id: RecordLauncherId?): String {
        if (id == null) return getString(R.string.native_status_unknown)
        when (id) {
            RecordLauncherId.RECORD_WIDGET_1X1 -> return getString(R.string.native_quick_widget_1x1)
            RecordLauncherId.RECORD_WIDGET_2X2 -> return getString(R.string.native_quick_widget_2x2)
            RecordLauncherId.RECORD_SHORTCUT -> return getString(R.string.native_quick_shortcut)
            RecordLauncherId.RECORD_TILE -> return getString(R.string.native_quick_tile)
            RecordLauncherId.RECORD_LONG_PRESS_MENU -> return getString(R.string.native_quick_long_press)
            else -> return id.name
        }
    }

    private fun addStateName(state: ComponentAddState?): String {
        if (state == null) return getString(R.string.native_status_unknown)
        when (state) {
            ComponentAddState.ADDED -> return getString(R.string.native_quick_state_added)
            ComponentAddState.NOT_ADDED -> return getString(R.string.native_quick_state_not_added)
            ComponentAddState.HIDE_ADD -> return getString(R.string.native_quick_state_hide)
            else -> return state.name
        }
    }

    // ===================== 模块 7 的两个 no-op 接口 =====================
    /**
     * 事件流控。**Native 版为 no-op**：不做任何节流，直接回 `onSuccess`，
     * 仅为接口对齐而保留，原用于在页面不可见时降低事件频率。
     */
    private fun callEventLimit() {
        manager.operateEventLimit(SAMPLE_EVENT_NAME, true, object : IResultCallback {
            override fun onSuccess() {
            }

            override fun onError(code: String?, error: String?) {
                toastError(code, error)
            }
        })
    }

    /**
     * 建立原生通道。**Android 侧为 no-op**（仅 iOS 需要），直接回 `onSuccess`。
     */
    private fun callSetupChannel() {
        manager.readyToSetupNativeChannel(object : IResultCallback {
            override fun onSuccess() {
            }

            override fun onError(code: String?, error: String?) {
                toastError(code, error)
            }
        })
    }

    companion object {
        /** 快捷入口开启。  */
        private const val ENABLED = 1

        /** 快捷入口关闭。  */
        private const val DISABLED = 0

        /** 桌面小组件，是唯一会回调 [IQuickEntryAddListener] 的类型。  */
        private const val TYPE_WIDGET = 101

        /**
         * 连续点击的锁定时长。
         * 
         * 
         * 开启快捷入口会拉起系统的添加确认弹窗，连点会重复拉起，故加防抖。
         */
        private const val CLICK_LOCK_MS = 800L

        /** `operateEventLimit` 的示例事件名，仅用于演示调用，Native 侧不做流控。  */
        private const val SAMPLE_EVENT_NAME = "onRecordStatusUpdate"
    }
}
