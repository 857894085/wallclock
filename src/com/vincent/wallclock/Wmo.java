package com.vincent.wallclock;

/**
 * WMO 天气代码 -> 中文描述 / 图标类型 / 是否降水。
 * 参考 Open-Meteo 文档的 weather_code 定义。
 */
public final class Wmo {

    public static final int ICON_SUN = 0;
    public static final int ICON_MOON = 1;
    public static final int ICON_PARTLY = 2;
    public static final int ICON_CLOUD = 3;
    public static final int ICON_FOG = 4;
    public static final int ICON_DRIZZLE = 5;
    public static final int ICON_RAIN = 6;
    public static final int ICON_SNOW = 7;
    public static final int ICON_SLEET = 8;
    public static final int ICON_THUNDER = 9;

    private Wmo() {
    }

    public static String text(int code) {
        switch (code) {
            case 0: return "晴";
            case 1: return "晴间多云";
            case 2: return "多云";
            case 3: return "阴";
            case 45: return "有雾";
            case 48: return "雾凇";
            case 51: return "小毛毛雨";
            case 53: return "毛毛雨";
            case 55: return "大毛毛雨";
            case 56: return "冻毛毛雨";
            case 57: return "强冻毛毛雨";
            case 61: return "小雨";
            case 63: return "中雨";
            case 65: return "大雨";
            case 66: return "冻雨";
            case 67: return "强冻雨";
            case 71: return "小雪";
            case 73: return "中雪";
            case 75: return "大雪";
            case 77: return "米雪";
            case 80: return "小阵雨";
            case 81: return "阵雨";
            case 82: return "强阵雨";
            case 85: return "小阵雪";
            case 86: return "强阵雪";
            case 95: return "雷阵雨";
            case 96: return "雷阵雨伴冰雹";
            case 99: return "强雷阵雨伴冰雹";
            default: return "未知";
        }
    }

    /** 图标类型，isDay 用于区分晴/晴夜 */
    public static int icon(int code, boolean isDay) {
        switch (code) {
            case 0: return isDay ? ICON_SUN : ICON_MOON;
            case 1:
            case 2: return ICON_PARTLY;
            case 3: return ICON_CLOUD;
            case 45:
            case 48: return ICON_FOG;
            case 51:
            case 53:
            case 55:
            case 56:
            case 57: return ICON_DRIZZLE;
            case 61:
            case 63:
            case 65:
            case 80:
            case 81:
            case 82: return ICON_RAIN;
            case 66:
            case 67: return ICON_SLEET;
            case 71:
            case 73:
            case 75:
            case 77:
            case 85:
            case 86: return ICON_SNOW;
            case 95:
            case 96:
            case 99: return ICON_THUNDER;
            default: return ICON_CLOUD;
        }
    }

    /** 风力等级辅助：km/h -> 中文风力描述里的方向名 */
    public static String windDir(int deg) {
        String[] names = {"北", "东北", "东", "东南", "南", "西南", "西", "西北"};
        int idx = (int) Math.round((deg % 360) / 45.0) % 8;
        if (idx < 0) {
            idx += 8;
        }
        return names[idx];
    }

    /** 蒲福风级（由 km/h 近似换算） */
    public static int beaufort(double kmh) {
        double[] limits = {1, 5, 11, 19, 28, 38, 49, 61, 74, 88, 102, 117};
        for (int i = 0; i < limits.length; i++) {
            if (kmh < limits[i]) {
                return i;
            }
        }
        return 12;
    }
}
