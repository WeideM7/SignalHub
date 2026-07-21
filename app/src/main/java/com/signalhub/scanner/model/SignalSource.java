package com.signalhub.scanner.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 信号源数据模型，表示一个 WiFi 或蓝牙信号源。
 */
public class SignalSource {

    // ==================== 枚举定义 ====================

    /**
     * 信号类型
     */
    public enum SignalType {
        WIFI,
        BLUETOOTH_CLASSIC,
        BLUETOOTH_LE
    }

    /**
     * 信号强度等级（基于 RSSI 值，单位 dBm）
     */
    public enum SignalStrengthLevel {
        CRITICAL,   // < -85 dBm
        WEAK,       // -85 ~ -70 dBm
        MEDIUM,     // -70 ~ -55 dBm
        STRONG      // > -55 dBm
    }

    // ==================== 字段 ====================

    /** 唯一标识 */
    private final String id;

    /** 信号类型 */
    private final SignalType type;

    /** 信号名称（SSID 或蓝牙设备名） */
    private String name;

    /** 地址（BSSID 或 MAC 地址） */
    private final String address;

    /** 当前 RSSI（接收信号强度指示，单位 dBm） */
    private int rssi;

    /** 频率（MHz） */
    private int frequency;

    /** 频道号 */
    private int channel;

    /** 估算距离（米） */
    private float estimatedDistance;

    /** 方位角（度） */
    private float bearing;

    /** 仰角（度） */
    private float elevation;

    /** 最后一次检测到的时间戳（毫秒） */
    private long lastSeenTimestamp;

    /** RSSI 历史记录，保留最近 20 个采样值 */
    private final List<Integer> rssiHistory;

    /** 是否被锁定追踪 */
    private boolean isLocked;

    // ==================== 构造函数 ====================

    public SignalSource(String id, SignalType type, String name, String address,
                        int rssi, int frequency, int channel,
                        float bearing, float elevation) {
        this.id = id;
        this.type = type;
        this.name = name;
        this.address = address;
        this.rssi = rssi;
        this.frequency = frequency;
        this.channel = channel;
        this.bearing = bearing;
        this.elevation = elevation;
        this.lastSeenTimestamp = System.currentTimeMillis();
        this.rssiHistory = new ArrayList<>();
        this.rssiHistory.add(rssi);
        this.isLocked = false;
        this.estimatedDistance = rssiToDistance(rssi, type);
    }

    // ==================== 信号等级与颜色 ====================

    /**
     * 根据 RSSI 值返回信号强度等级。
     *
     * @param rssi 信号强度（dBm）
     * @return 对应的信号等级
     */
    public static SignalStrengthLevel getLevel(int rssi) {
        if (rssi > -55) {
            return SignalStrengthLevel.STRONG;
        } else if (rssi > -70) {
            return SignalStrengthLevel.MEDIUM;
        } else if (rssi > -85) {
            return SignalStrengthLevel.WEAK;
        } else {
            return SignalStrengthLevel.CRITICAL;
        }
    }

    /**
     * 根据信号等级返回 ARGB 颜色值。
     *
     * @param level 信号等级
     * @return ARGB 颜色 int 值
     */
    public static int getLevelColor(SignalStrengthLevel level) {
        switch (level) {
            case STRONG:
                return 0xFF00E676;
            case MEDIUM:
                return 0xFFFFEB3B;
            case WEAK:
                return 0xFFFF5252;
            case CRITICAL:
                return 0xFFB71C1C;
            default:
                return 0xFF000000;
        }
    }

    // ==================== 距离估算 ====================

    /**
     * 使用对数路径损耗模型将 RSSI 转换为估算距离。
     * 公式: d = 10^((|RSSI| - A) / (10 * n))
     *
     * @param rssi 信号强度（dBm）
     * @param type 信号类型
     * @return 估算距离（米）
     */
    public static float rssiToDistance(int rssi, SignalType type) {
        int A;
        switch (type) {
            case WIFI:
                A = -40;
                break;
            case BLUETOOTH_LE:
            case BLUETOOTH_CLASSIC:
                A = -42;
                break;
            default:
                A = -40;
        }
        double n = 2.8;
        double absRssi = Math.abs(rssi);
        double exponent = (absRssi - Math.abs(A)) / (10.0 * n);
        return (float) Math.pow(10.0, exponent);
    }

    // ==================== WiFi 频率转频道 ====================

    /**
     * 将 WiFi 频率（MHz）转换为频道号。
     *
     * @param freq WiFi 频率（MHz）
     * @return 频道号，未知频率返回 0
     */
    public static int freqToChannel(int freq) {
        if (freq >= 2412 && freq <= 2484) {
            // 2.4 GHz 频段
            return (freq - 2407) / 5;
        } else if (freq >= 5170 && freq <= 5825) {
            // 5 GHz 频段
            return (freq - 5000) / 5;
        } else if (freq == 2484) {
            // 2.4 GHz 频段，频道 14
            return 14;
        }
        return 0;
    }

    // ==================== RSSI 采样更新 ====================

    /**
     * 添加新的 RSSI 采样值，使用 EWMA（指数加权移动平均）进行平滑。
     * smoothedRssi = 0.3 * newRssi + 0.7 * oldRssi
     * 同时更新估算距离，rssiHistory 保留最近 20 个记录。
     *
     * @param newRssi 新的 RSSI 采样值
     */
    public void addRssiSample(int newRssi) {
        // EWMA 平滑
        double alpha = 0.3;
        int smoothedRssi = (int) Math.round(alpha * newRssi + (1.0 - alpha) * this.rssi);
        this.rssi = smoothedRssi;

        // 更新时间戳
        this.lastSeenTimestamp = System.currentTimeMillis();

        // 更新历史记录，保留最近 20 个
        rssiHistory.add(newRssi);
        if (rssiHistory.size() > 20) {
            rssiHistory.remove(0);
        }

        // 重新计算估算距离
        this.estimatedDistance = rssiToDistance(smoothedRssi, this.type);
    }

    // ==================== Getter / Setter ====================

    public String getId() {
        return id;
    }

    public SignalType getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAddress() {
        return address;
    }

    public int getRssi() {
        return rssi;
    }

    public void setRssi(int rssi) {
        this.rssi = rssi;
        this.estimatedDistance = rssiToDistance(rssi, type);
    }

    public int getFrequency() {
        return frequency;
    }

    public void setFrequency(int frequency) {
        this.frequency = frequency;
    }

    public int getChannel() {
        return channel;
    }

    public void setChannel(int channel) {
        this.channel = channel;
    }

    public float getEstimatedDistance() {
        return estimatedDistance;
    }

    public float getBearing() {
        return bearing;
    }

    public void setBearing(float bearing) {
        this.bearing = bearing;
    }

    public float getElevation() {
        return elevation;
    }

    public void setElevation(float elevation) {
        this.elevation = elevation;
    }

    public long getLastSeenTimestamp() {
        return lastSeenTimestamp;
    }

    public void setLastSeenTimestamp(long lastSeenTimestamp) {
        this.lastSeenTimestamp = lastSeenTimestamp;
    }

    public List<Integer> getRssiHistory() {
        return rssiHistory;
    }

    public boolean isLocked() {
        return isLocked;
    }

    public void setLocked(boolean locked) {
        isLocked = locked;
    }

    /**
     * 获取当前信号等级。
     */
    public SignalStrengthLevel getLevel() {
        return getLevel(this.rssi);
    }

    /**
     * 获取当前信号等级对应的颜色值。
     */
    public int getLevelColor() {
        return getLevelColor(getLevel());
    }
}
