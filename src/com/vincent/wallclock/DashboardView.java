package com.vincent.wallclock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Scroller;

import com.vincent.wallclock.gen.ClockSpec;
import com.vincent.wallclock.gen.Layout;
import com.vincent.wallclock.gen.LightRule;
import com.vincent.wallclock.gen.Type;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 挂钟主视图。四个页面横向排列，左右拖动切换，停手 30 秒（可配）自动回到主页面。
 *
 * <pre>
 *   [ 主页面 ] [ 今日黄历 ] [ 天气详情 ] [ 月历 ]
 * </pre>
 *
 * <p>全部内容自绘，因此：
 * <ul>
 *   <li>任何分辨率与横竖屏都按比例自适应</li>
 *   <li>所有文字都「先量宽高再缩放字号」，结构上不会溢出或互相压盖</li>
 *   <li>数字等宽推进，秒数跳动时整块时钟不会左右抖动</li>
 *   <li>内容整体按周期做像素级位移，缓解长时间静止造成的烧屏</li>
 * </ul>
 *
 * <p>几点刻意的取舍：
 * <ul>
 *   <li>不引 ViewPager，翻页是自己用 Scroller 实现的——省掉 AndroidX 依赖，
 *       老机器上启动更快，也避免 4.4 上的兼容坑。</li>
 *   <li>拖动时只画与视口相交的那一两页，不是四页都画。</li>
 *   <li>防烧屏位移只在「停在主页面」时生效；看副页时暂停位移，
 *       免得正在读的内容一直在动。</li>
 * </ul>
 */
public class DashboardView extends View {

    public interface OnSettingsTap {
        void openSettings();
    }

    private static final String[] WEEK_CN = {"日", "一", "二", "三", "四", "五", "六"};

    /** 页面 id */
    private static final int PG_MAIN = 0;
    private static final int PG_ALMANAC = 1;
    private static final int PG_WEATHER = 2;
    private static final int PG_MONTH = 3;

    private static final int MAX_PAGES = 16;

    /** 天气失败状态条用的红色 */
    private static final int C_ERR = 0xFFE24B4A;

    /**
     * 一屏 = 一个板块。
     *
     * <p>副页<b>不再拆屏</b>：原来内容多了会拆成「第 1 屏 / 第 2 屏」，
     * 可横滑本来是「切板块」的手势，滑到一半根本分不清自己在黄历第 2 屏还是天气页。
     * 现在横滑只切板块，板块内部上下滑。
     */
    private static final class ScreenRef {
        final int id;

        ScreenRef(int id) {
            this.id = id;
        }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Prefs prefs;
    private WeatherData weather;
    private String cityLabel = "";
    private String statusNote = "";
    /** 天气/定位失败的提示文字，非空时主页面顶部会出现可点重试的状态条 */
    private String errorText = "";
    /** 状态条在内容坐标系里的位置，用于点击命中 */
    private float[] errorRect;
    private int battery = -1;
    private boolean charging;
    private OnSettingsTap tapListener;
    private OnRetry retryListener;

    /** 光线传感器最新读数（原始值），由 MainActivity 灌入 */
    private float ambientRaw = LightRule.UNKNOWN;
    /** 平滑后的照度，每帧向 ambientRaw 靠拢 */
    private float ambientLux = LightRule.UNKNOWN;
    /** 上一帧的主题判定，供光线阈值做滞回 */
    private boolean darkNow = false;

    private LunarCalendar lunarCache;
    private int lunarCacheJdn = -1;
    private String countdownCache = "";
    private int countdownCacheJdn = -1;

    // ---- 分页状态 ----
    private ScreenRef[] screens = new ScreenRef[0];
    private int pageCount = 0;
    private int builtW = -1;
    private int builtH = -1;
    private int builtSig = 0;
    private float scroll = 0f;                 // 内容横向位移（px）
    private Scroller scroller;
    private VelocityTracker velocity;
    private boolean dragging;
    private boolean flingInterrupted;
    private float downX, downY, downScroll;
    private long downTime;
    private long lastInteraction;
    private int touchSlop;

    // ---- 副页纵向滚动 ----
    /** 每个板块各自的垂直滚动位置，切板块时互不影响 */
    private float[] pageScroll = new float[0];
    /** 纵向惯性滚动（与横向的 scroller 分开，一次手势只会有其中一个在跑） */
    private Scroller vScroller;
    /** 本次手势锁定的方向：0=还没定 1=横向（切板块） 2=纵向（板块内滚动） 3=月历详情面板内滚动 */
    private int dragAxis = 0;
    private float downScrollY;
    /** 按下的那一点是不是落在月历页的详情面板里（面板自己有滚动，不走 pageScroll） */
    private boolean detailDrag;
    private float downDetailScroll;
    /** 按下那一刻当前板块的可滚动距离（内容在手势中途不会变，存一份就够） */
    private float downRange;
    /** 每个板块的可滚动距离缓存，由 onDraw 刷新，供触摸处理直接取用 */
    private float[] pageRange = new float[0];
    /** 每个板块的内容自然总高，与 pageRange 一起用来画滚动条 */
    private float[] pageContentH = new float[0];
    /** 每个板块的分页锚点（卡片顶部偏移），翻页时页顶对齐到这些位置 */
    private float[][] pageAnchors = new float[0][];

    // ---- 副页绘制器 ----
    private final AlmanacPage almanacPage = new AlmanacPage();
    private final WeatherDetailPage weatherDetailPage = new WeatherDetailPage();
    private final MonthPage monthPage = new MonthPage();

    public DashboardView(Context ctx) {
        this(ctx, null);
    }

    public DashboardView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        paint.setTypeface(Typeface.create(SANS, Typeface.NORMAL));
        paint.setTextAlign(Paint.Align.LEFT);
        setClickable(true);
        setLongClickable(true);
        scroller = new Scroller(ctx);
        vScroller = new Scroller(ctx);
        touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        lastInteraction = System.currentTimeMillis();
    }

    private static final Typeface SANS = Typeface.SANS_SERIF;

    public void setPrefs(Prefs p) {
        this.prefs = p;
        buildPages();
        invalidate();
    }

    public void setWeather(WeatherData w) {
        this.weather = w;
        invalidate();
    }

    public void setCityLabel(String s) {
        this.cityLabel = s == null ? "" : s;
        invalidate();
    }

    public void setStatusNote(String s) {
        this.statusNote = s == null ? "" : s;
        invalidate();
    }

    /**
     * 天气/定位失败的显眼提示。设了之后主页面顶部会出现一条状态条，点它重试。
     * 原来失败只是天气卡里一行小灰字，挂墙上根本注意不到。
     */
    public void setError(String msg) {
        this.errorText = msg == null ? "" : msg;
        invalidate();
    }

    /** 当前这一屏是否每秒都在变——决定要不要每秒重绘（挂墙省电） */
    public boolean needsPerSecondDraw() {
        if (prefs == null) {
            return true;
        }
        return scroll <= 0.5f && prefs.showSeconds;
    }

    public interface OnRetry {
        void retryWeather();
    }

    public void setOnRetry(OnRetry l) {
        this.retryListener = l;
    }

    public void setBattery(int pct, boolean charging) {
        this.battery = pct;
        this.charging = charging;
    }

    public void setOnSettingsTap(OnSettingsTap l) {
        this.tapListener = l;
    }

    /**
     * 灌入光线传感器的原始读数（lux）。这里只存原始值，平滑放到 onDraw 里做：
     * 很多光感是「变化才上报」，事件之间可能几秒没有回调，在事件里做 EMA 会永远收敛不到真值。
     */
    public void setAmbientLux(float lux) {
        ambientRaw = lux;
    }

    /** 没有光线传感器（或已停止监听）时调用：连平滑值一起清掉，免得旧读数继续影响主题 */
    public void clearAmbientLux() {
        ambientRaw = LightRule.UNKNOWN;
        ambientLux = LightRule.UNKNOWN;
    }

    // ------------------------------------------------------------------ 分页

    /** 内容区：与 drawPage 完全同一套边距，避免两处算法漂移 */
    private float[] pageBox(int w, int h, float x, float y, boolean withStrip) {
        boolean landscape = w > h;
        float padX = landscape ? w * 0.030f : w * 0.055f;
        float padY = landscape ? h * 0.040f : h * 0.030f;
        float stripH = withStrip ? Math.min(w, h) * 0.032f : 0f;
        return new float[]{x + padX, y + padY, w - padX * 2f, h - padY * 2f - stripH};
    }

