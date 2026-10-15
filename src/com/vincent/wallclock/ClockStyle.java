package com.vincent.wallclock;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.vincent.wallclock.gen.ClockSpec;

/**
 * 时钟的样式化绘制（依赖 Canvas，不做任何历法/版式决策）。
 *
 * <p>字模、配色、步进宽度都在 {@link ClockSpec} 里（纯算术，可桌面单测），
 * 这里只负责把它们画出来：辉光的多层描边、玻璃管的堆叠电极、翻页牌的中缝、
 * 全息的扫描线与色差。
 *
 * <p>两条刻意的规定：
 * <ul>
 *   <li><b>不发光时就不画辉光</b>：浅色主题下发光样式会配一块同色系浅底，
 *       数字改用该样式的深色版实心画。在亮底上糊一圈光晕只会显得脏。</li>
 *   <li><b>每个字符有固定步进</b>：七段和点阵不是等宽字形，步进由
 *       {@link ClockSpec#advance} 给，否则秒数一跳整块就左右抖。</li>
 * </ul>
 */
public final class ClockStyle {

    private ClockStyle() {
    }

    /**
     * 按样式画整块时间。
     *
     * @param text  只含 {@code 0-9 :} 与空格的串（见 {@link ClockSpec#text}）
     * @param ampm 12 小时制下的「上午/下午」，24 小时制传空串
     * @param dark  当前是否深色主题，决定用发光色还是该样式的深色版
     * @param glow  辉光强度倍率（0 表示完全不发光）
     * @param prev  上一次显示的串，用于翻页钟的翻片动画；没有就传 null
     * @param flipFrac 翻片进度 0~1（&lt;0 或 ≥1 表示不翻）
     * @return 实际占用的宽度（含「上午/下午」）
     */
    public static float draw(Canvas c, Paint p, Palette pal, int style, boolean dark,
                             String text, String ampm, float left, float top, float w, float h,
                             float glow, boolean landscape, String prev, float flipFrac) {
        int st = ClockSpec.norm(style);
        if (st == ClockSpec.MODERN || text == null || text.length() == 0 || w <= 4f || h <= 4f) {
            return 0f;
        }

        // 步进宽度之和 → 反推字高（unit），保证整块刚好放进框里
        int kind = ClockSpec.kind(st);
        float sum = ClockSpec.advanceSum(text, st);
        if (!(sum > 0f)) {
            return 0f;
        }

        // 「上午/下午」会挤掉数字的宽度，所以量一次、扣掉再量一次，两轮就够收敛
        boolean hasAmpm = ampm != null && ampm.length() > 0;
        float ampmW = 0f;
        float unit = ClockSpec.fitUnit(text, st, w, h, 0f);
        for (int pass = 0; pass < 2; pass++) {
            if (unit <= 0f) {
                return 0f;
            }
            if (hasAmpm) {
                p.setTypeface(Typeface.DEFAULT);
                p.setTextSize(unit * 0.30f);
                ampmW = p.measureText(ampm) + unit * 0.14f;
            }
            unit = ClockSpec.fitUnit(text, st, w, h, ampmW);
        }
        if (unit <= 0f) {
            return 0f;
        }
        float totalW = sum * unit + ampmW;
        float x0 = landscape ? left : left + Math.max(0f, (w - totalW) / 2f);
        float cy = top + h * 0.5f;

        // 底板：发光样式在浅色主题下靠它才有「该配的背景」
        float padX = unit * ClockSpec.PAD_RATIO;
        float padY = unit * ClockSpec.PAD_RATIO;
        boolean backdrop = ClockSpec.hasBackdrop(st);
        RectF box = new RectF(x0 - padX, cy - unit * 0.5f - padY,
                x0 + totalW + padX, cy + unit * 0.5f + padY);
        if (backdrop) {
            drawBackdrop(c, p, st, dark, box, unit);
        }

        int lit = ClockSpec.litColor(st, dark);
        int dim = ClockSpec.dimColor(st, dark);
        boolean doGlow = dark && ClockSpec.glows(st) && glow > 0.01f;

        // 背景装饰：必须画在数字之前，否则雨点、网格会糊在字上
        switch (st) {
            case ClockSpec.MATRIX:
                drawMatrixRain(c, p, box, unit, lit);
                break;
            case ClockSpec.CYBER:
                drawCyberGrid(c, p, box, unit, lit);
                break;
            default:
                break;
        }

        float x = x0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            float cellW = ClockSpec.advance(ch, st) * unit;
            float inkW = cellW * ClockSpec.inkRatio(ch, st);
            float bx = x + (cellW - inkW) / 2f;
            float by = cy - unit * 0.5f;
            // 这一位变了才翻：没变的位置保持静止，整块看起来才像真的机械牌
            char pch = (prev != null && prev.length() == text.length()) ? prev.charAt(i) : 0;
            float cf = (pch != 0 && pch != ch) ? flipFrac : -1f;

            if (ch == ':') {
                drawColon(c, p, st, dark, bx, by, inkW, unit, lit, dim, doGlow ? glow : 0f);
            } else if (ch == ' ') {
                // 空位：七段/点阵里就是这一位不亮
            } else {
                switch (kind) {
                    case ClockSpec.KIND_SEVEN:
                        drawSeven(c, p, st, dark, bx, by, inkW, unit, ch, lit, dim,
                                doGlow ? glow : 0f);
                        break;
                    case ClockSpec.KIND_DOT:
                        drawDots(c, p, st, dark, bx, by, inkW, unit, ch, lit, dim,
                                doGlow ? glow : 0f);
                        break;
                    default:
                        drawGlyph(c, p, st, dark, bx, by, inkW, unit, ch, lit, dim,
                                doGlow ? glow : 0f, pch, cf);
                        break;
                }
            }
            x += cellW;
        }

