package com.vincent.wallclock;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * 开机自启动。
 *
 * <p>旧手机插着电挂墙上时，断电再来电是常态，所以这里要保证「开机后自己回到挂钟界面」。
 * 三类广播都收：
 * <ul>
 *   <li>{@code BOOT_COMPLETED}：正常开机（加密设备上要等首次解锁之后才会收到）；</li>
 *   <li>{@code LOCKED_BOOT_COMPLETED}：直接启动阶段就收到，能在解锁前抢跑。本接收器
 *       声明了 {@code directBootAware}，此时读设置会失败，所以读设置一律兜底为默认值；</li>
 *   <li>{@code QUICKBOOT_POWERON}（含 HTC 私有前缀）：部分 ROM 的「快速开机」只发这个，
 *       不发标准的 BOOT_COMPLETED。原来代码里判断了这两个 Action，但清单里没声明，
 *       分支永远不会走到——这里补上声明。</li>
 * </ul>
 *
 * <p>收到后不立即启动，而是延迟几秒：开机瞬间系统服务还没起完，这时候 startActivity
 * 容易被拦或者白屏。延迟用 {@link AlarmManager} 而不是 {@code Thread.sleep}，
 * 因为广播接收器不能阻塞（会 ANR），而且进程随时可能被回收。
 */
public class BootReceiver extends BroadcastReceiver {

    /** 开机后等系统稳定再拉界面 */
    private static final long START_DELAY_MS = 7000L;

    /** 自己发给自己的延迟启动信号 */
    static final String ACTION_LAUNCH = "com.vincent.wallclock.BOOT_LAUNCH";

    private static final int REQ_LAUNCH = 0x5A18;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) {
            return;
        }
        String action = intent == null ? null : intent.getAction();
        if (ACTION_LAUNCH.equals(action)) {
            launch(context);
            return;
        }
        if (!isBootAction(action) || !autoStartEnabled(context)) {
            return;
        }
        // 断电重启后闹钟本来就丢了，开机时顺手把看门狗续上
        if (watchdogEnabled(context)) {
            Watchdog.schedule(context);
        }
        scheduleLaunch(context);
    }

    /**
     * 读设置。直接启动（未解锁）阶段凭据加密存储还不可用，
     * {@link Prefs} 会抛异常，这时按默认值（开）放行——
     * 一个挂钟宁可在开机时多启动一次，也不要因为读不到设置而彻底不启动。
     */
    private static boolean autoStartEnabled(Context ctx) {
        try {
            return new Prefs(ctx).autoStart;
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean watchdogEnabled(Context ctx) {
        try {
            return new Prefs(ctx).watchdog;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isBootAction(String action) {
        return Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.LOCKED_BOOT_COMPLETED".equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action);
    }

    private void scheduleLaunch(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            launch(ctx);
            return;
        }
        long at = System.currentTimeMillis() + START_DELAY_MS;
        PendingIntent pi = pending(ctx, PendingIntent.FLAG_UPDATE_CURRENT);
        if (pi == null) {
            launch(ctx);
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Throwable t) {
            try {
                am.set(AlarmManager.RTC_WAKEUP, at, pi);
            } catch (Throwable ignored) {
                launch(ctx);          // 闹钟彻底不可用就直接拉，尽力而为
            }
        }
    }

    private static PendingIntent pending(Context ctx, int flags) {
        try {
            Intent i = new Intent(ctx, BootReceiver.class);
            i.setAction(ACTION_LAUNCH);
            return PendingIntent.getBroadcast(ctx, REQ_LAUNCH, i, flags);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void launch(Context ctx) {
        if (!autoStartEnabled(ctx)) {
            return;               // 延迟这几秒里用户可能把开关关了
        }
        WatchdogReceiver.launch(ctx);
    }

    /**
     * 开关「开机自动启动」时同步启用/停用本接收器。
     *
     * <p>只改设置里的布尔值是不够的：接收器仍然会被开机广播唤醒（拉起一个进程、
     * 走一圈判断再退出），既费电又没意义。直接停用组件，系统在开机时就不会来找我们。
     *
     * <p>{@code DONT_KILL_APP}：改组件状态默认会杀掉本进程，而用户往往是
     * 在设置页里拨这个开关，被踢出去体验很差。
     */
    public static void setEnabled(Context ctx, boolean enabled) {
        if (ctx == null) {
            return;
        }
        try {
            ComponentName cn = new ComponentName(ctx, BootReceiver.class);
            int state = enabled
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            ctx.getPackageManager().setComponentEnabledSetting(
                    cn, state, PackageManager.DONT_KILL_APP);
        } catch (Throwable ignored) {
        }
    }
}