    /** 会影响到屏数与版式的设置项，变了就重建分屏 */
    private int prefsSig() {
        int s = 0;
        int[] bits = {
                prefs.pageAlmanac ? 1 : 0,
                prefs.pageWeatherDetail ? 2 : 0,
                prefs.pageMonth ? 4 : 0,
                prefs.elderMode ? 8 : 0,
                prefs.almanacFull ? 16 : 0,
                prefs.showWeather ? 32 : 0,
                prefs.showForecast ? 64 : 0,
                prefs.showLunar ? 128 : 0,
                prefs.showTermFestival ? 256 : 0,
                prefs.showCountdown ? 512 : 0,
                prefs.showDate ? 1024 : 0,
                prefs.showWeekday ? 2048 : 0,
        };
        for (int b : bits) {
            s += b;
        }
        s = s * 31 + prefs.pageDetail;
        s = s * 31 + prefs.weekStart;
        s = s * 31 + prefs.clockScale;
        s = s * 31 + (prefs.use24h ? 1 : 0);
        s = s * 31 + (prefs.showSeconds ? 1 : 0);
        s = s * 31 + (hasForecast() ? 1 : 0);
        return s;
    }

    private void buildPages() {
        if (prefs == null) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        if (w <= 8 || h <= 8) {
            return;
        }
        int sig = prefsSig();
        if (w == builtW && h == builtH && sig == builtSig && screens.length > 0) {
            return;
        }
        int keep = (builtW == w && pageCount > 0) ? currentIndex() : 0;
        builtW = w;
        builtH = h;
        builtSig = sig;

        boolean anySub = prefs.pageAlmanac || prefs.pageWeatherDetail || prefs.pageMonth;
        float[] box = pageBox(w, h, 0f, 0f, anySub);
        float bw = box[2];
        float bh = box[3];

        // 每个板块只占一屏：内容多改为板块内上下滑，不再横滑翻「第 2 屏」
        ArrayList<ScreenRef> list = new ArrayList<ScreenRef>();
        list.add(new ScreenRef(PG_MAIN));
        if (bw > 8f && bh > 8f) {
            if (prefs.pageAlmanac) {
                list.add(new ScreenRef(PG_ALMANAC));
            }
            if (prefs.pageWeatherDetail) {
                list.add(new ScreenRef(PG_WEATHER));
            }
            if (prefs.pageMonth) {
                list.add(new ScreenRef(PG_MONTH));
            }
        }
        screens = list.toArray(new ScreenRef[0]);
        pageCount = screens.length;
        keep = Math.max(0, Math.min(pageCount - 1, keep));
        scroll = keep * (float) w;

        // 各板块的纵向滚动位置：板块数变了就重建，否则尽量沿用
        float[] old = pageScroll;
        pageScroll = new float[pageCount];
        for (int i = 0; i < pageCount && i < old.length; i++) {
            pageScroll[i] = old[i];
        }
        pageRange = new float[pageCount];
        pageContentH = new float[pageCount];
        pageAnchors = new float[pageCount][];
        if (scroller != null) {
            scroller.forceFinished(true);
        }
        if (vScroller != null) {
            vScroller.forceFinished(true);
        }
        monthPage.syncToToday();
    }

    private int currentIndex() {
        int w = getWidth();
        if (w <= 0 || pageCount <= 0) {
            return 0;
        }
        int i = Math.round(scroll / w);
        return Math.max(0, Math.min(pageCount - 1, i));
    }

    /**
     * 第 idx 屏的可滚动距离：内容自然总高 − 内容区可视高度。
     * 主页面与月历页内容本来就放得下，返回 0（也就是滚不动）。
     */
    private float scrollRangeOf(int idx, int year, int month, int day) {
        if (prefs == null || idx < 0 || idx >= screens.length) {
            return 0f;
        }
        int w = getWidth();
        int h = getHeight();
        if (w <= 8 || h <= 8) {
            return 0f;
        }
        boolean anySub = prefs.pageAlmanac || prefs.pageWeatherDetail || prefs.pageMonth;
        float[] box = pageBox(w, h, 0f, 0f, anySub);
        float bw = box[2];
        float bh = box[3];
        if (bw <= 8f || bh <= 8f) {
            return 0f;
        }
        boolean landscape = w > h;
        float range = 0f;
        float contentH = 0f;
        float[] anchors = null;
        switch (screens[idx].id) {
            case PG_ALMANAC:
                range = almanacPage.scrollRange(paint, bw, bh, prefs, year, month, day, landscape);
                contentH = almanacPage.lastContentH();
                anchors = almanacPage.anchors(paint, bw, bh, prefs, year, month, day, landscape);
                break;
            case PG_WEATHER:
                range = weatherDetailPage.scrollRange(paint, bw, bh, prefs, weather, landscape);
                contentH = weatherDetailPage.lastContentH();
                anchors = weatherDetailPage.anchors(paint, bw, bh, prefs, weather, landscape);
                break;
            case PG_MONTH:
                // 月历页本身不整页滚（日历格子固定），要滚的是右边的选中日详情面板。
                // 这里只让它重新量一次，滚动范围存在页面自己手里，触摸时直接取。
                monthPage.detailScrollRange(paint, bw, bh, prefs, landscape);
                break;
            default:
                break;
        }
        if (idx < pageContentH.length) {
            pageContentH[idx] = contentH;
        }
        if (idx < pageAnchors.length) {
            pageAnchors[idx] = anchors;
        }
        return range;
    }

    /** 第 idx 屏的整页翻页停靠点（升序，第一项 0，最后一项是可滚动距离） */
    private float[] stopsOf(int idx) {
        float range = (idx >= 0 && idx < pageRange.length) ? pageRange[idx] : 0f;
        if (!(range > 0f)) {
            return new float[]{0f};
        }
        float contentH = (idx < pageContentH.length) ? pageContentH[idx] : 0f;
        float viewH = contentH - range;
        float[] anchors = (idx < pageAnchors.length) ? pageAnchors[idx] : null;
        return Layout.pageStops(anchors, viewH, range);
    }

