package com.vincent.wallclock;

import com.vincent.wallclock.gen.LunarTables;

/**
 * 农历 / 节气 / 节日 计算。
 *
 * <p>本类刻意不依赖任何 Android API，因此可以在桌面上用 javac 直接编译并跑回归测试
 * （见 tools/TestLunar.java，会逐日比对 tools/reference_lunar.csv 的 73018 天基准）。
 *
 * <p>算法为经典 lunarInfo 查表法，表见 {@link LunarTables}。
 */
public final class LunarCalendar {

    /** 表锚点：公历 1900-01-31 为农历 1900 年正月初一 */
    private static final int EPOCH_JDN = toJdn(1900, 1, 31);

    private static final String[] TIAN_GAN = {"甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸"};
    private static final String[] DI_ZHI = {"子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥"};
    private static final String[] ZODIAC = {"鼠", "牛", "虎", "兔", "龙", "蛇", "马", "羊", "猴", "鸡", "狗", "猪"};
    private static final String[] MONTH_NAMES = {"正月", "二月", "三月", "四月", "五月", "六月",
            "七月", "八月", "九月", "十月", "冬月", "腊月"};
    private static final String[] DAY_NAMES = {"初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
            "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
            "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十"};

    public int year;
    public int month;            // 1..12
    public int day;              // 1..30
    public boolean leap;         // 是否闰月
    public String monthName;     // 正月 / 闰四月 / 腊月
    public String dayName;       // 初一 / 廿三
    public String zodiac;        // 马
    public String ganZhi;        // 丙午
    public String term;          // 当天节气，无则 null
    public String festival;      // 当天节日/节庆，无则 null

    private LunarCalendar() {
    }

    // ------------------------------------------------------------------ 基础工具

    /** 公历 -> 儒略日数（Gregorian） */
    public static int toJdn(int y, int m, int d) {
        int a = (14 - m) / 12;
        int yy = y + 4800 - a;
        int mm = m + 12 * a - 3;
        return d + (153 * mm + 2) / 5 + 365 * yy + yy / 4 - yy / 100 + yy / 400 - 32045;
    }

    /** 儒略日数 -> 公历 {y, m, d} */
    public static int[] fromJdn(int jdn) {
        int a = jdn + 32044;
        int b = (4 * a + 3) / 146097;
        int c = a - 146097 * b / 4;
        int d2 = (4 * c + 3) / 1461;
        int e = c - 1461 * d2 / 4;
        int m2 = (5 * e + 2) / 153;
        int day = e - (153 * m2 + 2) / 5 + 1;
        int month = m2 + 3 - 12 * (m2 / 10);
        int year = 100 * b + d2 - 4800 + m2 / 10;
        return new int[]{year, month, day};
    }

    public static boolean inRange(int year) {
        return year >= LunarTables.YEAR_MIN && year <= LunarTables.YEAR_MAX;
    }

    /** 闰月月份，0 表示当年无闰月 */
    public static int leapMonth(int y) {
        return LunarTables.LUNAR_INFO[y - LunarTables.YEAR_MIN] & 0xF;
    }

    public static int leapDays(int y) {
        int lm = leapMonth(y);
        if (lm == 0) {
            return 0;
        }
        return ((LunarTables.LUNAR_INFO[y - LunarTables.YEAR_MIN] & 0x10000) != 0) ? 30 : 29;
    }

    /** 农历某个月的天数，m 为 1..12（不含闰月） */
    public static int monthDays(int y, int m) {
        return ((LunarTables.LUNAR_INFO[y - LunarTables.YEAR_MIN] & (0x10000 >> m)) != 0) ? 30 : 29;
    }

    /** 农历一年的天数 */
    public static int yearDays(int y) {
        int sum = 348;
        for (int i = 0x8000; i > 0x8; i >>= 1) {
            if ((LunarTables.LUNAR_INFO[y - LunarTables.YEAR_MIN] & i) != 0) {
                sum++;
            }
        }
        return sum + leapDays(y);
    }

