import com.vincent.wallclock.gen.LightRule;

/**
 * 光线感应规则的桌面回归测试。
 *
 * <p>这里要守住的是三件事：
 * <ol>
 *   <li><b>单调输入最多切换一次</b>：只有一个阈值的话，光线在阈值附近抖动会让背景色反复闪。
 *       加了 3 倍滞回之后，光照从全黑一路升到很亮（或反过来）应当至多发生一次切换。</li>
 *   <li><b>恒定光照是稳定点</b>：滞回带内的抖动不能让状态翻来覆去。</li>
 *   <li><b>边界明确</b>：阈值 0 = 关闭；没有传感器读数时不干预。</li>
 * </ol>
 *
 * <p>第 6 节把 {@code Palette.shouldBeDark} 的「昼夜 OR 光线」组合逻辑抄了一份，
 * 因为那个方法吃 {@code Prefs}，而 Prefs 依赖 android.content，桌面加载不了。
 * 抄的是三行判断，测试里标注了对应关系，改动时要一起改。
 */
public class TestLightRule {

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
        System.out.println("=== 光线感应规则回归测试 ===\n");

        testClampSnap();
        testSmooth();
        testWantsDarkBoundary();
        testMonotonicSingleFlip();
        testConstantIsStable();
        testExhaustive();
        testCombineWithDayNight();

