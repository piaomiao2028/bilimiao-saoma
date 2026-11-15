package cn.a10miaomiao.bilimiao.scan;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * 扫码按钮，两种画法：
 *  - 卡片式：白色圆底 + 主题色扫描框（悬浮按钮用）
 *  - 底栏式：只要图标 + 可选文字标签，尺寸/颜色/位置都按底栏的原生图标对齐
 */
public class ScanButton extends View {

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private boolean pressed = false;
    private boolean showBackground = true;
    private int accentColor = 0xFFFB7299;

    private int iconSizePx = 0;
    private int iconDx = 0;
    private int iconCenterFromBottom = -1;

    private String label = null;
    private int labelSizePx = 0;
    private int labelBaselineFromBottom = -1;

    public ScanButton(Context context) {
        this(context, 0xFFFB7299);
    }

    public ScanButton(Context context, int accentColor) {
        super(context);
        this.accentColor = accentColor;

        setClickable(true);
        setFocusable(true);
        setContentDescription("扫码");

        bgPaint.setStyle(Paint.Style.FILL);

        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeCap(Paint.Cap.ROUND);
        framePaint.setStrokeJoin(Paint.Join.ROUND);
        framePaint.setColor(accentColor);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setColor(0x1F000000);

        textPaint.setColor(accentColor);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setShowBackground(boolean show) {
        this.showBackground = show;
        invalidate();
    }

    public void setAccentColor(int color) {
        this.accentColor = color;
        framePaint.setColor(color);
        textPaint.setColor(color);
        invalidate();
    }

    /** 图标像素尺寸（0 = 自适应控件） */
    public void setIconSize(int px) {
        this.iconSizePx = px;
        invalidate();
    }

    public void setIconOffset(int dx, int dy) {
        this.iconDx = dx;
        this.iconDy = dy;
        invalidate();
    }

    private int iconDy = 0;

    /** 图标中心距控件底部的像素距离（底栏对齐用，-1 = 居中） */
    public void setIconCenterFromBottom(int px) {
        this.iconCenterFromBottom = px;
        invalidate();
    }

    /** 文字标签，例如「扫码」 */
    public void setLabel(String text, int sizePx) {
        this.label = text;
        this.labelSizePx = sizePx;
        textPaint.setTextSize(sizePx);
        invalidate();
    }

    /** 文字基线距控件底部的像素距离 */
    public void setLabelBaselineFromBottom(int px) {
        this.labelBaselineFromBottom = px;
        invalidate();
    }

    @Override
    public void setPressed(boolean pressed) {
        this.pressed = pressed;
        invalidate();
        super.setPressed(pressed);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();
        float size = Math.min(w, h);

        if (showBackground) {
            float pad = size * 0.04f;
            RectF circle = new RectF(pad, pad, w - pad, h - pad);
            bgPaint.setColor(pressed ? 0xFFF2F2F2 : Color.WHITE);
            canvas.drawOval(circle, bgPaint);

            borderPaint.setStrokeWidth(size * 0.012f);
            canvas.drawOval(new RectF(circle.left + size * 0.006f, circle.top + size * 0.006f,
                    circle.right - size * 0.006f, circle.bottom - size * 0.006f), borderPaint);
        } else {
            bgPaint.setColor(pressed ? 0x14000000 : Color.TRANSPARENT);
            canvas.drawCircle(w / 2f, h / 2f, size * 0.44f, bgPaint);
        }

        float box = iconSizePx > 0 ? iconSizePx : size * (showBackground ? 0.52f : 0.50f);
        float cx = w / 2f + iconDx;
        float cy = iconCenterFromBottom > 0 ? h - iconCenterFromBottom : h / 2f + iconDy;

        float left = cx - box / 2f;
        float top = cy - box / 2f;
        float right = left + box;
        float bottom = top + box;

        framePaint.setStrokeWidth(box * 0.10f);
        framePaint.setColor(pressed ? darken(accentColor) : accentColor);

        float armX = box * 0.26f;
        float armY = box * 0.26f;
        float r = box * 0.14f;

        path.reset();
        path.moveTo(left, top + armY + r);
        path.lineTo(left, top + armY);
        path.quadTo(left, top + armY, left + r, top + armY);
        path.lineTo(left + armX, top + armY);

        path.moveTo(right - armX, top + armY);
        path.lineTo(right - r, top + armY);
        path.quadTo(right, top + armY, right, top + armY + r);
        path.lineTo(right, bottom - armY - r);

        path.moveTo(right, bottom - armY - r);
        path.lineTo(right, bottom - armY);
        path.quadTo(right, bottom - armY, right - r, bottom - armY);
        path.lineTo(right - armX, bottom - armY);

        path.moveTo(left + armX, bottom - armY);
        path.lineTo(left + r, bottom - armY);
        path.quadTo(left, bottom - armY, left, bottom - armY - r);
        path.lineTo(left, top + armY + r);

        canvas.drawPath(path, framePaint);

        canvas.drawLine(left + armX + box * 0.10f, top + box * 0.5f,
                right - armX - box * 0.10f, top + box * 0.5f, framePaint);

        if (label != null) {
            textPaint.setTypeface(Typeface.DEFAULT);
            textPaint.setColor(pressed ? darken(accentColor) : accentColor);
            float baseline = labelBaselineFromBottom > 0 ? h - labelBaselineFromBottom
                    : h - size * 0.12f;
            canvas.drawText(label, w / 2f, baseline, textPaint);
        }
    }

    private static int darken(int color) {
        int a = (color >>> 24) & 0xFF;
        int r = (int) (((color >> 16) & 0xFF) * 0.75f);
        int g = (int) (((color >> 8) & 0xFF) * 0.75f);
        int b = (int) ((color & 0xFF) * 0.75f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
