package com.vincent.wallclock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 看门狗的一次自检。
 *
 * <p>自检逻辑只有三条：
 * <ol>
 *   <li>开关被关掉了 → 取消闹钟，什么都不做；</li>
 *   <li>界面已经在前台 → 什么都不做（用户正在看，或者正在改设置）；</li>
 *   <li>界面不在前台 → 把主界面拉起来。</li>
 * </ol>
 *
 * <p>无论走哪条，最后都把下一次自检续上——闹钟是一次性的，不续就不会再响。
 */
public class WatchdogReceiver extends BroadcastReceiver {

    public static final String ACTION_TICK = "com.vincent.wallclock.WATCHDOG_TICK";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) {
            return;
        }
        boolean enabled;
        try {
            enabled = new Prefs(context).watchdog;
        } catch (Throwable t) {
            // 直接启动（未解锁）阶段读不到设置：这次先不动，等开机广播那条链路处理
            return;
        }
        if (!enabled) {
            Watchdog.cancel(context);
            return;
        }
        if (!AppState.isForeground()) {
            launch(context);
        }
        Watchdog.schedule(context);
    }

    /** 拉起主界面；被系统拦下时静默失败，等下一轮自检再试 */
    static void launch(Context context) {
        try {
            Intent i = new Intent(context, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(i);
        } catch (Throwable ignored) {
        }
    }
}
