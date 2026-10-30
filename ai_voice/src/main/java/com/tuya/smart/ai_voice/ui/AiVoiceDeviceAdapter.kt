package com.tuya.smart.ai_voice.ui

import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.thingclips.smart.sdk.bean.DeviceBean
import com.tuya.smart.ai_voice.R
import com.tuya.smart.ai_voice.databinding.ItemAiVoiceDeviceBinding

class AiVoiceDeviceAdapter : RecyclerView.Adapter<AiVoiceDeviceAdapter.DeviceViewHolder>() {
    private val devices: MutableList<DeviceBean> = ArrayList<DeviceBean>()
    private var onDeviceClickListener: OnDeviceClickListener? = null

    interface OnDeviceClickListener {
        fun onDeviceClick(device: DeviceBean?)
    }

    fun setOnDeviceClickListener(listener: OnDeviceClickListener?) {
        this.onDeviceClickListener = listener
    }

    fun submitList(list: MutableList<DeviceBean?>?) {
        devices.clear()
        if (list != null) {
            for (d in list) {
                if (d != null) {
                    devices.add(d)
                }
            }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_ai_voice_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices.get(position)
        holder.name.setText(device.getName())
        val iconUrl = device.getIconUrl()
        if (!TextUtils.isEmpty(iconUrl)) {
            Glide.with(holder.icon)
                .load(iconUrl)
                .placeholder(R.drawable.ic_ai_device)
                .error(R.drawable.ic_ai_device)
                .into(holder.icon)
        } else {
            holder.icon.setImageResource(R.drawable.ic_ai_device)
        }
        holder.itemView.setOnClickListener(View.OnClickListener { v: View? ->
            if (onDeviceClickListener != null) {
                onDeviceClickListener?.onDeviceClick(device)
            }
        })
    }

    override fun getItemCount(): Int {
        return devices.size
    }

    class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView
        val name: TextView

        init {
            val itemBinding = ItemAiVoiceDeviceBinding.bind(itemView)
            icon = itemBinding.aiVoiceDeviceIcon
            name = itemBinding.aiVoiceDeviceName
        }
    }
}
