package com.vincent.wallclock;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 看门狗：每隔一段时间自检一次，发现界面不在前台就把挂钟拉回来。
 *
 * <p>挂墙上的机器最怕「挂了」：进程被系统回收、被别的应用盖住、或者屏幕被点亮到别处，
 * 结果就是墙上那块屏一直停在别的东西上，而人未必马上发现。这里用一个
 * {@link AlarmManager} 的周期闹钟做自检，比常驻 Service 省电得多，
 * 而且进程被杀死后闹钟照样能把进程唤醒。
 *
 * <p>几个刻意的选择：
 * <ul>
 *   <li><b>用不精确闹钟</b>：15 分钟一次的自检不需要秒级精度，用
 *       {@code setAndAllowWhileIdle} 既能穿透 Doze，又不需要
 *       {@code SCHEDULE_EXACT_ALARM} 这个受限权限，系统还能按批次合并唤醒省电。</li>
 *   <li><b>不做常驻 Service</b>：前台服务要一直挂个通知，反而更耗电也更惹眼。</li>
 *   <li><b>只在需要时排下一次</b>：由 {@link WatchdogReceiver} 在每次自检结束后续上，
 *       而不是用 repeating 闹钟——这样中途关掉开关能立刻停。</li>
 * </ul>
 *
 * <p>能力边界（这点必须说清楚）：Android 10 以后系统限制后台应用直接启动界面，
 * 所以自检发现「不在前台」时，拉起动作<b>可能被系统忽略</b>。因此这套自愈在
 * Android 9 及更老的机器上是可靠的（而这恰好是「旧手机改挂钟」的主要人群）；
 * 在 Android 10+ 上更现实的做法是把本应用设为默认桌面，或关掉锁屏密码。
 * 设置页的「开机与自启」里有对应指引。
 */
public final class Watchdog {

    /** 自检间隔：15 分钟。太密没意义，太疏则「挂掉」会很久没人管 */
    public static final long INTERVAL_MS = 15 * 60 * 1000L;

    private static final int REQ = 0x5A17;

    private Watchdog() {
    }

    /** 按设置打开或关闭自检 */
    public static void apply(Context ctx, boolean enabled) {
        if (enabled) {
            schedule(ctx);
        } else {
            cancel(ctx);
        }
    }

    /** 排下一次自检；重复调用安全（同 requestCode 会覆盖） */
    public static void schedule(Context ctx) {
        if (ctx == null) {
            return;
        }
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        long at = System.currentTimeMillis() + INTERVAL_MS;
        PendingIntent pi = pending(ctx, PendingIntent.FLAG_UPDATE_CURRENT);
        if (pi == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Throwable t) {
            // 个别 ROM 对闹钟有限制：退化成普通 set，再不行就放弃这一次
            try {
                am.set(AlarmManager.RTC_WAKEUP, at, pi);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void cancel(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.cancel(pending(ctx, PendingIntent.FLAG_UPDATE_CURRENT));
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 目标明确指向 {@link WatchdogReceiver}，因此这个 PendingIntent 是显式的；
     * 又因为是发给自己的广播接收器，不需要 {@code FLAG_IMMUTABLE} 也不会被
     * Android 12 的隐性 Intent 限制波及（显式 Intent 不受影响）。
     */
    private static PendingIntent pending(Context ctx, int flags) {
        try {
            Intent i = new Intent(ctx, WatchdogReceiver.class);
            i.setAction(WatchdogReceiver.ACTION_TICK);
            return PendingIntent.getBroadcast(ctx, REQ, i, flags);
        } catch (Throwable t) {
            return null;
        }
    }
}
