package com.vincent.wallclock;

import com.vincent.wallclock.gen.AlmanacTables;
import com.vincent.wallclock.gen.LunarTables;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 黄历（通书）计算引擎。
 *
 * <p>与 {@link LunarCalendar} 一样，本类<b>不依赖任何 Android API</b>，可在桌面用 javac
 * 直接编译并跑回归测试（见 <code>tools/TestAlmanac.java</code>）。
 *
 * <h3>数据可信度</h3>
 * 本项目把黄历内容分成两类，界面上的呈现方式据此区分：
 * <ul>
 *   <li><b>可推算项</b>（干支、建除十二神、二十八宿值日、黄道黑道值神、冲煞、纳音、
 *       彭祖百忌、胎神、喜神/财神/贵人方位、时辰吉凶、节气定月）——都有唯一算法，
 *       已逐项用外部真实黄历数据交叉验证，见 <code>tools/ALMANAC_NOTES.md</code>。</li>
 *   <li><b>宜 / 忌</b>——传统择日学中，宜忌是由数十个神煞的"所宜/所忌"叠加合成的，
 *       <b>各家通书结果并不一致，不存在唯一权威答案</b>。本类按主流起例规则合成，
 *       界面上标注为"参考"。</li>
 * </ul>
 */
public final class Almanac {

    // ------------------------------------------------------------------ 基础表

    public static final String[] GAN = {"甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸"};
    public static final String[] ZHI = {"子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥"};
    public static final String[] ZODIAC = {"鼠", "牛", "虎", "兔", "龙", "蛇", "马", "羊", "猴", "鸡", "狗", "猪"};

    /** 煞方位按日支三合局：组0 申子辰→南、组1 巳酉丑→东、组2 寅午戌→北、组3 亥卯未→西 */
    private static final String[] SHA_FANG = {"南", "东", "北", "西"};

    /** 三合局长生位（四生位），索引为「地支 % 4」 */
    private static final int[] SHENG = {8, 5, 2, 11};

    private static final int[] LIUHE = {1, 0, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2};
    private static final int[] HAI = {7, 6, 5, 4, 3, 2, 1, 0, 11, 10, 9, 8};
    /** 月刑：月支 → 所刑之日支 */
    private static final int[] XING = {3, 10, 5, 0, 4, 8, 6, 1, 2, 9, 7, 11};
    /** 月厌：正月起戌，逆行 */
    private static final int[] YAN = {0, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1};
    /** 月德：寅午戌月丙、申子辰月壬、亥卯未月甲、巳酉丑月庚 */
    private static final int[] YUEDE = {8, 6, 2, 0, 8, 6, 2, 0, 8, 6, 2, 0};
    /** 月德合 */
    private static final int[] YUEDEHE = {3, 1, 7, 5, 3, 1, 7, 5, 3, 1, 7, 5};
    /** 月恩：寅丙 卯丁 辰庚 巳己 午戊 未辛 申壬 酉癸 戌庚 亥乙 子甲 丑辛 */
    private static final int[] YUEEN = {0, 7, 2, 3, 6, 5, 4, 7, 8, 9, 6, 1};
    /** 天德（天干形式），-1 表示当月天德为地支 */
    private static final int[] TIANDE_GAN = {-1, 6, 3, -1, 8, 7, -1, 0, 9, -1, 2, 1};
    /** 天德（地支形式），-1 表示当月天德为天干 */
    private static final int[] TIANDE_ZHI = {5, -1, -1, 8, -1, -1, 11, -1, -1, 2, -1, -1};

    /** 血忌：月支 → 日支 */
    private static final int[] XUEJI = {6, 0, 1, 7, 2, 8, 3, 9, 4, 10, 5, 11};

