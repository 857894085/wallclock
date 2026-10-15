package com.vincent.wallclock;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.vincent.wallclock.gen.Cards;
import com.vincent.wallclock.gen.Layout;
import com.vincent.wallclock.gen.Type;

import java.util.List;
import java.util.Locale;

/**
 * 今日黄历页。
 *
 * <p><b>字号是固定的</b>：只由屏幕短边、档位（精简/自动/完整）和老人模式决定，
 * 与当天有多少条宜忌、当前显示了几张卡<b>没有关系</b>。上一版是「每张卡分到一屏高度的
 * 一份，再把文字 fit 进这个高度」，于是内容一多字就变小——同一块屏上今天和明天
 * 的字号都不一样，看着像 bug。
 *
 * <p>现在反过来：卡片按<b>内容的自然高度</b>摆放，装不下就整页上下滑。
 * 测量高度与绘制共用 {@link TextDraw#wrap}，所以「量出来的高度」必然等于
 * 「画出来的高度」，不会像估算那样漂。
 *
 * <p>顶部标题（日期 + 干支）与徽标行<b>吸顶</b>，不参与滚动：滚到哪都看得见这是哪一天。
 *
 * <p>页面本身不做任何历法推断，只负责把 {@link Almanac} 的字段摆好。
 * 页脚固定标注「宜忌为历注合成，仅供参考」——干支、建除、值神、星宿、冲煞、
 * 纳音、胎神、方位这些是可唯一推算的定值，而宜忌在不同通书间本就有出入。
 */
public final class AlmanacPage {

    private static final String[] WEEK_CN = {"日", "一", "二", "三", "四", "五", "六"};

    /** 黄道日 / 黑道日 */
    private static final int C_HUANG = 0xFFD9A441;
    private static final int C_HEI = 0xFF7C8FA3;
    /** 吉 / 凶 */
    private static final int C_AUSP = 0xFF3FBF6A;
    private static final int C_INAUSP = 0xFFE45B4C;
    /** 宜 / 忌 */
    private static final int C_YI = 0xFF35A85C;
    private static final int C_JI = 0xFFD0483F;

    private static final String FOOTNOTE = "宜忌由传统历注合成，各家通书互有出入，仅供参考";

    //
    // 固定区（吸顶标题 / 徽标行 / 页脚）的高度一律由**角色行高**算，不用屏幕百分比，
    // 也不在这里就地拍系数——全部向 gen/Type 那张层级表取。
    //
    // 历史教训有两条，都写在这儿免得再犯：
    //   1. 上一版高度写「页高 × 17%~22%」、字号写「font × 0.62/0.70/0.62」，
    //      两套数字互相独立，横屏或大字号时必然对不上（要么压字，要么白留一大块）。
    //   2. 上一版徽标行给 2.20em 高度却只放 0.60em 的字、页脚给 1.70em 只放 0.48em，
    //      固定区白白吃掉一屏的近三分之一，正文反倒没地方——「该大的不大」有一半来自这里。
    //      现在高度 = 行高 × 1.3 左右，多出来的高度还给内容。
    //
    /** 方位卡里标签列占卡内宽的比例（量高与绘制必须共用，否则行数会算错） */
    private static final float META_LABEL_PCT = 0.19f;
    /** 方位卡里标签与值之间的空隙占比 */
    private static final float META_GAP_PCT = 0.03f;
    /** 横屏时左列宽度占比 */
    private static final float LAND_LEFT_PCT = 0.545f;

    private Almanac cached;
    private int cachedJdn = -1;

    /** 当前这一屏的固定正文字号 */
    private float font = Layout.MIN_FONT;

    // ---- 版式缓存（同一尺寸 + 同一设置 + 同一天只算一次）----
    private int[] cardsL = new int[0];
    private int[] cardsR = new int[0];
    private float[] hsL = new float[0];
    private float[] hsR = new float[0];
    private float cacheContentH = 0f;
    private float planW = -1f;
    private float planH = -1f;
    private int planJdn = -1;
    private int planSig = 0;

