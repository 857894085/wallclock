package com.vincent.wallclock.gen;

/**
 * 副页的卡片清单、分档策略与**卡片固定高度**。
 *
 * <p>黄历页和天气详情页都由若干「卡片」拼成。这里定义四件事：
 * <ul>
 *   <li>每个档位（精简 / 自动 / 完整）显示哪几张卡；</li>
 *   <li>横屏时这些卡怎么分到左右两列；</li>
 *   <li>网格型卡片（时辰、生活指数、指标）在这个字号与宽度下排几列几行；</li>
 *   <li><b>每张卡「固定部分」占多高</b>——即与当天内容量无关的那部分（内边距、
 *       卡片标题、网格）。内容驱动的那部分（宜忌词条换了几行）由各页自己加。</li>
 * </ul>
 *
 * <p><b>不再有「放不下就丢卡」和「富余高度分配」</b>：上一版为了保证一屏装得下，
 * 会按优先级丢次要卡片、并把剩余高度按权重摊给各卡——代价就是卡片越多、宜忌词条越多，
 * 每张卡越矮，字就越小。现在字号由 {@link Layout#pageFont} 定死，卡片按内容取自然高度，
 * 装不下就整页上下滑，所以这两套策略都不需要了。
 *
 * <h3>为什么高度要搬到这里来</h3>
 *
 * <p>层级（{@link Type}）和高度是同一个硬币的两面：把卡片标题从 {@code BODY} 提到
 * {@code HEAD}，卡片必须跟着变高，否则 {@code Layout.floorFor} 会把它压回原样——
 * 表现就是「该大的地方其实没大」，而且从代码上看不出来。
 * 所以这里用 {@link #textRows} 声明每张卡有哪些固定文字行，
 * 用 {@link #chromeH} 按同一张角色表算出它们要占多高；
 * {@code tools/TestLayout.java} 断言 {@code chromeH} 永远装得下 {@code textRows}。
 * 谁再单方面改字号或改高度，测试立刻挂。
 *
 * <p>不带任何 Android 依赖。
 */
public final class Cards {

    private Cards() {
    }

    // ------------------------------------------------------------------ 高度口径常量
    //
    // 这些数字同时被「量高」和「绘制」使用，所以卡片报多高就真有多高，不会漂。

    /** 黄历页卡片内边距（相对正文字号，单边） */
    public static final float PAD_A = 0.80f;
    /** 天气详情页卡片内边距（相对正文字号，单边） */
    public static final float PAD_W = 0.75f;
    /** 胶囊 / 徽标方块的高度相对其文字行高的倍数（上下各留 15%） */
    public static final float PILL_PAD = 1.30f;
    /** 卡片标题行高相对 HEAD 行高的倍数 */
    public static final float CARD_TITLE_PAD = 1.25f;
    //
    // 各页「固定区」（吸顶标题 + 徽标 / 页脚，以及卡片间距）的口径也放这里。
    // 桌面测试要拿它算「一屏还剩多少给正文」，如果留在页面里就只能手抄一份，迟早漂移。

    /** 黄历页吸顶三行的角色：日期行 / 干支行（页面主标题）/ 纳音冲煞行 */
    public static final float[] A_HEAD_ROLES = {Type.NOTE, Type.TITLE, Type.NOTE};
    /** 黄历页页脚行高倍数 */
    public static final float A_FOOT_PAD = 1.25f;
    /** 黄历页卡片间距（相对正文字号） */
    public static final float A_GAP_EM = 0.55f;
    /** 天气详情页吸顶标题行高倍数 */
    public static final float W_HEAD_PAD = 1.55f;
    /** 天气详情页页脚行高倍数 */
    public static final float W_FOOT_PAD = 1.35f;
    /** 天气详情页卡片间距（相对正文字号） */
    public static final float W_GAP_EM = 0.50f;

    /** 天气详情页七天预报的行数 */
    public static final int DAY_ROWS = 7;
    /** 空气质量卡的污染物数（两列三行） */
    public static final int POLL_CELLS = 6;

    // ------------------------------------------------------------------ 黄历页