    /** 王日：春寅 夏巳 秋申 冬亥 */
    private static final int[] SEASON_WANG = {2, 5, 8, 11};
    /** 官日：春卯 夏午 秋酉 冬子 */
    private static final int[] SEASON_GUAN = {3, 6, 9, 0};
    /** 守日：春辰 夏未 秋戌 冬丑 */
    private static final int[] SEASON_SHOU = {4, 7, 10, 1};
    /** 相日：春巳 夏申 秋亥 冬寅 */
    private static final int[] SEASON_XIANG = {5, 8, 11, 2};
    /** 民日：春午 夏酉 秋子 冬卯 */
    private static final int[] SEASON_MIN = {6, 9, 0, 3};
    /** 时德：春午 夏辰 秋子 冬寅 */
    private static final int[] SEASON_SHI = {6, 4, 0, 2};
    /** 四击：春戌 夏丑 秋辰 冬未 */
    private static final int[] SEASON_SIJI = {10, 1, 4, 7};
    /** 五虚（三合组）：春→巳酉丑(1) 夏→申子辰(0) 秋→亥卯未(3) 冬→寅午戌(2) */
    private static final int[] SEASON_WUXU = {1, 0, 3, 2};
    /** 母仓日支：春亥子 夏寅卯 秋辰戌丑未 冬申酉 */
    private static final int[][] SEASON_MUCANG = {
            {11, 0}, {2, 3}, {4, 10, 1, 7}, {8, 9},
    };
    /** 四相日干：春丙丁 夏戊己 秋壬癸 冬甲乙 */
    private static final int[][] SEASON_SIXIANG = {
            {2, 3}, {4, 5}, {8, 9}, {0, 1},
    };
    /** 四废日干：春庚辛 夏壬癸 秋甲乙 冬丙丁 */
    private static final int[][] SEASON_SIFEI_GAN = {
            {6, 7}, {8, 9}, {0, 1}, {2, 3},
    };
    /** 四废日支：春申酉 夏子亥 秋寅卯 冬午巳 */
    private static final int[][] SEASON_SIFEI_ZHI = {
            {8, 9}, {0, 11}, {2, 3}, {6, 5},
    };
    /** 天赦：春戊寅 夏甲午 秋戊申 冬甲子（六十甲子序） */
    private static final int[] TIAN_SHE = {14, 30, 44, 0};

    /** 黄道值神缺省所宜（表中未单列时使用） */
    private static final String HUANG_DEFAULT_YI =
            "祭祀 祈福 嫁娶 纳采 开市 交易 立券 纳财 出行 入宅 移徙 安床 修造 动土";

    /** 四离 / 四绝 对应的节气序号 */
    private static final int[] SI_LI = {5, 11, 17, 23};    // 春分 夏至 秋分 冬至
    private static final int[] SI_JUE = {2, 8, 14, 20};    // 立春 立夏 立秋 立冬

    // ------------------------------------------------------------------ 结果

    public int year, month, day;

    public String yearGanZhi = "";
    public String monthGanZhi = "";
    public String dayGanZhi = "";
    public String zodiac = "";
    public String naYin = "";

    /** 建除十二神索引与名称（如 危） */
    public int jianChuIndex;
    public String jianChu = "";

    /** 二十八宿 */
    public int xiuIndex;
    public String xiu = "";        // 单字，如 翼
    public String xiuFull = "";    // 全名，如 翼火蛇
    public int xiuLuck;            // 1 吉 / -1 凶

    /** 值神（黄道黑道十二神之一） */
    public int zhiShenIndex;
    public String zhiShen = "";
    public boolean huangDao;

    /** 冲（生肖）与煞（方位） */
    public String chong = "";
    public String chongGanZhi = "";
    public String sha = "";

    public String pengZuGan = "";
    public String pengZuZhi = "";
    public String taiShen = "";

    public String xiShen = "";
    public String caiShen = "";
    public String fuShen = "";
    public String yangGui = "";
    public String yinGui = "";

    /** 吉神宜趋 / 凶神宜忌 */
    public final List<String> jiShen = new ArrayList<String>();
    public final List<String> xiongShen = new ArrayList<String>();

    /** 合成后的宜 / 忌（参考） */
    public final List<String> yi = new ArrayList<String>();
    public final List<String> ji = new ArrayList<String>();

    /** 大凶日：四离四绝、杨公忌、岁破 —— 界面直接显示"诸事不宜" */
    public boolean daXiong;
    public String daXiongReason = "";

