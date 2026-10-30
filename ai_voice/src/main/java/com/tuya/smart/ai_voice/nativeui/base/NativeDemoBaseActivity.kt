package com.tuya.smart.ai_voice.nativeui.base

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.viewbinding.ViewBinding
import com.alibaba.fastjson.JSON
import com.thingclips.smart.api.service.MicroServiceManager
import com.thingclips.smart.commonbiz.bizbundle.family.api.AbsBizBundleFamilyService
import com.thingclips.smart.home.sdk.ThingHomeSdk
import com.thingclips.smart.plugin.tuniaudiodetectmanager.ThingAudioDetectManagerNative
import com.thingclips.smart.sdk.bean.DeviceBean
import com.tuya.smart.ai_voice.R
import com.tuya.smart.ai_voice.databinding.ActivityNativeDemoBaseBinding
import com.tuya.smart.ai_voice.databinding.LayoutNativeScrollContainerBinding
import com.tuya.smart.ai_voice.nativeui.widget.SystemBarInsets
import com.tuya.smart.ai_voice.nativeui.widget.SystemBarInsets.setLightSystemBars

/**
 * Native 能力演示页基类，只提供脚手架，不封装任何业务 API。
 * 
 * 
 * 提供四样东西：
 * 
 *  1. 统一布局：顶部栏 + 内容容器，并完成状态栏 / 导航栏避让
 *  1. [.toast] / [.runOnUi]
 * —— SDK 回调一律在子线程，更新 UI 必须切回主线程
 *  1. [.registerListeners] / [.unregisterListeners] 抽象方法，
 * 分别在 `onCreate` / `onDestroy` 自动调用。
 * **SDK 的事件监听必须 add/remove 成对且传同一实例**，用模板强制住这一点
 *  1. [.setupDeviceSpinner] —— PHONE + 家庭音频设备的下拉加载
 * 
 * 各模块的 SDK 调用一律留在子类页面内，保证单个页面可独立阅读、整体复制。
 */
abstract class NativeDemoBaseActivity : AppCompatActivity() {
    /** 基类布局绑定：顶栏标题 / 返回键 / 内容容器 / 顶栏动作位。  */
    private lateinit var binding: ActivityNativeDemoBaseBinding

    /** 子类内容布局的根视图，[.onContentViewCreated] 中用于 ViewBinding.bind。  */
    private lateinit var contentRootView: View

    /** SDK 单例入口，接入者只需面向它编程。任意线程调用均安全（内部在专用后台线程初始化）。  */
    @JvmField
    protected val manager: ThingAudioDetectManagerNative =
        ThingAudioDetectManagerNative.getInstance()

    /** 主线程 Handler，供 [.runOnUi] 使用。  */
    @JvmField
    protected val main: Handler = Handler(Looper.getMainLooper())

    /** 设备下拉数据源：index 0 固定为 PHONE，其余为过滤后的家庭音频设备。  */
    @JvmField
    protected val deviceItems: MutableList<DeviceItem> = ArrayList<DeviceItem>()
    private var deviceSpinner: Spinner? = null

    // ===================== 子类实现 =====================
    @get:LayoutRes
    protected abstract val contentLayoutId: Int

    @get:StringRes
    protected abstract val titleResId: Int

    /**
     * 注册 SDK 事件监听。在 `onCreate` 中自动调用。
     * 
     * 
     * 监听实例必须用字段持有，[.unregisterListeners] 中传同一引用注销。
     */
    protected abstract fun registerListeners()

    /**
     * 注销 SDK 事件监听。在 `onDestroy` 中自动调用。
     */
    protected abstract fun unregisterListeners()

    /**
     * 内容区是否需要基类提供的滚动容器。
     * 
     * 
     * 表单型页面返回 `true`（默认）；内部已有 RecyclerView 等滚动控件的页面
     * 必须返回 `false`，否则嵌套滚动会失效。
     * 
     * @return true 由基类包一层 NestedScrollView
     */
    protected open fun useScrollContainer(): Boolean {
        return true
    }

