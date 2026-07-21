package com.signalhub.scanner.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * 自定义指南针/方向指示 View。
 * 使用 Canvas 绘制带刻度、方向标签和指北箭头的圆形指南针。
 *
 * <p>角度约定: 0=北, 90=东, 180=南, 270=西，顺时针增加。</p>
 */
public class CompassView extends View {

    // ==================== 常量 ====================

    private static final String TAG = "CompassView";

    /** 背景色 */
    private static final int COLOR_BACKGROUND = 0xFF1A1A2E;

    /** 外圈边框颜色 */
    private static final int COLOR_RING = 0xFF3A3A5A;

    /** 刻度线颜色 */
    private static final int COLOR_TICK = 0xFFAAAAAA;

    /** 主方向刻度颜色 */
    private static final int COLOR_TICK_MAJOR = 0xFFFFFFFF;

    /** 方向标签颜色 */
    private static final int COLOR_LABEL = 0xFFFFFFFF;

    /** 北方向标签特殊颜色（红色） */
    private static final int COLOR_NORTH = 0xFFFF4444;

    /** 指北三角形颜色 */
    private static final int COLOR_NORTH_ARROW = 0xFFFF3333;

    /** 指南三角形颜色 */
    private static final int COLOR_SOUTH_ARROW = 0xFF666666;

    /** 中心圆点颜色 */
    private static final int COLOR_CENTER_DOT = 0xFFFFFFFF;

    /** 刻度间距（度） */
    private static final float TICK_INTERVAL_DEG = 15f;

    /** 主方向角度: 北、东、南、西 */
    private static final float[] CARDINAL_ANGLES = {0f, 90f, 180f, 270f};

    /** 主方向标签 */
    private static final String[] CARDINAL_LABELS = {"N", "E", "S", "W"};

    /** 主方向是否为北 */
    private static final boolean[] CARDINAL_IS_NORTH = {true, false, false, false};

    // ==================== 状态属性 ====================

    /** 当前方位角（度），0=北，顺时针增加 */
    private float azimuth = 0f;

    // ==================== Paint 对象 ====================

    /** 背景画笔 */
    private Paint backgroundPaint;

    /** 外圈画笔 */
    private Paint ringPaint;

    /** 刻度线画笔（小刻度） */
    private Paint tickPaint;

    /** 主刻度线画笔（大刻度，东南西北） */
    private Paint majorTickPaint;

    /** 方向标签画笔 */
    private Paint labelPaint;

    /** 北方向标签画笔 */
    private Paint northLabelPaint;

    /** 指北三角形画笔（红色） */
    private Paint northArrowPaint;

    /** 指南三角形画笔（灰色） */
    private Paint southArrowPaint;

    /** 中心圆点画笔 */
    private Paint centerDotPaint;

    /** 指北三角形路径（红色，朝北） */
    private Path northTrianglePath;

    /** 指南三角形路径（灰色，朝南） */
    private Path southTrianglePath;

    // ==================== 构造函数 ====================

    public CompassView(Context context) {
        super(context);
        initPaints();
    }

