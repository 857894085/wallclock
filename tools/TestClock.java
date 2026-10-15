import com.vincent.wallclock.gen.ClockSpec;

/**
 * 时钟样式算术层桌面回归测试。
 *
 * <p>测的是 {@link ClockSpec}——也就是「不碰 Canvas 的那部分」。为什么要单独测：
 * 七段段掩码和 5×7 点阵字模是<b>手写位图</b>，写错一位在真机上的表现只是
 * 「某个数字缺一笔」或者「某一段永远不亮」，肉眼在发光效果下极难发现，
 * 但穷举一遍是零成本的。
 *
 * <p>另外三条必须守住的性质：
 * <ol>
 *   <li><b>等步进</b>：同一串里每个数字的步进宽度必须一样，否则秒数一跳整块左右抖。</li>
 *   <li><b>放得下才算数</b>：{@link ClockSpec#fitUnit} 要么给出能塞进框的字高，要么返回 0
 *       让调用方退回现代样式——绝不允许「画出去一截」。</li>
 *   <li><b>浅色主题下必须读得清</b>：发光样式在浅色下要配浅底板 + 深色数字，
 *       如果哪天有人把浅色版的点亮色调亮了，这里会拦下来。</li>
 * </ol>
 */
public class TestClock {

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

    static void checkStr(String label, String got, String want) {
        if (got == null ? want == null : got.equals(want)) {
            passed++;
        } else {
            failed++;
            System.out.println("  [FAIL] " + label + "  得到=" + got + "  期望=" + want);
        }
    }

    static int maxChannel(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return Math.max(r, Math.max(g, b));
    }

    static int avgChannel(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return (r + g + b) / 3;
    }

