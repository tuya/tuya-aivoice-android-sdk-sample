package com.tuya.smart.ai_voice.nativeui

import android.content.Context
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.ChipGroup
import com.thingclips.smart.earphone.enhance.api.bean.RecordTransferResultBean
import com.tuya.smart.ai_voice.R
import com.tuya.smart.ai_voice.databinding.ItemNativeRecordBinding
import com.tuya.smart.ai_voice.nativeui.NativeRecordListAdapter.ItemVh
import com.tuya.smart.ai_voice.nativeui.widget.RecordTagBinder
import com.tuya.smart.ai_voice.nativeui.widget.RecordTagBinder.bind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 录音文件列表 Adapter。数据源为 [RecordTransferResultBean]，字段全 public，直接读。
 * 点击列表项回调 [OnItemClickListener]（跳转详情），长按回调
 * [OnItemLongClickListener]（触发删除等操作）。
 */
class NativeRecordListAdapter : RecyclerView.Adapter<NativeRecordListAdapter.ItemVh>() {
    /** 列表项点击回调。  */
    fun interface OnItemClickListener {
        fun onItemClick(item: RecordTransferResultBean)
    }

    /** 列表项长按回调（用于触发删除等操作）。  */
    fun interface OnItemLongClickListener {
        fun onItemLongClick(item: RecordTransferResultBean)
    }

    private val data: MutableList<RecordTransferResultBean> = ArrayList<RecordTransferResultBean>()
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private var clickListener: OnItemClickListener? = null
    private var longClickListener: OnItemLongClickListener? = null

    fun submitList(list: MutableList<RecordTransferResultBean?>?) {
        data.clear()
        if (list != null) {
            addAllNonNull(list)
        }
        notifyDataSetChanged()
    }

    fun appendList(list: MutableList<RecordTransferResultBean?>?) {
        if (list == null || list.isEmpty()) {
            return
        }
        val start = data.size
        addAllNonNull(list)
        notifyItemRangeInserted(start, list.size)
    }

    /** SDK 返回的列表元素在 Kotlin 视角可空，落库前过滤 null。 */
    private fun addAllNonNull(list: List<RecordTransferResultBean?>) {
        for (item in list) {
            if (item != null) {
                data.add(item)
            }
        }
    }

    fun clear() {
        data.clear()
        notifyDataSetChanged()
    }

    fun setOnItemClickListener(l: OnItemClickListener?) {
        this.clickListener = l
    }

    fun setOnItemLongClickListener(l: OnItemLongClickListener?) {
        this.longClickListener = l
    }

