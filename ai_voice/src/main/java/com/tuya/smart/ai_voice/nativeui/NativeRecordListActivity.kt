package com.tuya.smart.ai_voice.nativeui

import android.content.DialogInterface
import android.content.Intent
import android.text.Html
import android.text.TextUtils
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.thingclips.smart.earphone.enhance.api.bean.AudioSearchMixItem
import com.thingclips.smart.earphone.enhance.api.bean.AudioSearchMixParams
import com.thingclips.smart.earphone.enhance.api.bean.FilesParam
import com.thingclips.smart.earphone.enhance.api.bean.RecordTransferResultBean
import com.thingclips.smart.earphone.enhance.api.bean.ttt.RecordUpdateInfo
import com.thingclips.smart.earphone.enhance.api.listener.IRecordCallBack
import com.thingclips.smart.earphone.enhance.api.listener.IRecordFileUpdateCallback
import com.thingclips.smart.sdk.api.IResultCallback
import com.tuya.smart.ai_voice.R
import com.tuya.smart.ai_voice.databinding.ActivityNativeRecordListBinding
import com.tuya.smart.ai_voice.nativeui.base.NativeDemoBaseActivity
import java.util.Locale

/**
 * 模块 3 · 文件管理演示页。
 * 
 * 
 * 覆盖录音文件的查询、筛选、搜索、删除与空间统计：
 * 
 *  * 列表 —— `getRecordTransferResultList`，`pageSize` 传 `null` 一次查全部
 *  * 筛选 —— `FilesParam` 的 `recordType` / `transfer` / `orderBy`
 *  * 搜索 —— `searchRecordTransferResult`，命中片段带 `<em>` 标记，需转成富文本高亮
 *  * 空间统计 —— `getAudioFilesSize`，返回本地音频占用字节数
 *  * 标签 —— `RecordTransferResultBean.tags`，只读展示，最多 3 个、其余折叠为 +N
 *  * 数据变更 —— [IRecordFileUpdateCallback] 的四个回调，分别对应局部刷新与全量刷新
 * 
 * 
 * <h3>下拉刷新会顺带发起云同步</h3>
 * 只重拉本地列表拿不到别的设备新增的录音，因此下拉时同时调 `syncNoteRecord`
 * （模块 6）。两件事互不等待：云端记录落到本地库后 SDK 会推
 * `onRecordListSyncSuccess`，列表在那里再全量重拉一次。
 * 
 * 
 * 这也是 `syncNoteRecord` 的推荐调用时机之一（另一个是首页首次加载），
 * 不必给用户一个「立即同步」按钮。
 * 
 * <h3>删除语义</h3>
 * <pre>
 * removeFileList(ids, true)          彻底删除（本地 + 云端），不可恢复
 * removeFileList(ids, false)         只删本地音频文件，记录保留，可重新下载
 * </pre>
 * 
 * 本页长按条目演示以上两种。`FilesParam.remove` 字段（回收站软删除）本页固定按
 * 「未删除」查询，不做筛选切换。
 */
class NativeRecordListActivity : NativeDemoBaseActivity() {
    /** 内容布局绑定。  */
    private lateinit var binding: ActivityNativeRecordListBinding
    private val adapter = NativeRecordListAdapter()
    private val dataList: MutableList<RecordTransferResultBean?> =
        ArrayList<RecordTransferResultBean?>()

    /** 加载中标志，防止筛选连续切换时并发请求。  */
    private var loading = false

    /** `syncNoteRecord` 是否在途，防止连续下拉重复发起。  */
    private var syncing = false

    /** 首次进入是否已主动刷新过，避免每次回到页面都重复触发云同步。  */
    private var firstRefreshDone = false

    /** 数据变更监听，用字段持有，remove 时传同一引用。  */
    private var updateCallback: IRecordFileUpdateCallback<MutableList<RecordUpdateInfo?>?>? = null

    override val contentLayoutId: Int
        get() = R.layout.activity_native_record_list

    override val titleResId: Int
        get() = R.string.native_record_list_title

    override fun useScrollContainer(): Boolean {
        // 内部是 RecyclerView，不能再套一层 NestedScrollView
        return false
    }

    override fun onContentViewCreated() {
        bindViews()
        setupFilters()
        loadAudioSize()
    }

