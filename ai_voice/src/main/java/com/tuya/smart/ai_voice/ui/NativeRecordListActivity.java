package com.tuya.smart.ai_voice.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.thingclips.smart.earphone.enhance.api.bean.FilesParam;
import com.thingclips.smart.earphone.enhance.api.bean.RecordTransferResultBean;
import com.thingclips.smart.earphone.enhance.api.bean.ttt.RecordUpdateInfo;
import com.thingclips.smart.earphone.enhance.api.bean.def.RecordOperateDef;
import com.thingclips.smart.earphone.enhance.api.listener.IRecordCallBack;
import com.thingclips.smart.earphone.enhance.api.listener.IRecordFileUpdateCallback;
import com.thingclips.smart.plugin.tuniaudiodetectmanager.ThingAudioDetectManagerNative;
import com.tuya.smart.ai_voice.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 录音文件列表页。
 * <p>
 * 基于 {@link ThingAudioDetectManagerNative#getRecordTransferResultList}，按 recordTime 降序分页：
 * <ul>
 *     <li>游标 lastFileId = 上一页末条 {@code recordTransferId}</li>
 *     <li>下拉刷新 → 全量重拉第一页</li>
 *     <li>滚动到底 → 加载下一页</li>
 *     <li>注册 {@link IRecordFileUpdateCallback}：ADD/同步完成全量刷新，其余局部刷新</li>
 * </ul>
 * 回调可能来自子线程，UI 更新一律切回主线程。
 */
public class NativeRecordListActivity extends AppCompatActivity {

    private static final int PAGE_SIZE = 20;

    private final ThingAudioDetectManagerNative manager =
            ThingAudioDetectManagerNative.getInstance();
    private final Handler main = new Handler(Looper.getMainLooper());

    private SwipeRefreshLayout refreshLayout;
    private RecyclerView recyclerView;
    private TextView tipView;
    private final NativeRecordListAdapter adapter = new NativeRecordListAdapter();

    private final List<RecordTransferResultBean> dataList = new ArrayList<>();
    private long lastFileId = 0;
    private boolean hasMore = true;
    private boolean loading = false;

    /** 数据变更监听，字段持有，remove 时传同一实例。 */
    private IRecordFileUpdateCallback<List<RecordUpdateInfo>> updateCallback;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_native_record_list);
        bindViews();
        registerUpdateListener();  // ①
        loadFirstPage();           // ②
    }

    private void bindViews() {
        refreshLayout = findViewById(R.id.native_list_refresh);
        recyclerView = findViewById(R.id.native_list_rv);
        tipView = findViewById(R.id.native_list_tip);
        findViewById(R.id.native_list_back).setOnClickListener(v -> finish());

        final LinearLayoutManager lm = new LinearLayoutManager(this);
        recyclerView.setLayoutManager(lm);
        recyclerView.setAdapter(adapter);

        refreshLayout.setOnRefreshListener(this::loadFirstPage);

        // 滚动到底自动加载下一页
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0) return;
                int last = lm.findLastVisibleItemPosition();
                if (hasMore && !loading && last >= dataList.size() - 3) {
                    loadNextPage();
                }
            }
        });
    }

    // ===================== 监听器 =====================

    private void registerUpdateListener() {
        updateCallback = new IRecordFileUpdateCallback<List<RecordUpdateInfo>>() {
            @Override
            public void onUpdate(List<RecordUpdateInfo> infos) {
                main.post(() -> loadFirstPage()); // 简化为全量刷新
            }

            @Override
            public void onRecordOperate(@NonNull String operate, List<RecordUpdateInfo> infos) {
                if (RecordOperateDef.OPERATE_TYPE_ADD.equals(operate)) {
                    main.post(() -> loadFirstPage()); // 新增 → 全量刷新
                } else {
                    main.post(() -> loadFirstPage());
                }
            }

            @Override
            public void onRecordListSyncSuccess() {
                main.post(() -> loadFirstPage()); // 云同步完成 → 全量刷新
            }

            @Override
            public void onUpdateWitheTags(List<RecordUpdateInfo> infos) {
                main.post(() -> loadFirstPage());
            }
        };
        manager.addFileRecordUpdateListener(updateCallback);
    }

    // ===================== 分页加载 =====================

    private void loadFirstPage() {
        lastFileId = 0;
        hasMore = true;
        dataList.clear();
        adapter.clear();
        loadNextPage();
    }

    private void loadNextPage() {
        if (loading || !hasMore) return;
        loading = true;
        if (dataList.isEmpty()) {
            showTip(getString(R.string.native_list_loading), false);
        }

        FilesParam param = new FilesParam(
                null,   // directoryId
                null,   // recordType
                null,   // deviceId
                null,   // transfer
                null,   // source
                false,  // remove：只看未删除
                1,      // orderBy：1=recordTime
                0,      // asc：0=降序（最新在前）
                lastFileId == 0 ? null : (int) lastFileId,
                PAGE_SIZE
        );
        manager.getRecordTransferResultList(param, new IRecordCallBack<List<RecordTransferResultBean>>() {
            @Override
            public void onSuccess(List<RecordTransferResultBean> result) {
                main.post(() -> {
                    loading = false;
                    refreshLayout.setRefreshing(false);
                    if (result == null || result.isEmpty()) {
                        hasMore = false;
                        if (dataList.isEmpty()) {
                            showTip(getString(R.string.native_list_empty), false);
                        }
                        return;
                    }
                    dataList.addAll(result);
                    adapter.appendList(result);
                    lastFileId = result.get(result.size() - 1).recordTransferId;
                    if (result.size() < PAGE_SIZE) {
                        hasMore = false;
                    }
                    showTip(null, true);
                });
            }

            @Override
            public void onError(String code, String error) {
                main.post(() -> {
                    loading = false;
                    refreshLayout.setRefreshing(false);
                    if (dataList.isEmpty()) {
                        showTip(getString(R.string.native_list_load_fail), false);
                    } else {
                        toast(getString(R.string.native_list_load_fail));
                    }
                });
            }
        });
    }

    private void showTip(@Nullable String text, boolean hide) {
        if (hide || text == null) {
            tipView.setVisibility(View.GONE);
        } else {
            tipView.setText(text);
            tipView.setVisibility(View.VISIBLE);
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (updateCallback != null) {
            manager.removeFileRecordUpdateListener(updateCallback);
            updateCallback = null;
        }
        main.removeCallbacksAndMessages(null);
    }
}
