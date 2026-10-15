package com.vincent.wallclock;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.vincent.wallclock.gen.ClockSpec;
import com.vincent.wallclock.gen.LightRule;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 设置页：所有项都直接读写 {@link Prefs}，改完立即保存，返回主界面即生效。
 * 界面用代码构建（无 XML 布局），旧机器上加载更快也更省内存。
 *
 * <p>几点刻意的做法：
 * <ul>
 *   <li><b>方向跟随主界面</b>：挂墙上多半是横放，如果这里硬顶着竖屏会很别扭。
 *       这里在 onCreate 里按 {@link Prefs#orientation} 设一次请求方向，
 *       和 MainActivity 用同一套取值。</li>
 *   <li><b>横屏双列</b>：横过来宽度翻倍，单列会拉成一条很长的表单，
 *       所以按「小节」为单位分到左右两列，按估算高度做贪心配平。
 *       小节不会被拆开，读起来还是连贯的。</li>
 *   <li><b>配色跟随主题</b>：主界面是深色而设置页雪白，晚上点开很刺眼。</li>
 * </ul>
 */
public class SettingsActivity extends Activity {

    /** 单列时内容的最大宽度（dp），横屏下避免每行拉得过长 */
    private static final int MAX_COL_DP = 560;
    /** 横屏且宽度达到这个 dp 才分两列 */
    private static final int TWO_COL_MIN_DP = 600;

    private interface Bool {
        boolean get();

        void set(boolean v);
    }

    private interface IntVal {
        int get();

        void set(int v);
    }

    /** 一个小节：标题 + 它下面的所有行。分列时以小节为最小单位，不拆开 */
    private static class Block {
        String title;
        final List<View> views = new ArrayList<View>();
        float weight;
    }

    private Prefs p;
    private Palette pal;
    private LinearLayout root;
    private ScrollView scroll;
    private LinearLayout shell;
    private LinearLayout colA;
    private LinearLayout colB;

    private final List<Block> blocks = new ArrayList<Block>();
    private Block cur;

    private boolean hasLightSensorCache;
    private boolean hasLightSensorKnown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        p = new Prefs(this);
        pal = Palette.of(Palette.shouldBeDark(p, false, null, null, nowMinutes()));

        // 先定方向再挂内容，避免横放时先闪一帧竖屏
        applyOrientation();
        try {
            ActionBar ab = getActionBar();
            if (ab != null) {
                ab.setTitle("挂钟设置");
                // 顺手把 ActionBar 上的「返回」也点亮：习惯用系统栏的人不必去找我们的按钮
                ab.setDisplayHomeAsUpEnabled(true);
            }
        } catch (Throwable ignored) {
        }

        // 根布局 = 吸顶返回条 + 可滚动的设置清单。
        // 上一版只有一个 ScrollView：从挂钟进来后既没有返回按钮，也看不出怎么退回去，
        // 只能摸系统返回键——挂墙上用久了很多人根本不知道还有这个键。
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(pal.bg);
        root.addView(buildBackBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll = new ScrollView(this);
        scroll.setBackgroundColor(pal.bg);
        shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.HORIZONTAL);
        shell.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(shell, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        build();
    }

    /** 吸顶的「← 返回」条：常驻在顶部，滚多远都能一键回挂钟 */
    private View buildBackBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(pal.panel);
        bar.setPadding(dp(8), dp(8), dp(12), dp(8));

        TextView back = new TextView(this);
        back.setText("‹  返回");
        back.setTextSize(17f);
        back.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        back.setTextColor(pal.accent);
        back.setPadding(dp(12), dp(8), dp(16), dp(8));
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onBackPressedPressed();
            }
        });
        bar.addView(back);

        TextView title = new TextView(this);
        title.setText("挂钟设置");
        title.setTextSize(14f);
        title.setTextColor(pal.textFaint);
        title.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView done = new TextView(this);
        done.setText("完成");
        done.setTextSize(15f);
        done.setTextColor(pal.accent);
        done.setPadding(dp(16), dp(8), dp(12), dp(8));
        done.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onBackPressedPressed();
            }
        });
        bar.addView(done);

        // 与内容之间一条细线，滚动时能看出「这一条是固定的」
        View line = new View(this);
        line.setBackgroundColor(pal.line);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        return wrap;
    }

    /** 统一的「离开设置页」入口：先落盘再关闭，避免最后一改动没保存 */
    private void onBackPressedPressed() {
        try {
            p.save();
        } catch (Throwable ignored) {
        }
        finish();
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item != null && item.getItemId() == android.R.id.home) {
            onBackPressedPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        onBackPressedPressed();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 清单里声明了 configChanges，旋转不会重建 Activity，这里手动重排
        build();
    }

    /** 和 MainActivity.applyScreenSettings 用同一套取值，两页方向保持一致 */
    private void applyOrientation() {
        int req;
        switch (p.orientation) {
            case Prefs.ORIENT_LANDSCAPE:
                req = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
                break;
            case Prefs.ORIENT_PORTRAIT:
                req = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
                break;
            default:
                req = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;
        }
        try {
            setRequestedOrientation(req);
        } catch (Throwable ignored) {
        }
    }

    private int nowMinutes() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private boolean hasLightSensor() {
        if (!hasLightSensorKnown) {
            hasLightSensorKnown = true;
            try {
                SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
                hasLightSensorCache = sm != null && sm.getDefaultSensor(Sensor.TYPE_LIGHT) != null;
            } catch (Throwable t) {
                hasLightSensorCache = false;
            }
        }
        return hasLightSensorCache;
    }

    // ------------------------------------------------------------------ 构建

    private void build() {
        if (shell == null) {
            return;
        }
        final int keepY = scroll.getScrollY();
        blocks.clear();
        cur = null;

        section("显示内容");
        addSwitch("显示天气", "关闭后只做时钟日历，界面更干净", new Bool() {
            public boolean get() {
                return p.showWeather;
            }

            public void set(boolean v) {
                p.showWeather = v;
            }
        });
        addSwitch("天气详情", "体感温度 / 湿度 / 风向风力", new Bool() {
            public boolean get() {
                return p.weatherDetail;
            }

            public void set(boolean v) {
                p.weatherDetail = v;
            }
        });
        addSwitch("未来三天预报", "底部一行显示明天、后天、大后天", new Bool() {
            public boolean get() {
                return p.showForecast;
            }

            public void set(boolean v) {
                p.showForecast = v;
            }
        });
        addSwitch("空气质量 AQI", "由 PM2.5 / PM10 按国标换算（近似值）", new Bool() {
            public boolean get() {
                return p.showAqi;
            }

            public void set(boolean v) {
                p.showAqi = v;
            }
        });
        addSwitch("日出日落", "", new Bool() {
            public boolean get() {
                return p.showSunTime;
            }

            public void set(boolean v) {
                p.showSunTime = v;
            }
        });
        addSwitch("显示城市名", "", new Bool() {
            public boolean get() {
                return p.showCityName;
            }

            public void set(boolean v) {
                p.showCityName = v;
            }
        });
        addSwitch("显示电量", "", new Bool() {
            public boolean get() {
                return p.showBattery;
            }

            public void set(boolean v) {
                p.showBattery = v;
            }
        });

        section("副页与翻页");
        addSwitch("今日黄历页", "左右滑动切到这一页：干支四柱、建除、值神、星宿、冲煞、宜忌、神煞、方位、时辰", new Bool() {
            public boolean get() {
                return p.pageAlmanac;
            }

            public void set(boolean v) {
                p.pageAlmanac = v;
            }
        });
        addSwitch("天气详情页", "24 小时温度曲线、七天预报、空气质量六项、生活指数", new Bool() {
            public boolean get() {
                return p.pageWeatherDetail;
            }

            public void set(boolean v) {
                p.pageWeatherDetail = v;
            }
        });
        addSwitch("月历页", "整月格子，点任意一天看那天的农历与宜忌", new Bool() {
            public boolean get() {
                return p.pageMonth;
            }

            public void set(boolean v) {
                p.pageMonth = v;
            }
        });
        addChoice("自动回到主页面", "停手一段时间后自动切回时钟页，适合挂在墙上无人看时", new String[]{
                "不自动", "15 秒", "30 秒", "1 分钟", "2 分钟", "5 分钟"}, new IntVal() {
            public int get() {
                int[] map = {0, 15, 30, 60, 120, 300};
                for (int i = 0; i < map.length; i++) {
                    if (map[i] == p.autoReturnSec) {
                        return i;
                    }
                }
                return 2;
            }

            public void set(int v) {
                int[] map = {0, 15, 30, 60, 120, 300};
                p.autoReturnSec = map[Math.max(0, Math.min(map.length - 1, v))];
            }
        });
        addSwitch("防误触", "挂墙上时关掉左右滑动翻页，避免被蹭到；长按要按 1.5 秒才进设置", new Bool() {
            public boolean get() {
                return p.touchLock;
            }

            public void set(boolean v) {
                p.touchLock = v;
            }
        });
        addChoice("黄历内容", "「完整」会多显示神煞、方位、彭祖百忌与十二时辰吉凶", new String[]{"完整", "精简"}, new IntVal() {
            public int get() {
                return p.almanacFull ? 0 : 1;
            }

            public void set(int v) {
                p.almanacFull = (v == 0);
            }
        });
        addChoice("月历一周起始", "按习惯选周日或周一起头", new String[]{"周日", "周一"}, new IntVal() {
            public int get() {
                return p.weekStart;
            }

            public void set(int v) {
                p.weekStart = v;
            }
        });

        section("阅读与字号");
        addSwitch("老人模式（大字号）",
                "整体字号放大约 35%。配合「可读下限」：每个字都不小于屏幕短边的 3.2%，"
                        + "装不下时自动少显示几项或拆到下一屏，而不是把字一路缩小",
                new Bool() {
                    public boolean get() {
                        return p.elderMode;
                    }

                    public void set(boolean v) {
                        p.elderMode = v;
                    }
                });
        addChoice("副页信息量",
                "「精简」每页只留核心项，一屏放下；「自动」按屏幕能放多大就放多少，"
                        + "装不下自动拆到第二屏；「完整」一项不少，需要几屏就几屏",
                new String[]{"精简", "自动", "完整"}, new IntVal() {
                    public int get() {
                        return p.pageDetail;
                    }

                    public void set(int v) {
                        p.pageDetail = v;
                    }
                });

        section("时钟与日期");
        addSwitch("24 小时制", "关闭则用 12 小时制并显示上午/下午", new Bool() {
            public boolean get() {
                return p.use24h;
            }

            public void set(boolean v) {
                p.use24h = v;
            }
        });
        addSwitch("显示秒", "秒数用强调色小字显示在右侧", new Bool() {
            public boolean get() {
                return p.showSeconds;
            }

            public void set(boolean v) {
                p.showSeconds = v;
            }
        });
        addSwitch("显示日期", "", new Bool() {
            public boolean get() {
                return p.showDate;
            }

            public void set(boolean v) {
                p.showDate = v;
            }
        });
        addSwitch("显示星期", "", new Bool() {
            public boolean get() {
                return p.showWeekday;
            }

            public void set(boolean v) {
                p.showWeekday = v;
            }
        });
        addSwitch("显示农历", "农历日期与干支生肖", new Bool() {
            public boolean get() {
                return p.showLunar;
            }

            public void set(boolean v) {
                p.showLunar = v;
            }
        });
        addSwitch("显示节气与节日", "", new Bool() {
            public boolean get() {
                return p.showTermFestival;
            }

            public void set(boolean v) {
                p.showTermFestival = v;
            }
        });
        addSwitch("节日倒计时", "例如「距中秋节 10 天」", new Bool() {
            public boolean get() {
                return p.showCountdown;
            }

            public void set(boolean v) {
                p.showCountdown = v;
            }
        });
        addSeek("时钟大小", "按需要放大或缩小", 60, 140, 5, "%", new IntVal() {
            public int get() {
                return p.clockScale;
            }

            public void set(int v) {
                p.clockScale = v;
            }
        });
        String[] styleNames = new String[ClockSpec.COUNT];
        for (int i = 0; i < ClockSpec.COUNT; i++) {
            styleNames[i] = ClockSpec.name(i);
        }
        addChoice("时钟样式", "辉光管 / 灯丝管 / VFD / 翻页钟 / 全息 / 点阵；发光样式在浅色主题下会自带同色系底",
                styleNames, new IntVal() {
                    public int get() {
                        return ClockSpec.norm(p.clockStyle);
                    }

                    public void set(int v) {
                        p.clockStyle = ClockSpec.norm(v);
                    }
                });
        addSeek("辉光强度", "0 为完全不发光；只对辉光管、灯丝管、VFD、全息、点阵生效",
                0, 100, 5, "%", new IntVal() {
                    public int get() {
                        return p.glowLevel;
                    }

                    public void set(int v) {
                        p.glowLevel = v;
                    }
                });

        section("外观");
        addChoice("主题", "深色更省电、夜间不刺眼；浅色白天更清楚", new String[]{"跟随昼夜自动切换", "固定深色", "固定浅色"},
                new IntVal() {
                    public int get() {
                        if (p.themeAuto) {
                            return 0;
                        }
                        return p.themeDark ? 1 : 2;
                    }

                    public void set(int v) {
                        if (v == 0) {
                            p.themeAuto = true;
                        } else {
                            p.themeAuto = false;
                            p.themeDark = (v == 1);
                        }
                    }
                });
        addHour("深色时段开始", "", new IntVal() {
            public int get() {
                return p.darkStart;
            }

            public void set(int v) {
                p.darkStart = v;
            }
        });
        addHour("深色时段结束", "跨零点也支持，例如 19 点到次日 7 点", new IntVal() {
            public int get() {
                return p.darkEnd;
            }

            public void set(int v) {
                p.darkEnd = v;
            }
        });
        addSwitch("用真实日出日落判断", "需要天气数据正常，拿不到时自动退回上面的固定时段",
                new Bool() {
                    public boolean get() {
                        return p.useSunTimes;
                    }

                    public void set(boolean v) {
                        p.useSunTimes = v;
                    }
                });
        addSwitch("环境变暗自动切深色",
                hasLightSensor()
                        ? "光线传感器检测到房间变暗时切深色背景，白天关灯也一样；「固定深色」下无需生效"
                        : "本机未检测到光线传感器，此项无效",
                new Bool() {
                    public boolean get() {
                        return p.lightAuto;
                    }

                    public void set(boolean v) {
                        p.lightAuto = v;
                    }
                });
        addSeek("光线触发阈值", "低于该值判为暗环境；回亮需达到约 3 倍，避免临界反复闪。0 = 永不触发",
                LightRule.LUX_MIN, LightRule.LUX_MAX, LightRule.LUX_STEP,
                " lux", new IntVal() {
                    public int get() {
                        return p.lightDarkLux;
                    }

                    public void set(int v) {
                        p.lightDarkLux = LightRule.snapLux(v);
                    }
                });
        addChoice("屏幕方向", "固定在横屏最像挂历；自动则跟随重力感应。设置页会跟着一起转",
                new String[]{"自动旋转", "锁定横屏", "锁定竖屏"},
                new IntVal() {
                    public int get() {
                        return p.orientation;
                    }

                    public void set(int v) {
                        p.orientation = v;
                        p.save();
                        // 立刻把本页也转过去，不用退出去再进来
                        applyOrientation();
                    }
                });

        section("屏幕与防烧屏");
        addSwitch("屏幕常亮", "长期挂机必须打开", new Bool() {
            public boolean get() {
                return p.keepScreenOn;
            }

            public void set(boolean v) {
                p.keepScreenOn = v;
            }
        });
        addChoice("常规亮度", "跟随系统即由手机自己的亮度设置决定", new String[]{"跟随系统", "100%", "80%", "60%", "45%", "30%"}, new IntVal() {
            public int get() {
                int[] map = {-1, 100, 80, 60, 45, 30};
                for (int i = 0; i < map.length; i++) {
                    if (map[i] == p.brightness) {
                        return i;
                    }
                }
                return 0;
            }

            public void set(int v) {
                int[] map = {-1, 100, 80, 60, 45, 30};
                p.brightness = map[Math.max(0, Math.min(map.length - 1, v))];
            }
        });
        addSwitch("夜间自动调暗", "到点自动降低亮度，白天恢复", new Bool() {
            public boolean get() {
                return p.nightDim;
            }

            public void set(boolean v) {
                p.nightDim = v;
            }
        });
        addHour("夜间开始", "", new IntVal() {
            public int get() {
                return p.nightStart;
            }

            public void set(int v) {
                p.nightStart = v;
            }
        });
        addHour("夜间结束", "", new IntVal() {
            public int get() {
                return p.nightEnd;
            }

            public void set(int v) {
                p.nightEnd = v;
            }
        });
        addSeek("夜间亮度", "0% 到 100%", 2, 60, 2, "%", new IntVal() {
            public int get() {
                return p.nightBrightness;
            }

            public void set(int v) {
                p.nightBrightness = v;
            }
        });
        addSwitch("夜间熄屏", "夜间彻底熄灭背光省电与护眼，轻触屏幕唤醒 30 秒", new Bool() {
            public boolean get() {
                return p.nightScreenOff;
            }

            public void set(boolean v) {
                p.nightScreenOff = v;
            }
        });
        addSwitch("防烧屏位移", "整块内容按周期做几个像素的位移，避免静态画面留下残影",
                new Bool() {
                    public boolean get() {
                        return p.burnInProtect;
                    }

                    public void set(boolean v) {
                        p.burnInProtect = v;
                    }
                });
        addSeek("位移周期", "每隔多少秒换一个位置", 20, 300, 20, " 秒", new IntVal() {
            public int get() {
                return p.burnInPeriod;
            }

            public void set(int v) {
                p.burnInPeriod = v;
            }
        });
        addSeek("位移幅度", "像素越大越不容易烧屏，但画面会有轻微漂移感", 0, 30, 2, " px", new IntVal() {
            public int get() {
                return p.burnInRange;
            }

            public void set(int v) {
                p.burnInRange = v;
            }
        });
        addSwitch("显示设置齿轮", "右下角小齿轮，点它进设置；关掉后长按屏幕任意位置也可进入",
                new Bool() {
                    public boolean get() {
                        return p.showGear;
                    }

                    public void set(boolean v) {
                        p.showGear = v;
                    }
                });

        section("城市与天气源");
        addChoice("定位方式", "自动定位会申请定位权限；手动指定则完全不定位，最省电",
                new String[]{"自动定位", "手动指定城市"}, new IntVal() {
                    public int get() {
                        return p.cityMode;
                    }

                    public void set(int v) {
                        p.cityMode = v;
                    }
                });
        addValue("当前城市", currentCityText(), "点这里选择城市", new Runnable() {
            public void run() {
                chooseCity();
            }
        });
        addSeek("刷新间隔", "天气多久拉取一次", 10, 180, 10, " 分钟", new IntVal() {
            public int get() {
                return p.refreshMin;
            }

            public void set(int v) {
                p.refreshMin = v;
            }
        });
        addChoice("温度单位", "华氏度按 1°C = 33.8°F 换算思路展示原始值", new String[]{"摄氏度 °C", "华氏度 °F"}, new IntVal() {
            public int get() {
                return p.tempUnit;
            }

            public void set(int v) {
                p.tempUnit = v;
            }
        });

        section("开机与自愈");
        addSwitch("开机自动启动",
                "断电恢复、手机重启后自动回到挂钟界面。关闭时会同时停用开机接收器，"
                        + "系统开机不再唤起本应用（少一个常驻监听也更省电）",
                new Bool() {
                    public boolean get() {
                        return p.autoStart;
                    }

                    public void set(boolean v) {
                        p.autoStart = v;
                        p.save();
                        BootReceiver.setEnabled(SettingsActivity.this, v);
                    }
                });
        addSwitch("看门狗自愈",
                "每 15 分钟自检一次：发现界面不在前台就重新拉起来。"
                        + "Android 9 及更老的机器上可靠；Android 10 以后系统限制后台启动界面，"
                        + "可能被拦下，建议配合下面的「设为默认桌面」",
                new Bool() {
                    public boolean get() {
                        return p.watchdog;
                    }

                    public void set(boolean v) {
                        p.watchdog = v;
                        p.save();
                        Watchdog.apply(SettingsActivity.this, v);
                    }
                });
        addAction("设为默认桌面（最可靠的自启）",
                "在打开的列表里选「挂钟日历」并选「始终」。之后按 Home 键、被别的应用盖住，"
                        + "都能回到挂钟；这也是绕开 Android 10+ 后台启动限制唯一靠谱的办法",
                new Runnable() {
                    public void run() {
                        try {
                            Intent home = new Intent(Intent.ACTION_MAIN);
                            home.addCategory(Intent.CATEGORY_HOME);
                            startActivity(Intent.createChooser(home, "选择默认桌面"));
                        } catch (Throwable t) {
                            toast("这台设备不支持更换桌面");
                        }
                    }
                });
        addAction("打开本应用的系统设置页",
                "国产 ROM 往往要在这里额外允许「自启动」「后台弹出界面」「关联启动」，"
                        + "否则开机自启会被系统拦掉",
                new Runnable() {
                    public void run() {
                        try {
                            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Throwable t) {
                            toast("这台设备不支持该设置页");
                        }
                    }
                });
        addAction("关闭锁屏密码（强烈建议）",
                "有密码的机器上，加密存储要等首次解锁后才发开机广播，挂墙的屏会白很久。"
                        + "打开的是系统安全设置页",
                new Runnable() {
                    public void run() {
                        try {
                            startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
                        } catch (Throwable t) {
                            toast("这台设备不支持该设置页");
                        }
                    }
                });
        addInfo("关于「断电后自动开机」：应用无权让手机关机后自己通电开机——那需要 root 改引导层"
                + "或内核，任何普通应用都做不到。可以这样绕：\n"
                + "① 在系统「设置 → 电源 / 电池」里找「接通电源自动开机」之类的选项，部分机型有这个开关；\n"
                + "② 用带开关的插座，再串一个充电宝 / UPS 垫一层：短时断电由电池扛过去，手机根本不会关机；\n"
                + "③ 打开系统的「电池保护 / 充电上限」，避免长期满电把电池撑坏。");

        section("系统");
        addAction("加入电池优化白名单（推荐）", "避免后台被杀导致天气不更新；在打开的列表里找到「挂钟日历」选择不优化", new Runnable() {
            public void run() {
                try {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                } catch (Throwable t) {
                    toast("这台设备不支持该设置页");
                }
            }
        });
        addAction("恢复默认设置", "所有选项回到初始值", new Runnable() {
            public void run() {
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("恢复默认设置？")
                        .setMessage("当前所有自定义项都会被重置。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("恢复", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                p.reset();
                                build();
                                toast("已恢复默认设置");
                            }
                        }).show();
            }
        });

        section("关于");
        addInfo("界面共 4 个页面，左右滑动切换：主时钟 → 今日黄历 → 天气详情 → 月历。"
                + "副页按「可读字号下限」排版：装不下就少显示几项或拆到第二屏，"
                + "不会把字缩到看不清（信息量见「阅读与字号」）。停手后会自动切回主页面。\n\n"
                + "版式全部由纯算术层算出，并被 tools/TestLayout.java 拿十几种真实分辨率"
                + "（含横竖屏、标准/老人两档字号）穷举回归，所以换机器、换方向都不会错位。\n\n"
                + "主题除了按昼夜时段切换，还可以选「环境变暗自动切深色」："
                + "由手机的光线传感器判断，房间关灯后即使还在白天也会切到深色背景。"
                + "阈值有 3 倍滞回（例如阈值 20 lux，要亮到 60 lux 才切回浅色），"
                + "避免临界附近反复闪。\n\n"
                + "省电：主页面只有开着秒数时才每秒重绘，副页内容不随时间变化就不重绘；"
                + "时钟与日期按秒对齐唤醒，避免无谓的抖动。\n\n"
                + "气象数据来自 Open-Meteo（免费、无需 API Key）；"
                + "定位失败或未授权时可在上面手动指定城市。取不到数据时主页面顶部会有红色"
                + "状态条，点它即可重试。\n\n"
                + "农历与节气覆盖 1900-2099 年，数据已用权威闰月表与逐日基准交叉校验；"
                + "超出范围时自动隐藏农历，只显示公历。\n\n"
                + "干支、建除十二神、二十八宿、黄道黑道值神、冲煞、纳音、胎神、方位、"
                + "十二时辰吉凶均可由历法唯一推算；而「宜忌」在各家通书之间本就有出入，"
                + "这里是按神煞所宜所忌合成，页面已标注「仅供参考」，请勿据以择日。\n\n"
                + "AQI 由 PM2.5/PM10 按 HJ 633-2012 换算，与官方综合 AQI 可能有小幅差异；"
                + "生活指数为本地推算。\n\n"
                + "网络兼容：" + TlsCompat.describe() + "\n\n"
                + "版本 1.8");

        // 滚到最后还有一个大按钮：从挂钟点进来的人，很多并不知道系统返回键在哪
        section("返回");
        addAction("返回挂钟界面", "改动已即时保存，直接回去即可", new Runnable() {
            public void run() {
                onBackPressedPressed();
            }
        });

        layoutBlocks();
        // 重排后把原来的滚动位置还回去，改一项设置不至于被弹回顶部
        scroll.post(new Runnable() {
            public void run() {
                scroll.scrollTo(0, keepY);
            }
        });
    }

    /** 把 blocks 分配到一列或两列 */
    private void layoutBlocks() {
        shell.removeAllViews();
        colA = new LinearLayout(this);
        colA.setOrientation(LinearLayout.VERTICAL);
        colB = null;

        int gap = dp(10);
        if (useTwoColumns()) {
            colB = new LinearLayout(this);
            colB.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            la.rightMargin = gap / 2;
            lb.leftMargin = gap / 2;
            shell.addView(colA, la);
            shell.addView(colB, lb);
        } else {
            int w = Math.min(getResources().getDisplayMetrics().widthPixels, dp(MAX_COL_DP));
            shell.addView(colA, new LinearLayout.LayoutParams(
                    w, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        float wa = 0f;
        float wb = 0f;
        for (Block b : blocks) {
            LinearLayout target;
            if (colB == null) {
                target = colA;
            } else if (wa <= wb) {
                target = colA;
                wa += b.weight + 0.5f;   // +0.5 约等于小节标题本身的高度
            } else {
                target = colB;
                wb += b.weight + 0.5f;
            }
            TextView h = headerView(b.title);
            target.addView(h);
            for (View v : b.views) {
                target.addView(v);
            }
        }
    }

    private boolean useTwoColumns() {
        Configuration cfg = getResources().getConfiguration();
        return cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
                && cfg.screenWidthDp >= TWO_COL_MIN_DP;
    }

    private String currentCityText() {
        if (p.cityMode == Prefs.CITY_AUTO) {
            if (p.hasLastLocation && p.lastCity.length() > 0) {
                return "自动定位 · 上次为 " + p.lastCity;
            }
            return "自动定位";
        }
        String name = p.cityName.length() > 0 ? p.cityName : "未命名地点";
        return name + " (" + String.format(java.util.Locale.US, "%.4f, %.4f", p.cityLat, p.cityLon) + ")";
    }

    // ------------------------------------------------------------------ 行控件

    private void section(String title) {
        cur = new Block();
        cur.title = title;
        blocks.add(cur);
    }

    /** 把一行放进当前小节，并按估算高度累加权重（seek 行比 switch 行高） */
    private void addRow(View v, float weight) {
        if (cur == null) {
            section("");
        }
        cur.views.add(v);
        cur.weight += weight;
    }

    private TextView headerView(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(13f);
        tv.setTextColor(pal.accent);
        tv.setPadding(dp(4), dp(18), dp(4), dp(6));
        return tv;
    }

    private LinearLayout row(String title, String sub) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(pal.panel);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(1);
        box.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15f);
        t.setTextColor(pal.textPrimary);
        box.addView(t);
        if (sub != null && sub.length() > 0) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(12f);
            s.setTextColor(pal.textFaint);
            s.setPadding(0, dp(2), 0, 0);
            box.addView(s);
        }
        return box;
    }

    private void addSwitch(String title, String sub, final Bool b) {
        LinearLayout box = row(title, sub);
        final android.widget.Switch sw = new android.widget.Switch(this);
        sw.setChecked(b.get());
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        // 把标题区与开关并排
        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        while (box.getChildCount() > 0) {
            View v = box.getChildAt(0);
            box.removeViewAt(0);
            textCol.addView(v);
        }
        wrap.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        wrap.addView(sw);
        box.addView(wrap);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton v, boolean isChecked) {
                b.set(isChecked);
                p.save();
            }
        });
        addRow(box, 1f);
    }

    private void addChoice(String title, String sub, final String[] labels, final IntVal v) {
        final TextView value = new TextView(this);
        value.setTextColor(pal.accent);
        value.setTextSize(14f);
        LinearLayout box = rowWithValue(title, sub, value);
        value.setText(labels[Math.max(0, Math.min(labels.length - 1, v.get()))]);
        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle(title)
                        .setSingleChoiceItems(labels, v.get(), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                v.set(which);
                                p.save();
                                value.setText(labels[which]);
                                d.dismiss();
                            }
                        }).show();
            }
        });
        addRow(box, 1f);
    }

    private void addHour(String title, String sub, final IntVal v) {
        final TextView value = new TextView(this);
        value.setTextColor(pal.accent);
        value.setTextSize(14f);
        LinearLayout box = rowWithValue(title, sub, value);
        value.setText(hourText(v.get()));
        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                final String[] items = new String[24];
                for (int i = 0; i < 24; i++) {
                    items[i] = hourText(i);
                }
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle(title)
                        .setSingleChoiceItems(items, v.get(), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                v.set(which);
                                p.save();
                                value.setText(items[which]);
                                d.dismiss();
                            }
                        }).show();
            }
        });
        addRow(box, 1f);
    }

    private String hourText(int h) {
        return String.format(java.util.Locale.US, "%02d:00", h);
    }

    private void addSeek(String title, String sub, final int min, final int max, final int step,
                         final String suffix, final IntVal v) {
        final TextView value = new TextView(this);
        value.setTextColor(pal.accent);
        value.setTextSize(14f);
        LinearLayout box = rowWithTitleValue(title, sub, value);
        value.setText(v.get() + suffix);

        SeekBar sb = new SeekBar(this);
        sb.setMax((max - min) / step);
        sb.setProgress(Math.max(0, (v.get() - min) / step));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        sb.setLayoutParams(lp);
        final boolean[] fromUser = {false};
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser2) {
                int val = min + progress * step;
                value.setText(val + suffix);
                if (fromUser[0]) {
                    v.set(val);
                }
            }

            public void onStartTrackingTouch(SeekBar s) {
                fromUser[0] = true;
            }

            public void onStopTrackingTouch(SeekBar s) {
                fromUser[0] = false;
                p.save();
            }
        });
        box.addView(sb);
        addRow(box, 1.7f);
    }

    private void addValue(String title, String currentValue, String hint, final Runnable onClick) {
        final TextView value = new TextView(this);
        value.setTextColor(pal.accent);
        value.setTextSize(14f);
        LinearLayout box = rowWithValue(title, hint, value);
        value.setText(currentValue);
        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onClick.run();
            }
        });
        addRow(box, 1f);
    }

    private void addAction(String title, String sub, final Runnable r) {
        LinearLayout box = row(title, sub);
        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                r.run();
            }
        });
        addRow(box, 1f);
    }

    private void addInfo(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12.5f);
        tv.setTextColor(pal.textSecondary);
        tv.setLineSpacing(dp(2), 1f);
        tv.setBackgroundColor(pal.panel);
        tv.setPadding(dp(12), dp(12), dp(12), dp(12));
        // 关于这段很长，按字数估权重，分列时不会把一列压得太长
        addRow(tv, 2f + text.length() / 90f);
    }

    private LinearLayout rowWithValue(String title, String sub, TextView value) {
        LinearLayout box = row(title, sub);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        while (box.getChildCount() > 0) {
            View v = box.getChildAt(0);
            box.removeViewAt(0);
            textCol.addView(v);
        }
        wrap.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        value.setPadding(dp(10), 0, 0, 0);
        wrap.addView(value);
        box.addView(wrap);
        return box;
    }

    private LinearLayout rowWithTitleValue(String title, String sub, TextView value) {
        // 滑杆用的行：标题与数值同一行，滑杆另起一行
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(pal.panel);
        box.setPadding(dp(12), dp(10), dp(12), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(1);
        box.setLayoutParams(lp);

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15f);
        t.setTextColor(pal.textPrimary);
        textCol.addView(t);
        if (sub != null && sub.length() > 0) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(12f);
            s.setTextColor(pal.textFaint);
            textCol.addView(s);
        }
        topRow.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        topRow.addView(value);
        box.addView(topRow);
        return box;
    }

    // ------------------------------------------------------------------ 选城市

    private void chooseCity() {
        final String[] actions = {"自动定位", "从内置城市列表选择", "联网搜索城市", "手动填写经纬度"};
        new AlertDialog.Builder(this)
                .setTitle("选择城市")
                .setItems(actions, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            p.cityMode = Prefs.CITY_AUTO;
                            p.save();
                            build();
                            toast("已改为自动定位，返回主界面即生效");
                        } else if (which == 1) {
                            pickFromBuiltin();
                        } else if (which == 2) {
                            searchOnline();
                        } else {
                            manualLatLon();
                        }
                    }
                }).show();
    }

    private void pickFromBuiltin() {
        final List<CityRepo.City> cities = CityRepo.all();
        final String[] labels = new String[cities.size()];
        for (int i = 0; i < cities.size(); i++) {
            labels[i] = cities.get(i).label();
        }
        new AlertDialog.Builder(this)
                .setTitle("内置城市（共 " + cities.size() + " 个）")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        applyCity(cities.get(which).name, cities.get(which).lat, cities.get(which).lon);
                    }
                }).show();
    }

    private void searchOnline() {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        final EditText input = new EditText(this);
        input.setHint("输入城市名，例如 杭州 / 苏州");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("联网搜索城市")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("搜索", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String q = input.getText().toString().trim();
                        if (q.length() == 0) {
                            toast("请输入城市名");
                            return;
                        }
                        doSearch(q);
                    }
                }).show();
    }

    private void doSearch(final String keyword) {
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage("正在搜索…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                List<String[]> found = null;
                String err = null;
                try {
                    found = WeatherData.searchCity(keyword);
                } catch (Throwable t) {
                    err = t.getMessage();
                }
                final List<String[]> res = found;
                final String error = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        try {
                            pd.dismiss();
                        } catch (Throwable ignored) {
                        }
                        if (res == null || res.size() == 0) {
                            toast(error != null ? ("搜索失败：" + error) : "没有找到匹配的城市");
                            return;
                        }
                        final String[] labels = new String[res.size()];
                        for (int i = 0; i < res.size(); i++) {
                            labels[i] = res.get(i)[0];
                        }
                        new AlertDialog.Builder(SettingsActivity.this)
                                .setTitle("搜索结果")
                                .setItems(labels, new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int which) {
                                        String[] r = res.get(which);
                                        applyCity(r[0].split(" · ")[0],
                                                Double.parseDouble(r[2]), Double.parseDouble(r[3]));
                                    }
                                }).show();
                    }
                });
            }
        }, "city-search").start();
    }

    private void manualLatLon() {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        final EditText name = new EditText(this);
        name.setHint("名称（可留空）");
        final EditText lat = new EditText(this);
        lat.setHint("纬度，例如 30.2741");
        lat.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        final EditText lon = new EditText(this);
        lon.setHint("经度，例如 120.1551");
        lon.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        box.addView(name);
        box.addView(lat);
        box.addView(lon);
        if (p.cityMode == Prefs.CITY_MANUAL) {
            lat.setText(String.valueOf(p.cityLat));
            lon.setText(String.valueOf(p.cityLon));
            name.setText(p.cityName);
        }
        new AlertDialog.Builder(this)
                .setTitle("手动填写经纬度")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        try {
                            double la = Double.parseDouble(lat.getText().toString().trim());
                            double lo = Double.parseDouble(lon.getText().toString().trim());
                            if (la < -90 || la > 90 || lo < -180 || lo > 180) {
                                toast("经纬度超出范围");
                                return;
                            }
                            String n = name.getText().toString().trim();
                            applyCity(n.length() > 0 ? n : "自定义位置", la, lo);
                        } catch (Throwable t) {
                            toast("请填写正确的数字");
                        }
                    }
                }).show();
    }

    private void applyCity(String name, double lat, double lon) {
        p.cityMode = Prefs.CITY_MANUAL;
        p.cityName = name;
        p.cityLat = (float) lat;
        p.cityLon = (float) lon;
        p.save();
        build();
        toast("已设置为 " + name);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 看门狗据此判断「界面是否已经在用户眼前」，避免把正在改设置的人拽回挂钟
        AppState.onActivityStart();
    }

    @Override
    protected void onStop() {
        super.onStop();
        AppState.onActivityStop();
    }

    @Override
    protected void onPause() {
        super.onPause();
        p.save();
    }
}
