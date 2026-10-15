package com.vincent.wallclock;

import java.util.ArrayList;
import java.util.List;

/**
 * 生活指数。全部由已有气象要素在本地推算，不额外请求接口——老机器上少一次网络往返，
 * 也避免再多依赖一个第三方数据源。
 *
 * <p>推算口径是常见的行业经验值（气温分档、湿度分档、降水概率分档），
 * 结论只作参考，页面上也会标明这一点。
 */
public final class LifeIndex {

    /** 等级色：0 不宜 / 1 一般 / 2 较适宜 / 3 适宜 */
    public static final int C_BAD = 0xFFE45B4C;
    public static final int C_SOSO = 0xFFE09A3A;
    public static final int C_OK = 0xFFC7B03A;
    public static final int C_GOOD = 0xFF3FBF6A;
    public static final int C_NONE = 0xFF8899A6;

    public final String name;
    public final String level;
    public final String advice;
    public final int color;

    private LifeIndex(String name, String level, String advice, int color) {
        this.name = name;
        this.level = level;
        this.advice = advice;
        this.color = color;
    }

    public static List<LifeIndex> of(WeatherData w) {
        List<LifeIndex> list = new ArrayList<LifeIndex>();
        double t = Double.isNaN(w.feelsLike) ? w.temp : w.feelsLike;
        int code = w.weatherCode;
        int prob = w.dHas[0] ? w.dPrecipProb[0] : -1;
        int prob48 = maxProb(w, 2);
        boolean wet = isWet(code);

        list.add(clothing(t));
        list.add(uv(w.uvIndex));
        list.add(cold(t, w.humidity, wet));
        list.add(sport(code, t, prob));
        list.add(carWash(prob48));
        list.add(comfort(t, w.humidity));
        list.add(drying(code, w.humidity, w.windSpeedKmh));
        list.add(air(w.aqi));
        return list;
    }

    // ------------------------------------------------------------------ 各项

    private static LifeIndex clothing(double t) {
        if (Double.isNaN(t)) {
            return none("穿衣");
        }
        if (t >= 30) {
            return new LifeIndex("穿衣", "炎热", "短袖短裤，注意防暑补水", C_BAD);
        }
        if (t >= 25) {
            return new LifeIndex("穿衣", "热", "短袖薄衫，室内可加薄外套", C_SOSO);
        }
        if (t >= 20) {
            return new LifeIndex("穿衣", "舒适", "长袖衬衫或薄衫即可", C_GOOD);
        }
        if (t >= 15) {
            return new LifeIndex("穿衣", "较凉", "薄外套 / 针织衫", C_GOOD);
        }
        if (t >= 8) {
            return new LifeIndex("穿衣", "凉", "夹克、风衣或薄毛衣", C_OK);
        }
        if (t >= 0) {
            return new LifeIndex("穿衣", "冷", "厚外套 + 毛衣，注意保暖", C_SOSO);
        }
        return new LifeIndex("穿衣", "寒冷", "羽绒服 / 棉衣，做好防寒", C_BAD);
    }

    private static LifeIndex uv(int uv) {
        if (uv < 0) {
            return none("紫外线");
        }
        if (uv <= 2) {
            return new LifeIndex("紫外线", "最弱", "无需特别防护", C_GOOD);
        }
        if (uv <= 4) {
            return new LifeIndex("紫外线", "弱", "长时间户外建议戴帽子", C_GOOD);
        }
        if (uv <= 6) {
            return new LifeIndex("紫外线", "中等", "涂 SPF15+ 防晒霜", C_OK);
        }
        if (uv <= 9) {
            return new LifeIndex("紫外线", "强", "SPF30+ 防晒，避开午间", C_SOSO);
        }
        return new LifeIndex("紫外线", "很强", "尽量待在阴凉处，全面防晒", C_BAD);
    }

    private static LifeIndex cold(double t, int humidity, boolean wet) {
        if (Double.isNaN(t)) {
            return none("感冒");
        }
        int score = 0;
        if (t < 5) {
            score += 2;
        } else if (t < 12) {
            score += 1;
        }
        if (t > 30) {
            score += 1;             // 闷热吹空调同样易感冒
        }
        if (humidity < 30 || humidity > 85) {
            score += 1;
        }
        if (wet) {
            score += 1;
        }
        if (score >= 3) {
            return new LifeIndex("感冒", "易发", "温差大，注意增减衣物", C_BAD);
        }
        if (score >= 2) {
            return new LifeIndex("感冒", "较易发", "当心着凉，多喝热水", C_SOSO);
        }
        if (score >= 1) {
            return new LifeIndex("感冒", "少发", "注意开窗通风", C_OK);
        }
        return new LifeIndex("感冒", "不易发", "气象条件不利于感冒", C_GOOD);
    }

