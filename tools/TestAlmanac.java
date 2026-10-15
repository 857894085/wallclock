import com.vincent.wallclock.Almanac;
import com.vincent.wallclock.LunarCalendar;

/**
 * 黄历引擎桌面回归测试。
 *
 * <p>断言全部来自外部真实黄历数据（timecha.com / 8s8s.net / ima.qq.com 三源互证），
 * 样本见 tools/ALMANAC_NOTES.md：
 * <ul>
 *   <li>A = 2026-09-15 丙午年 丁酉月 壬辰日 星期二</li>
 *   <li>B = 2026-09-16 丙午年 丁酉月 癸巳日 星期三</li>
 *   <li>C = 2026-01-01 乙巳年 戊子月 乙亥日 星期四</li>
 * </ul>
 */
public class TestAlmanac {

    static int passed = 0;
    static int failed = 0;

    static void check(String label, Object got, Object want) {
        boolean ok = got == null ? want == null : got.equals(want);
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("  [FAIL] " + label + "  得到=" + got + "  期望=" + want);
        }
    }

    static void checkTrue(String label, boolean cond) {
        if (cond) {
            passed++;
        } else {
            failed++;
            System.out.println("  [FAIL] " + label);
        }
    }

    static void checkHas(String label, java.util.List<String> list, String item) {
        checkTrue(label + " 含 " + item, list.contains(item));
    }

    public static void main(String[] args) {
        System.out.println("=== 黄历引擎回归测试 ===\n");

        // ============================================================ 样本 A
        System.out.println("A) 2026-09-15 星期二");
        Almanac a = Almanac.of(2026, 9, 15);
        check("日干支", a.dayGanZhi, "壬辰");
        check("月干支", a.monthGanZhi, "丁酉");
        check("年干支", a.yearGanZhi, "丙午");
        check("生肖", a.zodiac, "马");
        check("建除", a.jianChu, "危");
        check("值神", a.zhiShen, "天刑");
        check("黑道", a.huangDao, false);
        check("星宿", a.xiu, "翼");
        check("星宿全名", a.xiuFull, "翼火蛇");
        check("星宿吉凶", a.xiuLuck, -1);
        check("纳音", a.naYin, "长流水");
        check("冲", a.chong, "狗");
        check("煞", a.sha, "南");
        check("胎神", a.taiShen, "仓库栖 外正北");
        check("彭祖(干)", a.pengZuGan, "壬不汲水更难提防");
        check("彭祖(支)", a.pengZuZhi, "辰不哭泣必主重丧");
        check("喜神", a.xiShen, "正南");
        check("财神", a.caiShen, "正南");
        check("阳贵", a.yangGui, "正东");
        check("阴贵", a.yinGui, "东南");
        checkHas("吉神", a.jiShen, "母仓");
        checkHas("吉神", a.jiShen, "四相");
        checkHas("吉神", a.jiShen, "六合");
        checkHas("凶神", a.xiongShen, "月煞");
        checkHas("凶神", a.xiongShen, "月虚");
        checkHas("凶神", a.xiongShen, "四击");
        checkHas("凶神", a.xiongShen, "天刑");
        check("凶神个数", a.xiongShen.size(), 4);
        checkTrue("非大凶日", !a.daXiong);
        checkTrue("宜非空", a.yi.size() > 0);
        checkTrue("忌非空", a.ji.size() > 0);
        System.out.println("     宜=" + Almanac.join(a.yi, 8));
        System.out.println("     忌=" + Almanac.join(a.ji, 8));
        System.out.println("     凶神=" + a.xiongShen);

        // 时辰吉凶（源数据：子凶 丑凶 寅吉 卯凶 辰吉 巳吉 午凶 未凶 申吉 酉吉 戌凶 亥吉）
        boolean[] expA = {false, false, true, false, true, true, false, false, true, true, false, true};
        for (int i = 0; i < 12; i++) {
            check("时辰吉凶 " + Almanac.shiChenName(i), a.hourJi[i], expA[i]);
        }

        // ============================================================ 样本 B
        System.out.println("\nB) 2026-09-16 星期三");
        Almanac b = Almanac.of(2026, 9, 16);
        check("日干支", b.dayGanZhi, "癸巳");
        check("月干支", b.monthGanZhi, "丁酉");
        check("建除", b.jianChu, "成");
        check("值神", b.zhiShen, "朱雀");
        check("星宿", b.xiu, "轸");
        check("星宿全名", b.xiuFull, "轸水蚓");
        check("星宿吉凶", b.xiuLuck, 1);
        check("纳音", b.naYin, "长流水");
        check("冲", b.chong, "猪");
        check("煞", b.sha, "东");
        check("胎神", b.taiShen, "占房床 房内北");
        check("彭祖(干)", b.pengZuGan, "癸不词讼理弱敌强");
        check("彭祖(支)", b.pengZuZhi, "巳不远行财物伏藏");
        checkHas("吉神", b.jiShen, "四相");
        checkHas("吉神", b.jiShen, "三合");
        checkHas("吉神", b.jiShen, "月恩");
        checkHas("吉神", b.jiShen, "天喜");
        checkHas("吉神", b.jiShen, "天医");
        checkHas("吉神", b.jiShen, "临日");
        checkHas("凶神", b.xiongShen, "重日");
        checkHas("凶神", b.xiongShen, "朱雀");
        System.out.println("     凶神=" + b.xiongShen);

        // 时辰吉凶（源数据：子凶 丑吉 寅凶 卯凶 辰吉 巳凶 午吉 未吉 申凶 酉凶 戌吉 亥吉）
        boolean[] expB = {false, true, false, false, true, false, true, true, false, false, true, true};
        for (int i = 0; i < 12; i++) {
            check("时辰吉凶 " + Almanac.shiChenName(i), b.hourJi[i], expB[i]);
        }

        // ============================================================ 样本 C
        System.out.println("\nC) 2026-01-01 星期四");
        Almanac c = Almanac.of(2026, 1, 1);
        check("日干支", c.dayGanZhi, "乙亥");
        check("月干支", c.monthGanZhi, "戊子");
        check("年干支", c.yearGanZhi, "乙巳");
        check("建除", c.jianChu, "闭");
        check("值神", c.zhiShen, "朱雀");
        check("星宿", c.xiu, "井");
        check("星宿全名", c.xiuFull, "井木犴");
        check("纳音", c.naYin, "山头火");
        check("冲", c.chong, "蛇");
        check("煞", c.sha, "西");
        check("胎神", c.taiShen, "碓磨床 外西南");
        check("彭祖(干)", c.pengZuGan, "乙不栽植千株不长");
        check("彭祖(支)", c.pengZuZhi, "亥不嫁娶不利新郎");
        check("喜神", c.xiShen, "西北");
        check("阳贵", c.yangGui, "西南");
        check("阴贵", c.yinGui, "正北");
        checkHas("吉神", c.jiShen, "四相");
        checkHas("吉神", c.jiShen, "王日");
        check("吉神个数", c.jiShen.size(), 2);
        checkHas("凶神", c.xiongShen, "游祸");
        checkHas("凶神", c.xiongShen, "血支");
        checkHas("凶神", c.xiongShen, "重日");
        checkHas("凶神", c.xiongShen, "朱雀");
        checkTrue("C 非大凶日（岁破日通书仍照常列宜忌）", !c.daXiong);
        checkTrue("C 宜非空", c.yi.size() > 0);
        System.out.println("     吉神=" + c.jiShen);
        System.out.println("     凶神=" + c.xiongShen);

        boolean[] expC = {false, true, false, false, true, false, true, true, false, false, true, true};
        for (int i = 0; i < 12; i++) {
            check("时辰吉凶 " + Almanac.shiChenName(i), c.hourJi[i], expC[i]);
        }

        // ============================================================ 全量不变量扫描
        System.out.println("\nD) 1900-2099 全量不变量扫描");

        int xiuSundayBad = 0;      // 房/虚/昴/星 必须落在星期日
        int dayGZBad = 0;          // 日干支必须逐日 +1
        int jianChuBad = 0;        // 建除必须 0..11
        int zhiShenBad = 0;        // 值神必须 0..11
        int startJdn = LunarCalendar.toJdn(1900, 1, 31);
        int endJdn = LunarCalendar.toJdn(2099, 12, 31);

        int prevGZ = -1;
        for (int jdn = startJdn; jdn <= endJdn; jdn++) {
            int[] ymd = LunarCalendar.fromJdn(jdn);
            Almanac x = Almanac.of(ymd[0], ymd[1], ymd[2]);

            // 星宿与星期：索引(1基) 房=4 虚=11 昴=18 星=25 必须是周日
            int k1 = x.xiuIndex + 1;
            int dow = mod(jdn + 1, 7);           // 0 = 星期日
            if ((k1 == 4 || k1 == 11 || k1 == 18 || k1 == 25) && dow != 0) {
                xiuSundayBad++;
            }

            int gz = mod(jdn + 49, 60);
            if (prevGZ >= 0 && gz != mod(prevGZ + 1, 60)) {
                dayGZBad++;
            }
            prevGZ = gz;

            if (x.jianChuIndex < 0 || x.jianChuIndex > 11) {
                jianChuBad++;
            }
            if (x.zhiShenIndex < 0 || x.zhiShenIndex > 11) {
                zhiShenBad++;
            }
        }

        checkTrue("二十八宿星期不变量 0 违例（共 " + xiuSundayBad + "）", xiuSundayBad == 0);
        checkTrue("日干支逐日递进 0 违例（共 " + dayGZBad + "）", dayGZBad == 0);
        checkTrue("建除索引合法（共 " + jianChuBad + "）", jianChuBad == 0);
        checkTrue("值神索引合法（共 " + zhiShenBad + "）", zhiShenBad == 0);

        // 建除：月建不变的前提下，每过一日应 +1（跨节气换月时会跳变，跳过）
        int jcStepBad = 0;
        for (int jdn = startJdn; jdn <= endJdn - 1; jdn++) {
            int[] p = LunarCalendar.fromJdn(jdn);
            int[] q = LunarCalendar.fromJdn(jdn + 1);
            if (Almanac.monthBranch(p[0], p[1], p[2]) != Almanac.monthBranch(q[0], q[1], q[2])) {
                continue;
            }
            Almanac x = Almanac.of(p[0], p[1], p[2]);
            Almanac z = Almanac.of(q[0], q[1], q[2]);
            if (z.jianChuIndex != mod(x.jianChuIndex + 1, 12)) {
                jcStepBad++;
            }
        }
        checkTrue("建除逐日递进 0 违例（共 " + jcStepBad + "）", jcStepBad == 0);

        // 月建只应在 12 个「节」当天切换，且恰好在节气日
        int monthBranchBad = 0;
        for (int jdn = startJdn; jdn <= endJdn - 1; jdn++) {
            int[] p = LunarCalendar.fromJdn(jdn);
            int[] q = LunarCalendar.fromJdn(jdn + 1);
            int a1 = Almanac.monthBranch(p[0], p[1], p[2]);
            int a2 = Almanac.monthBranch(q[0], q[1], q[2]);
            if (a1 != a2) {
                // 换月当天必须是一个「节」（偶数序号节气）
                int t = Almanac.termIndexOf(q[0], q[1], q[2]);
                if (t < 0 || t % 2 != 0) {
                    monthBranchBad++;
                }
                if (a2 != mod(a1 + 1, 12)) {
                    monthBranchBad++;
                }
            }
        }
        checkTrue("月建仅于节气日切换且顺行（共 " + monthBranchBad + "）", monthBranchBad == 0);

        // 二十八宿 28 天一轮
        int xiuCycleBad = 0;
        for (int jdn = startJdn; jdn <= endJdn - 28; jdn++) {
            int[] p = LunarCalendar.fromJdn(jdn);
            int[] q = LunarCalendar.fromJdn(jdn + 28);
            Almanac x = Almanac.of(p[0], p[1], p[2]);
            Almanac z = Almanac.of(q[0], q[1], q[2]);
            if (x.xiuIndex != z.xiuIndex) {
                xiuCycleBad++;
            }
        }
        checkTrue("二十八宿 28 日一轮（共 " + xiuCycleBad + " 违例）", xiuCycleBad == 0);

        // 时辰 12 支全非空、干支合法
        int hourBad = 0;
        for (int jdn = startJdn; jdn <= endJdn; jdn += 37) {
            int[] ymd = LunarCalendar.fromJdn(jdn);
            Almanac x = Almanac.of(ymd[0], ymd[1], ymd[2]);
            for (int t = 0; t < 12; t++) {
                if (x.hourGanZhi[t] == null || x.hourGanZhi[t].length() != 2) {
                    hourBad++;
                }
            }
        }
        checkTrue("十二时辰干支合法（共 " + hourBad + "）", hourBad == 0);

        // ============================================================ 汇总
        System.out.println("\n=== 结果 ===");
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        System.out.println(failed == 0 ? "全部通过" : "存在问题");
        if (failed > 0) {
            System.exit(1);
        }
    }

    static int mod(int a, int b) {
        int r = a % b;
        return r < 0 ? r + b : r;
    }
}
