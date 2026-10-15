package com.vincent.wallclock;

/**
 * 空气质量指数换算。
 *
 * <p>Open-Meteo 只提供 PM2.5 / PM10 浓度，这里按《环境空气质量指数(AQI)技术规定
 * HJ 633-2012》的分段线性法换算 IAQI，取两者较大值作为 AQI。
 * 说明：国标 AQI 还会综合 SO2/NO2/CO/O3，本实现只基于颗粒物，属于近似值，
 * 界面按"颗粒物推算"呈现。
 */
public final class AqiUtil {

    /** {浓度下限, 浓度上限, IAQI 下限, IAQI 上限} */
    private static final double[][] PM25 = {
            {0, 35, 0, 50}, {35, 75, 50, 100}, {75, 115, 100, 150},
            {115, 150, 150, 200}, {150, 250, 200, 300}, {250, 350, 300, 400},
            {350, 500, 400, 500},
    };
    private static final double[][] PM10 = {
            {0, 50, 0, 50}, {50, 150, 50, 100}, {150, 250, 100, 150},
            {250, 350, 150, 200}, {350, 420, 200, 300}, {420, 500, 300, 400},
            {500, 600, 400, 500},
    };

    private AqiUtil() {
    }

    private static int iaqi(double c, double[][] table) {
        if (c <= 0) {
            return 0;
        }
        for (double[] t : table) {
            if (c < t[1]) {
                double v = (t[3] - t[2]) / (t[1] - t[0]) * (c - t[0]) + t[2];
                return (int) Math.round(v);
            }
        }
        return 500;
    }

    /** 由 PM2.5 / PM10 估算 AQI，两者都为负表示无数据 */
    public static int estimate(double pm25, double pm10) {
        if (pm25 < 0 && pm10 < 0) {
            return -1;
        }
        int a = pm25 < 0 ? 0 : iaqi(pm25, PM25);
        int b = pm10 < 0 ? 0 : iaqi(pm10, PM10);
        return Math.max(a, b);
    }

    public static String level(int aqi) {
        if (aqi < 0) {
            return "";
        }
        if (aqi <= 50) {
            return "优";
        }
        if (aqi <= 100) {
            return "良";
        }
        if (aqi <= 150) {
            return "轻度污染";
        }
        if (aqi <= 200) {
            return "中度污染";
        }
        if (aqi <= 300) {
            return "重度污染";
        }
        return "严重污染";
    }

    /** 等级配色（国标六级配色，深色背景上做了提亮） */
    public static int color(int aqi) {
        if (aqi < 0) {
            return 0xFF8899A6;
        }
        if (aqi <= 50) {
            return 0xFF3FD16A;
        }
        if (aqi <= 100) {
            return 0xFFE8D44D;
        }
        if (aqi <= 150) {
            return 0xFFFF9E3D;
        }
        if (aqi <= 200) {
            return 0xFFFF5A5A;
        }
        if (aqi <= 300) {
            return 0xFFC24BD6;
        }
        return 0xFFB0304F;
    }
}
