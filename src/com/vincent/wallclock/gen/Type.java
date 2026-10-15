package com.vincent.wallclock.gen;

/**
 * 字号角色表：全 App 唯一的层级来源。
 *
 * <p><b>为什么要有这张表。</b>界面「该大的不大、该小的不小」，根子不在某个字号取错了，
 * 而在于每个字号都是就地拍的——吸顶干支行写 {@code font*0.70}，卡片标题写 {@code font*0.85}，
 * 正文写 {@code font*1.00}，页脚写 {@code font*0.48}。于是：
 * <ul>
 *   <li>正文 1.00 比卡片标题 0.85 还大 —— <b>层级是反的</b>，卡片标题看起来像注释；</li>
 *   <li>页面主标题 0.70，比正文还小 —— 一眼看下去整页最不重要的反而是标题；</li>
 *   <li>页脚 0.48 与正文 1.00 只差一倍，缩得不够狠，注释和正文糊在一起；</li>
 *   <li>徽标胶囊给 2.20em 的高度却只放 0.60em 的字 —— 留白被固定区吃掉，正文反而没空间。</li>
 * </ul>
 *
 * <p>下面这七级把「谁比谁大、大多少」变成数字上的硬约束：严格递减，且相邻两级至少差
 * {@link #MIN_STEP}，最高一级与最低一级至少差 {@link #MIN_CONTRAST}。
 * 上一句要是被谁改坏了，{@code tools/TestLayout.java} 会直接挂——
 * 这样「层级塌陷」就不可能悄悄回来。
 *
 * <p>用法：正文基准字号由 {@link Layout#pageFont} 给出（或首页按面板尺寸算出的基准），
 * 各处只声明自己是哪一级，不再自己乘系数。
 *
 * <p>不带任何 Android 依赖。
 */
public final class Type {

    // ------------------------------------------------------------------ 七级阶梯

    // 取值原则：**顶上去，底不动**。
    // 只把上面几级拔高、下面几级基本留在原地，对比度就出来了；
    // 如果七级一起等比放大，那只是「整页字都变大」，层级还是平的。
    // 所以 NOTE / FOOT 与改造前的 0.62 / 0.48 几乎持平，而 TITLE 从 0.70 提到 1.32。

    /** 英雄数字：一屏里唯一的「一眼看过去」的量（温度、AQI）。每页最多一处 */
    public static final float HERO = 1.58f;
    /** 页面主标题：干支日、月份、卡片里的第一个大字 */
    public static final float TITLE = 1.32f;
    /** 卡片标题：必须大于正文，否则标题就成了注释 */
    public static final float HEAD = 1.12f;
    /** 正文：词条、数值、描述 */
    public static final float BODY = 1.00f;
    /** 字段标签、表头、次要数值（日期、风向） */
    public static final float LABEL = 0.84f;
    /** 注释：吸顶的辅助行、时间刻度、天气描述 */
    public static final float NOTE = 0.68f;
    /** 页脚：数据来源这类「有它安心、没它也行」的字，该小就小到底 */
    public static final float FOOT = 0.50f;

    /** 阶梯，从大到小 */
    public static final float[] LADDER = {HERO, TITLE, HEAD, BODY, LABEL, NOTE, FOOT};

    /** 与 {@link #LADDER} 一一对应的名字，只用于测试输出 */
    public static final String[] NAME =
            {"HERO", "TITLE", "HEAD", "BODY", "LABEL", "NOTE", "FOOT"};

    /** 相邻两级的最小比值：低于它，肉眼就分不出主次了 */
    public static final float MIN_STEP = 1.10f;

    /** 最高一级与最低一级的最小比值：低于它，整页就是平的 */
    public static final float MIN_CONTRAST = 2.40f;

    private Type() {
    }

    // ------------------------------------------------------------------ 取值

    /** 非法角色退回正文，避免有人传 NaN 把整页字号搞成 0 */
    public static float norm(float role) {
        return (Float.isNaN(role) || Float.isInfinite(role) || !(role > 0f)) ? BODY : role;
    }

    /** 某角色的字号 */
    public static float size(float font, float role) {
        float f = font * norm(role);
        return (Float.isNaN(f) || !(f > 1f)) ? 1f : f;
    }

    /**
     * 某角色「一行」占的高度。
     *
     * <p>版式里所有高度都从这一处取，绘制时也按同一行高摆放，
     * 所以「分到的高度」与「字实际占的高」永远是同一个数，不会对不上。
     */
    public static float line(float font, float role) {
        return size(font, role) * Layout.LINE_RATIO;
    }

    /** n 行同角色的总高 */
    public static float lines(float font, float role, int n) {
        return line(font, role) * (n > 0 ? n : 0);
    }

    /** 若干行按给定顺序堆叠所需的总高（自上而下） */
    public static float stack(float font, float... roles) {
        float h = 0f;
        for (int i = 0; roles != null && i < roles.length; i++) {
            h += line(font, roles[i]);
        }
        return h;
    }

    /** 阶梯最上面一级与最下面一级的比值 */
    public static float contrast() {
        return HERO / FOOT;
    }

    // ------------------------------------------------------------------ 不变量（供回归断言）

    /** 阶梯是否严格递减 */
    public static boolean strictLadder() {
        for (int i = 1; i < LADDER.length; i++) {
            if (!(LADDER[i - 1] > LADDER[i])) {
                return false;
            }
        }
        return true;
    }

    /** 相邻两级是否都拉开了 {@link #MIN_STEP} */
    public static boolean readableSteps() {
        for (int i = 1; i < LADDER.length; i++) {
            if (LADDER[i - 1] / LADDER[i] < MIN_STEP) {
                return false;
            }
        }
        return true;
    }

    /** 对比度是否够 {@link #MIN_CONTRAST} */
    public static boolean enoughContrast() {
        return contrast() >= MIN_CONTRAST;
    }

    /** 找某个数值落在第几级（用于测试输出，不做版式判断） */
    public static int levelOf(float role) {
        for (int i = 0; i < LADDER.length; i++) {
            if (Math.abs(LADDER[i] - role) < 1e-4f) {
                return i;
            }
        }
        return -1;
    }
}