    /** 十二时辰：干支、值神、吉凶 */
    public final String[] hourGanZhi = new String[12];
    public final String[] hourZhiShen = new String[12];
    public final boolean[] hourJi = new boolean[12];

    /** 农历月 / 日（用于界面显示，可能为 null 表示超出范围） */
    public LunarCalendar lunar;

    private Almanac() {
    }

    // ------------------------------------------------------------------ 入口

    /** 计算某一天的黄历；year 超出 1900-2099 时农历相关字段为空，但干支仍可算 */
    public static Almanac of(int y, int m, int d) {
        Almanac a = new Almanac();
        a.year = y;
        a.month = m;
        a.day = d;

        int jdn = LunarCalendar.toJdn(y, m, d);
        a.lunar = LunarCalendar.fromSolar(y, m, d);

        // ---- 年干支（依农历年，与通书一致）----
        int lunarYear = a.lunar != null ? a.lunar.year : y;
        int yGan = mod(lunarYear - 4, 10);
        int yZhi = mod(lunarYear - 4, 12);
        a.yearGanZhi = GAN[yGan] + ZHI[yZhi];
        a.zodiac = ZODIAC[yZhi];

        // ---- 日干支 ----
        int dayGZ = mod(jdn + 49, 60);
        int dGan = dayGZ % 10;
        int dZhi = dayGZ % 12;
        a.dayGanZhi = GAN[dGan] + ZHI[dZhi];

        // ---- 月建（节气定月）与月干支（五虎遁）----
        int mZhi = monthBranch(y, m, d);
        int yinGan = ((yGan % 5) * 2 + 2) % 10;                 // 寅月天干
        int mGan = (yinGan + mod(mZhi - 2, 12)) % 10;
        a.monthGanZhi = GAN[mGan] + ZHI[mZhi];

        // ---- 纳音 ----
        a.naYin = AlmanacTables.NAYIN[dayGZ / 2];

        // ---- 建除十二神 ----
        a.jianChuIndex = mod(dZhi - mZhi, 12);
        a.jianChu = AlmanacTables.JIANCHU[a.jianChuIndex];

        // ---- 二十八宿值日：((JDN + 12) mod 28)，余 0 取 28 ----
        int xiuRes = mod(jdn + 12, 28);
        a.xiuIndex = (xiuRes == 0) ? 27 : xiuRes - 1;
        a.xiu = AlmanacTables.XIU_SHORT[a.xiuIndex];
        a.xiuFull = AlmanacTables.XIU_FULL[a.xiuIndex];
        a.xiuLuck = AlmanacTables.XIU_LUCK[a.xiuIndex];

        // ---- 值神（黄道黑道）----
        a.zhiShenIndex = mod(dZhi - AlmanacTables.QINGLONG_BASE[mZhi], 12);
        a.zhiShen = AlmanacTables.ZHI_SHEN[a.zhiShenIndex];
        a.huangDao = AlmanacTables.ZHI_SHEN_HUANG[a.zhiShenIndex];

        // ---- 冲煞 ----
        int chongZhi = mod(dZhi + 6, 12);
        a.chong = ZODIAC[chongZhi];
        a.chongGanZhi = GAN[mod(dGan + 6, 10)] + ZHI[chongZhi];
        a.sha = SHA_FANG[dZhi % 4];

        // ---- 彭祖百忌 / 胎神 / 方位 ----
        a.pengZuGan = AlmanacTables.PENGZU_GAN[dGan];
        a.pengZuZhi = AlmanacTables.PENGZU_ZHI[dZhi];
        a.taiShen = AlmanacTables.TAI_SHEN[dayGZ];
        a.xiShen = AlmanacTables.XI_SHEN[dGan];
        a.caiShen = AlmanacTables.CAI_SHEN[dGan];
        a.fuShen = AlmanacTables.FU_SHEN[dGan];
        a.yangGui = AlmanacTables.YANG_GUI[dGan];
        a.yinGui = AlmanacTables.YIN_GUI[dGan];

        // ---- 神煞 ----
        int season = mod(mZhi - 2, 12) / 3;
        int sheng = SHENG[mZhi % 4];
        a.collectJiShen(mZhi, dGan, dZhi, dayGZ, season, sheng);
        a.collectXiongShen(yZhi, mZhi, dGan, dZhi, dayGZ, season, sheng);

        // ---- 时辰 ----
        for (int t = 0; t < 12; t++) {
            int hGan = ((dGan % 5) * 2 + t) % 10;
            a.hourGanZhi[t] = GAN[hGan] + ZHI[t];
            int hz = mod(t - AlmanacTables.QINGLONG_BASE[dZhi], 12);
            a.hourZhiShen[t] = AlmanacTables.ZHI_SHEN[hz];
            a.hourJi[t] = AlmanacTables.ZHI_SHEN_HUANG[hz];
        }

        a.checkDaXiong(y, m, d, jdn, mZhi, yZhi, dZhi);
        a.synthesize();

        return a;
    }

