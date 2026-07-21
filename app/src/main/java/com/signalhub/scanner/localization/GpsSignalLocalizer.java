package com.signalhub.scanner.localization;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.signalhub.scanner.model.SignalSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GPS 信号源方位定位器
 *
 * 原理：
 * 1. 用户携带手机移动，在不同位置采集同一信号源的 RSSI
 * 2. 每个位置得到一个以距离为半径的圆
 * 3. 多圆交点即为信号源估计位置
 * 4. 从信号源位置反算相对于手机的方位角 (bearing)
 *
 * GPS 提供准确的设备位置和移动朝向，
 * 解决了纯传感器方法无法确定信号绝对方向的问题。
 */
public class GpsSignalLocalizer implements LocationListener {

    private static final String TAG = "GpsSignalLocalizer";

    /** GPS 最小位移阈值（米），移动超过此距离才记录新采样点 */
    private static final float MIN_DISPLACEMENT_M = 2.0f;

    /** 信号方位缓存：signalId → estimated bearing (度, 0=北) */
    private final ConcurrentHashMap<String, Float> bearingCache = new ConcurrentHashMap<>();

    /** GPS 位置采样缓存：signalId → 位置列表 */
    private final ConcurrentHashMap<String, List<LocationSample>> sampleCache = new ConcurrentHashMap<>();

    /** 上一次 GPS 位置 */
    private Location lastKnownLocation = null;

    /** 上一次记录采样时的纬度，用于快速跳过未变化的 GPS 位置 */
    private double lastRecordedLat = Double.NaN;

    /** 上一次记录采样时的经度，用于快速跳过未变化的 GPS 位置 */
    private double lastRecordedLon = Double.NaN;

    /** 设备朝向（度, 0=北, 顺时针） */
    private float deviceBearing = 0f;

    /** 当前 GPS 卫星数量 */
    private volatile int satelliteCount = 0;

    /** 卫星数量变化回调 */
    public interface OnSatelliteCountChangeListener {
        void onSatelliteCountChanged(int count);
    }

    private OnSatelliteCountChangeListener satelliteListener = null;

    private final Context context;
    private final LocationManager locationManager;
    private final Handler handler;

    /** GNSS 状态回调引用（用于正确注销） */
    private GnssStatus.Callback gnssCallback;

    /** 采样数据点 */
    public static class LocationSample {
        public final double latitude;
        public final double longitude;
        public final int rssi;
        public final long timestamp;

        public LocationSample(double lat, double lon, int rssi, long ts) {
            this.latitude = lat;
            this.longitude = lon;
            this.rssi = rssi;
            this.timestamp = ts;
        }
    }

    public GpsSignalLocalizer(Context context) {
        this.context = context.getApplicationContext();
        this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        this.handler = new Handler(Looper.getMainLooper());
    }