    /** 按天缓存，避免每秒重算一次（主界面每秒都会 invalidate） */
    public Almanac almanacOf(int y, int m, int d) {
        int jdn = LunarCalendar.toJdn(y, m, d);
        if (cached == null || cachedJdn != jdn) {
            cached = Almanac.of(y, m, d);
            cachedJdn = jdn;
        }
        return cached;
    }

    // ------------------------------------------------------------------ 版式

    private static int sigOf(Prefs prefs, boolean landscape) {
        int s = prefs.elderMode ? 1 : 0;
        s = s * 3 + (prefs.almanacFull ? 1 : 0);
        s = s * 3 + (landscape ? 1 : 0);
        s = s * 7 + prefs.pageDetail;
        return s;
    }

    private float headH() {
        return Type.stack(font, Cards.A_HEAD_ROLES);
    }

    private float badgeH() {
        return Type.line(font, Type.NOTE) * Cards.PILL_PAD;
    }

    private float footH() {
        return Type.line(font, Type.FOOT) * Cards.A_FOOT_PAD;
    }

    /** 吸顶标题 + 徽标行 + 页脚占掉的总高 */
    private float chromeH() {
        return headH() + badgeH() + footH();
    }

    /** 内容可视高度（去掉吸顶标题、徽标行与页脚之后） */
    private float viewH(float h) {
        float v = h - chromeH() - gap() * 2f;
        return v > 0f ? v : 0f;
    }

    private float gap() {
        return font * Cards.A_GAP_EM;
    }

    /**
     * 量出每张卡的自然高度。必须在绘制前调用（或由 {@link #contentHeight} 触发）。
     */
    private void measure(Paint p, float w, float h, Prefs prefs,
                         int year, int month, int day, boolean landscape) {
        int jdn = LunarCalendar.toJdn(year, month, day);
        int sig = sigOf(prefs, landscape);
        if (planW == w && planH == h && planJdn == jdn && planSig == sig) {
            return;
        }
        font = Layout.pageFont(w, h, prefs.pageDetail, prefs.textScale());
        Almanac a = almanacOf(year, month, day);

        if (landscape) {
            float gap = gap();
            float lw = (w - gap) * LAND_LEFT_PCT;
            float rw = w - gap - lw;
            int[] ml = Cards.members(Cards.A_LAND_L, prefs.pageDetail);
            int[] mr = Cards.members(Cards.A_LAND_R, prefs.pageDetail);
            cardsL = ml;
            cardsR = mr;
            hsL = heights(p, a, lw, ml);
            hsR = heights(p, a, rw, mr);
            cacheContentH = Math.max(sum(hsL, gap), sum(hsR, gap));
        } else {
            int[] m = Cards.members(Cards.A_PORTRAIT, prefs.pageDetail);
            cardsL = m;
            cardsR = new int[0];
            hsL = heights(p, a, w, m);
            hsR = new float[0];
            cacheContentH = sum(hsL, gap());
        }
        planW = w;
        planH = h;
        planJdn = jdn;
        planSig = sig;
    }

    private static float sum(float[] hs, float gap) {
        float t = 0f;
        for (int i = 0; i < hs.length; i++) {
            t += hs[i] + (i > 0 ? gap : 0f);
        }
        return t;
    }

    /**
     * 本页的可滚动距离：内容自然总高减去内容区可视高度。
     * 内容比视口矮时返回 0（也就是不许滚）。
     *
     * <p>对外只暴露这一个数，调用方不需要知道标题多高、页脚多高——那些是页面自己的事。
     */
    public float scrollRange(Paint p, float w, float h, Prefs prefs,
                             int year, int month, int day, boolean landscape) {
        if (w <= 8f || h <= 8f) {
            return 0f;
        }
        measure(p, w, h, prefs, year, month, day, landscape);
        return Layout.scrollRange(cacheContentH, viewH(h));
    }

    /** 上一次测量后的内容自然总高，供滚动条算滑块长度（配合 {@link #scrollRange} 用） */
    public float lastContentH() {
        return cacheContentH;
    }

