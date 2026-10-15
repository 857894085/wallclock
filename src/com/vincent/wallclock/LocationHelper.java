package com.vincent.wallclock;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.List;

/**
 * 定位辅助：优先用缓存位置立即出结果，再依次尝试网络定位（室内可用）与 GPS，
 * 拿到一次结果就回调，并带超时兜底。
 */
public class LocationHelper {

    public interface Callback {
        void onLocation(double lat, double lon, String source);

        void onFailed(String reason);
    }

    private final Context ctx;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LocationManager lm;
    private LocationListener listener;
    private boolean done;
    private Runnable timeout;

    public LocationHelper(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public static boolean hasPermission(Context ctx) {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        return ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public void request(final Callback cb, final long timeoutMs) {
        done = false;
        try {
            lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) {
                cb.onFailed("设备不支持定位");
                return;
            }
            if (!hasPermission(ctx)) {
                cb.onFailed("没有定位权限");
                return;
            }
            if (!lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                    && !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                cb.onFailed("系统定位服务未开启");
                return;
            }

            // 1) 先用上一次的位置，秒出
            Location best = null;
            List<String> providers = lm.getProviders(true);
            for (String p : providers) {
                try {
                    Location l = lm.getLastKnownLocation(p);
                    if (l == null) {
                        continue;
                    }
                    if (best == null || l.getTime() > best.getTime()) {
                        best = l;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (best != null && System.currentTimeMillis() - best.getTime() < 6 * 3600 * 1000L) {
                finish(best.getLatitude(), best.getLongitude(), "缓存定位", cb);
                return;
            }

            listener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    if (location != null) {
                        finish(location.getLatitude(), location.getLongitude(), "定位", cb);
                    }
                }

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {
                }

                @Override
                public void onProviderEnabled(String provider) {
                }

                @Override
                public void onProviderDisabled(String provider) {
                }
            };

            // 2) 网络定位优先（室内更快），再叠加 GPS
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, listener, Looper.getMainLooper());
            }
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper());
            }

            timeout = new Runnable() {
                @Override
                public void run() {
                    if (done) {
                        return;
                    }
                    // 超时前再捞一次缓存
                    Location l = lastKnownAny();
                    if (l != null) {
                        finish(l.getLatitude(), l.getLongitude(), "缓存定位", cb);
                    } else {
                        stop();
                        done = true;
                        cb.onFailed("定位超时");
                    }
                }
            };
            handler.postDelayed(timeout, timeoutMs);
        } catch (Throwable t) {
            cb.onFailed(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private Location lastKnownAny() {
        try {
            Location best = null;
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) {
                    best = l;
                }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    private void finish(double lat, double lon, String source, Callback cb) {
        if (done) {
            return;
        }
        done = true;
        stop();
        cb.onLocation(lat, lon, source);
    }

    public void stop() {
        try {
            if (lm != null && listener != null) {
                lm.removeUpdates(listener);
            }
        } catch (Throwable ignored) {
        }
        if (timeout != null) {
            handler.removeCallbacks(timeout);
            timeout = null;
        }
    }
}
