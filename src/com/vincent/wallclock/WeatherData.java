package com.vincent.wallclock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 天气数据模型 + Open-Meteo 拉取。全部字段为公制原始值，显示时再换算。
 *
 * <p>一次请求同时取回三层数据，分别服务三个页面：
 * <ul>
 *   <li>{@code current} + {@code daily[0..3]} → 挂钟主页面的天气卡片与三天预报</li>
 *   <li>{@code hourly} 中从当前整点起的 24 小时 → 天气详情页的温度曲线</li>
 *   <li>{@code daily[0..6]} → 天气详情页的七天预报</li>
 * </ul>
 * 空气质量另走一个接口，失败不影响主流程。
 */
public class WeatherData {

    public boolean valid = false;
    public long updateTime = 0L;
    public String error = null;
    public String cityName = "";

    /** 该坐标所属时区的 UTC 偏移（秒），用于把接口返回的本地时间对齐到设备时间 */
    public int utcOffsetSeconds = Integer.MIN_VALUE;

    // ------------------------------------------------------------------ 当前实况

    public double temp = Double.NaN;
    public double feelsLike = Double.NaN;
    public int humidity;
    public int weatherCode;
    public boolean isDay = true;
    public double windSpeedKmh;
    public int windDir;

    /** 详情页需要的补充实况，缺失一律为 NaN / -1 */
    public double precipNow = 0;              // mm
    public double cloudCover = Double.NaN;    // %
    public double pressureMsl = Double.NaN;   // hPa 海平面气压
    public double pressureSurface = Double.NaN;
    public double windGustsKmh = Double.NaN;  // km/h
    public double dewPoint = Double.NaN;      // °C
    public double visibilityKm = Double.NaN;  // km
    public int uvIndex = -1;

    // ------------------------------------------------------------------ 空气质量

    public int aqi = -1;
    public double pm25 = -1;
    public double pm10 = -1;
    public double o3 = -1;      // µg/m³
    public double no2 = -1;     // µg/m³
    public double so2 = -1;     // µg/m³
    public double co = -1;      // µg/m³
    public double dust = -1;    // µg/m³

    // ------------------------------------------------------------------ 日出日落 / 今日温度

    public String sunrise = "";
    public String sunset = "";
    public double todayMax = Double.NaN;
    public double todayMin = Double.NaN;

    // ------------------------------------------------------------------ 主页面：未来 4 天

    /** 未来 4 天（含今天）：[0] 今天，[1] 明天 ... */
    public static final int FORECAST_DAYS = 4;
    public final int[] fCode = new int[FORECAST_DAYS];
    public final double[] fMax = new double[FORECAST_DAYS];
    public final double[] fMin = new double[FORECAST_DAYS];
    public final int[] fMonth = new int[FORECAST_DAYS];
    public final int[] fDay = new int[FORECAST_DAYS];
    public final boolean[] fHas = new boolean[FORECAST_DAYS];

    // ------------------------------------------------------------------ 详情页：7 天

    public static final int DETAIL_DAYS = 7;
    public final int[] dCode = new int[DETAIL_DAYS];
    public final double[] dMax = new double[DETAIL_DAYS];
    public final double[] dMin = new double[DETAIL_DAYS];
    public final int[] dPrecipProb = new int[DETAIL_DAYS];   // %，-1 表示无数据
    public final int[] dUv = new int[DETAIL_DAYS];           // -1 表示无数据
    public final int[] dMonth = new int[DETAIL_DAYS];
    public final int[] dDay = new int[DETAIL_DAYS];
    public final int[] dDow = new int[DETAIL_DAYS];          // 0=周日
    public final String[] dSunrise = new String[DETAIL_DAYS];
    public final String[] dSunset = new String[DETAIL_DAYS];
    public final boolean[] dHas = new boolean[DETAIL_DAYS];

    // ------------------------------------------------------------------ 详情页：24 小时

    /** 从当前整点起的逐小时数据 */
    public static final int HOURS = 24;
    public final String[] hTime = new String[HOURS];         // "14:00"
    public final double[] hTemp = new double[HOURS];
    public final int[] hCode = new int[HOURS];
    public final int[] hPrecipProb = new int[HOURS];         // %
    public final boolean[] hDay = new boolean[HOURS];
    public int hCount = 0;

    // ------------------------------------------------------------------ 展示换算

