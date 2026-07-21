package com.signalhub.scanner.ui;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.signalhub.scanner.R;
import com.signalhub.scanner.adapter.SignalAdapter;
import com.signalhub.scanner.databinding.FragmentSignalsBinding;
import com.signalhub.scanner.model.SignalSource;
import com.signalhub.scanner.scanner.SignalScanner;

import java.util.List;

/**
 * 信号列表 Fragment
 * 使用 RecyclerView + SwipeRefreshLayout 展示所有扫描到的信号
 */
public class SignalListFragment extends Fragment {

    private FragmentSignalsBinding binding;
    private SignalAdapter adapter;

    /** 延迟设置的锁定回调（因为 Fragment 事务异步，onCreate 时 adapter 可能还没创建） */
    private SignalAdapter.OnSignalClickListener pendingLockCallback;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentSignalsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 初始化 RecyclerView
        adapter = new SignalAdapter();
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerView.setAdapter(adapter);

        // 如果之前有 pending 的锁定回调，现在应用
        if (pendingLockCallback != null) {
            adapter.setOnSignalClickListener(pendingLockCallback);
            pendingLockCallback = null;
        }

        // 下拉刷新
        binding.swipeRefresh.setOnRefreshListener(() -> {
            // 手动触发一次 WiFi 扫描
            SignalScanner scanner = SignalScanner.getInstance();
            List<SignalSource> all = scanner.getAllSignals();
            updateSignals(all);
            binding.swipeRefresh.setRefreshing(false);
        });

        // 初次加载数据
        List<SignalSource> initial = SignalScanner.getInstance().getAllSignals();
        if (initial != null && !initial.isEmpty()) {
            updateSignals(initial);
        }
    }

    /**
     * 更新信号列表
     */
    public void updateSignals(List<SignalSource> signals) {
        if (binding == null) return;

        if (signals == null || signals.isEmpty()) {
            binding.recyclerView.setVisibility(View.GONE);
            binding.tvEmpty.setVisibility(View.VISIBLE);
            binding.tvTotalSignals.setText("总计: 0");
            binding.tvWifiSignals.setText("WiFi: 0");
            binding.tvBleSignals.setText("BLE: 0");
            binding.tvStrongestSignal.setText("最强: --");
            return;
        }

        binding.recyclerView.setVisibility(View.VISIBLE);
        binding.tvEmpty.setVisibility(View.GONE);

        adapter.setSignals(signals);

        // 统计
        int wifiCount = 0;
        int bleCount = 0;
        SignalSource strongest = null;

        for (SignalSource s : signals) {
            if (s.getType() == SignalSource.SignalType.WIFI) {
                wifiCount++;
            } else if (s.getType() == SignalSource.SignalType.BLUETOOTH_LE) {
                bleCount++;
            }
            if (strongest == null || s.getRssi() > strongest.getRssi()) {
                strongest = s;
            }
        }

        binding.tvTotalSignals.setText("总计: " + signals.size());
        binding.tvWifiSignals.setText("WiFi: " + wifiCount);
        binding.tvBleSignals.setText("BLE: " + bleCount);
        if (strongest != null) {
            binding.tvStrongestSignal.setText("最强: " + strongest.getRssi() + " dBm");
        }
    }

    /**
     * 设置锁定回调。
     * 如果 adapter 还没创建（Fragment 事务异步），则保存为 pending，等 onViewCreated 中应用。
     */
    public void setLockCallback(SignalAdapter.OnSignalClickListener listener) {
        if (adapter != null) {
            adapter.setOnSignalClickListener(listener);
        } else {
            pendingLockCallback = listener;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
