package com.signalhub.scanner.ui;

import android.graphics.Color;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.signalhub.scanner.databinding.FragmentRadarBinding;
import com.signalhub.scanner.localization.GpsSignalLocalizer;
import com.signalhub.scanner.model.SignalSource;
import com.signalhub.scanner.scanner.SignalScanner;
import com.signalhub.scanner.view.CompassView;
import com.signalhub.scanner.view.RadarView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RadarFragment extends Fragment {

    private FragmentRadarBinding binding;

    /** 显示数量限制: 0 = 全部(无限), 50/100/200 = 具体数量, 默认 50 */
    private int displayLimit = 50;

    /** 信号类型筛选: 0=全部, 1=仅WiFi, 2=仅BLE */
    private int filterType = 0;

    private static final int FILTER_ALL = 0;
    private static final int FILTER_WIFI_ONLY = 1;
    private static final int FILTER_BLE_ONLY = 2;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentRadarBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        DisplayMetrics metrics = new DisplayMetrics();
        requireActivity().getWindowManager().getDefaultDisplay().getMetrics(metrics);
        int size = Math.min(metrics.widthPixels, metrics.heightPixels);

        ViewGroup.LayoutParams params = binding.radarView.getLayoutParams();
        params.width = size;
        params.height = size;
        binding.radarView.setLayoutParams(params);

        binding.radarView.startScan();

        // 数量限制按钮
        setupDisplayLimitControls();

        // 信号类型筛选按钮
        setupFilterControls();

        // 初始化卫星数量监听
        setupSatelliteListener();
    }

    // ==================== 数量限制 ====================

    private void setupDisplayLimitControls() {
        binding.btnLimitDown.setOnClickListener(v -> {
            if (displayLimit == 0) {
                displayLimit = 200; // ALL → 200
            } else if (displayLimit > 100) {
                displayLimit = 100; // 200 → 100
            } else if (displayLimit > 50) {
                displayLimit = 50;  // 100 → 50
            } else {
                displayLimit = 0;   // 50 → ALL
            }
            refreshDisplay();
        });

        binding.btnLimitUp.setOnClickListener(v -> {
            if (displayLimit == 0) {
                displayLimit = 50;   // ALL → 50
            } else if (displayLimit < 100) {
                displayLimit = 100;  // 50 → 100
            } else if (displayLimit < 200) {
                displayLimit = 200;  // 100 → 200
            } else {
                displayLimit = 0;    // 200 → ALL
            }
            refreshDisplay();
        });

        updateLimitDisplay();
    }

    private void refreshDisplay() {
        updateLimitDisplay();
        // 重新触发一次信号刷新，应用新的数量限制
        SignalScanner scanner = SignalScanner.getInstance();
        List<SignalSource> all = scanner.getAllSignals();
        if (all != null) {
            updateSignals(all);
        }
    }

    private void updateLimitDisplay() {
        if (displayLimit == 0) {
            binding.tvDisplayLimit.setText("ALL");
        } else {
            binding.tvDisplayLimit.setText(String.valueOf(displayLimit));
        }
    }

    // ==================== 信号类型筛选 ====================

    private void setupFilterControls() {
        binding.btnFilterAll.setOnClickListener(v -> setFilterType(FILTER_ALL));
        binding.btnFilterWifi.setOnClickListener(v -> setFilterType(FILTER_WIFI_ONLY));
        binding.btnFilterBle.setOnClickListener(v -> setFilterType(FILTER_BLE_ONLY));
        updateFilterDisplay();
    }

    private void setFilterType(int type) {
        if (filterType == type) return;
        filterType = type;
        updateFilterDisplay();
        refreshDisplay();
    }

    private void updateFilterDisplay() {
        int activeColor = Color.parseColor("#00E676");
        int inactiveColor = Color.parseColor("#AAAAAA");

        binding.btnFilterAll.setTextColor(filterType == FILTER_ALL ? activeColor : inactiveColor);
        binding.btnFilterWifi.setTextColor(filterType == FILTER_WIFI_ONLY ? activeColor : inactiveColor);
        binding.btnFilterBle.setTextColor(filterType == FILTER_BLE_ONLY ? activeColor : inactiveColor);
    }

    // ==================== 卫星数量 ====================

    private void setupSatelliteListener() {
        GpsSignalLocalizer localizer = SignalScanner.getInstance().getGpsLocalizer();
        if (localizer != null) {
            localizer.setOnSatelliteCountChangeListener(count -> {
                if (getActivity() == null) return;
                requireActivity().runOnUiThread(() -> updateSatelliteDisplay(count));
            });
        }
    }

    private void updateSatelliteDisplay(int count) {
        if (binding == null) return;

        binding.tvSatelliteCount.setText(String.valueOf(count));

        // 根据卫星数量改变颜色和状态文字
        if (count >= 8) {
            binding.tvSatelliteCount.setTextColor(Color.parseColor("#00E676"));
            binding.tvGpsStatus.setText("定位良好");
            binding.tvGpsStatus.setTextColor(Color.parseColor("#00E676"));
        } else if (count >= 4) {
            binding.tvSatelliteCount.setTextColor(Color.parseColor("#FFC107"));
            binding.tvGpsStatus.setText("定位中");
            binding.tvGpsStatus.setTextColor(Color.parseColor("#FFC107"));
        } else if (count > 0) {
            binding.tvSatelliteCount.setTextColor(Color.parseColor("#FF9800"));
            binding.tvGpsStatus.setText("卫星不足");
            binding.tvGpsStatus.setTextColor(Color.parseColor("#FF9800"));
        } else {
            binding.tvSatelliteCount.setTextColor(Color.parseColor("#F44336"));
            binding.tvGpsStatus.setText("搜索中");
            binding.tvGpsStatus.setTextColor(Color.parseColor("#F44336"));
        }
    }

    // ==================== 信号更新 ====================

    public void updateSignals(List<SignalSource> signals) {
        if (binding == null) return;

        // 先按类型筛选
        List<SignalSource> filtered = applyTypeFilter(signals);

        // 再应用数量限制
        List<SignalSource> displaySignals = applyDisplayLimit(filtered);

        binding.radarView.setSignals(displaySignals);

        // 统计（基于全部信号）
        int wifiCount = 0;
        int bleCount = 0;
        for (SignalSource signal : signals) {
            if (signal.getType() == SignalSource.SignalType.WIFI) {
                wifiCount++;
            } else if (signal.getType() == SignalSource.SignalType.BLUETOOTH_LE) {
                bleCount++;
            }
        }
        binding.tvWifiCount.setText(String.valueOf(wifiCount));
        binding.tvBleCount.setText(String.valueOf(bleCount));
    }

    /**
     * 根据信号类型筛选
     */
    private List<SignalSource> applyTypeFilter(List<SignalSource> signals) {
        if (filterType == FILTER_ALL) return signals;

        List<SignalSource> result = new ArrayList<>();
        SignalSource.SignalType targetType = (filterType == FILTER_WIFI_ONLY)
                ? SignalSource.SignalType.WIFI
                : SignalSource.SignalType.BLUETOOTH_LE;

        for (SignalSource signal : signals) {
            if (signal.getType() == targetType) {
                result.add(signal);
            }
        }
        return result;
    }

    /**
     * 根据数量限制过滤信号（所有锁定信号始终显示）
     */
    private List<SignalSource> applyDisplayLimit(List<SignalSource> signals) {
        if (displayLimit == 0 || signals.size() <= displayLimit) {
            return signals; // 全部显示
        }

        // 按 RSSI 降序排列
        List<SignalSource> sorted = new ArrayList<>(signals);
        Collections.sort(sorted, (a, b) -> Integer.compare(b.getRssi(), a.getRssi()));

        // 取前 N 个，但确保所有锁定的信号始终包含
        List<SignalSource> result = new ArrayList<>(sorted.subList(0, displayLimit));

        SignalScanner scanner = SignalScanner.getInstance();
        List<SignalSource> lockedSignals = scanner.getLockedSignals();
        for (SignalSource locked : lockedSignals) {
            if (locked != null && !result.contains(locked)) {
                // 替换最后一个最弱的非锁定信号
                for (int i = result.size() - 1; i >= 0; i--) {
                    if (!result.get(i).isLocked()) {
                        result.set(i, locked);
                        break;
                    }
                }
            }
        }

        return result;
    }

    /**
     * 获取当前显示数量限制
     */
    public int getDisplayLimit() {
        return displayLimit;
    }

    // ==================== 方位角更新 ====================

    public void updateAzimuth(float azimuth) {
        if (binding == null) return;

        binding.compassView.setAzimuth(azimuth);
        binding.radarView.setDeviceAzimuth(azimuth);
    }

    // ==================== 锁定信号 ====================

    public void updateLockedSignals(List<SignalSource> signals) {
        if (binding == null) return;

        binding.radarView.setLockedSignals(signals);

        if (signals != null && !signals.isEmpty()) {
            binding.tvLockedInfo.setVisibility(View.VISIBLE);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < signals.size(); i++) {
                SignalSource s = signals.get(i);
                if (i > 0) sb.append("  |  ");
                sb.append(s.getName()).append(" ").append(s.getRssi()).append(" dBm");
            }
            binding.tvLockedInfo.setText(sb.toString());
        } else {
            binding.tvLockedInfo.setVisibility(View.GONE);
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (binding == null) return;
        if (hidden) {
            binding.radarView.stopScan();
        } else {
            binding.radarView.startScan();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (binding != null) {
            binding.radarView.stopScan();
        }
        binding = null;
    }
}