    // ------------------------------------------------------------------ 神煞

    private void collectJiShen(int mZhi, int dGan, int dZhi, int dayGZ, int season, int sheng) {
        int tdG = TIANDE_GAN[mZhi];
        int tdZ = TIANDE_ZHI[mZhi];
        if (tdG == dGan || tdZ == dZhi) {
            add(jiShen, "天德");
        }
        if ((tdG >= 0 && mod(tdG + 5, 10) == dGan) || (tdZ >= 0 && LIUHE[tdZ] == dZhi)) {
            add(jiShen, "天德合");
        }
        if (YUEDE[mZhi] == dGan) {
            add(jiShen, "月德");
        }
        if (YUEDEHE[mZhi] == dGan) {
            add(jiShen, "月德合");
        }
        if (TIAN_SHE[season] == dayGZ) {
            add(jiShen, "天赦");
        }
        if (contains(SEASON_MUCANG[season], dZhi)) {
            add(jiShen, "母仓");
        }
        if (contains(SEASON_SIXIANG[season], dGan)) {
            add(jiShen, "四相");
        }
        if (dZhi != mZhi && dZhi % 4 == mZhi % 4) {
            add(jiShen, "三合");
        }
        if (LIUHE[mZhi] == dZhi) {
            add(jiShen, "六合");
        }
        if (YUEEN[mZhi] == dGan) {
            add(jiShen, "月恩");
        }
        if (sheng == dZhi) {
            add(jiShen, "天喜");
        }
        if (mod(mZhi + 8, 12) == dZhi) {
            add(jiShen, "天医");
            add(jiShen, "临日");
        }
        if (SEASON_WANG[season] == dZhi) {
            add(jiShen, "王日");
        }
        if (SEASON_GUAN[season] == dZhi) {
            add(jiShen, "官日");
        }
        if (SEASON_SHOU[season] == dZhi) {
            add(jiShen, "守日");
        }
        if (SEASON_XIANG[season] == dZhi) {
            add(jiShen, "相日");
        }
        if (SEASON_MIN[season] == dZhi) {
            add(jiShen, "民日");
        }
        if (SEASON_SHI[season] == dZhi) {
            add(jiShen, "时德");
        }
        if (huangDao && !"天德".equals(zhiShen)) {
            add(jiShen, zhiShen);
        }
    }

