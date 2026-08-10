package com.tuya.smart.ai_voice.nativeui;

import android.content.Intent;
import android.text.Html;
import android.text.TextUtils;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.thingclips.smart.earphone.enhance.api.bean.AudioSearchMixItem;
import com.thingclips.smart.earphone.enhance.api.bean.AudioSearchMixParams;
import com.thingclips.smart.earphone.enhance.api.bean.FilesParam;
import com.thingclips.smart.earphone.enhance.api.bean.RecordTransferResultBean;
import com.thingclips.smart.earphone.enhance.api.bean.def.RecordOperateDef;
import com.thingclips.smart.earphone.enhance.api.bean.ttt.RecordUpdateInfo;
import com.thingclips.smart.earphone.enhance.api.listener.IRecordCallBack;
import com.thingclips.smart.earphone.enhance.api.listener.IRecordFileUpdateCallback;
import com.thingclips.smart.sdk.api.IResultCallback;
import com.tuya.smart.ai_voice.R;
import com.tuya.smart.ai_voice.nativeui.base.NativeDemoBaseActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * 模块 3 · 文件管理演示页。
 * <p>
 * 覆盖录音文件的查询、筛选、搜索、删除与空间统计：
 * <ul>
 *     <li>列表 —— {@code getRecordTransferResultList}，{@code pageSize} 传 {@code null} 一次查全部</li>
 *     <li>筛选 —— {@code FilesParam} 的 {@code recordType} / {@code transfer} / {@code orderBy}</li>
 *     <li>搜索 —— {@code searchRecordTransferResult}，命中片段带 {@code <em>} 标记，需转成富文本高亮</li>
 *     <li>空间统计 —— {@code getAudioFilesSize}，返回本地音频占用字节数</li>
 *     <li>数据变更 —— {@link IRecordFileUpdateCallback} 的四个回调，分别对应局部刷新与全量刷新</li>
 * </ul>
 *
 * <h3>删除语义</h3>
 * <pre>
 * removeFileList(ids, true)          彻底删除（本地 + 云端），不可恢复
 * removeFileList(ids, false)         只删本地音频文件，记录保留，可重新下载
 * </pre>
 * 本页长按条目演示以上两种。{@code FilesParam.remove} 字段（回收站软删除）本页固定按
 * 「未删除」查询，不做筛选切换。
 */
public class NativeRecordListActivity extends NativeDemoBaseActivity {

    /** 搜索结果页大小。 */
    private static final int SEARCH_PAGE_SIZE = 20;
    /** 搜索首页页码。 */
    private static final int SEARCH_FIRST_PAGE = 1;

    // ===== FilesParam.orderBy =====
    /** 按 fileId 排序。 */
    private static final int ORDER_BY_FILE_ID = 0;
    /** 按录音时间排序。 */
    private static final int ORDER_BY_RECORD_TIME = 1;
    /** 按更新时间排序。 */
    private static final int ORDER_BY_UPDATE_AT = 2;

    // ===== FilesParam.asc =====
    /** 降序（最新在前）。 */
    private static final int ORDER_DESC = 0;

    /** 筛选下拉中代表「全部」的位置。 */
    private static final int FILTER_ALL_POSITION = 0;

    private static final long BYTES_PER_MB = 1024L * 1024L;

    private SwipeRefreshLayout refreshLayout;
    private RecyclerView recyclerView;
    private TextView tipView;
    private TextView audioSizeText;
    private EditText searchInput;
    private Spinner recordTypeSpinner;
    private Spinner transferSpinner;
    private Spinner orderSpinner;

    private final NativeRecordListAdapter adapter = new NativeRecordListAdapter();
    private final List<RecordTransferResultBean> dataList = new ArrayList<>();

    /** 加载中标志，防止筛选连续切换时并发请求。 */
    private boolean loading = false;

    /** 数据变更监听，用字段持有，remove 时传同一引用。 */
    private IRecordFileUpdateCallback<List<RecordUpdateInfo>> updateCallback;

    @Override
    protected int getContentLayoutId() {
        return R.layout.activity_native_record_list;
    }

    @Override
    protected int getTitleResId() {
        return R.string.native_record_list_title;
    }

    @Override
    protected boolean useScrollContainer() {
        // 内部是 RecyclerView，不能再套一层 NestedScrollView
        return false;
    }

    @Override
    protected void onContentViewCreated() {
        bindViews();
        setupFilters();
        loadList();
        loadAudioSize();
    }