    /** 宜 */
    public static final int A_YI = 0;
    /** 忌 */
    public static final int A_JI = 1;
    /** 方位 / 彭祖 / 胎神 */
    public static final int A_META = 2;
    /** 神煞 */
    public static final int A_SHEN = 3;
    /** 十二时辰吉凶 */
    public static final int A_HOUR = 4;
    public static final int A_COUNT = 5;

    /** 十二时辰的格子数 */
    public static final int HOUR_CELLS = 12;

    /**
     * 竖屏各档位显示哪些卡（按显示顺序）。
     *
     * <p>精简只留宜忌——那是用户看黄历的唯一理由；自动加上方位与神煞；完整再加时辰。
     */
    public static final int[][] A_PORTRAIT = {
            {A_YI, A_JI},                                   // 精简
            {A_YI, A_JI, A_META, A_SHEN},                   // 自动
            {A_YI, A_JI, A_META, A_SHEN, A_HOUR},           // 完整
    };

    /** 横屏左列 */
    public static final int[][] A_LAND_L = {
            {A_YI},
            {A_YI, A_JI},
            {A_YI, A_JI},
    };

    /** 横屏右列 */
    public static final int[][] A_LAND_R = {
            {A_JI},
            {A_META, A_SHEN},
            {A_META, A_SHEN, A_HOUR},
    };

    // ------------------------------------------------------------------ 天气详情页

    //
    // 天气页的卡片 id 从 W_BASE 起编号，好和黄历页的 id **区隔开**。
    // 这两组 id 会一起进 Cards.textRows / Cards.chromeH 的同一个 switch，
    // 若都从 0 开始编号，switch 会出现重复的 case 标签，直接编译不过。
    //
    /** 天气页卡片 id 的起点 */
    public static final int W_BASE = 10;
    /** 当前概况 */
    public static final int W_NOW = W_BASE + 0;
    /** 指标（体感/湿度/风/气压…） */
    public static final int W_METRIC = W_BASE + 1;
    /** 24 小时 */
    public static final int W_HOUR = W_BASE + 2;
    /** 未来 7 天 */
    public static final int W_DAY = W_BASE + 3;
    /** 空气质量 */
    public static final int W_AIR = W_BASE + 4;
    /** 生活指数 */
    public static final int W_LIFE = W_BASE + 5;
    /** 天气页卡片张数 */
    public static final int W_COUNT = 6;

    /** 生活指数固定 8 项 */
    public static final int LIFE_CELLS = 8;
    /** 指标卡的格子数（体感/湿度/风/气压/能见度/云量/露点/紫外线…） */
    public static final int METRIC_CELLS = 10;

    /** 竖屏各档位显示哪些卡 */
    public static final int[][] W_PORTRAIT = {
            {W_NOW, W_HOUR, W_DAY},                                     // 精简
            {W_NOW, W_METRIC, W_HOUR, W_DAY, W_AIR},                    // 自动
            {W_NOW, W_METRIC, W_HOUR, W_DAY, W_AIR, W_LIFE},            // 完整
    };

    /** 横屏左列 */
    public static final int[][] W_LAND_L = {
            {W_NOW},
            {W_NOW, W_METRIC},
            {W_NOW, W_METRIC, W_LIFE},
    };

    /** 横屏右列 */
    public static final int[][] W_LAND_R = {
            {W_HOUR, W_DAY},
            {W_HOUR, W_DAY, W_AIR},
            {W_HOUR, W_DAY, W_AIR},
    };

    // ------------------------------------------------------------------ 网格型卡片

    /**
     * 时辰网格的列数与行数。
     *
     * <p>每格要放「时辰名 + 干支 + 值神」三行，所以按字号估一个最小格宽再反推列数。
     * 这里必须与 {@code AlmanacPage.drawHourCard} 用同一个函数，否则量出来的卡片高度
     * 和画出来的会对不上（表现为卡片底部留一条空白或内容被裁掉）。
     */
    public static int[] hourGrid(float w, float font) {
        float per = Math.max(font * 3.4f, 1f);
        int cols = Layout.clamp((int) (Math.max(1f, w * 0.90f) / per), 2, 12);
        int rows = (HOUR_CELLS + cols - 1) / cols;
        return new int[]{cols, rows};
    }