    override fun onResume() {
        super.onResume()
        if (firstRefreshDone) return
        firstRefreshDone = true
        // 首次进入主动触发一次下拉刷新（含云同步）。post 到下一帧：
        // SwipeRefreshLayout 未完成 attach 时 setRefreshing(true) 不会显示刷新动画
        binding.nativeListRefresh.post {
            binding.nativeListRefresh.setRefreshing(true)
            onPullToRefresh()
        }
    }

    private fun bindViews() {
        binding = ActivityNativeRecordListBinding.bind(contentView())
        binding.nativeListRv.setLayoutManager(LinearLayoutManager(this))
        binding.nativeListRv.setAdapter(adapter)
        binding.nativeListRefresh.setOnRefreshListener { this.onPullToRefresh() }

        binding.btnSearch.setOnClickListener { search() }

        // 点击 → 详情页。传 recordId，详情页据其取详情并拿到 recordTransferId
        adapter.setOnItemClickListener { bean ->
            if (bean == null || TextUtils.isEmpty(bean.recordId)) {
                toast(getString(R.string.native_detail_toast_no_record_id))
                return@setOnItemClickListener
            }
            startActivity(
                Intent(this, NativeRecordDetailActivity::class.java)
                    .putExtra(NativeRecordDetailActivity.EXTRA_RECORD_ID, bean.recordId)
            )
        }
        adapter.setOnItemLongClickListener { item ->
            this.showDeleteOptions(item)
        }
    }