    /** 按 recordTransferId 移除单条数据并刷新。  */
    fun removeByRecordTransferId(recordTransferId: Long) {
        for (i in data.indices) {
            val b = data.get(i)
            if (b.recordTransferId != null && b.recordTransferId == recordTransferId) {
                data.removeAt(i)
                notifyItemRemoved(i)
                return
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemVh {
        val view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_native_record, parent, false)
        return ItemVh(view)
    }

    override fun onBindViewHolder(holder: ItemVh, position: Int) {
        val b = data.get(position)
        val ctx = holder.itemView.getContext()
        holder.name.setText(if (TextUtils.isEmpty(b.name)) ctx.getString(R.string.native_unnamed) else b.name)
        holder.transfer.setText(transferText(ctx, b.transfer))
        holder.type.setText(
            ctx.getString(
                R.string.native_record_type_format,
                recordTypeText(ctx, b.recordType)
            )
        )
        holder.source.setText(sourceText(ctx, b.source))
        holder.duration.setText(formatDuration(b.duration))
        holder.time.setText(if (b.recordTime == null) "" else dateFmt.format(Date(b.recordTime * 1000L)))
        holder.summary.setText(summaryText(ctx, b.summary))
        holder.unread.setVisibility(if (isUnread(b.visit)) View.VISIBLE else View.GONE)
        // 标签只做扫读，超出 3 个折叠为 +N；无标签时整块隐藏
        bind(holder.tags, b.tags, RecordTagBinder.LIMIT_LIST)

        holder.itemView.setOnClickListener {
            if (clickListener != null) {
                clickListener?.onItemClick(b)
            }
        }
        holder.itemView.setOnLongClickListener {
            if (longClickListener != null) {
                longClickListener?.onItemLongClick(b)
                return@setOnLongClickListener true
            }
            false
        }
    }

    override fun getItemCount(): Int {
        return data.size
    }

    private fun transferText(ctx: Context, t: Int?): String {
        if (t == null) return "-"
        when (t) {
            0 -> return ctx.getString(R.string.native_transfer_0)
            1 -> return ctx.getString(R.string.native_transfer_1)
            2 -> return ctx.getString(R.string.native_transfer_2)
            3 -> return ctx.getString(R.string.native_transfer_3)
            else -> return "-"
        }
    }

    private fun recordTypeText(ctx: Context, t: Int?): String {
        if (t == null) return "-"
        when (t) {
            0 -> return ctx.getString(R.string.native_record_type_0)
            1 -> return ctx.getString(R.string.native_record_type_1)
            2, 3 -> return ctx.getString(R.string.native_record_type_2)
            4 -> return ctx.getString(R.string.native_record_type_4)
            5 -> return ctx.getString(R.string.native_record_type_5)
            else -> return t.toString()
        }
    }

    private fun sourceText(ctx: Context, s: Int?): String {
        if (s == null) return ""
        return if (s == 1) ctx.getString(R.string.native_source_1) else ctx.getString(R.string.native_source_0)
    }

    private fun summaryText(ctx: Context, s: Int?): String {
        if (s == null) return ""
        when (s) {
            1 -> return ctx.getString(R.string.native_summary_1)
            2 -> return ctx.getString(R.string.native_summary_2)
            3 -> return ctx.getString(R.string.native_summary_3)
            4 -> return ctx.getString(R.string.native_summary_4)
            else -> return ""
        }
    }

    class ItemVh(v: View) : RecyclerView.ViewHolder(v) {
        val unread: View
        val name: TextView
        val transfer: TextView
        val type: TextView
        val source: TextView
        val duration: TextView
        val time: TextView
        val summary: TextView
        val tags: ChipGroup

        init {
            val itemBinding = ItemNativeRecordBinding.bind(v)
            unread = itemBinding.nativeItemUnread
            name = itemBinding.nativeItemName
            transfer = itemBinding.nativeItemTransfer
            type = itemBinding.nativeItemType
            source = itemBinding.nativeItemSource
            duration = itemBinding.nativeItemDuration
            time = itemBinding.nativeItemTime
            summary = itemBinding.nativeItemSummary
            tags = itemBinding.nativeItemTags
        }
    }

    companion object {
        private fun formatDuration(ms: Long?): String {
            if (ms == null || ms <= 0) return "00:00"
            val totalSec = ms / 1000
            val m = totalSec / 60
            val s = totalSec % 60
            return String.format(Locale.getDefault(), "%02d:%02d", m, s)
        }

        // ===== RecordTransferResultBean.visit =====
        /** 未读。  */
        private const val VISIT_UNREAD = 0

        /** 已转录未读。  */
        private const val VISIT_TRANSCRIBED_UNREAD = 2

        /**
         * 是否显示未读红点。
         * 
         * 
         * visit 有四个取值，转写前后各有一组未读 / 已读：`0` 未读 / `1` 已读 /
         * `2` 已转录未读 / `3` 已转录已读。转写完成会把已读的条目重新置为
         * `2`，红点再次亮起，提醒用户「有新内容可看」。
         * 
         * @param visit 访问状态，可能为 null
         * @return true 表示未读
         */
        private fun isUnread(visit: Int?): Boolean {
            return visit != null && (visit == VISIT_UNREAD || visit == VISIT_TRANSCRIBED_UNREAD)
        }
    }
}
