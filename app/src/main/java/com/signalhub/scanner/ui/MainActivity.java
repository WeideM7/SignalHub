package com.signalhub.scanner.ui;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.signalhub.scanner.R;
import com.signalhub.scanner.adapter.SignalAdapter;
import com.signalhub.scanner.databinding.ActivityMainBinding;
import com.signalhub.scanner.model.SignalSource;
import com.signalhub.scanner.scanner.SignalScanner;

import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private SignalScanner signalScanner;

    private RadarFragment radarFragment;
    private ARFragment arFragment;
    private SignalListFragment signalListFragment;

    private static final int TAB_RADAR = 0;
    private static final int TAB_AR = 1;
    private static final int TAB_SIGNALS = 2;
    private int currentTab = TAB_RADAR;

    // 传感器
    private SensorManager sensorManager;
    private Sensor rotationVectorSensor;
    private float currentAzimuth = 0f;

    private final SensorEventListener sensorEventListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(@NonNull SensorEvent event) {
            float[] rotationMatrix = new float[9];
            float[] orientationValues = new float[3];
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
            SensorManager.getOrientation(rotationMatrix, orientationValues);
            currentAzimuth = (float) Math.toDegrees(orientationValues[0]);
            if (radarFragment != null) {
                radarFragment.updateAzimuth(currentAzimuth);
            }
            if (arFragment != null) {
                arFragment.updateAzimuth(currentAzimuth);
                // 俯仰角
                float pitch = (float) Math.toDegrees(orientationValues[1]);
                arFragment.updatePitch(pitch);
            }
        }

        @Override
        public void onAccuracyChanged(@NonNull Sensor sensor, int accuracy) { }
    };

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    this::onPermissionResult
            );

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        signalScanner = SignalScanner.getInstance();

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }

        initFragments();
        setupTabs();
        setupLockCallback();
        checkAndRequestPermissions();
        switchTab(TAB_RADAR);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sensorManager != null && rotationVectorSensor != null) {
            sensorManager.registerListener(sensorEventListener, rotationVectorSensor,
                    SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) {
            sensorManager.unregisterListener(sensorEventListener);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        signalScanner.stopScan();
    }

    // ==================== 权限 ====================

    private void checkAndRequestPermissions() {
        String[] permissions = getRequiredPermissions();
        boolean allGranted = true;
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (allGranted) {
            onAllPermissionsGranted();
        } else {
            permissionLauncher.launch(permissions);
        }
    }

    private String[] getRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.CAMERA,
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            };
        }
        return new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.CAMERA
        };
    }

    private void onPermissionResult(@NonNull Map<String, Boolean> result) {
        boolean allGranted = true;
        for (Boolean granted : result.values()) {
            if (!granted) { allGranted = false; break; }
        }
        if (allGranted) {
            onAllPermissionsGranted();
        } else {
            Toast.makeText(this, "需要所有权限才能使用信号扫描功能", Toast.LENGTH_LONG).show();
            binding.tvScanStatus.setText("权限不足");
        }
    }

    private void onAllPermissionsGranted() {
        signalScanner.startScan(this, new SignalScanner.ScanCallback() {
            @Override
            public void onSignalsUpdated(@NonNull List<SignalSource> signals,
                                        @NonNull SignalScanner.ScannerStats stats) {
                // SignalScanner 已确保在主线程回调，无需再 runOnUiThread

                // 更新所有 Fragment（show/hide 模式下实例始终存活）
                if (radarFragment != null) radarFragment.updateSignals(signals);
                if (arFragment != null) arFragment.updateSignals(signals);
                if (signalListFragment != null) signalListFragment.updateSignals(signals);

                binding.tvScanStatus.setText("扫描中... (" + stats.totalSignals + " 个信号)");
            }

            @Override
            public void onScanError(@NonNull String error) {
                // SignalScanner 已确保在主线程回调
                Toast.makeText(MainActivity.this, error, Toast.LENGTH_SHORT).show();
                binding.tvScanStatus.setText("扫描出错");
            }
        });
    }

    // ==================== Tab 切换 ====================

    private void initFragments() {
        radarFragment = new RadarFragment();
        arFragment = new ARFragment();
        signalListFragment = new SignalListFragment();

        // 一次性添加所有 Fragment，只显示雷达
        getSupportFragmentManager().beginTransaction()
                .add(binding.fragmentContainer.getId(), radarFragment, "radar")
                .add(binding.fragmentContainer.getId(), arFragment, "ar")
                .add(binding.fragmentContainer.getId(), signalListFragment, "signals")
                .hide(arFragment)
                .hide(signalListFragment)
                .commit();
    }

    private void setupTabs() {
        binding.btnTabRadar.setOnClickListener(v -> switchTab(TAB_RADAR));
        binding.btnTabAR.setOnClickListener(v -> switchTab(TAB_AR));
        binding.btnTabSignals.setOnClickListener(v -> switchTab(TAB_SIGNALS));
    }

    private void switchTab(int tab) {
        currentTab = tab;
        androidx.fragment.app.FragmentTransaction ft =
                getSupportFragmentManager().beginTransaction();
        ft.hide(radarFragment);
        ft.hide(arFragment);
        ft.hide(signalListFragment);

        switch (tab) {
            case TAB_AR: ft.show(arFragment); break;
            case TAB_SIGNALS: ft.show(signalListFragment); break;
            default: ft.show(radarFragment); break;
        }
        ft.commit();

        int green = getColor(R.color.primary_green);
        int white = getColor(android.R.color.white);
        binding.btnTabRadar.setTextColor(tab == TAB_RADAR ? green : white);
        binding.btnTabAR.setTextColor(tab == TAB_AR ? green : white);
        binding.btnTabSignals.setTextColor(tab == TAB_SIGNALS ? green : white);
    }

    // ==================== 锁定回调 ====================

    private void setupLockCallback() {
        signalListFragment.setLockCallback(new SignalAdapter.OnSignalClickListener() {
            @Override
            public void onSignalClick(SignalSource signal) {
                // 点击整行 → 切换该信号的锁定状态
                signalScanner.lockSignal(signal.getId());
                refreshAllFragments();
            }

            @Override
            public void onLockClick(SignalSource signal) {
                // 点击锁定图标 → 切换该信号的锁定状态
                signalScanner.lockSignal(signal.getId());
                refreshAllFragments();
            }
        });
    }

    private void refreshAllFragments() {
        List<SignalSource> allSignals = signalScanner.getAllSignals();
        List<SignalSource> lockedSignals = signalScanner.getLockedSignals();
        if (radarFragment != null) {
            radarFragment.updateSignals(allSignals);
            radarFragment.updateLockedSignals(lockedSignals);
        }
        if (arFragment != null) {
            arFragment.updateSignals(allSignals);
            arFragment.updateLockedSignals(lockedSignals);
        }
        if (signalListFragment != null) {
            signalListFragment.updateSignals(allSignals);
        }
    }
}
