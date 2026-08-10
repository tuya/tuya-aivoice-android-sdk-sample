package com.tuya.smart.ai_voice.nativeui;

import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.tuya.smart.ai_voice.R;
import com.tuya.smart.ai_voice.nativeui.base.NativeDemoBaseActivity;

/**
 * 更多 Native 能力清单页。
 * <p>
 * 纯导航页，不调用任何 SDK 接口，因此 {@link #registerListeners()} /
 * {@link #unregisterListeners()} 为空实现、日志区关闭。
 * 清单条目与 {@code docs/modules/} 下的模块编号一一对应。
 * <p>
 * 未列出的模块：
 * <ul>
 *     <li>模块 1 录音控制、模块 7 设备能力 → {@link NativeRecordActivity}</li>
 *     <li>模块 2 转写/总结 → {@link NativeRecordDetailActivity}</li>
 *     <li>模块 3 文件管理 → {@link NativeRecordListActivity}</li>
 *     <li>模块 10 文本翻译 → 属于 AI Translate 业务（businessType=1），本 Demo 不实现</li>
 * </ul>
 */
public class NativeApiListActivity extends NativeDemoBaseActivity {

    /**
     * 清单条目定义。{@code target} 为 null 表示该模块不可进入（仅作说明展示）。
     */
    private static final Entry[] ENTRIES = {
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
                    null), //NativeQuickEntryActivity.class
            new Entry(R.string.native_api_entry_translate_title,
                    R.string.native_api_entry_translate_desc,
                    R.string.native_api_entry_translate_api,
                    null),
    };

    @Override
    protected int getContentLayoutId() {
        return R.layout.activity_native_api_list;
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
     * @param inflater  布局填充器
     * @param parent    父容器，仅用于测量参数，不附加
     * @param entry     条目定义
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
        /** 目标页；null 表示本 Demo 不实现该模块。 */
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