    private static LifeIndex sport(int code, double t, int prob) {
        if (Double.isNaN(t)) {
            return none("运动");
        }
        boolean bad = code >= 61 || code == 95 || code == 96 || code == 99;
        boolean hot = t >= 33;
        boolean cold = t <= -5;
        if (bad || hot || cold) {
            return new LifeIndex("运动", "不宜", bad ? "有降水，建议室内活动" : "气温极端，建议室内",
                    C_BAD);
        }
        if (prob >= 50 || code == 45 || code == 48 || t >= 30 || t <= 0) {
            return new LifeIndex("运动", "较不宜", "户外活动注意强度与时长", C_SOSO);
        }
        if (prob >= 25 || t >= 28) {
            return new LifeIndex("运动", "较适宜", "适合慢跑、快走等中低强度", C_OK);
        }
        return new LifeIndex("运动", "适宜", "天气不错，适合户外运动", C_GOOD);
    }

    private static LifeIndex carWash(int prob48) {
        if (prob48 < 0) {
            return none("洗车");
        }
        if (prob48 >= 70) {
            return new LifeIndex("洗车", "不宜", "未来两天有降水，洗了白洗", C_BAD);
        }
        if (prob48 >= 40) {
            return new LifeIndex("洗车", "较不宜", "可能有零星降水", C_SOSO);
        }
        if (prob48 >= 20) {
            return new LifeIndex("洗车", "较适宜", "注意留意天气变化", C_OK);
        }
        return new LifeIndex("洗车", "适宜", "近两天无雨，可放心洗车", C_GOOD);
    }

    private static LifeIndex comfort(double t, int humidity) {
        if (Double.isNaN(t)) {
            return none("舒适度");
        }
        // 夏季用温湿指数近似：高温 + 高湿 明显更难受
        if (t >= 28 && humidity >= 70) {
            return new LifeIndex("舒适度", "闷热", "体感黏腻，注意降温", C_BAD);
        }
        if (t >= 30) {
            return new LifeIndex("舒适度", "偏热", "阳光较强，注意补水", C_SOSO);
        }
        if (t <= 0) {
            return new LifeIndex("舒适度", "寒冷", "室外体感很冷", C_BAD);
        }
        if (t <= 8) {
            return new LifeIndex("舒适度", "偏冷", "注意保暖", C_SOSO);
        }
        if (humidity < 30) {
            return new LifeIndex("舒适度", "干燥", "空气干，注意补水保湿", C_OK);
        }
        if (t >= 18 && t <= 26 && humidity >= 40 && humidity <= 70) {
            return new LifeIndex("舒适度", "舒适", "温湿度宜人", C_GOOD);
        }
        return new LifeIndex("舒适度", "一般", "体感尚可", C_OK);
    }

    private static LifeIndex drying(int code, int humidity, double wind) {
        boolean wet = isWet(code);
        if (wet) {
            return new LifeIndex("晾晒", "不宜", "有降水，建议室内晾干", C_BAD);
        }
        if (humidity >= 85) {
            return new LifeIndex("晾晒", "较不宜", "湿度过大，不易干", C_SOSO);
        }
        if (humidity >= 70) {
            return new LifeIndex("晾晒", "较适宜", "干得偏慢，注意翻面", C_OK);
        }
        if (!Double.isNaN(wind) && wind >= 25) {
            return new LifeIndex("晾晒", "适宜", "日照通风俱佳，干得很快", C_GOOD);
        }
        return new LifeIndex("晾晒", "适宜", "条件不错，适合晾晒", C_GOOD);
    }

    private static LifeIndex air(int aqi) {
        if (aqi < 0) {
            return none("空气");
        }
        if (aqi <= 50) {
            return new LifeIndex("空气", "优", "空气清新，尽情户外", C_GOOD);
        }
        if (aqi <= 100) {
            return new LifeIndex("空气", "良", "空气质量可接受", C_GOOD);
        }
        if (aqi <= 150) {
            return new LifeIndex("空气", "轻度污染", "敏感人群减少户外", C_OK);
        }
        if (aqi <= 200) {
            return new LifeIndex("空气", "中度污染", "建议戴口罩，减少户外", C_SOSO);
        }
        return new LifeIndex("空气", "重度污染", "尽量留在室内，关好门窗", C_BAD);
    }

    // ------------------------------------------------------------------ 小工具

    private static boolean isWet(int code) {
        return code >= 51 && code <= 99;
    }

    private static int maxProb(WeatherData w, int days) {
        int best = -1;
        for (int i = 0; i < days && i < WeatherData.DETAIL_DAYS; i++) {
            if (w.dHas[i] && w.dPrecipProb[i] > best) {
                best = w.dPrecipProb[i];
            }
        }
        return best;
    }

    private static LifeIndex none(String name) {
        return new LifeIndex(name, "--", "", C_NONE);
    }
}
