package com.tuya.smart.ai_voice.ui;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.thingclips.smart.earphone.enhance.api.bean.RecordTransferResultBean;
import com.tuya.smart.ai_voice.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 录音文件列表 Adapter。数据源为 {@link RecordTransferResultBean}，字段全 public，直接读。
 * 列表项仅展示，无点击事件。
 */
public class NativeRecordListAdapter
        extends RecyclerView.Adapter<NativeRecordListAdapter.ItemVh> {

    private final List<RecordTransferResultBean> data = new ArrayList<>();
    private final SimpleDateFormat dateFmt =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    public void submitList(List<RecordTransferResultBean> list) {
        data.clear();
        if (list != null) {
            data.addAll(list);
        }
        notifyDataSetChanged();
    }

    public void appendList(List<RecordTransferResultBean> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        int start = data.size();
        data.addAll(list);
        notifyItemRangeInserted(start, list.size());
    }

    public void clear() {
        data.clear();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ItemVh onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_native_record, parent, false);
        return new ItemVh(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ItemVh holder, int position) {
        RecordTransferResultBean b = data.get(position);
        android.content.Context ctx = holder.itemView.getContext();
        holder.name.setText(TextUtils.isEmpty(b.name) ? ctx.getString(R.string.native_unnamed) : b.name);
        holder.transfer.setText(transferText(ctx, b.transfer));
        holder.type.setText(ctx.getString(R.string.native_record_type_format, recordTypeText(ctx, b.recordType)));
        holder.source.setText(sourceText(ctx, b.source));
        holder.duration.setText(formatDuration(b.duration));
        holder.time.setText(b.recordTime == null ? "" : dateFmt.format(new Date(b.recordTime * 1000L)));
        holder.summary.setText(summaryText(ctx, b.summary));
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    private String transferText(android.content.Context ctx, Integer t) {
        if (t == null) return "-";
        switch (t) {
            case 0: return ctx.getString(R.string.native_transfer_0);
            case 1: return ctx.getString(R.string.native_transfer_1);
            case 2: return ctx.getString(R.string.native_transfer_2);
            case 3: return ctx.getString(R.string.native_transfer_3);
            default: return "-";
        }
    }

    private String recordTypeText(android.content.Context ctx, Integer t) {
        if (t == null) return "-";
        switch (t) {
            case 0: return ctx.getString(R.string.native_record_type_0);
            case 1: return ctx.getString(R.string.native_record_type_1);
            case 2:
            case 3: return ctx.getString(R.string.native_record_type_2);
            case 4: return ctx.getString(R.string.native_record_type_4);
            case 5: return ctx.getString(R.string.native_record_type_5);
            default: return String.valueOf(t);
        }
    }

    private String sourceText(android.content.Context ctx, Integer s) {
        if (s == null) return "";
        return s == 1 ? ctx.getString(R.string.native_source_1) : ctx.getString(R.string.native_source_0);
    }

    private String summaryText(android.content.Context ctx, Integer s) {
        if (s == null) return "";
        switch (s) {
            case 1: return ctx.getString(R.string.native_summary_1);
            case 2: return ctx.getString(R.string.native_summary_2);
            case 3: return ctx.getString(R.string.native_summary_3);
            case 4: return ctx.getString(R.string.native_summary_4);
            default: return "";
        }
    }

    private static String formatDuration(Long ms) {
        if (ms == null || ms <= 0) return "00:00";
        long totalSec = ms / 1000;
        long m = totalSec / 60;
        long s = totalSec % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", m, s);
    }

    static class ItemVh extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView transfer;
        final TextView type;
        final TextView source;
        final TextView duration;
        final TextView time;
        final TextView summary;

        ItemVh(@NonNull View v) {
            super(v);
            name = v.findViewById(R.id.native_item_name);
            transfer = v.findViewById(R.id.native_item_transfer);
            type = v.findViewById(R.id.native_item_type);
            source = v.findViewById(R.id.native_item_source);
            duration = v.findViewById(R.id.native_item_duration);
            time = v.findViewById(R.id.native_item_time);
            summary = v.findViewById(R.id.native_item_summary);
        }
    }
}