    /** 生活指数网格的列数与行数（每格放「名称 + 等级」两行） */
    public static int[] lifeGrid(float w, float font) {
        float per = Math.max(font * 5.0f, 1f);
        int cols = Layout.clamp((int) (Math.max(1f, w * 0.92f) / per), 1, 4);
        int rows = (LIFE_CELLS + cols - 1) / cols;
        return new int[]{cols, rows};
    }

    /** 指标网格的列数与行数（每格放「名称 + 数值」两行） */
    public static int[] metricGrid(float w, float font) {
        float per = Math.max(font * 4.2f, 1f);
        int cols = Layout.clamp((int) (Math.max(1f, w * 0.92f) / per), 1, 5);
        int rows = (METRIC_CELLS + cols - 1) / cols;
        return new int[]{cols, rows};
    }

    /**
     * 取指定档位的卡片数组；档位越界时按「自动」处理。
     */
    public static int[] members(int[][] table, int detail) {
        int d = (detail >= 0 && detail < table.length) ? detail : 1;
        return table[d];
    }

    // ------------------------------------------------------------------ 每张卡的固定高度

    /**
     * 这张卡有哪些<b>固定文字行</b>，各自是什么角色（自上而下）。
     *
     * <p>只用来说明「这张卡至少需要哪些行、是多大的字」，不重复声明位置——
     * 位置由绘制代码决定。{@link #chromeH} 必须装得下这里声明的所有行，
     * 这条契约由 {@code tools/TestLayout.java} 穷举断言。
     *
     * <p>网格型卡片（时辰 / 指标 / 生活指数）这里只列「每格那几行」，
     * 总行数由行数参数表达。
     */
    public static float[] textRows(int id) {
        switch (id) {
            case A_YI:
            case A_JI:
                // 徽标方块里那个「宜 / 忌」
                return new float[]{Type.HEAD};
            case A_META:
                // 标题 + 四行「标签 + 值」
                return new float[]{Type.HEAD, Type.BODY, Type.BODY, Type.BODY, Type.BODY};
            case A_SHEN:
                // 标题 + 两组「小标题 + 词条」
                return new float[]{Type.HEAD, Type.LABEL, Type.LABEL};
            case A_HOUR:
                return new float[]{Type.HEAD};
            case W_NOW:
                // 温度（英雄数字）→ 天气状况 → 今日/日出日落
                return new float[]{Type.HERO, Type.HEAD, Type.NOTE};
            case W_METRIC:
                return new float[]{Type.LABEL, Type.BODY};
            case W_HOUR:
                // 标题 + 时间刻度 + 降水概率
                return new float[]{Type.HEAD, Type.NOTE, Type.NOTE};
            case W_DAY:
                return new float[]{Type.HEAD};
            case W_AIR:
                // AQI 数字是英雄数字，标题与名称是注释级
                return new float[]{Type.TITLE, Type.NOTE, Type.BODY};
            case W_LIFE:
                return new float[]{Type.HEAD, Type.LABEL, Type.NOTE};
            default:
                return new float[]{Type.BODY};
        }
    }

    /** 卡片标题行高（HEAD 的行高 + 上下各 12.5% 呼吸） */
    public static float cardTitleH(float font) {
        return Type.line(font, Type.HEAD) * CARD_TITLE_PAD;
    }

    /** 宜 / 忌 卡片顶部那个「宜」字方块占的高度 */
    public static float yijiBadgeH(float font) {
        return Type.line(font, Type.HEAD) * PILL_PAD;
    }

    //
    // 格高 = 「格子里那几行的角色行高之和」+ 一点行间呼吸。
    // 绘制时不再对格子高度按比例二次切分（上一版用 h*0.36 / h*0.54 这种比例切，
    // 与文字实际行高是两套数字，格子一矮两行就叠在一起）。现在文字按角色行高摆放，
    // 剩余的高度作为呼吸留在格子底部，多了少了都不会叠字。

    /** 时辰格高度：时辰名 + 干支 + 值神 三行 */
    public static float hourCellH(float font) {
        return Type.line(font, Type.LABEL) + Type.lines(font, Type.NOTE, 2) + font * 0.45f;
    }