    /**
     * 分页锚点：每张卡顶部相对内容起点的偏移（升序），末尾补上内容总高。
     *
     * <p>供「一页一页翻」用：页顶对齐到这些位置，卡片就不会被切成上下两半。
     */
    public float[] anchors(Paint p, float w, float h, Prefs prefs,
                           int year, int month, int day, boolean landscape) {
        if (w <= 8f || h <= 8f) {
            return new float[0];
        }
        measure(p, w, h, prefs, year, month, day, landscape);
        float g = gap();
        float[] a = Layout.cardTops(hsL, g);
        float[] b = (landscape && hsR.length > 0) ? Layout.cardTops(hsR, g) : null;
        return Layout.mergeAnchors(a, b, cacheContentH);
    }

    // ------------------------------------------------------------------ 入口

    public void drawScreen(Canvas c, Paint p, Palette pal, Prefs prefs,
                           float left, float top, float w, float h,
                           int year, int month, int day, boolean landscape, float scrollY) {
        if (w <= 8f || h <= 8f) {
            return;
        }
        measure(p, w, h, prefs, year, month, day, landscape);
        Almanac a = almanacOf(year, month, day);
        int dow = dowOf(year, month, day);

        float hh = headH();
        float bh = badgeH();
        float fh = footH();
        float g = gap();

        // 吸顶：标题 + 徽标行，永远在最上面
        drawHeader(c, p, pal, left, top, w, hh, a, year, month, day, dow);
        drawBadges(c, p, pal, left, top + hh, w, bh, a);

        // 内容区：裁到可视范围，再按 scrollY 上移
        float bodyTop = top + hh + bh + g;
        float bodyH = h - hh - bh - fh - g * 2f;
        if (bodyH <= 4f) {
            drawFootnote(c, p, pal, left, top + h - fh, w, fh);
            return;
        }
        int save = c.save();
        c.clipRect(left, bodyTop, left + w, bodyTop + bodyH);
        c.translate(0f, -scrollY);

        if (landscape) {
            float lw = (w - g) * LAND_LEFT_PCT;
            float rw = w - g - lw;
            drawColumn(c, p, pal, a, left, bodyTop, lw, cardsL, hsL, g);
            drawColumn(c, p, pal, a, left + lw + g, bodyTop, rw, cardsR, hsR, g);
        } else {
            drawColumn(c, p, pal, a, left, bodyTop, w, cardsL, hsL, g);
        }
        c.restoreToCount(save);

        drawFootnote(c, p, pal, left, top + h - fh, w, fh);
    }

    private void drawColumn(Canvas c, Paint p, Palette pal, Almanac a,
                            float left, float top, float w, int[] cards, float[] hs, float gap) {
        float y = top;
        for (int i = 0; i < cards.length && i < hs.length; i++) {
            drawCard(c, p, pal, cards[i], left, y, w, hs[i], a);
            y += hs[i] + gap;
        }
    }

    private static int dowOf(int y, int m, int d) {
        return WeatherData.dowOf(y, m, d);
    }

    // ------------------------------------------------------------------ 每张卡的高度

    /** 卡片内边距：只由字号定，不依赖卡片自身高度（避免循环依赖） */
    private float pad() {
        return font * Cards.PAD_A;
    }

    private float[] heights(Paint p, Almanac a, float w, int[] members) {
        float[] out = new float[members.length];
        for (int i = 0; i < members.length; i++) {
            out[i] = heightOf(p, a, members[i], w);
        }
        return out;
    }