        System.out.println();
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        System.out.println(failed == 0 ? "全部通过" : "存在失败项");
        if (failed != 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ 1

    static void testClampSnap() {
        System.out.println("1) clampLux / snapLux");
        checkInt("负值夹到下限", LightRule.clampLux(-999), LightRule.LUX_MIN);
        checkInt("超上限夹住", LightRule.clampLux(99999), LightRule.LUX_MAX);
        checkInt("区间内原样", LightRule.clampLux(37), 37);

        for (int v = -50; v <= 300; v++) {
            int s = LightRule.snapLux(v);
            checkTrue("snap 结果在区间内 v=" + v, s >= LightRule.LUX_MIN && s <= LightRule.LUX_MAX);
            checkTrue("snap 结果是步长整数倍 v=" + v, (s - LightRule.LUX_MIN) % LightRule.LUX_STEP == 0);
        }
        checkInt("默认阈值本身要对齐步长",
                LightRule.snapLux(LightRule.LUX_DEFAULT), LightRule.LUX_DEFAULT);
        checkInt("0 仍然表示关闭", LightRule.snapLux(0), 0);
        System.out.println();
    }

    // ------------------------------------------------------------------ 2

    static void testSmooth() {
        System.out.println("2) smooth 平滑");
        checkTrue("上一帧未知时直接采用新读数",
                Math.abs(LightRule.smooth(LightRule.UNKNOWN, 120f) - 120f) < 1e-4f);
        checkTrue("新读数非法时保持原值",
                Math.abs(LightRule.smooth(80f, LightRule.UNKNOWN) - 80f) < 1e-4f);
        checkTrue("两边都未知仍是未知",
                LightRule.smooth(LightRule.UNKNOWN, LightRule.UNKNOWN) < 0f);
        checkTrue("alpha=0 原地不动",
                Math.abs(LightRule.smooth(50f, 200f, 0f) - 50f) < 1e-4f);
        checkTrue("alpha=1 直接采用",
                Math.abs(LightRule.smooth(50f, 200f, 1f) - 200f) < 1e-4f);

        // 恒定输入必须在若干帧内收敛到该值
        float v = LightRule.UNKNOWN;
        for (int i = 0; i < 40; i++) {
            v = LightRule.smooth(v, 300f);
        }
        checkTrue("恒定输入会收敛到真值", Math.abs(v - 300f) < 0.5f);

        // 平滑过程不能冲过目标（指数平均不会过冲）
        v = 10f;
        boolean overshoot = false;
        for (int i = 0; i < 40; i++) {
            v = LightRule.smooth(v, 100f);
            if (v > 100f + 1e-3f) {
                overshoot = true;
            }
        }
        checkTrue("不会过冲", !overshoot);
        System.out.println();
    }

    // ------------------------------------------------------------------ 3

    static void testWantsDarkBoundary() {
        System.out.println("3) 阈值边界");
        // 阈值 0 = 关闭，哪怕是全黑
        checkTrue("阈值 0 时全黑也不切", !LightRule.wantsDark(0f, 0, false));
        checkTrue("阈值 0 时全黑也不切（原状态为深色）", !LightRule.wantsDark(0f, 0, true));
        checkTrue("阈值 0 时负值也不切", !LightRule.wantsDark(-1f, 0, true));

        // 阈值 20：<20 深色，>60 浅色，中间维持
        checkTrue("低于阈值 → 深色", LightRule.wantsDark(5f, 20, false));
        checkTrue("刚好等于阈值不算低于 → 维持", !LightRule.wantsDark(20f, 20, false));
        checkTrue("明显亮 → 浅色", !LightRule.wantsDark(200f, 20, true));
        checkTrue("滞回带内且原为浅色 → 保持浅色", !LightRule.wantsDark(40f, 20, false));
        checkTrue("滞回带内且原为深色 → 保持深色", LightRule.wantsDark(40f, 20, true));

        // 没有读数
        checkTrue("无读数且原为浅色 → 不干预", !LightRule.wantsDark(LightRule.UNKNOWN, 20, false));
        checkTrue("无读数且原为深色 → 不干预", LightRule.wantsDark(LightRule.UNKNOWN, 20, true));

        // 越界阈值会被夹住
        checkTrue("阈值超上限被夹住后仍然有效", LightRule.wantsDark(5f, 99999, false));
        checkTrue("阈值负数等价于关闭", !LightRule.wantsDark(0f, -30, true));
        System.out.println();
    }

    // ------------------------------------------------------------------ 4

    static void testMonotonicSingleFlip() {
        System.out.println("4) 单调光照最多切换一次");
        int[] thresholds = {1, 5, 10, 20, 45, 60, 120};
        for (int th : thresholds) {
            // 从全黑升到很亮
            boolean state = true;      // 假设初始为深色
            int flips = 0;
            boolean prev = state;
            for (float lux = 0f; lux <= 400f; lux += 0.5f) {
                state = LightRule.wantsDark(lux, th, state);
                if (state != prev) {
                    flips++;
                    prev = state;
                }
            }
            checkTrue("上行 th=" + th + " 切换次数=" + flips, flips <= 1);
            checkTrue("上行终点应为浅色 th=" + th, !state);

            // 从很亮降到全黑
            state = false;
            flips = 0;
            prev = false;
            for (float lux = 400f; lux >= 0f; lux -= 0.5f) {
                state = LightRule.wantsDark(lux, th, state);
                if (state != prev) {
                    flips++;
                    prev = state;
                }
            }
            checkTrue("下行 th=" + th + " 切换次数=" + flips, flips <= 1);
            checkTrue("下行终点应为深色 th=" + th, state);
        }
        System.out.println();
    }

    // ------------------------------------------------------------------ 5

    static void testConstantIsStable() {
        System.out.println("5) 恒定光照 + 抖动是稳定点");
        int[] thresholds = {5, 20, 60};
        for (int th : thresholds) {
            // 在滞回带里抖动：任何起始状态都必须保持
            for (int startDark = 0; startDark < 2; startDark++) {
                boolean state = startDark == 1;
                boolean initial = state;
                int flips = 0;
                for (int i = 0; i < 2000; i++) {
                    // 在 [th, 3*th] 内来回抖
                    float lux = th + (th * 2f) * ((i % 2 == 0) ? 0.1f : 0.9f);
                    boolean next = LightRule.wantsDark(lux, th, state);
                    if (next != state) {
                        flips++;
                    }
                    state = next;
                }
                checkTrue("滞回带抖动不翻状态 th=" + th + " start=" + initial + " flips=" + flips, flips == 0);
            }

            // 全黑闪烁（有人影晃过）：偶尔跳到很亮再回来，应稳定回深色
            boolean state = false;
            for (int i = 0; i < 600; i++) {
                float lux = (i % 30 == 0) ? 200f : 2f;
                state = LightRule.wantsDark(lux, th, state);
            }
            checkTrue("全黑中偶发强光后回到深色 th=" + th, state);
        }
        System.out.println();
    }

    // ------------------------------------------------------------------ 6

    static void testExhaustive() {
        System.out.println("6) 参数穷举");
        int cases = 0;
        for (int th = LightRule.LUX_MIN; th <= LightRule.LUX_MAX; th++) {
            for (float lux = 0f; lux <= 260f; lux += 0.5f) {
                boolean d0 = LightRule.wantsDark(lux, th, false);
                boolean d1 = LightRule.wantsDark(lux, th, true);
                cases++;
                if (th == 0) {
                    checkTrue("th=0 恒不切(th=" + th + ",lux=" + lux + ")", !d0 && !d1);
                } else if (lux < th) {
                    checkTrue("低于阈值必切(th=" + th + ",lux=" + lux + ")", d0 && d1);
                } else if (lux > th * LightRule.HYSTERESIS) {
                    checkTrue("高于 3 倍阈值必不切(th=" + th + ",lux=" + lux + ")", !d0 && !d1);
                } else {
                    // 滞回带：跟随原状态
                    checkTrue("滞回带跟随原状态(th=" + th + ",lux=" + lux + ")", !d0 && d1);
                }
            }
        }
        System.out.println("   已覆盖 " + cases + " 组参数");
        System.out.println();
    }

    // ------------------------------------------------------------------ 7

    /**
     * 与 {@code Palette.shouldBeDark} 的「昼夜 OR 光线」组合对应：
     * 基准为深色则恒深色；基准为浅色时才看光线，且光线不能把深色反向压回浅色。
     */
    static boolean combine(boolean baseDark, boolean lightAuto, float lux, int th, boolean currentlyDark) {
        if (baseDark) {
            return true;
        }
        if (!lightAuto || !LightRule.usable(lux)) {
            return false;
        }
        return LightRule.wantsDark(lux, th, currentlyDark);
    }

    static void testCombineWithDayNight() {
        System.out.println("7) 昼夜与光线的组合");
        // 固定深色：无论多亮都是深色
        checkTrue("基准深色 + 很亮 仍为深色", combine(true, true, 10000f, 20, true));
        checkTrue("基准深色 + 关掉光线感应 仍为深色", combine(true, false, 0f, 20, true));

        // 基准浅色：只看光线
        checkTrue("基准浅色 + 光线暗 → 转深色", combine(false, true, 3f, 20, false));
        checkTrue("基准浅色 + 光线亮 → 保持浅色", !combine(false, true, 500f, 20, false));
        checkTrue("基准浅色 + 关掉光线感应 → 保持浅色", !combine(false, false, 0f, 20, false));
        checkTrue("基准浅色 + 无传感器 → 保持浅色", !combine(false, true, LightRule.UNKNOWN, 20, false));
        checkTrue("基准浅色 + 阈值 0 → 保持浅色", !combine(false, true, 0f, 0, false));

        // 反向不成立：光线亮不能把基准深色改成浅色
        checkTrue("光线亮不能覆盖基准深色", combine(true, true, 100000f, 20, true));
        System.out.println();
    }
}
