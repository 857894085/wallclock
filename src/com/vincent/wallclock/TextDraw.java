package com.vincent.wallclock;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import com.vincent.wallclock.gen.Layout;

import java.util.ArrayList;
import java.util.List;

/**
 * 自绘用的文字与卡片工具。主界面与各副页共用同一套口径：
 * <b>先按目标宽高把字号缩到刚好放得下，再画</b>，所以任何分辨率、任何横竖屏
 * 都不会出现文字溢出或互相压盖。
 */
public final class TextDraw {

    /** 绝对兜底字号：再小就没意义了 */
    public static final float MIN_SIZE = 4.5f;

    /** 一行文字实际占高与字号的比值，见 {@link Layout#floorFor} 的说明 */
    public static final float LINE_RATIO = Layout.LINE_RATIO;

    private TextDraw() {
    }

    /**
     * 当前画布与字号档位下的可读下限。
     *
     * <p>各页都从这一处取，避免有人忘了加下限又把字号缩到看不见
     * （手机上「字太小看不了」的根因就是这个）。
     */
    public static float floor(float w, float h, float scale) {
        return Layout.readableFloor(Math.min(w, h), scale);
    }

    // ------------------------------------------------------------------ 字号适配

    /** 按宽高上限把字号缩到位，但不低于 {@link #MIN_SIZE} */
    public static float fit(Paint p, String s, float maxW, float maxH, float startSize, boolean mono) {
        return fit(p, s, maxW, maxH, startSize, mono, MIN_SIZE);
    }

    /**
     * 按宽高上限把字号缩到位，但<b>不低于 {@code minSize}</b>。
     *
     * <p>{@code minSize} 是可读下限（见 {@code gen/Layout#readableFloor}）。
     * 传了它之后，「放不下」就不再靠继续缩字解决，而是由调用方改用省略号或截断行
     * ——手机上把 30 项宜忌缩到 6px 是没意义的，宁可少显示几项。
     */
    public static float fit(Paint p, String s, float maxW, float maxH,
                            float startSize, boolean mono, float minSize) {
        float size = fitSize(p, s, maxW, maxH, startSize, mono, minSize);
        p.setTextSize(size);
        return size;
    }

    public static float fitSize(Paint p, String s, float maxW, float maxH, float startSize, boolean mono) {
        return fitSize(p, s, maxW, maxH, startSize, mono, MIN_SIZE);
    }

    public static float fitSize(Paint p, String s, float maxW, float maxH,
                                float startSize, boolean mono, float minSize) {
        // 下限顶破分配高度时，缩的是「下限」本身：否则循环会在 floor 处停下，
        // 文字该多高还是多高，于是压到下一行身上（这就是「文字叠起来」的来源）。
        float floor = Layout.floorFor(minSize, maxH);
        float size = Math.max(floor, startSize);
        if (s == null || s.length() == 0 || maxW <= 0 || maxH <= 0) {
            p.setTextSize(size);
            return size;
        }
        for (int guard = 0; guard < 24; guard++) {
            p.setTextSize(size);
            float tw = mono ? monoWidth(p, s) : p.measureText(s);
            Paint.FontMetrics fm = p.getFontMetrics();
            float th = fm.descent - fm.ascent;
            if (tw <= maxW && th <= maxH) {
                break;
            }
            float kw = tw > 0 ? maxW / tw : 1f;
            float kh = th > 0 ? maxH / th : 1f;
            float k = Math.min(kw, kh);
            if (k >= 0.998f) {
                break;
            }
            size = Math.max(floor, size * k);
            if (size <= floor) {
                break;
            }
        }
        p.setTextSize(size);
        return size;
    }

    /**
     * 单行文字：先按可读下限量宽，量不下就用省略号收尾。
     * 返回实际该画的字符串，并把字号写回 Paint。
     */
    public static String fitLine(Paint p, String s, float maxW, float maxH,
                                 float startSize, boolean mono, float minSize) {
        if (s == null || s.length() == 0) {
            p.setTextSize(Math.max(MIN_SIZE, startSize));
            return "";
        }
        float size = fitSize(p, s, maxW, maxH, startSize, mono, minSize);
        p.setTextSize(size);
        return ellipsize(p, s, maxW);
    }

