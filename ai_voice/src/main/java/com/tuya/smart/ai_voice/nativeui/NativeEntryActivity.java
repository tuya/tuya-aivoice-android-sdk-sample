package com.tuya.smart.ai_voice.nativeui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.tuya.smart.ai_voice.R;
import com.tuya.smart.ai_voice.nativeui.base.NativeDemoBaseActivity;
import com.tuya.smart.ai_voice.nativeui.widget.InjectEndDispatcher;

/**
 * Native 能力总入口。所有 Native 演示页的唯一导航页。
 * <p>
 * 纯导航，不调用任何 SDK 接口，因此 {@link #registerListeners()} /
 * {@link #unregisterListeners()} 为空实现。条目与 {@code docs/modules/} 下的模块编号一一对应。
 *
 * <h3>作为调试入口使用</h3>
 * 本组件以 aar 形式提供给接入方时，这一页是唯一需要知道的入口，
 * 接入方从自己的代码里调 {@link #start(Context)} 进入。
 * <p>
 * 本页与其余演示页一样是 {@code exported="false"}：这是一组调试页，
 * 没有理由允许其它应用拉起。<b>接入方的正式包中请移除对本组件的依赖。</b>
 *
 * <h3>进不去的两个模块</h3>
 * <ul>
 *     <li>模块 2 转写 / 总结 —— {@link NativeRecordDetailActivity} 必须带
 *         {@code EXTRA_RECORD_ID}，只能从文件管理页点进某条录音</li>
 *     <li>模块 10 文本翻译 —— 属于 AI Translate 业务（{@code businessType=1}），本 Demo 不实现</li>
 * </ul>
 * 这两条以置灰形式保留在清单里：不列出来会让人误以为 SDK 不支持。
 */
public class NativeEntryActivity extends NativeDemoBaseActivity {

    /**
     * 能力清单。按模块编号排列，调试类工具置底。
     * <p>
     * {@code target} 为 null 表示该条目不能直接进入，条目会整体置灰，原因写在 desc 里。
     */
    private static final Entry[] ENTRIES = {
            new Entry(R.string.native_api_entry_record_title,
                    R.string.native_api_entry_record_desc,
                    R.string.native_api_entry_record_api,
                    NativeRecordActivity.class),
            new Entry(R.string.native_api_entry_detail_title,
                    R.string.native_api_entry_detail_desc,
                    R.string.native_api_entry_detail_api,
                    null),
            new Entry(R.string.native_api_entry_file_title,
                    R.string.native_api_entry_file_desc,
                    R.string.native_api_entry_file_api,
                    NativeRecordListActivity.class),
            new Entry(R.string.native_api_entry_offline_title,
                    R.string.native_api_entry_offline_desc,
                    R.string.native_api_entry_offline_api,
                    NativeOfflineFileActivity.class),
            new Entry(R.string.native_api_entry_import_title,
                    R.string.native_api_entry_import_desc,
                    R.string.native_api_entry_import_api,
                    NativeAudioImportActivity.class),
            new Entry(R.string.native_api_entry_cloud_title,
                    R.string.native_api_entry_cloud_desc,
                    R.string.native_api_entry_cloud_api,
                    NativeCloudSyncActivity.class),
            new Entry(R.string.native_api_entry_merge_title,
                    R.string.native_api_entry_merge_desc,
                    R.string.native_api_entry_merge_api,
                    NativeMergeRecordActivity.class),
            new Entry(R.string.native_api_entry_quick_title,
                    R.string.native_api_entry_quick_desc,
                    R.string.native_api_entry_quick_api,
                    NativeQuickEntryActivity.class),
            new Entry(R.string.native_api_entry_translate_title,
                    R.string.native_api_entry_translate_desc,
                    R.string.native_api_entry_translate_api,
                    null),
            new Entry(R.string.native_api_entry_inject_title,
                    R.string.native_api_entry_inject_desc,
                    R.string.native_api_entry_inject_api,
                    NativeAudioInjectActivity.class),
            new Entry(R.string.native_api_entry_batch_title,
                    R.string.native_api_entry_batch_desc,
                    R.string.native_api_entry_batch_api,
                    NativeInjectBatchActivity.class),
    };

    /**
     * 打开 Native 能力总入口。
     * <p>
     * 允许传入 Application / Service 等非 Activity 的 Context，内部会补上
     * {@code FLAG_ACTIVITY_NEW_TASK}——缺了它从非 Activity Context 启动会直接抛异常。
     *
     * @param context 上下文
     */
    public static void start(@NonNull Context context) {
        // 进入调试入口即安装灌流结束的分发器：所有能开启灌流的路径都必经这里，
        // 装在这里既保证「有灌流就一定有人接事件」，也保证接入方没打开过调试入口时
        // 组件不往他的进程里注册任何全局监听。
        InjectEndDispatcher.get().install(context);
        Intent intent = new Intent(context, NativeEntryActivity.class);
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    @Override
    protected int getContentLayoutId() {
        return R.layout.activity_native_entry;
    }

    @Override
    protected int getTitleResId() {
        return R.string.native_api_list_title;
    }

    @Override
    protected void onContentViewCreated() {
        LinearLayout container = findViewById(R.id.ll_entry_container);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (Entry entry : ENTRIES) {
            container.addView(createEntryView(inflater, container, entry));
        }
    }

    /**
     * 构建单个清单条目视图。不可进入的条目整体置灰且不响应点击。
     *
     * @param inflater 布局填充器
     * @param parent   父容器，仅用于测量参数，不附加
     * @param entry    条目定义
     * @return 条目视图
     */
    private View createEntryView(LayoutInflater inflater, LinearLayout parent, Entry entry) {
        View item = inflater.inflate(R.layout.item_native_api_entry, parent, false);
        ((TextView) item.findViewById(R.id.tv_entry_title)).setText(entry.title);
        ((TextView) item.findViewById(R.id.tv_entry_desc)).setText(entry.desc);
        ((TextView) item.findViewById(R.id.tv_entry_api)).setText(entry.api);
        if (entry.target == null) {
            item.setAlpha(0.45f);
        } else {
            item.setOnClickListener(v -> startActivity(new Intent(this, entry.target)));
        }
        return item;
    }

    @Override
    protected void registerListeners() {
        // 纯导航页，无事件监听
    }

    @Override
    protected void unregisterListeners() {
        // 纯导航页，无事件监听
    }

    /** 清单条目。 */
    private static class Entry {
        @StringRes
        final int title;
        @StringRes
        final int desc;
        @StringRes
        final int api;
        /** 目标页；null 表示不能从本页直接进入。 */
        @Nullable
        final Class<?> target;

        Entry(@StringRes int title, @StringRes int desc, @StringRes int api,
              @Nullable Class<?> target) {
            this.title = title;
            this.desc = desc;
            this.api = api;
            this.target = target;
        }
    }
}
