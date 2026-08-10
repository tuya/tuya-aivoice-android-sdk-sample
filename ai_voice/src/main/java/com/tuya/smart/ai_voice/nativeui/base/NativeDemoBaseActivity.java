package com.tuya.smart.ai_voice.nativeui.base;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.thingclips.smart.api.service.MicroServiceManager;
import com.thingclips.smart.commonbiz.bizbundle.family.api.AbsBizBundleFamilyService;
import com.thingclips.smart.home.sdk.ThingHomeSdk;
import com.thingclips.smart.plugin.tuniaudiodetectmanager.ThingAudioDetectManagerNative;
import com.thingclips.smart.sdk.bean.DeviceBean;
import com.tuya.smart.ai_voice.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Native 能力演示页基类，只提供脚手架，不封装任何业务 API。
 * <p>
 * 提供四样东西：
 * <ol>
 *     <li>统一布局：顶部栏 + 内容容器 + 可选事件日志区（{@link #enableLogPanel()} 控制）</li>
 *     <li>{@link #appendLog(String)} / {@link #toast(String)} / {@link #runOnUi(Runnable)}
 *         —— SDK 回调一律在子线程，更新 UI 必须切回主线程</li>
 *     <li>{@link #registerListeners()} / {@link #unregisterListeners()} 抽象方法，
 *         分别在 {@code onCreate} / {@code onDestroy} 自动调用。
 *         <b>SDK 的事件监听必须 add/remove 成对且传同一实例</b>，用模板强制住这一点</li>
 *     <li>{@link #setupDeviceSpinner(Spinner)} —— PHONE + 家庭音频设备的下拉加载</li>
 * </ol>
 * 各模块的 SDK 调用一律留在子类页面内，保证单个页面可独立阅读、整体复制。
 */
public abstract class NativeDemoBaseActivity extends AppCompatActivity {

    /** 手机本地录音使用的固定设备 ID。 */
    protected static final String DEVICE_ID_PHONE = "PHONE";

    /** 音频类设备的产品配置 meta key，用于从 configMetas 中解析 product_type。 */
    private static final String AUDIO_PRODUCT_META_KEY = "tyabiw4jrd";

    private static final String LOG_TIME_PATTERN = "HH:mm:ss.SSS";

    /** SDK 单例入口，接入者只需面向它编程。 */
    protected final ThingAudioDetectManagerNative manager =
            ThingAudioDetectManagerNative.getInstance();

    /** 主线程 Handler，供 {@link #runOnUi(Runnable)} 使用。 */
    protected final Handler main = new Handler(Looper.getMainLooper());

    private TextView logText;
    private ScrollView logScroll;

    /** 设备下拉数据源：index 0 固定为 PHONE，其余为过滤后的家庭音频设备。 */
    protected final List<DeviceItem> deviceItems = new ArrayList<>();
    private Spinner deviceSpinner;

    // ===================== 子类实现 =====================

    /**
     * 内容区布局，注入到脚手架的内容容器中。
     *
     * @return 布局资源 ID
     */
    @LayoutRes
    protected abstract int getContentLayoutId();

    /**
     * 页面标题。
     *
     * @return 标题文案资源 ID
     */
    @StringRes
    protected abstract int getTitleResId();

    /**
     * 注册 SDK 事件监听。在 {@code onCreate} 中自动调用。
     * <p>
     * 监听实例必须用字段持有，{@link #unregisterListeners()} 中传同一引用注销。
     */
    protected abstract void registerListeners();

    /**
     * 注销 SDK 事件监听。在 {@code onDestroy} 中自动调用。
     */
    protected abstract void unregisterListeners();

    /**
     * 是否显示底部事件日志区。纯展示型页面可返回 {@code false}。
     *
     * @return true 显示（默认）
     */
    protected boolean enableLogPanel() {
        return true;
    }

    /**
     * 内容区是否需要基类提供的滚动容器。
     * <p>
     * 表单型页面返回 {@code true}（默认）；内部已有 RecyclerView 等滚动控件的页面
     * 必须返回 {@code false}，否则嵌套滚动会失效。
     *
     * @return true 由基类包一层 NestedScrollView
     */
    protected boolean useScrollContainer() {
        return true;
    }

    // ===================== 生命周期 =====================

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_native_demo_base);

        ((TextView) findViewById(R.id.tv_title)).setText(getTitleResId());
        findViewById(R.id.iv_back).setOnClickListener(v -> finish());

        LayoutInflater inflater = LayoutInflater.from(this);
        FrameLayout contentRoot = findViewById(R.id.fl_content_root);
        if (useScrollContainer()) {
            inflater.inflate(R.layout.layout_native_scroll_container, contentRoot, true);
            LinearLayout container = findViewById(R.id.ll_content_container);
            inflater.inflate(getContentLayoutId(), container, true);
        } else {
            inflater.inflate(getContentLayoutId(), contentRoot, true);
        }

        View logPanel = findViewById(R.id.ll_log_panel);
        if (enableLogPanel()) {
            logText = findViewById(R.id.tv_log);
            logScroll = findViewById(R.id.sv_log);
            findViewById(R.id.tv_log_clear).setOnClickListener(v -> logText.setText(""));
        } else {
            logPanel.setVisibility(View.GONE);
        }

        onContentViewCreated();
        registerListeners();
    }

    /**
     * 内容区布局已注入、日志区已就绪时回调，子类在此绑定 View 与初始化数据。
     * <p>
     * 此时 {@link #registerListeners()} 尚未调用，可安全初始化监听所依赖的字段。
     */
    protected abstract void onContentViewCreated();

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterListeners();
        main.removeCallbacksAndMessages(null);
    }

    // ===================== 通用工具 =====================

    /**
     * 追加一条事件日志并滚动到底部。仅在 {@link #enableLogPanel()} 为 true 时生效。
     * <p>
     * 必须在主线程调用。
     *
     * @param msg 日志内容
     */
    protected void appendLog(String msg) {
        if (logText == null) return;
        String time = new SimpleDateFormat(LOG_TIME_PATTERN, Locale.getDefault()).format(new Date());
        logText.append("[" + time + "] " + msg + "\n\n");
        logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    /**
     * 切回主线程执行。SDK 回调可能在子线程，更新 UI 前一律走这里。
     *
     * @param r 待执行任务
     */
    protected void runOnUi(Runnable r) {
        main.post(r);
    }

    protected void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ===================== 设备下拉 =====================

    /**
     * 装配设备下拉：index 0 固定为 PHONE（手机本地录音），其余为当前家庭下
     * 符合音频能力（Pro 耳机 / 入门耳机 / 录音卡片 / OS 耳机）的设备。
     * <p>
     * 这是环境依赖而非某个模块的能力演示，故放在基类；
     * 选中项通过 {@link #currentDeviceId()} / {@link #currentDeviceName()} 读取。
     *
     * @param spinner 目标下拉控件
     */
    protected void setupDeviceSpinner(@NonNull Spinner spinner) {
        this.deviceSpinner = spinner;
        deviceItems.clear();
        deviceItems.add(new DeviceItem(
                DEVICE_ID_PHONE, getString(R.string.native_device_phone), null, true));

        List<DeviceBean> homeDevices = getHomeDevices();
        if (homeDevices != null) {
            for (DeviceBean dev : homeDevices) {
                String productType = getAudioProductType(dev);
                if (productType == null) continue;
                String name = TextUtils.isEmpty(dev.getName()) ? dev.getDevId() : dev.getName();
                String display = getString(R.string.native_device_name_with_category,
                        name, categoryName(productType));
                deviceItems.add(new DeviceItem(
                        dev.getDevId(), display, productType, dev.getIsOnline()));
            }
        }

        List<String> names = new ArrayList<>();
        for (DeviceItem item : deviceItems) {
            names.add(item.displayName);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.item_spinner_native, names);
        adapter.setDropDownViewResource(R.layout.item_spinner_native_dropdown);
        spinner.setAdapter(adapter);
        spinner.setSelection(0);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                onDeviceSelected(deviceItems.get(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    /**
     * 设备下拉选中项变化。默认打日志，子类可覆写做联动。
     *
     * @param item 选中的设备
     */
    protected void onDeviceSelected(@NonNull DeviceItem item) {
        appendLog(getString(R.string.native_log_select_device, item.displayName, item.devId));
    }

    /**
     * @return 当前选中的设备 ID；未装配下拉时返回 {@link #DEVICE_ID_PHONE}
     */
    protected String currentDeviceId() {
        DeviceItem item = currentDeviceItem();
        return item == null ? DEVICE_ID_PHONE : item.devId;
    }

    /**
     * @return 当前选中的设备展示名；未装配下拉时返回 {@link #DEVICE_ID_PHONE}
     */
    protected String currentDeviceName() {
        DeviceItem item = currentDeviceItem();
        return item == null ? DEVICE_ID_PHONE : item.displayName;
    }

    /**
     * @return 当前选中的设备项；未装配下拉或列表为空时返回 {@code null}
     */
    @Nullable
    protected DeviceItem currentDeviceItem() {
        if (deviceSpinner == null) return null;
        int pos = deviceSpinner.getSelectedItemPosition();
        if (pos >= 0 && pos < deviceItems.size()) {
            return deviceItems.get(pos);
        }
        return null;
    }

    /**
     * @return 当前家庭的设备列表；家庭服务不可用时返回 {@code null}
     */
    @Nullable
    private List<DeviceBean> getHomeDevices() {
        try {
            AbsBizBundleFamilyService familyService = MicroServiceManager.getInstance()
                    .findServiceByInterface(AbsBizBundleFamilyService.class.getName());
            if (familyService == null) return null;
            return ThingHomeSdk.newHomeInstance(familyService.getCurrentHomeId())
                    .getHomeBean().getDeviceList();
        } catch (Exception e) {
            appendLog(getString(R.string.native_log_get_home_devices_exception, e.getMessage()));
            return null;
        }
    }

    /**
     * 读取设备的音频产品类型：解析 productRefBean.configMetas 中的 product_type 字段。
     * <p>
     * 返回原始类型值（而非展示名），因为录音时的 audioSource 推导依赖它。
     *
     * @param dev 设备
     * @return {@code DeviceItem.TYPE_*} 之一；非音频设备返回 {@code null}
     */
    @Nullable
    private String getAudioProductType(DeviceBean dev) {
        try {
            if (dev == null || dev.getProductRefBean() == null) return null;
            Map<String, Object> configMetas = dev.getProductRefBean().getConfigMetas();
            if (configMetas == null) return null;
            Object meta = configMetas.get(AUDIO_PRODUCT_META_KEY);
            if (meta == null) return null;
            JSONObject json = JSON.parseObject(meta.toString());
            if (json == null) return null;
            String productType = json.getString("product_type");
            if (TextUtils.isEmpty(productType)) return null;
            switch (productType) {
                case DeviceItem.TYPE_PRO:
                case DeviceItem.TYPE_CARD:
                case DeviceItem.TYPE_ENTRY:
                case DeviceItem.TYPE_OS_BUDS:
                    return productType;
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 产品类型原始值转展示名。
     *
     * @param productType {@code DeviceItem.TYPE_*}
     * @return 展示用类别名
     */
    private String categoryName(@NonNull String productType) {
        switch (productType) {
            case DeviceItem.TYPE_PRO: return getString(R.string.native_category_pro_earphone);
            case DeviceItem.TYPE_CARD: return getString(R.string.native_category_card);
            case DeviceItem.TYPE_ENTRY: return getString(R.string.native_category_entry_earphone);
            case DeviceItem.TYPE_OS_BUDS: return getString(R.string.native_category_os_buds);
            default: return productType;
        }
    }

    /**
     * 设备下拉项。{@link #productType} 决定录音时的 audioSource 取值，见各录音页的推导逻辑。
     */
    public static class DeviceItem {

        /** Pro 版耳机。 */
        public static final String TYPE_PRO = "pro_version";
        /** 入门版耳机。 */
        public static final String TYPE_ENTRY = "entry_version";
        /** 录音卡片。 */
        public static final String TYPE_CARD = "card";
        /** OS 耳机。 */
        public static final String TYPE_OS_BUDS = "os_buds";
        /** 手机本地录音（非真实设备）。 */
        public static final String TYPE_PHONE = "phone";

        public final String devId;
        public final String displayName;
        /** 产品类型原始值；PHONE 项为 {@code null}。 */
        @Nullable
        public final String productType;
        /** 设备是否在线。离线设备无法开始录音，PHONE 恒为 true。 */
        public final boolean online;

        public DeviceItem(String devId, String displayName, @Nullable String productType,
                          boolean online) {
            this.devId = devId;
            this.displayName = displayName;
            this.productType = productType;
            this.online = online;
        }

        /**
         * @return 是否为手机本地录音项
         */
        public boolean isPhone() {
            return DEVICE_ID_PHONE.equals(devId);
        }
    }
}
