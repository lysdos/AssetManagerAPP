package com.huaxia.inventory.barcode;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.Nullable;

/**
 * Decorative view drawn on top of {@code PreviewView}: dims the area outside
 * the scan frame and draws 4 corner brackets. Pure View, no touch handling —
 * CameraX keeps doing the actual frame analysis behind it.
 */
public class ScanFrameView extends View {

    private final Paint dimPaint = new Paint();
    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ScanFrameView(Context context) { super(context); init(); }
    public ScanFrameView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }
    public ScanFrameView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr); init();
    }

    private void init() {
        dimPaint.setColor(0xAA000000); // 66% black dim
        dimPaint.setStyle(Paint.Style.FILL);
        cornerPaint.setColor(0xFF1976D2); // colorPrimary
        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeWidth(dp(4f));
        cornerPaint.setStrokeCap(Paint.Cap.SQUARE);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        // 中心矩形 — 宽 75% × 高 55% (一维码横长比合适)
        int frameW = (int) (w * 0.75f);
        int frameH = (int) (frameW * 0.55f);
        int left = (w - frameW) / 2;
        int top = (h - frameH) / 2;
        int right = left + frameW;
        int bottom = top + frameH;

        // 4 块遮罩盖住中心以外
        canvas.drawRect(0, 0, w, top, dimPaint);              // top
        canvas.drawRect(0, bottom, w, h, dimPaint);           // bottom
        canvas.drawRect(0, top, left, bottom, dimPaint);      // left
        canvas.drawRect(right, top, w, bottom, dimPaint);     // right

        // 4 个角的 L 形
        int len = (int) dp(28f);
        canvas.drawLine(left, top, left + len, top, cornerPaint);
        canvas.drawLine(left, top, left, top + len, cornerPaint);
        canvas.drawLine(right, top, right - len, top, cornerPaint);
        canvas.drawLine(right, top, right, top + len, cornerPaint);
        canvas.drawLine(left, bottom, left + len, bottom, cornerPaint);
        canvas.drawLine(left, bottom, left, bottom - len, cornerPaint);
        canvas.drawLine(right, bottom, right - len, bottom, cornerPaint);
        canvas.drawLine(right, bottom, right, bottom - len, cornerPaint);
    }
}