    /**
     * 卡片自然高度 = {@link Cards#chromeH}（固定部分）+ 内容折行多出来的那部分。
     *
     * <p>固定部分（内边距、卡片标题、徽标方块、网格）全部由 {@code Cards} 按角色表算，
     * 这一页只负责加「今天有多少条宜忌」这类内容驱动的高度。
     * 两边共用同一张角色表，所以「把标题调大一级」必然同时把卡片撑高，
     * 不存在「字号改了、高度没跟上 → 被 {@link Layout#floorFor} 压回去」这种情况。
     */
    private float heightOf(Paint p, Almanac a, int id, float w) {
        float iw = Math.max(1f, w - pad() * 2f);
        switch (id) {
            case Cards.A_YI:
                return Cards.chromeH(id, font, Cards.PAD_A, 1)
                        + yijiBodyH(p, a, true, iw);
            case Cards.A_JI:
                return Cards.chromeH(id, font, Cards.PAD_A, 1)
                        + yijiBodyH(p, a, false, iw);
            case Cards.A_META:
                return Cards.chromeH(id, font, Cards.PAD_A, 1)
                        + metaOverflowH(p, a, iw);
            case Cards.A_SHEN:
                return Cards.chromeH(id, font, Cards.PAD_A, 1)
                        + shenOverflowH(p, a, iw);
            case Cards.A_HOUR:
                return Cards.chromeH(id, font, Cards.PAD_A, Cards.hourGrid(iw, font)[1]);
            default:
                return Cards.chromeH(id, font, Cards.PAD_A, 1);
        }
    }

    /** 宜忌词条在正文角色下的自然高度；没有词条就是 0（不画占位符） */
    private float yijiBodyH(Paint p, Almanac a, boolean yi, float iw) {
        String body = yijiBodyText(a, yi);
        if (body.length() == 0) {
            return 0f;
        }
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        return TextDraw.wrappedHeight(p, body, iw, Type.size(font, Type.BODY), 0.26f);
    }

    private static String yijiBodyText(Almanac a, boolean yi) {
        if (yi && a.daXiong) {
            return "四离四绝、杨公忌日，传统上忌一切大事";
        }
        List<String> items = yi ? a.yi : a.ji;
        return items.isEmpty() ? "" : join(items);
    }

    /** 方位卡：四行「标签 + 值」，某行的值折了行就把多出来的高度补上 */
    private float metaOverflowH(Paint p, Almanac a, float iw) {
        float labelW = iw * META_LABEL_PCT;
        float gapW = iw * META_GAP_PCT;
        float valueW = Math.max(1f, iw - labelW - gapW);
        float line = Type.line(font, Type.BODY);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        float extra = 0f;
        for (String v : metaValues(a)) {
            float wh = TextDraw.wrappedHeight(p, v, valueW, Type.size(font, Type.BODY), 0.10f);
            if (wh > line) {
                extra += wh - line;
            }
        }
        return extra;
    }

    private static String[] metaValues(Almanac a) {
        return new String[]{
                blank(a.taiShen),
                "喜 " + blank(a.xiShen) + "　财 " + blank(a.caiShen) + "　福 " + blank(a.fuShen),
                "阳贵 " + blank(a.yangGui) + "　阴贵 " + blank(a.yinGui),
                blank(a.pengZuGan) + "　" + blank(a.pengZuZhi),
        };
    }

    /** 神煞卡：吉神 / 凶神 两组词条折行的高度（两组的小标题已含在 chromeH 里） */
    private float shenOverflowH(Paint p, Almanac a, float iw) {
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        return shenRowBodyH(p, a.jiShen, iw) + shenRowBodyH(p, a.xiongShen, iw);
    }

    private float shenRowBodyH(Paint p, List<String> items, float iw) {
        if (items.isEmpty()) {
            return 0f;
        }
        return TextDraw.wrappedHeight(p, join(items), iw, Type.size(font, Type.BODY), 0.12f);
    }

    // ------------------------------------------------------------------ 卡片分发

