package com.vincent.wallclock;

import android.content.Context;
import android.content.SharedPreferences;

import com.vincent.wallclock.gen.Layout;
import com.vincent.wallclock.gen.LightRule;

/**
 * 全部可配置项。设置页与主界面共用，改完立即生效（MainActivity 在 onResume 重新读取）。
 */
public class Prefs {

    private static final String NAME = "wallclock";

    public static final int CITY_AUTO = 0;
    public static final int CITY_MANUAL = 1;

    public static final int ORIENT_AUTO = 0;
    public static final int ORIENT_LANDSCAPE = 1;
    public static final int ORIENT_PORTRAIT = 2;

    /** 副页信息量：只留核心项，一屏放下 */
    public static final int PAGE_SLIM = 0;
    /** 副页信息量：按屏幕自动（可读下限 + 必要时拆屏） */
    public static final int PAGE_AUTO = 1;
    /** 副页信息量：一项不少，需要几屏就几屏 */
    public static final int PAGE_FULL = 2;

    // ---- 主题 ----
    public boolean themeAuto = true;       // 跟随昼夜自动切换
    public boolean themeDark = true;       // themeAuto=false 时生效：true 深色
    public int darkStart = 19;             // 深色时段起（小时）
    public int darkEnd = 7;                // 深色时段止（小时）
    public boolean useSunTimes = false;    // 用真实日出日落代替固定时段
    public boolean lightAuto = true;       // 光线感应：环境变暗就切深色
    public int lightDarkLux = LightRule.LUX_DEFAULT;   // 触发阈值（lux），0 表示不触发

    // ---- 时钟 ----
    public boolean use24h = true;
    public boolean showSeconds = true;
    public int clockScale = 100;           // 时钟大小百分比 60-140
    public int clockStyle = 0;             // 时钟样式，见 gen.ClockSpec（0=现代）
    public int glowLevel = 60;             // 辉光强度 0-100，仅对发光样式生效
    public int orientation = ORIENT_AUTO;

    // ---- 日期 / 农历 ----
    public boolean showDate = true;
    public boolean showWeekday = true;
    public boolean showLunar = true;
    public boolean showTermFestival = true; // 节气与节日
    public boolean showCountdown = true;    // 距下一个节日还有几天

    // ---- 天气 ----
    public boolean showWeather = true;
    public boolean weatherDetail = true;    // 体感 / 湿度 / 风
    public boolean showForecast = true;
    public boolean showAqi = true;
    public boolean showSunTime = true;
    public boolean showCityName = true;
    public boolean showBattery = true;
    public int tempUnit = 0;                // 0 摄氏度 1 华氏度
    public int refreshMin = 30;             // 天气刷新间隔（分钟）
    public int cityMode = CITY_AUTO;
    public String cityName = "";
    public float cityLat = 39.9042f;
    public float cityLon = 116.4074f;

    // 上一次成功定位的结果，用于开机后立即出数据
    public float lastLat = 39.9042f;
    public float lastLon = 116.4074f;
    public String lastCity = "";
    public boolean hasLastLocation = false;

    // ---- 屏幕 / 防烧屏 ----
    public boolean keepScreenOn = true;
    public int brightness = -1;            // -1 跟随系统，否则 0-100
    public boolean nightDim = true;
    public int nightStart = 23;
    public int nightEnd = 6;
    public int nightBrightness = 18;       // 夜间亮度 0-100
    public boolean nightScreenOff = false; // 夜间熄灭屏幕（轻触唤醒 30 秒）
    public boolean burnInProtect = true;
    public int burnInPeriod = 60;          // 位移周期（秒）
    public int burnInRange = 12;           // 位移幅度（像素）
    public boolean showGear = true;        // 显示设置齿轮

    // ---- 副页（横滑切换）----
    public boolean pageAlmanac = true;     // 今日黄历
    public boolean pageWeatherDetail = true; // 天气详情
    public boolean pageMonth = true;       // 月历 + 点某天
    public int autoReturnSec = 30;         // 多久没操作就自动回到主页面，0 = 不自动
    public boolean touchLock = false;      // 防误触：关闭横滑翻页，且需长按 1.5 秒才进设置
    public boolean almanacFull = true;     // 黄历显示全套（神煞 / 时辰 / 方位）
    public int weekStart = 0;              // 月历一周从哪天开始：0 周日，1 周一

