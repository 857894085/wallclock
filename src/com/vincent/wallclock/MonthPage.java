package com.vincent.wallclock;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.vincent.wallclock.gen.Cards;
import com.vincent.wallclock.gen.Layout;
import com.vincent.wallclock.gen.Type;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 月历页：整月格子 + 点选某天看那一天的详情。
 *
 * <p>每格上面是公历日，下面是农历日 / 节气 / 节日（有节气或节日优先显示节气节日）。
 * 选中日会在右侧（横屏）或下方（竖屏）展开这一天的农历、干支、建除、值神、星宿、
 * 冲煞与宜忌——也就是把主黄历页的内容按「任意一天」重算一遍。
 *
 * <p>交互状态（当前显示哪个月、选中哪天）由本页自己持有，
 * {@link #onTap} 返回 true 表示状态变了、需要重绘。
 */
public final class MonthPage {

    private static final String[] WEEK_CN = {"日", "一", "二", "三", "四", "五", "六"};
    private static final String[] WEEK_FULL = {"日", "一", "二", "三", "四", "五", "六"};

    private static final int C_HUANG = 0xFFD9A441;
    private static final int C_HEI = 0xFF7C8FA3;
    private static final int C_AUSP = 0xFF3FBF6A;
    private static final int C_INAUSP = 0xFFE45B4C;
    private static final int C_YI = 0xFF35A85C;
    private static final int C_JI = 0xFFD0483F;

    // ---- 详情面板的角色与间距（只声明「哪一行是哪一级」，字号一律取自 gen/Type）----

    /** 详情面板顶部两行：日期（主标题级）+ 农历干支纳音（注释级） */
    private static final float[] D_HEAD_ROLES = {Type.TITLE, Type.NOTE};
    /** 面板内边距 / 块间距 / 宜忌小卡自己的内边距，单位都是「字号」 */
    private static final float D_PAD_EM = 0.80f;
    private static final float D_GAP_EM = 0.55f;
    private static final float D_BOX_PAD_EM = 0.55f;
    /** 日期行 / 正文行 / 注释行的行距系数 */
    private static final float D_TITLE_GAP = 0.10f;
    private static final float D_BODY_GAP = 0.24f;
    private static final float D_NOTE_GAP = 0.16f;
    /** 详情面板横竖屏各占的比例（横屏是右侧一列，竖屏在日历下方） */
    private static final float D_LAND_PCT = 0.60f;
    private static final float D_PORT_PCT = 0.60f;

    // ---- 交互状态 ----
    private int shownYear = -1;
    private int shownMonth = -1;
    private int selYear = -1;
    private int selMonth = -1;
    private int selDay = -1;

    /** 详情面板内部的滚动位置与可滚动距离 */
    private float detailScroll = 0f;
    private float detailRange = 0f;
    /** 上一次绘制时的详情面板矩形（页面内容坐标系），供命中测试用 */
    private final float[] detailRect = new float[4];
    /** 本页正文基准字号：只由屏幕尺寸与字号档位决定，与内容多少无关 */
    private float font = Layout.MIN_FONT;

    // ---- 绘制时记录下来的几何，供命中测试使用 ----
    private float gridLeft, gridTop, gridW, gridH;
    private float[] prevRect = new float[4];
    private float[] nextRect = new float[4];
    private float[] todayRect = new float[4];
    private int lead;
    private int daysInMonth;
    private int gridRows;

    // ---- 缓存 ----
    private final String[] cellSub = new String[37];
    private final boolean[] cellHasTerm = new boolean[37];
    private int cellCacheKey = -1;
    private Almanac selAlmanac;
    private int selAlmanacKey = -1;

    // ------------------------------------------------------------------ 状态

    /** 首次进入时对齐到今天 */
    public void syncToToday() {
        Calendar c = Calendar.getInstance();
        int y = c.get(Calendar.YEAR);
        int m = c.get(Calendar.MONTH) + 1;
        int d = c.get(Calendar.DAY_OF_MONTH);
        if (shownYear < 0) {
            shownYear = y;
            shownMonth = m;
        }
        if (selYear < 0) {
            selYear = y;
            selMonth = m;
            selDay = d;
        }
    }

    public int selectedYear() {
        return selYear;
    }

    public int selectedMonth() {
        return selMonth;
    }

    public int selectedDay() {
        return selDay;
    }

    private static int todayY() {
        return Calendar.getInstance().get(Calendar.YEAR);
    }

    private static int todayM() {
        return Calendar.getInstance().get(Calendar.MONTH) + 1;
    }

    private static int todayD() {
        return Calendar.getInstance().get(Calendar.DAY_OF_MONTH);
    }

    // ------------------------------------------------------------------ 入口

    public void draw(Canvas c, Paint p, Palette pal, Prefs prefs,
                     float left, float top, float w, float h, boolean landscape) {
        if (w <= 8 || h <= 8) {
            return;
        }
        syncToToday();
        // 本页所有字号的总源头：屏幕尺寸 + 字号档位。内容多少不参与——
        // 「宜忌一多字就变小」的根子就是这里以前按块高乘系数。
        font = Layout.pageFont(w, h, prefs.pageDetail, prefs.textScale());
        float gap = Math.min(w, h) * 0.024f;

        if (landscape) {
            float lw = (w - gap) * D_LAND_PCT;
            float rw = w - gap - lw;
            drawGrid(c, p, pal, prefs, left, top, lw, h);
            drawDetail(c, p, pal, left + lw + gap, top, rw, h);
        } else {
            float gh = (h - gap) * D_PORT_PCT;
            drawGrid(c, p, pal, prefs, left, top, w, gh);
            drawDetail(c, p, pal, left, top + gh + gap, w, h - gh - gap);
        }
    }

    // ------------------------------------------------------------------ 详情面板的尺寸口径
    //
    // 绘制与「能不能滚」必须用同一份几何：面板盒子的位置尺寸、每块的自然高度都只算一次口径，
    // 否则很容易出现「量出来说不用滚、画出来却缺一截」。

    /** 详情面板相对页面原点的盒子 {@code {x, y, w, h}} */
    private float[] detailBox(float w, float h, boolean landscape) {
        float gap = Math.min(w, h) * 0.024f;
        if (landscape) {
            float lw = (w - gap) * D_LAND_PCT;
            return new float[]{lw + gap, 0f, w - gap - lw, h};
        }
        float gh = (h - gap) * D_PORT_PCT;
        return new float[]{0f, gh + gap, w, h - gh - gap};
    }

    /**
     * 详情面板内部的可滚动距离（内容自然高 − 面板可视高）。
     *
     * <p>和副页的 {@code scrollRange} 是同一套口径，区别只是这里滚的是面板里的内容，
     * 日历格子不动——宜忌再多也不该把整个月历推走。
     */
    public float detailScrollRange(Paint p, float w, float h, Prefs prefs, boolean landscape) {
        if (w <= 8f || h <= 8f) {
            detailRange = 0f;
            return 0f;
        }
        syncToToday();
        font = Layout.pageFont(w, h, prefs.pageDetail, prefs.textScale());
        float[] box = detailBox(w, h, landscape);
        float pad = font * D_PAD_EM;
        float iw = box[2] - pad * 2f;
        float ih = box[3] - pad * 2f;
        Almanac a = (iw > 4f && ih > 4f && selYear >= 0) ? selectedAlmanac() : null;
        if (a == null) {
            detailRange = 0f;
            return 0f;
        }
        detailRange = Layout.scrollRange(detailInnerH(p, a, iw), ih);
        detailScroll = Layout.clampScroll(detailScroll, detailRange);
        return detailRange;
    }

    /** 手是不是按在详情面板上。{@code x,y} 是页面内容坐标系（横向滚动偏移由调用方加回） */
    public boolean inDetail(float x, float y) {
        return inRect(x, y, detailRect);
    }

    public float detailScroll() {
        return detailScroll;
    }

    public void setDetailScroll(float y) {
        detailScroll = Layout.clampScroll(y, detailRange);
    }

    /** 面板内滚动（正数往下）。返回是否真的动了 */
    public boolean scrollDetailBy(float dy) {
        float before = detailScroll;
        setDetailScroll(detailScroll + dy);
        return Math.abs(detailScroll - before) > 0.01f;
    }

    private void resetDetailScroll() {
        detailScroll = 0f;
    }

    // ------------------------------------------------------------------ 网格

    private void drawGrid(Canvas c, Paint p, Palette pal, Prefs prefs,
                          float left, float top, float w, float h) {
        TextDraw.card(c, p, pal, left, top, left + w, top + h, Math.min(w, h) * 0.035f);

        float pad = Math.min(w, h) * 0.040f;
        float ix = left + pad;
        float iy = top + pad;
        float iw = w - pad * 2f;
        float ih = h - pad * 2f;
        if (iw <= 0 || ih <= 0) {
            return;
        }

        // ---- 顶部：月份 + 翻页 + 今天 ----
        float headH = ih * 0.145f;
        int ws = prefs.weekStart == 1 ? 1 : 0;

        String title = shownYear + " 年 " + shownMonth + " 月";
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        p.setColor(pal.textPrimary);
        float ts = TextDraw.fit(p, title, iw * 0.42f, headH * 0.62f, headH * 0.62f, false);
        p.setTextSize(ts);
        TextDraw.draw(c, title, ix, iy + headH / 2f, p, 1);

        float btn = Math.min(headH * 0.66f, iw * 0.055f);
        float by = iy + (headH - btn) / 2f;
        float nx = ix + iw - btn;
        prevRect = new float[]{nx - btn * 2.5f, by, nx - btn * 1.5f, by + btn};
        nextRect = new float[]{nx, by, nx + btn, by + btn};
        drawArrow(c, p, pal, prevRect, false);
        drawArrow(c, p, pal, nextRect, true);

        boolean canToday = shownYear != todayY() || shownMonth != todayM();
        float tw = btn * 1.9f;
        todayRect = new float[]{prevRect[0] - btn * 0.9f - tw, by, prevRect[0] - btn * 0.9f, by + btn};
        if (canToday) {
            TextDraw.pill(c, p, TextDraw.softBg(pal, pal.accent), todayRect[0], todayRect[1],
                    todayRect[2], todayRect[3], btn / 2f);
            p.setColor(TextDraw.softText(pal, pal.accent));
            p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            float ss = TextDraw.fit(p, "今天", tw * 0.80f, btn * 0.56f, btn * 0.56f, false);
            p.setTextSize(ss);
            TextDraw.draw(c, "今天", (todayRect[0] + todayRect[2]) / 2f,
                    (todayRect[1] + todayRect[3]) / 2f, p, 0);
        } else {
            todayRect = new float[]{0, 0, 0, 0};
        }

        // ---- 星期表头 ----
        float weekH = ih * 0.095f;
        float gridTopY = iy + headH + weekH;
        float gh2 = iy + ih - gridTopY;
        if (gh2 <= 4) {
            return;
        }

        int diy = daysIn(shownYear, shownMonth);
        int firstDow = WeatherData.dowOf(shownYear, shownMonth, 1);
        lead = mod(firstDow - ws, 7);
        daysInMonth = diy;
        gridRows = (int) Math.ceil((lead + diy) / 7.0);
        if (gridRows <= 0) {
            gridRows = 1;
        }

        float cw = iw / 7f;
        float ch = gh2 / gridRows;

        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        p.setColor(pal.textFaint);
        float whs = TextDraw.fit(p, "三", cw * 0.62f, weekH * 0.66f, weekH * 0.66f, false);
        p.setTextSize(whs);
        for (int i = 0; i < 7; i++) {
            int dow = mod(ws + i, 7);
            boolean weekend = dow == 0 || dow == 6;
            p.setColor(weekend ? TextDraw.softText(pal, C_INAUSP) : pal.textFaint);
            TextDraw.draw(c, WEEK_CN[dow], ix + cw * (i + 0.5f), iy + headH + weekH * 0.5f, p, 0);
        }

        gridLeft = ix;
        gridTop = gridTopY;
        gridW = iw;
        gridH = gh2;

        ensureSubCache();

        int ty = todayY();
        int tm = todayM();
        int td = todayD();

        for (int row = 0; row < gridRows; row++) {
            for (int col = 0; col < 7; col++) {
                int cell = row * 7 + col;
                int day = cell - lead + 1;
                float x = ix + col * cw;
                float y = gridTopY + row * ch;
                if (day < 1 || day > diy) {
                    continue;
                }
                boolean isToday = shownYear == ty && shownMonth == tm && day == td;
                boolean isSel = shownYear == selYear && shownMonth == selMonth && day == selDay;
                drawCell(c, p, pal, x, y, cw, ch, day, cell, isToday, isSel);
            }
        }

        // 行分隔线，让整月看起来更像挂历
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f);
        p.setColor(pal.line);
        for (int row = 1; row < gridRows; row++) {
            float y = gridTopY + row * ch;
            c.drawLine(ix, y, ix + iw, y, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private void drawArrow(Canvas c, Paint p, Palette pal, float[] r, boolean right) {
        float cx = (r[0] + r[2]) / 2f;
        float cy = (r[1] + r[3]) / 2f;
        float s = (r[3] - r[1]) * 0.26f;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.4f, s * 0.85f));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(pal.textSecondary);
        float d = right ? 1f : -1f;
        c.drawLine(cx + d * s * 0.45f, cy - s, cx - d * s * 0.45f, cy, p);
        c.drawLine(cx - d * s * 0.45f, cy, cx + d * s * 0.45f, cy + s, p);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawCell(Canvas c, Paint p, Palette pal, float left, float top, float w, float h,
                          int day, int cell, boolean isToday, boolean isSel) {
        float padX = w * 0.07f;
        float padY = h * 0.08f;
        float bw = w - padX * 2f;
        float bh = h - padY * 2f;
        if (bw <= 2 || bh <= 2) {
            return;
        }
        float cx = left + w / 2f;

        if (isToday) {
            float d = Math.min(bw, bh * 0.62f);
            TextDraw.pill(c, p, pal.accent, cx - d / 2f, top + padY, cx + d / 2f, top + padY + d, d / 2f);
        } else if (isSel) {
            float d = Math.min(bw, bh * 0.62f);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.4f, d * 0.055f));
            p.setColor(pal.accent);
            c.drawRoundRect(new android.graphics.RectF(cx - d / 2f, top + padY, cx + d / 2f, top + padY + d),
                    d / 2f, d / 2f, p);
            p.setStyle(Paint.Style.FILL);
        }

        // 公历日
        String num = String.valueOf(day);
        float numH = bh * 0.54f;
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        p.setColor(isToday ? pickOnAccent(pal) : pal.textPrimary);
        float ns = TextDraw.fit(p, num, bw * 0.72f, numH, numH, false);
        p.setTextSize(ns);
        TextDraw.draw(c, num, cx, top + padY + numH * 0.52f, p, 0);

        // 农历 / 节气 / 节日
        String sub = cell < cellSub.length ? cellSub[cell] : null;
        if (sub == null || sub.length() == 0) {
            return;
        }
        boolean term = cell < cellHasTerm.length && cellHasTerm[cell];
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        if (isToday) {
            p.setColor(pickOnAccent(pal));
        } else if (term) {
            p.setColor(TextDraw.softText(pal, pal.accent));
        } else {
            p.setColor(pal.textFaint);
        }
        float sh = bh * 0.34f;
        float ss = TextDraw.fit(p, sub, bw * 0.94f, sh, sh, false);
        p.setTextSize(ss);
        TextDraw.draw(c, sub, cx, top + padY + numH + sh * 0.5f, p, 0);
    }

    /** 强调色底上的文字色：深色主题用黑，浅色主题用白（accent 在浅色下是较深的蓝） */
    private static int pickOnAccent(Palette pal) {
        return pal.dark ? 0xFF0A0F14 : 0xFFFFFFFF;
    }

    private void ensureSubCache() {
        int key = shownYear * 100 + shownMonth;
        if (cellCacheKey == key) {
            return;
        }
        cellCacheKey = key;
        for (int i = 0; i < cellSub.length; i++) {
            cellSub[i] = null;
            cellHasTerm[i] = false;
        }
        for (int day = 1; day <= daysInMonth; day++) {
            int cell = lead + day - 1;
            if (cell < 0 || cell >= cellSub.length) {
                continue;
            }
            LunarCalendar lc = LunarCalendar.fromSolar(shownYear, shownMonth, day);
            if (lc == null) {
                continue;
            }
            String s = null;
            boolean term = false;
            if (lc.term != null && lc.term.length() > 0) {
                s = lc.term;
                term = true;
            } else if (lc.festival != null && lc.festival.length() > 0) {
                s = lc.festival.length() > 3 ? lc.festival.substring(0, 3) : lc.festival;
                term = true;
            } else if (lc.day == 1) {
                s = lc.monthName;
            } else {
                s = lc.dayName;
            }
            cellSub[cell] = s;
            cellHasTerm[cell] = term;
        }
    }

    // ------------------------------------------------------------------ 选中日详情

    private void drawDetail(Canvas c, Paint p, Palette pal, float left, float top, float w, float h) {
        if (w <= 8 || h <= 8) {
            return;
        }
        TextDraw.card(c, p, pal, left, top, left + w, top + h, Math.min(w, h) * 0.045f);
        detailRect[0] = left;
        detailRect[1] = top;
        detailRect[2] = left + w;
        detailRect[3] = top + h;

        float pad = font * D_PAD_EM;
        float ix = left + pad;
        float iy = top + pad;
        float iw = w - pad * 2f;
        float ih = h - pad * 2f;
        syncToToday();
        Almanac a = (iw > 4f && ih > 4f && selYear >= 0) ? selectedAlmanac() : null;
        if (a == null) {
            detailRange = 0f;
            detailScroll = 0f;
            return;
        }

        // 内容自然总高与「实际画了什么」共用 detailBlocks，量出来的和画出来的必然一致
        float inner = detailInnerH(p, a, iw);
        detailRange = Layout.scrollRange(inner, ih);
        detailScroll = Layout.clampScroll(detailScroll, detailRange);

        float g = font * D_GAP_EM;
        float[] hs = detailBlocks(p, a, iw);
        int save = c.save();
        c.clipRect(ix, iy, ix + iw, iy + ih);
        c.translate(0f, -detailScroll);
        float y = iy;
        int n = 0;
        for (int i = 0; i < hs.length; i++) {
            if (!(hs[i] > 0f)) {
                continue;                        // 空块不画，也不白占一段间距
            }
            if (n > 0) {
                y += g;
            }
            switch (i) {
                case B_TITLE:
                    drawDetailTitle(c, p, pal, a, ix, y, iw);
                    break;
                case B_BADGE:
                    drawBadges(c, p, pal, ix, y, iw, hs[i], a);
                    break;
                case B_META:
                    drawDetailMeta(c, p, pal, a, ix, y, iw);
                    break;
                case B_YI:
                    drawYiJi(c, p, pal, ix, y, iw, hs[i], a, true);
                    break;
                default:
                    drawYiJi(c, p, pal, ix, y, iw, hs[i], a, false);
                    break;
            }
            y += hs[i];
            n++;
        }
        c.restoreToCount(save);

        drawDetailBar(c, p, pal, left, top, w, h, pad, ih, inner);
    }

    // ------------------------------------------------------------------ 详情面板的分块

    private static final int B_TITLE = 0;
    private static final int B_BADGE = 1;
    private static final int B_META = 2;
    private static final int B_YI = 3;
    private static final int B_JI = 4;

    /**
     * 面板内各块的自然高度，顺序固定：标题 / 徽标 / 冲煞 / 宜 / 忌。
     *
     * <p>绘制与「能不能滚」都只认这一份：量高与绘制走同一串函数，就不会出现
     * 「说好不用滚、画出来却缺一截」。
     */
    private float[] detailBlocks(Paint p, Almanac a, float iw) {
        return new float[]{
                detailTitleH(p, a, iw),
                badgeRowH(),
                detailMetaH(p, a, iw),
                yijiBlockH(p, a, true, iw),
                yijiBlockH(p, a, false, iw),
        };
    }

    /** 非空块的自然总高（块之间才有间距，空块不占） */
    private float detailInnerH(Paint p, Almanac a, float iw) {
        float[] hs = detailBlocks(p, a, iw);
        float g = font * D_GAP_EM;
        float total = 0f;
        int n = 0;
        for (int i = 0; i < hs.length; i++) {
            if (!(hs[i] > 0f)) {
                continue;
            }
            total += hs[i] + (n > 0 ? g : 0f);
            n++;
        }
        return total;
    }

    /** 面板右缘的细滚动条：内容装不下时给个「下面还有」的提示 */
    private void drawDetailBar(Canvas c, Paint p, Palette pal, float left, float top,
                               float w, float h, float pad, float viewH, float innerH) {
        if (!(innerH > viewH) || viewH <= 4f) {
            return;
        }
        float barW = Math.max(2f, font * 0.16f);
        float x = left + w - pad * 0.55f;
        float trackTop = top + pad;
        float trackH = Math.max(8f, h - pad * 2f);
        float thumbH = Math.max(trackH * 0.14f, trackH * (viewH / innerH));
        float frac = detailRange > 0.5f ? Layout.clamp01(detailScroll / detailRange) : 0f;
        float thumbTop = trackTop + (trackH - thumbH) * frac;
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(pal.line);
        c.drawRoundRect(new android.graphics.RectF(x, trackTop, x + barW, trackTop + trackH),
                barW / 2f, barW / 2f, p);
        p.setColor(pal.textFaint);
        c.drawRoundRect(new android.graphics.RectF(x, thumbTop, x + barW, thumbTop + thumbH),
                barW / 2f, barW / 2f, p);
    }

    // ------------------------------------------------------------------ 详情面板：标题 / 冲煞

    private String detailLine1() {
        return String.format(Locale.US, "%d年%d月%d日 星期%s",
                selYear, selMonth, selDay, WEEK_FULL[WeatherData.dowOf(selYear, selMonth, selDay)]);
    }

    private static String detailLine2(Almanac a) {
        StringBuilder l2 = new StringBuilder();
        if (a.lunar != null) {
            l2.append("农历").append(a.lunar.monthName).append(a.lunar.dayName);
        }
        append(l2, a.yearGanZhi + "年 " + a.monthGanZhi + "月 " + a.dayGanZhi + "日");
        append(l2, a.naYin);
        return l2.toString();
    }

    private float detailTitleH(Paint p, Almanac a, float iw) {
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        float h1 = TextDraw.wrappedHeight(p, detailLine1(), iw,
                Type.size(font, Type.TITLE), D_TITLE_GAP);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        float h2 = TextDraw.wrappedHeight(p, detailLine2(a), iw,
                Type.size(font, Type.NOTE), D_NOTE_GAP);
        return h1 + h2;
    }

    private void drawDetailTitle(Canvas c, Paint p, Palette pal, Almanac a,
                                 float left, float top, float iw) {
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        float h1 = TextDraw.drawWrappedFixed(c, p, detailLine1(), left, top, iw,
                Type.size(font, Type.TITLE), pal.textPrimary, 1, D_TITLE_GAP);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        TextDraw.drawWrappedFixed(c, p, detailLine2(a), left, top + h1, iw,
                Type.size(font, Type.NOTE), pal.textSecondary, 1, D_NOTE_GAP);
    }

    private static String detailMeta(Almanac a) {
        StringBuilder meta = new StringBuilder("冲" + a.chong + " 煞" + a.sha);
        if (a.taiShen != null && a.taiShen.length() > 0) {
            meta.append("　胎神 ").append(a.taiShen);
        }
        return meta.toString();
    }

    private float detailMetaH(Paint p, Almanac a, float iw) {
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        return TextDraw.wrappedHeight(p, detailMeta(a), iw, Type.size(font, Type.NOTE), D_NOTE_GAP);
    }

    private void drawDetailMeta(Canvas c, Paint p, Palette pal, Almanac a,
                                float left, float top, float iw) {
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        TextDraw.drawWrappedFixed(c, p, detailMeta(a), left, top, iw,
                Type.size(font, Type.NOTE), pal.textFaint, 1, D_NOTE_GAP);
    }

    /** 徽标行的固定高度：只认字号，不认面板有多高 */
    private float badgeRowH() {
        return Type.line(font, Type.LABEL) * Cards.PILL_PAD;
    }

    private void drawBadges(Canvas c, Paint p, Palette pal, float left, float top, float w, float h,
                            Almanac a) {
        if (h <= 2 || w <= 4) {
            return;
        }
        String[] texts = {
                a.jianChuDay(),
                a.zhiShen + (a.huangDao ? "·黄道" : "·黑道"),
                a.xiuFull + (a.xiuLuck > 0 ? "·吉" : "·凶"),
        };
        int[] colors = {
                pal.accent,
                a.huangDao ? C_HUANG : C_HEI,
                a.xiuLuck > 0 ? C_AUSP : C_INAUSP,
        };
        float gap = w * 0.02f;
        float cw = (w - gap * 2f) / 3f;
        float size = Type.size(font, Type.LABEL);
        for (int i = 0; i < 3; i++) {
            float x = left + i * (cw + gap);
            TextDraw.pill(c, p, TextDraw.softBg(pal, colors[i]), x, top, x + cw, top + h, h * 0.30f);
            p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            // 徽标文字也用固定角色字号，窄了就省略号——以前是 fit 到胶囊高度，胶囊矮字就小
            TextDraw.drawLine(c, p, texts[i], x + cw / 2f, top + h / 2f, cw * 0.88f, size,
                    TextDraw.softText(pal, colors[i]), 0);
        }
    }

    /** 宜 / 忌 词条拼成一段。空就是空，不放占位符 */
    private static String yijiBody(Almanac a, boolean yi) {
        if (yi && a.daXiong) {
            return "四离四绝、杨公忌日，传统上忌一切大事";
        }
        List<String> items = yi ? a.yi : a.ji;
        if (items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    /**
     * 一块宜/忌的自然高度：小卡内边距 ×2 + 「宜」字胶囊行 + 词条正文。
     *
     * <p>词条正文按 <b>正文角色</b>的固定字号量行数——词条多就是行数多、这块变高、
     * 面板变长、可以往下滚，而不是把字压小塞进去。
     */
    private float yijiBlockH(Paint p, Almanac a, boolean yi, float iw) {
        String body = yijiBody(a, yi);
        float boxPad = font * D_BOX_PAD_EM;
        float inner = iw - boxPad * 2f;
        if (body.length() == 0 || inner <= 4f) {
            return 0f;
        }
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        float bodyH = TextDraw.wrappedHeight(p, body, inner,
                Type.size(font, Type.BODY), D_BODY_GAP);
        return boxPad * 2f + Cards.yijiBadgeH(font) + font * 0.45f + bodyH;
    }

    private void drawYiJi(Canvas c, Paint p, Palette pal, float left, float top,
                          float w, float h, Almanac a, boolean yi) {
        String body = yijiBody(a, yi);
        if (h <= 4 || w <= 4 || body.length() == 0) {
            return;
        }
        int color = yi ? C_YI : C_JI;
        String label = yi ? "宜" : "忌";
        boolean verdict = yi && a.daXiong;

        float radius = font * 0.55f;
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(TextDraw.blend(color, pal.panel, pal.dark ? 0.90f : 0.94f));
        c.drawRoundRect(new android.graphics.RectF(left, top, left + w, top + h), radius, radius, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, font * 0.05f));
        p.setColor(TextDraw.blend(color, pal.panelStroke, pal.dark ? 0.55f : 0.42f));
        c.drawRoundRect(new android.graphics.RectF(left, top, left + w, top + h), radius, radius, p);
        p.setStyle(Paint.Style.FILL);

        float boxPad = font * D_BOX_PAD_EM;
        float ix = left + boxPad;
        float iy = top + boxPad;
        float iw = Math.max(1f, w - boxPad * 2f);

        // 「宜 / 忌」是这一块的视觉锚，给卡片标题级
        float badge = Cards.yijiBadgeH(font);
        TextDraw.pill(c, p, color, ix, iy, ix + badge, iy + badge, badge * 0.26f);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        TextDraw.drawLine(c, p, label, ix + badge / 2f, iy + badge / 2f, badge * 0.80f,
                Type.size(font, Type.HEAD), 0xFFFFFFFF, 0);

        String head = verdict ? "诸事不宜" : label + " · " + (yi ? a.yi.size() : a.ji.size()) + " 项";
        float headW = Math.max(1f, iw - badge - font * 0.60f);
        TextDraw.drawLine(c, p, head, ix + badge + font * 0.60f, iy + badge / 2f, headW,
                Type.size(font, Type.LABEL), verdict ? C_JI : pal.textSecondary, 1);

        float bodyTop = iy + badge + font * 0.45f;
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        TextDraw.drawWrappedFixed(c, p, body, ix, bodyTop, iw,
                Type.size(font, Type.BODY),
                verdict ? TextDraw.softText(pal, C_JI) : pal.textPrimary, 1, D_BODY_GAP);
    }

    private Almanac selectedAlmanac() {
        int key = selYear * 10000 + selMonth * 100 + selDay;
        if (selAlmanac == null || selAlmanacKey != key) {
            selAlmanac = Almanac.of(selYear, selMonth, selDay);
            selAlmanacKey = key;
        }
        return selAlmanac;
    }

    // ------------------------------------------------------------------ 命中测试

    /**
     * @param x,y 页面内容坐标系下的点（调用方需把屏幕坐标加上滚动偏移）
     * @return true 表示状态发生变化，需要重绘
     */
    public boolean onTap(float x, float y) {
        syncToToday();
        // 换了一天就是换了一份内容，面板滚回顶部——否则会停在新内容的中段
        if (inRect(x, y, prevRect)) {
            shiftMonth(-1);
            resetDetailScroll();
            return true;
        }
        if (inRect(x, y, nextRect)) {
            shiftMonth(1);
            resetDetailScroll();
            return true;
        }
        if (inRect(x, y, todayRect)) {
            Calendar c = Calendar.getInstance();
            shownYear = c.get(Calendar.YEAR);
            shownMonth = c.get(Calendar.MONTH) + 1;
            selYear = shownYear;
            selMonth = shownMonth;
            selDay = c.get(Calendar.DAY_OF_MONTH);
            resetDetailScroll();
            return true;
        }
        if (gridW > 0 && gridH > 0
                && x >= gridLeft && x < gridLeft + gridW
                && y >= gridTop && y < gridTop + gridH) {
            int col = (int) ((x - gridLeft) / (gridW / 7f));
            int row = (int) ((y - gridTop) / (gridH / gridRows));
            col = Math.max(0, Math.min(6, col));
            row = Math.max(0, Math.min(gridRows - 1, row));
            int day = row * 7 + col - lead + 1;
            if (day >= 1 && day <= daysInMonth) {
                boolean changed = selYear != shownYear || selMonth != shownMonth || selDay != day;
                selYear = shownYear;
                selMonth = shownMonth;
                selDay = day;
                if (changed) {
                    resetDetailScroll();
                }
                return true;
            }
        }
        return false;
    }

    private void shiftMonth(int delta) {
        int m = shownMonth + delta;
        int y = shownYear;
        while (m < 1) {
            m += 12;
            y--;
        }
        while (m > 12) {
            m -= 12;
            y++;
        }
        if (y < 1900 || y > 2099) {
            return;
        }
        shownYear = y;
        shownMonth = m;
    }

    private static boolean inRect(float x, float y, float[] r) {
        return r != null && r[2] > r[0] && x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3];
    }

    // ------------------------------------------------------------------ 工具

    private static void append(StringBuilder sb, String s) {
        if (s == null || s.length() == 0) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(" · ");
        }
        sb.append(s);
    }

    private static int daysIn(int y, int m) {
        switch (m) {
            case 1: case 3: case 5: case 7: case 8: case 10: case 12:
                return 31;
            case 4: case 6: case 9: case 11:
                return 30;
            default:
                return isLeap(y) ? 29 : 28;
        }
    }

    private static boolean isLeap(int y) {
        return (y % 4 == 0 && y % 100 != 0) || y % 400 == 0;
    }

    private static int mod(int a, int b) {
        return Layout.mod(a, b);
    }
}