    /**
     * 纵向甩动后的翻页：不再做自由惯性，而是吸附到最近的一页。
     *
     * <p>为什么不用 fling：副页是卡片列表，自由惯性停下来时页顶常常停在
     * 某张卡的中间，上半截在页尾、下半截在页头，读起来比连续滚动还难受。
     * 快速甩一下会多翻一页，慢速松手就停在离当前位置最近的那页。
     */
    private void settleVertical(MotionEvent e) {
        if (vScroller == null) {
            return;
        }
        float range = downRange;
        if (!(range > 0f)) {
            return;
        }
        float vel = 0f;
        if (velocity != null) {
            velocity.computeCurrentVelocity(1000);
            vel = velocity.getYVelocity();
        }
        float cur = Layout.clampScroll(downScrollY - (e.getY() - downY), range);
        float[] stops = stopsOf(currentIndex());
        int nearest = 0;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < stops.length; i++) {
            float d = Math.abs(stops[i] - cur);
            if (d < best) {
                best = d;
                nearest = i;
            }
        }
        // 上滑（vel<0）是往下读，多翻一页；下滑反过来。慢速松手就不额外加页。
        if (vel < -1200f) {
            nearest = Math.min(stops.length - 1, nearest + 1);
        } else if (vel > 1200f) {
            nearest = Math.max(0, nearest - 1);
        }
        animateVertical(stops[nearest]);
    }

    /** 垂直滚动到指定位置（带动画，越远越慢，最长 320ms） */
    private void animateVertical(float target) {
        if (vScroller == null) {
            return;
        }
        int idx = currentIndex();
        float from = (idx >= 0 && idx < pageScroll.length) ? pageScroll[idx] : 0f;
        float range = (idx >= 0 && idx < pageRange.length) ? pageRange[idx] : 0f;
        target = Layout.clampScroll(target, range);
        if (Math.abs(target - from) < 1f) {
            if (idx >= 0 && idx < pageScroll.length) {
                pageScroll[idx] = target;
            }
            invalidate();
            return;
        }
        int ms = Math.min(320, Math.max(120, (int) (Math.abs(target - from) * 0.45f)));
        vScroller.startScroll(0, Math.round(from), 0, Math.round(target - from), ms);
        invalidate();
    }

    /** 第 idx 屏当前的纵向偏移，已经夹进合法范围 */
    private float scrollYOf(int idx) {
        if (idx < 0 || idx >= pageScroll.length) {
            return 0f;
        }
        float range = (idx < pageRange.length) ? pageRange[idx] : 0f;
        if (!(range > 0f)) {
            return 0f;
        }
        return Layout.clampScroll(pageScroll[idx], range);
    }

    /** 直接设定当前板块的纵向偏移（拖动时用） */
    private void setScrollY(int idx, float v) {
        if (idx < 0 || idx >= pageScroll.length) {
            return;
        }
        pageScroll[idx] = Layout.clampScroll(v, (idx < pageRange.length) ? pageRange[idx] : 0f);
    }

    private void setScroll(float v) {
        int w = getWidth();
        if (w <= 0 || pageCount <= 0) {
            scroll = 0f;
            return;
        }
        float max = (pageCount - 1) * (float) w;
        if (v < 0f) {
            v = v / 3f;                       // 到头了给一点阻尼
        } else if (v > max) {
            v = max + (v - max) / 3f;
        }
        scroll = v;
    }

    private void animateTo(float target) {
        int w = getWidth();
        if (w <= 0) {
            return;
        }
        float max = Math.max(0, pageCount - 1) * (float) w;
        target = Math.max(0f, Math.min(max, target));
        if (Math.abs(target - scroll) < 1f) {
            scroll = target;
            invalidate();
            return;
        }
        scroller.startScroll(Math.round(scroll), 0, Math.round(target - scroll), 0, 280);
        invalidate();
    }

    private void settle(MotionEvent e) {
        int w = getWidth();
        if (w <= 0 || pageCount <= 1) {
            animateTo(0f);
            return;
        }
        float vel = 0f;
        if (velocity != null) {
            velocity.computeCurrentVelocity(1000);
            vel = velocity.getXVelocity();
        }
        float frac = scroll / w;
        int base = (int) Math.floor(frac);
        float r = frac - base;
        int idx;
        if (vel < -400f) {
            idx = base + 1;                   // 向左甩 → 下一页
        } else if (vel > 400f) {
            idx = base;                       // 向右甩 → 上一页
        } else if (r >= 0.34f) {
            idx = base + 1;
        } else {
            idx = base;
        }
        idx = Math.max(0, Math.min(pageCount - 1, idx));
        animateTo(idx * (float) w);
    }

    /** 停手一段时间后自动回到主页面 */
    private void maybeAutoReturn() {
        if (prefs == null || prefs.autoReturnSec <= 0 || pageCount <= 1) {
            return;
        }
        if (dragging || !scroller.isFinished() || scroll <= 1f) {
            return;
        }
        if (System.currentTimeMillis() - lastInteraction < prefs.autoReturnSec * 1000L) {
            return;
        }
        animateTo(0f);
    }

    // ------------------------------------------------------------------ 交互

    private float gearRadius(int w, int h) {
        return Math.min(w, h) * 0.026f;
    }

    private boolean hitGear(float x, float y, int w, int h) {
        float dx = x - (w - Math.min(w, h) * 0.055f);
        float dy = y - (h - Math.min(w, h) * 0.055f);
        float r = gearRadius(w, h) * 3.2f;
        return dx * dx + dy * dy <= r * r;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (prefs == null) {
            return super.onTouchEvent(e);
        }
        buildPages();
        int w = getWidth();
        int h = getHeight();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                lastInteraction = System.currentTimeMillis();
                downTime = lastInteraction;
                downX = e.getX();
                downY = e.getY();
                downScroll = scroll;
                dragging = false;
                dragAxis = 0;
                int di = currentIndex();
                downScrollY = (di >= 0 && di < pageScroll.length) ? pageScroll[di] : 0f;
                downRange = (di >= 0 && di < pageRange.length) ? pageRange[di] : 0f;
                // 月历页的详情面板是「面板里自己滚」：按在面板上时竖滑归它，
                // 按在日历格子上时竖滑没有意义（格子是固定的），别把整页拖走
                detailDrag = isMonthScreen(di) && monthPage.inDetail(e.getX() + scroll, e.getY());
                downDetailScroll = monthPage.detailScroll();
                boolean vRunning = vScroller != null && !vScroller.isFinished();
                flingInterrupted = !scroller.isFinished() || vRunning;
                if (!scroller.isFinished()) {
                    scroller.forceFinished(true);
                }
                if (vScroller != null) {
                    vScroller.forceFinished(true);
                }
                if (velocity == null) {
                    velocity = VelocityTracker.obtain();
                } else {
                    velocity.clear();
                }
                velocity.addMovement(e);
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                lastInteraction = System.currentTimeMillis();
                if (velocity != null) {
                    velocity.addMovement(e);
                }
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                if (dragAxis == 0) {
                    if (prefs.touchLock) {
                        break;                       // 防误触：这一笔完全不响应
                    }
                    float ax = Math.abs(dx);
                    float ay = Math.abs(dy);
                    if (ax < touchSlop && ay < touchSlop) {
                        break;                       // 还没确定方向，先不动
                    }
                    // 方向锁定：这一笔定成横滑或竖滑之后就不再改，
                    // 免得斜着划的时候两个方向来回跳
                    if (detailDrag && ay >= ax) {
                        dragAxis = 3;                // 详情面板内滚动，优先于整块翻页
                    } else if (ax >= ay) {
                        dragAxis = (pageCount > 1) ? 1 : 2;
                    } else {
                        dragAxis = (downRange > 0f) ? 2 : 1;
                    }
                    dragging = true;
                }
                if (dragAxis == 1) {
                    setScroll(downScroll - dx);
                } else if (dragAxis == 3) {
                    monthPage.setDetailScroll(downDetailScroll - dy);
                } else {
                    setScrollY(currentIndex(), downScrollY - dy);
                }
                invalidate();
                break;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                lastInteraction = System.currentTimeMillis();
                boolean wasDragging = dragging;
                int axis = dragAxis;
                dragging = false;
                dragAxis = 0;
                if (wasDragging && axis == 1) {
                    flingInterrupted = false;
                    settle(e);
                } else if (wasDragging && axis == 2) {
                    flingInterrupted = false;
                    settleVertical(e);
                } else if (wasDragging) {
                    // 详情面板内滚动：松手就停，不吸附也不甩（内容长短不一，吸附反而跳）
                    flingInterrupted = true;
                } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    handleUp(e.getX(), e.getY(), w, h);
                }
                if (velocity != null) {
                    velocity.recycle();
                    velocity = null;
                }
                break;
            }

            default:
                break;
        }
        return true;
    }

    private void handleUp(float x, float y, int w, int h) {
        // 刚把一次滑动按住终止，这一下抬手不再当成点击
        if (flingInterrupted) {
            flingInterrupted = false;
            return;
        }
        long held = System.currentTimeMillis() - downTime;
        if (held >= longPressMs()) {
            openSettings();
            return;
        }
        if (prefs.showGear && hitGear(x, y, w, h)) {
            openSettings();
            return;
        }
        // 只有完全停住的那一页才接受点击
        int idx = currentIndex();
        int w2 = getWidth();
        if (w2 <= 0 || Math.abs(scroll - idx * (float) w2) > 1f) {
            return;
        }
        if (isMonthScreen(idx)) {
            // 月历页要的是内容坐标系下的点，所以把滚动偏移加回去
            if (monthPage.onTap(x + scroll, y)) {
                invalidate();
            }
            return;
        }
        if (idx == 0 && errorRect != null) {
            float gx = x + scroll;
            if (gx >= errorRect[0] && gx <= errorRect[2] && y >= errorRect[1] && y <= errorRect[3]) {
                if (retryListener != null) {
                    retryListener.retryWeather();
                }
            }
        }
    }

    private boolean isMonthScreen(int idx) {
        return idx >= 0 && idx < screens.length && screens[idx].id == PG_MONTH;
    }

    /** 防误触模式下，长按要按更久才进设置 */
    private long longPressMs() {
        return prefs.touchLock ? 1500L : 600L;
    }

    private void openSettings() {
        if (tapListener != null) {
            tapListener.openSettings();
        }
    }

    @Override
    public boolean performLongClick() {
        openSettings();
        return true;
    }

    // ------------------------------------------------------------------ 绘制入口

    @Override
    protected void onDraw(Canvas c) {
        if (prefs == null) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        if (w <= 8 || h <= 8) {
            return;
        }
        buildPages();

        // 翻页动画自驱动：不依赖框架是否回调 computeScroll
        if (scroller.computeScrollOffset()) {
            scroll = scroller.getCurrX();
        }
        // 板块内纵向惯性滚动，同样自驱动
        if (vScroller != null && vScroller.computeScrollOffset()) {
            int vi = currentIndex();
            if (vi >= 0 && vi < pageScroll.length) {
                pageScroll[vi] = vScroller.getCurrY();
            }
        }
        maybeAutoReturn();

        Calendar cal = Calendar.getInstance();
        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH) + 1;
        int day = cal.get(Calendar.DAY_OF_MONTH);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);
        int second = cal.get(Calendar.SECOND);
        floorPx = TextDraw.floor(w, h, prefs.textScale());
        errorRect = null;

        boolean hasSun = weather != null && weather.valid
                && weather.sunrise.length() >= 5 && weather.sunset.length() >= 5;
        ambientLux = LightRule.smooth(ambientLux, ambientRaw);
        boolean dark = Palette.shouldBeDark(prefs, hasSun,
                hasSun ? weather.sunrise : null,
                hasSun ? weather.sunset : null,
                hour * 60 + minute, ambientLux, darkNow);
        darkNow = dark;
        Palette pal = Palette.of(dark);

        drawBackground(c, pal, w, h);

        float sl = scroll;
        int first = (int) Math.floor(sl / w);
        int last = (int) Math.floor((sl + w - 1f) / w);
        first = Math.max(0, Math.min(pageCount - 1, first));
        last = Math.max(0, Math.min(pageCount - 1, last));

        int save = c.save();
        // 防烧屏位移只在稳定停在主页面时生效，看副页时暂停
        if (prefs.burnInProtect && prefs.burnInRange > 0 && sl <= 0.5f) {
            long period = Math.max(10, prefs.burnInPeriod) * 1000L;
            int step = (int) ((System.currentTimeMillis() / period) % 8L);
            double ang = Math.PI * 2 * step / 8.0;
            int dx = Math.round((float) (Math.cos(ang) * prefs.burnInRange));
            int dy = Math.round((float) (Math.sin(ang) * prefs.burnInRange));
            c.translate(dx, dy);
        }
        c.translate(-sl, 0f);

        LunarCalendar lunar = lunarOf(year, month, day);
        for (int i = first; i <= last; i++) {
            // 先刷新这一屏的可滚动距离，再取（夹过范围的）纵向偏移
            pageRange[i] = scrollRangeOf(i, year, month, day);
            float sy = scrollYOf(i);
            int clip = c.save();
            c.clipRect(i * (float) w, 0f, (i + 1) * (float) w, (float) h);
            drawPage(c, pal, screens[i], i * (float) w, 0f, w, h,
                    year, month, day, hour, minute, second, lunar, cal, sy);
            c.restoreToCount(clip);
        }
        c.restoreToCount(save);

        drawGear(c, pal, w, h);
        drawIndicator(c, pal, w, h);
        drawScrollHint(c, pal, w, h);

        if (!scroller.isFinished() || (vScroller != null && !vScroller.isFinished())
                || flipRunning()) {
            invalidate();
        }
    }

    private void drawPage(Canvas c, Palette pal, ScreenRef ref, float x, float y, float w, float h,
                          int year, int month, int day, int hour, int minute, int second,
                          LunarCalendar lunar, Calendar cal, float scrollY) {
        float[] box = pageBox((int) w, (int) h, x, y, pageCount > 1);
        float left = box[0];
        float top = box[1];
        float availW = box[2];
        float availH = box[3];
        if (availW <= 8f || availH <= 8f) {
            return;
        }
        boolean landscape = w > h;

        // 天气失败时主页面顶部先占一条状态条，剩下的高度才交给版式
        if (ref.id == PG_MAIN && errorText.length() > 0) {
            float barH = Layout.clamp(Math.min(availW, availH) * 0.075f, 34f, 120f);
            drawErrorBar(c, pal, left, top, availW, barH);
            errorRect = new float[]{left, top, left + availW, top + barH};
            float adv = barH + availH * 0.02f;
            top += adv;
            availH -= adv;
            if (availH <= 8f) {
                return;
            }
        }

        switch (ref.id) {
            case PG_ALMANAC:
                almanacPage.drawScreen(c, paint, pal, prefs, left, top, availW, availH,
                        year, month, day, landscape, scrollY);
                break;
            case PG_WEATHER:
                weatherDetailPage.drawScreen(c, paint, pal, prefs, weather, left, top,
                        availW, availH, landscape, cityLabel, statusNote, scrollY);
                break;
            case PG_MONTH:
                monthPage.draw(c, paint, pal, prefs, left, top, availW, availH, landscape);
                break;
            default:
                if (landscape) {
                    drawLandscape(c, pal, left, top, availW, availH,
                            year, month, day, hour, minute, second, lunar, cal);
                } else {
                    drawPortrait(c, pal, left, top, availW, availH,
                            year, month, day, hour, minute, second, lunar, cal);
                }
        }
    }

    private void drawBackground(Canvas c, Palette pal, int w, int h) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, 0, 0, h, pal.bg, pal.bgGrad, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
    }

    // ------------------------------------------------------------------ 首页版式
    //
    // 原来这里是一串写死的百分比：日期 8.5%、农历 7.5%、时钟占剩余的 44%（不显示天气时 74%）、
    // 而时钟字号的上限还要再乘 0.8。结果是「关掉内容后时钟也不变大」，中间空一大块。
    // 现在改成：每一块先报自己最少要多高，剩下的交给 Layout.allocate 按权重分，
    // 时钟按「再高也不会更大」的高度报需求，富余高度自然流向天气卡与预报卡。

    /** 时钟文字的宽高探测：返回 {宽度系数, 行高系数}（都是 100px 字号下的像素值） */
    private float[] clockProbe() {
        paint.setTypeface(Typeface.create(SANS, Typeface.BOLD));
        paint.setTextSize(100f);
        float k = monoWidth(paint, "88:88");
        if (prefs.showSeconds) {
            paint.setTextSize(28f);
            k += 16f + paint.measureText("88");           // gapSec = 0.16em
        }
        if (!prefs.use24h) {
            paint.setTextSize(20f);
            k += 12f + paint.measureText("上午");          // gapAmpm = 0.12em
        }
        paint.setTextSize(100f);
        Paint.FontMetrics fm = paint.getFontMetrics();
        return new float[]{k, fm.descent - fm.ascent};
    }

    /** 时钟「再高也不会更大」的框高：竖屏时字号先被屏宽卡住，再给高度只是空白 */
    private float clockNeedHeight(float availW, float scale) {
        float[] p = clockProbe();
        if (!(p[0] > 0f) || !(p[1] > 0f)) {
            return availW;
        }
        float sc = Math.max(0.6f, scale);
        return availW * 0.98f * (p[1] / p[0]) / (0.90f * sc);
    }

    /** 反过来：框高定死时，时钟最多要用多宽 */
    private float clockWidthForHeight(float boxH, float scale) {
        float[] p = clockProbe();
        if (!(p[0] > 0f) || !(p[1] > 0f)) {
            return boxH;
        }
        float sc = Math.max(0.6f, scale);
        return boxH * 0.90f * sc * (p[0] / p[1]);
    }

    /** 只统计非零块，返回整摞内容垂直居中后的起始 y */
    private float stackStart(float top, float availH, float gap, float[] hs) {
        float used = 0f;
        int cnt = 0;
        for (float v : hs) {
            if (v > 0.5f) {
                used += v;
                cnt++;
            }
        }
        if (cnt == 0) {
            return top;
        }
        used += gap * (cnt - 1);
        float slack = availH - used;
        return top + (slack > 0f ? slack / 2f : 0f);
    }

    // ------------------------------------------------------------------ 首页副文字的字号
    //
    // 首页原来所有副文字都走「先量容器、再缩字号」：农历两行各自被压到行高的六成，
    // 再按宽度缩一次，1080p 横屏上只剩约 2.8% 屏高 —— 而旁边的时钟有 40% 高。
    // 落差太大，看着就像「农历只是时钟底下的一行脚注」。
    //
    // 现在农历/节气这两行改成**由屏幕短边定死**（和副页同一个思路），
    // 版式按同一张角色表给它留够高度。字号与高度同源，所以既不会变小、也不会压到时钟。

    /** 首页农历/节气行的字号占屏幕短边的比例（改造前实际只有约 2.8%） */
    private static final float HOME_LUNAR_PCT = 0.054f;

    /** 首页农历两行的角色：农历+干支一行、节气/节日/倒计时一行 */
    private static final float[] HOME_LUNAR_ROLES = {Type.BODY, Type.BODY};

    /** 首页农历两行的字号：只跟屏幕短边和字号档位有关，与当天有几行文字无关 */
    private float lunarFont() {
        float s = Math.min(getWidth(), getHeight());
        float f = s * HOME_LUNAR_PCT * (prefs == null ? 1f : prefs.textScale());
        return Math.max(floorPx, f);
    }

    /** 首页农历块需要的高度；量高与绘制共用同一张角色表，所以必然一致 */
    private float lunarBlockH() {
        return Type.stack(lunarFont(), HOME_LUNAR_ROLES);
    }

    // ------------------------------------------------------------------ 横屏布局

    private void drawLandscape(Canvas c, Palette pal, float left, float top, float availW, float availH,
                              int year, int month, int day, int hour, int minute, int second,
                              LunarCalendar lunar, Calendar cal) {
        boolean fc = prefs.showForecast && hasForecast();
        boolean showHeader = prefs.showDate || prefs.showWeekday;
        float gap = availH * 0.020f;

        // 注意：未来几天不再单独占底部一行，而是并进右侧窄列（见 drawSideColumn）。
        // 时钟字号受**高度**限制（"88:88" 宽高比约 2.11，16:9 屏上高度才是瓶颈），
        // 光把天气区变窄只会让时钟右边多一条空白；必须把那一行预报让出来，
        // 主块才能独占剩余高度，时间字才真的变大。
        float[] need = {
                showHeader ? availH * 0.135f : 0f,
                availH * 0.34f,
        };
        float[] grow = {showHeader ? 0.10f : 0f, 1f};
        float[] hs = Layout.allocate(availH, gap, need, grow);
        if (hs == null) {
            return;
        }
        float y = stackStart(top, availH, gap, hs);
        if (showHeader && hs[0] > 0.5f) {
            drawHeader(c, pal, left, y, availW, hs[0], year, month, day, cal);
            y += hs[0] + gap;
        }
        if (hs[1] > 0.5f) {
            drawMainLandscape(c, pal, left, y, availW, hs[1], hour, minute, second, lunar, fc);
        }
    }

    /** 右侧一列里「天气卡 : 未来几天」的高度比 */
    private static final float[] W_SIDE_SPLIT = {0.64f, 0.36f};

    private void drawMainLandscape(Canvas c, Palette pal, float left, float top, float availW, float availH,
                                   int hour, int minute, int second, LunarCalendar lunar, boolean fc) {
        boolean showWx = prefs.showWeather;
        boolean showLunarRow = prefs.showLunar || prefs.showTermFestival || prefs.showCountdown;
        float gap = availW * 0.025f;

        // 纵向：农历两行在最下，时钟吃掉剩下的。
        // 农历的高度不再写「主块的百分之几」——那种百分比与字号是两套数字，
        // 结果就是上面说的「字被压到 2.8% 屏高」。现在直接用角色行高，
        // 想放大农历只改 HOME_LUNAR_PCT 一个数，高度会自动跟上。
        float lunarH = showLunarRow ? lunarBlockH() : 0f;
        float clockH = Math.max(20f, availH - lunarH - (showLunarRow ? gap : 0f));

        // 横向：右侧一列按内容需要定宽（20%~30%），不再吃掉全部剩余
        float clockW = availW;
        float sideW = 0f;
        boolean needSide = showWx || fc;
        if (needSide) {
            sideW = Layout.clamp(availW * 0.26f, availW * 0.20f, availW * 0.30f);
            clockW = Math.max(availW * 0.30f, availW - sideW - gap);
        }
        drawClock(c, pal, left, top, clockW, clockH, hour, minute, second, showWx);
        if (showLunarRow && lunarH > 1f) {
            drawLunarLines(c, pal, left, top + clockH + gap, clockW, lunarH, lunar);
        }
        if (needSide && sideW > 8f) {
            drawSideColumn(c, pal, left + clockW + gap, top, sideW, availH, showWx, fc);
        }
    }

    /** 右侧一列：上面天气卡，下面未来几天预报；两者都没有就不画 */
    private void drawSideColumn(Canvas c, Palette pal, float left, float top, float w, float h,
                                boolean showWx, boolean fc) {
        if (showWx && fc) {
            float gap = h * 0.025f;
            float[] hs = Layout.split(h, gap, W_SIDE_SPLIT);
            if (hs == null) {
                return;
            }
            drawWeatherPanel(c, pal, left, top, w, hs[0]);
            drawForecast(c, pal, left, top + hs[0] + gap, w, hs[1]);
        } else if (showWx) {
            drawWeatherPanel(c, pal, left, top, w, h);
        } else if (fc) {
            drawForecast(c, pal, left, top, w, h);
        }
    }

    // ------------------------------------------------------------------ 竖屏布局

    private void drawPortrait(Canvas c, Palette pal, float left, float top, float availW, float availH,
                              int year, int month, int day, int hour, int minute, int second,
                              LunarCalendar lunar, Calendar cal) {
        boolean showWx = prefs.showWeather;
        boolean fc = prefs.showForecast && hasForecast();
        boolean showHeader = prefs.showDate || prefs.showWeekday;
        boolean showLunarRow = prefs.showLunar || prefs.showTermFestival || prefs.showCountdown;
        float scale = prefs.clockScale / 100f;
        float gap = availH * 0.020f;

        // 关键的一条：关掉日期/星期后，头部的高度需求就是 0，不再白占 8.5%
        float[] need = {
                showHeader ? availH * 0.075f : 0f,
                showLunarRow ? lunarBlockH() : 0f,
                Math.min(clockNeedHeight(availW, scale), availH * 0.80f),
                showWx ? availH * 0.17f : 0f,
                fc ? availH * 0.17f : 0f,
        };
        float[] grow = {
                showHeader ? 0.12f : 0f,
                showLunarRow ? 0.12f : 0f,
                1.00f,                       // 时钟优先吃富余高度
                showWx ? 0.30f : 0f,
                fc ? 0.25f : 0f,
        };
        float[] hs = Layout.allocate(availH, gap, need, grow);
        if (hs == null) {
            return;
        }
        float y = stackStart(top, availH, gap, hs);

        if (showHeader && hs[0] > 0.5f) {
            drawHeader(c, pal, left, y, availW, hs[0], year, month, day, cal);
            y += hs[0] + gap;
        }
        if (showLunarRow && hs[1] > 0.5f) {
            drawLunarLines(c, pal, left, y, availW, hs[1], lunar);
            y += hs[1] + gap;
        }
        if (hs[2] > 0.5f) {
            drawClock(c, pal, left, y, availW, hs[2], hour, minute, second, false);
            y += hs[2] + gap;
        }
        if (showWx && hs[3] > 0.5f) {
            drawWeatherPanel(c, pal, left, y, availW, hs[3]);
            y += hs[3] + gap;
        }
        if (fc && hs[4] > 0.5f) {
            drawForecast(c, pal, left, y, availW, hs[4]);
        }
    }

    // ------------------------------------------------------------------ 顶部一行

    private void drawHeader(Canvas c, Palette pal, float left, float top, float w, float h,
                            int year, int month, int day, Calendar cal) {
        StringBuilder sb = new StringBuilder();
        if (prefs.showDate) {
            sb.append(year).append("年").append(month).append("月").append(day).append("日");
        }
        if (prefs.showWeekday) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append("星期").append(WEEK_CN[cal.get(Calendar.DAY_OF_WEEK) - 1]);
        }

        boolean landscape = getWidth() > getHeight();
        float textH = h * 0.66f;

        if (landscape) {
            if (sb.length() > 0) {
                paint.setTypeface(Typeface.create(SANS, Typeface.BOLD));
                paint.setColor(pal.textSecondary);
                fit(paint, sb.toString(), w * 0.56f, textH, textH, false);
                draw(c, sb.toString(), left, top + h * 0.5f, paint, 1);
            }
            String right = headerRightText();
            if (right.length() > 0) {
                paint.setTypeface(Typeface.create(SANS, Typeface.NORMAL));
                paint.setColor(pal.textFaint);
                fit(paint, right, w * 0.42f, h * 0.50f, h * 0.50f, false);
                draw(c, right, left + w, top + h * 0.5f, paint, 2);
            }
        } else {
            float reserved = 0f;
            String right = batteryText();
            if (right.length() > 0) {
                paint.setTypeface(Typeface.create(SANS, Typeface.NORMAL));
                paint.setColor(pal.textFaint);
                fit(paint, right, w * 0.26f, textH * 0.78f, textH * 0.78f, false);
                draw(c, right, left + w, top + h * 0.5f, paint, 2);
                reserved = paint.measureText(right) + w * 0.04f;
            }
            if (sb.length() > 0) {
                paint.setTypeface(Typeface.create(SANS, Typeface.BOLD));
                paint.setColor(pal.textSecondary);
                fit(paint, sb.toString(), Math.max(w * 0.3f, w - reserved), textH, textH, false);
                draw(c, sb.toString(), left, top + h * 0.5f, paint, 1);
            }
        }
    }

    private String headerRightText() {
        StringBuilder sb = new StringBuilder();
        if (prefs.showCityName && cityLabel.length() > 0) {
            sb.append(cityLabel);
        }
        if (weather != null && weather.valid && weather.updateTime > 0) {
            Calendar u = Calendar.getInstance();
            u.setTimeInMillis(weather.updateTime);
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append("更新 ").append(String.format(Locale.US, "%02d:%02d",
                    u.get(Calendar.HOUR_OF_DAY), u.get(Calendar.MINUTE)));
        }
        String b = batteryText();
        if (b.length() > 0) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(b);
        }
        return sb.toString();
    }

    private String batteryText() {
        if (!prefs.showBattery || battery < 0) {
            return "";
        }
        return (charging ? "充电中 " : "电量 ") + battery + "%";
    }

    // ------------------------------------------------------------------ 农历信息

    /**
     * 首页的农历 / 节气两行。
     *
     * <p>横竖屏一律两行：竖屏以前把农历、节气、倒计时挤成一行居中，只能靠缩字号将就宽度，
     * 越挤越小。拆成两行之后每一行都短了，字号就不必再让位给长度。
     *
     * <p>字号由 {@link #lunarFont()} 定死（屏幕短边的固定比例），宽度放不下就省略号收尾，
     * <b>不再缩字号</b>——与副页的 {@code TextDraw.drawLine} 是同一条规矩。
     */
    private void drawLunarLines(Canvas c, Palette pal, float left, float top, float w, float h,
                                LunarCalendar lunar) {
        if (lunar == null || h <= 1f) {
            return;
        }
        float size = lunarFont();
        float lineH = h / HOME_LUNAR_ROLES.length;
        StringBuilder first = new StringBuilder();
        if (prefs.showLunar) {
            first.append("农历").append(lunar.monthName).append(lunar.dayName);
        }
        if (prefs.showTermFestival) {
            if (first.length() > 0) {
                first.append(" · ");
            }
            first.append(lunar.ganZhi).append(lunar.zodiac).append("年");
        }

        StringBuilder tail = new StringBuilder();
        if (prefs.showTermFestival) {
            if (lunar.term != null) {
                tail.append(lunar.term);
            }
            if (lunar.festival != null) {
                if (tail.length() > 0) {
                    tail.append(" · ");
                }
                tail.append(lunar.festival);
            }
        }
        if (prefs.showCountdown) {
            String cd = countdownText();
            if (cd.length() > 0) {
                if (tail.length() > 0) {
                    tail.append(" · ");
                }
                tail.append(cd);
            }
        }

        boolean emphasize = (lunar.term != null || lunar.festival != null) && tail.length() > 0;

        paint.setTypeface(Typeface.create(SANS, Typeface.NORMAL));
        if (first.length() > 0) {
            TextDraw.drawLine(c, paint, first.toString(), left, top + lineH * 0.5f, w,
                    size, pal.textSecondary, 1);
        }
        if (tail.length() > 0) {
            TextDraw.drawLine(c, paint, tail.toString(), left, top + lineH * 1.5f, w,
                    size, emphasize ? pal.accent : pal.textFaint, 1);
        }
    }

    private String countdownText() {
        int jdn = lunarCacheJdn;
        if (countdownCacheJdn == jdn) {
            return countdownCache;
        }
        String out = "";
        if (jdn > 0) {
            int[] t = LunarCalendar.fromJdn(jdn);
            String[] next = LunarCalendar.nextFestival(t[0], t[1], t[2]);
            if (next != null) {
                out = "距" + next[0] + " " + next[1] + " 天";
            }
        }
        countdownCache = out;
        countdownCacheJdn = jdn;
        return out;
    }

    private LunarCalendar lunarOf(int y, int m, int d) {
        int jdn = LunarCalendar.toJdn(y, m, d);
        if (lunarCache == null || lunarCacheJdn != jdn) {
            lunarCache = LunarCalendar.fromSolar(y, m, d);
            lunarCacheJdn = jdn;
            countdownCacheJdn = -1;
        }
        return lunarCache;
    }

    // ------------------------------------------------------------------ 时钟

    /** 翻片动画时长（毫秒）：太黏看不出干脆，太短看不清是「翻」过去的 */
    private static final long FLIP_MS = 340L;
    /** 翻页钟：上一次真正显示过的串（动画起点），以及本次翻片的起跳时刻 */
    private String flipShown = "";
    private String flipPrev = "";
    private long flipStart = 0L;

    private float flipFrac() {
        if (flipStart <= 0L) {
            return 1f;
        }
        float f = (System.currentTimeMillis() - flipStart) / (float) FLIP_MS;
        return f >= 1f ? 1f : f;
    }

    /** 翻片还在跑吗——是就自驱多画几帧，跑完立刻停（挂墙上不能一直满帧重绘） */
    private boolean flipRunning() {
        return flipStart > 0L && flipFrac() < 1f;
    }

    /**
     * 同步「上一次 / 这一次」的时间串，并给出本次翻片进度。
     *
     * <p>只有显示内容真的变了才重新起跳，所以一秒最多翻一次；动画跑完后把
     * {@link #flipPrev} 拉平到当前串，下一帧所有位都相等，自然就不翻也不重绘了。
     */
    private float stepFlip(String text) {
        if (!text.equals(flipShown)) {
            flipPrev = flipShown;
            flipShown = text;
            flipStart = System.currentTimeMillis();
        }
        float f = flipFrac();
        if (f >= 1f) {
            flipPrev = flipShown;
        }
        return f;
    }

    /**
     * 非「现代」的样式（辉光管 / 灯丝管 / VFD / 翻页钟 / 全息 / 点阵 / 五种科技感）走这里。
     *
     * @return true 表示已经画完，调用方不必再画现代样式
     */
    private boolean drawStyledClock(Canvas c, Palette pal, float left, float top, float w, float h,
                                    int hour, int minute, int second, boolean landscape) {
        int st = ClockSpec.norm(prefs.clockStyle);
        if (st == ClockSpec.MODERN) {
            return false;
        }
        // clockScale 照样生效：只缩放框高，框始终居中，不会把时间顶到上半格
        float sc = Math.max(0.5f, Math.min(1.4f, prefs.clockScale / 100f));
        float boxH = h * Math.min(1f, 0.92f * sc);
        float boxTop = top + (h - boxH) * 0.5f;
        String text = ClockSpec.text(hour, minute, second, prefs.use24h, prefs.showSeconds);
        // 只有翻页钟要看「上一次是什么」——其它样式不需要动画状态
        String prev = null;
        float frac = -1f;
        if (st == ClockSpec.FLIP) {
            frac = stepFlip(text);
            prev = flipPrev;
        }
        float used = ClockStyle.draw(c, paint, pal, st, pal.dark, text,
                ClockSpec.ampm(hour, prefs.use24h),
                left, boxTop, w, boxH, ClockSpec.glowScale(prefs.glowLevel), landscape,
                prev, frac);
        return used > 0f;      // 框太小画不下就退回现代样式，至少时间还在
    }

    private void drawClock(Canvas c, Palette pal, float left, float top, float w, float h,
                           int hour, int minute, int second, boolean landscape) {
        if (drawStyledClock(c, pal, left, top, w, h, hour, minute, second, landscape)) {
            return;
        }

        String hhmm;
        String ampm = "";
        if (prefs.use24h) {
            hhmm = String.format(Locale.US, "%02d:%02d", hour, minute);
        } else {
            int h12 = hour % 12;
            if (h12 == 0) {
                h12 = 12;
            }
            hhmm = String.format(Locale.US, "%2d:%02d", h12, minute);
            ampm = hour < 12 ? "上午" : "下午";
        }

        float scale = Math.max(0.5f, prefs.clockScale / 100f);
        float maxH = h * 0.90f * scale;      // 原来是 0.80，白留了 20% 高度
        float maxW = w * 0.98f;

        paint.setTypeface(Typeface.create(SANS, Typeface.BOLD));
        float size = fitSize(paint, hhmm, maxW, maxH, maxH, true);
        float clockW = monoWidth(paint, hhmm);

        String secText = prefs.showSeconds ? String.format(Locale.US, "%02d", second) : "";
        float secSize = size * 0.28f;
        float secW = 0f;
        if (secText.length() > 0) {
            paint.setTextSize(secSize);
            secW = paint.measureText(secText);
        }
        float ampmW = 0f;
        if (ampm.length() > 0) {
            paint.setTextSize(size * 0.20f);
            ampmW = paint.measureText(ampm);
        }
        float gapSec = secW > 0 ? size * 0.16f : 0f;
        float gapAmpm = ampmW > 0 ? size * 0.12f : 0f;
        float totalW = clockW + gapSec + secW + gapAmpm + ampmW;

        if (totalW > maxW && totalW > 0) {
            float k = maxW / totalW;
            size *= k;
            clockW *= k;
            secSize *= k;
            secW *= k;
            ampmW *= k;
            gapSec *= k;
            gapAmpm *= k;
            totalW = maxW;
        }

        paint.setTextSize(size);
        float x = left;
        if (!landscape) {
            x = left + Math.max(0f, (w - totalW) / 2f);
        }
        Paint.FontMetrics fm = paint.getFontMetrics();
        float baseY = top + h * 0.5f - (fm.ascent + fm.descent) / 2f;

        paint.setColor(pal.textPrimary);
        x = drawMono(c, hhmm, x, baseY, paint);

        if (secW > 0) {
            x += gapSec;
            paint.setColor(pal.accent);
            paint.setTextSize(secSize);
            c.drawText(secText, x, baseY - (fm.descent - fm.ascent) * 0.30f, paint);
            x += secW;
        }
        if (ampmW > 0) {
            x += gapAmpm;
            paint.setColor(pal.textFaint);
            paint.setTextSize(size * 0.20f);
            c.drawText(ampm, x, baseY - size * 0.36f, paint);
        }
    }

    private boolean hasForecast() {
        return weather != null && weather.valid
                && (weather.fHas[1] || weather.fHas[2] || weather.fHas[3]);
    }

    // ------------------------------------------------------------------ 天气卡片

    private void drawWeatherPanel(Canvas c, Palette pal, float left, float top, float w, float h) {
        float radius = Math.min(w, h) * 0.07f;
        RectF r = new RectF(left, top, left + w, top + h);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pal.panel);
        c.drawRoundRect(r, radius, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, Math.min(w, h) * 0.0035f));
        paint.setColor(pal.panelStroke);
        c.drawRoundRect(r, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);

        float inset = Math.min(w, h) * 0.075f;
        float ix = left + inset;
        float iy = top + inset;
        float iw = w - inset * 2f;
        float ih = h - inset * 2f;

        // 面板里各处的层级和副页共用同一张角色表，只是基准字号由面板自身尺寸定
        // （这一块归首页管，不走副页的 pageFont）。上一版每行各自乘一个系数
        // ——温度 0.62、状况 0.32、详情 0.66——温度与状况差不到一倍，整块看着是平的。
        //
        // 基准 0.155 倍内高的来历：三行注释 + 英雄数字 + 卡片标题一共 5.688 个行高，
        // 5.688 × 0.155 ≈ 0.88，正好留下一成多内高做行间呼吸。
        float base = Math.min(Math.max(4f, ih * 0.155f), Math.max(4f, iw * 0.115f));

        if (weather == null || !weather.valid) {
            String msg = statusNote.length() > 0 ? statusNote : "天气加载中…";
            paint.setTypeface(Typeface.create(SANS, Typeface.NORMAL));
            TextDraw.drawLine(c, paint, msg, ix + iw / 2f, iy + ih / 2f, iw,
                    Type.size(base, Type.NOTE), pal.textFaint, 0);
            return;
        }

        // 温度单位由设置决定（0 摄氏度 / 1 华氏度）
        weather.unit = prefs.tempUnit;

        float heroH = Type.line(base, Type.HERO);
        float headH = Type.line(base, Type.HEAD);
        float noteH = Type.line(base, Type.NOTE);
        float labelH = Type.line(base, Type.LABEL);

        // 上半：图标 + 温度（英雄数字）+ 天气状况（卡片标题）
        float topH = heroH + headH;
        float iconS = Math.min(topH * 0.92f, iw * 0.32f);
        WeatherArt.draw(c, Wmo.icon(weather.weatherCode, weather.isDay),
                ix + iconS / 2f, iy + topH / 2f, iconS, pal, weather.isDay);

        float tx = ix + iconS + inset * 0.6f;
        float tw = Math.max(iw * 0.2f, ix + iw - tx);

        float y = iy;
        paint.setTypeface(Typeface.create(SANS, Typeface.BOLD));
        TextDraw.drawLine(c, paint, weather.formatTemp(weather.temp), tx, y + heroH * 0.5f, tw,
                Type.size(base, Type.HERO), pal.textPrimary, 1);
        y += heroH;

        paint.setTypeface(Typeface.create(SANS, Typeface.NORMAL));
        TextDraw.drawLine(c, paint, Wmo.text(weather.weatherCode), tx, y + headH * 0.5f, tw,
                Type.size(base, Type.HEAD), pal.textSecondary, 1);

        if (!Double.isNaN(weather.todayMax) && !Double.isNaN(weather.todayMin)) {
            TextDraw.drawLine(c, paint,
                    weather.formatRange(weather.todayMin, weather.todayMax),
                    ix + iw, iy + labelH * 0.6f, iw * 0.55f,
                    Type.size(base, Type.LABEL), pal.textFaint, 2);
        }

        // 下半：文字行（最多 3 行：详情 / 日出日落 / AQI）
        List<String> lines = new ArrayList<String>();
        List<Integer> colors = new ArrayList<Integer>();
        if (prefs.weatherDetail) {
            lines.add(weather.detailLine());
            colors.add(Integer.valueOf(pal.textFaint));
        }
        if (prefs.showSunTime && weather.sunrise.length() >= 5) {
            lines.add("日出 " + weather.sunrise + " · 日落 " + weather.sunset);
            colors.add(Integer.valueOf(pal.textFaint));
        }
        if (prefs.showAqi && weather.aqi >= 0) {
            lines.add("AQI " + weather.aqi + " " + AqiUtil.level(weather.aqi));
            colors.add(Integer.valueOf(AqiUtil.color(weather.aqi)));
        }
        if (statusNote.length() > 0 && lines.size() < 3) {
            lines.add(statusNote);
            colors.add(Integer.valueOf(pal.textFaint));
        }

        float areaTop = iy + topH;
        float areaH = Math.max(0f, ih - topH);
        int n = lines.size();
        if (n > 0 && areaH > 0) {
            // 注释行也按角色行高摆；面板太矮就少显示一行，而不是把字缩小
            // （「字随内容变大小」那个毛病就是这么来的）
            int show = Math.min(n, Math.max(1, (int) (areaH / Math.max(1f, noteH))));
            float step = areaH / show;
            for (int i = 0; i < show; i++) {
                String s = lines.get(i);
                boolean aqiLine = s.startsWith("AQI ");
                paint.setTypeface(Typeface.create(SANS,
                        aqiLine ? Typeface.BOLD : Typeface.NORMAL));
                TextDraw.drawLine(c, paint, s, ix, areaTop + step * (i + 0.5f), iw,
                        Type.size(base, Type.NOTE), colors.get(i).intValue(), 1);
            }
        }
    }

    // ------------------------------------------------------------------ 未来几天

    private void drawForecast(Canvas c, Palette pal, float left, float top, float w, float h) {
        int[] idxs = {1, 2, 3};
        float cellW = w / idxs.length;

        for (int k = 0; k < idxs.length; k++) {
            int i = idxs[k];
            if (!weather.fHas[i]) {
                continue;
            }
            float cx = left + cellW * k;
            if (k > 0) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, h * 0.006f));
                paint.setColor(pal.line);
                c.drawLine(cx, top + h * 0.14f, cx, top + h * 0.86f, paint);
                paint.setStyle(Paint.Style.FILL);
            }

            // 格子里的三行也分三档：日期（标签级）< 天气描述（注释级）< 温度（正文级）。
            // 上一版三行是 0.19 / 0.19 / 0.16 倍格高，几乎一样大，看不出主次。
            // 高度按角色行高自上而下排，剩下的给图标，不会互相压盖。
            float base = h * 0.150f;
            float labelH = Type.line(base, Type.LABEL);
            float valueH = Type.line(base, Type.BODY);
            float condH = Type.line(base, Type.NOTE);
            float iconBoxH = Math.max(0f, h - labelH - valueH - condH);
            float cxm = cx + cellW / 2f;

            // 预报格子很窄，「18° / 27°」这种串宽度常常比高度更要紧，
            // 所以这三行用「从角色字号往下收，但不低于可读下限」的写法：
            // 位置与行高仍由角色定（层级在），宽度真放不下时才允许收一点。
            float y = top;
            paint.setTypeface(Typeface.create(SANS, Typeface.NORMAL));
            float floor = Math.max(6f, floorPx * 0.9f);
            String lab = dayLabel(i, weather.fMonth[i], weather.fDay[i]);
            TextDraw.fitLine(paint, lab, cellW * 0.80f, labelH, Type.size(base, Type.LABEL),
                    false, floor);
            draw(c, lab, cxm, y + labelH * 0.5f, paint, 0);
            y += labelH;

            float iconS = Math.min(iconBoxH * 0.80f, cellW * 0.34f);
            WeatherArt.draw(c, Wmo.icon(weather.fCode[i], true), cxm, y + iconBoxH * 0.5f,
                    iconS, pal, true);
            y += iconBoxH;

            String rng = weather.formatRange(weather.fMin[i], weather.fMax[i]);
            TextDraw.fitLine(paint, rng, cellW * 0.86f, valueH, Type.size(base, Type.BODY),
                    false, floor);
            paint.setColor(pal.textSecondary);
            draw(c, rng, cxm, y + valueH * 0.5f, paint, 0);
            y += valueH;

            String cond = Wmo.text(weather.fCode[i]);
            TextDraw.fitLine(paint, cond, cellW * 0.86f, condH, Type.size(base, Type.NOTE),
                    false, floor);
            paint.setColor(pal.textFaint);
            draw(c, cond, cxm, y + condH * 0.5f, paint, 0);
        }
    }

    private String dayLabel(int i, int month, int day) {
        if (i == 1) {
            return "明天";
        }
        if (i == 2) {
            return "后天";
        }
        Calendar now = Calendar.getInstance();
        int year = now.get(Calendar.YEAR);
        if (month < now.get(Calendar.MONTH) + 1 - 6) {
            year++;
        }
        int dow = mod(LunarCalendar.toJdn(year, month, day) + 1, 7);
        return "周" + WEEK_CN[dow];
    }

    private static int mod(int a, int b) {
        int r = a % b;
        return r < 0 ? r + b : r;
    }

    // ------------------------------------------------------------------ 齿轮与页码

    /** 天气失败的状态条：整条可点，点一下重试 */
    private void drawErrorBar(Canvas c, Palette pal, float left, float top, float w, float h) {
        float radius = Math.min(h * 0.30f, w * 0.04f);
        RectF r = new RectF(left, top, left + w, top + h);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(TextDraw.softBg(pal, C_ERR));
        c.drawRoundRect(r, radius, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, h * 0.018f));
        paint.setColor(TextDraw.softText(pal, C_ERR));
        c.drawRoundRect(r, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);

        float padX = h * 0.55f;
        paint.setTypeface(Typeface.create(SANS, Typeface.NORMAL));
        paint.setColor(TextDraw.softText(pal, C_ERR));
        fit(paint, "点这里重试", w * 0.30f, h * 0.44f, h * 0.44f, false);
        float hintW = paint.measureText("点这里重试");
        draw(c, "点这里重试", left + w - padX, top + h / 2f, paint, 2);

        paint.setTypeface(Typeface.create(SANS, Typeface.BOLD));
        paint.setColor(pal.textPrimary);
        float msgW = Math.max(w * 0.25f, w - hintW - padX * 3f);
        fit(paint, errorText, msgW, h * 0.50f, h * 0.50f, false);
        draw(c, errorText, left + padX, top + h / 2f, paint, 1);
    }

    private void drawGear(Canvas c, Palette pal, int w, int h) {
        if (!prefs.showGear) {
            return;
        }
        float m = Math.min(w, h);
        float cx = w - m * 0.055f;
        float cy = h - m * 0.055f;
        float r = gearRadius(w, h);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.2f, r * 0.30f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(pal.textFaint);
        c.drawCircle(cx, cy, r * 0.60f, paint);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8 + Math.PI / 8;
            c.drawLine(cx + (float) (Math.cos(a) * r * 0.72f), cy + (float) (Math.sin(a) * r * 0.72f),
                    cx + (float) (Math.cos(a) * r * 1.05f), cy + (float) (Math.sin(a) * r * 1.05f), paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    /** 底部分页指示点：当前页是放大的实心点，其余是半透明小点 */
    private void drawIndicator(Canvas c, Palette pal, int w, int h) {
        if (pageCount <= 1) {
            return;
        }
        float m = Math.min(w, h);
        float r = Math.max(2.0f, m * 0.0055f);
        float gap = r * 4.0f;
        float total = gap * (pageCount - 1);
        float cy = h - m * 0.038f;
        float cx = w / 2f;
        int cur = currentIndex();

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        for (int i = 0; i < pageCount; i++) {
            float x = cx - total / 2f + i * gap;
            boolean active = i == cur;
            paint.setColor(active
                    ? pal.accent
                    : TextDraw.blend(pal.textFaint, pal.bg, 0.45f));
            c.drawCircle(x, cy, active ? r * 1.45f : r, paint);
        }
    }

    /**
     * 板块内的纵向滚动条。
     *
     * <p>竖滑是这一版才有的交互，不画出来的话用户很可能根本不知道还能往下看——
     * 尤其内容只比一屏多一点点的时候，看着像已经到底了。
     */
    private void drawScrollHint(Canvas c, Palette pal, int w, int h) {
        int idx = currentIndex();
        if (idx < 0 || idx >= pageRange.length || idx >= pageContentH.length) {
            return;
        }
        float range = pageRange[idx];
        float contentH = pageContentH[idx];
        if (!(range > 1f) || !(contentH > 1f)) {
            return;
        }
        float m = Math.min(w, h);
        float barW = Math.max(2.5f, m * 0.006f);
        float padX = m * 0.012f;
        float top = h * 0.12f;
        float bot = h * 0.86f;
        float trackH = bot - top;
        if (trackH <= 4f) {
            return;
        }
        float x = w - padX - barW;

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        // 整页翻页之后，这里画的是**页码**而不是「内容比例」：
        // 一小段 = 一页，高亮的那段是当前页。一眼能看出还有几页、现在第几页。
        float[] stops = stopsOf(idx);
        if (stops.length < 2) {
            return;
        }
        int n = stops.length;
        float cur = scrollYOf(idx);
        int active = 0;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            float d = Math.abs(stops[i] - cur);
            if (d < best) {
                best = d;
                active = i;
            }
        }
        float segGap = Math.max(1.5f, barW * 0.55f);
        float segH = (trackH - segGap * (n - 1)) / n;
        if (!(segH > 1f)) {
            segH = Math.max(1f, trackH / n);
            segGap = 0f;
        }
        float y = top;
        for (int i = 0; i < n && y + segH <= bot + 0.5f; i++) {
            paint.setColor(i == active
                    ? pal.accent
                    : TextDraw.blend(pal.textFaint, pal.bg, 0.55f));
            c.drawRoundRect(new android.graphics.RectF(x, y, x + barW, y + segH),
                    barW / 2f, barW / 2f, paint);
            y += segH + segGap;
        }
        paint.setStyle(Paint.Style.FILL);
    }

    // ------------------------------------------------------------------ 文字工具

    /** 当前画布的可读字号下限，onDraw 开头会刷新 */
    private float floorPx = TextDraw.MIN_SIZE;

    /**
     * 主界面所有文字都从这里取字号，并且都带可读下限。
     * 唯一不走这里的是时钟本体——它由 {@code fitSize} 直接算，字号本来就远大于下限。
     */
    private float fit(Paint p, String s, float maxW, float maxH, float startSize, boolean mono) {
        return TextDraw.fit(p, s, maxW, maxH, startSize, mono, floorPx);
    }

    private float fitSize(Paint p, String s, float maxW, float maxH, float startSize, boolean mono) {
        return TextDraw.fitSize(p, s, maxW, maxH, startSize, mono);
    }

    /** align: 0 居中 1 左 2 右 */
    private void draw(Canvas c, String s, float x, float cy, Paint p, int align) {
        TextDraw.draw(c, s, x, cy, p, align);
    }

    private float monoWidth(Paint p, String s) {
        return TextDraw.monoWidth(p, s);
    }

    private float drawMono(Canvas c, String s, float x, float baseline, Paint p) {
        return TextDraw.drawMono(c, s, x, baseline, p);
    }
}
