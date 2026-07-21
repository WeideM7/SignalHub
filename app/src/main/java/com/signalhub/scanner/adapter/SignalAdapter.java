package com.signalhub.scanner.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.signalhub.scanner.R;
import com.signalhub.scanner.model.SignalSource;

import java.util.ArrayList;
import java.util.List;

/**
 * 信号列表 RecyclerView 适配器
 * 直接使用 SignalSource 模型
 */
public class SignalAdapter extends RecyclerView.Adapter<SignalAdapter.ViewHolder> {

    private final List<SignalSource> signalList = new ArrayList<>();
    private OnSignalClickListener signalClickListener;

    /** 点击回调接口 */
    public interface OnSignalClickListener {
        void onSignalClick(SignalSource signal);
        void onLockClick(SignalSource signal);
    }

    public void setOnSignalClickListener(OnSignalClickListener listener) {
        this.signalClickListener = listener;
    }

    public void setSignals(List<SignalSource> signals) {
        signalList.clear();
        if (signals != null) {
            signalList.addAll(signals);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_signal, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SignalSource signal = signalList.get(position);
        holder.bind(signal);
    }

    @Override
    public int getItemCount() {
        return signalList.size();
    }

    /** 信号等级颜色 */
    private int getSignalColor(int rssi) {
        if (rssi >= -50) return Color.parseColor("#4CAF50");
        if (rssi >= -65) return Color.parseColor("#FFC107");
        if (rssi >= -80) return Color.parseColor("#FF5722");
        return Color.parseColor("#F44336");
    }

    /** RSSI (-100~-30) 映射到 0-100 */
    private int rssiToProgress(int rssi) {
        int clamped = Math.max(-100, Math.min(-30, rssi));
        return (int) ((clamped + 100) * 100.0 / 70);
    }

    /** 格式化距离 */
    private String formatDistance(float distance) {
        if (distance < 1.0f) {
            return String.format("%.0f cm", distance * 100);
        } else {
            return String.format("%.1f m", distance);
        }
    }

    class ViewHolder extends RecyclerView.ViewHolder {

        View viewSignalIndicator;
        ImageView ivSignalIcon;
        TextView tvSignalName;
        TextView tvSignalAddress;
        ProgressBar progressBar;
        TextView tvRssi;
        TextView tvChannel;
        TextView tvDistance;
        ImageView ivLock;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            viewSignalIndicator = itemView.findViewById(R.id.viewSignalIndicator);
            ivSignalIcon = itemView.findViewById(R.id.ivSignalIcon);
            tvSignalName = itemView.findViewById(R.id.tvSignalName);
            tvSignalAddress = itemView.findViewById(R.id.tvSignalAddress);
            progressBar = itemView.findViewById(R.id.progressBar);
            tvRssi = itemView.findViewById(R.id.tvRssi);
            tvChannel = itemView.findViewById(R.id.tvChannel);
            tvDistance = itemView.findViewById(R.id.tvDistance);
            ivLock = itemView.findViewById(R.id.ivLock);
        }

        void bind(SignalSource signal) {
            // 名称
            String name = (signal.getName() != null && !signal.getName().isEmpty())
                    ? signal.getName() : "未知信号";
            tvSignalName.setText(name);

            // 地址
            tvSignalAddress.setText(signal.getAddress());

            // 类型图标和颜色：WiFi 蓝，BLE 黄
            boolean isWifi = signal.getType() == SignalSource.SignalType.WIFI;
            int typeColor = isWifi
                    ? Color.parseColor("#2196F3")
                    : Color.parseColor("#FFEB3B");
            ivSignalIcon.setImageResource(android.R.drawable.ic_menu_share);
            ivSignalIcon.setColorFilter(typeColor);

            // RSSI 进度条
            int rssi = signal.getRssi();
            int progress = rssiToProgress(rssi);
            progressBar.setProgress(progress);
            int signalColor = getSignalColor(rssi);
            progressBar.setProgressTintList(
                    android.content.res.ColorStateList.valueOf(signalColor));
            tvRssi.setText(rssi + " dBm");
            tvRssi.setTextColor(signalColor);

            // 左侧颜色条：按类型着色（WiFi 蓝，BLE 黄）
            viewSignalIndicator.setBackgroundColor(typeColor);

            // 信道信息
            if (signal.getChannel() > 0) {
                String channelText;
                if (isWifi) {
                    channelText = "CH " + signal.getChannel()
                            + " (" + signal.getFrequency() + " MHz)";
                } else {
                    channelText = "BLE CH " + signal.getChannel();
                }
                tvChannel.setText(channelText);
                tvChannel.setVisibility(View.VISIBLE);
            } else {
                tvChannel.setVisibility(View.GONE);
            }

            // 距离
            if (signal.getEstimatedDistance() > 0) {
                tvDistance.setText("距离: " + formatDistance(signal.getEstimatedDistance()));
                tvDistance.setVisibility(View.VISIBLE);
            } else {
                tvDistance.setVisibility(View.GONE);
            }

            // 锁定状态
            ivLock.setImageResource(android.R.drawable.ic_lock_lock);
            ivLock.setColorFilter(signal.isLocked()
                    ? Color.parseColor("#4CAF50")
                    : Color.parseColor("#666666"));

            // 锁定按钮点击
            ivLock.setOnClickListener(v -> {
                if (signalClickListener != null) {
                    signalClickListener.onLockClick(signal);
                }
            });

            // 整行点击
            itemView.setOnClickListener(v -> {
                if (signalClickListener != null) {
                    signalClickListener.onSignalClick(signal);
                }
            });
        }
    }
}