    private void collectXiongShen(int yZhi, int mZhi, int dGan, int dZhi, int dayGZ,
                                  int season, int sheng) {
        if (mod(mZhi + 6, 12) == dZhi) {
            add(xiongShen, "月破");
            add(xiongShen, "大耗");
        }
        if (mod(yZhi + 6, 12) == dZhi) {
            add(xiongShen, "岁破");
        }
        if (mod(sheng + 10, 12) == dZhi) {
            add(xiongShen, "灾煞");
        }
        if (mod(sheng + 9, 12) == dZhi) {
            add(xiongShen, "劫煞");
        }
        if (mod(sheng + 11, 12) == dZhi) {
            add(xiongShen, "月煞");
            add(xiongShen, "月虚");
        }
        if (mod(sheng + 4, 12) == dZhi) {
            add(xiongShen, "大时");
        }
        if (mod(sheng + 1, 12) == dZhi) {
            add(xiongShen, "咸池");
        }
        if (mod(sheng + 8, 12) == dZhi) {
            add(xiongShen, "五墓");
        }
        if (HAI[mZhi] == dZhi) {
            add(xiongShen, "月害");
        }
        if (XING[mZhi] == dZhi) {
            add(xiongShen, "月刑");
        }
        if (YAN[mZhi] == dZhi) {
            add(xiongShen, "月厌");
        }
        if (SEASON_SIJI[season] == dZhi) {
            add(xiongShen, "四击");
        }
        int off = mod(mZhi - 2, 12);
        if (mod(2 + off / 4 + 3 * off, 12) == dZhi) {
            add(xiongShen, "往亡");
        }
        if (dZhi % 4 == SEASON_WUXU[season]) {
            add(xiongShen, "五虚");
        }
        if (contains(SEASON_SIFEI_GAN[season], dGan) && contains(SEASON_SIFEI_ZHI[season], dZhi)) {
            add(xiongShen, "四废");
        }
        if (mod(mZhi + 11, 12) == dZhi) {
            add(xiongShen, "血支");
            add(xiongShen, "游祸");
        }
        if (XUEJI[mZhi] == dZhi) {
            add(xiongShen, "血忌");
        }
        if (dZhi == 5 || dZhi == 11) {
            add(xiongShen, "重日");
        }
        if (!huangDao) {
            add(xiongShen, zhiShen);
        }
    }