    /** 农历 y 年 m 月 d 日对应的儒略日数；参数非法返回 -1 */
    public static int lunarToJdn(int y, int m, int d, boolean isLeap) {
        if (!inRange(y) || m < 1 || m > 12 || d < 1 || d > 30) {
            return -1;
        }
        if (isLeap && leapMonth(y) != m) {
            return -1;
        }
        int max = isLeap ? leapDays(y) : monthDays(y, m);
        if (d > max) {
            return -1;
        }
        int jdn = EPOCH_JDN;
        for (int i = LunarTables.YEAR_MIN; i < y; i++) {
            jdn += yearDays(i);
        }
        // 正月 .. 目标月之前
        for (int i = 1; i < m; i++) {
            jdn += monthDays(y, i);
            if (leapMonth(y) == i) {
                jdn += leapDays(y);
            }
        }
        // 闰月排在同名正常月之后
        if (isLeap) {
            jdn += monthDays(y, m);
        }
        return jdn + d - 1;
    }

    // ------------------------------------------------------------------ 换算

    /** 公历日期 -> 农历；超出 1900-2099 范围返回 null */
    public static LunarCalendar fromSolar(int y, int m, int d) {
        if (!inRange(y)) {
            return null;
        }
        int jdn = toJdn(y, m, d);
        int offset = jdn - EPOCH_JDN;
        if (offset < 0) {
            return null;
        }
        int ly = LunarTables.YEAR_MIN;
        while (ly <= LunarTables.YEAR_MAX) {
            int yd = yearDays(ly);
            if (offset < yd) {
                break;
            }
            offset -= yd;
            ly++;
        }
        if (ly > LunarTables.YEAR_MAX) {
            return null;
        }
        // 按时间顺序逐月推进：正月 -> 二月 -> ... -> 闰X月（紧随同名正常月）-> 腊月
        int remaining = offset;
        int curMonth = 0;
        boolean curLeap = false;
        int curDays = 30;
        int lm = 1;
        boolean isLeap = false;
        int leap = leapMonth(ly);
        for (int idx = 0; idx < 13; idx++) {
            if (curMonth == 0) {
                curMonth = 1;
                curLeap = false;
            } else if (!curLeap && leap != 0 && leap == curMonth) {
                curLeap = true;                     // 闰月排在同名正常月之后
            } else {
                curMonth++;
                curLeap = false;
                if (curMonth > 12) {
                    break;
                }
            }
            curDays = curLeap ? leapDays(ly) : monthDays(ly, curMonth);
            if (remaining < curDays) {
                lm = curMonth;
                isLeap = curLeap;
                break;
            }
            remaining -= curDays;
        }

        LunarCalendar c = new LunarCalendar();
        c.year = ly;
        c.month = lm;
        c.day = remaining + 1;
        c.leap = isLeap;
        c.monthName = (isLeap ? "闰" : "") + MONTH_NAMES[lm - 1];
        c.dayName = DAY_NAMES[c.day - 1];
        c.zodiac = ZODIAC[mod(ly - 4, 12)];
        c.ganZhi = TIAN_GAN[mod(ly - 4, 10)] + DI_ZHI[mod(ly - 4, 12)];
        c.term = termOf(y, m, d);
        c.festival = festivalOf(y, m, d, c);
        return c;
    }

    private static int mod(int a, int b) {
        int r = a % b;
        return r < 0 ? r + b : r;
    }

    // ------------------------------------------------------------------ 节气

    /** 当天节气名，无则 null；year 超出表范围返回 null */
    public static String termOf(int y, int m, int d) {
        if (!inRange(y)) {
            return null;
        }
        int base = (y - LunarTables.YEAR_MIN) * 24;
        for (int n = (m - 1) * 2; n <= (m - 1) * 2 + 1; n++) {
            if (LunarTables.TERM_DAYS[base + n] == d) {
                return LunarTables.TERM_NAMES[n];
            }
        }
        return null;
    }