    // ===================== 筛选 =====================
    private fun setupFilters() {
        bindFilterSpinner(
            binding.spRecordType, intArrayOf(
                R.string.native_list_filter_type_all,
                R.string.native_record_type_0,
                R.string.native_record_type_1,
                R.string.native_record_type_5
            )
        )
        bindFilterSpinner(
            binding.spTransfer, intArrayOf(
                R.string.native_list_filter_transfer_all,
                R.string.native_transfer_0,
                R.string.native_transfer_1,
                R.string.native_transfer_2,
                R.string.native_transfer_3
            )
        )
        bindFilterSpinner(
            binding.spOrder, intArrayOf(
                R.string.native_list_order_record_time,
                R.string.native_list_order_file_id,
                R.string.native_list_order_update_at
            )
        )

        val reload: AdapterView.OnItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    loadList()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                }
            }
        binding.spRecordType.setOnItemSelectedListener(reload)
        binding.spTransfer.setOnItemSelectedListener(reload)
        binding.spOrder.setOnItemSelectedListener(reload)
    }

    private fun bindFilterSpinner(spinner: Spinner, labelResIds: IntArray) {
        val labels: MutableList<String?> = ArrayList<String?>()
        for (resId in labelResIds) {
            labels.add(getString(resId))
        }
        val adapter = ArrayAdapter<String?>(this, R.layout.item_spinner_native, labels)
        adapter.setDropDownViewResource(R.layout.item_spinner_native_dropdown)
        spinner.setAdapter(adapter)
    }

    /**
     * 录音类型筛选值。下拉第 0 项为「全部」，对应 `null`。
     * 
     * @return recordType 取值，null 表示不限
     */
    private fun selectedRecordType(): Int? {
        val pos = binding.spRecordType.getSelectedItemPosition()
        if (pos == FILTER_ALL_POSITION) return null
        // 下拉项依次为：电话(0)、会议(1)、音频导入(5)
        when (pos) {
            1 -> return 0
            2 -> return 1
            3 -> return 5
            else -> return null
        }
    }

    /**
     * 转写状态筛选值。
     * 
     * @return transfer 取值，null 表示不限
     */
    private fun selectedTransfer(): Int? {
        val pos = binding.spTransfer.getSelectedItemPosition()
        return if (pos == FILTER_ALL_POSITION) null else pos - 1
    }

    private fun selectedOrderBy(): Int {
        when (binding.spOrder.getSelectedItemPosition()) {
            1 -> return ORDER_BY_FILE_ID
            2 -> return ORDER_BY_UPDATE_AT
            else -> return ORDER_BY_RECORD_TIME
        }
    }

    // ===================== 事件监听 =====================
    override fun registerListeners() {
        updateCallback = object : IRecordFileUpdateCallback<MutableList<RecordUpdateInfo?>?> {
            override fun onUpdate(infos: MutableList<RecordUpdateInfo?>?) {
                // 状态变更（如转写完成）：条目内容已变，按当前筛选条件重拉
                runOnUi {
                    loadList()
                }
            }

            override fun onRecordOperate(operate: String, infos: MutableList<RecordUpdateInfo?>?) {
                runOnUi {
                    // 新增会改变列表长度，必须全量刷新；改/删同样重拉以保证与筛选条件一致
                    loadList()
                }
            }

            override fun onRecordListSyncSuccess() {
                // 云端记录已落到本地库——这是「拿到云端数据」的唯一信号。
                // 不是 syncNoteRecord 的 onSuccess（那只表示任务已启动），
                // 也不是 SyncObserver 的下载回调（那管的是音频文件字节流）。
                // 本地数据可能大批变化，全量刷新
                runOnUi {
                    loadList()
                }
            }

            override fun onUpdateWitheTags(infos: MutableList<RecordUpdateInfo?>?) {
                // 本页对四个变更回调统一采取全量重拉：列表始终跟着当前筛选条件走，
                // 重拉最简单也最不容易出错。
                // 事件里其实带了最新的 tags，够做局部刷新——
                // NativeRecordDetailActivity 就是那么做的（只有一条记录，粒度天然更细）。
                // 两种粒度都成立，按页面职责选即可。
                runOnUi { loadList() }
            }
        }
        updateCallback?.let { manager.addFileRecordUpdateListener(it) }
    }

    override fun unregisterListeners() {
        if (updateCallback != null) {
            updateCallback?.let { manager.removeFileRecordUpdateListener(it) }
            updateCallback = null
        }
    }

    private fun size(infos: MutableList<RecordUpdateInfo?>?): Int {
        return if (infos == null) 0 else infos.size
    }

    // ===================== 下拉刷新 =====================
    /**
     * 下拉刷新：**发起一次云同步 + 重拉本地列表**，两件事互不等待。
     * 
     * 
     * 用户下拉的语义是「给我最新数据」，只重拉本地只能拿到本机已有的记录，
     * 别的设备新增的录音要靠 `syncNoteRecord` 才会下来。
     * 
     * 
     * 云端数据什么时候到、要不要再刷一次列表，**不由这里决定**——
     * 记录落到本地库时 SDK 会推 `onRecordListSyncSuccess`，
     * 列表在那里再全量重拉一次。所以此处不必等同步结果。
     */
    private fun onPullToRefresh() {
        syncNoteRecord()
        loadList()
    }

    /**
     * 发起云同步。
     * 
     * 
     * 两道防重：本地 [.syncing] 标志拦住连续下拉；即便漏过，底层也会以
     * `onError("10213")` 拒绝——那个码**不是失败**，而是「正在同步中」的确定信号。
     * 
     * 
     * 同步是后台行为、用户没有显式发起，因此**失败一律不提示**，
     * 避免下拉刷新时弹出用户看不懂的错误。
     */
    private fun syncNoteRecord() {
        if (syncing) return
        syncing = true
        manager.syncNoteRecord(object : IResultCallback {
            override fun onSuccess() {
                // 仅表示任务已启动；云端记录到位后走 onRecordListSyncSuccess
                runOnUi { syncing = false }
            }

            override fun onError(code: String?, error: String?) {
                // 常见的是 10213「底层已在同步中」——它不是失败，只说明这次不必再发一遍。
                // 其余错误（网络等）也一样不提示：用户只是下拉刷新，不该看到同步的报错。
                runOnUi { syncing = false }
            }
        })
    }

    // ===================== 列表加载 =====================
    /**
     * 拉取录音列表。
     * 
     * 
     * **不分页**：`lastFileId` 与 `pageSize` 都传 `null`，
     * 一次取回符合筛选条件的全部记录。`FilesParam` 支持游标式分页
     * （`lastFileId` 传上一页末条的 `recordTransferId`），
     * 录音记录量级有限，本页不做，筛选变更或下拉刷新时整体重拉。
     */
    private fun loadList() {
        if (loading) return
        loading = true
        if (dataList.isEmpty()) {
            showTip(getString(R.string.native_list_loading), false)
        }

        val param = FilesParam(
            null,  // directoryId：不限目录
            selectedRecordType(),
            null,  // deviceId：不限设备
            selectedTransfer(),
            null,  // source：不限来源
            false,  // remove：只查未删除的记录
            selectedOrderBy(),
            ORDER_DESC,
            null,  // lastFileId：不分页
            null
        ) // pageSize：为空即查询全部

        manager.getRecordTransferResultList(
            param,
            object : IRecordCallBack<MutableList<RecordTransferResultBean?>?> {
                override fun onSuccess(result: MutableList<RecordTransferResultBean?>?) {
                    runOnUi { onListLoaded(result) }
                }

                override fun onError(code: String?, error: String?) {
                    runOnUi {
                        loading = false
                        binding.nativeListRefresh.setRefreshing(false)
                        if (dataList.isEmpty()) {
                            showTip(getString(R.string.native_list_load_fail), false)
                        } else {
                            toast(getString(R.string.native_list_load_fail))
                        }
                    }
                }
            })
    }

    /**
     * 渲染列表结果，整体替换旧数据。
     * 
     * @param result 查询结果，可能为 null 或空
     */
    private fun onListLoaded(result: MutableList<RecordTransferResultBean?>?) {
        loading = false
        binding.nativeListRefresh.setRefreshing(false)
        dataList.clear()
        if (result == null || result.isEmpty()) {
            adapter.clear()
            showTip(getString(R.string.native_list_empty), false)
            return
        }
        dataList.addAll(result)
        adapter.submitList(result)
        showTip(null, true)
    }

    // ===================== 搜索 =====================
    /**
     * 关键词搜索，跨标题 / 标签 / 摘要 / 转写正文。
     * 
     * 
     * 返回的 `title` / `summary` / `content` 中，命中部分被
     * `<em>` 包裹，需按富文本渲染才能看到高亮；此处以弹窗形式展示结果。
     */
    private fun search() {
        val keyword = binding.etSearch.getText().toString().trim { it <= ' ' }
        if (TextUtils.isEmpty(keyword)) {
            loadList()
            return
        }
        // keyword 是保留字段，实际检索只看 content
        val param = AudioSearchMixParams(
            null, keyword, SEARCH_FIRST_PAGE, SEARCH_PAGE_SIZE
        )
        manager.searchRecordTransferResult(
            param,
            object : IRecordCallBack<ArrayList<AudioSearchMixItem>?> {
            override fun onSuccess(result: ArrayList<AudioSearchMixItem>?) {
                    runOnUi { showSearchResult(result) }
                }

                override fun onError(code: String?, error: String?) {
                    runOnUi {
                        toast(getString(R.string.native_list_log_search_fail, code, error))
                    }
                }
            })
    }

    private fun showSearchResult(result: MutableList<AudioSearchMixItem>?) {
        if (result == null || result.isEmpty()) {
            toast(getString(R.string.native_list_search_empty))
            return
        }
        val sb = StringBuilder()
        for (item in result) {
            sb.append("<b>").append(nullToDash(item.title)).append("</b><br/>")
            if (!TextUtils.isEmpty(item.summary)) {
                sb.append(item.summary).append("<br/>")
            }
            if (!TextUtils.isEmpty(item.content)) {
                sb.append(item.content).append("<br/>")
            }
            sb.append("<br/>")
        }
        // <em> 是接口约定的高亮标记，交给 Html 解析成斜体富文本
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.native_list_search_result_title, result.size))
            .setMessage(Html.fromHtml(sb.toString(), Html.FROM_HTML_MODE_COMPACT))
            .setPositiveButton(R.string.native_list_delete_cancel, null)
            .show()
    }

    private fun nullToDash(s: String?): String {
        return if (s.isNullOrEmpty()) "-" else s
    }

    // ===================== 空间统计 =====================
    /** 查询本地音频占用空间（字节）。  */
    private fun loadAudioSize() {
        manager.getAudioFilesSize(object : IRecordCallBack<Int?> {
            override fun onSuccess(bytes: Int?) {
                runOnUi {
                    val value = if (bytes == null) 0L else bytes.toLong()
                    binding.tvAudioSize.setText(
                        getString(
                            R.string.native_list_audio_size,
                            String.format(Locale.getDefault(), "%.1f", value * 1f / BYTES_PER_MB)
                        )
                    )
                }
            }

            override fun onError(code: String?, error: String?) {
                toastError(code, error)
            }
        })
    }

    // ===================== 删除 =====================
    /**
     * 长按弹出删除选项，演示 `removeFileList` 的两种单条语义。
     * 
     * @param item 目标条目
     */
    private fun showDeleteOptions(item: RecordTransferResultBean) {
        val rtid = item.recordTransferId
        if (rtid == null || rtid <= 0) {
            toast(getString(R.string.native_list_delete_no_id))
            return
        }
        val name =
            if (TextUtils.isEmpty(item.name)) getString(R.string.native_unnamed) else item.name
        val options = arrayOf<String?>(
            getString(R.string.native_list_delete_all),
            getString(R.string.native_list_delete_audio_only)
        )
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(
                options,
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                    val deleteAll = which == 0
                    if (deleteAll) {
                        confirmDeleteAll(rtid, name)
                    } else {
                        deleteFile(rtid, false)
                    }
                })
            .show()
    }

    /** 彻底删除前的二次确认——本地与云端都会删掉，不可恢复。  */
    private fun confirmDeleteAll(recordTransferId: Long, name: String?) {
        AlertDialog.Builder(this)
            .setTitle(R.string.native_list_delete_title)
            .setMessage(getString(R.string.native_list_delete_msg, name))
            .setPositiveButton(
                R.string.native_list_delete_confirm,
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    deleteFile(
                        recordTransferId,
                        true
                    )
                })
            .setNegativeButton(R.string.native_list_delete_cancel, null)
            .show()
    }

    /**
     * 删除单条。
     * 
     * @param recordTransferId 文件 ID
     * @param deleteAll        true 彻底删除（本地 + 云端）；false 只删本地音频，记录保留
     */
    private fun deleteFile(recordTransferId: Long, deleteAll: kotlin.Boolean) {
        manager.removeFileList(
            mutableListOf<Long?>(recordTransferId), deleteAll,
            object : IResultCallback {
                override fun onSuccess() {
                    runOnUi {
                        if (deleteAll) {
                            removeFromLocalList(recordTransferId)
                        } else {
                            // 只删了本地音频，记录仍在，重拉以刷新其同步状态
                            loadList()
                        }
                        loadAudioSize()
                        toast(getString(R.string.native_list_delete_success))
                    }
                }

                override fun onError(code: String?, error: String?) {
                    runOnUi { toast(getString(R.string.native_list_delete_fail, error)) }
                }
            })
    }

    /** 彻底删除成功后就地移除，不重拉列表。  */
    private fun removeFromLocalList(recordTransferId: Long) {
        val it = dataList.iterator()
        while (it.hasNext()) {
            val b = it.next()
            if (b != null && b.recordTransferId != null && b.recordTransferId == recordTransferId) {
                it.remove()
                break
            }
        }
        adapter.removeByRecordTransferId(recordTransferId)
        if (dataList.isEmpty()) {
            showTip(getString(R.string.native_list_empty), false)
        }
    }

    private fun showTip(text: String?, hide: kotlin.Boolean) {
        if (hide || text == null) {
            binding.nativeListTip.setVisibility(View.GONE)
        } else {
            binding.nativeListTip.setText(text)
            binding.nativeListTip.setVisibility(View.VISIBLE)
        }
    }

    companion object {
        /** 搜索结果页大小。  */
        private const val SEARCH_PAGE_SIZE = 20

        /** 搜索首页页码。  */
        private const val SEARCH_FIRST_PAGE = 1

        // ===== FilesParam.orderBy =====
        /** 按 fileId 排序。  */
        private const val ORDER_BY_FILE_ID = 0

        /** 按录音时间排序。  */
        private const val ORDER_BY_RECORD_TIME = 1

        /** 按更新时间排序。  */
        private const val ORDER_BY_UPDATE_AT = 2

        // ===== FilesParam.asc =====
        /** 降序（最新在前）。  */
        private const val ORDER_DESC = 0

        /** 筛选下拉中代表「全部」的位置。  */
        private const val FILTER_ALL_POSITION = 0

        private val BYTES_PER_MB = 1024L * 1024L
    }
}
