package com.signalhub.scanner.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import com.signalhub.scanner.model.SignalSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 极简雷达扫描 View。
 * 纯黑背景 + 十字线 + 从中心向外的彩色射线表示信号方向。
 * 完全避免任何可能导致颜色混合/背景污染的绘制方式。
 */
public class RadarView extends View {

    // ==================== 常量 ====================

    private static final int COLOR_BACKGROUND = 0xFF000000;
    private static final int COLOR_CROSSHAIR = 0xFF1B5E20;
    private static final int COLOR_WIFI = 0xFF2196F3;
    private static final int COLOR_BLE = 0xFFFFFF00;
    private static final int COLOR_SWEEP = 0xFF4CAF50;
    private static final long SWEEP_DURATION_MS = 2500L;

    private static final float MAX_DOTS = 20;
    private static final float MAX_LABELS = 10;

    // ==================== 状态 ====================

    private List<SignalSource> signals = new ArrayList<>();
    private float deviceAzimuth = 0f;
    private final List<SignalSource> lockedSignals = new ArrayList<>();
    private float sweepProgress = 0f;
    private boolean isScanning = false;
    private ValueAnimator sweepAnimator;

    // ==================== Paint（全部在 init 中一次性创建） ====================

    private final Paint bgPaint = new Paint();
    private final Paint crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lockRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public RadarView(Context context) {
        super(context);
        init();
    }

    public RadarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RadarView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        crosshairPaint.setColor(COLOR_CROSSHAIR);
        crosshairPaint.setStyle(Paint.Style.STROKE);
        crosshairPaint.setStrokeWidth(1.5f);

        sweepPaint.setColor(COLOR_SWEEP);
        sweepPaint.setStyle(Paint.Style.STROKE);
        sweepPaint.setStrokeWidth(2f);

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2f);

        dotPaint.setStyle(Paint.Style.FILL);

        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(24f);

        lockRingPaint.setColor(Color.RED);
        lockRingPaint.setStyle(Paint.Style.STROKE);
        lockRingPaint.setStrokeWidth(3f);
    }

    // ==================== 公共方法 ====================

    public void setSignals(List<SignalSource> signals) {
        if (signals != null) {
            this.signals = new ArrayList<>(signals);
            Collections.sort(this.signals, (a, b) -> Integer.compare(b.getRssi(), a.getRssi()));
        } else {
            this.signals = new ArrayList<>();
        }
        invalidate();
    }

    public void setDeviceAzimuth(float azimuth) {
        this.deviceAzimuth = azimuth;
        invalidate();
    }

    public void setLockedSignals(List<SignalSource> signals) {
        lockedSignals.clear();
        if (signals != null) {
            lockedSignals.addAll(signals);
        }
        invalidate();
    }

    public void startScan() {
        if (isScanning) return;
        isScanning = true;
        sweepAnimator = ValueAnimator.ofFloat(0f, 1f);
        sweepAnimator.setDuration(SWEEP_DURATION_MS);
        sweepAnimator.setRepeatCount(ValueAnimator.INFINITE);
        sweepAnimator.setInterpolator(new LinearInterpolator());
        sweepAnimator.addUpdateListener(animation -> {
            sweepProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
        sweepAnimator.start();
    }

    public void stopScan() {
        if (!isScanning) return;
        isScanning = false;
        if (sweepAnimator != null) {
            sweepAnimator.cancel();
            sweepAnimator = null;
        }
        sweepProgress = 0f;
        invalidate();
    }

    // ==================== onDraw ====================

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float cx = w / 2f;
        float cy = h / 2f;
        float radius = Math.min(cx, cy);

        // 0. 强制纯黑背景 — 这是最关键的一步
        bgPaint.setColor(COLOR_BACKGROUND);
        bgPaint.setStyle(Paint.Style.FILL);
        canvas.drawPaint(bgPaint);

        // 动态调整文字大小
        labelPaint.setTextSize(Math.max(10f, radius * 0.05f));

        // 1. 画中心十字线
        canvas.drawLine(cx - radius, cy, cx + radius, cy, crosshairPaint);
        canvas.drawLine(cx, cy - radius, cx, cy + radius, crosshairPaint);

        // 2. 画同心圆（仅描边，深绿色）
        float[] ratios = {0.25f, 0.5f, 0.75f};
        for (float r : ratios) {
            canvas.drawCircle(cx, cy, radius * r, crosshairPaint);
        }
        canvas.drawCircle(cx, cy, radius, crosshairPaint);

        // 3. 声纳脉冲环
        if (isScanning) {
            float maxR = radius * 0.9f;
            float pulseR = sweepProgress * maxR;
            int alpha = (int) ((1f - sweepProgress) * 200);
            sweepPaint.setAlpha(alpha);
            canvas.drawCircle(cx, cy, pulseR, sweepPaint);
            sweepPaint.setAlpha(255);
        }

        // 4. 画信号射线
        drawSignals(canvas, cx, cy, radius);
    }

    private void drawSignals(Canvas canvas, float cx, float cy, float radius) {
        if (signals.isEmpty()) return;

        int count = Math.min(signals.size(), (int) MAX_DOTS);

        for (int i = 0; i < count; i++) {
            SignalSource signal = signals.get(i);
            if (signal == null) continue;

            float bearing = signal.getBearing();
            float distance = signal.getEstimatedDistance();

            float relativeBearing = bearing - deviceAzimuth;
            relativeBearing = normalizeAngle(relativeBearing);

            float normalizedDist = normalizeDistance(distance);
            float pointRadius = normalizedDist * radius * 0.85f;

            float angleRad = (float) Math.toRadians(relativeBearing);
            float px = cx + pointRadius * (float) Math.sin(angleRad);
            float py = cy - pointRadius * (float) Math.cos(angleRad);

            boolean isLocked = isSignalLocked(signal);
            int color = isLocked ? Color.RED : getSignalColor(signal);

            // 画射线：从中心到信号点
            linePaint.setColor(color);
            linePaint.setStrokeWidth(2f);
            canvas.drawLine(cx, cy, px, py, linePaint);

            // 画端点：小圆点
            dotPaint.setColor(color);
            canvas.drawCircle(px, py, 4f, dotPaint);

            // 锁定信号画红色环
            if (isLocked) {
                canvas.drawCircle(px, py, 12f, lockRingPaint);
            }

            // 名称标签（前 MAX_LABELS 个）
            if (i < MAX_LABELS) {
                String name = signal.getName();
                if (name != null && !name.isEmpty()) {
                    canvas.drawText(name, px, py - 12f, labelPaint);
                }
            }
        }
    }

    private int getSignalColor(SignalSource signal) {
        return signal.getType() == SignalSource.SignalType.BLUETOOTH_LE ? COLOR_BLE : COLOR_WIFI;
    }

    private boolean isSignalLocked(SignalSource signal) {
        for (SignalSource ls : lockedSignals) {
            if (ls != null && ls.getId().equals(signal.getId())) return true;
        }
        return false;
    }

    private float normalizeAngle(float angle) {
        while (angle > 180f) angle -= 360f;
        while (angle < -180f) angle += 360f;
        return angle;
    }

    private float normalizeDistance(float distance) {
        if (distance <= 0f) return 0f;
        float maxD = 100f;
        float clamped = Math.min(distance, maxD);
        return (float) Math.max(0f, Math.min(1f, Math.log1p(clamped) / Math.log1p(maxD)));
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopScan();
    }
}