    // ===================== 生命周期 =====================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNativeDemoBaseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // targetSdk 36 起系统强制 edge-to-edge，内容会画到状态栏与导航栏下面，
        // 上下 inset 打在带背景色的根布局上，顶栏与内容整体避开系统栏。
        SystemBarInsets.applyPadding(binding.llNativeRoot, true, true)
        setLightSystemBars(this)

        binding.tvTitle.setText(this.titleResId)
        binding.ivBack.setOnClickListener(View.OnClickListener { v: View? -> finish() })

        val inflater = LayoutInflater.from(this)
        // 内容布局先 inflate 不挂载：拿到子视图交给子类做 ViewBinding 绑定，
        // 再手动挂到滚动容器 / 内容帧布局里（attachToRoot=true 时 inflate 返回的是父容器）
        if (useScrollContainer()) {
            val scroll = LayoutNativeScrollContainerBinding.inflate(inflater, binding.flContentRoot, true)
            contentRootView = inflater.inflate(this.contentLayoutId, scroll.llContentContainer, false)
            scroll.llContentContainer.addView(contentRootView)
        } else {
            contentRootView = inflater.inflate(this.contentLayoutId, binding.flContentRoot, false)
            binding.flContentRoot.addView(contentRootView)
        }

        onContentViewCreated()
        registerListeners()
    }

    /**
     * 内容区布局已注入时回调，子类在此绑定 View 与初始化数据。
     * 
     * 
     * 此时 [.registerListeners] 尚未调用，可安全初始化监听所依赖的字段。
     */
    protected abstract fun onContentViewCreated()

    /**
     * @return 子类内容布局的根视图，供 `ViewBinding.bind(contentView())` 建立绑定
     */
    protected fun contentView(): View = contentRootView

    /**
     * 顶栏标题文案。子类需要按运行期状态改标题时使用（默认取 [.titleResId]）。
     *
     * @param resId 文案资源
     */
    protected fun setTitleText(@StringRes resId: Int) {
        binding.tvTitle.setText(resId)
    }

    /** @return 顶栏返回键，子类需要接管返回行为时重新绑点击事件  */
    protected fun topBarBackView(): View = binding.ivBack

    override fun onDestroy() {
        super.onDestroy()
        unregisterListeners()
        main.removeCallbacksAndMessages(null)
    }

    // ===================== 顶栏动作位 =====================
    /**
     * 挂载顶栏右侧的动作入口（设置 / 更多）。未调用时该位置不显示。
     * 
     * 
     * 需要随状态改变图标或点击行为时重复调用即可，内部只做覆盖。
     * 
     * @param iconRes  图标资源
     * @param descRes  无障碍描述
     * @param listener 点击回调
     */
    protected fun setTopBarAction(
        @DrawableRes iconRes: Int, @StringRes descRes: Int,
        listener: View.OnClickListener
    ) {
        val action = binding.ivTopAction
        action.setImageResource(iconRes)
        action.setContentDescription(getString(descRes))
        action.setOnClickListener(listener)
        action.setVisibility(View.VISIBLE)
    }

    // ===================== 通用工具 =====================
    /**
     * 切回主线程执行。SDK 回调可能在子线程，更新 UI 前一律走这里。
     * 
     * @param r 待执行任务
     */
    protected fun runOnUi(r: Runnable) {
        main.post(r)
    }

    protected fun toast(s: String?) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    /**
     * 提示接口调用失败。SDK 回调可能在子线程，内部已切回主线程，调用方直接用即可。
     * 
     * 
     * 错误码是底层透传、无固定枚举，故原样展示而不做分支映射；
     * 录音链路有专属码表的场景请改用 `RecordErrorCode.messageOf`。
     * 
     * @param code  错误码
     * @param error 错误消息
     */
    protected fun toastError(code: String?, error: String?) {
        runOnUi { toast(getString(R.string.native_toast_api_failed, code, error)) }
    }

    // ===================== 设备下拉 =====================
    /**
     * 装配设备下拉：index 0 固定为 PHONE（手机本地录音），其余为当前家庭下
     * 符合音频能力（Pro 耳机 / 入门耳机 / 录音卡片 / OS 耳机）的设备。
     * 
     * 
     * 这是环境依赖而非某个模块的能力演示，故放在基类；
     * 选中项通过 [.currentDeviceId] / [.currentDeviceName] 读取。
     * 
     * @param spinner 目标下拉控件
     */
    protected fun setupDeviceSpinner(spinner: Spinner) {
        this.deviceSpinner = spinner
        populateDeviceItems()

        val names: MutableList<String?> = ArrayList<String?>()
        for (item in deviceItems) {
            names.add(item.displayName)
        }
        val adapter = ArrayAdapter<String?>(this, R.layout.item_spinner_native, names)
        adapter.setDropDownViewResource(R.layout.item_spinner_native_dropdown)
        spinner.setAdapter(adapter)
        spinner.setSelection(0)
        spinner.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                onDeviceSelected(deviceItems.get(position))
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        })
    }

    /**
     * 装载设备列表到 [.deviceItems]：index 0 固定为 PHONE（若 [.includePhoneDevice]），
     * 其余为当前家庭下符合音频能力的设备。
     * 
     * 
     * 供 [.setupDeviceSpinner] 与不走 Spinner 的页面（录音室风格的设备弹窗）共用。
     */
    protected fun populateDeviceItems() {
        deviceItems.clear()
        if (includePhoneDevice()) {
            deviceItems.add(
                DeviceItem(
                    DEVICE_ID_PHONE, getString(R.string.native_device_phone), null, true
                )
            )
        }

        val homeDevices =
            this.homeDevices
        if (homeDevices != null) {
            for (dev in homeDevices) {
                val productType = getAudioProductType(dev)
                if (productType == null) continue
                if (!acceptDevice(productType)) continue
                val name = if (TextUtils.isEmpty(dev.getName())) dev.getDevId() else dev.getName()
                val display = getString(
                    R.string.native_device_name_with_category,
                    name, categoryName(productType)
                )
                deviceItems.add(
                    DeviceItem(
                        dev.getDevId(), display, productType, dev.getIsOnline()
                    )
                )
            }
        }
    }

    /**
     * 设备下拉是否包含「手机本地录音」这一项。
     * 
     * 
     * 只作用于真实设备的能力（如离线文件传输）应返回 `false`——
     * 手机上不存在设备侧缓存，列出来只会让人误选。
     * 
     * @return true 包含（默认）
     */
    protected open fun includePhoneDevice(): Boolean {
        return true
    }

    /**
     * 是否把该类型的设备放进下拉。
     * 
     * 
     * 默认接受全部音频设备。**能力只支持部分机型时应覆写**，把不支持的直接过滤掉，
     * 比给出「该设备不支持」的事后提示更省事——用户根本选不到。
     * 
     * @param productType `DeviceItem.TYPE_*` 之一
     * @return true 放进下拉（默认）
     */
    protected open fun acceptDevice(productType: String): Boolean {
        return true
    }

    /**
     * @return 设备下拉是否为空（不含手机项）。为空说明当前家庭没有该能力支持的设备
     */
    protected fun hasNoUsableDevice(): Boolean {
        for (item in deviceItems) {
            if (!item.isPhone) return false
        }
        return true
    }

    /**
     * 设备下拉选中项变化。默认打日志，子类可覆写做联动。
     * 
     * @param item 选中的设备
     */
    protected open fun onDeviceSelected(item: DeviceItem) {
    }

    /**
     * @return 当前选中的设备 ID；未装配下拉时返回 [.DEVICE_ID_PHONE]
     */
    protected fun currentDeviceId(): String? {
        val item = currentDeviceItem()
        return if (item == null) DEVICE_ID_PHONE else item.devId
    }

    /**
     * @return 当前选中的设备展示名；未装配下拉时返回 [.DEVICE_ID_PHONE]
     */
    protected fun currentDeviceName(): String? {
        val item = currentDeviceItem()
        return if (item == null) DEVICE_ID_PHONE else item.displayName
    }

    /**
     * @return 当前选中的设备项；未装配下拉或列表为空时返回 `null`
     */
    protected open fun currentDeviceItem(): DeviceItem? {
        if (deviceSpinner == null) return null
        val pos = deviceSpinner?.getSelectedItemPosition() ?: -1
        if (pos >= 0 && pos < deviceItems.size) {
            return deviceItems.get(pos)
        }
        return null
    }

    private val homeDevices: MutableList<DeviceBean>?
        /**
         * @return 当前家庭的设备列表；家庭服务不可用时返回 `null`
         */
        get() {
            try {
                val familyService = MicroServiceManager.getInstance()
                    .findServiceByInterface<AbsBizBundleFamilyService?>(AbsBizBundleFamilyService::class.java.getName())
                if (familyService == null) return null
                return ThingHomeSdk.newHomeInstance(familyService.getCurrentHomeId())
                    .getHomeBean().getDeviceList()
            } catch (e: Exception) {
                return null
            }
        }

    /**
     * 读取设备的音频产品类型：解析 productRefBean.configMetas 中的 product_type 字段。
     * 
     * 
     * 返回原始类型值（而非展示名），因为录音时的 audioSource 推导依赖它。
     * 
     * @param dev 设备
     * @return `DeviceItem.TYPE_*` 之一；非音频设备返回 `null`
     */
    private fun getAudioProductType(dev: DeviceBean?): String? {
        try {
            if (dev == null || dev.getProductRefBean() == null) return null
            val configMetas = dev.getProductRefBean().getConfigMetas()
            if (configMetas == null) return null
            val meta = configMetas.get(AUDIO_PRODUCT_META_KEY)
            if (meta == null) return null
            val json = JSON.parseObject(meta.toString())
            if (json == null) return null
            val productType = json.getString("product_type")
            if (TextUtils.isEmpty(productType)) return null
            when (productType) {
                DeviceItem.TYPE_PRO, DeviceItem.TYPE_CARD, DeviceItem.TYPE_ENTRY, DeviceItem.TYPE_OS_BUDS -> return productType
                else -> return null
            }
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 产品类型原始值转展示名。
     * 
     * @param productType `DeviceItem.TYPE_*`
     * @return 展示用类别名
     */
    private fun categoryName(productType: String): String {
        when (productType) {
            DeviceItem.TYPE_PRO -> return getString(R.string.native_category_pro_earphone)
            DeviceItem.TYPE_CARD -> return getString(R.string.native_category_card)
            DeviceItem.TYPE_ENTRY -> return getString(R.string.native_category_entry_earphone)
            DeviceItem.TYPE_OS_BUDS -> return getString(R.string.native_category_os_buds)
            else -> return productType
        }
    }

    /**
     * 设备下拉项。[.productType] 决定录音时的 audioSource 取值，见各录音页的推导逻辑。
     */
    class DeviceItem(
        val devId: String?, @JvmField val displayName: String?,
        /** 产品类型原始值；PHONE 项为 `null`。  */
        @JvmField val productType: String?,
        /** 设备是否在线。离线设备无法开始录音，PHONE 恒为 true。  */
        @JvmField val online: Boolean
    ) {
        val isPhone: Boolean
            /**
             * @return 是否为手机本地录音项
             */
            get() = DEVICE_ID_PHONE == devId

        companion object {
            /** Pro 版耳机。  */
            const val TYPE_PRO: String = "pro_version"

            /** 入门版耳机。  */
            const val TYPE_ENTRY: String = "entry_version"

            /** 录音卡片。  */
            const val TYPE_CARD: String = "card"

            /** OS 耳机。  */
            const val TYPE_OS_BUDS: String = "os_buds"

            /** 手机本地录音（非真实设备）。  */
            const val TYPE_PHONE: String = "phone"
        }
    }

    companion object {
        /** 手机本地录音使用的固定设备 ID。  */
        protected const val DEVICE_ID_PHONE: String = "PHONE"

        /** 音频类设备的产品配置 meta key，用于从 configMetas 中解析 product_type。  */
        private const val AUDIO_PRODUCT_META_KEY = "tyabiw4jrd"
    }
}
