package com.tuya.smart.ai_voice.nativeui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.tuya.smart.ai_voice.R
import com.tuya.smart.ai_voice.databinding.ActivityNativeEntryBinding
import com.tuya.smart.ai_voice.databinding.ItemNativeApiEntryBinding
import com.tuya.smart.ai_voice.nativeui.base.NativeDemoBaseActivity
import com.tuya.smart.ai_voice.nativeui.widget.InjectEndDispatcher.Companion.get

/**
 * Native 能力总入口。所有 Native 演示页的唯一导航页。
 * 
 * 
 * 纯导航，不调用任何 SDK 接口，因此 [.registerListeners] /
 * [.unregisterListeners] 为空实现。条目与 `docs/modules/` 下的模块编号一一对应。
 * 
 * <h3>作为调试入口使用</h3>
 * 本组件以 aar 形式提供给接入方时，这一页是唯一需要知道的入口，
 * 接入方从自己的代码里调 [.start] 进入。
 * 
 * 
 * 本页与其余演示页一样是 `exported="false"`：这是一组调试页，
 * 没有理由允许其它应用拉起。**接入方的正式包中请移除对本组件的依赖。**
 * 
 * <h3>进不去的两个模块</h3>
 * 
 *  * 模块 2 转写 / 总结 —— [NativeRecordDetailActivity] 必须带
 * `EXTRA_RECORD_ID`，只能从文件管理页点进某条录音
 *  * 模块 10 文本翻译 —— 属于 AI Translate 业务（`businessType=1`），本 Demo 不实现
 * 
 * 这两条以置灰形式保留在清单里：不列出来会让人误以为 SDK 不支持。
 */
class NativeEntryActivity : NativeDemoBaseActivity() {
    /** 内容布局绑定。  */
    private lateinit var binding: ActivityNativeEntryBinding


    override val contentLayoutId: Int
        get() = R.layout.activity_native_entry

    override val titleResId: Int
        get() = R.string.native_api_list_title

