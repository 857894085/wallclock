package com.vincent.wallclock;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;

/**
 * 天气图标全部用 Canvas 画出来（不依赖任何图片资源与字体 emoji，
 * 这样在旧机器上一定能显示）。
 */
public final class WeatherArt {

    private WeatherArt() {
    }

    /**
     * @param type  {@link Wmo} 的 ICON_* 常量
     * @param cx    中心 x
     * @param cy    中心 y
     * @param size  图标外接正方形边长
     */
    public static void draw(Canvas c, int type, float cx, float cy, float size, Palette pal, boolean isDay) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);

        // 所有图标都以"中心"为基准，内部统一用 s 作为比例尺
        float s = size;
        switch (type) {
            case Wmo.ICON_SUN:
                drawSun(c, p, cx, cy, s, pal);
                break;
            case Wmo.ICON_MOON:
                drawMoon(c, p, cx, cy, s, pal);
                break;
            case Wmo.ICON_PARTLY:
                drawPartly(c, p, cx, cy, s, pal, isDay);
                break;
            case Wmo.ICON_CLOUD:
                drawCloud(c, p, cx, cy, s, pal, pal.cloudLight);
                break;
            case Wmo.ICON_FOG:
                drawCloud(c, p, cx, cy - s * 0.14f, s * 0.86f, pal, pal.cloud);
                drawFogLines(c, p, cx, cy + s * 0.26f, s, pal);
                break;
            case Wmo.ICON_DRIZZLE:
                drawCloud(c, p, cx, cy - s * 0.14f, s * 0.86f, pal, pal.cloud);
                drawDrops(c, p, cx, cy + s * 0.22f, s, pal.rain, true);
                break;
            case Wmo.ICON_RAIN:
                drawCloud(c, p, cx, cy - s * 0.14f, s * 0.86f, pal, pal.cloud);
                drawDrops(c, p, cx, cy + s * 0.22f, s, pal.rain, false);
                break;
            case Wmo.ICON_SNOW:
                drawCloud(c, p, cx, cy - s * 0.14f, s * 0.86f, pal, pal.cloud);
                drawFlakes(c, p, cx, cy + s * 0.24f, s, pal.snow);
                break;
            case Wmo.ICON_SLEET:
                drawCloud(c, p, cx, cy - s * 0.14f, s * 0.86f, pal, pal.cloud);
                drawDrops(c, p, cx - s * 0.13f, cy + s * 0.22f, s * 0.7f, pal.rain, false);
                drawFlakes(c, p, cx + s * 0.20f, cy + s * 0.24f, s * 0.55f, pal.snow);
                break;
            case Wmo.ICON_THUNDER:
                drawCloud(c, p, cx, cy - s * 0.16f, s * 0.86f, pal, pal.cloud);
                drawBolt(c, p, cx, cy + s * 0.24f, s, pal.bolt);
                break;
            default:
                drawCloud(c, p, cx, cy, s, pal, pal.cloudLight);
        }
    }

    // ------------------------------------------------------------------ 元素

    private static void drawSun(Canvas c, Paint p, float cx, float cy, float s, Palette pal) {
        p.setColor(pal.sun);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.5f, s * 0.055f));
        p.setStrokeCap(Paint.Cap.ROUND);
        float r = s * 0.22f;
        float r1 = s * 0.31f;
        float r2 = s * 0.46f;
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8;
            c.drawLine(cx + (float) (Math.cos(a) * r1), cy + (float) (Math.sin(a) * r1),
                    cx + (float) (Math.cos(a) * r2), cy + (float) (Math.sin(a) * r2), p);
        }
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, r, p);
    }

    private static void drawMoon(Canvas c, Paint p, float cx, float cy, float s, Palette pal) {
        // 用两圆相减做出月牙：先画整圆，再用 CLEAR 抠掉偏移的圆
        p.setColor(pal.moon);
        float r = s * 0.36f;
        int layer = c.saveLayer(cx - s * 0.6f, cy - s * 0.6f, cx + s * 0.6f, cy + s * 0.6f, null);
        c.drawCircle(cx, cy, r, p);
        Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
        clear.setColor(0xFFFFFFFF);
        clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        c.drawCircle(cx + r * 0.52f, cy - r * 0.34f, r * 0.94f, clear);
        c.restoreToCount(layer);

        // 两颗小星
        p.setColor(pal.moon);
        c.drawCircle(cx + s * 0.30f, cy - s * 0.30f, Math.max(1f, s * 0.035f), p);
        c.drawCircle(cx + s * 0.40f, cy - s * 0.05f, Math.max(1f, s * 0.028f), p);
    }

    private static void drawPartly(Canvas c, Paint p, float cx, float cy, float s, Palette pal, boolean isDay) {
        // 太阳/月亮在左上，云在右下
        if (isDay) {
            drawSun(c, p, cx - s * 0.16f, cy - s * 0.18f, s * 0.72f, pal);
        } else {
            drawMoon(c, p, cx - s * 0.16f, cy - s * 0.18f, s * 0.72f, pal);
        }
        drawCloud(c, p, cx + s * 0.06f, cy + s * 0.16f, s * 0.78f, pal, pal.cloudLight);
    }

    /** 云朵：若干圆 + 底部圆角矩形并集 */
    private static void drawCloud(Canvas c, Paint p, float cx, float cy, float s, Palette pal, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        float w = s * 0.84f;
        float h = s * 0.40f;
        float baseTop = cy + h * 0.06f;
        RectF base = new RectF(cx - w / 2, baseTop, cx + w / 2, baseTop + h * 0.62f);
        c.drawRoundRect(base, h * 0.31f, h * 0.31f, p);
        c.drawCircle(cx - w * 0.24f, baseTop + h * 0.10f, h * 0.42f, p);
        c.drawCircle(cx - w * 0.02f, baseTop - h * 0.16f, h * 0.56f, p);
        c.drawCircle(cx + w * 0.24f, baseTop + h * 0.06f, h * 0.44f, p);
    }

    private static void drawDrops(Canvas c, Paint p, float cx, float cy, float s, int color, boolean tiny) {
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(Math.max(1.5f, s * 0.05f));
        float len = tiny ? s * 0.09f : s * 0.17f;
        for (int i = -1; i <= 1; i++) {
            float x = cx + i * s * 0.20f;
            float y = cy + (i == 0 ? s * 0.05f : 0f);
            c.drawLine(x, y, x - len * 0.25f, y + len, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private static void drawFlakes(Canvas c, Paint p, float cx, float cy, float s, int color) {
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(Math.max(1.2f, s * 0.04f));
        float r = s * 0.055f;
        for (int i = -1; i <= 1; i++) {
            float x = cx + i * s * 0.20f;
            float y = cy + (i == 0 ? s * 0.05f : 0f);
            c.drawLine(x - r, y, x + r, y, p);
            c.drawLine(x, y - r, x, y + r, p);
            c.drawLine(x - r * 0.7f, y - r * 0.7f, x + r * 0.7f, y + r * 0.7f, p);
            c.drawLine(x - r * 0.7f, y + r * 0.7f, x + r * 0.7f, y - r * 0.7f, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private static void drawFogLines(Canvas c, Paint p, float cx, float cy, float s, Palette pal) {
        p.setColor(pal.fog);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(Math.max(1.5f, s * 0.05f));
        for (int i = 0; i < 3; i++) {
            float y = cy + i * s * 0.11f;
            float half = s * (i == 1 ? 0.30f : 0.38f);
            c.drawLine(cx - half, y, cx + half, y, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private static void drawBolt(Canvas c, Paint p, float cx, float cy, float s, int color) {
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        Path path = new Path();
        path.moveTo(cx + s * 0.04f, cy - s * 0.16f);
        path.lineTo(cx - s * 0.16f, cy + s * 0.06f);
        path.lineTo(cx - s * 0.02f, cy + s * 0.06f);
        path.lineTo(cx - s * 0.10f, cy + s * 0.26f);
        path.lineTo(cx + s * 0.16f, cy - s * 0.02f);
        path.lineTo(cx + s * 0.01f, cy - s * 0.02f);
        path.close();
        c.drawPath(path, p);
    }
}