    private void bindViews() {
        refreshLayout = findViewById(R.id.native_list_refresh);
        recyclerView = findViewById(R.id.native_list_rv);
        tipView = findViewById(R.id.native_list_tip);
        audioSizeText = findViewById(R.id.tv_audio_size);
        searchInput = findViewById(R.id.et_search);
        recordTypeSpinner = findViewById(R.id.sp_record_type);
        transferSpinner = findViewById(R.id.sp_transfer);
        orderSpinner = findViewById(R.id.sp_order);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
        refreshLayout.setOnRefreshListener(this::loadList);

        findViewById(R.id.btn_search).setOnClickListener(v -> search());

        // 点击 → 详情页。传 recordId，详情页据其取详情并拿到 recordTransferId
        adapter.setOnItemClickListener(bean -> {
            if (TextUtils.isEmpty(bean.recordId)) {
                toast(getString(R.string.native_detail_toast_no_record_id));
                return;
            }
            startActivity(new Intent(this, NativeRecordDetailActivity.class)
                    .putExtra(NativeRecordDetailActivity.EXTRA_RECORD_ID, bean.recordId));
        });
        adapter.setOnItemLongClickListener(this::showDeleteOptions);
    }

    // ===================== 筛选 =====================

    private void setupFilters() {
        bindFilterSpinner(recordTypeSpinner, new int[]{
                R.string.native_list_filter_type_all,
                R.string.native_record_type_0,
                R.string.native_record_type_1,
                R.string.native_record_type_5});
        bindFilterSpinner(transferSpinner, new int[]{
                R.string.native_list_filter_transfer_all,
                R.string.native_transfer_0,
                R.string.native_transfer_1,
                R.string.native_transfer_2,
                R.string.native_transfer_3});
        bindFilterSpinner(orderSpinner, new int[]{
                R.string.native_list_order_record_time,
                R.string.native_list_order_file_id,
                R.string.native_list_order_update_at});

        AdapterView.OnItemSelectedListener reload = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                loadList();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        recordTypeSpinner.setOnItemSelectedListener(reload);
        transferSpinner.setOnItemSelectedListener(reload);
        orderSpinner.setOnItemSelectedListener(reload);
    }

    private void bindFilterSpinner(Spinner spinner, int[] labelResIds) {
        List<String> labels = new ArrayList<>();
        for (int resId : labelResIds) {
            labels.add(getString(resId));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.item_spinner_native, labels);
        adapter.setDropDownViewResource(R.layout.item_spinner_native_dropdown);
        spinner.setAdapter(adapter);
    }

    /**
     * 录音类型筛选值。下拉第 0 项为「全部」，对应 {@code null}。
     *
     * @return recordType 取值，null 表示不限
     */
    @Nullable
    private Integer selectedRecordType() {
        int pos = recordTypeSpinner.getSelectedItemPosition();
        if (pos == FILTER_ALL_POSITION) return null;
        // 下拉项依次为：电话(0)、会议(1)、音频导入(5)
        switch (pos) {
            case 1: return 0;
            case 2: return 1;
            case 3: return 5;
            default: return null;
        }
    }

    /**
     * 转写状态筛选值。
     *
     * @return transfer 取值，null 表示不限
     */
    @Nullable
    private Integer selectedTransfer() {
        int pos = transferSpinner.getSelectedItemPosition();
        return pos == FILTER_ALL_POSITION ? null : pos - 1;
    }

    private int selectedOrderBy() {
        switch (orderSpinner.getSelectedItemPosition()) {
            case 1: return ORDER_BY_FILE_ID;
            case 2: return ORDER_BY_UPDATE_AT;
            default: return ORDER_BY_RECORD_TIME;
        }
    }

    // ===================== 事件监听 =====================

    @Override
    protected void registerListeners() {
        updateCallback = new IRecordFileUpdateCallback<List<RecordUpdateInfo>>() {
            @Override
            public void onUpdate(List<RecordUpdateInfo> infos) {
                // 状态变更（如转写完成）：条目内容已变，按当前筛选条件重拉
                runOnUi(() -> {
                    appendLog(getString(R.string.native_list_log_on_update, size(infos)));
                    loadList();
                });
            }

            @Override
            public void onRecordOperate(@NonNull String operate, List<RecordUpdateInfo> infos) {
                runOnUi(() -> {
                    appendLog(getString(R.string.native_list_log_on_operate, operate, size(infos)));
                    // 新增会改变列表长度，必须全量刷新；改/删同样重拉以保证与筛选条件一致
                    loadList();
                });
            }

            @Override
            public void onRecordListSyncSuccess() {
                // 云端记录已落到本地库——这是「拿到云端数据」的唯一信号。
                // 不是 syncNoteRecord 的 onSuccess（那只表示任务已启动），
                // 也不是 SyncObserver 的下载回调（那管的是音频文件字节流）。
                // 本地数据可能大批变化，全量刷新
                runOnUi(() -> {
                    appendLog(getString(R.string.native_list_log_sync_success));
                    loadList();
                });
            }

            @Override
            public void onUpdateWitheTags(List<RecordUpdateInfo> infos) {
                runOnUi(() -> {
                    appendLog(getString(R.string.native_list_log_on_tags, size(infos)));
                    loadList();
                });
            }
        };
        manager.addFileRecordUpdateListener(updateCallback);
        appendLog("addFileRecordUpdateListener");
    }