    /**
     * 启动 GPS 位置跟踪
     */
    @SuppressLint("MissingPermission")
    public void startLocationTracking() {
        if (ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.ACCESS_FINE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }

        try {
            // 请求 GPS 位置更新，最小位移 2 米
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000,  // 1秒
                    MIN_DISPLACEMENT_M,
                    this,
                    Looper.getMainLooper()
            );

            // 同时请求网络位置作为 GPS 的补充
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        1000,
                        MIN_DISPLACEMENT_M,
                        this,
                        Looper.getMainLooper()
                );
            }

            // 获取最后已知位置
            Location lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location lastNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (lastGps != null) {
                lastKnownLocation = lastGps;
            } else if (lastNet != null) {
                lastKnownLocation = lastNet;
            }

            // 注册 GNSS 卫星状态监听
            try {
                gnssCallback = new GnssStatus.Callback() {
                    @Override
                    public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
                        int count = 0;
                        for (int i = 0; i < status.getSatelliteCount(); i++) {
                            if (status.usedInFix(i)) {
                                count++;
                            }
                        }
                        satelliteCount = count;
                        if (satelliteListener != null) {
                            satelliteListener.onSatelliteCountChanged(count);
                        }
                    }

                    @Override
                    public void onStarted() {
                        // GPS 引擎启动，通知 UI
                        if (satelliteListener != null) {
                            satelliteListener.onSatelliteCountChanged(satelliteCount);
                        }
                    }
                };
                locationManager.registerGnssStatusCallback(gnssCallback, handler);
            } catch (Exception e) {
                // GnssStatus 不可用，降级使用 Location extras 中的卫星信息
                gnssCallback = null;
            }
        } catch (Exception e) {
            // GPS 不可用
        }
    }

    /**
     * 停止 GPS 位置跟踪
     */
    @SuppressLint("MissingPermission")
    public void stopLocationTracking() {
        try {
            locationManager.removeUpdates(this);
        } catch (Exception e) { }
        try {
            if (gnssCallback != null) {
                locationManager.unregisterGnssStatusCallback(gnssCallback);
                gnssCallback = null;
            }
        } catch (Exception e) { }
    }

    // ==================== LocationListener ====================

    @Override
    public void onLocationChanged(@NonNull Location location) {
        lastKnownLocation = location;
        if (location.hasBearing()) {
            deviceBearing = location.getBearing();
        }

        // 备用卫星数量获取：如果 GnssStatus 回调不可用，尝试从 Location extras 中读取
        if (gnssCallback == null && location.getExtras() != null) {
            Bundle extras = location.getExtras();
            int satCount = extras.getInt("satellites", -1);
            if (satCount < 0) {
                satCount = extras.getInt("satellite", -1);
            }
            if (satCount >= 0 && satCount != satelliteCount) {
                satelliteCount = satCount;
                if (satelliteListener != null) {
                    satelliteListener.onSatelliteCountChanged(satCount);
                }
            }
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) { }
    @Override
    public void onProviderEnabled(@NonNull String provider) { }
    @Override
    public void onProviderDisabled(@NonNull String provider) { }

    // ==================== 核心定位逻辑 ====================

    /**
     * 当用户移动到新位置时，记录当前位置所有信号的 RSSI
     * 应在每次 WiFi/BLE 扫描完成后调用
     *
     * @param signals 当前扫描到的所有信号
     */
    public void recordLocationSamples(List<SignalSource> signals) {
        if (lastKnownLocation == null) return;

        double currentLat = lastKnownLocation.getLatitude();
        double currentLon = lastKnownLocation.getLongitude();

        // 如果位置没变，跳过整个计算
        if (!Double.isNaN(lastRecordedLat)
                && Math.abs(currentLat - lastRecordedLat) < 1e-7
                && Math.abs(currentLon - lastRecordedLon) < 1e-7) {
            return;
        }

        lastRecordedLat = currentLat;
        lastRecordedLon = currentLon;

        for (SignalSource signal : signals) {
            String id = signal.getId();
            List<LocationSample> samples = sampleCache.getOrDefault(id, new ArrayList<>());

            // 检查是否与上一个采样点有足够位移
            if (!samples.isEmpty()) {
                LocationSample lastSample = samples.get(samples.size() - 1);
                float[] results = new float[3];
                Location.distanceBetween(
                        lastSample.latitude, lastSample.longitude,
                        lastKnownLocation.getLatitude(), lastKnownLocation.getLongitude(),
                        results
                );
                if (results[0] < MIN_DISPLACEMENT_M) {
                    continue; // 位移太小，跳过
                }
            }

            LocationSample sample = new LocationSample(
                    lastKnownLocation.getLatitude(),
                    lastKnownLocation.getLongitude(),
                    signal.getRssi(),
                    System.currentTimeMillis()
            );
            samples.add(sample);

            // 保留最近 20 个采样点
            if (samples.size() > 20) {
                samples = new ArrayList<>(samples.subList(samples.size() - 20, samples.size()));
            }

            sampleCache.put(id, samples);

            // 尝试三角定位
            if (samples.size() >= 3) {
                triangulate(id, samples);
            }
        }
    }

    /**
     * 三角定位算法（最小二乘法）
     * N 个已知位置 (xi, yi)，每个位置的信号距离估计为 di
     * 求解最优 (x, y) 使得 Σ((x-xi)² + (y-yi)² - di²)² 最小
     *
     * @param signalId 信号 ID
     * @param samples 位置采样列表
     */
    private void triangulate(String signalId, List<LocationSample> samples) {
        if (samples.size() < 3) return;

        int n = samples.size();

        // 构建线性方程组 Ax = b
        double[][] A = new double[n - 1][2];
        double[] b = new double[n - 1];

        LocationSample s0 = samples.get(0);
        double d0 = estimateDistance(s0.rssi);
        double lat0 = s0.latitude;
        double lon0 = s0.longitude;

        for (int i = 0; i < n - 1; i++) {
            LocationSample si = samples.get(i + 1);
            double di = estimateDistance(si.rssi);

            // 将经纬度差转换为近似米（简化：1度纬度 ≈ 111km, 1度经度 ≈ 111km * cos(lat)）
            double cosLat = Math.cos(Math.toRadians(s0.latitude));
            double dx = (si.longitude - lon0) * 111000 * cosLat;
            double dy = (si.latitude - lat0) * 111000;

            A[i][0] = 2.0 * dx;
            A[i][1] = 2.0 * dy;
            b[i] = di * di - d0 * d0 - dx * dx - dy * dy;
        }

        // 最小二乘求解: x = (A^T A)^-1 A^T b
        double[][] ata = new double[2][2];
        double[] atb = new double[2];

        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                for (int k = 0; k < n - 1; k++) {
                    ata[i][j] += A[k][i] * A[k][j];
                }
            }
            for (int k = 0; k < n - 1; k++) {
                atb[i] += A[k][i] * b[k];
            }
        }

        // 2x2 矩阵求逆
        double det = ata[0][0] * ata[1][1] - ata[0][1] * ata[1][0];
        if (Math.abs(det) < 1e-10) return;

        double x = (ata[1][1] * atb[0] - ata[0][1] * atb[1]) / det;
        double y = (ata[0][0] * atb[1] - ata[1][0] * atb[0]) / det;

        // (x, y) 是相对于第一个采样位置的偏移（米）
        // 将偏移转回经纬度
        double cosLat0 = Math.cos(Math.toRadians(lat0));
        double targetLat = lat0 + y / 111000.0;
        double targetLon = lon0 + x / (111000.0 * cosLat0);

        // 计算从当前设备位置到目标位置的方位角
        if (lastKnownLocation != null) {
            float[] results = new float[3];
            Location.distanceBetween(
                    lastKnownLocation.getLatitude(),
                    lastKnownLocation.getLongitude(),
                    targetLat, targetLon, results
            );
            float bearing = results[1]; // 方位角（度）

            // 置信度基于采样数量和残差距离
            float distance = results[0];
            float confidence = Math.min(1.0f, samples.size() / 10.0f);
            if (distance > 200) confidence *= 0.5f; // 距离太远降低置信度

            // 只在置信度足够时更新
            if (confidence > 0.2f) {
                bearingCache.put(signalId, bearing);
            }
        }
    }

    /**
     * RSSI 估计距离（米）— 对数路径损耗模型
     * 使用比 SignalSource 中更保守的参数
     */
    private double estimateDistance(int rssi) {
        // A = 1米处参考 RSSI, n = 路径损耗指数
        double a = -42.0;
        double n = 3.0; // 室内环境取较大值提高稳定性
        return Math.pow(10.0, (Math.abs(rssi) - Math.abs(a)) / (10.0 * n));
    }

    // ==================== 公共查询方法 ====================

    /**
     * 获取信号的估计方位角（度, 0=北, 顺时针）
     * 如果没有足够的 GPS 采样数据，返回 null
     *
     * @param signalId 信号 ID
     * @return 方位角（度），或 null（无法确定）
     */
    public Float getSignalBearing(String signalId) {
        return bearingCache.get(signalId);
    }

    /**
     * 获取当前设备朝向（度）
     */
    public float getDeviceBearing() {
        return deviceBearing;
    }

    /**
     * 获取当前使用的卫星数量
     */
    public int getSatelliteCount() {
        return satelliteCount;
    }

    /**
     * 设置卫星数量变化监听
     */
    public void setOnSatelliteCountChangeListener(OnSatelliteCountChangeListener listener) {
        this.satelliteListener = listener;
    }

    /**
     * 获取所有已计算方位角的信号 ID
     */
    public List<String> getLocalizedSignalIds() {
        return new ArrayList<>(bearingCache.keySet());
    }

    /**
     * 获取某个信号的采样点数量
     */
    public int getSampleCount(String signalId) {
        List<LocationSample> samples = sampleCache.get(signalId);
        return samples != null ? samples.size() : 0;
    }

    /**
     * 清除某个信号的定位数据
     */
    public void clearSignalSamples(String signalId) {
        bearingCache.remove(signalId);
        sampleCache.remove(signalId);
    }

    /**
     * 清除所有定位数据
     */
    public void clear() {
        bearingCache.clear();
        sampleCache.clear();
        lastKnownLocation = null;
        lastRecordedLat = Double.NaN;
        lastRecordedLon = Double.NaN;
    }
}
