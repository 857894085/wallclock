import com.vincent.wallclock.LunarCalendar;
import com.vincent.wallclock.gen.LunarTables;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;

/**
 * 桌面回归测试：把 LunarCalendar 的输出逐日比对 tools/reference_lunar.csv（73018 天）。
 * 该测试不依赖 Android，用普通 javac/java 即可运行：
 *
 *   javac -encoding UTF-8 -d build src/com/vincent/wallclock/gen/LunarTables.java \
 *                                  src/com/vincent/wallclock/LunarCalendar.java tools/TestLunar.java
 *   java -cp build TestLunar tools/reference_lunar.csv
 */
public class TestLunar {

    private static int fail = 0;
    private static int total = 0;

    public static void main(String[] args) throws Exception {
        String csv = args.length > 0 ? args[0] : "tools/reference_lunar.csv";
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(csv), "UTF-8"));
        String line;
        while ((line = r.readLine()) != null) {
            if (line.length() == 0 || line.charAt(0) == '#') {
                continue;
            }
            String[] p = line.split(",");
            int sy = Integer.parseInt(p[0]);
            int sm = Integer.parseInt(p[1]);
            int sd = Integer.parseInt(p[2]);
            int ly = Integer.parseInt(p[3]);
            int lm = Integer.parseInt(p[4]);
            int ld = Integer.parseInt(p[5]);
            int lp = Integer.parseInt(p[6]);

            LunarCalendar c = LunarCalendar.fromSolar(sy, sm, sd);
            total++;
            if (c == null) {
                report("fromSolar 返回 null", sy, sm, sd, ly, lm, ld, lp, null);
                continue;
            }
            if (c.year != ly || c.month != lm || c.day != ld || (c.leap ? 1 : 0) != lp) {
                report("换算不符", sy, sm, sd, ly, lm, ld, lp, c);
                continue;
            }
            // 反向一致性：农历 -> 儒略日 应回到原日期
            int back = LunarCalendar.lunarToJdn(c.year, c.month, c.day, c.leap);
            if (back != LunarCalendar.toJdn(sy, sm, sd)) {
                report("反向换算不符", sy, sm, sd, ly, lm, ld, lp, c);
            }
        }
        r.close();

        System.out.println("逐日比对完成: " + total + " 天, 不符 " + fail + " 天");

        // ---------------- 若干定点断言 ----------------
        check("2026-09-15 农历", LunarCalendar.fromSolar(2026, 9, 15), 2026, 8, 5, false);
        check("2026-09-25 中秋", LunarCalendar.fromSolar(2026, 9, 25), 2026, 8, 15, false);
        check("2026-02-17 春节", LunarCalendar.fromSolar(2026, 2, 17), 2026, 1, 1, false);
        check("2025-01-29 春节", LunarCalendar.fromSolar(2025, 1, 29), 2025, 1, 1, false);
        check("2024-02-10 春节", LunarCalendar.fromSolar(2024, 2, 10), 2024, 1, 1, false);
        check("2025-07-25 闰六月初一", LunarCalendar.fromSolar(2025, 7, 25), 2025, 6, 1, true);
        check("2020-05-23 闰四月初一", LunarCalendar.fromSolar(2020, 5, 23), 2020, 4, 1, true);
        check("2023-03-22 闰二月初一", LunarCalendar.fromSolar(2023, 3, 22), 2023, 2, 1, true);
        check("1978-09-02 七月三十", LunarCalendar.fromSolar(1978, 9, 2), 1978, 7, 30, false);

        assertEq("2025 除夕", "除夕", LunarCalendar.fromSolar(2025, 1, 28).festival);
        assertEq("2026 中秋节日", "中秋节", LunarCalendar.fromSolar(2026, 9, 25).festival);
        assertEq("2026 国庆", "国庆节", LunarCalendar.fromSolar(2026, 10, 1).festival);
        assertEq("2026 端午节", "端午节", LunarCalendar.fromSolar(2026, 6, 19).festival);
        assertEq("2026 母亲节(5/10)", "母亲节", LunarCalendar.fromSolar(2026, 5, 10).festival);
        assertEq("2026 父亲节(6/21)", "父亲节", LunarCalendar.fromSolar(2026, 6, 21).festival);
        assertEq("2026 秋分", "秋分", LunarCalendar.termOf(2026, 9, 23));
        assertEq("2026 芒种", "芒种", LunarCalendar.termOf(2026, 6, 5));
        assertEq("2026 冬至", "冬至", LunarCalendar.termOf(2026, 12, 22));
        assertEq("2026 立春", "立春", LunarCalendar.termOf(2026, 2, 4));
        assertEq("2026 生肖", "马", LunarCalendar.fromSolar(2026, 9, 15).zodiac);
        assertEq("2026 干支", "丙午", LunarCalendar.fromSolar(2026, 9, 15).ganZhi);
        assertEq("2026 农历文本", "八月初五", LunarCalendar.fromSolar(2026, 9, 15).shortText());
        // 节气连续性：2026 年 24 个节气都应能定位到指定日期
        int termHits = 0;
        int base = (2026 - LunarTables.YEAR_MIN) * 24;
        for (int n = 0; n < 24; n++) {
            int month = n / 2 + 1;
            int day = LunarTables.TERM_DAYS[base + n];
            if (LunarTables.TERM_NAMES[n].equals(LunarCalendar.termOf(2026, month, day))) {
                termHits++;
            }
        }
        assertEq("2026 节气定位", "24", String.valueOf(termHits));

        // 范围外应安全降级
        if (LunarCalendar.fromSolar(2100, 1, 1) != null) {
            System.out.println("[FAIL] 2100 年应返回 null");
            fail++;
        }
        if (LunarCalendar.fromSolar(1899, 6, 1) != null) {
            System.out.println("[FAIL] 1899 年应返回 null");
            fail++;
        }

        System.out.println(fail == 0 ? "全部通过" : ("存在 " + fail + " 处失败"));
        if (fail != 0) {
            System.exit(1);
        }
    }

    private static void check(String name, LunarCalendar c, int y, int m, int d, boolean leap) {
        if (c == null) {
            System.out.println("[FAIL] " + name + " -> null");
            fail++;
            return;
        }
        if (c.year != y || c.month != m || c.day != d || c.leap != leap) {
            System.out.println("[FAIL] " + name + " -> " + c.year + "/" + c.month + "/" + c.day
                    + (c.leap ? " 闰" : "") + "，期望 " + y + "/" + m + "/" + d + (leap ? " 闰" : ""));
            fail++;
        }
    }

    private static void assertEq(String name, String expect, String actual) {
        boolean ok = expect == null ? actual == null : expect.equals(actual);
        if (!ok) {
            System.out.println("[FAIL] " + name + " -> " + actual + "，期望 " + expect);
            fail++;
        }
    }

    private static void report(String msg, int sy, int sm, int sd, int ly, int lm, int ld, int lp, LunarCalendar c) {
        fail++;
        if (fail <= 20) {
            System.out.println("[FAIL] " + msg + " " + sy + "-" + sm + "-" + sd
                    + " 期望 " + ly + "/" + lm + "/" + ld + (lp == 1 ? "(闰)" : "")
                    + " 实际 " + (c == null ? "null" : c.year + "/" + c.month + "/" + c.day + (c.leap ? "(闰)" : "")));
        }
    }
}