    /** 指标格高度：名称 + 数值 */
    public static float metricCellH(float font) {
        return Type.line(font, Type.LABEL) + Type.line(font, Type.BODY) + font * 0.50f;
    }

    /** 生活指数格高度：名称/等级一行 + 建议一行（建议换行更多时由页面补） */
    public static float lifeCellH(float font) {
        return Type.line(font, Type.LABEL) + Type.line(font, Type.NOTE) + font * 0.40f;
    }

    /** 24 小时卡：曲线区高度 */
    public static float hourChartH(float font) {
        return font * 4.60f;
    }

    /** 24 小时卡：曲线下方三排刻度（时间 / 图标 / 降水概率） */
    public static float hourAxisH(float font) {
        return Type.lines(font, Type.NOTE, 2) + font * 1.90f;
    }

    /** 七天预报单行高度（行里放日期、描述、温度数字与温度条） */
    public static float dayRowH(float font) {
        return Type.line(font, Type.BODY) * 1.60f;
    }

    /** 空气质量卡顶部行高度（标题 + AQI 数字 + 等级胶囊） */
    public static float airHeadH(float font) {
        return Type.line(font, Type.TITLE) * 1.15f;
    }

    /** 空气质量卡单个污染物格高度：名称/数值一行 + 下方浓度条 */
    public static float airPollH(float font) {
        return Type.line(font, Type.BODY) + font * 0.80f;
    }

    /** 空气质量卡浓度条的高度 */
    public static float airBarH(float font) {
        return font * 0.36f;
    }

    /** 黄历页「吸顶三行 + 徽标行 + 页脚」占掉的高度（不含两处间距） */
    public static float almanacChromeH(float font) {
        return Type.stack(font, A_HEAD_ROLES)
                + Type.line(font, Type.NOTE) * PILL_PAD
                + Type.line(font, Type.FOOT) * A_FOOT_PAD;
    }

    /** 天气详情页「吸顶标题 + 页脚」占掉的高度（不含两处间距） */
    public static float weatherChromeH(float font) {
        return Type.line(font, Type.NOTE) * W_HEAD_PAD
                + Type.line(font, Type.FOOT) * W_FOOT_PAD;
    }

    /**
     * 卡片「固定部分」的高度：与当天内容量无关的那块（内边距 + 标题 + 网格）。
     *
     * <p>内容驱动的那部分（宜忌词条换了几行、某行的值太长折了行）由各页加在返回值之上。
     *
     * @param id     卡片 id
     * @param font   正文字号
     * @param padEm  单边内边距（黄历页传 {@link #PAD_A}，天气页传 {@link #PAD_W}）
     * @param rows   网格行数（非网格卡忽略）
     */
    public static float chromeH(int id, float font, float padEm, int rows) {
        int r = rows > 0 ? rows : 1;
        float padH = padEm * font * 2f;
        switch (id) {
            case A_YI:
            case A_JI:
                // 徽标方块行 + 与词条之间的间隙
                return padH + yijiBadgeH(font) + font * 0.45f;
            case A_META:
                return padH + cardTitleH(font) + Type.lines(font, Type.BODY, 4);
            case A_SHEN:
                return padH + cardTitleH(font) + Type.lines(font, Type.LABEL, 2);
            case A_HOUR:
                return padH + cardTitleH(font) + hourCellH(font) * r;
            case W_NOW:
                return padH + Type.stack(font, Type.HERO, Type.HEAD, Type.NOTE);
            case W_METRIC:
                return padH + metricCellH(font) * r;
            case W_HOUR:
                return padH + Type.line(font, Type.HEAD) + hourChartH(font) + hourAxisH(font);
            case W_DAY:
                return padH + Type.line(font, Type.HEAD) + dayRowH(font) * r;
            case W_AIR:
                return padH + airHeadH(font) + airPollH(font) * (POLL_CELLS / 2);
            case W_LIFE:
                return padH + cardTitleH(font) + lifeCellH(font) * r;
            default:
                return padH + Type.line(font, Type.BODY);
        }
    }
}