    public static void main(String[] args) {
        System.out.println("=== 时钟样式算术回归测试 ===\n");

        testNorm();
        testKindAndGlow();
        testColors();
        testAdvance();
        testGlowScale();
        testText();
        testSevenSeg();
        testDotMatrix();
        testFitUnit();

        System.out.println();
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        System.out.println(failed == 0 ? "全部通过" : "存在失败项");
        if (failed != 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ 样式编号

    static void testNorm() {
        System.out.println("1) 样式编号归一化");
        for (int s = -20; s < ClockSpec.COUNT + 20; s++) {
            int n = ClockSpec.norm(s);
            checkTrue("norm(" + s + ") 越界", n >= 0 && n < ClockSpec.COUNT);
            if (s >= 0 && s < ClockSpec.COUNT) {
                checkInt("norm 应原样返回合法值 " + s, n, s);
            } else {
                checkInt("非法样式应退回现代 " + s, n, ClockSpec.MODERN);
            }
        }
        // 每种样式的名字必须非空且互不相同，否则设置页会出现两个同名选项
        for (int a = 0; a < ClockSpec.COUNT; a++) {
            checkTrue("name(" + a + ") 非空", ClockSpec.name(a) != null
                    && ClockSpec.name(a).length() > 0);
            for (int b = a + 1; b < ClockSpec.COUNT; b++) {
                checkTrue("name 重复: " + a + "/" + b,
                        !ClockSpec.name(a).equals(ClockSpec.name(b)));
            }
        }
    }

    static void testKindAndGlow() {
        System.out.println("2) 字模类型与发光属性");
        checkInt("灯丝管用七段", ClockSpec.kind(ClockSpec.NUMITRON), ClockSpec.KIND_SEVEN);
        checkInt("VFD 用七段", ClockSpec.kind(ClockSpec.VFD), ClockSpec.KIND_SEVEN);
        checkInt("点阵用点阵", ClockSpec.kind(ClockSpec.DOT), ClockSpec.KIND_DOT);
        int[] glyph = {ClockSpec.MODERN, ClockSpec.NIXIE, ClockSpec.FLIP, ClockSpec.HOLO,
                ClockSpec.MATRIX, ClockSpec.NEON, ClockSpec.CRT, ClockSpec.SCAN, ClockSpec.CYBER};
        for (int i = 0; i < glyph.length; i++) {
            checkInt("样式 " + glyph[i] + " 用字形",
                    ClockSpec.kind(glyph[i]), ClockSpec.KIND_GLYPH);
        }
        int[] glowOn = {ClockSpec.NIXIE, ClockSpec.NUMITRON, ClockSpec.VFD,
                ClockSpec.HOLO, ClockSpec.DOT, ClockSpec.MATRIX, ClockSpec.NEON,
                ClockSpec.CRT, ClockSpec.SCAN, ClockSpec.CYBER};
        for (int i = 0; i < glowOn.length; i++) {
            checkTrue("样式 " + glowOn[i] + " 应发光", ClockSpec.glows(glowOn[i]));
        }
        checkTrue("现代不发光", !ClockSpec.glows(ClockSpec.MODERN));
        checkTrue("翻页钟不发光", !ClockSpec.glows(ClockSpec.FLIP));
        for (int s = 0; s < ClockSpec.COUNT; s++) {
            checkTrue("hasBackdrop 契约 " + s,
                    ClockSpec.hasBackdrop(s) == (ClockSpec.glows(s) || s == ClockSpec.FLIP));
        }
    }

    static void testColors() {
        System.out.println("3) 配色（浅色主题必须读得清）");
        for (int s = 1; s < ClockSpec.COUNT; s++) {
            for (int d = 0; d < 2; d++) {
                boolean dark = d == 1;
                String tag = ClockSpec.name(s) + (dark ? "/深" : "/浅");

                int lit = ClockSpec.litColor(s, dark);
                checkTrue(tag + " 点亮色不透明", (lit >>> 24) == 0xFF);

                int dim = ClockSpec.dimColor(s, dark);
                int da = dim >>> 24;
                checkTrue(tag + " 暗色是半透明", da > 0 && da <= 0x40);

                int bd = ClockSpec.backdropColor(s, dark);
                checkTrue(tag + " 底板不透明", (bd >>> 24) == 0xFF);
                checkTrue(tag + " 描边不透明", (ClockSpec.frameColor(s, dark) >>> 24) == 0xFF);

                if (dark) {
                    checkTrue(tag + " 底板要够暗", avgChannel(bd) <= 0x40);
                    checkTrue(tag + " 点亮色要够亮", maxChannel(lit) >= 0xF0);
                } else {
                    // 浅底 + 深色字，这是「发光样式在浅色主题下也读得清」的关键
                    checkTrue(tag + " 底板要够浅", avgChannel(bd) >= 0xC0);
                    checkTrue(tag + " 点亮色要够深", maxChannel(lit) <= 0xC0);
                }
            }
        }
        // 翻页钟 / 灯丝管 / 点阵是「每个字符一块牌」，必须有牌面颜色
        checkTrue("翻页钟有牌面色", (ClockSpec.cardColor(ClockSpec.FLIP, true) >>> 24) == 0xFF);
        checkTrue("灯丝管有牌面色", (ClockSpec.cardColor(ClockSpec.NUMITRON, false) >>> 24) == 0xFF);
        checkTrue("点阵有牌面色", (ClockSpec.cardColor(ClockSpec.DOT, true) >>> 24) == 0xFF);
    }

    static void testAdvance() {
        System.out.println("4) 步进宽度（等宽，防秒数跳动抖动）");
        char[] chars = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', ':', ' '};
        for (int s = 0; s < ClockSpec.COUNT; s++) {
            for (int i = 0; i < chars.length; i++) {
                float a = ClockSpec.advance(chars[i], s);
                checkTrue("样式" + s + " 步进为正 " + chars[i], a > 0f);
                float r = ClockSpec.inkRatio(chars[i], s);
                checkTrue("样式" + s + " 占宽比例合法 " + chars[i], r > 0f && r <= 1f);
            }
            // 所有数字必须同宽：秒位从 9 跳回 0 时整块不许左右动
            float d0 = ClockSpec.advance('0', s);
            for (char c = '1'; c <= '9'; c++) {
                checkTrue("样式" + s + " 数字等步进 " + c, Math.abs(ClockSpec.advance(c, s) - d0) < 1e-6f);
            }
            // 冒号比数字窄；12 小时制的补位空格也要比数字窄
            checkTrue("样式" + s + " 冒号更窄", ClockSpec.advance(':', s) < d0);
            if (s != ClockSpec.MODERN) {
                checkTrue("样式" + s + " 空格更窄", ClockSpec.advance(' ', s) < d0);
            }
        }
    }

    static void testGlowScale() {
        System.out.println("5) 辉光强度");
        checkTrue("0 就是不发光", Math.abs(ClockSpec.glowScale(0)) < 1e-6f);
        checkTrue("负数夹到 0", Math.abs(ClockSpec.glowScale(-99)) < 1e-6f);
        checkTrue("满档 = 1.5 倍", Math.abs(ClockSpec.glowScale(100) - 1.5f) < 1e-6f);
        checkTrue("超档夹住", Math.abs(ClockSpec.glowScale(999) - 1.5f) < 1e-6f);
        float prev = -1f;
        for (int lv = 0; lv <= 100; lv++) {
            float g = ClockSpec.glowScale(lv);
            checkTrue("强度区间 " + lv, g >= 0f && g <= 1.5f);
            checkTrue("强度单调 " + lv, g >= prev);
            prev = g;
        }
    }

    static void testText() {
        System.out.println("6) 时间串拼法");
        int[] secs = {0, 1, 5, 9, 10, 30, 59};
        for (int hour = 0; hour < 24; hour++) {
            for (int minute = 0; minute < 60; minute++) {
                for (int k = 0; k < secs.length; k++) {
                    int second = secs[k];
                    String tag = hour + ":" + minute + ":" + second;

                    String t24 = ClockSpec.text(hour, minute, second, true, true);
                    checkStr("24h 带秒长度 " + tag, String.valueOf(t24.length()), "8");
                    checkStr("24h 带秒分隔符 " + tag, t24.substring(2, 3) + t24.substring(5, 6), "::");
                    checkStr("24h 无秒 " + tag,
                            ClockSpec.text(hour, minute, second, true, false),
                            String.format("%02d:%02d", hour, minute));
                    checkStr("24h 无上下午 " + tag, ClockSpec.ampm(hour, true), "");

                    int h12 = hour % 12;
                    if (h12 == 0) {
                        h12 = 12;
                    }
                    String t12 = ClockSpec.text(hour, minute, second, false, true);
                    checkStr("12h 带秒长度 " + tag, String.valueOf(t12.length()), "8");
                    // 首位可能是空格（七段里就是这一位不亮），去掉后应等于 12 小时值
                    checkInt("12h 小时值 " + tag, Integer.parseInt(t12.substring(0, 2).trim()), h12);
                    checkStr("12h 上下午 " + tag, ClockSpec.ampm(hour, false),
                            hour < 12 ? "上午" : "下午");

                    // 串里只能出现 0-9 / : / 空格，其它字符七段和点阵都画不出来
                    for (int i = 0; i < t24.length(); i++) {
                        char ch = t24.charAt(i);
                        checkTrue("24h 字符合法 " + tag + " @" + i,
                                (ch >= '0' && ch <= '9') || ch == ':' || ch == ' ');
                    }
                    for (int i = 0; i < t12.length(); i++) {
                        char ch = t12.charAt(i);
                        checkTrue("12h 字符合法 " + tag + " @" + i,
                                (ch >= '0' && ch <= '9') || ch == ':' || ch == ' ');
                    }
                }
            }
        }
        // 12 小时制补空格而不是补 0
        checkStr("12h 个位补空格", ClockSpec.text(9, 5, 3, false, false), " 9:05");
        checkStr("12h 零点显示 12", ClockSpec.text(0, 0, 0, false, false), "12:00");
        checkStr("12h 中午显示 12", ClockSpec.text(12, 0, 0, false, false), "12:00");
        checkStr("24h 零点显示 00", ClockSpec.text(0, 0, 0, true, false), "00:00");
    }

    static void testSevenSeg() {
        System.out.println("7) 七段字模");
        int[] want = {6, 2, 5, 5, 4, 5, 6, 3, 7, 6};
        int seen = 0;
        for (char c = '0'; c <= '9'; c++) {
            int m = ClockSpec.segMask(c);
            int d = c - '0';
            checkTrue("段掩码非零 " + c, m != 0);
            checkTrue("段掩码只用 7 位 " + c, (m & ~0x7F) == 0);
            int bits = 0;
            for (int b = 0; b < 7; b++) {
                if ((m & (1 << b)) != 0) {
                    bits++;
                }
            }
            checkInt("段数 " + c, bits, want[d]);
            seen |= m;
        }
        checkInt("七段全部用过", seen, 0x7F);
        // 关键字形逐段对一遍
        checkInt("1 = b+c",
                ClockSpec.segMask('1'), ClockSpec.SEG_B | ClockSpec.SEG_C);
        checkInt("7 = a+b+c",
                ClockSpec.segMask('7'), ClockSpec.SEG_A | ClockSpec.SEG_B | ClockSpec.SEG_C);
        checkInt("0 = 除 g 外全亮",
                ClockSpec.segMask('0'), 0x7F & ~ClockSpec.SEG_G);
        checkInt("8 = 全亮", ClockSpec.segMask('8'), 0x7F);
        // 10 个数字的掩码必须互不相同，否则会有两个数字长得一样
        for (char a = '0'; a <= '9'; a++) {
            for (char b = (char) (a + 1); b <= '9'; b++) {
                checkTrue("掩码重复 " + a + "/" + b, ClockSpec.segMask(a) != ClockSpec.segMask(b));
            }
        }
        checkInt("冒号无段", ClockSpec.segMask(':'), 0);
        checkInt("空格无段", ClockSpec.segMask(' '), 0);
        checkInt("非法字符无段", ClockSpec.segMask('A'), 0);
    }

    static void testDotMatrix() {
        System.out.println("8) 5×7 点阵字模");
        for (char c = '0'; c <= '9'; c++) {
            int[] rows = ClockSpec.dotRows(c);
            checkTrue("字模存在 " + c, rows != null);
            if (rows == null) {
                continue;
            }
            checkInt("行数 " + c, rows.length, ClockSpec.DOT_ROWS);
            int lit = 0;
            for (int r = 0; r < rows.length; r++) {
                checkTrue("行值不超 5 位 " + c + "@" + r, rows[r] >= 0 && rows[r] <= 31);
                for (int col = 0; col < ClockSpec.DOT_COLS; col++) {
                    if (ClockSpec.dotOn(rows, r, col)) {
                        lit++;
                    }
                }
            }
            checkTrue("至少点亮一个点 " + c, lit > 0);
        }
        int[] blank = ClockSpec.dotRows(' ');
        checkInt("空格行数", blank.length, ClockSpec.DOT_ROWS);
        int any = 0;
        for (int r = 0; r < blank.length; r++) {
            any |= blank[r];
        }
        checkInt("空格全灭", any, 0);

        int[] colon = ClockSpec.dotRows(':');
        checkTrue("冒号有内容", colon != null && (colon[1] != 0 || colon[2] != 0));
        checkInt("冒号首行空", colon[0], 0);

        // 10 个点阵也必须互不相同
        for (char a = '0'; a <= '9'; a++) {
            int[] ra = ClockSpec.dotRows(a);
            for (char b = (char) (a + 1); b <= '9'; b++) {
                int[] rb = ClockSpec.dotRows(b);
                boolean same = ra.length == rb.length;
                for (int r = 0; same && r < ra.length; r++) {
                    same = ra[r] == rb[r];
                }
                checkTrue("点阵重复 " + a + "/" + b, !same);
            }
        }

        checkTrue("未知字符无字模", ClockSpec.dotRows('X') == null);
        // 最高位是最左列：1 的首行 0b00100 只能在中间那一列
        int[] one = ClockSpec.dotRows('1');
        checkInt("1 首行值", one[0], 4);
        checkTrue("1 首行只有中间列", ClockSpec.dotOn(one, 0, 2)
                && !ClockSpec.dotOn(one, 0, 0) && !ClockSpec.dotOn(one, 0, 4));
        // 越界一律灭，不能崩
        checkTrue("行越界", !ClockSpec.dotOn(one, -1, 2));
        checkTrue("行越界(下)", !ClockSpec.dotOn(one, ClockSpec.DOT_ROWS, 2));
        checkTrue("列越界", !ClockSpec.dotOn(one, 0, -1));
        checkTrue("列越界(右)", !ClockSpec.dotOn(one, 0, ClockSpec.DOT_COLS));
        checkTrue("空字模", !ClockSpec.dotOn(null, 0, 0));
    }

    static void testFitUnit() {
        System.out.println("9) 字高反推（放不下就返回 0）");
        float[] ws = {180f, 240f, 360f, 480f, 720f, 1080f, 1600f};
        float[] hs = {60f, 90f, 120f, 200f, 320f, 480f};
        String[] texts = {
                ClockSpec.text(12, 34, 56, true, true),
                ClockSpec.text(9, 8, 0, true, false),
                ClockSpec.text(9, 8, 7, false, true),
        };
        for (int s = 1; s < ClockSpec.COUNT; s++) {
            for (int t = 0; t < texts.length; t++) {
                String txt = texts[t];
                float sum = ClockSpec.advanceSum(txt, s);
                checkTrue("步进和为正 样式" + s, sum > 0f);
                boolean glyph = ClockSpec.kind(s) == ClockSpec.KIND_GLYPH;
                for (int i = 0; i < ws.length; i++) {
                    for (int j = 0; j < hs.length; j++) {
                        float w = ws[i];
                        float h = hs[j];
                        float u = ClockSpec.fitUnit(txt, s, w, h, 0f);
                        String tag = "样式" + s + " " + (int) w + "x" + (int) h + " [" + txt + "]";
                        if (u <= 0f) {
                            // 返回 0 只能是因为「框真的小到连 4px 字高都放不下」
                            float room = Math.min(h * (glyph ? 0.76f : 0.88f),
                                    w * 0.98f / (sum + ClockSpec.PAD_RATIO * 2f));
                            checkTrue(tag + " 不该放弃（还有 " + room + "px）", room <= 4f);
                            continue;
                        }
                        checkTrue(tag + " 字高不超框", u <= h * (glyph ? 0.76f : 0.88f) + 0.01f);
                        // 含两侧留白也不许溢出
                        float total = sum * u + ClockSpec.PAD_RATIO * 2f * u;
                        checkTrue(tag + " 宽度溢出 " + total + ">" + w, total <= w * 0.98f + 0.5f);
                    }
                }
                // 宽度越宽，字高不能反而变小
                float prev = -1f;
                for (int i = 0; i < ws.length; i++) {
                    float u = ClockSpec.fitUnit(txt, s, ws[i], 480f, 0f);
                    checkTrue("宽度单调 样式" + s, u >= prev);
                    prev = u;
                }
            }
        }
        // 退化输入
        String t = ClockSpec.text(1, 2, 3, true, true);
        checkTrue("空串放不下", ClockSpec.fitUnit("", ClockSpec.NIXIE, 400f, 200f, 0f) <= 0f);
        checkTrue("null 放不下", ClockSpec.fitUnit(null, ClockSpec.NIXIE, 400f, 200f, 0f) <= 0f);
        checkTrue("宽度 0", ClockSpec.fitUnit(t, ClockSpec.NIXIE, 0f, 200f, 0f) <= 0f);
        checkTrue("高度 0", ClockSpec.fitUnit(t, ClockSpec.NIXIE, 400f, 0f, 0f) <= 0f);
        checkTrue("整宽都被预留", ClockSpec.fitUnit(t, ClockSpec.NIXIE, 400f, 200f, 400f) <= 0f);
        checkTrue("advanceSum(null)", ClockSpec.advanceSum(null, ClockSpec.NIXIE) == 0f);
        checkTrue("advanceSum(空串)", ClockSpec.advanceSum("", ClockSpec.NIXIE) == 0f);
    }
}