    // ---- 阅读（字号 / 信息量）----
    public boolean elderMode = false;      // 大字号 + 高对比：老人模式
    public int pageDetail = PAGE_AUTO;     // 副页信息量

    // ---- 开机与自愈 ----
    public boolean autoStart = true;       // 开机自动启动
    public boolean watchdog = true;        // 看门狗：不在前台时自动拉起来

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
        load();
    }

    public void load() {
        themeAuto = sp.getBoolean("themeAuto", themeAuto);
        themeDark = sp.getBoolean("themeDark", themeDark);
        darkStart = sp.getInt("darkStart", darkStart);
        darkEnd = sp.getInt("darkEnd", darkEnd);
        useSunTimes = sp.getBoolean("useSunTimes", useSunTimes);
        lightAuto = sp.getBoolean("lightAuto", lightAuto);
        lightDarkLux = LightRule.snapLux(sp.getInt("lightDarkLux", lightDarkLux));

        use24h = sp.getBoolean("use24h", use24h);
        showSeconds = sp.getBoolean("showSeconds", showSeconds);
        clockScale = sp.getInt("clockScale", clockScale);
        clockStyle = sp.getInt("clockStyle", clockStyle);
        glowLevel = Math.max(0, Math.min(100, sp.getInt("glowLevel", glowLevel)));
        orientation = sp.getInt("orientation", orientation);

        showDate = sp.getBoolean("showDate", showDate);
        showWeekday = sp.getBoolean("showWeekday", showWeekday);
        showLunar = sp.getBoolean("showLunar", showLunar);
        showTermFestival = sp.getBoolean("showTermFestival", showTermFestival);
        showCountdown = sp.getBoolean("showCountdown", showCountdown);

        showWeather = sp.getBoolean("showWeather", showWeather);
        weatherDetail = sp.getBoolean("weatherDetail", weatherDetail);
        showForecast = sp.getBoolean("showForecast", showForecast);
        showAqi = sp.getBoolean("showAqi", showAqi);
        showSunTime = sp.getBoolean("showSunTime", showSunTime);
        showCityName = sp.getBoolean("showCityName", showCityName);
        showBattery = sp.getBoolean("showBattery", showBattery);
        tempUnit = sp.getInt("tempUnit", tempUnit);
        refreshMin = sp.getInt("refreshMin", refreshMin);
        cityMode = sp.getInt("cityMode", cityMode);
        cityName = sp.getString("cityName", cityName);
        cityLat = sp.getFloat("cityLat", cityLat);
        cityLon = sp.getFloat("cityLon", cityLon);
        lastLat = sp.getFloat("lastLat", lastLat);
        lastLon = sp.getFloat("lastLon", lastLon);
        lastCity = sp.getString("lastCity", lastCity);
        hasLastLocation = sp.getBoolean("hasLastLocation", hasLastLocation);

        keepScreenOn = sp.getBoolean("keepScreenOn", keepScreenOn);
        brightness = sp.getInt("brightness", brightness);
        nightDim = sp.getBoolean("nightDim", nightDim);
        nightStart = sp.getInt("nightStart", nightStart);
        nightEnd = sp.getInt("nightEnd", nightEnd);
        nightBrightness = sp.getInt("nightBrightness", nightBrightness);
        nightScreenOff = sp.getBoolean("nightScreenOff", nightScreenOff);
        burnInProtect = sp.getBoolean("burnInProtect", burnInProtect);
        burnInPeriod = sp.getInt("burnInPeriod", burnInPeriod);
        burnInRange = sp.getInt("burnInRange", burnInRange);
        showGear = sp.getBoolean("showGear", showGear);

        pageAlmanac = sp.getBoolean("pageAlmanac", pageAlmanac);
        pageWeatherDetail = sp.getBoolean("pageWeatherDetail", pageWeatherDetail);
        pageMonth = sp.getBoolean("pageMonth", pageMonth);
        autoReturnSec = sp.getInt("autoReturnSec", autoReturnSec);
        touchLock = sp.getBoolean("touchLock", touchLock);
        almanacFull = sp.getBoolean("almanacFull", almanacFull);
        weekStart = sp.getInt("weekStart", weekStart);

        elderMode = sp.getBoolean("elderMode", elderMode);
        pageDetail = sp.getInt("pageDetail", pageDetail);
        autoStart = sp.getBoolean("autoStart", autoStart);
        watchdog = sp.getBoolean("watchdog", watchdog);
    }

    public void save() {
        SharedPreferences.Editor e = sp.edit();
        e.putBoolean("themeAuto", themeAuto);
        e.putBoolean("themeDark", themeDark);
        e.putInt("darkStart", darkStart);
        e.putInt("darkEnd", darkEnd);
        e.putBoolean("useSunTimes", useSunTimes);
        e.putBoolean("lightAuto", lightAuto);
        e.putInt("lightDarkLux", lightDarkLux);

        e.putBoolean("use24h", use24h);
        e.putBoolean("showSeconds", showSeconds);
        e.putInt("clockScale", clockScale);
        e.putInt("clockStyle", clockStyle);
        e.putInt("glowLevel", glowLevel);
        e.putInt("orientation", orientation);

        e.putBoolean("showDate", showDate);
        e.putBoolean("showWeekday", showWeekday);
        e.putBoolean("showLunar", showLunar);
        e.putBoolean("showTermFestival", showTermFestival);
        e.putBoolean("showCountdown", showCountdown);

        e.putBoolean("showWeather", showWeather);
        e.putBoolean("weatherDetail", weatherDetail);
        e.putBoolean("showForecast", showForecast);
        e.putBoolean("showAqi", showAqi);
        e.putBoolean("showSunTime", showSunTime);
        e.putBoolean("showCityName", showCityName);
        e.putBoolean("showBattery", showBattery);
        e.putInt("tempUnit", tempUnit);
        e.putInt("refreshMin", refreshMin);
        e.putInt("cityMode", cityMode);
        e.putString("cityName", cityName);
        e.putFloat("cityLat", cityLat);
        e.putFloat("cityLon", cityLon);
        e.putFloat("lastLat", lastLat);
        e.putFloat("lastLon", lastLon);
        e.putString("lastCity", lastCity);
        e.putBoolean("hasLastLocation", hasLastLocation);

        e.putBoolean("keepScreenOn", keepScreenOn);
        e.putInt("brightness", brightness);
        e.putBoolean("nightDim", nightDim);
        e.putInt("nightStart", nightStart);
        e.putInt("nightEnd", nightEnd);
        e.putInt("nightBrightness", nightBrightness);
        e.putBoolean("nightScreenOff", nightScreenOff);
        e.putBoolean("burnInProtect", burnInProtect);
        e.putInt("burnInPeriod", burnInPeriod);
        e.putInt("burnInRange", burnInRange);
        e.putBoolean("showGear", showGear);

        e.putBoolean("pageAlmanac", pageAlmanac);
        e.putBoolean("pageWeatherDetail", pageWeatherDetail);
        e.putBoolean("pageMonth", pageMonth);
        e.putInt("autoReturnSec", autoReturnSec);
        e.putBoolean("touchLock", touchLock);
        e.putBoolean("almanacFull", almanacFull);
        e.putInt("weekStart", weekStart);

        e.putBoolean("elderMode", elderMode);
        e.putInt("pageDetail", pageDetail);
        e.putBoolean("autoStart", autoStart);
        e.putBoolean("watchdog", watchdog);
        e.apply();
    }

    /** 当前字号档位（老人模式整体放大） */
    public float textScale() {
        return elderMode ? Layout.ELDER_SCALE : 1f;
    }

    public void reset() {
        sp.edit().clear().apply();
        load();
    }

    /** 判断给定小时的节点是否落在夜间区间（支持跨零点） */
    public static boolean inWindow(int nowHour, int start, int end) {
        if (start == end) {
            return false;
        }
        if (start < end) {
            return nowHour >= start && nowHour < end;
        }
        return nowHour >= start || nowHour < end;
    }
}