        if (hasAmpm) {
            float ampmSize = unit * 0.30f;
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(Typeface.DEFAULT);
            p.setTextAlign(Paint.Align.LEFT);
            p.setTextSize(ampmSize);
            p.setColor(pal.textFaint);
            c.drawText(ampm, x0 + sum * unit + unit * 0.14f,
                    cy + unit * 0.5f - ampmSize * 0.22f, p);
        }

        // 前景装饰：压在最上面，才有「一层玻璃 / 一道扫描光」的感觉
        switch (st) {
            case ClockSpec.HOLO:
                drawHoloDecor(c, p, st, dark, box, unit, lit);
                break;
            case ClockSpec.SCAN:
                drawScan(c, p, box, unit, lit, sweepValue(text));
                break;
            case ClockSpec.CRT:
                drawCrtScan(c, p, box, unit, lit);
                break;
            default:
                break;
        }
        return totalW;
    }

    // ------------------------------------------------------------------ 底板

    private static void drawBackdrop(Canvas c, Paint p, int st, boolean dark, RectF box, float unit) {
        float r = unit * (st == ClockSpec.FLIP ? 0.10f : 0.16f);
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(ClockSpec.backdropColor(st, dark));
        c.drawRoundRect(box, r, r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, unit * 0.022f));
        p.setColor(ClockSpec.frameColor(st, dark));
        c.drawRoundRect(box, r, r, p);
        p.setStyle(Paint.Style.FILL);
    }

    // ------------------------------------------------------------------ 字形类（辉光 / 全息 / 翻页）

    private static void drawGlyph(Canvas c, Paint p, int st, boolean dark,
                                  float x, float y, float w, float h, char ch,
                                  int lit, int dim, float glow, char prev, float flipFrac) {
        switch (st) {
            case ClockSpec.NIXIE:
                drawNixie(c, p, x, y, w, h, ch, lit, dim, glow);
                break;
            case ClockSpec.HOLO:
                drawHolo(c, p, x, y, w, h, ch, lit, dim, glow);
                break;
            case ClockSpec.FLIP:
                drawFlip(c, p, dark, x, y, w, h, ch, lit, dim, prev, flipFrac);
                break;
            case ClockSpec.MATRIX:
                drawMatrix(c, p, x, y, w, h, ch, lit, dim, glow);
                break;
            case ClockSpec.NEON:
                drawNeon(c, p, x, y, w, h, ch, lit, glow);
                break;
            case ClockSpec.CRT:
                drawCrt(c, p, x, y, w, h, ch, lit, glow);
                break;
            case ClockSpec.SCAN:
                drawGlowText(c, p, x, y, w, h, ch, lit, glow, 0.050f, false, lit);
                break;
            case ClockSpec.CYBER:
                drawCyber(c, p, x, y, w, h, ch, lit, glow);
                break;
            default:
                drawPlain(c, p, x, y, w, h, ch, lit);
                break;
        }
    }

    // ------------------------------------------------------------------ 科技感一组

    /**
     * 通用「描边辉光字」：由粗到细几层同色描边堆出辉光，最里面一道更细的近白内芯。
     *
     * <p>霓虹、矩阵、雷达这些样式都是「发光管」的路子，差别只在配色、笔画粗细
     * 和有没有内芯，所以抽成一个函数，省得每个样式各写一遍多层描边。
     */
    private static void drawGlowText(Canvas c, Paint p, float x, float y, float w, float h, char ch,
                                     int lit, float glow, float weight, boolean innerWhite,
                                     int core) {
        String s = String.valueOf(ch);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(h * 0.92f);
        float cx = x + w / 2f;
        Paint.FontMetrics fm = p.getFontMetrics();
        float base = y + h * 0.5f - (fm.ascent + fm.descent) / 2f;

        if (glow > 0.01f) {
            float[] mul = {2.6f, 1.7f, 1.10f};
            int[] alpha = {0x20, 0x3C, 0x6E};
            for (int i = 0; i < mul.length; i++) {
                p.setColor(withAlpha(lit, (int) (alpha[i] * glow)));
                p.setStrokeWidth(h * weight * mul[i] * (0.7f + glow * 0.4f));
                c.drawText(s, cx, base, p);
            }
        }
        p.setStrokeWidth(h * weight);
        p.setColor(core);
        c.drawText(s, cx, base, p);
        if (innerWhite && glow > 0.01f) {
            p.setStrokeWidth(h * weight * 0.42f);
            p.setColor(withAlpha(0xFFFFFFFF, 0xD0));
            c.drawText(s, cx, base, p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextAlign(Paint.Align.LEFT);
    }

    /** 矩阵雨：字自己带一层辉光，背后再压两粒雨点，和整块的雨连成一片 */
    private static void drawMatrix(Canvas c, Paint p, float x, float y, float w, float h, char ch,
                                   int lit, int dim, float glow) {
        String s = String.valueOf(ch);
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.MONOSPACE);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(h * 0.18f);
        p.setColor(withAlpha(lit, 0x34));
        c.drawText(s, x + w * 0.18f, y + h * 0.20f, p);
        c.drawText(s, x + w * 0.84f, y + h * 0.90f, p);
        drawGlowText(c, p, x, y, w, h, ch, lit, glow, 0.055f, false, lit);
    }

    /** 霓虹灯管：品红管身 + 白色内芯，管子上缘一道玻璃反光 */
    private static void drawNeon(Canvas c, Paint p, float x, float y, float w, float h, char ch,
                                 int lit, float glow) {
        drawGlowText(c, p, x, y, w, h, ch, lit, glow, 0.062f, true, lit);
        // 管子上缘的高光：细长一条，像灯管表面反出来的光
        float rr = h * 0.026f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(withAlpha(0xFFFFFFFF, 0x30));
        c.drawRoundRect(new RectF(x + w * 0.14f, y + h * 0.05f, x + w * 0.86f, y + h * 0.05f + rr),
                rr, rr, p);
    }

    /** CRT 绿屏：绿磷光 + 红蓝色差，扫描线由前景装饰压上去 */
    private static void drawCrt(Canvas c, Paint p, float x, float y, float w, float h, char ch,
                                int lit, float glow) {
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(h * 0.92f);
        float cx = x + w / 2f;
        Paint.FontMetrics fm = p.getFontMetrics();
        float base = y + h * 0.5f - (fm.ascent + fm.descent) / 2f;
        String s = String.valueOf(ch);
        // 显像管的分色不准：红蓝各偏一点点，绿色主体压在上面
        float d = h * 0.016f + h * 0.014f * glow;
        p.setColor(withAlpha(0xFFFF3020, 0x46));
        c.drawText(s, cx - d, base, p);
        p.setColor(withAlpha(0xFF2080FF, 0x46));
        c.drawText(s, cx + d, base, p);
        if (glow > 0.01f) {
            p.setColor(withAlpha(lit, (int) (0x30 * glow)));
            c.drawText(s, cx, base - h * 0.012f, p);
        }
        p.setColor(lit);
        c.drawText(s, cx, base, p);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextAlign(Paint.Align.LEFT);
    }

    /** 赛博朋克：青 / 品红错位描边 + 中间青色实线，招牌上的重影 */
    private static void drawCyber(Canvas c, Paint p, float x, float y, float w, float h, char ch,
                                  int lit, float glow) {
        String s = String.valueOf(ch);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(h * 0.88f);
        float cx = x + w / 2f;
        Paint.FontMetrics fm = p.getFontMetrics();
        float base = y + h * 0.5f - (fm.ascent + fm.descent) / 2f;
        float d = h * 0.030f;

        if (glow > 0.01f) {
            p.setStrokeWidth(h * 0.17f * (0.6f + glow * 0.4f));
            p.setColor(withAlpha(lit, (int) (0x2A * glow)));
            c.drawText(s, cx, base, p);
        }
        p.setStrokeWidth(h * 0.048f);
        p.setColor(withAlpha(0xFFFF2FA0, 0xB4));
        c.drawText(s, cx - d, base, p);
        p.setColor(withAlpha(0xFF2FE8FF, 0xB4));
        c.drawText(s, cx + d, base, p);
        p.setStrokeWidth(h * 0.050f);
        p.setColor(lit);
        c.drawText(s, cx, base, p);
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextAlign(Paint.Align.LEFT);
    }

    /** 数字里最后两位（有秒就是秒，没秒就是分），给「按时间推进」的装饰用 */
    private static int sweepValue(String text) {
        if (text == null || text.length() < 2) {
            return 0;
        }
        try {
            return Integer.parseInt(text.substring(text.length() - 2).trim());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int hash(int v) {
        int x = v * 0x9E3779B1;
        x ^= x >>> 15;
        x *= 0x85EBCA6B;
        x ^= x >>> 13;
        return x & 0x7FFFFFFF;
    }

    /**
     * 矩阵雨的雨幕：分列下落，每列有自己的速度与相位。
     *
     * <p>推进的节拍刻意跟着<b>整秒</b>走（和挂钟本身的刷新同频），
     * 不做每秒几十帧的动画——挂墙上是要亮一整天的，为了雨点好看去飙帧率不值当。
     */
    private static void drawMatrixRain(Canvas c, Paint p, RectF box, float unit, int lit) {
        float step = unit * 0.34f;
        float cellH = unit * 0.30f;
        if (step < 3f || cellH < 2f) {
            return;
        }
        int cols = (int) (box.width() / step);
        if (cols <= 0) {
            return;
        }
        if (cols > 64) {
            cols = 64;
        }
        int rows = (int) (box.height() / cellH) + 2;
        if (rows <= 0) {
            return;
        }
        long t = System.currentTimeMillis() / 1000L;
        float size = cellH * 0.84f;

        int save = c.save();
        c.clipRect(box.left, box.top, box.right, box.bottom);
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.MONOSPACE);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(size);
        for (int i = 0; i < cols; i++) {
            int h1 = hash(i * 7 + 3);
            int speed = 1 + (h1 % 3);
            float head = (float) ((t * speed + (h1 % 97)) % (rows + 8)) - 4f;
            for (int k = 0; k < 7; k++) {
                float r = head - k;
                if (r < 0f || r > rows) {
                    continue;
                }
                int alpha = (int) (0x58 * (1f - k / 7f));
                if (alpha <= 2) {
                    continue;
                }
                char ch = (char) ('0' + (hash(i * 131 + k * 17 + (int) t) % 10));
                p.setColor(withAlpha(lit, k == 0 ? Math.min(0xA0, alpha * 2) : alpha));
                c.drawText(String.valueOf(ch), box.left + step * (i + 0.5f),
                        box.top + cellH * (r + 1f), p);
            }
        }
        c.restoreToCount(save);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(Typeface.DEFAULT);
    }

    /** 赛博朋克背后的网格 */
    private static void drawCyberGrid(Canvas c, Paint p, RectF box, float unit, int lit) {
        float step = unit * 0.30f;
        if (step < 3f) {
            return;
        }
        int save = c.save();
        c.clipRect(box.left, box.top, box.right, box.bottom);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, unit * 0.012f));
        p.setColor(withAlpha(lit, 0x1E));
        for (float x = box.left + step; x < box.right; x += step) {
            c.drawLine(x, box.top, x, box.bottom, p);
        }
        for (float y = box.top + step; y < box.bottom; y += step) {
            c.drawLine(box.left, y, box.right, y, p);
        }
        p.setStyle(Paint.Style.FILL);
        c.restoreToCount(save);
    }

    /** 雷达：上下边缘的刻度 + 一条每 60 秒从左扫到右的光带 */
    private static void drawScan(Canvas c, Paint p, RectF box, float unit, int lit, int sweep) {
        float tick = unit * 0.10f;
        float tw = Math.max(1f, unit * 0.020f);
        int n = Math.max(1, (int) (box.width() / (unit * 0.34f)));
        p.setStyle(Paint.Style.FILL);
        p.setColor(withAlpha(lit, 0x55));
        for (int i = 0; i <= n; i++) {
            float x = box.left + box.width() * i / n;
            float len = (i % 5 == 0) ? tick * 1.7f : tick * 0.8f;
            c.drawRect(x, box.top, x + tw, box.top + len, p);
            c.drawRect(x, box.bottom - len, x + tw, box.bottom, p);
        }
        // 光带：五条由外到内渐亮的竖条，像扫过去的一道亮
        int save = c.save();
        c.clipRect(box.left, box.top, box.right, box.bottom);
        float frac = ((sweep % 60) + 60) % 60 / 60f;
        float gx = box.left + box.width() * frac;
        float bw = Math.max(2f, unit * 0.14f);
        int[] alpha = {0x2A, 0x4E, 0x80, 0x4E, 0x2A};
        for (int i = 0; i < alpha.length; i++) {
            float xx = gx + (i - 2) * bw * 0.62f;
            p.setColor(withAlpha(lit, alpha[i]));
            c.drawRect(xx - bw * 0.30f, box.top, xx + bw * 0.30f, box.bottom, p);
        }
        c.restoreToCount(save);
        p.setStyle(Paint.Style.FILL);
    }

    /** CRT 的扫描线与上下暗角，压在数字之上 */
    private static void drawCrtScan(Canvas c, Paint p, RectF box, float unit, int lit) {
        float step = Math.max(2f, unit * 0.075f);
        int save = c.save();
        c.clipRect(box.left, box.top, box.right, box.bottom);
        p.setStyle(Paint.Style.FILL);
        p.setColor(withAlpha(0xFF000000, 0x42));
        for (float yy = box.top + step * 0.5f; yy < box.bottom; yy += step) {
            c.drawRect(box.left, yy, box.right, Math.min(box.bottom, yy + step * 0.40f), p);
        }
        p.setColor(withAlpha(0xFF000000, 0x34));
        float vg = box.height() * 0.12f;
        c.drawRect(box.left, box.top, box.right, box.top + vg, p);
        c.drawRect(box.left, box.bottom - vg, box.right, box.bottom, p);
        c.restoreToCount(save);
        p.setStyle(Paint.Style.FILL);
    }

    /** 辉光管：先画管里隐约的其它电极，再把当前数字点亮 */
    private static void drawNixie(Canvas c, Paint p, float x, float y, float w, float h,
                                  char ch, int lit, int dim, float glow) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(h * 0.92f);

        float cx = x + w / 2f;
        Paint.FontMetrics fm = p.getFontMetrics();
        float base = y + h * 0.5f - (fm.ascent + fm.descent) / 2f;

        // 未点亮的电极：0-9 依次往里错开一点，做出管子里数字堆叠的纵深
        p.setColor(dim);
        p.setStrokeWidth(h * 0.030f);
        for (int d = 0; d < 10; d++) {
            if (d == (ch - '0')) {
                continue;
            }
            float off = (d - (ch - '0')) * h * 0.010f;
            c.drawText(String.valueOf((char) ('0' + d)), cx, base + off, p);
        }

        // 点亮：多层由粗到细的描边堆出辉光，最里面一层接近白色
        String s = String.valueOf(ch);
        if (glow > 0.01f) {
            int[] layers = {0x20, 0x3A, 0x62};
            float[] widths = {0.16f, 0.105f, 0.070f};
            for (int i = 0; i < layers.length; i++) {
                p.setColor(withAlpha(lit, (int) (layers[i] * glow)));
                p.setStrokeWidth(h * widths[i] * (0.6f + glow * 0.5f));
                c.drawText(s, cx, base, p);
            }
        }
        // 管芯：亮色描边打底，再压一道更细的近白内芯，橙红中间才是「灯丝」
        p.setStrokeWidth(h * 0.034f);
        p.setColor(lit);
        c.drawText(s, cx, base, p);
        if (glow > 0.01f) {
            p.setStrokeWidth(h * 0.015f);
            p.setColor(withAlpha(0xFFFFFFFF, 0xE0));
            c.drawText(s, cx, base, p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextAlign(Paint.Align.LEFT);
    }

    /** 全息 HUD：细线字 + 轻微色差 */
    private static void drawHolo(Canvas c, Paint p, float x, float y, float w, float h,
                                 char ch, int lit, int dim, float glow) {
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(h * 0.96f);
        float cx = x + w / 2f;
        Paint.FontMetrics fm = p.getFontMetrics();
        float base = y + h * 0.5f - (fm.ascent + fm.descent) / 2f;
        String s = String.valueOf(ch);

        if (glow > 0.01f) {
            float d = h * 0.022f * glow;
            p.setColor(withAlpha(0xFF2E7FFF, (int) (0x70 * glow)));
            c.drawText(s, cx - d, base, p);
            p.setColor(withAlpha(0xFF7FFFD8, (int) (0x70 * glow)));
            c.drawText(s, cx + d, base, p);
        }
        p.setColor(lit);
        c.drawText(s, cx, base, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    /**
     * 翻页钟：上下两半卡片 + 中缝。
     *
     * <p>这一位变了（{@code prev != ch}）才翻，并且是按「真牌子」的机械过程画的：
     * <ol>
     *   <li>下半张立刻换成新数字——牌落下来的时候下半张先露出来；</li>
     *   <li>上半张在 {@code frac &lt; 0.5} 时还是旧数字，过了一半换成新数字；</li>
     *   <li>翻片本身绕中缝折叠：前半程是旧数字的上半张往下倒（越倒越扁），
     *       后半程是新数字的上半张往上展开，同时压一层阴影——不然看不出是一块纸在动。</li>
     * </ol>
     */
    private static void drawFlip(Canvas c, Paint p, boolean dark,
                                 float x, float y, float w, float h, char ch,
                                 int lit, int dim, char prev, float flipFrac) {
        float r = h * 0.10f;
        float midY = y + h * 0.5f;
        boolean anim = prev != 0 && prev != ch && flipFrac >= 0f && flipFrac < 1f;
        int oldLit = withAlpha(lit, 0xC0);

        // ---- 牌底 ----
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(ClockSpec.cardColor(ClockSpec.FLIP, dark));
        c.drawRoundRect(new RectF(x, y, x + w, y + h), r, r, p);
        // 上半略亮、下半略暗，做出翻页牌的厚度
        p.setColor(dark ? 0x18FFFFFF : 0x10000000);
        c.drawRoundRect(new RectF(x, y, x + w, midY), r, r, p);
        p.setColor(dark ? 0x14000000 : 0x0A000000);
        c.drawRoundRect(new RectF(x, midY, x + w, y + h), r, r, p);

        // ---- 下半：新数字 ----
        int s1 = c.save();
        c.clipRect(x, midY, x + w, y + h);
        drawFlipDigit(c, p, x, y, w, h, ch, lit);
        c.restoreToCount(s1);

        // ---- 上半：前半程旧数字，后半程新数字 ----
        int s2 = c.save();
        c.clipRect(x, y, x + w, midY);
        if (anim && flipFrac < 0.5f) {
            drawFlipDigit(c, p, x, y, w, h, prev, oldLit);
        } else {
            drawFlipDigit(c, p, x, y, w, h, ch, lit);
        }
        c.restoreToCount(s2);

        // ---- 翻片：绕中缝折起来的那半张 ----
        if (anim) {
            boolean first = flipFrac < 0.5f;
            float k = first ? (1f - flipFrac * 2f) : (flipFrac * 2f - 1f);
            char fc = first ? prev : ch;
            int fLit = first ? oldLit : lit;
            int s3 = c.save();
            c.clipRect(x, y, x + w, midY);
            c.translate(0f, midY);
            c.scale(1f, Math.max(0.02f, k));
            c.translate(0f, -midY);
            drawFlipDigit(c, p, x, y, w, h, fc, fLit);
            c.restoreToCount(s3);
            // 翻片自己的影子：折叠过程中越贴住缝越暗
            p.setStyle(Paint.Style.FILL);
            p.setColor(dark ? 0x60000000 : 0x30000000);
            c.drawRect(x, midY - k * h * 0.5f, x + w, midY, p);
        }

        // ---- 中缝 ----
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, h * 0.018f));
        p.setColor(dark ? 0xFF0A0A0A : 0xFFB4B4B4);
        c.drawLine(x + w * 0.06f, midY, x + w * 0.94f, midY, p);
        p.setStyle(Paint.Style.FILL);
    }

    /** 翻页牌上的一个字（上下的裁剪由调用方做，这里整字照画） */
    private static void drawFlipDigit(Canvas c, Paint p, float x, float y, float w, float h,
                                      char ch, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(h * 0.74f);
        p.setColor(color);
        Paint.FontMetrics fm = p.getFontMetrics();
        float base = y + h * 0.5f - (fm.ascent + fm.descent) / 2f;
        c.drawText(String.valueOf(ch), x + w / 2f, base, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    private static void drawPlain(Canvas c, Paint p, float x, float y, float w, float h,
                                  char ch, int lit) {
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(h * 0.92f);
        p.setColor(lit);
        Paint.FontMetrics fm = p.getFontMetrics();
        float base = y + h * 0.5f - (fm.ascent + fm.descent) / 2f;
        c.drawText(String.valueOf(ch), x + w / 2f, base, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    // ------------------------------------------------------------------ 七段

    private static void drawSeven(Canvas c, Paint p, int st, boolean dark,
                                  float x, float y, float w, float h, char ch,
                                  int lit, int dim, float glow) {
        int mask = ClockSpec.segMask(ch);
        float t = h * (st == ClockSpec.VFD ? 0.115f : 0.130f);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);

        // 暗段：先把七段全画一遍，骨架感才对
        p.setColor(dim);
        p.setStrokeWidth(t);
        drawSegPaths(c, p, x, y, w, h, t, 0x7F);

        // 亮段
        if (glow > 0.01f && mask != 0) {
            float[] mul = {2.4f, 1.7f, 1.15f};
            int[] alpha = {0x26, 0x40, 0x70};
            for (int i = 0; i < mul.length; i++) {
                p.setColor(withAlpha(lit, (int) (alpha[i] * glow)));
                p.setStrokeWidth(t * mul[i] * (0.7f + glow * 0.4f));
                drawSegPaths(c, p, x, y, w, h, t, mask);
            }
        }
        p.setColor(lit);
        p.setStrokeWidth(t);
        drawSegPaths(c, p, x, y, w, h, t, mask);
        p.setStyle(Paint.Style.FILL);
    }

    private static void drawSegPaths(Canvas c, Paint p, float x, float y,
                                     float w, float h, float t, int mask) {
        float mid = y + h * 0.5f;
        float lx = x + t * 0.55f;
        float rx = x + w - t * 0.55f;
        float ty = y + t * 0.55f;
        float by = y + h - t * 0.55f;
        float vy0 = y + t * 0.70f;
        float vy1 = mid - t * 0.55f;
        float vy2 = mid + t * 0.55f;
        float vy3 = y + h - t * 0.70f;

        if ((mask & ClockSpec.SEG_A) != 0) {
            c.drawLine(lx, ty, rx, ty, p);
        }
        if ((mask & ClockSpec.SEG_G) != 0) {
            c.drawLine(lx, mid, rx, mid, p);
        }
        if ((mask & ClockSpec.SEG_D) != 0) {
            c.drawLine(lx, by, rx, by, p);
        }
        if ((mask & ClockSpec.SEG_F) != 0) {
            c.drawLine(x + t * 0.55f, vy0, x + t * 0.55f, vy1, p);
        }
        if ((mask & ClockSpec.SEG_B) != 0) {
            c.drawLine(rx, vy0, rx, vy1, p);
        }
        if ((mask & ClockSpec.SEG_E) != 0) {
            c.drawLine(x + t * 0.55f, vy2, x + t * 0.55f, vy3, p);
        }
        if ((mask & ClockSpec.SEG_C) != 0) {
            c.drawLine(rx, vy2, rx, vy3, p);
        }
    }

    // ------------------------------------------------------------------ 点阵

    private static void drawDots(Canvas c, Paint p, int st, boolean dark,
                                 float x, float y, float w, float h, char ch,
                                 int lit, int dim, float glow) {
        int[] rows = ClockSpec.dotRows(ch);
        if (rows == null) {
            return;
        }
        int cols = ClockSpec.DOT_COLS;
        int rws = ClockSpec.DOT_ROWS;
        float stepX = w / cols;
        float stepY = h / rws;
        float r = Math.min(stepX, stepY) * 0.34f;
        p.setStyle(Paint.Style.FILL);
        for (int r0 = 0; r0 < rws; r0++) {
            for (int c0 = 0; c0 < cols; c0++) {
                float cx = x + stepX * (c0 + 0.5f);
                float cy = y + stepY * (r0 + 0.5f);
                boolean on = ClockSpec.dotOn(rows, r0, c0);
                if (!on) {
                    p.setColor(dim);
                    c.drawCircle(cx, cy, r * 0.62f, p);
                    continue;
                }
                if (glow > 0.01f) {
                    p.setColor(withAlpha(lit, (int) (0x38 * glow)));
                    c.drawCircle(cx, cy, r * (1.5f + glow * 0.7f), p);
                    p.setColor(withAlpha(lit, (int) (0x60 * glow)));
                    c.drawCircle(cx, cy, r * (1.18f + glow * 0.3f), p);
                }
                p.setColor(lit);
                c.drawCircle(cx, cy, r, p);
            }
        }
    }

    // ------------------------------------------------------------------ 冒号 / 装饰

    private static void drawColon(Canvas c, Paint p, int st, boolean dark,
                                  float x, float y, float w, float h,
                                  int lit, int dim, float glow) {
        if (st == ClockSpec.FLIP) {
            drawFlipColon(c, p, dark, x, y, w, h, lit);
            return;
        }
        float r = h * 0.062f;
        float cx = x + w / 2f;
        float y1 = y + h * 0.32f;
        float y2 = y + h * 0.68f;
        p.setStyle(Paint.Style.FILL);
        if (glow > 0.01f) {
            p.setColor(withAlpha(lit, (int) (0x40 * glow)));
            c.drawCircle(cx, y1, r * (1.9f + glow * 0.6f), p);
            c.drawCircle(cx, y2, r * (1.9f + glow * 0.6f), p);
        }
        p.setColor(lit);
        c.drawCircle(cx, y1, r, p);
        c.drawCircle(cx, y2, r, p);
    }

    private static void drawFlipColon(Canvas c, Paint p, boolean dark,
                                      float x, float y, float w, float h, int lit) {
        float r = h * 0.075f;
        float cx = x + w / 2f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(lit);
        c.drawCircle(cx, y + h * 0.32f, r, p);
        c.drawCircle(cx, y + h * 0.68f, r, p);
    }

    /** 全息样式的四角框 + 扫描线 */
    private static void drawHoloDecor(Canvas c, Paint p, int st, boolean dark,
                                      RectF box, float unit, int lit) {
        float len = unit * 0.26f;
        float sw = Math.max(1f, unit * 0.022f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(sw);
        p.setColor(withAlpha(lit, 0x90));
        // 左上
        c.drawLine(box.left, box.top + len, box.left, box.top, p);
        c.drawLine(box.left, box.top, box.left + len, box.top, p);
        // 右上
        c.drawLine(box.right - len, box.top, box.right, box.top, p);
        c.drawLine(box.right, box.top, box.right, box.top + len, p);
        // 左下
        c.drawLine(box.left, box.bottom - len, box.left, box.bottom, p);
        c.drawLine(box.left, box.bottom, box.left + len, box.bottom, p);
        // 右下
        c.drawLine(box.right - len, box.bottom, box.right, box.bottom, p);
        c.drawLine(box.right, box.bottom, box.right, box.bottom - len, p);

        // 扫描线
        float step = Math.max(2f, unit * 0.075f);
        p.setColor(withAlpha(lit, 0x18));
        for (float yy = box.top + step; yy < box.bottom; yy += step) {
            c.drawLine(box.left, yy, box.right, yy, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    // ------------------------------------------------------------------ 工具

    private static int withAlpha(int color, int alpha) {
        int a = alpha < 0 ? 0 : (alpha > 255 ? 255 : alpha);
        return (color & 0x00FFFFFF) | (a << 24);
    }
}