    /** 下一个节气名称与相隔天数（1..366），找不到返回 null */
    public static String[] nextTerm(int y, int m, int d, int[] outDays) {
        int jdn = toJdn(y, m, d);
        for (int k = 1; k <= 40; k++) {
            int[] s = fromJdn(jdn + k);
            String t = termOf(s[0], s[1], s[2]);
            if (t != null) {
                if (outDays != null) {
                    outDays[0] = k;
                }
                return new String[]{t, String.valueOf(k)};
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 节日

    private static final String[][] SOLAR_FESTIVALS = {
            {"1", "1", "元旦"}, {"2", "14", "情人节"}, {"3", "8", "妇女节"}, {"3", "12", "植树节"},
            {"4", "1", "愚人节"}, {"5", "1", "劳动节"}, {"5", "4", "青年节"}, {"6", "1", "儿童节"},
            {"7", "1", "建党节"}, {"8", "1", "建军节"}, {"9", "10", "教师节"}, {"10", "1", "国庆节"},
            {"12", "24", "平安夜"}, {"12", "25", "圣诞节"},
    };

    /** 农历固定节日：{月, 日, 名称} */
    private static final String[][] LUNAR_FESTIVALS = {
            {"1", "1", "春节"}, {"1", "15", "元宵节"}, {"2", "2", "龙抬头"},
            {"5", "5", "端午节"}, {"7", "7", "七夕节"}, {"7", "15", "中元节"},
            {"8", "15", "中秋节"}, {"9", "9", "重阳节"}, {"12", "8", "腊八节"},
            {"12", "23", "小年"},
    };

    /** 当天节日（含农历节日、除夕、母亲节/父亲节/感恩节），无则 null */
    public static String festivalOf(int y, int m, int d, LunarCalendar lunar) {
        int jdn = toJdn(y, m, d);
        for (String[] f : SOLAR_FESTIVALS) {
            if (Integer.parseInt(f[0]) == m && Integer.parseInt(f[1]) == d) {
                return f[2];
            }
        }
        // 母亲节：5 月第 2 个周日；父亲节：6 月第 3 个周日；感恩节：11 月第 4 个周四
        if (m == 5 && isNthWeekday(y, m, d, 7, 2)) {
            return "母亲节";
        }
        if (m == 6 && isNthWeekday(y, m, d, 7, 3)) {
            return "父亲节";
        }
        if (m == 11 && isNthWeekday(y, m, d, 4, 4)) {
            return "感恩节";
        }
        if (lunar == null || lunar.leap) {
            return null;
        }
        for (String[] f : LUNAR_FESTIVALS) {
            if (Integer.parseInt(f[0]) == lunar.month && Integer.parseInt(f[1]) == lunar.day) {
                return f[2];
            }
        }
        // 除夕：腊月的最后一天
        if (lunar.month == 12) {
            int last = monthDays(lunar.year, 12);
            if (lunar.day == last) {
                return "除夕";
            }
        }
        return null;
    }

    /** d 是否为该月第 n 个星期 weekday（Calendar 常量，周日=1 ... 周六=7） */
    private static boolean isNthWeekday(int y, int m, int d, int weekday, int n) {
        int firstJdn = toJdn(y, m, 1);
        int firstDow = mod(firstJdn + 1, 7);   // 1970-01-01 是周四 -> jdn+1 mod 7: 0=周日
        int dow = mod(toJdn(y, m, d) + 1, 7);
        int want = weekday % 7;                // 周日=0
        if (dow != want) {
            return false;
        }
        int firstOccur = firstDow == want ? 1 : 1 + (want - firstDow + 7) % 7;
        return d == firstOccur + 7 * (n - 1);
    }

    /**
     * 未来最近的节日：返回 {名称, 还有几天}，60 天内没有则返回 null。
     */
    public static String[] nextFestival(int y, int m, int d) {
        int jdn = toJdn(y, m, d);
        for (int k = 1; k <= 90; k++) {
            int[] s = fromJdn(jdn + k);
            LunarCalendar lc = inRange(s[0]) ? fromSolar(s[0], s[1], s[2]) : null;
            String f = festivalOf(s[0], s[1], s[2], lc);
            if (f != null) {
                return new String[]{f, String.valueOf(k)};
            }
        }
        return null;
    }

    /** 供 UI 使用的紧凑描述：农历八月初五 */
    public String shortText() {
        return monthName + dayName;
    }

    @Override
    public String toString() {
        return year + "年" + monthName + dayName + (leap ? "(闰)" : "");
    }
}
