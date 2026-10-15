package com.vincent.wallclock.gen;

/**
 * 光线感应的判断规则。
 *
 * <p>刻意不 import android.*：屏幕方向、阈值、滞回这些算术最容易出错，而真机上极难复现，
 * 所以抽到这里，桌面 javac 直接跑 {@code tools/TestLightRule.java} 回归。
 *
 * <p>为什么要滞回（hysteresis）：光线传感器在阈值附近会持续抖动，
 * 只用一个阈值的话背景色会在几秒内反复闪。这里规定
 * 「低于阈值 → 深色」「高于阈值的 {@link #HYSTERESIS} 倍 → 浅色」「中间维持现状」，
 * 于是任何单调变化的光照最多只会引起一次切换。
 */
public final class LightRule {

    /** 没有传感器 / 还没有读数 */
    public static final float UNKNOWN = -1f;

    public static final int LUX_MIN = 0;
    public static final int LUX_MAX = 120;
    public static final int LUX_STEP = 5;
    public static final int LUX_DEFAULT = 20;

    /** 从深色回到浅色所需的倍数：阈值 20 lux 时，要亮到 60 lux 才切回浅色 */
    public static final float HYSTERESIS = 3f;

    /** 每秒向最新读数靠拢的比例 */
    public static final float SMOOTH = 0.4f;

    private LightRule() {
    }

    public static int clampLux(int v) {
        if (v < LUX_MIN) {
            return LUX_MIN;
        }
        if (v > LUX_MAX) {
            return LUX_MAX;
        }
        return v;
    }

    /** 把阈值对齐到 seek 的步长 */
    public static int snapLux(int v) {
        int c = clampLux(v);
        if (LUX_STEP <= 0) {
            return c;
        }
        int n = Math.round((float) (c - LUX_MIN) / LUX_STEP);
        return clampLux(LUX_MIN + n * LUX_STEP);
    }

    public static boolean usable(float lux) {
        return lux >= 0f && !Float.isNaN(lux);
    }

    /**
     * 指数滑动平均。上一帧未知时直接采用新读数，避免从 -1 缓慢爬升。
     */
    public static float smooth(float prev, float sample) {
        return smooth(prev, sample, SMOOTH);
    }

    public static float smooth(float prev, float sample, float alpha) {
        if (!usable(sample)) {
            return usable(prev) ? prev : UNKNOWN;
        }
        float a = Math.max(0f, Math.min(1f, alpha));
        if (!usable(prev)) {
            return sample;
        }
        return prev + (sample - prev) * a;
    }

    /**
     * 是否需要切到深色。
     *
     * @param lux           当前环境照度，见 {@link #UNKNOWN}
     * @param darkLux       阈值；{@code <= 0} 表示关闭该功能
     * @param currentlyDark 上一次的判定结果，用于滞回
     */
    public static boolean wantsDark(float lux, int darkLux, boolean currentlyDark) {
        int th = clampLux(darkLux);
        if (th <= 0) {
            return false;                 // 0 表示永不触发
        }
        if (!usable(lux)) {
            return currentlyDark;         // 没有读数就不要乱动
        }
        if (lux < th) {
            return true;
        }
        if (lux > th * HYSTERESIS) {
            return false;
        }
        return currentlyDark;             // 滞回带内维持现状
    }
}
