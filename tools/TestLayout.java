import com.vincent.wallclock.gen.Cards;
import com.vincent.wallclock.gen.Layout;
import com.vincent.wallclock.gen.Type;

/**
 * 版式算术桌面回归测试。
 *
 * <p>这一版测的是<b>新模型</b>：副页字号由 {@link Layout#pageFont} 定死（只取决于屏幕短边
 * 与档位，与当天有多少条宜忌无关），卡片按内容取自然高度，超出部分上下滑。
 *
 * <p>重点验证三件事：
 * <ol>
 *   <li><b>字号稳定</b>：同一块屏同一档位，字号恒定；三档严格有序；老人模式严格更大。
 *       这是「字越多就越小」那个 bug 的回归护栏。</li>
 *   <li><b>滚动范围正确</b>：内容比视口矮时不许滚，比视口高多少就能滚多少，
 *       滚动位置永远夹在 [0, range]。</li>
 *   <li><b>分档不重不漏</b>：横屏左右两列拼起来正好是该档位的全部卡片，
 *       精简 ⊂ 自动 ⊂ 完整，宜忌在任何档位都保留。</li>
 * </ol>
 *
 * <p>注意：真机尺寸那一节里的「页高比例」是<b>刻意复制</b>自 AlmanacPage /
 * WeatherDetailPage 的常量，因为那些代码依赖 android.graphics 无法在桌面加载。
 * 字号与网格列数则直接引用 {@link Layout} / {@link Cards}，不给漂移留空间。
 */