    override fun onContentViewCreated() {
        binding = ActivityNativeEntryBinding.bind(contentView())
        val container = binding.llEntryContainer
        val inflater = LayoutInflater.from(this)
        for (entry in ENTRIES) {
            entry?.let { container.addView(createEntryView(inflater, container, it)) }
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
    private fun createEntryView(
        inflater: LayoutInflater,
        parent: LinearLayout?,
        entry: Entry
    ): View {
        val itemBinding = ItemNativeApiEntryBinding.inflate(inflater, parent, false)
        val item = itemBinding.root
        itemBinding.tvEntryTitle.setText(entry.title)
        itemBinding.tvEntryDesc.setText(entry.desc)
        itemBinding.tvEntryApi.setText(entry.api)
        if (entry.target == null) {
            item.setAlpha(0.45f)
        } else if (entry.target == NativeRecordActivity::class.java) {
            // 录音条目先弹模式选择（发起录音 = 弹窗），选定后带 extra 进对应控制页
            item.setOnClickListener(View.OnClickListener { v: View? -> showRecordModeDialog() })
        } else {
            item.setOnClickListener(View.OnClickListener { v: View? ->
                startActivity(
                    Intent(
                        this,
                        entry.target
                    )
                )
            })
        }
        return item
    }

    /**
     * 发起录音的模式弹窗。
     * 
     * 
     * 「只保留选择录音模式」的发起形态：行式条目（模式名 + 一句说明），
     * 选定后把模式名经 [NativeRecordActivity.EXTRA_RECORD_MODE] 传给控制页，
     * 对话翻译独立成页。弹窗即发起——没有单独的 setup 页。
     */
    private fun showRecordModeDialog() {
        val content = LayoutInflater.from(this).inflate(R.layout.dialog_native_mode_picker, null)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.native_record_mode_dialog_title)
            .setView(content)
            .create()
        dispatchRow(
            content,
            R.id.mode_row_live,
            dialog,
            { NativeRecordActivity.Companion.start(this, "LIVE") })
        dispatchRow(
            content,
            R.id.mode_row_phone,
            dialog,
            { NativeRecordActivity.Companion.start(this, "PHONE") })
        dispatchRow(
            content,
            R.id.mode_row_realtime,
            dialog,
            { NativeRecordActivity.Companion.start(this, "REALTIME") })
        dispatchRow(
            content,
            R.id.mode_row_simultaneous,
            dialog,
            { NativeRecordActivity.Companion.start(this, "SIMULTANEOUS") })
        dispatchRow(
            content,
            R.id.mode_row_f2f,
            dialog,
            { NativeFaceToFaceActivity.Companion.start(this) })
        dialog.show()
    }

    private fun dispatchRow(content: View, rowId: Int, dialog: AlertDialog, action: () -> Unit) {
        content.findViewById<View>(rowId)
            .setOnClickListener(View.OnClickListener { v: View? ->
                // 先关掉弹窗再跳转，避免返回时弹窗还挂在页面上
                dialog.dismiss()
                action()
            })
    }

    override fun registerListeners() {
        // 纯导航页，无事件监听
    }

    override fun unregisterListeners() {
        // 纯导航页，无事件监听
    }

    /** 清单条目。  */
    private class Entry(
        @field:StringRes @param:StringRes val title: Int,
        @field:StringRes @param:StringRes val desc: Int,
        @field:StringRes @param:StringRes val api: Int,
        /** 目标页；null 表示不能从本页直接进入。  */
        val target: Class<*>?
    )

    companion object {
        /**
         * 能力清单。按模块编号排列，调试类工具置底。
         * 
         * 
         * `target` 为 null 表示该条目不能直接进入，条目会整体置灰，原因写在 desc 里。
         */
        private val ENTRIES = arrayOf<Entry?>(
            Entry(
                R.string.native_api_entry_record_title,
                R.string.native_api_entry_record_desc,
                R.string.native_api_entry_record_api,
                NativeRecordActivity::class.java
            ),
            Entry(
                R.string.native_api_entry_detail_title,
                R.string.native_api_entry_detail_desc,
                R.string.native_api_entry_detail_api,
                null
            ),
            Entry(
                R.string.native_api_entry_file_title,
                R.string.native_api_entry_file_desc,
                R.string.native_api_entry_file_api,
                NativeRecordListActivity::class.java
            ),
            Entry(
                R.string.native_api_entry_offline_title,
                R.string.native_api_entry_offline_desc,
                R.string.native_api_entry_offline_api,
                NativeOfflineFileActivity::class.java
            ),
            Entry(
                R.string.native_api_entry_import_title,
                R.string.native_api_entry_import_desc,
                R.string.native_api_entry_import_api,
                NativeAudioImportActivity::class.java
            ),
            Entry(
                R.string.native_api_entry_cloud_title,
                R.string.native_api_entry_cloud_desc,
                R.string.native_api_entry_cloud_api,
                NativeCloudSyncActivity::class.java
            ),
            Entry(
                R.string.native_api_entry_merge_title,
                R.string.native_api_entry_merge_desc,
                R.string.native_api_entry_merge_api,
                NativeMergeRecordActivity::class.java
            ),
            Entry(
                R.string.native_api_entry_quick_title,
                R.string.native_api_entry_quick_desc,
                R.string.native_api_entry_quick_api,
                NativeQuickEntryActivity::class.java
            ),
            Entry(
                R.string.native_api_entry_translate_title,
                R.string.native_api_entry_translate_desc,
                R.string.native_api_entry_translate_api,
                null
            ),
            Entry(
                R.string.native_api_entry_inject_title,
                R.string.native_api_entry_inject_desc,
                R.string.native_api_entry_inject_api,
                NativeAudioInjectActivity::class.java
            ),
            Entry(
                R.string.native_api_entry_batch_title,
                R.string.native_api_entry_batch_desc,
                R.string.native_api_entry_batch_api,
                NativeInjectBatchActivity::class.java
            ),
        )

        /**
         * 打开 Native 能力总入口。
         * 
         * 
         * 允许传入 Application / Service 等非 Activity 的 Context，内部会补上
         * `FLAG_ACTIVITY_NEW_TASK`——缺了它从非 Activity Context 启动会直接抛异常。
         * 
         * @param context 上下文
         */
        fun start(context: Context) {
            // 进入调试入口即安装灌流结束的分发器：所有能开启灌流的路径都必经这里，
            // 装在这里既保证「有灌流就一定有人接事件」，也保证接入方没打开过调试入口时
            // 组件不往他的进程里注册任何全局监听。
            get().install(context)
            val intent = Intent(context, NativeEntryActivity::class.java)
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
