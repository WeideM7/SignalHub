package com.signalhub.scanner.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.signalhub.scanner.databinding.FragmentArBinding;
import com.signalhub.scanner.model.SignalSource;
import com.signalhub.scanner.view.RadarView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AR HUD 视图 Fragment
 *
 * 布局结构：
 * - CameraX 后置摄像头全屏预览
 * - 中心十字准星（陀螺仪水平参照）
 * - 信号按方位角分配到四个方向区域（左/右/上/下）
 * - 锁定信号时显示方向大箭头 + 详细信息卡片
 * - 底部小雷达显示附近信号分布
 * - 最大显示数量限制（默认 8 个）
 */
public class ARFragment extends Fragment {

    private FragmentArBinding binding;
    private float deviceAzimuth = 0f;
    private float devicePitch = 0f;
    private float deviceRoll = 0f;

    /** 最大同时显示的信号数量 */
    private static final int MAX_DISPLAY_SIGNALS = 8;

    /** 当前锁定的信号列表 */
    private final List<SignalSource> lockedSignals = new ArrayList<>();

    /** HUD 标签 tag */
    private static final String TAG_HUD = "ar_hud_label";

    /** 方位角阈值（度），用于判断信号在左/右/上/下哪个区域 */
    private static final float DIRECTION_THRESHOLD = 45f;

    // ==================== 生命周期 ====================

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentArBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 初始化十字准星自定义 View
        initCrosshair();

        // 初始化小雷达
        binding.miniRadarView.startScan();

