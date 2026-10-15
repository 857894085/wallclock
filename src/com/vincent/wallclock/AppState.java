package com.vincent.wallclock;

/**
 * 进程内的「有没有界面在前台」计数。
 *
 * <p>看门狗需要一个判断依据：现在到底是「用户正在看挂钟」还是「应用被别的东西盖住了、
 * 或者进程已经死掉」。用静态计数就够了——它只在本进程内有意义，
 * 而进程被杀之后静态字段自然回到 0，正是我们希望看门狗「重新拉起来」的情形。
 *
 * <p>刻意做成计数器而不是布尔量：从主界面点进设置页时，MainActivity 会 stop、
 * SettingsActivity 会 start，如果两个都写同一个布尔的 true/false，看门狗就会在
 * 用户正在改设置的时候把主界面硬拉出来。
 */
public final class AppState {

    private static int started = 0;

    private AppState() {
    }

    public static synchronized void onActivityStart() {
        started++;
    }

    public static synchronized void onActivityStop() {
        if (started > 0) {
            started--;
        }
    }

    /** 本进程是否有界面可见（主界面或设置页都算） */
    public static synchronized boolean isForeground() {
        return started > 0;
    }
}
