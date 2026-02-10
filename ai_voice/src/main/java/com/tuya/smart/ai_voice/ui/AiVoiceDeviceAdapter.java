package com.tuya.smart.ai_voice.ui;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.thingclips.smart.sdk.bean.DeviceBean;
import com.tuya.smart.ai_voice.R;

import java.util.ArrayList;
import java.util.List;

public class AiVoiceDeviceAdapter extends RecyclerView.Adapter<AiVoiceDeviceAdapter.DeviceViewHolder> {

    private final List<DeviceBean> devices = new ArrayList<>();
    private OnDeviceClickListener onDeviceClickListener;

    public interface OnDeviceClickListener {
        void onDeviceClick(DeviceBean device);
    }

    public void setOnDeviceClickListener(OnDeviceClickListener listener) {
        this.onDeviceClickListener = listener;
    }

    public void submitList(List<DeviceBean> list) {
        devices.clear();
        if (list != null) {
            devices.addAll(list);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public DeviceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_ai_voice_device, parent, false);
        return new DeviceViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull DeviceViewHolder holder, int position) {
        DeviceBean device = devices.get(position);
        holder.name.setText(device.getName());
        String iconUrl = device.getIconUrl();
        if (!TextUtils.isEmpty(iconUrl)) {
            Glide.with(holder.icon)
                    .load(iconUrl)
                    .placeholder(R.drawable.ic_ai_device)
                    .error(R.drawable.ic_ai_device)
                    .into(holder.icon);
        } else {
            holder.icon.setImageResource(R.drawable.ic_ai_device);
        }
        holder.itemView.setOnClickListener(v -> {
            if (onDeviceClickListener != null) {
                onDeviceClickListener.onDeviceClick(device);
            }
        });
    }

    @Override
    public int getItemCount() {
        return devices.size();
    }

    static class DeviceViewHolder extends RecyclerView.ViewHolder {
        private final ImageView icon;
        private final TextView name;

        DeviceViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.ai_voice_device_icon);
            name = itemView.findViewById(R.id.ai_voice_device_name);
        }
    }
}