    /** 0 摄氏度，1 华氏度。绘制前由界面写入。 */
    public int unit = 0;

    public String formatTemp(double c) {
        if (Double.isNaN(c)) {
            return "--";
        }
        return Math.round(convert(c)) + "°";
    }

    public String formatRange(double min, double max) {
        if (Double.isNaN(min) || Double.isNaN(max)) {
            return "--";
        }
        return Math.round(convert(min)) + "° / " + Math.round(convert(max)) + "°";
    }

    public String formatTempPlain(double c) {
        if (Double.isNaN(c)) {
            return "--";
        }
        return Math.round(convert(c)) + "°";
    }

    private double convert(double c) {
        return unit == 1 ? c * 9.0 / 5.0 + 32.0 : c;
    }

    /** 体感 / 湿度 / 风的组合子行 */
    public String detailLine() {
        StringBuilder sb = new StringBuilder();
        sb.append("体感 ").append(formatTemp(feelsLike));
        sb.append("　湿度 ").append(humidity).append("%");
        if (windSpeedKmh > 0) {
            sb.append("　").append(Wmo.windDir(windDir)).append("风 ")
              .append(Wmo.beaufort(windSpeedKmh)).append("级");
        }
        return sb.toString();
    }

    /** "东南风 3 级" */
    public String windText() {
        if (Double.isNaN(windSpeedKmh)) {
            return "--";
        }
        return Wmo.windDir(windDir) + "风 " + Wmo.beaufort(windSpeedKmh) + " 级";
    }

    /** "15.2 km/h" */
    public String windSpeedText() {
        if (Double.isNaN(windSpeedKmh)) {
            return "--";
        }
        return trim1(windSpeedKmh) + " km/h";
    }

    public String gustText() {
        if (Double.isNaN(windGustsKmh) || windGustsKmh <= 0) {
            return "--";
        }
        return trim1(windGustsKmh) + " km/h";
    }

    public String pressureText() {
        if (Double.isNaN(pressureMsl)) {
            return "--";
        }
        return Math.round(pressureMsl) + " hPa";
    }

    public String visibilityText() {
        if (Double.isNaN(visibilityKm)) {
            return "--";
        }
        if (visibilityKm >= 10) {
            return "≥ 10 km";
        }
        if (visibilityKm >= 1) {
            return trim1(visibilityKm) + " km";
        }
        return Math.round(visibilityKm * 1000) + " m";
    }

    public String cloudText() {
        if (Double.isNaN(cloudCover)) {
            return "--";
        }
        return Math.round(cloudCover) + "%";
    }

    public String dewPointText() {
        if (Double.isNaN(dewPoint)) {
            return "--";
        }
        return formatTemp(dewPoint);
    }

    public String precipText() {
        if (precipNow <= 0) {
            return "无";
        }
        return trim1(precipNow) + " mm";
    }

    public String humidityText() {
        return humidity + "%";
    }

    /** 紫外线：接口给的是 0..11+ 的整数 */
    public String uvText() {
        if (uvIndex < 0) {
            return "--";
        }
        return uvIndex + " " + uvLevelName(uvIndex);
    }

    public static String uvLevelName(int uv) {
        if (uv <= 2) {
            return "最弱";
        }
        if (uv <= 4) {
            return "弱";
        }
        if (uv <= 6) {
            return "中等";
        }
        if (uv <= 9) {
            return "强";
        }
        return "很强";
    }

    public static int uvColor(int uv) {
        if (uv < 0) {
            return 0xFF8899A6;
        }
        if (uv <= 2) {
            return 0xFF3FD16A;
        }
        if (uv <= 4) {
            return 0xFFE8D44D;
        }
        if (uv <= 6) {
            return 0xFFFF9E3D;
        }
        if (uv <= 9) {
            return 0xFFFF5A5A;
        }
        return 0xFFC24BD6;
    }

    /** 一氧化碳接口单位是 µg/m³，换算成 mg/m³ 更好读 */
    public String coText() {
        if (co < 0) {
            return "--";
        }
        if (co >= 1000) {
            return trim1(co / 1000.0) + " mg/m³";
        }
        return Math.round(co) + " µg/m³";
    }

    /** 污染物浓度统一文本 */
    public static String concText(double v) {
        if (v < 0) {
            return "--";
        }
        return Math.round(v) + " µg/m³";
    }