public class TestLayout {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean cond) {
        if (cond) {
            passed++;
        } else {
            failed++;
            System.out.println("  [FAIL] " + label);
        }
    }

    static void checkInt(String label, int got, int want) {
        if (got == want) {
            passed++;
        } else {
            failed++;
            System.out.println("  [FAIL] " + label + "  得到=" + got + "  期望=" + want);
        }
    }

    public static void main(String[] args) {
        System.out.println("=== 版式算术回归测试 ===\n");

        testSplitContract();
        testSplitDegenerate();
        testClampAndMod();
        testReadableFloor();
        testAllocateContract();
        testAllocateDegenerate();
        testPageFont();
        testScroll();
        testCardMembers();
        testGrids();
        testRealScreens();
        testPaging();
        testFloorGuard();
        testTypeLadder();
        testCardChrome();

        System.out.println();
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        System.out.println(failed == 0 ? "全部通过" : "存在失败项");
        if (failed != 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ split 契约

    static void testSplitContract() {
        System.out.println("1) split 契约");
        float[] weights = {1f, 2f, 3f, 4f};
        int cases = 0;
        for (float total = 4f; total <= 3000f; total += 7.3f) {
            for (float gap = 0f; gap <= 40f; gap += 1.7f) {
                float[] out = Layout.split(total, gap, weights);
                cases++;
                if (out == null) {
                    // 只在真的放不下时才允许 null
                    checkTrue("total=" + total + " gap=" + gap + " 不该为 null",
                            total - gap * 3f <= 1f);
                    continue;
                }
                checkTrue("split 段数", out.length == 4);
                float sum = 0f;
                boolean ok = true;
                for (float v : out) {
                    if (Float.isNaN(v) || v < 0f) {
                        ok = false;
                    }
                    sum += v;
                }
                checkTrue("split 非负且非 NaN", ok);
                checkTrue("split 总长守恒 total=" + total + " gap=" + gap,
                        Math.abs(sum + gap * 3f - total) < 0.01f);
            }
        }
        System.out.println("   已覆盖 " + cases + " 组参数");
    }

    static void testSplitDegenerate() {
        System.out.println("2) split 退化输入");
        checkTrue("空权重", Layout.split(100f, 4f, new float[0]) == null);
        checkTrue("null 权重", Layout.split(100f, 4f, null) == null);
        checkTrue("零权重", Layout.split(100f, 4f, new float[]{0f, 0f}) == null);
        checkTrue("负权重", Layout.split(100f, 4f, new float[]{-1f, 2f}) == null);
        checkTrue("NaN 权重", Layout.split(100f, 4f, new float[]{1f, Float.NaN}) == null);
        checkTrue("总高为 0", Layout.split(0f, 4f, new float[]{1f, 2f}) == null);
        // 可用空间 = total - gap*(n-1)，这里要压到 <= 1 才算真的放不下
        checkTrue("gap 吃掉全部", Layout.split(10f, 9.5f, new float[]{1f, 2f}) == null);
        checkTrue("gap 刚好吃平", Layout.split(10f, 4.5f, new float[]{1f, 2f}) != null);
        float[] one = Layout.split(100f, 4f, new float[]{1f});
        checkTrue("单段取全长", one != null && Math.abs(one[0] - 100f) < 0.001f);
    }

    // ------------------------------------------------------------------ clamp / mod

    static void testClampAndMod() {
        System.out.println("3) clamp / mod");
        checkTrue("clamp 下限", Layout.clamp(-5f, 0f, 10f) == 0f);
        checkTrue("clamp 上限", Layout.clamp(50f, 0f, 10f) == 10f);
        checkTrue("clamp 区间内", Layout.clamp(5f, 0f, 10f) == 5f);
        checkTrue("clamp(int) 下限", Layout.clamp(-3, 0, 9) == 0);
        checkTrue("clamp(int) 上限", Layout.clamp(30, 0, 9) == 9);
        checkTrue("clamp01 负", Layout.clamp01(-1f) == 0f);
        checkTrue("clamp01 超 1", Layout.clamp01(3f) == 1f);
        checkTrue("clamp01 NaN", Layout.clamp01(Float.NaN) == 0f);
        checkTrue("mod 正数", Layout.mod(7, 3) == 1);
        checkTrue("mod 负数", Layout.mod(-1, 3) == 2);
        checkTrue("mod 零除数", Layout.mod(5, 0) == 0);
    }

    // ------------------------------------------------------------------ 主页面可读下限

    static void testReadableFloor() {
        System.out.println("4) 可读字号下限 readableFloor");
        float[] sides = {240f, 320f, 360f, 480f, 540f, 720f, 1080f, 1440f, 2160f};
        for (float side : sides) {
            float f = Layout.readableFloor(side, 1f);
            checkTrue("下限不低于绝对兜底 side=" + side, f >= Layout.MIN_FONT);
            checkTrue("下限不高于短边的一半 side=" + side, f <= side * 0.5f);
            checkTrue("老人模式更大 side=" + side,
                    Layout.readableFloor(side, Layout.ELDER_SCALE) > f);
        }
        checkTrue("短边为 0 取兜底", Layout.readableFloor(0f, 1f) == Layout.MIN_FONT);
        checkTrue("短边为负取兜底", Layout.readableFloor(-100f, 1f) == Layout.MIN_FONT);
        checkTrue("短边 NaN 取兜底", Layout.readableFloor(Float.NaN, 1f) == Layout.MIN_FONT);
        checkTrue("倍率为 0 按 1 处理",
                Math.abs(Layout.readableFloor(500f, 0f) - Layout.readableFloor(500f, 1f)) < 1e-4f);
        checkTrue("倍率 NaN 按 1 处理",
                Math.abs(Layout.readableFloor(500f, Float.NaN) - Layout.readableFloor(500f, 1f)) < 1e-4f);
    }

    // ------------------------------------------------------------------ allocate

    static void testAllocateContract() {
        System.out.println("5) allocate 契约（自适应高度分配）");
        int cases = 0;
        float[] grows = {0f, 1f, 0.5f};
        for (float total = 10f; total <= 2000f; total += 11.7f) {
            for (float gap = 0f; gap <= 30f; gap += 2.3f) {
                for (float g : grows) {
                    float[] need = {total * 0.10f, total * 0.20f, total * 0.15f};
                    float[] grow = {g, 1f, 0.3f};
                    float[] out = Layout.allocate(total, gap, need, grow);
                    cases++;
                    if (out == null) {
                        checkTrue("allocate 不该为 null total=" + total, false);
                        continue;
                    }
                    checkTrue("allocate 段数", out.length == 3);
                    float sum = 0f;
                    boolean ok = true;
                    for (float v : out) {
                        if (Float.isNaN(v) || v < 0f) {
                            ok = false;
                        }
                        sum += v;
                    }
                    checkTrue("allocate 非负非 NaN", ok);
                    // 内容高度任何情况下都不能超出总高
                    checkTrue("allocate 内容不超总量 total=" + total + " gap=" + gap,
                            sum <= total + 0.5f);
                    // 空间够时才要求「正好分完」：空间不足时 allocate 会按顺序满足、
                    // 后面的块直接给 0，这时并不会预留全部 (n-1) 个间隙
                    boolean enough = need[0] + need[1] + need[2] + gap * 2f <= total;
                    if (enough) {
                        checkTrue("allocate 空间够时分完 total=" + total + " gap=" + gap,
                                Math.abs(sum + gap * 2f - total) < 0.5f);
                    }
                }
            }
        }
        System.out.println("   已覆盖 " + cases + " 组参数");
    }

    static void testAllocateDegenerate() {
        System.out.println("6) allocate 退化输入");
        checkTrue("空 need", Layout.allocate(100f, 0f, new float[0], new float[0]) == null);
        checkTrue("null need", Layout.allocate(100f, 0f, null, null) == null);
        checkTrue("total 为 0", Layout.allocate(0f, 0f, new float[]{1f}, new float[]{1f}) == null);
        checkTrue("total 为负", Layout.allocate(-10f, 0f, new float[]{1f}, new float[]{1f}) == null);
        checkTrue("total NaN", Layout.allocate(Float.NaN, 0f, new float[]{1f}, new float[]{1f}) == null);
        float[] noGrow = Layout.allocate(100f, 0f, new float[]{10f, 10f}, new float[]{0f, 0f});
        checkTrue("无人弹性时保留最小值", noGrow != null && noGrow[0] == 10f && noGrow[1] == 10f);
    }

    // ------------------------------------------------------------------ 副页固定字号

    static void testPageFont() {
        System.out.println("7) 副页固定字号 pageFont（与内容量无关）");
        float[][][] screens = {
                {{320, 480}, {480, 320}},
                {{720, 1280}, {1280, 720}},
                {{1080, 1920}, {1920, 1080}},
                {{1080, 2340}, {2340, 1080}},
                {{1440, 2560}, {2560, 1440}},
                {{1200, 1920}, {1920, 1200}},
                {{800, 1280}, {1280, 800}},
                {{540, 960}, {960, 540}},
        };
        int cases = 0;
        for (float[][] s : screens) {
            for (int k = 0; k < 2; k++) {
                float w = s[k][0];
                float h = s[k][1];
                for (int d = 0; d < 3; d++) {
                    for (int e = 0; e < 2; e++) {
                        float scale = e == 0 ? 1f : Layout.ELDER_SCALE;
                        float f = Layout.pageFont(w, h, d, scale);
                        cases++;
                        checkTrue("字号不低于绝对兜底", f >= Layout.MIN_FONT);
                        checkTrue("字号不高于短边", f <= Math.min(w, h));
                        // 同一块屏同一档位，字号是稳定值：连算两次必须一样
                        checkTrue("同参数字号稳定",
                                f == Layout.pageFont(w, h, d, scale));
                        // 字号 = min(短边 × 档位比例 × 老人系数, 页高上限)。
                        // 横竖屏交换后短边不变、页高变了，所以字号可以不同——这是设计如此：
                        // 横屏页高小，吸顶标题 + 页脚这些按字号定高的固定区必须给正文留够地方。
                        float want = Math.min(Math.min(w, h) * Layout.PAGE_FONT_PCT[d] * scale,
                                h * Layout.MAX_PAGE_FONT_OF_H
                                        * (Layout.PAGE_FONT_PCT[d] / Layout.PAGE_FONT_PCT[0]));
                        checkTrue("字号 = min(短边比例, 页高上限)",
                                Math.abs(f - Math.max(want, Layout.MIN_FONT)) < 1e-3f);
                        checkTrue("字号不超过页高上限",
                                f <= h * Layout.MAX_PAGE_FONT_OF_H + 0.01f);
                        if (e == 1) {
                            checkTrue("老人模式更大",
                                    f > Layout.pageFont(w, h, d, 1f));
                        }
                    }
                }
                float slim = Layout.pageFont(w, h, 0, 1f);
                float auto = Layout.pageFont(w, h, 1, 1f);
                float full = Layout.pageFont(w, h, 2, 1f);
                checkTrue("精简 > 自动 > 完整", slim > auto && auto > full);
                // 三档之间的比值是固定的，与屏幕无关
                checkTrue("档位比值恒定 r1",
                        Math.abs(slim / auto - Layout.PAGE_FONT_PCT[0] / Layout.PAGE_FONT_PCT[1]) < 1e-3f);
                checkTrue("档位比值恒定 r2",
                        Math.abs(auto / full - Layout.PAGE_FONT_PCT[1] / Layout.PAGE_FONT_PCT[2]) < 1e-3f);
            }
        }
        // 退化输入
        checkTrue("短边为 0 取兜底", Layout.pageFont(0f, 0f, 1, 1f) == Layout.MIN_FONT);
        checkTrue("尺寸 NaN 取兜底",
                Layout.pageFont(Float.NaN, 1000f, 1, 1f) == Layout.MIN_FONT);
        checkTrue("档位越界按自动",
                Layout.pageFont(1080f, 1920f, 99, 1f) == Layout.pageFont(1080f, 1920f, 1, 1f));
        checkTrue("档位为负按自动",
                Layout.pageFont(1080f, 1920f, -1, 1f) == Layout.pageFont(1080f, 1920f, 1, 1f));
        checkTrue("倍率 NaN 按 1 处理",
                Math.abs(Layout.pageFont(1080f, 1920f, 1, Float.NaN)
                        - Layout.pageFont(1080f, 1920f, 1, 1f)) < 1e-4f);
        System.out.println("   已覆盖 " + cases + " 组参数");
    }

    // ------------------------------------------------------------------ 滚动

    static void testScroll() {
        System.out.println("8) 滚动范围 scrollRange / clampScroll");
        int cases = 0;
        for (float content = 0f; content <= 3000f; content += 13.7f) {
            for (float view = 0f; view <= 3000f; view += 17.3f) {
                float r = Layout.scrollRange(content, view);
                cases++;
                checkTrue("滚动距离非负", r >= 0f);
                checkTrue("滚动距离不产生 NaN", !Float.isNaN(r));
                if (content <= view) {
                    checkTrue("内容不比视口高就不许滚 c=" + content + " v=" + view, r == 0f);
                } else {
                    checkTrue("滚动距离 = 高出部分", Math.abs(r - (content - view)) < 0.01f);
                }
                // 任何输入下滚动位置都被夹住
                for (float y = -500f; y <= 3500f; y += 211.7f) {
                    float c = Layout.clampScroll(y, r);
                    checkTrue("滚动位置夹在区间内", c >= 0f && c <= Math.max(r, 0f));
                }
            }
        }
        checkTrue("内容 NaN 不许滚", Layout.scrollRange(Float.NaN, 100f) == 0f);
        checkTrue("视口 NaN 不许滚", Layout.scrollRange(100f, Float.NaN) == 0f);
        checkTrue("位置 NaN 归零", Layout.clampScroll(Float.NaN, 100f) == 0f);
        checkTrue("距离为 0 时位置归零", Layout.clampScroll(50f, 0f) == 0f);
        checkTrue("距离为负时位置归零", Layout.clampScroll(50f, -10f) == 0f);
        checkTrue("距离为 NaN 时位置归零", Layout.clampScroll(50f, Float.NaN) == 0f);
        System.out.println("   已覆盖 " + cases + " 组参数");
    }

    // ------------------------------------------------------------------ 卡片分档

    static void testCardMembers() {
        System.out.println("9) 卡片分档 members（不重不漏）");

        // 竖屏：精简 ⊂ 自动 ⊂ 完整
        for (int d = 0; d < 3; d++) {
            int[] m = Cards.members(Cards.A_PORTRAIT, d);
            checkTrue("黄历竖屏档位非空 d=" + d, m.length > 0);
            checkTrue("黄历精简档必有宜", contains(m, Cards.A_YI));
            checkTrue("黄历任何档位都有宜忌", contains(m, Cards.A_YI) && contains(m, Cards.A_JI));
            checkTrue("黄历档位内不重复 d=" + d, !hasDup(m));
            checkTrue("黄历卡片 id 合法 d=" + d, allInRange(m, Cards.A_COUNT));
        }
        checkTrue("黄历精简 ⊂ 自动",
                isSubset(Cards.members(Cards.A_PORTRAIT, 0), Cards.members(Cards.A_PORTRAIT, 1)));
        checkTrue("黄历自动 ⊂ 完整",
                isSubset(Cards.members(Cards.A_PORTRAIT, 1), Cards.members(Cards.A_PORTRAIT, 2)));

        for (int d = 0; d < 3; d++) {
            int[] m = Cards.members(Cards.W_PORTRAIT, d);
            checkTrue("天气竖屏档位非空 d=" + d, m.length > 0);
            checkTrue("天气任何档位都有概况", contains(m, Cards.W_NOW));
            checkTrue("天气档位内不重复 d=" + d, !hasDup(m));
            checkTrue("天气卡片 id 合法 d=" + d,
                    allInRange(m, Cards.W_BASE, Cards.W_BASE + Cards.W_COUNT));
        }
        checkTrue("天气精简 ⊂ 自动",
                isSubset(Cards.members(Cards.W_PORTRAIT, 0), Cards.members(Cards.W_PORTRAIT, 1)));
        checkTrue("天气自动 ⊂ 完整",
                isSubset(Cards.members(Cards.W_PORTRAIT, 1), Cards.members(Cards.W_PORTRAIT, 2)));

        // 横屏：左列 + 右列 == 该档位全部卡片，且不重不漏
        for (int d = 0; d < 3; d++) {
            int[] l = Cards.members(Cards.A_LAND_L, d);
            int[] r = Cards.members(Cards.A_LAND_R, d);
            checkTrue("黄历横屏左列非空 d=" + d, l.length > 0);
            checkTrue("黄历横屏右列非空 d=" + d, r.length > 0);
            checkTrue("黄历横屏左右不重叠 d=" + d, !overlap(l, r));
            checkTrue("黄历横屏左右合计 = 竖屏全集 d=" + d,
                    sameSet(concat(l, r), Cards.members(Cards.A_PORTRAIT, d)));

            int[] wl = Cards.members(Cards.W_LAND_L, d);
            int[] wr = Cards.members(Cards.W_LAND_R, d);
            checkTrue("天气横屏左列非空 d=" + d, wl.length > 0);
            checkTrue("天气横屏右列非空 d=" + d, wr.length > 0);
            checkTrue("天气横屏左右不重叠 d=" + d, !overlap(wl, wr));
            checkTrue("天气横屏左右合计 = 竖屏全集 d=" + d,
                    sameSet(concat(wl, wr), Cards.members(Cards.W_PORTRAIT, d)));
        }
        checkTrue("members 档位越界按自动",
                Cards.members(Cards.A_PORTRAIT, 99) == Cards.members(Cards.A_PORTRAIT, 1));
    }

    // ------------------------------------------------------------------ 网格

    static void testGrids() {
        System.out.println("10) 网格列数 hourGrid / lifeGrid / metricGrid");
        int cases = 0;
        for (float font = 8f; font <= 90f; font += 1.3f) {
            for (float w = 60f; w <= 2000f; w += 17.1f) {
                int[] hg = Cards.hourGrid(w, font);
                cases++;
                checkTrue("时辰列数在范围内", hg[0] >= 2 && hg[0] <= 12);
                checkTrue("时辰格子放得下 12 项", hg[0] * hg[1] >= Cards.HOUR_CELLS);
                checkTrue("时辰行数合理", hg[1] >= 1 && hg[1] <= Cards.HOUR_CELLS);

                int[] lg = Cards.lifeGrid(w, font);
                checkTrue("生活指数列数在范围内", lg[0] >= 1 && lg[0] <= 4);
                checkTrue("生活指数格子放得下 8 项", lg[0] * lg[1] >= Cards.LIFE_CELLS);

                int[] mg = Cards.metricGrid(w, font);
                checkTrue("指标列数在范围内", mg[0] >= 1 && mg[0] <= 5);
                checkTrue("指标格子放得下 10 项", mg[0] * mg[1] >= Cards.METRIC_CELLS);
            }
        }
        // 字号越大列数越少（同样宽度下）
        float w = 900f;
        checkTrue("字号变大 → 时辰列数不增",
                Cards.hourGrid(w, 60f)[0] <= Cards.hourGrid(w, 20f)[0]);
        checkTrue("宽度变大 → 时辰列数不减",
                Cards.hourGrid(1800f, 30f)[0] >= Cards.hourGrid(400f, 30f)[0]);
        // 退化输入不崩
        checkTrue("零宽度不崩", Cards.hourGrid(0f, 20f)[0] >= 2);
        checkTrue("零字号不崩", Cards.hourGrid(900f, 0f)[0] >= 2);
        System.out.println("   已覆盖 " + cases + " 组参数");
    }

    // ------------------------------------------------------------------ 真机尺寸

    static void testRealScreens() {
        System.out.println("11) 真机尺寸模拟");
        float[][] sizes = {
                {320, 480}, {480, 320}, {480, 800}, {800, 480},
                {540, 960}, {960, 540}, {720, 1280}, {1280, 720},
                {1080, 1920}, {1920, 1080}, {1080, 2340}, {2340, 1080},
                {1440, 2560}, {2560, 1440}, {1200, 1920}, {1920, 1200},
        };
        // 副页的吸顶标题 / 徽标行 / 页脚高度现在都由**字号**定，且口径直接从 Cards 取，
        // 不再手抄一份常量（手抄正是上一版「量高与绘制漂移」的来源）。
        // 这三块曾经是屏幕百分比，和「字号按短边定」互相独立 → 横屏或大字号时塞不下，
        // 文字就压成一团。改成字号驱动之后，这条断言就是在守那个 bug 不再回来。
        float chromeEm = Cards.almanacChromeH(1f) + Cards.A_GAP_EM * 2f;
        int cases = 0;
        for (float[] s : sizes) {
            float w = s[0];
            float h = s[1];
            for (int d = 0; d < 3; d++) {
                for (int e = 0; e < 2; e++) {
                    float scale = e == 0 ? 1f : Layout.ELDER_SCALE;
                    float font = Layout.pageFont(w, h, d, scale);
                    float viewH = h - font * chromeEm;
                    cases++;
                    checkTrue("固定区不能吃掉整屏 w=" + w + " h=" + h + " d=" + d + " e=" + e
                                    + " viewH=" + (int) viewH,
                            viewH > h * 0.45f);
                    // 一行正文按 1.2 倍字号算，一屏至少要放得下 6 行，
                    // 否则说明字号大到「一屏只有三四行」，得往下调
                    float lines = viewH / (font * 1.20f);
                    checkTrue("一屏至少 6 行正文 w=" + w + " h=" + h + " d=" + d + " e=" + e
                                    + " 实际=" + (int) lines,
                            lines >= 6f);
                    // 滚动距离在任何档位下都不该是 NaN
                    float r = Layout.scrollRange(viewH * 2.5f, viewH);
                    checkTrue("滚动距离非 NaN", !Float.isNaN(r) && r >= 0f);
                }
            }
        }
        System.out.println("   已覆盖 " + sizes.length + " 种真实分辨率（横竖各半）");
        System.out.println("   已覆盖 " + cases + " 组参数");
    }

    // ------------------------------------------------------------------ 整页翻页

    static void testPaging() {
        System.out.println("12) 整页翻页锚点");

        // cardTops：第一张卡顶部恒为 0，相邻两张差 = 前一张高 + 间距
        float[] hs = {120f, 80f, 200f};
        float[] tops = Layout.cardTops(hs, 16f);
        checkInt("cardTops 长度", tops.length, 3);
        checkTrue("cardTops 首项为 0", Math.abs(tops[0]) < 0.001f);
        checkTrue("cardTops 第二项", Math.abs(tops[1] - 136f) < 0.001f);
        checkTrue("cardTops 第三项", Math.abs(tops[2] - 232f) < 0.001f);
        checkInt("cardTops 空数组", Layout.cardTops(new float[0], 10f).length, 0);
        checkInt("cardTops null", Layout.cardTops(null, 10f).length, 0);
        float[] messy = Layout.cardTops(new float[]{50f, -20f, Float.NaN}, 10f);
        checkInt("cardTops 脏数据不崩", messy.length, 3);
        for (int i = 1; i < messy.length; i++) {
            checkTrue("cardTops 仍单调不减 @" + i, messy[i] >= messy[i - 1]);
        }

        // mergeAnchors：两列合并后升序去重，末尾补内容总高
        float[] a = {0f, 150f, 330f};
        float[] b = {0f, 90f, 150f, 400f};
        float[] mg = Layout.mergeAnchors(a, b, 500f);
        checkTrue("mergeAnchors 非空", mg.length >= 4);
        for (int i = 1; i < mg.length; i++) {
            checkTrue("mergeAnchors 升序 @" + i, mg[i] > mg[i - 1]);
        }
        checkTrue("mergeAnchors 去重（150 只出现一次）", !hasNearDup(mg, 0.5f));
        checkTrue("mergeAnchors 末尾是内容总高",
                Math.abs(mg[mg.length - 1] - 500f) < 0.001f);
        float[] one = Layout.mergeAnchors(a, null, 330f);
        checkTrue("mergeAnchors 单列也成立", one.length == 3
                && Math.abs(one[2] - 330f) < 0.001f);
        checkTrue("mergeAnchors 双空只剩总高",
                Layout.mergeAnchors(null, null, 0f).length == 1);
        checkTrue("mergeAnchors NaN 总高不崩",
                Layout.mergeAnchors(a, null, Float.NaN).length >= 1);

        // pageStops：首项 0、末项 range、严格递增
        checkTrue("range 为 0 时只有一页", Layout.pageStops(a, 300f, 0f).length == 1);
        float[] none = Layout.pageStops(null, 300f, 500f);
        checkTrue("没有锚点时也能到底",
                none.length == 2 && Math.abs(none[1] - 500f) < 0.001f);

        float[][] topsSets = {
                {0f, 150f, 330f, 500f},
                {0f, 40f, 80f, 120f, 160f, 200f},
                {0f, 400f, 800f, 1200f},
        };
        float[] viewHs = {120f, 240f, 400f, 900f};
        float[] ranges = {50f, 200f, 380f, 700f, 1000f};
        int cases = 0;
        for (float[] ts : topsSets) {
            for (float vh : viewHs) {
                for (float rg : ranges) {
                    float[] st = Layout.pageStops(ts, vh, rg);
                    cases++;
                    checkTrue("页起点首项为 0", Math.abs(st[0]) < 0.001f);
                    checkTrue("页起点末项能到底",
                            Math.abs(st[st.length - 1] - rg) < 0.001f);
                    for (int i = 1; i < st.length; i++) {
                        checkTrue("页起点严格递增 @" + i, st[i] - st[i - 1] > 0.5f);
                        checkTrue("页起点不超范围 @" + i, st[i] >= 0f && st[i] <= rg + 0.001f);
                    }
                    checkTrue("页数不超过锚点数+2", st.length <= ts.length + 2);
                    // 每一页的起点都必须落在某个锚点上（或就是 range），
                    // 否则页顶会停在一张卡的中间
                    for (int i = 0; i < st.length; i++) {
                        checkTrue("页顶对齐到卡片 @" + i,
                                onAnchor(ts, st[i], rg));
                    }
                }
            }
        }
        // 退化输入不崩
        checkTrue("NaN range", Layout.pageStops(topsSets[0], 300f, Float.NaN).length == 1);
        checkTrue("viewH 为 0", Layout.pageStops(topsSets[0], 0f, 500f).length == 2);
        System.out.println("   已覆盖 " + cases + " 组参数");
    }

    // ------------------------------------------------------------------ 可读下限的护栏

    /**
     * 「文字叠起来」这个 bug 的回归护栏。
     *
     * <p>根因是两套互不相干的数字：字号下限按屏幕短边算，区块高度按版式算。
     * 一旦某块分到的高度装不下下限字号，fitSize 会在下限处停下、文字照旧那么高，
     * 于是压到下一行身上。floorFor 就是那道兜底的夹子。
     */
    static void testFloorGuard() {
        System.out.println("13) 可读下限不得顶破分配高度");
        float[] maxHs = {0f, 1f, 8f, 12f, 20f, 40f, 80f, 200f, 1000f};
        float[] mins = {0f, 4.5f, 7f, 11f, 20f, 34f, 50f, 70f, 120f, Float.NaN};
        int cases = 0;
        for (float maxH : maxHs) {
            for (float min : mins) {
                float f = Layout.floorFor(min, maxH);
                cases++;
                checkTrue("下限是正数", f > 0f && !Float.isNaN(f));
                checkTrue("不低于绝对兜底", f >= Layout.MIN_FONT - 1e-4f);
                checkTrue("不超过调用方给的下限",
                        Float.isNaN(min) || f <= Math.max(min, Layout.MIN_FONT) + 1e-4f);
                if (maxH > 0f) {
                    // 这条就是「不叠字」：用这个下限画一行，行高一定塞得进 maxH。
                    // 例外是 maxH 连绝对兜底字号那一行都放不下（比如 8px 高的区块）——
                    // 这种高度真实界面里不存在，夹子会主动放弃，否则字会缩到 0。
                    boolean gaveUp = !(maxH / Layout.LINE_RATIO > Layout.MIN_FONT);
                    float lineH = f * Layout.LINE_RATIO;
                    checkTrue("一行装得下 maxH=" + maxH + " min=" + min + " 行高=" + lineH,
                            lineH <= maxH + 0.01f || gaveUp);
                }
            }
        }
        // 高度充裕时下限原样保留（否则这道夹子会把字号普遍压小，白丢可读性）
        checkTrue("高度充裕时不压", Math.abs(Layout.floorFor(40f, 400f) - 40f) < 1e-4f);
        checkTrue("刚好放得下时不压", Math.abs(Layout.floorFor(10f, 12f) - 10f) < 1e-4f);
        checkTrue("放不下时压到 maxH/1.2",
                Math.abs(Layout.floorFor(20f, 12f) - 10f) < 1e-4f);
        System.out.println("   已覆盖 " + cases + " 组参数");
    }

    // ------------------------------------------------------------------ 字号层级表

    /**
     * 「该大的不大、该小的不小」这个毛病的回归护栏。
     *
     * <p>根因是每个字号就地拍：正文 1.00、卡片标题 0.85、主标题 0.70、页脚 0.48。
     * 卡片标题比正文还小、主标题比正文还小、页脚只比正文小一半——整页因此是平的。
     * {@code gen/Type} 把「谁比谁大、大多少」变成数字上的硬约束，这一节守的就是那张表。
     */
    static void testTypeLadder() {
        System.out.println("14) 字号层级表 Type");
        checkTrue("阶梯严格递减", Type.strictLadder());
        checkTrue("相邻两级拉开 " + Type.MIN_STEP + " 倍", Type.readableSteps());
        checkTrue("最强对比 ≥ " + Type.MIN_CONTRAST + " 倍（实际 " + Type.contrast() + "）",
                Type.enoughContrast());

        for (int i = 1; i < Type.LADDER.length; i++) {
            float hi = Type.LADDER[i - 1];
            float lo = Type.LADDER[i];
            checkTrue("层级 " + Type.NAME[i - 1] + " > " + Type.NAME[i], hi > lo);
            checkTrue("层级 " + Type.NAME[i - 1] + "/" + Type.NAME[i] + " ≥ " + Type.MIN_STEP,
                    hi / lo >= Type.MIN_STEP);
        }

        // 三条最容易被人改回去的具体契约
        checkTrue("卡片标题 > 正文（原来是 0.85 < 1.00）", Type.HEAD > Type.BODY);
        checkTrue("主标题 ≥ 正文×1.25（原来是 0.70）", Type.TITLE >= Type.BODY * 1.25f);
        checkTrue("页脚 < 注释 < 标签 < 正文",
                Type.FOOT < Type.NOTE && Type.NOTE < Type.LABEL && Type.LABEL < Type.BODY);

        int cases = 0;
        for (float font = 8f; font <= 200f; font += 3.7f) {
            for (float role : Type.LADDER) {
                float sz = Type.size(font, role);
                float ln = Type.line(font, role);
                cases++;
                checkTrue("size = font × 角色", Math.abs(sz - font * role) < 1e-4f);
                checkTrue("line = size × LINE_RATIO",
                        Math.abs(ln - sz * Layout.LINE_RATIO) < 1e-4f);
                checkTrue("行高非 NaN 且为正", !Float.isNaN(ln) && ln > 0f);
            }
        }

        // 非法输入一律退到正文，不能把整页字号搞成 0 或 NaN
        checkTrue("角色 NaN 退正文", Type.size(100f, Float.NaN) == 100f * Type.BODY);
        checkTrue("角色负值退正文", Type.size(100f, -3f) == 100f * Type.BODY);
        checkTrue("角色 0 退正文", Type.size(100f, 0f) == 100f * Type.BODY);
        checkTrue("角色无穷退正文", Type.size(100f, Float.POSITIVE_INFINITY) == 100f * Type.BODY);
        checkTrue("字号 0 不返回 NaN", !Float.isNaN(Type.line(0f, Type.HERO)));

        float font = 40f;
        checkTrue("stack = 各行之和",
                Math.abs(Type.stack(font, Type.HERO, Type.HEAD, Type.NOTE)
                        - (Type.line(font, Type.HERO) + Type.line(font, Type.HEAD)
                        + Type.line(font, Type.NOTE))) < 1e-3f);
        checkTrue("stack 无参数为 0", Type.stack(font) == 0f);
        checkTrue("lines 负数为 0", Type.lines(font, Type.BODY, -2) == 0f);
        checkTrue("levelOf 认得出各级", Type.levelOf(Type.BODY) == 3);
        checkTrue("levelOf 认不出给 -1", Type.levelOf(0.123f) == -1);
        System.out.println("   已覆盖 " + cases + " 组字号 × 角色");
        System.out.println("   对比度 HERO/FOOT = " + Type.contrast());
    }

    // ------------------------------------------------------------------ 卡片高度装得下文字

    /**
     * 「把标题调大一级、但卡片没跟着变高」这个毛病的回归护栏。
     *
     * <p>{@code Cards.textRows} 声明每张卡有哪些固定文字行、各是什么角色；
     * {@code Cards.chromeH} 算出这些行连同内边距要占多高。两者必须自洽——
     * 否则 {@code Layout.floorFor} 会把字号压回去，表现就是「该大的地方其实没大」，
     * 而且从代码上完全看不出来。
     */
    static void testCardChrome() {
        System.out.println("15) 卡片固定高度装得下它声明的文字行");
        float[][] sizes = {
                {320, 480}, {480, 800}, {720, 1280}, {1080, 1920}, {1080, 2340}, {2560, 1440},
        };
        int cases = 0;
        for (float[] s : sizes) {
            for (int k = 0; k < 2; k++) {
                float w = s[k == 0 ? 1 : 0];
                float h = s[k == 0 ? 0 : 1];
                for (int d = 0; d < 3; d++) {
                    for (int e = 0; e < 2; e++) {
                        float font = Layout.pageFont(w, h, d,
                                e == 0 ? 1f : Layout.ELDER_SCALE);
                        float iw = w * 0.55f;
                        for (int id = 0; id < Cards.A_COUNT; id++) {
                            int rows = (id == Cards.A_HOUR)
                                    ? Cards.hourGrid(iw, font)[1] : 1;
                            cases++;
                            checkChrome("黄历卡 " + id + " w=" + w + " d=" + d + " e=" + e,
                                    id, font, Cards.PAD_A, rows);
                        }
                        for (int id = Cards.W_BASE; id < Cards.W_BASE + Cards.W_COUNT; id++) {
                            int rows = 1;
                            if (id == Cards.W_METRIC) {
                                rows = Cards.metricGrid(iw, font)[1];
                            } else if (id == Cards.W_LIFE) {
                                rows = Cards.lifeGrid(iw, font)[1];
                            } else if (id == Cards.W_AIR) {
                                rows = Cards.POLL_CELLS / 2;
                            } else if (id == Cards.W_DAY) {
                                rows = Cards.DAY_ROWS;
                            }
                            cases++;
                            checkChrome("天气卡 " + id + " w=" + w + " d=" + d + " e=" + e,
                                    id, font, Cards.PAD_W, rows);
                        }
                    }
                }
            }
        }

        // 天气页的吸顶 + 页脚也必须给正文留够地方
        for (float[] s : sizes) {
            for (int k = 0; k < 2; k++) {
                float w = s[k == 0 ? 1 : 0];
                float h = s[k == 0 ? 0 : 1];
                for (int d = 0; d < 3; d++) {
                    float font = Layout.pageFont(w, h, d, 1f);
                    float view = h - Cards.weatherChromeH(font) - font * Cards.W_GAP_EM * 2f;
                    cases++;
                    checkTrue("天气页固定区不吃掉整屏 w=" + w + " h=" + h + " d=" + d
                                    + " viewH=" + (int) view, view > h * 0.55f);
                }
            }
        }
        System.out.println("   已覆盖 " + cases + " 组参数");
    }

    static void checkChrome(String tag, int id, float font, float padEm, int rows) {
        float chrome = Cards.chromeH(id, font, padEm, rows);
        float need = padEm * font * 2f + Type.stack(font, Cards.textRows(id));
        checkTrue(tag + " chromeH ≥ 内边距 + 声明的文字行（chrome=" + (int) chrome
                        + " need=" + (int) need + "）",
                chrome >= need - 0.01f);
        checkTrue(tag + " chromeH 非 NaN 且为正", !Float.isNaN(chrome) && chrome > 0f);
    }

    static boolean hasNearDup(float[] a, float eps) {
        for (int i = 1; i < a.length; i++) {
            if (a[i] - a[i - 1] <= eps) {
                return true;
            }
        }
        return false;
    }

    static boolean onAnchor(float[] anchors, float v, float range) {
        if (Math.abs(v - range) < 0.001f) {
            return true;
        }
        for (float a : anchors) {
            if (Math.abs(a - v) < 0.001f) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 小工具

    static boolean contains(int[] a, int v) {
        for (int x : a) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    static boolean hasDup(int[] a) {
        for (int i = 0; i < a.length; i++) {
            for (int j = i + 1; j < a.length; j++) {
                if (a[i] == a[j]) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean allInRange(int[] a, int count) {
        return allInRange(a, 0, count);
    }

    /** 半开区间 [lo, hi) 内的 id 才算合法（天气页 id 从 W_BASE 起编号） */
    static boolean allInRange(int[] a, int lo, int hi) {
        for (int x : a) {
            if (x < lo || x >= hi) {
                return false;
            }
        }
        return true;
    }

    static boolean overlap(int[] a, int[] b) {
        for (int x : a) {
            if (contains(b, x)) {
                return true;
            }
        }
        return false;
    }

    static boolean isSubset(int[] sub, int[] all) {
        for (int x : sub) {
            if (!contains(all, x)) {
                return false;
            }
        }
        return true;
    }

    static boolean sameSet(int[] a, int[] b) {
        return a.length == b.length && isSubset(a, b);
    }

    static int[] concat(int[] a, int[] b) {
        int[] out = new int[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
