package com.signalhub.scanner.scanner;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.ActivityCompat;

import com.signalhub.scanner.localization.GpsSignalLocalizer;
import com.signalhub.scanner.model.SignalSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 信号扫描器单例类，统一管理 WiFi 和蓝牙（BLE）扫描。
 */
public class SignalScanner {

    // ==================== 回调接口 ====================

    /**
     * 扫描结果回调接口。
     */
    public interface ScanCallback {
        /**
         * 当信号列表更新时调用。
         *
         * @param signals 所有当前可见的信号列表（按 RSSI 降序）
         * @param stats   扫描统计信息
         */
        void onSignalsUpdated(List<SignalSource> signals, ScannerStats stats);

        /**
         * 当扫描出错时调用。
         *
         * @param error 错误描述
         */
        void onScanError(String error);
    }

    // ==================== 统计信息 ====================

    /**
     * 扫描统计数据。
     */
    public static class ScannerStats {
        /** 总信号数 */
        public int totalSignals;

        /** WiFi 信号数 */
        public int wifiCount;

        /** BLE 信号数 */
        public int bleCount;

        /** 所有信号的平均 RSSI */
        public int avgRssi;

        /** 信号最强的信号源 */
        public SignalSource strongest;

        public ScannerStats() {
        }

        public ScannerStats(int totalSignals, int wifiCount, int bleCount,
                            int avgRssi, SignalSource strongest) {
            this.totalSignals = totalSignals;
            this.wifiCount = wifiCount;
            this.bleCount = bleCount;
            this.avgRssi = avgRssi;
            this.strongest = strongest;
        }
    }

    // ==================== 单例 ====================

    private static volatile SignalScanner instance;

    private SignalScanner() {
        signalCache = new ConcurrentHashMap<>();
        handler = new Handler(Looper.getMainLooper());
    }

    /**
     * 获取 SignalScanner 单例实例。
     *
     * @return 单例实例
     */
    public static SignalScanner getInstance() {
        if (instance == null) {
            synchronized (SignalScanner.class) {
                if (instance == null) {
                    instance = new SignalScanner();
                }
            }
        }
        return instance;
    }

    // ==================== 内部字段 ====================

    private WifiManager wifiManager;
    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;

    /** 信号缓存，key 为信号 ID */
    private final ConcurrentHashMap<String, SignalSource> signalCache;

    /** 主线程 Handler，用于定时任务 */
    private final Handler handler;

    /** 外部回调 */
    private ScanCallback externalCallback;

    /** 应用上下文，用于权限检查等 */
    private Context appContext;

    /** 是否正在扫描 */
    private volatile boolean scanning = false;

    /** 最大锁定信号数量 */
    private static final int MAX_LOCKED_SIGNALS = 2;

    /** 锁定的信号 ID 列表（按锁定顺序，最多2个） */
    private final List<String> lockedSignalIds = new ArrayList<>();

    /** GPS 信号方位定位器 */
    private GpsSignalLocalizer gpsLocalizer;

    /** WiFi 扫描间隔（毫秒），30 秒（Android 10+ 限制每 2 分钟最多 4 次扫描） */
    private static final long WIFI_SCAN_INTERVAL = 30000L;

    /** 信号过期时间（毫秒），30 秒 */
    private static final long SIGNAL_EXPIRE_MS = 30_000L;

    /** 过期清理周期（毫秒），10 秒 */
    private static final long CLEANUP_INTERVAL = 10_000L;

    /** 通知节流间隔（毫秒），200 毫秒 */
    private static final long NOTIFY_THROTTLE_MS = 200L;

    /** 待执行的节流通知 Runnable */
    private Runnable pendingNotifyRunnable = null;

    // ==================== WiFi 相关 ====================