    // ------------------------------------------------------------------ 绘制

    /**
     * 按<b>角色定死的字号</b>画一行：字号不参与压缩，宽度放不下就用省略号收尾。
     *
     * <p>和 {@link #fitLine} 的分工：{@code fitLine} 是主页面的老路子，
     * 「先量容器再缩字号」，所以同一个元素在不同卡片里会不一样大；
     * 副页用这个——字号来自 {@code gen/Type} 的角色表，**容器小了就截断文字，不动字号**。
     * 层级因此是稳定的，不会因为某张卡矮一点就整块塌下来。
     *
     * @param maxW 行宽上限（超出部分省略号）
     * @param size 角色字号，取自 {@code Type.size(font, role)}
     * @return 实际画出的宽度
     */
    public static float drawLine(Canvas c, Paint p, String s, float x, float cy,
                                 float maxW, float size, int color, int align) {
        if (s == null || s.length() == 0) {
            return 0f;
        }
        p.setTextSize(size);
        p.setColor(color);
        String t = ellipsize(p, s, maxW);
        draw(c, t, x, cy, p, align);
        return p.measureText(t);
    }

    /** align: 0 居中 1 左 2 右；cy 是文字竖直中心 */
    public static void draw(Canvas c, String s, float x, float cy, Paint p, int align) {
        if (s == null || s.length() == 0) {
            return;
        }
        Paint.FontMetrics fm = p.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) / 2f;
        float tx = x;
        if (align == 0) {
            tx = x - p.measureText(s) / 2f;
        } else if (align == 2) {
            tx = x - p.measureText(s);
        }
        c.drawText(s, tx, baseline, p);
    }

    /** 数字等宽宽度：数字统一按 "0" 的宽度算，秒数跳动时整块不会左右抖 */
    public static float monoWidth(Paint p, String s) {
        if (s == null) {
            return 0f;
        }
        float digit = p.measureText("0");
        float total = 0f;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            total += (ch >= '0' && ch <= '9') ? digit : p.measureText(s, i, i + 1);
        }
        return total;
    }

    public static float drawMono(Canvas c, String s, float x, float baseline, Paint p) {
        float digit = p.measureText("0");
        float cx = x;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            c.drawText(s.substring(i, i + 1), cx, baseline, p);
            cx += (ch >= '0' && ch <= '9') ? digit : p.measureText(s, i, i + 1);
        }
        return cx;
    }

    // ------------------------------------------------------------------ 换行与整段排版

    /**
     * 按宽度换行。先把空格分隔的整词优先排一行，遇到比整行还宽的单词再退化为按字符切。
     * 中文宜忌这类「词 + 空格」结构用这个刚好。
     */
    public static List<String> wrap(Paint p, String text, float maxW) {
        List<String> out = new ArrayList<String>();
        if (text == null || text.length() == 0 || maxW <= 0) {
            return out;
        }
        String[] tokens = text.trim().split("\\s+");
        StringBuilder line = new StringBuilder();
        for (String tk : tokens) {
            if (tk.length() == 0) {
                continue;
            }
            String cand = line.length() == 0 ? tk : line + " " + tk;
            if (p.measureText(cand) <= maxW) {
                line.setLength(0);
                line.append(cand);
                continue;
            }
            if (line.length() > 0) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (p.measureText(tk) <= maxW) {
                line.append(tk);
            } else {
                StringBuilder piece = new StringBuilder();
                for (int i = 0; i < tk.length(); i++) {
                    char ch = tk.charAt(i);
                    if (piece.length() > 0 && p.measureText(piece.toString() + ch) > maxW) {
                        out.add(piece.toString());
                        piece.setLength(0);
                    }
                    piece.append(ch);
                }
                line.append(piece);
            }
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }

    /**
     * 在 left/top 起、宽 w 高 h 的框里整段画文字：自动选一个能放下的字号，
     * 逐行排布。返回实际占用高度（没放下的部分不会溢出框外）。
     *
     * @param lineGap 行距系数，0.15 表示行高 = 字高 × 1.15
     */
    public static float drawWrapped(Canvas c, Paint p, String text, float left, float top,
                                    float w, float h, float startSize, int color,
                                    int align, float lineGap) {
        return drawWrapped(c, p, text, left, top, w, h, startSize, color, align, lineGap, MIN_SIZE);
    }

    /**
     * 同上，但字号不低于 {@code minSize}（可读下限）。
     *
     * <p>传了 {@code minSize} 之后，装不下的处理方式是<b>按行截断</b>而不是继续缩字——
     * {@link #wrap} 只在空格处断行（除非单个词比整行还宽），所以截断都落在词与词之间，
     * 不会把「祭祀」切成「祭」。宜忌这类「词 + 空格」的长串正合适。
     */
    public static float drawWrapped(Canvas c, Paint p, String text, float left, float top,
                                    float w, float h, float startSize, int color,
                                    int align, float lineGap, float minSize) {
        if (text == null || text.length() == 0 || w <= 0 || h <= 0) {
            return 0f;
        }
        float floor = (minSize > MIN_SIZE && !Float.isNaN(minSize)) ? minSize : MIN_SIZE;
        float size = Math.max(floor, startSize);
        List<String> lines = null;
        for (int guard = 0; guard < 30; guard++) {
            p.setTextSize(size);
            Paint.FontMetrics fm = p.getFontMetrics();
            float lineH = (fm.descent - fm.ascent) * (1f + lineGap);
            lines = wrap(p, text, w);
            if (lines.isEmpty()) {
                return 0f;
            }
            float need = lines.size() * lineH;
            if (need <= h || size <= floor) {
                break;
            }
            float k = h / need;
            size = Math.max(floor, size * Math.min(0.95f, k));
        }
        p.setTextSize(size);
        p.setColor(color);
        Paint.FontMetrics fm = p.getFontMetrics();
        float lineH = (fm.descent - fm.ascent) * (1f + lineGap);
        float y = top;
        float maxLines = Math.max(1f, (float) Math.floor(h / lineH + 0.0001f));
        int n = 0;
        for (String ln : lines) {
            if (n >= maxLines) {
                break;
            }
            float anchor = align == 1 ? left : (align == 2 ? left + w : left + w / 2f);
            draw(c, ln, anchor, y + lineH / 2f, p, align);
            y += lineH;
            n++;
        }
        return n * lineH;
    }

    // ------------------------------------------------------------------ 固定字号排版
    //
    // 副页改成固定字号之后，「这张卡该多高」必须由内容量算出来：内容多 → 卡片变高 →
    // 页面变长 → 上下滑，而不是把字缩小去将就高度。
    // 下面两个函数共用同一个 wrap()，所以量出来的高度和画出来的高度必然相等，不会漂。

    /**
     * 按<b>固定字号</b>量一段文字换行后要占多高（不绘制）。
     * 调用前必须设好 typeface；字号由参数指定，不会被改动逻辑覆盖。
     */
    public static float wrappedHeight(Paint p, String text, float maxW, float size, float lineGap) {
        if (text == null || text.length() == 0 || maxW <= 0f || !(size > 0f)) {
            return 0f;
        }
        p.setTextSize(size);
        List<String> lines = wrap(p, text, maxW);
        if (lines.isEmpty()) {
            return 0f;
        }
        Paint.FontMetrics fm = p.getFontMetrics();
        return lines.size() * (fm.descent - fm.ascent) * (1f + lineGap);
    }

    /**
     * 按<b>固定字号</b>整段绘制（不再缩字）。返回实际占用高度，
     * 与 {@link #wrappedHeight} 在同一个 Paint 状态下必然相等。
     */
    public static float drawWrappedFixed(Canvas c, Paint p, String text, float left, float top,
                                         float w, float size, int color, int align, float lineGap) {
        if (text == null || text.length() == 0 || w <= 0f || !(size > 0f)) {
            return 0f;
        }
        p.setTextSize(size);
        p.setColor(color);
        List<String> lines = wrap(p, text, w);
        if (lines.isEmpty()) {
            return 0f;
        }
        Paint.FontMetrics fm = p.getFontMetrics();
        float lineH = (fm.descent - fm.ascent) * (1f + lineGap);
        float y = top;
        for (String ln : lines) {
            float anchor = align == 1 ? left : (align == 2 ? left + w : left + w / 2f);
            draw(c, ln, anchor, y + lineH / 2f, p, align);
            y += lineH;
        }
        return lines.size() * lineH;
    }

    // ------------------------------------------------------------------ 卡片与分隔线

    /** 圆角卡片：填充 + 细描边，与主页面天气卡片同一套观感 */
    public static void card(Canvas c, Paint p, Palette pal, float left, float top,
                            float right, float bottom, float radius) {
        if (right - left <= 1f || bottom - top <= 1f) {
            return;
        }
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(pal.panel);
        RectF r = new RectF(left, top, right, bottom);
        c.drawRoundRect(r, radius, radius, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, Math.min(right - left, bottom - top) * 0.004f));
        p.setColor(pal.panelStroke);
        c.drawRoundRect(r, radius, radius, p);
        p.setStyle(Paint.Style.FILL);
    }

    public static void divider(Canvas c, Paint p, Palette pal, float x1, float y1, float x2, float y2) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, Math.min(Math.abs(x2 - x1) + Math.abs(y2 - y1), 400f) * 0.0025f));
        p.setColor(pal.line);
        c.drawLine(x1, y1, x2, y2, p);
        p.setStyle(Paint.Style.FILL);
    }

    /** 小圆点 / 圆角标签底色 */
    public static void pill(Canvas c, Paint p, int color, float left, float top,
                            float right, float bottom, float radius) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        p.setColor(color);
        float r = radius <= 0 ? (bottom - top) / 2f : radius;
        c.drawRoundRect(new RectF(left, top, right, bottom), r, r, p);
    }

    /** 文字加上限，超出用省略号收尾 */
    public static String ellipsize(Paint p, String s, float maxW) {
        if (s == null || s.length() == 0 || p.measureText(s) <= maxW) {
            return s == null ? "" : s;
        }
        for (int i = s.length() - 1; i > 0; i--) {
            String cand = s.substring(0, i) + "…";
            if (p.measureText(cand) <= maxW) {
                return cand;
            }
        }
        return "…";
    }

    // ------------------------------------------------------------------ 颜色

    /** 按比例混色：t=0 取 a，t=1 取 b（忽略两者的 alpha，输出不透明） */
    public static int blend(int a, int b, float t) {
        if (t <= 0f) {
            return 0xFF000000 | (a & 0x00FFFFFF);
        }
        if (t >= 1f) {
            return 0xFF000000 | (b & 0x00FFFFFF);
        }
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /**
     * 语义色在两种主题下的「底色」：深色主题往黑里压，浅色主题往白里提。
     * 用于黄道/星宿/空气质量这类彩色徽标，保证底色和卡片有明显区分又不抢眼。
     */
    public static int softBg(Palette pal, int color) {
        return pal.dark ? blend(color, 0xFF000000, 0.74f) : blend(color, 0xFFFFFFFF, 0.86f);
    }

    /** 与 {@link #softBg} 配套的前景色：浅色主题下把颜色压深，保证白底上够清晰 */
    public static int softText(Palette pal, int color) {
        return pal.dark ? blend(color, 0xFFFFFFFF, 0.10f) : blend(color, 0xFF000000, 0.26f);
    }
}