    private static String trim1(double v) {
        double r = Math.round(v * 10.0) / 10.0;
        if (r == Math.floor(r)) {
            return String.valueOf((long) r);
        }
        return String.format(Locale.US, "%.1f", r);
    }

    // ------------------------------------------------------------------ 网络

    private static final String FORECAST_URL = "https://api.open-meteo.com/v1/forecast";
    private static final String AIR_URL = "https://air-quality-api.open-meteo.com/v1/air-quality";

    private static final String HOURLY_VARS =
            "temperature_2m,relative_humidity_2m,weather_code,precipitation_probability,"
                    + "is_day,dew_point_2m,visibility,uv_index";
    private static final String DAILY_VARS =
            "weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,"
                    + "precipitation_probability_max,precipitation_sum,uv_index_max";

    /**
     * 拉取天气。网络异常不抛异常，而是把原因写进 {@link #error}。
     */
    public static WeatherData fetch(double lat, double lon, String cityName) {
        WeatherData d = new WeatherData();
        d.cityName = cityName == null ? "" : cityName;
        try {
            String fc = FORECAST_URL
                    + "?latitude=" + fmt(lat) + "&longitude=" + fmt(lon)
                    + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,"
                    + "precipitation,weather_code,cloud_cover,pressure_msl,surface_pressure,"
                    + "wind_speed_10m,wind_direction_10m,wind_gusts_10m"
                    + "&hourly=" + HOURLY_VARS
                    + "&daily=" + DAILY_VARS
                    + "&timezone=auto&forecast_days=" + DETAIL_DAYS + "&wind_speed_unit=kmh";
            d.parseForecast(get(fc));

            // 空气质量是可选增强，失败不影响主流程
            try {
                String aq = AIR_URL + "?latitude=" + fmt(lat) + "&longitude=" + fmt(lon)
                        + "&current=pm2_5,pm10,carbon_monoxide,nitrogen_dioxide,"
                        + "sulphur_dioxide,ozone,dust&timezone=auto";
                d.parseAir(get(aq));
            } catch (Throwable ignored) {
                d.aqi = -1;
            }

            d.valid = true;
            d.updateTime = System.currentTimeMillis();
        } catch (Throwable t) {
            d.valid = false;
            d.error = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage());
        }
        return d;
    }

    static String fmt(double v) {
        return String.format(Locale.US, "%.4f", v);
    }

    private static String get(String url) throws Exception {
        // 幂等的懒加载：确保老系统上 TLS 兼容层一定先于任何 HTTPS 请求就位
        TlsCompat.install();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("User-Agent", "WallClock/1.0 (Android)");
        // Android 4.4 上必须补 TLS 1.2 + 内置根证书，否则连不上
        TlsCompat.apply(c);
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                throw new Exception("HTTP " + code);
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------------ 解析

    private void parseForecast(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        utcOffsetSeconds = root.optInt("utc_offset_seconds", Integer.MIN_VALUE);

        parseCurrent(root.getJSONObject("current"));
        JSONObject daily = root.getJSONObject("daily");
        parseDaily(daily);
        parseHourly(root.optJSONObject("hourly"));

        JSONArray sr = daily.optJSONArray("sunrise");
        JSONArray ss = daily.optJSONArray("sunset");
        if (sr != null && sr.length() > 0) {
            sunrise = hhmm(sr.optString(0, ""));
        }
        if (ss != null && ss.length() > 0) {
            sunset = hhmm(ss.optString(0, ""));
        }
    }

    private void parseCurrent(JSONObject cur) {
        temp = cur.optDouble("temperature_2m", Double.NaN);
        feelsLike = cur.optDouble("apparent_temperature", Double.NaN);
        humidity = cur.optInt("relative_humidity_2m", 0);
        weatherCode = cur.optInt("weather_code", 3);
        isDay = cur.optInt("is_day", 1) == 1;
        windSpeedKmh = cur.optDouble("wind_speed_10m", Double.NaN);
        windDir = (int) Math.round(cur.optDouble("wind_direction_10m", 0));
        precipNow = cur.optDouble("precipitation", 0);
        cloudCover = cur.optDouble("cloud_cover", Double.NaN);
        pressureMsl = cur.optDouble("pressure_msl", Double.NaN);
        pressureSurface = cur.optDouble("surface_pressure", Double.NaN);
        windGustsKmh = cur.optDouble("wind_gusts_10m", Double.NaN);
    }

    private void parseDaily(JSONObject daily) {
        JSONArray codes = daily.optJSONArray("weather_code");
        JSONArray maxs = daily.optJSONArray("temperature_2m_max");
        JSONArray mins = daily.optJSONArray("temperature_2m_min");
        JSONArray times = daily.optJSONArray("time");
        JSONArray probs = daily.optJSONArray("precipitation_probability_max");
        JSONArray uvs = daily.optJSONArray("uv_index_max");
        JSONArray srs = daily.optJSONArray("sunrise");
        JSONArray sss = daily.optJSONArray("sunset");
        if (codes == null || times == null) {
            return;
        }

        int n = Math.min(DETAIL_DAYS, times.length());
        for (int i = 0; i < n; i++) {
            dHas[i] = true;
            dCode[i] = codes.optInt(i, 3);
            dMax[i] = maxs == null ? Double.NaN : maxs.optDouble(i, Double.NaN);
            dMin[i] = mins == null ? Double.NaN : mins.optDouble(i, Double.NaN);
            dPrecipProb[i] = probs == null ? -1 : probs.optInt(i, -1);
            dUv[i] = uvs == null ? -1 : (int) Math.round(uvs.optDouble(i, -1));
            dSunrise[i] = srs == null ? "" : hhmm(srs.optString(i, ""));
            dSunset[i] = sss == null ? "" : hhmm(sss.optString(i, ""));

            int[] ymd = splitDate(times.optString(i, ""));
            dMonth[i] = ymd[1];
            dDay[i] = ymd[2];
            dDow[i] = ymd[0] > 0 ? dowOf(ymd[0], ymd[1], ymd[2]) : 0;
        }

        // 主页面的三天预报沿用前 4 天
        int m = Math.min(FORECAST_DAYS, n);
        for (int i = 0; i < m; i++) {
            fHas[i] = dHas[i];
            fCode[i] = dCode[i];
            fMax[i] = dMax[i];
            fMin[i] = dMin[i];
            fMonth[i] = dMonth[i];
            fDay[i] = dDay[i];
        }
        todayMax = dHas[0] ? dMax[0] : Double.NaN;
        todayMin = dHas[0] ? dMin[0] : Double.NaN;
        if (dUv[0] >= 0) {
            uvIndex = dUv[0];
        }
    }

    /**
     * 逐小时数据：接口按 {@code timezone=auto} 给的是**该地时区**的本地时间，
     * 从当天 00:00 开始共 168 条。这里要挑出「设备当前所在的那个整点」往后 24 条。
     *
     * <p>正常情况设备时区与城市一致，直接字符串匹配即可；匹配不到（比如手动指定了
     * 异国城市）就退化成第一条，宁可时间轴起点不准，也不让页面空着。
     */
    private void parseHourly(JSONObject hourly) {
        if (hourly == null) {
            return;
        }
        JSONArray times = hourly.optJSONArray("time");
        if (times == null || times.length() == 0) {
            return;
        }
        JSONArray temps = hourly.optJSONArray("temperature_2m");
        JSONArray codes = hourly.optJSONArray("weather_code");
        JSONArray probs = hourly.optJSONArray("precipitation_probability");
        JSONArray days = hourly.optJSONArray("is_day");
        JSONArray dews = hourly.optJSONArray("dew_point_2m");
        JSONArray viss = hourly.optJSONArray("visibility");
        JSONArray uvs = hourly.optJSONArray("uv_index");

        String nowKey = localHourKey();
        int start = indexOfHour(times, nowKey);
        int available = Math.min(HOURS, times.length() - start);
        if (available <= 0) {
            return;
        }
        hCount = available;
        for (int i = 0; i < available; i++) {
            int src = start + i;
            hTime[i] = hhmm(times.optString(src, ""));
            hTemp[i] = temps == null ? Double.NaN : temps.optDouble(src, Double.NaN);
            hCode[i] = codes == null ? 3 : codes.optInt(src, 3);
            hPrecipProb[i] = probs == null ? -1 : probs.optInt(src, -1);
            hDay[i] = days == null || days.optInt(src, 1) == 1;
        }

        // 实况里没有的露点 / 能见度 / 紫外线，从当前整点那一格补
        if (Double.isNaN(dewPoint) && dews != null) {
            dewPoint = dews.optDouble(start, Double.NaN);
        }
        if (Double.isNaN(visibilityKm) && viss != null) {
            double meters = viss.optDouble(start, Double.NaN);
            visibilityKm = Double.isNaN(meters) ? Double.NaN : meters / 1000.0;
        }
        if (uvs != null) {
            double uv = uvs.optDouble(start, Double.NaN);
            if (!Double.isNaN(uv)) {
                uvIndex = Math.max(uvIndex, (int) Math.round(uv));
            }
        }
    }

    private void parseAir(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONObject cur = root.getJSONObject("current");
        pm25 = cur.optDouble("pm2_5", -1);
        pm10 = cur.optDouble("pm10", -1);
        o3 = cur.optDouble("ozone", -1);
        no2 = cur.optDouble("nitrogen_dioxide", -1);
        so2 = cur.optDouble("sulphur_dioxide", -1);
        co = cur.optDouble("carbon_monoxide", -1);
        dust = cur.optDouble("dust", -1);
        aqi = AqiUtil.estimate(pm25, pm10);
    }

    // ------------------------------------------------------------------ 小工具

    /** 设备当前的 "yyyy-MM-ddTHH:00"，用来在逐小时数组里定位起点 */
    private static String localHourKey() {
        Calendar c = Calendar.getInstance();
        return String.format(Locale.US, "%04d-%02d-%02dT%02d:00",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY));
    }

    /** 精确匹配优先；退而求其次找第一条不小于目标时刻的；再不行就 0 */
    static int indexOfHour(JSONArray times, String key) {
        int firstAfter = -1;
        for (int i = 0; i < times.length(); i++) {
            String t = times.optString(i, "");
            if (t.length() < 13) {
                continue;
            }
            String norm = t.substring(0, 13) + ":00";
            if (norm.equals(key)) {
                return i;
            }
            if (firstAfter < 0 && norm.compareTo(key) > 0) {
                firstAfter = i;
            }
        }
        return firstAfter < 0 ? 0 : firstAfter;
    }

    static int[] splitDate(String iso) {
        // "2026-09-15"
        int[] r = new int[]{0, 0, 0};
        if (iso == null || iso.length() < 10) {
            return r;
        }
        try {
            r[0] = Integer.parseInt(iso.substring(0, 4));
            r[1] = Integer.parseInt(iso.substring(5, 7));
            r[2] = Integer.parseInt(iso.substring(8, 10));
        } catch (Throwable ignored) {
        }
        return r;
    }

    static String hhmm(String iso) {
        // "2026-09-15T05:42"
        int i = iso == null ? -1 : iso.indexOf('T');
        if (i < 0 || iso.length() < i + 6) {
            return "";
        }
        return iso.substring(i + 1, i + 6);
    }

    /** 0=周日 ... 6=周六 */
    static int dowOf(int y, int m, int d) {
        int jdn = LunarCalendar.toJdn(y, m, d);
        int r = (jdn + 1) % 7;
        return r < 0 ? r + 7 : r;
    }

    // ------------------------------------------------------------------ 城市搜索

    /** Open-Meteo 地理编码：按中文名搜城市，返回 [名称, 省份, 纬度, 经度] */
    public static List<String[]> searchCity(String keyword) throws Exception {
        String url = "https://geocoding-api.open-meteo.com/v1/search?count=10&language=zh&format=json&name="
                + URLEncoder.encode(keyword, "UTF-8");
        String json = get(url);
        List<String[]> out = new ArrayList<String[]>();
        JSONObject root = new JSONObject(json);
        JSONArray results = root.optJSONArray("results");
        if (results == null) {
            return out;
        }
        for (int i = 0; i < results.length(); i++) {
            JSONObject o = results.getJSONObject(i);
            String name = o.optString("name", "");
            String admin = o.optString("admin1", "");
            String country = o.optString("country", "");
            String label = name;
            if (admin.length() > 0 && !admin.equals(name)) {
                label = name + " · " + admin;
            }
            if (country.length() > 0 && !"中国".equals(country)) {
                label = label + " (" + country + ")";
            }
            out.add(new String[]{label, admin, String.valueOf(o.optDouble("latitude", 0)),
                    String.valueOf(o.optDouble("longitude", 0))});
        }
        return out;
    }
}
