package com.vincent.wallclock.gen;

import java.util.Arrays;

/**
 * 纯几何布局工具（不含任何 Android 依赖，可桌面单测）。
 *
 * <p>副页与主页面的版式都由这里的算术决定。之所以单独拆出来，是因为这类算术一旦出错
 * （除零、负高度、行数算少）在界面上表现为「内容没了」或「字挤出卡片」，
 * 很难靠肉眼看日志定位。放在这里就能用 {@code tools/TestLayout.java} 做穷举回归。
 */
public final class Layout {

    // ------------------------------------------------------------------ 各页版式权重
    //
    // 放在这个不依赖 Android 的类里，是为了让 tools/TestLayout.java 能直接引用
    // 真实取值做回归——如果留在各页里，测试就只能手抄一份，迟早和实现漂移。

    /** 月历页 · 宜 / 忌 */
    public static final float[] W_MONTH_YIJI = {1f, 1f};

    private Layout() {
    }

    /**
     * 按权重把总长切成 n 段，并预留 (n-1) 个 gap。
     *
     * @return 长度 n 的数组；空间不够或权重非法时返回 {@code null}，调用方应直接跳过绘制
     */
    public static float[] split(float total, float gap, float[] weights) {
        if (weights == null || weights.length == 0) {
            return null;
        }
        int n = weights.length;
        if (n == 1) {
            return total > 1f ? new float[]{total} : null;
        }
        float avail = total - gap * (n - 1);
        float sum = 0f;
        for (float x : weights) {
            if (!(x > 0f) || Float.isInfinite(x) || Float.isNaN(x)) {
                return null;
            }
            sum += x;
        }
        if (avail <= 1f || sum <= 0f) {
            return null;
        }
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = avail * weights[i] / sum;
        }
        return out;
    }

    public static float clamp01(float v) {
        if (Float.isNaN(v)) {
            return 0f;
        }
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    public static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 取整取模，负数也返回非负结果 */
    public static int mod(int a, int b) {
        if (b == 0) {
            return 0;
        }
        int r = a % b;
        return r < 0 ? r + b : r;
    }

    // ------------------------------------------------------------------ 字号下限
    //
    // 主页面仍然走「先量宽高再缩放字号」，但缩放有下限：装不下就截断，不缩到看不见。

    /** 可读字号下限占屏幕短边的比例（约等于 360dp 屏上的 11sp） */
    public static final float READABLE_PCT = 0.032f;
    /** 绝对兜底字号，防止极小分辨率下算出 0 */
    public static final float MIN_FONT = 7f;
    /** 老人模式字号放大系数 */
    public static final float ELDER_SCALE = 1.35f;

    /**
     * 可读下限：主页面任何一处文字都不该小于屏幕短边的 {@link #READABLE_PCT}。
     *
     * @param scale 字号档位，老人模式传 {@link #ELDER_SCALE}
     */
    public static float readableFloor(float shortSide, float scale) {
        float k = (scale > 0f && !Float.isNaN(scale) && !Float.isInfinite(scale)) ? scale : 1f;
        float f = shortSide * READABLE_PCT * k;
        if (Float.isNaN(f) || !(f > MIN_FONT)) {
            return MIN_FONT;
        }
        return f;
    }

    // ------------------------------------------------------------------ 副页固定字号
    //
    // 副页曾经是「每张卡先分到一屏高度的一份，再把文字 fit 进这个高度」，
    // 于是卡片越多、宜忌词条越多，字就越小——同一块屏上今天和明天字号都不一样。
    // 现在反过来：字号只由屏幕短边和档位正推出来，内容多就让卡片变高、页面变长，
    // 超出部分上下滑。字号因此是稳定值，不再随当天内容抖动。

    /** 副页正文固定字号占屏幕短边的比例：精简 / 自动 / 完整 */
    public static final float[] PAGE_FONT_PCT = {0.052f, 0.044f, 0.038f};

    /**
     * 副页字号相对**页高**的上限。
     *
     * <p>吸顶标题、徽标行、页脚的高度现在都由字号决定（合计约 8.9em）。
     * 横屏时短边就是页高，字号按短边算出来会到 7%×页高，光这些固定区就吃掉六成屏。
     * 夹一道之后：固定区最多占页高的一半，剩下的永远够放正文。
     * 这个上限同样只跟屏幕尺寸和档位有关，<b>与当天有多少条内容无关</b>，
     * 所以不会把「字随内容变大小」那个毛病放回来。
     *
     * <p>注意上限是「精简档」的值，其它两档按 {@link #PAGE_FONT_PCT} 同比缩小——
     * 否则横屏下会出现「自动档被夹住、精简档没被夹住 → 自动比精简还大」这种顺序反转。
     */
    public static final float MAX_PAGE_FONT_OF_H = 0.056f;

    /**
     * 副页正文的固定字号。
     *
     * <p>只由「屏幕短边 + 档位 + 老人模式」决定，与当天有多少条宜忌、
     * 当前显示了几张卡<b>完全无关</b>。360dp 的手机上三档大约对应
     * 17dp / 15dp / 13dp，老人模式再放大 1.35 倍。
     *
     * @param detail 0=精简 1=自动 2=完整（越界按「自动」处理）
     * @param scale  字号倍率，老人模式传 {@link #ELDER_SCALE}，否则传 1
     */
    public static float pageFont(float w, float h, int detail, float scale) {
        float shortSide = Math.min(w, h);
        if (Float.isNaN(shortSide) || !(shortSide > 0f)) {
            return MIN_FONT;
        }
        int d = (detail >= 0 && detail < PAGE_FONT_PCT.length) ? detail : 1;
        float k = (scale > 0f && !Float.isNaN(scale) && !Float.isInfinite(scale)) ? scale : 1f;
        float f = shortSide * PAGE_FONT_PCT[d] * k;
        if (!Float.isNaN(h) && h > 0f) {
            float cap = h * MAX_PAGE_FONT_OF_H * (PAGE_FONT_PCT[d] / PAGE_FONT_PCT[0]);
            f = Math.min(f, cap);
        }
        return (Float.isNaN(f) || !(f > MIN_FONT)) ? MIN_FONT : f;
    }

    /** 一行文字实际占高与字号的比值（{@code (descent - ascent) / textSize}） */
    public static final float LINE_RATIO = 1.20f;

    /**
     * 实际该用的字号下限：可读下限，但再夹一道「放得进分配给我的高度」。
     *
     * <p>为什么必须有这一道：可读下限是按屏幕短边算的，而各块分到的高度是按
     * 版式算的，两者互相独立。一旦某块分到的高度装不下下限字号，缩字就会在下限处
     * 停下，文字该多高还是多高 —— 表现就是<b>相邻两行互相压盖</b>。
     * 宁可这一行字小一点，也不要叠在一起。
     *
     * @param minSize 调用方给的可读下限
     * @param maxH    这一块分到的高度
     */
    public static float floorFor(float minSize, float maxH) {
        float f = (Float.isNaN(minSize) || !(minSize > MIN_FONT)) ? MIN_FONT : minSize;
        if (!Float.isNaN(maxH) && maxH > 0f) {
            float cap = maxH / LINE_RATIO;
            if (cap > MIN_FONT && f > cap) {
                f = cap;
            }
        }
        return f;
    }

    /**
     * 可滚动内容的可滚动距离：内容比视口矮时返回 0（不许滚），
     * 高多少就能滚多少。
     */
    public static float scrollRange(float contentH, float viewH) {
        if (Float.isNaN(contentH) || Float.isNaN(viewH)) {
            return 0f;
        }
        float d = contentH - viewH;
        return d > 0f ? d : 0f;
    }

    /** 把滚动位置夹进 [0, range]；参数非法返回 0 */
    public static float clampScroll(float y, float range) {
        if (Float.isNaN(y) || Float.isNaN(range) || !(range > 0f)) {
            return 0f;
        }
        return y < 0f ? 0f : (y > range ? range : y);
    }

    // ------------------------------------------------------------------ 整页翻页的锚点
    //
    // 副页上下滑改成「一页一页翻」之后，需要一个「页顶停在哪」的列表。
    // 停的位置必须是某张卡的顶部，否则每次翻页都会把一张卡切成上下两半——
    // 上半截在页尾、下半截在页头，比连续滚动还难读。

    /**
     * 每张卡顶部相对内容起点的偏移（升序，第一张恒为 0）。
     *
     * @param hs  各卡自然高度
     * @param gap 卡间距
     */
    public static float[] cardTops(float[] hs, float gap) {
        if (hs == null || hs.length == 0) {
            return new float[0];
        }
        float g = Math.max(0f, gap);
        float[] out = new float[hs.length];
        float y = 0f;
        for (int i = 0; i < hs.length; i++) {
            out[i] = y;
            float v = hs[i];
            y += (Float.isNaN(v) || v < 0f) ? 0f : v;
            y += g;
        }
        return out;
    }

    /**
     * 把（横屏时左右）两列的卡片顶部合成一份升序、去重的锚点表，末尾补上内容总高。
     *
     * <p>末尾那条是必须的：否则最后几张卡高度之和不足一屏时，会没有停靠点可以滚到底。
     */
    public static float[] mergeAnchors(float[] a, float[] b, float contentH) {
        int na = (a == null) ? 0 : a.length;
        int nb = (b == null) ? 0 : b.length;
        float[] buf = new float[na + nb];
        int n = 0;
        for (int i = 0; i < na; i++) {
            if (!Float.isNaN(a[i])) {
                buf[n++] = a[i];
            }
        }
        for (int i = 0; i < nb; i++) {
            if (!Float.isNaN(b[i])) {
                buf[n++] = b[i];
            }
        }
        Arrays.sort(buf, 0, n);
        float[] out = new float[n + 1];
        int m = 0;
        for (int i = 0; i < n; i++) {
            if (m == 0 || buf[i] - out[m - 1] > 0.5f) {
                out[m++] = buf[i];
            }
        }
        if (Float.isNaN(contentH)) {
            return Arrays.copyOf(out, m);
        }
        if (m == 0 || contentH - out[m - 1] > 0.5f) {
            out[m++] = contentH;
        }
        return Arrays.copyOf(out, m);
    }

    /**
     * 由锚点表反推「页起点」：每页从某个锚点开始，尽量装下一整屏。
     *
     * <p>最后一页直接取 {@code range}，保证一定能滚到最底下。
     *
     * @param anchors 升序锚点（末尾是内容总高）
     * @param viewH   一屏的可视高度
     * @param range   可滚动距离
     * @return 升序的页起点，第一项恒为 0，最后一项恒为 range
     */
    public static float[] pageStops(float[] anchors, float viewH, float range) {
        if (Float.isNaN(range) || !(range > 0f)) {
            return new float[]{0f};
        }
        if (anchors == null || anchors.length == 0 || !(viewH > 0f)) {
            return new float[]{0f, range};
        }
        float[] out = new float[anchors.length + 2];
        int m = 0;
        out[m++] = 0f;
        float start = 0f;
        float prev = 0f;
        for (int i = 0; i < anchors.length; i++) {
            float a = anchors[i];
            if (Float.isNaN(a) || a <= 0f) {
                continue;
            }
            if (a - start > viewH) {
                float s = Math.min(prev, range);
                if (s - out[m - 1] > 0.5f) {
                    out[m++] = s;
                    start = s;
                }
            }
            prev = a;
        }
        if (range - out[m - 1] > 0.5f) {
            out[m++] = range;
        }
        return Arrays.copyOf(out, m);
    }

    // ------------------------------------------------------------------ 高度分配

    /**
     * 按「最小高度 + 弹性权重」分配总高度。
     *
     * <p>这一条替换掉各页里写死的百分比（例如首页原来「时钟固定占剩余高度的 44%」）：
     * 每个块先声明自己最少需要多高（{@code need}），剩下的空间再按 {@code grow} 权重分，
     * 想独占剩余空间的块把 grow 给 1 就行。这样「关掉天气时钟就变大」是自然结果，
     * 而不是再补一个特例分支。
     *
     * <p>空间不够时按数组顺序满足，后面的块给 0（调用方跳过绘制），
     * 而不是把每块都压扁——宁可少显示一块，也不要全都残缺。
     *
     * @return 长度与 need 相同的实际高度；参数非法或 total&lt;=0 时返回 {@code null}
     */
    public static float[] allocate(float total, float gap, float[] need, float[] grow) {
        if (need == null || need.length == 0 || Float.isNaN(total) || !(total > 0f)) {
            return null;
        }
        int n = need.length;
        float[] gw = new float[n];
        float[] nd = new float[n];
        float sumNeed = 0f;
        for (int i = 0; i < n; i++) {
            float v = need[i];
            if (Float.isNaN(v) || v < 0f) {
                v = 0f;
            }
            nd[i] = v;
            sumNeed += v;
            float w = (grow == null || i >= grow.length) ? 0f : grow[i];
            gw[i] = (Float.isNaN(w) || w < 0f) ? 0f : w;
        }
        float g = (n > 1) ? Math.max(0f, gap) * (n - 1) : 0f;
        float[] out = new float[n];

        if (sumNeed + g > total) {
            // 空间不够：依次满足，第一个放不下的拿剩余量，后面全部归零
            float left = total;
            for (int i = 0; i < n; i++) {
                float use = (i > 0) ? Math.max(0f, gap) : 0f;
                if (left - use >= nd[i]) {
                    left -= use;
                    out[i] = nd[i];
                    left -= nd[i];
                } else {
                    float v = left - use;
                    out[i] = v > 0f ? v : 0f;
                    left = 0f;
                }
            }
            return out;
        }

        System.arraycopy(nd, 0, out, 0, n);
        float sumGrow = 0f;
        for (int i = 0; i < n; i++) {
            sumGrow += gw[i];
        }
        if (!(sumGrow > 0f)) {
            return out;                  // 谁都不弹性，剩余就是留白
        }
        float left = total - sumNeed - g;
        float given = 0f;
        int last = -1;
        for (int i = 0; i < n; i++) {
            if (gw[i] <= 0f) {
                continue;
            }
            float add = left * gw[i] / sumGrow;
            out[i] += add;
            given += add;
            last = i;
        }
        if (last >= 0) {
            out[last] += left - given;   // 收掉浮点残差，保证总和精确
        }
        return out;
    }
}