        // 初始化 CameraX（异步，不阻塞主线程）
        initCamera();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (binding != null && binding.miniRadarView != null) {
            binding.miniRadarView.stopScan();
        }
        binding = null;
    }

    // ==================== 初始化 ====================

    /**
     * 绘制十字准星。用自定义 View 替代 ImageView。
     */
    private void initCrosshair() {
        CrosshairView crosshair = new CrosshairView(requireContext());
        binding.crosshairCenter.removeAllViews();
        binding.crosshairCenter.addView(crosshair);
    }

    /**
     * 初始化 CameraX，异步绑定不阻塞 UI。
     */
    private void initCamera() {
        try {
            ProcessCameraProvider.getInstance(requireContext()).addListener(() -> {
                try {
                    ProcessCameraProvider cameraProvider =
                            ProcessCameraProvider.getInstance(requireContext()).get();
                    cameraProvider.unbindAll();
                    Preview preview = new Preview.Builder().build();
                    preview.setSurfaceProvider(binding.previewView.getSurfaceProvider());
                    cameraProvider.bindToLifecycle(this,
                            CameraSelector.DEFAULT_BACK_CAMERA, preview);
                } catch (Exception e) {
                    // Camera 不可用，静默处理
                }
            }, ContextCompat.getMainExecutor(requireContext()));
        } catch (Exception e) {
            // CameraX 初始化失败，静默处理
        }
    }

    // ==================== 方位角更新（来自 MainActivity 的传感器数据） ====================

    /**
     * 更新设备方位角（陀螺仪/传感器融合数据）
     */
    public void updateAzimuth(float azimuth) {
        this.deviceAzimuth = azimuth;
    }

    /**
     * 更新设备俯仰角
     */
    public void updatePitch(float pitch) {
        this.devicePitch = pitch;
    }

    // ==================== 信号更新 ====================

    /**
     * 更新信号显示 — 按方位角分区显示到左/右/上/下四个区域
     */
    public void updateSignals(List<SignalSource> signals) {
        if (binding == null) return;

        // 清除旧的 HUD 标签
        clearAllLabels(binding.layoutSignalsTop);
        clearAllLabels(binding.layoutSignalsLeft);
        clearAllLabels(binding.layoutSignalsRight);
        clearAllLabels(binding.layoutSignalsBottom);

        // 限制显示数量，按 RSSI 降序取最强的前 N 个，确保锁定信号包含在内
        List<SignalSource> displayList = new ArrayList<>();
        if (signals != null && !signals.isEmpty()) {
            List<SignalSource> sorted = new ArrayList<>(signals);
            Collections.sort(sorted, (a, b) -> Integer.compare(b.getRssi(), a.getRssi()));
            int limit = Math.min(sorted.size(), MAX_DISPLAY_SIGNALS);
            displayList = new ArrayList<>(sorted.subList(0, limit));

            // 确保锁定的信号在显示列表中
            for (SignalSource locked : lockedSignals) {
                if (locked != null && !displayList.contains(locked)) {
                    // 替换最后一个最弱的非锁定信号
                    for (int i = displayList.size() - 1; i >= 0; i--) {
                        if (!displayList.get(i).isLocked()) {
                            displayList.set(i, locked);
                            break;
                        }
                    }
                }
            }
        }

        // 将信号分配到四个方向区域
        for (SignalSource signal : displayList) {
            TextView label = createSignalLabel(signal);
            Direction direction = getSignalDirection(signal);

            switch (direction) {
                case LEFT:
                    binding.layoutSignalsLeft.addView(label);
                    break;
                case RIGHT:
                    binding.layoutSignalsRight.addView(label);
                    break;
                case TOP:
                    binding.layoutSignalsTop.addView(label);
                    break;
                case BOTTOM:
                    binding.layoutSignalsBottom.addView(label);
                    break;
            }
        }

        // 更新统计
        int wifiCount = 0, bleCount = 0;
        if (signals != null) {
            for (SignalSource s : signals) {
                if (s.getType() == SignalSource.SignalType.WIFI) wifiCount++;
                else bleCount++;
            }
        }
        String statsText = "信号: " + (signals != null ? signals.size() : 0)
                + " | 显示: " + displayList.size() + "/" + MAX_DISPLAY_SIGNALS
                + " | WiFi: " + wifiCount + " | BLE: " + bleCount;
        binding.tvARStats.setText(statsText);

        // 更新小雷达
        binding.miniRadarView.setSignals(displayList);
        binding.miniRadarView.setDeviceAzimuth(deviceAzimuth);
    }

    /**
     * 更新锁定的信号 — 显示方向箭头和详细信息（AR视图只显示第一个锁定的信号）
     */
    public void updateLockedSignals(List<SignalSource> signals) {
        lockedSignals.clear();
        if (signals != null) {
            lockedSignals.addAll(signals);
        }

        if (binding == null) return;

        SignalSource signal = !lockedSignals.isEmpty() ? lockedSignals.get(0) : null;

        if (signal != null) {
            binding.cardLockedSignal.setVisibility(View.VISIBLE);
            binding.layoutLockedArrow.setVisibility(View.VISIBLE);

            binding.tvLockedName.setText(signal.getName());
            if (signal.getChannel() > 0) {
                binding.tvLockedChannel.setText("CH " + signal.getChannel()
                        + " (" + signal.getFrequency() + " MHz)");
            } else {
                binding.tvLockedChannel.setText(signal.getType() == SignalSource.SignalType.WIFI
                        ? "WiFi" : "BLE");
            }
            binding.tvLockedRssi.setText(signal.getRssi() + " dBm");
            binding.tvLockedDistance.setText(String.format("%.1f", signal.getEstimatedDistance()) + "m");

            // 更新方向箭头
            Direction dir = getSignalDirection(signal);
            String arrowText;
            switch (dir) {
                case LEFT:   arrowText = "◄ 左方 (" + Math.abs(signal.getBearing() - deviceAzimuth) + "°)"; break;
                case RIGHT:  arrowText = "► 右方 (" + Math.abs(signal.getBearing() - deviceAzimuth) + "°)"; break;
                case TOP:    arrowText = "▲ 上方"; break;
                case BOTTOM: arrowText = "▼ 下方"; break;
                default:     arrowText = "● 前方"; break;
            }
            binding.tvLockedDirection.setText(arrowText);
        } else {
            binding.cardLockedSignal.setVisibility(View.GONE);
            binding.layoutLockedArrow.setVisibility(View.GONE);
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 判断信号相对于设备朝向的方向
     */
    private Direction getSignalDirection(SignalSource signal) {
        float relativeAngle = signal.getBearing() - deviceAzimuth;

        // 归一化到 -180 ~ 180
        while (relativeAngle > 180f) relativeAngle -= 360f;
        while (relativeAngle < -180f) relativeAngle += 360f;

        float absAngle = Math.abs(relativeAngle);

        if (absAngle <= DIRECTION_THRESHOLD) {
            // 基本正前方，根据仰角判断上/下
            if (devicePitch < -15f) return Direction.BOTTOM;
            if (devicePitch > 15f) return Direction.TOP;
            return Direction.TOP; // 默认显示在上方
        } else if (relativeAngle > 0) {
            // 信号在右侧
            return Direction.RIGHT;
        } else {
            // 信号在左侧
            return Direction.LEFT;
        }
    }

    /**
     * 创建一个信号标签 TextView
     */
    private TextView createSignalLabel(SignalSource signal) {
        float density = getResources().getDisplayMetrics().density;

        TextView tv = new TextView(requireContext());
        tv.setTag(TAG_HUD);

        // 类型颜色：WiFi 蓝，BLE 黄
        int textColor;
        if (signal.getType() == SignalSource.SignalType.BLUETOOTH_LE) {
            textColor = Color.parseColor("#FFEB3B"); // 黄色 BLE
        } else {
            textColor = Color.parseColor("#2196F3"); // 蓝色 WiFi
        }

        tv.setTextColor(textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setGravity(android.view.Gravity.CENTER_VERTICAL);

        int padH = (int) (10 * density);
        int padV = (int) (4 * density);
        tv.setPadding(padH, padV, padH, padV);

        // 方向箭头前缀
        float relativeAngle = signal.getBearing() - deviceAzimuth;
        while (relativeAngle > 180f) relativeAngle -= 360f;
        while (relativeAngle < -180f) relativeAngle += 360f;

        String arrow;
        if (Math.abs(relativeAngle) <= DIRECTION_THRESHOLD) arrow = "●";
        else if (relativeAngle > 0) arrow = "►";
        else arrow = "◄";

        String text = arrow + " " + signal.getName()
                + "  " + signal.getRssi() + "dBm"
                + "  ~" + String.format("%.1f", signal.getEstimatedDistance()) + "m";
        tv.setText(text);

        // 半透明背景
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.parseColor("#99000000"));
        bg.setCornerRadius(6 * density);
        tv.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        int margin = (int) (4 * density);
        lp.setMargins(margin, margin, margin, margin);
        tv.setLayoutParams(lp);

        return tv;
    }

    /**
     * 清除容器中所有带 TAG_HUD 标记的 View
     */
    private void clearAllLabels(ViewGroup container) {
        if (container == null) return;
        for (int i = container.getChildCount() - 1; i >= 0; i--) {
            View child = container.getChildAt(i);
            if (TAG_HUD.equals(child.getTag())) {
                container.removeViewAt(i);
            }
        }
    }

    // ==================== 方向枚举 ====================

    private enum Direction {
        LEFT, RIGHT, TOP, BOTTOM
    }

    // ==================== 十字准星自定义 View ====================

    /**
     * 中心十字准星 View，带陀螺仪水平参照线
     */
    public static class CrosshairView extends View {
        private Paint crosshairPaint;
        private Paint centerDotPaint;
        private Paint levelLinePaint;

        public CrosshairView(Context context) {
            super(context);
            init();
        }

        public CrosshairView(Context context, android.util.AttributeSet attrs) {
            super(context, attrs);
            init();
        }

        public CrosshairView(Context context, android.util.AttributeSet attrs, int defStyleAttr) {
            super(context, attrs, defStyleAttr);
            init();
        }

        private void init() {
            crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            crosshairPaint.setColor(Color.argb(120, 0, 230, 118));
            crosshairPaint.setStyle(Paint.Style.STROKE);
            crosshairPaint.setStrokeWidth(2f);

            centerDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            centerDotPaint.setColor(Color.argb(180, 0, 230, 118));
            centerDotPaint.setStyle(Paint.Style.FILL);

            levelLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            levelLinePaint.setColor(Color.argb(60, 0, 230, 118));
            levelLinePaint.setStyle(Paint.Style.STROKE);
            levelLinePaint.setStrokeWidth(1f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            int w = getWidth();
            int h = getHeight();

            if (w <= 0 || h <= 0) return;

            float cx = w / 2f;
            float cy = h / 2f;
            float radius = Math.min(cx, cy);

            // 外圈
            canvas.drawCircle(cx, cy, radius * 0.9f, crosshairPaint);

            // 十字线
            canvas.drawLine(cx - radius, cy, cx + radius, cy, crosshairPaint);
            canvas.drawLine(cx, cy - radius, cx, cy + radius, crosshairPaint);

            // 对角线（淡色）
            float diag = radius * 0.7f;
            canvas.drawLine(cx - diag, cy - diag, cx + diag, cy + diag, levelLinePaint);
            canvas.drawLine(cx - diag, cy + diag, cx + diag, cy - diag, levelLinePaint);

            // 中心点
            canvas.drawCircle(cx, cy, 4f, centerDotPaint);
        }
    }
}
