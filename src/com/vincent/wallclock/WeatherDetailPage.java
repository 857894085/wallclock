package com.vincent.wallclock;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;

import com.vincent.wallclock.gen.Cards;
import com.vincent.wallclock.gen.Layout;
import com.vincent.wallclock.gen.Type;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 天气详情页：实况概况 → 十项指标 → 24 小时温度曲线 → 七天预报 → 空气质量 → 生活指数。
 *
 * <p>和黄历页同一套模型：<b>字号由 {@link Layout#pageFont} 定死</b>，卡片按自然高度摆放，
 * 装不下就整页上下滑。上一版是「每张卡分到一屏高度的一份，再把文字 fit 进这个高度」，
 * 于是卡片越多字越小；现在内容多只会让页面变长，不会让字变小。
 *
 * <p>卡片高度基本是字号的固定倍数（指标 10 格、七天 7 行、空气 6 项这些都是定数），
 * 只有网格列数会随卡宽变，所以用 {@link Cards#metricGrid} 等纯算术函数算，
 * 测量与绘制共用同一个函数，不会对不上。
 *
 * <p>顶部「城市 · 更新时间」吸顶，底部数据来源页脚固定，中间内容区滚动。
 *
 * <p>全部自绘。温度曲线是自己连点画的（不引任何图表库，旧机器上也不掉帧），
 * 七天预报的温度条按各日区间画在一条公共温标上，横向一眼能比出冷暖。
 */
public final class WeatherDetailPage {

    private static final String[] WEEK_FULL = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};

    /** 六项污染物的国标二级限值（µg/m³），用于把浓度条归一化 */
    private static final double[] POLL_LIMIT = {75, 150, 160, 80, 150, 4000};
    private static final String[] POLL_NAME = {"PM2.5", "PM10", "O₃", "NO₂", "SO₂", "CO"};

    private static final String FOOTNOTE = "数据来自 Open-Meteo；生活指数为本地推算，仅供参考";

    //
    // 和黄历页同一条规矩：吸顶标题与页脚的高度由**字号**定，不用屏幕百分比。
    // 字号是「屏幕短边的固定比例」，百分比只跟页高走，横屏时两者会失配 →
    // 分到的高度塞不下固定字号，而 fitLine 又不能突破可读下限 → 文字压到邻居身上。
    //
    /** 横屏左列宽度占比 */
    private static final float LAND_LEFT_PCT = 0.500f;

    /** 当前这一屏的固定正文字号 */
    private float font = Layout.MIN_FONT;

    // ---- 版式缓存 ----
    private int[] cardsL = new int[0];
    private int[] cardsR = new int[0];
    private float[] hsL = new float[0];
    private float[] hsR = new float[0];
    private float cacheContentH = 0f;
    private float planW = -1f;
    private float planH = -1f;
    private int planSig = 0;

    // ------------------------------------------------------------------ 版式

    private static int sigOf(Prefs prefs, boolean landscape, boolean valid) {
        int s = prefs.elderMode ? 1 : 0;
        s = s * 3 + (landscape ? 1 : 0);
        s = s * 3 + (valid ? 1 : 0);
        s = s * 7 + prefs.pageDetail;
        return s;
    }

    private float headH() {
        return Type.line(font, Type.NOTE) * Cards.W_HEAD_PAD;
    }

    private float footH() {
        return Type.line(font, Type.FOOT) * Cards.W_FOOT_PAD;
    }

    private float gap() {
        return font * Cards.W_GAP_EM;
    }

    private float pad() {
        return font * Cards.PAD_W;
    }

    private static float sum(float[] hs, float gap) {
        float t = 0f;
        for (int i = 0; i < hs.length; i++) {
            t += hs[i] + (i > 0 ? gap : 0f);
        }
        return t;
    }

    private void measure(Paint p, float w, float h, Prefs prefs, boolean landscape, WeatherData wx) {
        boolean valid = wx != null && wx.valid;
        int sig = sigOf(prefs, landscape, valid);
        if (planW == w && planH == h && planSig == sig) {
            return;
        }
        font = Layout.pageFont(w, h, prefs.pageDetail, prefs.textScale());
        if (!valid) {
            cardsL = new int[0];
            cardsR = new int[0];
            hsL = new float[0];
            hsR = new float[0];
            cacheContentH = 0f;
        } else if (landscape) {
            float gap = gap();
            float lw = (w - gap) * LAND_LEFT_PCT;
            float rw = w - gap - lw;
            int[] ml = Cards.members(Cards.W_LAND_L, prefs.pageDetail);
            int[] mr = Cards.members(Cards.W_LAND_R, prefs.pageDetail);
            cardsL = ml;
            cardsR = mr;
            hsL = heights(p, ml, lw, wx);
            hsR = heights(p, mr, rw, wx);
            cacheContentH = Math.max(sum(hsL, gap), sum(hsR, gap));
        } else {
            int[] m = Cards.members(Cards.W_PORTRAIT, prefs.pageDetail);
            cardsL = m;
            cardsR = new int[0];
            hsL = heights(p, m, w, wx);
            hsR = new float[0];
            cacheContentH = sum(hsL, gap());
        }
        planW = w;
        planH = h;
        planSig = sig;
    }

    /** 内容区可视高度（去掉吸顶标题与页脚） */
    private float viewH(float h) {
        float v = h - headH() - footH() - gap() * 2f;
        return v > 0f ? v : 0f;
    }

    /**
     * 本页的可滚动距离：内容自然总高减去内容区可视高度，比视口矮时返回 0。
     */
    public float scrollRange(Paint p, float w, float h, Prefs prefs,
                             WeatherData wx, boolean landscape) {
        if (w <= 8f || h <= 8f) {
            return 0f;
        }
        measure(p, w, h, prefs, landscape, wx);
        return Layout.scrollRange(cacheContentH, viewH(h));
    }

    /** 上一次测量后的内容自然总高，供滚动条算滑块长度（配合 {@link #scrollRange} 用） */
    public float lastContentH() {
        return cacheContentH;
    }

    /**
     * 分页锚点：每张卡顶部相对内容起点的偏移（升序），末尾补上内容总高。
     * 供「一页一页翻」用，页顶对齐到这些位置就不会把卡片切成两半。
     */
    public float[] anchors(Paint p, float w, float h, Prefs prefs,
                           WeatherData wx, boolean landscape) {
        if (w <= 8f || h <= 8f) {
            return new float[0];
        }
        measure(p, w, h, prefs, landscape, wx);
        float g = gap();
        float[] a = Layout.cardTops(hsL, g);
        float[] b = (landscape && hsR.length > 0) ? Layout.cardTops(hsR, g) : null;
        return Layout.mergeAnchors(a, b, cacheContentH);
    }

    /**
     * 每张卡的自然高度 = {@link Cards#chromeH}（固定部分：内边距、标题、网格）
     * + 内容驱动的那部分（生活指数的建议文字换行）。
     *
     * <p>固定部分全部由角色表算，和 {@link Cards#textRows} 声明的文字行一一对应，
     * 所以「把温度提到 HERO」这件事必然同时把卡撑高，不会出现字号被容器压回去的情况。
     */
    private float[] heights(Paint p, int[] members, float w, WeatherData wx) {
        float[] out = new float[members.length];
        for (int i = 0; i < members.length; i++) {
            out[i] = heightOf(p, members[i], w, wx);
        }
        return out;
    }

    private float heightOf(Paint p, int id, float w, WeatherData wx) {
        float iw = Math.max(1f, w - pad() * 2f);
        switch (id) {
            case Cards.W_METRIC:
                return Cards.chromeH(id, font, Cards.PAD_W, Cards.metricGrid(iw, font)[1]);
            case Cards.W_DAY:
                return Cards.chromeH(id, font, Cards.PAD_W, Math.max(1, dayRows(wx)));
            case Cards.W_LIFE:
                return Cards.chromeH(id, font, Cards.PAD_W, Cards.lifeGrid(iw, font)[1])
                        + lifeOverflowH(p, iw, wx);
            default:
                return Cards.chromeH(id, font, Cards.PAD_W, 1);
        }
    }

    /**
     * 实际能画的预报天数。
     *
     * <p>缺哪天的数据就少一行，卡片高度也跟着少一行——量高与绘制共用这一个数，
     * 所以不会出现「报了七行只画三行、底部留一大块空白」。
     */
    private static int dayRows(WeatherData wx) {
        if (wx == null || !wx.valid) {
            return 0;
        }
        int avail = 0;
        int n = Math.min(Cards.DAY_ROWS, WeatherData.DETAIL_DAYS);
        for (int i = 0; i < n; i++) {
            if (wx.dHas[i]) {
                avail++;
            }
        }
        return Math.min(avail, Cards.DAY_ROWS);
    }

    /**
     * 生活指数的建议文字在注释级字号下换行超出一行时，多出来的高度。
     *
     * <p>取「最坏的那一格」，因为网格里所有格子必须一样高。
     * 少了这一项，建议一长就会画出卡片外面。
     */
    private float lifeOverflowH(Paint p, float iw, WeatherData wx) {
        if (wx == null || !wx.valid) {
            return 0f;
        }
        List<LifeIndex> list = LifeIndex.of(wx);
        if (list.isEmpty()) {
            return 0f;
        }
        int[] g = Cards.lifeGrid(iw, font);
        float cellW = iw / g[0];
        float cw = Math.max(1f, cellW - cellW * 0.08f * 2f);
        float line = Type.line(font, Type.NOTE);
        float worst = 0f;
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        for (LifeIndex li : list) {
            if (li.advice == null || li.advice.length() == 0) {
                continue;
            }
            float wh = TextDraw.wrappedHeight(p, li.advice, cw,
                    Type.size(font, Type.NOTE), 0.10f);
            if (wh > worst) {
                worst = wh;
            }
        }
        return worst > line ? worst - line : 0f;
    }

    // ------------------------------------------------------------------ 入口

    public void drawScreen(Canvas c, Paint p, Palette pal, Prefs prefs, WeatherData wx,
                           float left, float top, float w, float h, boolean landscape,
                           String cityLabel, String emptyNote, float scrollY) {
        if (w <= 8f || h <= 8f) {
            return;
        }
        boolean valid = wx != null && wx.valid;
        measure(p, w, h, prefs, landscape, wx);

        float hh = headH();
        float fh = footH();
        float g = gap();

        drawHeader(c, p, pal, left, top, w, hh, cityLabel, wx);
        drawFootnote(c, p, pal, left, top + h - fh, w, fh);

        float bodyTop = top + hh + g;
        float bodyH = h - hh - fh - g * 2f;
        if (bodyH <= 4f) {
            return;
        }
        int save = c.save();
        c.clipRect(left, bodyTop, left + w, bodyTop + bodyH);
        c.translate(0f, -scrollY);

        if (!valid) {
            String note = (emptyNote == null || emptyNote.length() == 0)
                    ? "天气数据加载中…" : emptyNote;
            drawEmpty(c, p, pal, left, bodyTop, w, bodyH, note);
        } else if (landscape) {
            float lw = (w - g) * LAND_LEFT_PCT;
            float rw = w - g - lw;
            drawColumn(c, p, pal, left, bodyTop, lw, cardsL, hsL, g, wx);
            drawColumn(c, p, pal, left + lw + g, bodyTop, rw, cardsR, hsR, g, wx);
        } else {
            drawColumn(c, p, pal, left, bodyTop, w, cardsL, hsL, g, wx);
        }
        c.restoreToCount(save);
    }

    private void drawColumn(Canvas c, Paint p, Palette pal, float left, float top,
                            float w, int[] cards, float[] hs, float gap, WeatherData wx) {
        float y = top;
        for (int i = 0; i < cards.length && i < hs.length; i++) {
            drawCard(c, p, pal, cards[i], left, y, w, hs[i], wx);
            y += hs[i] + gap;
        }
    }

    /** 卡片分发：只有这一处知道「哪张卡长什么样」 */
    private void drawCard(Canvas c, Paint p, Palette pal, int id,
                          float left, float top, float w, float h, WeatherData wx) {
        if (h <= 4f || w <= 4f) {
            return;
        }
        switch (id) {
            case Cards.W_NOW:
                drawSummary(c, p, pal, left, top, w, h, wx);
                break;
            case Cards.W_METRIC:
                drawMetrics(c, p, pal, left, top, w, h, wx);
                break;
            case Cards.W_HOUR:
                drawHourly(c, p, pal, left, top, w, h, wx);
                break;
            case Cards.W_DAY:
                drawDaily(c, p, pal, left, top, w, h, wx);
                break;
            case Cards.W_AIR:
                drawAir(c, p, pal, left, top, w, h, wx);
                break;
            case Cards.W_LIFE:
                drawLife(c, p, pal, left, top, w, h, wx);
                break;
            default:
                break;
        }
    }

    private void drawEmpty(Canvas c, Paint p, Palette pal, float left, float top,
                           float w, float h, String note) {
        TextDraw.card(c, p, pal, left, top, left + w, top + h, font * 0.60f);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        float size = Type.size(font, Type.BODY);
        TextDraw.drawWrappedFixed(c, p, note, left + w * 0.08f,
                top + h * 0.5f - Type.line(font, Type.BODY) * 0.5f,
                w * 0.84f, size, pal.textFaint, 0, 0.20f);
    }

    // ------------------------------------------------------------------ 吸顶标题

    private void drawHeader(Canvas c, Paint p, Palette pal, float left, float top, float w, float h,
                            String cityLabel, WeatherData wx) {
        if (h <= 4f) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (cityLabel != null && cityLabel.length() > 0) {
            sb.append(cityLabel);
        }
        if (wx != null && wx.valid && wx.updateTime > 0) {
            if (sb.length() > 0) {
                sb.append("　·　");
            }
            Calendar u = Calendar.getInstance();
            u.setTimeInMillis(wx.updateTime);
            sb.append("更新 ").append(String.format(Locale.US, "%02d:%02d",
                    u.get(Calendar.HOUR_OF_DAY), u.get(Calendar.MINUTE)));
        }
        if (sb.length() == 0) {
            return;
        }
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        // 「哪个城市 · 什么时候更新的」是辅助信息，给注释级，不跟下面的数据抢注意力
        TextDraw.drawLine(c, p, sb.toString(), left, top + h / 2f, w,
                Type.size(font, Type.NOTE), pal.textFaint, 1);
    }

    // ------------------------------------------------------------------ 实况概况

    private void drawSummary(Canvas c, Paint p, Palette pal, float left, float top,
                             float w, float h, WeatherData wx) {
        if (h <= 4 || w <= 4) {
            return;
        }
        TextDraw.card(c, p, pal, left, top, left + w, top + h, font * 0.70f);

        float pad = pad();
        float ix = left + pad;
        float iy = top + pad;
        float iw = Math.max(1f, w - pad * 2f);
        float ih = Math.max(1f, h - pad * 2f);

        float iconS = Math.min(ih * 0.92f, iw * 0.34f);
        WeatherArt.draw(c, Wmo.icon(wx.weatherCode, wx.isDay),
                ix + iconS / 2f, iy + ih / 2f, iconS, pal, wx.isDay);

        float tx = ix + iconS + pad * 0.7f;
        float tw = iw - iconS - pad * 0.7f;
        if (tw <= iw * 0.25f) {
            tw = iw;
            tx = ix;
        }

        // 三行各有各的角色，行高直接取角色行高，合计恰好等于 ih（由 Cards.chromeH 保证）。
        // 上一版是按 ih 的固定比例摆中心（0.28 / 0.60 / 0.85）+ 各自一个系数，
        // 中心和行高是两套独立数字：温度一高就压到「天气状况」身上。
        //
        // 另一处层级问题：温度只给到正文的 1.35 倍，「26°」和下面的字差不了多少，
        // 整张卡看不出主角是谁。现在温度是 HERO（正文的 1.58 倍）。
        float y = iy;

        float lh = Type.line(font, Type.HERO);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        TextDraw.drawLine(c, p, wx.formatTemp(wx.temp), tx, y + lh * 0.5f, tw,
                Type.size(font, Type.HERO), pal.textPrimary, 1);
        y += lh;

        lh = Type.line(font, Type.HEAD);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        TextDraw.drawLine(c, p, Wmo.text(wx.weatherCode), tx, y + lh * 0.5f, tw,
                Type.size(font, Type.HEAD), pal.textSecondary, 1);
        y += lh;

        StringBuilder sub = new StringBuilder();
        if (!Double.isNaN(wx.todayMax) && !Double.isNaN(wx.todayMin)) {
            sub.append("今日 ").append(wx.formatRange(wx.todayMin, wx.todayMax));
        }
        if (wx.sunrise.length() >= 5 && wx.sunset.length() >= 5) {
            if (sub.length() > 0) {
                sub.append("　");
            }
            sub.append("日出 ").append(wx.sunrise).append(" · 日落 ").append(wx.sunset);
        }
        if (sub.length() > 0) {
            lh = Type.line(font, Type.NOTE);
            p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
            TextDraw.drawLine(c, p, sub.toString(), tx, y + lh * 0.5f, tw,
                    Type.size(font, Type.NOTE), pal.textFaint, 1);
        }
    }

    // ------------------------------------------------------------------ 十项指标

    private void drawMetrics(Canvas c, Paint p, Palette pal, float left, float top,
                             float w, float h, WeatherData wx) {
        if (h <= 4 || w <= 4) {
            return;
        }
        TextDraw.card(c, p, pal, left, top, left + w, top + h, font * 0.55f);

        float pad = pad();
        float ix = left + pad;
        float iy = top + pad;
        float iw = Math.max(1f, w - pad * 2f);
        float ih = Math.max(1f, h - pad * 2f);

        String[] labels = {"体感", "湿度", "风", "阵风", "气压", "能见度", "云量", "露点", "降水", "紫外线"};
        String[] values = {
                wx.formatTemp(wx.feelsLike),
                wx.humidityText(),
                wx.windText(),
                wx.gustText(),
                wx.pressureText(),
                wx.visibilityText(),
                wx.cloudText(),
                wx.dewPointText(),
                wx.precipText(),
                wx.uvText(),
        };
        int[] colors = new int[labels.length];
        for (int i = 0; i < colors.length; i++) {
            colors[i] = pal.textPrimary;
        }
        colors[labels.length - 1] = wx.uvIndex < 0 ? pal.textPrimary : WeatherData.uvColor(wx.uvIndex);

        int n = labels.length;
        // 与 heightOf 同一口径：列数由 Cards.metricGrid 定，量高与绘制必然一致
        int[] g = Cards.metricGrid(iw, font);
        int cols = g[0];
        int rows = g[1];
        float cw = iw / cols;
        float ch = ih / rows;
        for (int i = 0; i < n; i++) {
            drawMetricCell(c, p, pal, ix + (i % cols) * cw, iy + (i / cols) * ch,
                    cw, ch, labels[i], values[i], colors[i]);
        }
    }

    private void drawMetricCell(Canvas c, Paint p, Palette pal, float left, float top,
                                float w, float h, String label, String value, int valueColor) {
        float padX = w * 0.06f;
        float cw = w - padX * 2f;
        if (cw <= 2 || h <= 2) {
            return;
        }
        // 两行按角色行高摆放，多出来的高度留在格子底部当呼吸。
        // 上一版把格子高度切成 0.36 / 0.54 两块，格子一矮就压字——同样的病。
        float lh = Type.line(font, Type.LABEL);
        float vh = Type.line(font, Type.BODY);
        float y0 = top + Math.max(0f, (h - lh - vh) * 0.5f);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        TextDraw.drawLine(c, p, label, left + w / 2f, y0 + lh * 0.5f, cw,
                Type.size(font, Type.LABEL), pal.textFaint, 0);

        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        TextDraw.drawLine(c, p, value, left + w / 2f, y0 + lh + vh * 0.5f, cw,
                Type.size(font, Type.BODY), valueColor, 0);
    }

    // ------------------------------------------------------------------ 24 小时曲线

    private void drawHourly(Canvas c, Paint p, Palette pal, float left, float top,
                            float w, float h, WeatherData wx) {
        if (h <= 4 || w <= 4) {
            return;
        }
        TextDraw.card(c, p, pal, left, top, left + w, top + h, font * 0.55f);

        float pad = pad();
        float ix = left + pad;
        float iy = top + pad;
        float iw = Math.max(1f, w - pad * 2f);
        float ih = Math.max(1f, h - pad * 2f);
        float titleH = Type.line(font, Type.HEAD);

        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (int i = 0; i < wx.hCount; i++) {
            double t = wx.hTemp[i];
            if (Double.isNaN(t)) {
                continue;
            }
            lo = Math.min(lo, t);
            hi = Math.max(hi, t);
        }
        boolean hasCurve = wx.hCount > 1 && lo <= hi;

        String title = "24 小时";
        if (hasCurve) {
            title += "　最高 " + wx.formatTempPlain(hi) + "　最低 " + wx.formatTempPlain(lo);
        }
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        TextDraw.drawLine(c, p, title, ix, iy + titleH / 2f, iw,
                Type.size(font, Type.HEAD), pal.accent, 1);

        float bodyTop = iy + titleH;
        float bodyH = ih - titleH;
        if (bodyH <= 4) {
            return;
        }
        if (!hasCurve) {
            p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
            TextDraw.drawWrappedFixed(c, p, "暂无逐小时数据", ix, bodyTop + bodyH * 0.35f, iw,
                    Type.size(font, Type.BODY), pal.textFaint, 0, 0.20f);
            return;
        }

        // 上面画曲线，下面放三排刻度（时间 / 天气图标 / 降水概率）。
        // 分区高度直接取 Cards 的口径，不在这里按 bodyH 的比例二次切——
        // 比例切出来的每排高度和文字角色行高是两套数字，迟早对不上。
        float chartH = Cards.hourChartH(font);
        float axisH = Cards.hourAxisH(font);
        float labelH = Type.line(font, Type.NOTE);
        float probH = Type.line(font, Type.NOTE);
        float iconH = axisH - labelH - probH;

        float range = (float) Math.max(1.0, hi - lo);
        float topPad = chartH * 0.16f;
        float botPad = chartH * 0.12f;
        float usableH = chartH - topPad - botPad;

        final int n = wx.hCount;
        float stepX = iw / n;
        float[] px = new float[n];
        float[] py = new float[n];
        Path line = new Path();
        for (int i = 0; i < n; i++) {
            px[i] = ix + stepX * (i + 0.5f);
            double t = wx.hTemp[i];
            float frac = Double.isNaN(t) ? 0.5f : (float) ((hi - t) / range);
            py[i] = bodyTop + topPad + usableH * frac;
        }
        line.moveTo(px[0], py[0]);
        for (int i = 1; i < n; i++) {
            line.lineTo(px[i], py[i]);
        }

        // 曲线下方浅色填充，走势更醒目
        Path fill = new Path(line);
        fill.lineTo(px[n - 1], bodyTop + chartH);
        fill.lineTo(px[0], bodyTop + chartH);
        fill.close();
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, bodyTop, 0, bodyTop + chartH,
                withAlpha(pal.accent, 0x4A), withAlpha(pal.accent, 0x00), Shader.TileMode.CLAMP));
        c.drawPath(fill, p);
        p.setShader(null);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(Math.max(1.4f, chartH * 0.030f));
        p.setColor(pal.accent);
        c.drawPath(line, p);
        p.setStyle(Paint.Style.FILL);

        // 刻度间隔：太密就每 6 / 9 / 12 小时一格
        float minCell = font * 2.6f;
        int step = 3;
        while (step < 12 && iw / (n / (float) step) < minCell) {
            step += 3;
        }
        int cells = Math.max(1, n / step);
        float cellW = iw / cells;

        for (int i = 0; i < n; i += step) {
            c.drawCircle(px[i], py[i], Math.max(1.3f, chartH * 0.025f), p);
        }

        for (int k = 0; k < cells; k++) {
            int i = k * step;
            if (i >= n) {
                break;
            }
            float cx = ix + cellW * (k + 0.5f);
            float cwBox = cellW * 0.94f;

            p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
            TextDraw.drawLine(c, p, wx.hTime[i], cx, bodyTop + chartH + labelH * 0.5f, cwBox,
                    Type.size(font, Type.NOTE), pal.textFaint, 0);

            float iconS = Math.min(iconH * 0.92f, cwBox * 0.80f);
            WeatherArt.draw(c, Wmo.icon(wx.hCode[i], wx.hDay[i]),
                    cx, bodyTop + chartH + labelH + iconH * 0.5f, iconS, pal, wx.hDay[i]);

            int prob = wx.hPrecipProb[i];
            if (prob > 0) {
                TextDraw.drawLine(c, p, prob + "%", cx,
                        bodyTop + chartH + labelH + iconH + probH * 0.5f, cwBox,
                        Type.size(font, Type.NOTE), TextDraw.softText(pal, pal.rain), 0);
            }
        }
    }

    // ------------------------------------------------------------------ 七天预报

    private void drawDaily(Canvas c, Paint p, Palette pal, float left, float top,
                           float w, float h, WeatherData wx) {
        if (h <= 4 || w <= 4) {
            return;
        }
        TextDraw.card(c, p, pal, left, top, left + w, top + h, font * 0.55f);

        float pad = pad();
        float ix = left + pad;
        float iy = top + pad;
        float iw = Math.max(1f, w - pad * 2f);
        float ih = Math.max(1f, h - pad * 2f);
        float titleH = Type.line(font, Type.HEAD);
        float bodyH = ih - titleH;
        if (bodyH <= 4) {
            return;
        }

        // 有多少天画多少天：卡片高度在 heightOf 里就是按同一个 show 算的，
        // 量高与绘制同源，不会出现「报了七行只画三行 → 底部一大块空白」。
        int show = dayRows(wx);
        if (show <= 0) {
            return;
        }

        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        String title = show >= WeatherData.DETAIL_DAYS ? "七天预报" : ("未来 " + show + " 天");
        TextDraw.drawLine(c, p, title, ix, iy + titleH / 2f, iw,
                Type.size(font, Type.HEAD), pal.accent, 1);

        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (int i = 0; i < show; i++) {
            if (!Double.isNaN(wx.dMin[i])) {
                lo = Math.min(lo, wx.dMin[i]);
            }
            if (!Double.isNaN(wx.dMax[i])) {
                hi = Math.max(hi, wx.dMax[i]);
            }
        }
        if (lo > hi) {
            lo = 0;
            hi = 1;
        }
        float range = (float) Math.max(1.0, hi - lo);
        float rowH = bodyH / show;

        float numW = iw * 0.085f;
        float wDate = iw * 0.155f;
        float wIcon = iw * 0.10f;
        float wDesc = iw * 0.20f;
        float wProb = iw * 0.10f;
        float wBar = iw - wDate - wIcon - wDesc - wProb - numW * 2f;
        if (wBar < iw * 0.12f) {
            wBar = Math.max(4f, iw * 0.12f);
        }

        for (int i = 0; i < show; i++) {
            float rowTop = iy + titleH + rowH * i;
            float cy = rowTop + rowH / 2f;

            // 一行里三档：日期是标签级、天气描述是注释级、温度是正文级——
            // 上一版三行分别 0.70 / 0.62 / 0.68，几乎一样大，看过去分不出重点。
            String label = i == 0 ? "今天" : (i == 1 ? "明天" : WEEK_FULL[wx.dDow[i]]);
            p.setTypeface(Typeface.create(Typeface.SANS_SERIF,
                    i == 0 ? Typeface.BOLD : Typeface.NORMAL));
            TextDraw.drawLine(c, p, label, ix, cy, wDate * 0.94f,
                    Type.size(font, Type.LABEL),
                    i == 0 ? pal.textPrimary : pal.textSecondary, 1);

            float iconS = Math.min(rowH * 0.84f, wIcon * 0.84f);
            WeatherArt.draw(c, Wmo.icon(wx.dCode[i], true),
                    ix + wDate + wIcon / 2f, cy, iconS, pal, true);

            p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
            TextDraw.drawLine(c, p, Wmo.text(wx.dCode[i]), ix + wDate + wIcon, cy,
                    wDesc * 0.94f, Type.size(font, Type.NOTE), pal.textFaint, 1);

            int prob = wx.dPrecipProb[i];
            if (prob > 0) {
                TextDraw.drawLine(c, p, prob + "%", ix + wDate + wIcon + wDesc, cy,
                        wProb * 0.92f, Type.size(font, Type.NOTE),
                        TextDraw.softText(pal, pal.rain), 1);
            }

            double dmin = Double.isNaN(wx.dMin[i]) ? lo : wx.dMin[i];
            double dmax = Double.isNaN(wx.dMax[i]) ? hi : wx.dMax[i];

            p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            TextDraw.drawLine(c, p, wx.formatTempPlain(dmin),
                    ix + wDate + wIcon + wDesc + wProb, cy, numW * 0.98f,
                    Type.size(font, Type.BODY), pal.textFaint, 1);

            float barX = ix + wDate + wIcon + wDesc + wProb + numW;
            float barH = Math.max(2f, rowH * 0.20f);
            float barY = cy - barH / 2f;
            TextDraw.pill(c, p, pal.line, barX, barY, barX + wBar, barY + barH, barH / 2f);
            float f0 = (float) ((dmin - lo) / range);
            float f1 = (float) ((dmax - lo) / range);
            float x0 = barX + wBar * clamp01(f0);
            float x1 = barX + wBar * clamp01(f1);
            if (x1 - x0 < barH * 0.8f) {
                x1 = x0 + barH * 0.8f;
            }
            TextDraw.pill(c, p, tempColor((dmin + dmax) / 2.0), x0, barY, x1, barY + barH, barH / 2f);

            TextDraw.drawLine(c, p, wx.formatTempPlain(dmax), barX + wBar + numW, cy,
                    numW * 0.98f, Type.size(font, Type.BODY), pal.textSecondary, 2);
        }
    }

    // ------------------------------------------------------------------ 空气质量

    private void drawAir(Canvas c, Paint p, Palette pal, float left, float top,
                         float w, float h, WeatherData wx) {
        if (h <= 4 || w <= 4) {
            return;
        }
        TextDraw.card(c, p, pal, left, top, left + w, top + h, font * 0.60f);

        float pad = pad();
        float ix = left + pad;
        float iy = top + pad;
        float iw = Math.max(1f, w - pad * 2f);
        float ih = Math.max(1f, h - pad * 2f);
        float headH = Cards.airHeadH(font);

        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        TextDraw.drawLine(c, p, "空气质量", ix, iy + headH / 2f, iw * 0.30f,
                Type.size(font, Type.HEAD), pal.accent, 1);

        if (wx.aqi < 0) {
            p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
            TextDraw.drawLine(c, p, "暂无数据", ix + iw, iy + headH / 2f, iw * 0.40f,
                    Type.size(font, Type.NOTE), pal.textFaint, 2);
            return;
        }

        int color = AqiUtil.color(wx.aqi);
        float bw = iw * 0.28f;
        float bh = Math.min(headH, Type.line(font, Type.LABEL) * Cards.PILL_PAD);
        float bx = ix + iw - bw;
        float by = iy + (headH - bh) / 2f;
        TextDraw.pill(c, p, TextDraw.softBg(pal, color), bx, by, bx + bw, by + bh, bh / 2f);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        TextDraw.drawLine(c, p, AqiUtil.level(wx.aqi), bx + bw / 2f, by + bh / 2f, bw * 0.88f,
                Type.size(font, Type.LABEL), TextDraw.softText(pal, color), 0);

        // AQI 数值是这张卡的英雄数字：全卡最大，一眼就能读到
        TextDraw.drawLine(c, p, "AQI " + wx.aqi, ix + iw * 0.30f, iy + headH / 2f,
                Math.max(bx - ix - iw * 0.02f, iw * 0.30f),
                Type.size(font, Type.TITLE), TextDraw.softText(pal, color), 1);

        // 六项污染物：两列三行
        double[] vals = {wx.pm25, wx.pm10, wx.o3, wx.no2, wx.so2, wx.co};
        float bodyTop = iy + headH;
        float bodyH = ih - headH;
        if (bodyH <= 4) {
            return;
        }
        int cols = 2;
        int rows = 3;
        float cw = iw / cols;
        float ch = bodyH / rows;
        for (int i = 0; i < vals.length; i++) {
            drawPollCell(c, p, pal, ix + (i % cols) * cw, bodyTop + (i / cols) * ch,
                    cw, ch, POLL_NAME[i], vals[i], POLL_LIMIT[i]);
        }
    }

    private void drawPollCell(Canvas c, Paint p, Palette pal, float left, float top,
                              float w, float h, String name, double value, double limit) {
        float padX = w * 0.05f;
        float cw = w - padX * 2f;
        if (cw <= 4 || h <= 4) {
            return;
        }
        // 名称注释级、数值正文级：数值才是要读的东西
        float rowH = Type.line(font, Type.BODY);
        float cy = top + rowH * 0.5f;
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        TextDraw.drawLine(c, p, name, left + padX, cy, cw * 0.52f,
                Type.size(font, Type.NOTE), pal.textFaint, 1);

        float ratio = value < 0 ? 0f : (float) Math.min(1.6, value / limit);
        int color = ratio <= 0.5f ? 0xFF3FBF6A
                : ratio <= 0.8f ? 0xFFC7B03A
                : ratio <= 1.0f ? 0xFFE09A3A
                : ratio <= 1.3f ? 0xFFE45B4C : 0xFFB0304F;
        String val = value < 0 ? "--" : String.valueOf(Math.round(value));
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        TextDraw.drawLine(c, p, val, left + padX + cw, cy, cw * 0.40f,
                Type.size(font, Type.BODY),
                value < 0 ? pal.textFaint : TextDraw.softText(pal, color), 2);

        float barH = Math.min(Cards.airBarH(font), Math.max(2f, h - rowH - font * 0.16f));
        float barY = top + h - barH;
        float barMax = Math.max(1f, cw * 0.72f);
        TextDraw.pill(c, p, pal.line, left + padX, barY, left + padX + barMax, barY + barH,
                barH / 2f);
        if (value >= 0) {
            float frac = Math.max(0.02f, Math.min(1f, (float) (value / limit)));
            TextDraw.pill(c, p, color, left + padX, barY,
                    left + padX + barMax * frac, barY + barH, barH / 2f);
        }
    }

    // ------------------------------------------------------------------ 生活指数

    private void drawLife(Canvas c, Paint p, Palette pal, float left, float top,
                          float w, float h, WeatherData wx) {
        if (h <= 4 || w <= 4) {
            return;
        }
        List<LifeIndex> list = LifeIndex.of(wx);
        TextDraw.card(c, p, pal, left, top, left + w, top + h, font * 0.55f);

        float pad = pad();
        float ix = left + pad;
        float iy = top + pad;
        float iw = Math.max(1f, w - pad * 2f);
        float ih = Math.max(1f, h - pad * 2f);

        float titleH = Cards.cardTitleH(font);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        TextDraw.drawLine(c, p, "生活指数", ix, iy + titleH / 2f, iw,
                Type.size(font, Type.HEAD), pal.accent, 1);

        int n = list.size();
        float gh = ih - titleH;
        if (n == 0 || gh <= 4) {
            return;
        }
        // 与 heightOf 同一口径
        int[] g = Cards.lifeGrid(iw, font);
        int cols = g[0];
        int rows = g[1];
        float cw = iw / cols;
        float ch = gh / rows;
        for (int i = 0; i < n; i++) {
            drawLifeCell(c, p, pal, ix + (i % cols) * cw, iy + titleH + (i / cols) * ch,
                    cw, ch, list.get(i));
        }
    }

    private void drawLifeCell(Canvas c, Paint p, Palette pal, float left, float top,
                              float w, float h, LifeIndex li) {
        float padX = w * 0.08f;
        float cw = w - padX * 2f;
        if (cw <= 2 || h <= 2) {
            return;
        }
        // 名称（注释级、淡）+ 等级（标签级、加粗上色）拼一行，建议在下一行（注释级）。
        // 上一版名称和等级是同一个字号，等级只靠颜色区分——层级不够，一眼看不出哪个是结论。
        float rowH = Type.line(font, Type.LABEL);
        float nameSize = Type.size(font, Type.NOTE);
        float lvSize = Type.size(font, Type.LABEL);
        String namePart = li.name + "  ";

        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        p.setTextSize(nameSize);
        float nw = p.measureText(namePart);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        p.setTextSize(lvSize);
        float lw = p.measureText(li.level);
        if (nw + lw > cw) {
            // 塞不下就让等级让位：宁可这一格少显示等级，也不把两段挤成一条线
            nw = Math.min(nw, cw * 0.55f);
            lw = Math.max(0f, cw - nw);
        }

        float y = top + rowH * 0.5f;
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        TextDraw.drawLine(c, p, li.name, left + padX, y, Math.max(1f, nw - nameSize * 0.5f),
                nameSize, pal.textFaint, 1);
        if (lw > 2f) {
            TextDraw.drawLine(c, p, li.level, left + padX + nw, y, lw, lvSize,
                    TextDraw.softText(pal, li.color), 1);
        }

        if (li.advice != null && li.advice.length() > 0) {
            p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
            TextDraw.drawWrappedFixed(c, p, li.advice, left + padX, top + rowH, cw,
                    Type.size(font, Type.NOTE), pal.textSecondary, 1, 0.10f);
        }
    }

    // ------------------------------------------------------------------ 页脚

    private void drawFootnote(Canvas c, Paint p, Palette pal, float left, float top, float w, float h) {
        if (h <= 2f) {
            return;
        }
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        // 页脚是全页最小的字：数据来源说明是「有它安心、没它也行」的信息
        TextDraw.drawLine(c, p, FOOTNOTE, left + w / 2f, top + h / 2f, w,
                Type.size(font, Type.FOOT), pal.textFaint, 0);
    }

    // ------------------------------------------------------------------ 工具

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** 气温配色：冷紫 → 蓝 → 青 → 绿 → 橙 → 红 */
    static int tempColor(double c) {
        if (c <= -10) {
            return 0xFF8E7BE8;
        }
        if (c <= 0) {
            return 0xFF4FA3F7;
        }
        if (c <= 10) {
            return 0xFF3FC0B4;
        }
        if (c <= 20) {
            return 0xFF5FBF4F;
        }
        if (c <= 28) {
            return 0xFFD9B03A;
        }
        if (c <= 35) {
            return 0xFFE0813A;
        }
        return 0xFFD0483F;
    }
}