    private void checkDaXiong(int y, int m, int d, int jdn, int mZhi, int yZhi, int dZhi) {
        // 注：岁破、月破虽然也是凶日，但通书仍会给出所宜事项（实测样本 C 就是岁破日，
        // 各家仍照常列宜忌），因此只有下列三类才判为"诸事不宜"。
        // 四离四绝：看明天是不是二分二至 / 四立
        int[] tmr = LunarCalendar.fromJdn(jdn + 1);
        int tn = termIndexOf(tmr[0], tmr[1], tmr[2]);
        if (contains(SI_LI, tn)) {
            daXiong = true;
            daXiongReason = "四离";
            return;
        }
        if (contains(SI_JUE, tn)) {
            daXiong = true;
            daXiongReason = "四绝";
            return;
        }
        // 杨公忌：农历固定十三日
        if (lunar != null && !lunar.leap) {
            for (int[] g : AlmanacTables.YANG_GONG) {
                if (g[0] == lunar.month && g[1] == lunar.day) {
                    daXiong = true;
                    daXiongReason = "杨公忌";
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------ 宜忌合成

    private void synthesize() {
        if (daXiong) {
            yi.clear();
            ji.clear();
            ji.add("诸事不宜");
            return;
        }

        Map<String, Integer> yiMap = new LinkedHashMap<String, Integer>();
        Map<String, Integer> jiMap = new LinkedHashMap<String, Integer>();

        // 建除十二神 + 二十八宿 打底
        putAll(yiMap, AlmanacTables.JIANCHU_YI[jianChuIndex]);
        putAll(yiMap, AlmanacTables.XIU_YI[xiuIndex]);
        putAll(jiMap, AlmanacTables.JIANCHU_JI[jianChuIndex]);
        putAll(jiMap, AlmanacTables.XIU_JI[xiuIndex]);

        // 吉神加宜
        for (int i = 0; i < jiShen.size(); i++) {
            String name = jiShen.get(i);
            String items = lookup(AlmanacTables.JI_SHEN, name);
            if (items == null) {
                items = HUANG_DEFAULT_YI;
            }
            putAll(yiMap, items);
        }
        // 凶神加忌
        for (int i = 0; i < xiongShen.size(); i++) {
            String items = lookup(AlmanacTables.XIONG_SHA, xiongShen.get(i));
            if (items != null) {
                putAll(jiMap, items);
            }
        }

        // 冲突消解：出现在宜中的事项，从忌里去掉
        for (String k : yiMap.keySet()) {
            jiMap.remove(k);
        }

        yi.addAll(sortByWeight(yiMap));
        ji.addAll(sortByWeight(jiMap));
    }

    private static void putAll(Map<String, Integer> map, String items) {
        if (items == null) {
            return;
        }
        String[] parts = items.split(" ");
        for (String p : parts) {
            if (p.length() == 0) {
                continue;
            }
            Integer old = map.get(p);
            map.put(p, old == null ? 1 : old.intValue() + 1);
        }
    }

    private static String lookup(String[][] table, String name) {
        for (String[] row : table) {
            if (row[0].equals(name)) {
                return row[1];
            }
        }
        return null;
    }

    /** 出现次数多的排在前面（意味着被更多神煞共同认定） */
    private static List<String> sortByWeight(Map<String, Integer> map) {
        List<Map.Entry<String, Integer>> list =
                new ArrayList<Map.Entry<String, Integer>>(map.entrySet());
        Collections.sort(list, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue().intValue() - a.getValue().intValue();
            }
        });
        List<String> out = new ArrayList<String>(list.size());
        for (Map.Entry<String, Integer> e : list) {
            out.add(e.getKey());
        }
        return out;
    }

    // ------------------------------------------------------------------ 工具

    /** 节气月支索引（子=0）：以「节」为界，立春起寅月 */
    public static int monthBranch(int y, int m, int d) {
        int jdn = LunarCalendar.toJdn(y, m, d);
        for (int k = 0; k <= 40; k++) {
            int[] s = LunarCalendar.fromJdn(jdn - k);
            if (!LunarCalendar.inRange(s[0])) {
                break;
            }
            int base = (s[0] - LunarTables.YEAR_MIN) * 24;
            for (int n = 0; n < 24; n += 2) {
                if ((n / 2 + 1) == s[1] && LunarTables.TERM_DAYS[base + n] == s[2]) {
                    return (n / 2 + 1) % 12;
                }
            }
        }
        return 2;
    }

    /** 当天的节气序号（0..23），无则 -1 */
    public static int termIndexOf(int y, int m, int d) {
        if (!LunarCalendar.inRange(y)) {
            return -1;
        }
        int base = (y - LunarTables.YEAR_MIN) * 24;
        for (int n = (m - 1) * 2; n <= (m - 1) * 2 + 1; n++) {
            if (n >= 0 && n < 24 && LunarTables.TERM_DAYS[base + n] == d) {
                return n;
            }
        }
        return -1;
    }

    /** 该时辰（0=子时）对应的起始小时 */
    public static int hourStart(int shiChen) {
        return (shiChen * 2 + 23) % 24;
    }

    /** 十二时辰名 */
    public static String shiChenName(int i) {
        return ZHI[i] + "时";
    }

    private static boolean contains(int[] arr, int v) {
        for (int x : arr) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    private static void add(List<String> list, String s) {
        if (!list.contains(s)) {
            list.add(s);
        }
    }

    private static int mod(int a, int b) {
        int r = a % b;
        return r < 0 ? r + b : r;
    }

    // ------------------------------------------------------------------ 展示

    /** "危日" */
    public String jianChuDay() {
        return jianChu + "日";
    }

    /** "翼火蛇" 或者 "翼火蛇（吉宿）" */
    public String xiuLabel() {
        return xiuFull + (xiuLuck > 0 ? "（吉）" : "（凶）");
    }

    /** 值神标签，如 "天刑（黑道日）" */
    public String zhiShenLabel() {
        return zhiShen + (huangDao ? "（黄道日）" : "（黑道日）");
    }

    /** 冲煞一行："冲狗 煞南" */
    public String chongSha() {
        return "冲" + chong + " 煞" + sha;
    }

    /** 把列表拼成一行，用空格分隔，最多 max 项 */
    public static String join(List<String> list, int max) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String s : list) {
            if (n >= max) {
                sb.append(" …");
                break;
            }
            if (n > 0) {
                sb.append(' ');
            }
            sb.append(s);
            n++;
        }
        return sb.toString();
    }
}