    @Override
    protected void unregisterListeners() {
        if (updateCallback != null) {
            manager.removeFileRecordUpdateListener(updateCallback);
            updateCallback = null;
        }
    }

    private int size(@Nullable List<RecordUpdateInfo> infos) {
        return infos == null ? 0 : infos.size();
    }

    // ===================== 列表加载 =====================

    /**
     * 拉取录音列表。
     * <p>
     * <b>不分页</b>：{@code lastFileId} 与 {@code pageSize} 都传 {@code null}，
     * 一次取回符合筛选条件的全部记录。{@code FilesParam} 支持游标式分页
     * （{@code lastFileId} 传上一页末条的 {@code recordTransferId}），
     * 录音记录量级有限，本页不做，筛选变更或下拉刷新时整体重拉。
     */
    private void loadList() {
        if (loading) return;
        loading = true;
        if (dataList.isEmpty()) {
            showTip(getString(R.string.native_list_loading), false);
        }

        FilesParam param = new FilesParam(
                null,                                   // directoryId：不限目录
                selectedRecordType(),
                null,                                   // deviceId：不限设备
                selectedTransfer(),
                null,                                   // source：不限来源
                Boolean.FALSE,                          // remove：只查未删除的记录
                selectedOrderBy(),
                ORDER_DESC,
                null,                                   // lastFileId：不分页
                null);                                  // pageSize：为空即查询全部
        appendLog(getString(R.string.native_list_log_query,
                String.valueOf(selectedRecordType()), String.valueOf(selectedTransfer()),
                selectedOrderBy()));

        manager.getRecordTransferResultList(param, new IRecordCallBack<List<RecordTransferResultBean>>() {
            @Override
            public void onSuccess(List<RecordTransferResultBean> result) {
                runOnUi(() -> onListLoaded(result));
            }

            @Override
            public void onError(String code, String error) {
                runOnUi(() -> {
                    loading = false;
                    refreshLayout.setRefreshing(false);
                    appendLog(getString(R.string.native_list_log_query_fail, code, error));
                    if (dataList.isEmpty()) {
                        showTip(getString(R.string.native_list_load_fail), false);
                    } else {
                        toast(getString(R.string.native_list_load_fail));
                    }
                });
            }
        });
    }

    /**
     * 渲染列表结果，整体替换旧数据。
     *
     * @param result 查询结果，可能为 null 或空
     */
    private void onListLoaded(@Nullable List<RecordTransferResultBean> result) {
        loading = false;
        refreshLayout.setRefreshing(false);
        dataList.clear();
        if (result == null || result.isEmpty()) {
            adapter.clear();
            showTip(getString(R.string.native_list_empty), false);
            return;
        }
        dataList.addAll(result);
        adapter.submitList(result);
        showTip(null, true);
    }

    // ===================== 搜索 =====================

    /**
     * 关键词搜索，跨标题 / 标签 / 摘要 / 转写正文。
     * <p>
     * 返回的 {@code title} / {@code summary} / {@code content} 中，命中部分被
     * {@code <em>} 包裹，需按富文本渲染才能看到高亮；此处以弹窗形式展示结果。
     */
    private void search() {
        String keyword = searchInput.getText().toString().trim();
        if (TextUtils.isEmpty(keyword)) {
            loadList();
            return;
        }
        // keyword 是保留字段，实际检索只看 content
        AudioSearchMixParams param = new AudioSearchMixParams(
                null, keyword, SEARCH_FIRST_PAGE, SEARCH_PAGE_SIZE);
        appendLog(getString(R.string.native_list_log_search, keyword));
        manager.searchRecordTransferResult(param, new IRecordCallBack<ArrayList<AudioSearchMixItem>>() {
            @Override
            public void onSuccess(ArrayList<AudioSearchMixItem> result) {
                runOnUi(() -> showSearchResult(result));
            }

            @Override
            public void onError(String code, String error) {
                runOnUi(() -> {
                    appendLog(getString(R.string.native_list_log_search_fail, code, error));
                    toast(getString(R.string.native_list_log_search_fail, code, error));
                });
            }
        });
    }