    public CompassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initPaints();
    }

    public CompassView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initPaints();
    }

    // ==================== 初始化 ====================

    /**
     * 初始化所有 Paint 对象。
     */
    private void initPaints() {
        // 背景
        backgroundPaint = createPaint(COLOR_BACKGROUND, Paint.Style.FILL, 1f);

        // 外圈
        ringPaint = createPaint(COLOR_RING, Paint.Style.STROKE, 3f);

        // 小刻度线
        tickPaint = createPaint(COLOR_TICK, Paint.Style.STROKE, 1.5f);

        // 大刻度线（东南西北方向）
        majorTickPaint = createPaint(COLOR_TICK_MAJOR, Paint.Style.STROKE, 2.5f);

        // 方向标签
        labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setColor(COLOR_LABEL);
        labelPaint.setStyle(Paint.Style.FILL);
        labelPaint.setTextAlign(Paint.Align.CENTER);

        // 北方向标签（红色）
        northLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        northLabelPaint.setColor(COLOR_NORTH);
        northLabelPaint.setStyle(Paint.Style.FILL);
        northLabelPaint.setTextAlign(Paint.Align.CENTER);
        northLabelPaint.setFakeBoldText(true);

        // 指北三角形（红色）
        northArrowPaint = createPaint(COLOR_NORTH_ARROW, Paint.Style.FILL, 1f);

        // 指南三角形（灰色）
        southArrowPaint = createPaint(COLOR_SOUTH_ARROW, Paint.Style.FILL, 1f);

        // 中心圆点
        centerDotPaint = createPaint(COLOR_CENTER_DOT, Paint.Style.FILL, 1f);

        // 初始化三角形路径（在 onDraw 中会根据尺寸更新）
        northTrianglePath = new Path();
        southTrianglePath = new Path();
    }

    /**
     * 创建基础 Paint 的辅助方法。
     */
    private Paint createPaint(int color, Paint.Style style, float strokeWidth) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setStyle(style);
        paint.setStrokeWidth(strokeWidth);
        return paint;
    }

    // ==================== 公共方法 ====================

    /**
     * 更新方位角并刷新视图。
     *
     * @param azimuth 方位角（度），0=北, 90=东, 180=南, 270=西
     */
    public void setAzimuth(float azimuth) {
        this.azimuth = azimuth;
        invalidate();
    }

    /**
     * 获取当前方位角。
     *
     * @return 方位角（度）
     */
    public float getAzimuth() {
        return azimuth;
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

        // 动态调整文字大小和尺寸比例
        float labelTextSize = Math.max(12f, radius * 0.1f);
        labelPaint.setTextSize(labelTextSize);
        northLabelPaint.setTextSize(labelTextSize * 1.1f);

        float majorTickLength = radius * 0.12f;
        float minorTickLength = radius * 0.06f;
        float labelOffset = radius * 0.2f;

        // 1. 绘制圆形背景
        canvas.drawCircle(cx, cy, radius, backgroundPaint);

        // 2. 绘制外圈边框
        canvas.drawCircle(cx, cy, radius, ringPaint);

        // 3. 绘制刻度线（每 15 度一个刻度）
        drawTicks(canvas, cx, cy, radius, majorTickLength, minorTickLength);

        // 4. 绘制方向标签 N/S/E/W
        drawCardinalLabels(canvas, cx, cy, radius, labelOffset);

        // 5. 绘制指北三角形箭头
        drawArrow(canvas, cx, cy, radius);

        // 6. 绘制中心圆点
        float dotRadius = Math.max(4f, radius * 0.04f);
        canvas.drawCircle(cx, cy, dotRadius, centerDotPaint);
    }

    // ==================== 刻度线绘制 ====================

    /**
     * 绘制所有刻度线。
     * 每 15 度一个小刻度，东南西北方向绘制大刻度。
     */
    private void drawTicks(Canvas canvas, float cx, float cy, float radius,
                           float majorTickLength, float minorTickLength) {
        for (float deg = 0f; deg < 360f; deg += TICK_INTERVAL_DEG) {
            // 判断是否为主方向
            boolean isCardinal = isCardinalAngle(deg);

            float tickLen = isCardinal ? majorTickLength : minorTickLength;
            Paint paint = isCardinal ? majorTickPaint : tickPaint;

            // 计算刻度线起点和终点
            // 角度 0=北（上方），在 canvas 坐标系中需要 -90 度偏移
            float angleRad = (float) Math.toRadians(deg - 90f);

            float outerX = cx + radius * (float) Math.cos(angleRad);
            float outerY = cy + radius * (float) Math.sin(angleRad);

            float innerX = cx + (radius - tickLen) * (float) Math.cos(angleRad);
            float innerY = cy + (radius - tickLen) * (float) Math.sin(angleRad);

            canvas.drawLine(outerX, outerY, innerX, innerY, paint);
        }
    }

    // ==================== 方向标签绘制 ====================

    /**
     * 绘制 N/E/S/W 四个主方向标签。
     */
    private void drawCardinalLabels(Canvas canvas, float cx, float cy, float radius,
                                    float labelOffset) {
        for (int i = 0; i < CARDINAL_ANGLES.length; i++) {
            float deg = CARDINAL_ANGLES[i];
            String label = CARDINAL_LABELS[i];
            Paint paint = CARDINAL_IS_NORTH[i] ? northLabelPaint : labelPaint;

            // 标签位置
            float angleRad = (float) Math.toRadians(deg - 90f);
            float labelRadius = radius - labelOffset;
            float lx = cx + labelRadius * (float) Math.cos(angleRad);
            float ly = cy + labelRadius * (float) Math.sin(angleRad);

            // 绘制文字（垂直居中修正）
            float textOffset = paint.getTextSize() / 3f;
            canvas.drawText(label, lx, ly + textOffset, paint);
        }
    }

    // ==================== 指北箭头绘制 ====================

    /**
     * 绘制指北箭头（三角形）和指南箭头。
     * 整个箭头盘随设备方位角旋转，红色尖端始终指向北方。
     */
    private void drawArrow(Canvas canvas, float cx, float cy, float radius) {
        float arrowLength = radius * 0.6f;
        float arrowHalfWidth = radius * 0.06f;
        float tailLength = radius * 0.2f;
        float tailHalfWidth = radius * 0.04f;

        // 方位角转换为 canvas 旋转角度
        // 0 度 = 北 = -90 度在 canvas 坐标中
        float rotationDeg = -azimuth;

        canvas.save();
        canvas.rotate(rotationDeg, cx, cy);

        // ---- 指北三角形（红色）----
        // 尖端在上方（北），底边在中心偏下
        northTrianglePath.reset();
        northTrianglePath.moveTo(cx, cy - arrowLength);                    // 尖端（北）
        northTrianglePath.lineTo(cx - arrowHalfWidth, cy);                // 左下
        northTrianglePath.lineTo(cx + arrowHalfWidth, cy);                // 右下
        northTrianglePath.close();
        canvas.drawPath(northTrianglePath, northArrowPaint);

        // ---- 指南三角形（灰色，较短的尾巴）----
        southTrianglePath.reset();
        southTrianglePath.moveTo(cx, cy + tailLength);                   // 尾端（南）
        southTrianglePath.lineTo(cx - tailHalfWidth, cy);                // 左上
        southTrianglePath.lineTo(cx + tailHalfWidth, cy);                // 右上
        southTrianglePath.close();
        canvas.drawPath(southTrianglePath, southArrowPaint);

        canvas.restore();
    }

    // ==================== 工具方法 ====================

    /**
     * 判断给定的角度是否为主方向（东南西北）。
     *
     * @param deg 角度值
     * @return true 如果是 0/90/180/270 度
     */
    private boolean isCardinalAngle(float deg) {
        for (float cardinal : CARDINAL_ANGLES) {
            if (Math.abs(deg - cardinal) < 0.1f) return true;
        }
        return false;
    }
}
