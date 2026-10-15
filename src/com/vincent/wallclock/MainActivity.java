package com.vincent.wallclock;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;

import com.vincent.wallclock.gen.LightRule;

import java.util.Calendar;

/**
 * 挂钟主界面：常亮、全屏、亮度与夜间调暗、定位与天气刷新。
 */
public class MainActivity extends Activity {

    private static final int REQ_LOCATION = 1001;

    private Prefs prefs;
    private DashboardView view;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LocationHelper locationHelper;
    private WeatherData weather;
    private boolean refreshing;
    private long wakeUntil;
    private int lastAppliedSecond = -1;

    /** 上一次落到系统上的自启/看门狗开关，用来判断要不要重新下发 */
    private boolean bootSyncKnown;
    private boolean bootSynced;
    private boolean watchdogKnown;
    private boolean watchdogSynced;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            if (level >= 0 && scale > 0) {
                view.setBattery((int) (level * 100f / scale), charging);
                view.invalidate();
            }
        }
    };

    // ---- 光线感应 ----

    private SensorManager sensorManager;
    /** 光感是否已注册；onResume/onPause 会成对调用，用这个标记避免重复注册 */
    private boolean lightRegistered;

    private final SensorEventListener lightListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event != null && event.values != null && event.values.length > 0) {
                view.setAmbientLux(event.values[0]);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            int second = Calendar.getInstance().get(Calendar.SECOND);
            boolean changed = second != lastAppliedSecond;
            boolean housekeeping = false;
            if (changed) {
                lastAppliedSecond = second;
                if (second % 30 == 0) {
                    // 亮度/主题可能刚跨过夜间或整点，顺手补一帧
                    applyScreenSettings();
                    housekeeping = true;
                }
                if (second == 5) {
                    maybeRefreshWeather();
                }
            }
            // 省电：副页内容不随时间变化，主页面也只在真的会变时才重绘。
            // 原来这里无条件每秒 invalidate 一次，挂墙上一整天就是 8 万多次无谓重绘，
            // 对旧机器是实打实的耗电与发热。
            if (view.needsPerSecondDraw() || (changed && (second == 0 || housekeeping))) {
                view.invalidate();
            }
            long now = System.currentTimeMillis();
            handler.postDelayed(this, 1000L - (now % 1000L) + 8L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 老机器（API 19-24）上必须先补 TLS 1.2 与缺失的根证书，否则天气接口连不上
        TlsCompat.install();
        prefs = new Prefs(this);
        view = new DashboardView(this);
        view.setPrefs(prefs);
        view.setOnSettingsTap(new DashboardView.OnSettingsTap() {
            @Override
            public void openSettings() {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        // 天气失败时状态条上的「重试」：清掉提示、立刻重新拉一次
        view.setOnRetry(new DashboardView.OnRetry() {
            @Override
            public void retryWeather() {
                view.setError("");
                view.setStatusNote("正在重新获取天气…");
                refreshWeather(true);
            }
        });
        setContentView(view);
        locationHelper = new LocationHelper(this);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        if (Build.VERSION.SDK_INT >= 27) {
            // API 27+ 的官方做法：允许在锁屏之上显示，并在被拉起时点亮屏幕。
            // 比 FLAG_DISMISS_KEYGUARD 可靠，也不需要用户输入密码。
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        applyScreenSettings();
        checkPermissionAndRefresh();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 看门狗据此判断「界面是否已经在用户眼前」，免得把正在改设置的人拽回挂钟
        AppState.onActivityStart();
    }

    @Override
    protected void onStop() {
        super.onStop();
        AppState.onActivityStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        prefs.load();
        view.setPrefs(prefs);
        syncStartupSettings();
        applyScreenSettings();
        syncLightSensor();
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        handler.removeCallbacks(tick);
        handler.post(tick);
        maybeRefreshWeather();
    }

    /**
     * 把「开机自启」「看门狗」两个开关落到系统上。
     *
     * <p>这两项光存进 SharedPreferences 是不生效的：一个要改开机接收器的启用状态，
     * 一个要向 AlarmManager 排闹钟。只在取值真的变了的时候动手，
     * 否则每次回到界面都重排一次闹钟，反而把自检时间一直往后推。
     */
    private void syncStartupSettings() {
        if (!bootSyncKnown || bootSynced != prefs.autoStart) {
            bootSyncKnown = true;
            bootSynced = prefs.autoStart;
            BootReceiver.setEnabled(this, prefs.autoStart);
        }
        if (!watchdogKnown || watchdogSynced != prefs.watchdog) {
            watchdogKnown = true;
            watchdogSynced = prefs.watchdog;
            Watchdog.apply(this, prefs.watchdog);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        stopLightSensor();
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (locationHelper != null) {
            locationHelper.stop();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersive();
            applyScreenSettings();
        }
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (prefs != null && prefs.nightScreenOff) {
            // 夜间熄屏时，轻触唤醒一段时间
            wakeUntil = System.currentTimeMillis() + 30000L;
            applyScreenSettings();
        }
        return super.dispatchTouchEvent(ev);
    }

    // ------------------------------------------------------------------ 屏幕

    private void applyImmersive() {
        View d = getWindow().getDecorView();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        d.setSystemUiVisibility(flags);
    }

    private void applyScreenSettings() {
        // 常亮：夜间熄屏模式下也保持 WakeLock 语义上的常亮，只把背光压到 0，
        // 这样定时任务不会随 CPU 休眠而停摆，天亮能自动恢复
        if (prefs.keepScreenOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        // 方向
        switch (prefs.orientation) {
            case Prefs.ORIENT_LANDSCAPE:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                break;
            case Prefs.ORIENT_PORTRAIT:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                break;
            default:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        }

        // 亮度
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        boolean night = prefs.nightDim && Prefs.inWindow(hour, prefs.nightStart, prefs.nightEnd);
        if (prefs.nightScreenOff && isNightScreenOff()) {
            lp.screenBrightness = 0f;
        } else if (night) {
            lp.screenBrightness = Math.max(0.01f, prefs.nightBrightness / 100f);
        } else if (prefs.brightness >= 0) {
            lp.screenBrightness = Math.max(0.01f, prefs.brightness / 100f);
        } else {
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        }
        getWindow().setAttributes(lp);
        applyImmersive();
    }

    private boolean isNightScreenOff() {
        if (System.currentTimeMillis() < wakeUntil) {
            return false;
        }
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return Prefs.inWindow(hour, prefs.nightStart, prefs.nightEnd);
    }

    // ------------------------------------------------------------------ 光线感应

    /**
     * 光线感应只有在真的用得上时才注册：
     * 主题固定深色时本来就恒为深色，没必要一直听传感器（省电、也少一条常驻回调）。
     */
    private boolean lightSensorNeeded() {
        return prefs.lightAuto && (prefs.themeAuto || !prefs.themeDark);
    }

    private void syncLightSensor() {
        try {
            if (sensorManager == null) {
                sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
            }
            if (sensorManager == null) {
                view.clearAmbientLux();
                return;
            }
            if (!lightSensorNeeded()) {
                stopLightSensor();
                return;
            }
            if (lightRegistered) {
                return;
            }
            Sensor light = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
            if (light == null) {
                // 这台机器没有光感。仍然按未知处理，主题退回纯昼夜规则
                view.clearAmbientLux();
                return;
            }
            lightRegistered = sensorManager.registerListener(lightListener, light,
                    SensorManager.SENSOR_DELAY_NORMAL);
            if (!lightRegistered) {
                view.clearAmbientLux();
            }
        } catch (Throwable t) {
            lightRegistered = false;
            view.clearAmbientLux();
        }
    }

    private void stopLightSensor() {
        try {
            if (lightRegistered && sensorManager != null) {
                sensorManager.unregisterListener(lightListener);
            }
        } catch (Throwable ignored) {
        }
        lightRegistered = false;
        view.clearAmbientLux();
    }

    // ------------------------------------------------------------------ 天气

    private void checkPermissionAndRefresh() {
        if (prefs.cityMode == Prefs.CITY_AUTO && !LocationHelper.hasPermission(this)) {
            if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
            }
        }
        maybeRefreshWeather();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            if (LocationHelper.hasPermission(this)) {
                view.setStatusNote("");
                maybeRefreshWeather();
            } else {
                String note = "未授权定位，可在设置里手动选城市";
                view.setStatusNote(note);
                failIfNoData(note);
            }
        }
    }

    private synchronized void maybeRefreshWeather() {
        refreshWeather(false);
    }

    /**
     * @param force true 表示用户手动点了「重试」，跳过「刚更新过就不用再拉」的冷却判断
     */
    private synchronized void refreshWeather(boolean force) {
        if (!prefs.showWeather) {
            // 关掉天气后台就不该再请求；顺手把失败条也清掉，免得留着一条无意义的提示
            view.setError("");
            view.setStatusNote("");
            refreshing = false;
            return;
        }
        if (refreshing) {
            return;
        }
        if (!force && weather != null && weather.valid
                && System.currentTimeMillis() - weather.updateTime < Math.max(5, prefs.refreshMin) * 60000L) {
            return;
        }
        refreshing = true;

        if (prefs.cityMode == Prefs.CITY_MANUAL) {
            fetchWeather(prefs.cityLat, prefs.cityLon, prefs.cityName);
            return;
        }

        // 自动定位：先用上次结果垫着，同时后台重新定位
        if (prefs.hasLastLocation) {
            if (weather == null) {
                fetchWeather(prefs.lastLat, prefs.lastLon, prefs.lastCity);
            }
        }
        if (!LocationHelper.hasPermission(this)) {
            refreshing = false;
            String note = "未授权定位，可在设置里手动选城市";
            view.setStatusNote(note);
            failIfNoData(note);
            return;
        }
        view.setStatusNote(weather == null ? "正在定位…" : "");
        locationHelper.request(new LocationHelper.Callback() {
            @Override
            public void onLocation(double lat, double lon, String source) {
                CityRepo.City near = CityRepo.nearest(lat, lon, 80);
                String name = near != null ? near.name : "当前位置";
                prefs.lastLat = (float) lat;
                prefs.lastLon = (float) lon;
                prefs.lastCity = name;
                prefs.hasLastLocation = true;
                prefs.save();
                if (weather == null || weather.valid) {
                    view.setCityLabel(name);
                }
                fetchWeather(lat, lon, name);
            }

            @Override
            public void onFailed(String reason) {
                if (weather != null && weather.valid) {
                    view.setStatusNote("");
                    refreshing = false;
                } else if (prefs.hasLastLocation) {
                    fetchWeather(prefs.lastLat, prefs.lastLon, prefs.lastCity);
                } else {
                    String note = "定位失败：" + reason + "，可在设置里选城市";
                    view.setStatusNote(note);
                    failIfNoData(note);
                    refreshing = false;
                }
            }
        }, 20000L);
    }

    /**
     * 只有在「手上一条可用的天气数据都没有」时才弹醒目状态条。
     *
     * <p>有旧数据还硬顶着一条红色横幅挂一整天，是另一种形式的打扰；
     * 那种情况交给天气卡里那行小灰字（statusNote）就够了。
     */
    private void failIfNoData(String note) {
        if (weather == null || !weather.valid) {
            view.setError(note);
        }
    }

    private void fetchWeather(final double lat, final double lon, final String name) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final WeatherData d = WeatherData.fetch(lat, lon, name);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        refreshing = false;
                        if (d.valid) {
                            weather = d;
                            view.setWeather(d);
                            if (name != null && name.length() > 0) {
                                view.setCityLabel(name);
                            }
                            view.setStatusNote("");
                            view.setError("");
                        } else if (weather != null && weather.valid) {
                            view.setStatusNote("天气刷新失败，显示上次数据");
                        } else {
                            String note = "天气获取失败：" + (d.error == null ? "网络不可用" : d.error);
                            view.setStatusNote(note);
                            view.setError(note);
                        }
                    }
                });
            }
        }, "weather-fetch").start();
    }
}