    private final BroadcastReceiver wifiScanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())) {
                boolean success = intent.getBooleanExtra(
                        WifiManager.EXTRA_RESULTS_UPDATED, false);
                if (success) {
                    processWifiScanResults();
                }
                // 无论成功与否，继续定时下一次扫描
            }
        }
    };

    private Runnable wifiScanRunnable;
    private Runnable cleanupRunnable;

    // ==================== BLE 相关 ====================

    private final android.bluetooth.le.ScanCallback bleLeScanCallback = new android.bluetooth.le.ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            processBleScanResult(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            if (results == null || results.isEmpty()) return;
            for (ScanResult result : results) {
                processBleScanResult(result);
            }
            // 批量处理完成后统一通知一次
            notifySignalsUpdated();
        }

        @Override
        public void onScanFailed(int errorCode) {
            String errorMsg;
            switch (errorCode) {
                case android.bluetooth.le.ScanCallback.SCAN_FAILED_ALREADY_STARTED:
                    errorMsg = "BLE 扫描已在进行中";
                    break;
                case android.bluetooth.le.ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                    errorMsg = "BLE 扫描应用注册失败";
                    break;
                case android.bluetooth.le.ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED:
                    errorMsg = "BLE 扫描功能不受支持";
                    break;
                case android.bluetooth.le.ScanCallback.SCAN_FAILED_INTERNAL_ERROR:
                default:
                    errorMsg = "BLE 扫描内部错误 (code=" + errorCode + ")";
                    break;
            }
            if (externalCallback != null) {
                externalCallback.onScanError(errorMsg);
            }
        }
    };

    // ==================== 扫描控制 ====================

    /**
     * 启动 WiFi 和 BLE 扫描。
     *
     * @param context  Android 上下文
     * @param callback 扫描回调
     */
    public void startScan(Context context, ScanCallback callback) {
        if (scanning) {
            return;
        }

        this.appContext = context.getApplicationContext();
        this.externalCallback = callback;
        this.scanning = true;

        // 初始化 WiFi 管理器
        wifiManager = (WifiManager) appContext
                .getSystemService(Context.WIFI_SERVICE);

        // 初始化蓝牙管理器
        bluetoothManager = (BluetoothManager) appContext
                .getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
            if (bluetoothAdapter != null) {
                bleScanner = bluetoothAdapter.getBluetoothLeScanner();
            }
        }

        // 注册 WiFi 扫描广播接收器
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(wifiScanReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(wifiScanReceiver, intentFilter);
        }

        // 启动 WiFi 周期扫描
        startWifiPeriodicScan();

        // 启动 BLE 扫描
        startBleScan();

        // 启动过期信号清理
        startCleanupTask();

        // 启动 GPS 位置跟踪（用于信号方位三角定位）
        gpsLocalizer = new GpsSignalLocalizer(appContext);
        gpsLocalizer.startLocationTracking();
    }

    /**
     * 停止所有扫描。
     */
    public void stopScan() {
        if (!scanning) {
            return;
        }

        scanning = false;

        // 停止 WiFi 周期扫描
        stopWifiPeriodicScan();

        // 停止 BLE 扫描
        stopBleScan();

        // 停止过期清理
        stopCleanupTask();

        // 停止 GPS 位置跟踪
        if (gpsLocalizer != null) {
            gpsLocalizer.stopLocationTracking();
            gpsLocalizer.clear();
            gpsLocalizer = null;
        }

        // 尝试注销广播（需要 context，此处通过标记让回调不再工作）
        wifiScanReceiver.abortBroadcast();

        externalCallback = null;
    }

    /**
     * 是否正在扫描。
     *
     * @return true 表示正在扫描
     */
    public boolean isScanning() {
        return scanning;
    }

    // ==================== WiFi 扫描实现 ====================

    private void startWifiPeriodicScan() {
        if (wifiManager == null) return;

        wifiScanRunnable = new Runnable() {
            @Override
            public void run() {
                if (!scanning) return;
                if (ActivityCompat.checkSelfPermission(
                        appContext, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED) {
                    wifiManager.startScan();
                }
                // 3 秒后再次扫描
                handler.postDelayed(this, WIFI_SCAN_INTERVAL);
            }
        };

        // 立即执行第一次扫描
        handler.post(wifiScanRunnable);
    }

    private void stopWifiPeriodicScan() {
        if (wifiScanRunnable != null) {
            handler.removeCallbacks(wifiScanRunnable);
            wifiScanRunnable = null;
        }
    }

    private void processWifiScanResults() {
        if (wifiManager == null) return;
        if (ActivityCompat.checkSelfPermission(
                appContext, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        List<android.net.wifi.ScanResult> results = wifiManager.getScanResults();
        if (results == null) return;

        long now = System.currentTimeMillis();

        for (android.net.wifi.ScanResult result : results) {
            if (result.BSSID == null || result.BSSID.isEmpty()) continue;

            String id = "WIFI_" + result.BSSID;
            String name = result.SSID != null ? result.SSID : "Unknown WiFi";
            int rssi = result.level;
            int frequency = result.frequency;
            int channel = SignalSource.freqToChannel(frequency);

            synchronized (signalCache) {
                SignalSource existing = signalCache.get(id);
                if (existing != null) {
                    existing.addRssiSample(rssi);
                    existing.setFrequency(frequency);
                    existing.setChannel(channel);
                } else {
                    SignalSource source = new SignalSource(
                            id,
                            SignalSource.SignalType.WIFI,
                            name,
                            result.BSSID,
                            rssi,
                            frequency,
                            channel,
                            0.0f,
                            0.0f
                    );
                    signalCache.put(id, source);
                }
            }
        }

        notifySignalsUpdated();
    }

    /**
     * 处理单个 BLE 扫描结果，更新信号缓存。
     */
    private void processBleScanResult(ScanResult result) {
        if (result == null || result.getDevice() == null) return;

        String address = result.getDevice().getAddress();
        if (address == null || address.isEmpty()) return;

        String id = "BLE_" + address;
        String name = result.getDevice().getName();
        if (name == null) name = "Unknown BLE";
        int rssi = result.getRssi();

        synchronized (signalCache) {
            SignalSource existing = signalCache.get(id);
            if (existing != null) {
                existing.addRssiSample(rssi);
                if (!existing.getName().equals("Unknown BLE")) {
                    existing.setName(name);
                }
            } else {
                SignalSource source = new SignalSource(
                        id,
                        SignalSource.SignalType.BLUETOOTH_LE,
                        name,
                        address,
                        rssi,
                        0,  // BLE 频率不通过 ScanResult 直接获取
                        0,
                        0.0f,
                        0.0f
                );
                signalCache.put(id, source);
            }
        }
    }

    // ==================== BLE 扫描实现 ====================

    private void startBleScan() {
        if (bleScanner == null) {
            if (externalCallback != null) {
                externalCallback.onScanError("BluetoothLeScanner 不可用，请确认蓝牙已开启");
            }
            return;
        }

        ScanSettings scanSettings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(500)   // 500ms 批量回调，平衡实时性和性能
                .build();

        if (ActivityCompat.checkSelfPermission(
                appContext, Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED) {
            bleScanner.startScan(null, scanSettings, bleLeScanCallback);
        }
    }

    private void stopBleScan() {
        if (bleScanner != null) {
            if (ActivityCompat.checkSelfPermission(
                    appContext, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED) {
                bleScanner.stopScan(bleLeScanCallback);
            }
        }
    }

    // ==================== 过期清理 ====================

    private void startCleanupTask() {
        cleanupRunnable = new Runnable() {
            @Override
            public void run() {
                if (!scanning) return;

                long now = System.currentTimeMillis();
                List<String> expiredIds = new ArrayList<>();

                synchronized (signalCache) {
                    for (SignalSource source : signalCache.values()) {
                        // 锁定的信号不会被清理
                        if (lockedSignalIds.contains(source.getId())) continue;
                        if (now - source.getLastSeenTimestamp() > SIGNAL_EXPIRE_MS) {
                            expiredIds.add(source.getId());
                        }
                    }

                    for (String expiredId : expiredIds) {
                        signalCache.remove(expiredId);
                    }
                }

                // 如果有信号被清理，通知更新
                if (!expiredIds.isEmpty()) {
                    notifySignalsUpdated();
                }

                handler.postDelayed(this, CLEANUP_INTERVAL);
            }
        };

        // 首次延迟 10 秒后开始清理
        handler.postDelayed(cleanupRunnable, CLEANUP_INTERVAL);
    }

    private void stopCleanupTask() {
        if (cleanupRunnable != null) {
            handler.removeCallbacks(cleanupRunnable);
            cleanupRunnable = null;
        }
    }

    // ==================== 数据查询 ====================

    /**
     * 获取所有当前可见的信号列表，按 RSSI 降序排列。
     *
     * @return 按 RSSI 降序排列的信号列表
     */
    public List<SignalSource> getAllSignals() {
        List<SignalSource> signals;
        synchronized (signalCache) {
            signals = new ArrayList<>(signalCache.values());
        }
        // 按 RSSI 降序排列（RSSI 值越大信号越强）
        Collections.sort(signals, (a, b) -> Integer.compare(b.getRssi(), a.getRssi()));
        return signals;
    }

    /**
     * 根据 ID 获取信号源。
     *
     * @param id 信号 ID
     * @return 对应的 SignalSource，未找到则返回 null
     */
    public SignalSource getSignalById(String id) {
        synchronized (signalCache) {
            return signalCache.get(id);
        }
    }

    // ==================== 信号锁定 ====================

    /**
     * 锁定/解锁某个信号。最多支持同时锁定2个信号。
     * 如果该信号已在锁定列表中，则移除（切换解锁）。
     * 如果锁定列表已满（2个），则替换最早锁定的那个。
     *
     * @param id 要锁定的信号 ID，传入 null 则全部解锁
     */
    public void lockSignal(String id) {
        if (id == null) {
            unlockAllSignals();
            return;
        }

        synchronized (signalCache) {
            SignalSource source = signalCache.get(id);
            if (source == null) return;

            // 已在锁定列表中 → 移除（切换解锁）
            if (lockedSignalIds.contains(id)) {
                lockedSignalIds.remove(id);
                source.setLocked(false);
                if (gpsLocalizer != null) {
                    gpsLocalizer.clearSignalSamples(id);
                }
                return;
            }

            // 锁定列表已满 → 替换最早的
            if (lockedSignalIds.size() >= MAX_LOCKED_SIGNALS) {
                String oldestId = lockedSignalIds.remove(0);
                SignalSource oldest = signalCache.get(oldestId);
                if (oldest != null) oldest.setLocked(false);
                if (gpsLocalizer != null) {
                    gpsLocalizer.clearSignalSamples(oldestId);
                }
            }

            lockedSignalIds.add(id);
            source.setLocked(true);
        }
    }

    /**
     * 解锁所有锁定的信号。
     */
    public void unlockAllSignals() {
        synchronized (signalCache) {
            for (String sid : new ArrayList<>(lockedSignalIds)) {
                SignalSource source = signalCache.get(sid);
                if (source != null) source.setLocked(false);
                if (gpsLocalizer != null) {
                    gpsLocalizer.clearSignalSamples(sid);
                }
            }
            lockedSignalIds.clear();
        }
    }

    /**
     * 获取 GPS 定位器（用于读取卫星数量等）
     */
    public GpsSignalLocalizer getGpsLocalizer() {
        return gpsLocalizer;
    }

    /**
     * 获取当前锁定的信号（兼容旧代码，返回第一个）。
     *
     * @return 被锁定的 SignalSource，未锁定则返回 null
     */
    public SignalSource getLockedSignal() {
        if (lockedSignalIds.isEmpty()) return null;
        synchronized (signalCache) {
            return signalCache.get(lockedSignalIds.get(0));
        }
    }

    /**
     * 获取所有当前锁定的信号列表。
     *
     * @return 锁定的信号列表
     */
    public List<SignalSource> getLockedSignals() {
        List<SignalSource> result = new ArrayList<>();
        synchronized (signalCache) {
            for (String id : lockedSignalIds) {
                SignalSource s = signalCache.get(id);
                if (s != null) result.add(s);
            }
        }
        return result;
    }

    // ==================== 内部通知 ====================

    /**
     * 通知外部回调信号列表已更新（带节流机制）。
     * 如果上一次通知尚未执行，则取消并只发送最新的。
     */
    private void notifySignalsUpdated() {
        // 取消之前排队的通知
        if (pendingNotifyRunnable != null) {
            handler.removeCallbacks(pendingNotifyRunnable);
        }

        pendingNotifyRunnable = () -> {
            pendingNotifyRunnable = null;
            doNotifySignalsUpdated();
        };

        handler.postDelayed(pendingNotifyRunnable, NOTIFY_THROTTLE_MS);
    }

    /**
     * 实际执行通知逻辑：全量获取信号、更新方位角、构建统计、回调。
     */
    private void doNotifySignalsUpdated() {
        if (externalCallback == null) return;

        List<SignalSource> allSignals = getAllSignals();

        // 使用 GPS 定位器更新信号方位角
        if (gpsLocalizer != null) {
            // 先记录当前位置采样
            gpsLocalizer.recordLocationSamples(allSignals);

            // 然后更新每个信号的 bearing
            for (SignalSource signal : allSignals) {
                Float bearing = gpsLocalizer.getSignalBearing(signal.getId());
                if (bearing != null) {
                    signal.setBearing(bearing);
                }
            }
        }

        ScannerStats stats = buildStats(allSignals);

        if (externalCallback != null) {
            externalCallback.onSignalsUpdated(allSignals, stats);
        }
    }

    /**
     * 构建扫描统计数据。
     *
     * @param signals 信号列表（已按 RSSI 降序排列）
     * @return 统计信息
     */
    private ScannerStats buildStats(List<SignalSource> signals) {
        ScannerStats stats = new ScannerStats();

        stats.totalSignals = signals.size();
        stats.strongest = signals.isEmpty() ? null : signals.get(0);

        int totalRssi = 0;
        for (SignalSource source : signals) {
            if (source.getType() == SignalSource.SignalType.WIFI) {
                stats.wifiCount++;
            } else if (source.getType() == SignalSource.SignalType.BLUETOOTH_LE) {
                stats.bleCount++;
            }
            totalRssi += source.getRssi();
        }

        stats.avgRssi = signals.isEmpty() ? 0 : totalRssi / signals.size();

        return stats;
    }
}