    private void showSearchResult(@Nullable List<AudioSearchMixItem> result) {
        if (result == null || result.isEmpty()) {
            toast(getString(R.string.native_list_search_empty));
            appendLog(getString(R.string.native_list_search_empty));
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (AudioSearchMixItem item : result) {
            sb.append("<b>").append(nullToDash(item.getTitle())).append("</b><br/>");
            if (!TextUtils.isEmpty(item.getSummary())) {
                sb.append(item.getSummary()).append("<br/>");
            }
            if (!TextUtils.isEmpty(item.getContent())) {
                sb.append(item.getContent()).append("<br/>");
            }
            sb.append("<br/>");
        }
        // <em> 是接口约定的高亮标记，交给 Html 解析成斜体富文本
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.native_list_search_result_title, result.size()))
                .setMessage(Html.fromHtml(sb.toString(), Html.FROM_HTML_MODE_COMPACT))
                .setPositiveButton(R.string.native_list_delete_cancel, null)
                .show();
        appendLog(getString(R.string.native_list_log_search_ok, result.size()));
    }

    private String nullToDash(@Nullable String s) {
        return TextUtils.isEmpty(s) ? "-" : s;
    }

    // ===================== 空间统计 =====================

    /** 查询本地音频占用空间（字节）。 */
    private void loadAudioSize() {
        manager.getAudioFilesSize(new IRecordCallBack<Integer>() {
            @Override
            public void onSuccess(Integer bytes) {
                runOnUi(() -> {
                    long value = bytes == null ? 0L : bytes;
                    audioSizeText.setText(getString(R.string.native_list_audio_size,
                            String.format(Locale.getDefault(), "%.1f", value * 1f / BYTES_PER_MB)));
                });
            }

            @Override
            public void onError(String code, String error) {
                runOnUi(() -> appendLog(getString(R.string.native_list_log_size_fail, code, error)));
            }
        });
    }

    // ===================== 删除 =====================

    /**
     * 长按弹出删除选项，演示 {@code removeFileList} 的两种单条语义。
     *
     * @param item 目标条目
     */
    private void showDeleteOptions(@NonNull RecordTransferResultBean item) {
        Long rtid = item.recordTransferId;
        if (rtid == null || rtid <= 0) {
            toast(getString(R.string.native_list_delete_no_id));
            return;
        }
        String name = TextUtils.isEmpty(item.name) ? getString(R.string.native_unnamed) : item.name;
        String[] options = {
                getString(R.string.native_list_delete_all),
                getString(R.string.native_list_delete_audio_only)};
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(options, (dialog, which) -> {
                    boolean deleteAll = which == 0;
                    if (deleteAll) {
                        confirmDeleteAll(rtid, name);
                    } else {
                        deleteFile(rtid, false);
                    }
                })
                .show();
    }

    /** 彻底删除前的二次确认——本地与云端都会删掉，不可恢复。 */
    private void confirmDeleteAll(long recordTransferId, String name) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.native_list_delete_title)
                .setMessage(getString(R.string.native_list_delete_msg, name))
                .setPositiveButton(R.string.native_list_delete_confirm,
                        (d, w) -> deleteFile(recordTransferId, true))
                .setNegativeButton(R.string.native_list_delete_cancel, null)
                .show();
    }

    /**
     * 删除单条。
     *
     * @param recordTransferId 文件 ID
     * @param deleteAll        true 彻底删除（本地 + 云端）；false 只删本地音频，记录保留
     */
    private void deleteFile(long recordTransferId, boolean deleteAll) {
        appendLog(getString(R.string.native_list_log_remove, recordTransferId, deleteAll));
        manager.removeFileList(Collections.singletonList(recordTransferId), deleteAll,
                new IResultCallback() {
                    @Override
                    public void onSuccess() {
                        runOnUi(() -> {
                            if (deleteAll) {
                                removeFromLocalList(recordTransferId);
                            } else {
                                // 只删了本地音频，记录仍在，重拉以刷新其同步状态
                                loadList();
                            }
                            loadAudioSize();
                            toast(getString(R.string.native_list_delete_success));
                        });
                    }

                    @Override
                    public void onError(String code, String error) {
                        runOnUi(() -> toast(getString(R.string.native_list_delete_fail, error)));
                    }
                });
    }

    /** 彻底删除成功后就地移除，不重拉列表（对齐小程序行为）。 */
    private void removeFromLocalList(long recordTransferId) {
        Iterator<RecordTransferResultBean> it = dataList.iterator();
        while (it.hasNext()) {
            RecordTransferResultBean b = it.next();
            if (b.recordTransferId != null && b.recordTransferId == recordTransferId) {
                it.remove();
                break;
            }
        }
        adapter.removeByRecordTransferId(recordTransferId);
        if (dataList.isEmpty()) {
            showTip(getString(R.string.native_list_empty), false);
        }
    }

    private void showTip(@Nullable String text, boolean hide) {
        if (hide || text == null) {
            tipView.setVisibility(View.GONE);
        } else {
            tipView.setText(text);
            tipView.setVisibility(View.VISIBLE);
        }
    }
}
