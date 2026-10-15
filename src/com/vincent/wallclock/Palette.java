package com.vincent.wallclock;

import com.vincent.wallclock.gen.LightRule;

/**
 * 昼夜两套配色。深色适合夜间与 OLED/LCD 抗烧屏，浅色是纸张挂历风。
 */
public class Palette {

    public final boolean dark;

    public int bg;
    public int bgGrad;
    public int panel;
    public int panelStroke;
    public int textPrimary;
    public int textSecondary;
    public int textFaint;
    public int accent;
    public int line;

    public int sun;
    public int moon;
    public int cloud;
    public int cloudLight;
    public int rain;
    public int snow;
    public int bolt;
    public int fog;

    private Palette(boolean dark) {
        this.dark = dark;
        if (dark) {
            bg = 0xFF000000;
            bgGrad = 0xFF070B10;
            panel = 0xFF0D141C;
            panelStroke = 0xFF1D2A38;
            textPrimary = 0xFFFFFFFF;
            textSecondary = 0xFFC2CFDD;
            textFaint = 0xFF7C8A99;
            accent = 0xFF4FC3F7;
            line = 0xFF1B2734;

            sun = 0xFFFFC54D;
            moon = 0xFFE8E2C8;
            cloud = 0xFF8FA3B5;
            cloudLight = 0xFFB9C7D4;
            rain = 0xFF4FC3F7;
            snow = 0xFFD6ECFA;
            bolt = 0xFFFFD54F;
            fog = 0xFF9AA9B6;
        } else {
            bg = 0xFFF6F2E8;
            bgGrad = 0xFFEDE7D9;
            panel = 0xFFFFFFFF;
            panelStroke = 0xFFE0D8C6;
            textPrimary = 0xFF18242E;
            textSecondary = 0xFF44525E;
            textFaint = 0xFF8A94A0;
            accent = 0xFF1E88C7;
            line = 0xFFE4DCCB;

            sun = 0xFFF2A400;
            moon = 0xFF9A8F6B;
            cloud = 0xFF7C8C9B;
            cloudLight = 0xFF9DAAB8;
            rain = 0xFF1E88C7;
            snow = 0xFF7FB6D9;
            bolt = 0xFFE8A100;
            fog = 0xFF8A97A3;
        }
    }

    private static final Palette DARK = new Palette(true);
    private static final Palette LIGHT = new Palette(false);

    public static Palette of(boolean dark) {
        return dark ? DARK : LIGHT;
    }

    /** 主题是否应为深色（只按昼夜时段判断） */
    public static boolean shouldBeDark(Prefs p, boolean sunriseSunsetValid, String sunrise, String sunset, int nowMinutes) {
        return shouldBeDark(p, sunriseSunsetValid, sunrise, sunset, nowMinutes, LightRule.UNKNOWN, false);
    }

    /**
     * 主题是否应为深色。
     *
     * <p>两个独立的触发条件，任一满足即为深色：
     * <ol>
     *   <li>昼夜规则：固定深色 / 日出日落 / 固定时段</li>
     *   <li>光线感应：环境照度低于阈值（{@code lightAuto} 打开时）</li>
     * </ol>
     *
     * <p>刻意只做「变暗 → 深色」这一个方向，不反向覆盖：夜里开着灯也应该保持深色，
     * 晚上突然变白底比白天偶尔用深底难受得多。选「固定深色」时本来就恒为深色。
     *
     * @param lux           环境照度，{@link LightRule#UNKNOWN} 表示没有传感器或还没读数
     * @param currentlyDark 上一帧的判定结果，用于光线阈值的滞回
     */
    public static boolean shouldBeDark(Prefs p, boolean sunriseSunsetValid, String sunrise, String sunset,
                                       int nowMinutes, float lux, boolean currentlyDark) {
        if (!p.themeAuto) {
            if (p.themeDark) {
                return true;              // 固定深色，无需再看光线
            }
        } else if (p.useSunTimes && sunriseSunsetValid) {
            int sr = parseMinutes(sunrise);
            int ss = parseMinutes(sunset);
            if (sr >= 0 && ss > sr) {
                if (nowMinutes < sr || nowMinutes >= ss) {
                    return true;
                }
            } else if (Prefs.inWindow(nowMinutes / 60, p.darkStart, p.darkEnd)) {
                return true;              // 日出日落数据不可信，退回固定时段
            }
        } else if (Prefs.inWindow(nowMinutes / 60, p.darkStart, p.darkEnd)) {
            return true;
        }

        // 到这里基准是浅色：交给光线感应决定要不要「因为太暗」而转深色
        if (!p.lightAuto || !LightRule.usable(lux)) {
            return false;
        }
        return LightRule.wantsDark(lux, p.lightDarkLux, currentlyDark);
    }

    private static int parseMinutes(String hhmm) {
        if (hhmm == null || hhmm.length() < 5) {
            return -1;
        }
        try {
            int h = Integer.parseInt(hhmm.substring(0, 2));
            int m = Integer.parseInt(hhmm.substring(3, 5));
            return h * 60 + m;
        } catch (Throwable t) {
            return -1;
        }
    }
}