    /** 只有这一处知道「哪张卡长什么样」 */
    private void drawCard(Canvas c, Paint p, Palette pal, int id,
                          float left, float top, float w, float h, Almanac a) {
        if (h <= 4f || w <= 4f) {
            return;
        }
        switch (id) {
            case Cards.A_YI:
                drawYiJi(c, p, pal, left, top, w, h, a, true);
                break;
            case Cards.A_JI:
                drawYiJi(c, p, pal, left, top, w, h, a, false);
                break;
            case Cards.A_META:
                drawMetaCard(c, p, pal, left, top, w, h, a);
                break;
            case Cards.A_SHEN:
                drawShenShaCard(c, p, pal, left, top, w, h, a);
                break;
            case Cards.A_HOUR:
                drawHourCard(c, p, pal, left, top, w, h, a);
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ 吸顶标题

    private void drawHeader(Canvas c, Paint p, Palette pal, float left, float top, float w, float h,
                            Almanac a, int year, int month, int day, int dow) {
        String dateLine = String.format(Locale.US, "%d年%d月%d日 星期%s", year, month, day, WEEK_CN[dow]);
        String lunarPart = "";
        if (a.lunar != null) {
            lunarPart = "农历" + a.lunar.monthName + a.lunar.dayName;
        }
        String sub = lunarPart.length() > 0 ? dateLine + " · " + lunarPart : dateLine;

        String main = a.yearGanZhi + "年 " + a.monthGanZhi + "月 " + a.dayGanZhi + "日";
        String extra = a.naYin + " · 冲" + a.chong + "煞" + a.sha;

        // 三行不再是「等分 + 各自一个系数」，而是各自领一个角色：
        // 日期（NOTE）→ 干支（TITLE，这页的主标题）→ 纳音冲煞（NOTE）。
        // 上一版三行写 0.62 / 0.70 / 0.62，主标题只比注释大一点点，整页看着是平的。
        String[] texts = {sub, main, extra};
        int[] colors = {pal.textFaint, pal.textPrimary, pal.textSecondary};
        float y = top;
        for (int i = 0; i < Cards.A_HEAD_ROLES.length && i < texts.length; i++) {
            float lh = Type.line(font, Cards.A_HEAD_ROLES[i]);
            p.setTypeface(Typeface.create(Typeface.SANS_SERIF,
                    i == 1 ? Typeface.BOLD : Typeface.NORMAL));
            TextDraw.drawLine(c, p, texts[i], left, y + lh * 0.5f, w,
                    Type.size(font, Cards.A_HEAD_ROLES[i]), colors[i], 1);
            y += lh;
        }
    }

    /** 徽标行：建除 / 值神 / 星宿 */
    private void drawBadges(Canvas c, Paint p, Palette pal, float left, float top, float w, float h,
                            Almanac a) {
        if (h <= 4f) {
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
        float gap = w * 0.022f;
        float cw = (w - gap * 2f) / 3f;
        float radius = h * 0.34f;
        for (int i = 0; i < texts.length; i++) {
            float x = left + i * (cw + gap);
            TextDraw.pill(c, p, TextDraw.softBg(pal, colors[i]), x, top, x + cw, top + h, radius);
            p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            // 徽标是「看一眼就知道今天什么日子」的辅助信息，给注释级就够；
            // 上一版胶囊高 2.20em 却只放 0.60em 的字，白留的高度全是从正文身上挤的。
            TextDraw.drawLine(c, p, texts[i], x + cw / 2f, top + h / 2f,
                    cw - h * 0.44f, Type.size(font, Type.NOTE),
                    TextDraw.softText(pal, colors[i]), 0);
        }
    }

    // ------------------------------------------------------------------ 宜 / 忌

    private void drawYiJi(Canvas c, Paint p, Palette pal, float left, float top,
                          float w, float h, Almanac a, boolean yi) {
        List<String> items = yi ? a.yi : a.ji;
        int color = yi ? C_YI : C_JI;
        String label = yi ? "宜" : "忌";
        boolean verdict = yi && a.daXiong;

        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(TextDraw.blend(color, pal.panel, pal.dark ? 0.90f : 0.94f));
        RectF r = new RectF(left, top, left + w, top + h);
        float radius = font * 0.55f;
        c.drawRoundRect(r, radius, radius, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, font * 0.05f));
        p.setColor(TextDraw.blend(color, pal.panelStroke, pal.dark ? 0.55f : 0.42f));
        c.drawRoundRect(r, radius, radius, p);
        p.setStyle(Paint.Style.FILL);

        float pad = pad();
        float ix = left + pad;
        float iy = top + pad;
        float iw = Math.max(1f, w - pad * 2f);

        // 「宜 / 忌」那个字是这张卡的视觉锚，给卡片标题级；方块高度与 Cards 的量高口径一致
        float badge = Cards.yijiBadgeH(font);
        TextDraw.pill(c, p, color, ix, iy, ix + badge, iy + badge, badge * 0.26f);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        TextDraw.drawLine(c, p, label, ix + badge / 2f, iy + badge / 2f,
                badge * 0.80f, Type.size(font, Type.HEAD), 0xFFFFFFFF, 0);

        String head = verdict
                ? "诸事不宜" + (a.daXiongReason.length() > 0 ? " · " + a.daXiongReason : "")
                : label + " · " + items.size() + " 项";
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        // 「宜 · 12 项」是计数说明，不是内容，给标签级——上一版和词条一样大，主次就糊了
        float headW = Math.max(1f, iw - badge - font * 0.6f);
        TextDraw.drawLine(c, p, head, ix + badge + font * 0.6f, iy + badge / 2f, headW,
                Type.size(font, Type.LABEL), verdict ? C_JI : pal.textSecondary, 1);

        float bodyTop = iy + badge + font * 0.45f;
        float bodyH = top + h - pad - bodyTop;
        if (bodyH <= 0f) {
            return;
        }
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        String body = yijiBodyText(a, yi);
        if (body.length() == 0) {
            return;                       // 空着就空着，不放占位符
        }
        // 词条是这张卡唯一要读的内容，给正文级；字号固定，词条多就多占几行
        // （卡片随之变高、页面变长），绝不把字缩小。
        TextDraw.drawWrappedFixed(c, p, body, ix, bodyTop, iw,
                Type.size(font, Type.BODY),
                verdict ? TextDraw.softText(pal, C_JI) : pal.textPrimary, 1, 0.26f);
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 方位 / 彭祖 / 胎神

    private void drawMetaCard(Canvas c, Paint p, Palette pal, float left, float top,
                              float w, float h, Almanac a) {
        float radius = font * 0.55f;
        TextDraw.card(c, p, pal, left, top, left + w, top + h, radius);

        float pad = pad();
        float ix = left + pad;
        float iy = top + pad;
        float iw = Math.max(1f, w - pad * 2f);

        float titleH = Cards.cardTitleH(font);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        TextDraw.drawLine(c, p, "方位 · 彭祖 · 胎神", ix, iy + titleH / 2f, iw,
                Type.size(font, Type.HEAD), pal.accent, 1);

        String[] labels = {"胎神", "方位", "贵人", "彭祖"};
        String[] values = metaValues(a);
        float labelW = iw * META_LABEL_PCT;
        float gapW = iw * META_GAP_PCT;
        float valueW = Math.max(1f, iw - labelW - gapW);
        float line = Type.line(font, Type.BODY);

        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        float y = iy + titleH;
        for (int i = 0; i < labels.length; i++) {
            float wh = TextDraw.wrappedHeight(p, values[i], valueW,
                    Type.size(font, Type.BODY), 0.10f);
            float rh = Math.max(line, wh);
            p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
            TextDraw.drawLine(c, p, labels[i], ix, y + rh / 2f, labelW,
                    Type.size(font, Type.LABEL), pal.textFaint, 1);
            TextDraw.drawWrappedFixed(c, p, values[i], ix + labelW + gapW, y, valueW,
                    Type.size(font, Type.BODY), pal.textSecondary, 1, 0.10f);
            y += rh;
        }
    }

    private static String blank(String s) {
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------------ 神煞

    private void drawShenShaCard(Canvas c, Paint p, Palette pal, float left, float top,
                                 float w, float h, Almanac a) {
        float radius = font * 0.55f;
        TextDraw.card(c, p, pal, left, top, left + w, top + h, radius);

        float pad = pad();
        float ix = left + pad;
        float iy = top + pad;
        float iw = Math.max(1f, w - pad * 2f);

        float titleH = Cards.cardTitleH(font);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        TextDraw.drawLine(c, p, "神煞", ix, iy + titleH / 2f, iw,
                Type.size(font, Type.HEAD), pal.accent, 1);

        float y = iy + titleH;
        y += drawShenRow(c, p, pal, ix, y, iw, "吉神宜趋", a.jiShen, C_AUSP);
        drawShenRow(c, p, pal, ix, y, iw, "凶神宜忌", a.xiongShen, C_INAUSP);
    }

    private float drawShenRow(Canvas c, Paint p, Palette pal, float left, float top,
                              float w, String label, List<String> items, int color) {
        float labelH = Type.line(font, Type.LABEL);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        TextDraw.drawLine(c, p, label, left, top + labelH / 2f, w,
                Type.size(font, Type.LABEL), TextDraw.softText(pal, color), 1);
        if (items.isEmpty()) {
            return labelH;
        }
        float bh = TextDraw.drawWrappedFixed(c, p, join(items), left, top + labelH, w,
                Type.size(font, Type.BODY), pal.textSecondary, 1, 0.12f);
        return labelH + bh;
    }

    // ------------------------------------------------------------------ 时辰

    private void drawHourCard(Canvas c, Paint p, Palette pal, float left, float top,
                              float w, float h, Almanac a) {
        float radius = font * 0.55f;
        TextDraw.card(c, p, pal, left, top, left + w, top + h, radius);

        float pad = pad();
        float ix = left + pad;
        float iy = top + pad;
        float iw = Math.max(1f, w - pad * 2f);

        float titleH = Cards.cardTitleH(font);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        TextDraw.drawLine(c, p, "时辰吉凶", ix, iy + titleH / 2f, iw,
                Type.size(font, Type.HEAD), pal.accent, 1);

        int[] g = Cards.hourGrid(iw, font);
        int cols = g[0];
        int rows = g[1];
        float cw = iw / cols;
        float ch = Cards.hourCellH(font);

        for (int i = 0; i < Cards.HOUR_CELLS; i++) {
            int r = i / cols;
            int col = i % cols;
            drawHourCell(c, p, pal, ix + col * cw, iy + titleH + r * ch, cw, ch, a, i);
        }
    }

    private void drawHourCell(Canvas c, Paint p, Palette pal, float left, float top,
                              float w, float h, Almanac a, int i) {
        float padX = w * 0.05f;
        float padY = h * 0.05f;
        float bw = w - padX * 2f;
        float bh = h - padY * 2f;
        if (bw <= 2f || bh <= 2f) {
            return;
        }
        boolean good = a.hourJi[i];
        int color = good ? C_AUSP : C_INAUSP;

        TextDraw.pill(c, p, TextDraw.softBg(pal, color), left + padX, top + padY,
                left + padX + bw, top + padY + bh, bh * 0.22f);

        String l1 = Almanac.shiChenName(i);
        String l2 = a.hourGanZhi[i] == null ? "" : a.hourGanZhi[i];
        String l3 = a.hourZhiShen[i] == null ? "" : a.hourZhiShen[i];

        // 格子里三行也是分级的：时辰名（标签级、加粗、按吉凶上色）压住下面两行注释级的
        float cx = left + w / 2f;
        float y = top + padY;
        float lh = Type.line(font, Type.LABEL);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        TextDraw.drawLine(c, p, l1, cx, y + lh / 2f, bw * 0.94f,
                Type.size(font, Type.LABEL), TextDraw.softText(pal, color), 0);
        y += lh;

        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        lh = Type.line(font, Type.NOTE);
        TextDraw.drawLine(c, p, l2, cx, y + lh / 2f, bw * 0.94f,
                Type.size(font, Type.NOTE), pal.textSecondary, 0);
        y += lh;
        TextDraw.drawLine(c, p, l3, cx, y + lh / 2f, bw * 0.94f,
                Type.size(font, Type.NOTE), TextDraw.softText(pal, color), 0);
    }

    // ------------------------------------------------------------------ 页脚

    private void drawFootnote(Canvas c, Paint p, Palette pal, float left, float top, float w, float h) {
        if (h <= 2f) {
            return;
        }
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        // 页脚就该是页脚：它是全页最小的字。上一版 0.48 倍只比正文小一半，
        // 看着和正文一个量级，反而占着 1.70em 的高度。
        TextDraw.drawLine(c, p, FOOTNOTE, left + w / 2f, top + h / 2f, w,
                Type.size(font, Type.FOOT), pal.textFaint, 0);
    }
}
