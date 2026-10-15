package com.vincent.wallclock.gen;

/**
 * 时钟样式的纯算术层（不含任何 Android 依赖，可桌面单测）。
 *
 * <p>这一层只管三件不会随画布变的事：
 * <ul>
 *   <li>每种样式的<b>配色</b>（深色/浅色各一套发光色、暗色、底色）；</li>
 *   <li><b>字模</b>：七段的段掩码、点阵的 5×7 位图——写错一位在真机上就是
 *       「某个数字缺一笔」，靠肉眼很难查，所以放这里穷举回归；</li>
 *   <li>时间串的拼法（24 小时制 / 12 小时制、要不要秒）。</li>
 * </ul>
 *
 * <p>具体的发光、玻璃反光、翻页中缝这些绘制细节在 {@code ClockStyle}（依赖 Canvas）。
 */
public final class ClockSpec {

    // ------------------------------------------------------------------ 样式

    /** 现代无衬线（原来的样子，也是默认） */
    public static final int MODERN = 0;
    /** 辉光管：橙红霓虹，管里堆叠的金属丝数字，未点亮的隐约可见 */
    public static final int NIXIE = 1;
    /** 灯丝管 Numitron：暖橙七段，段间有缝，陶瓷封装质感 */
    public static final int NUMITRON = 2;
    /** VFD 荧光屏：青绿冷光七段 */
    public static final int VFD = 3;
    /** 翻页钟：机械翻页牌，上下两半带中缝，不发光 */
    public static final int FLIP = 4;
    /** 全息 HUD：青蓝细线 + 扫描线 + 四角框，科技感 */
    public static final int HOLO = 5;
    /** 点阵 LED：红色点阵，车站大屏的味道 */
    public static final int DOT = 6;

    // ---- 以下是「科技感」一组：都是字形类，靠装饰层拉开区别 ----
    // 共同点：都发光、都自带底板（浅色主题下要有自己的底才不会糊在白纸上）。

    /** 矩阵雨：荧光绿数字，背后是逐秒下落的数码雨 */
    public static final int MATRIX = 7;
    /** 霓虹灯管：品红霓虹，粗描边多层辉光 + 管子上缘的玻璃反光 */
    public static final int NEON = 8;
    /** CRT 绿屏：绿磷光 + 扫描线 + 暗角 + 轻微色差，老终端那味 */
    public static final int CRT = 9;
    /** 雷达扫描：青色，上下边缘刻度 + 一条每 60 秒扫一遍的竖直光带 */
    public static final int SCAN = 10;
    /** 赛博朋克：青 / 品红错位描边 + 背后的网格，霓虹都市的招牌色 */
    public static final int CYBER = 11;

    public static final int COUNT = 12;

    /** 字模类型 */
    public static final int KIND_GLYPH = 0;
    public static final int KIND_SEVEN = 1;
    public static final int KIND_DOT = 2;

    private ClockSpec() {
    }

    public static int norm(int style) {
        return (style >= 0 && style < COUNT) ? style : MODERN;
    }

    public static String name(int style) {
        switch (norm(style)) {
            case NIXIE:
                return "辉光管";
            case NUMITRON:
                return "灯丝管";
            case VFD:
                return "VFD 荧光屏";
            case FLIP:
                return "翻页钟";
            case HOLO:
                return "全息 HUD";
            case DOT:
                return "点阵 LED";
            case MATRIX:
                return "矩阵雨";
            case NEON:
                return "霓虹灯管";
            case CRT:
                return "CRT 绿屏";
            case SCAN:
                return "雷达扫描";
            case CYBER:
                return "赛博朋克";
            default:
                return "现代";
        }
    }

    /** 字模类型：决定用系统字形、七段还是点阵 */
    public static int kind(int style) {
        switch (norm(style)) {
            case NUMITRON:
            case VFD:
                return KIND_SEVEN;
            case DOT:
                return KIND_DOT;
            default:
                return KIND_GLYPH;
        }
    }

    /** 是否发光（发光样式在浅色主题下会被配一块同色系的浅底） */
    public static boolean glows(int style) {
        switch (norm(style)) {
            case NIXIE:
            case NUMITRON:
            case VFD:
            case HOLO:
            case DOT:
            case MATRIX:
            case NEON:
            case CRT:
            case SCAN:
            case CYBER:
                return true;
            default:
                return false;
        }
    }

    /** 发光样式是否自带底板（浅色主题下就是那块「更适合这个主题的背景」） */
    public static boolean hasBackdrop(int style) {
        return glows(style) || norm(style) == FLIP;
    }

    // ------------------------------------------------------------------ 配色

    /** 点亮色 */
    public static int litColor(int style, boolean dark) {
        switch (norm(style)) {
            case NIXIE:
                return dark ? 0xFFFF8A3C : 0xFF8A3A10;
            case NUMITRON:
                return dark ? 0xFFFFA23A : 0xFFA9541A;
            case VFD:
                return dark ? 0xFF6BF0CE : 0xFF0E6B58;
            case HOLO:
                return dark ? 0xFF8FEEFF : 0xFF0B5A78;
            case DOT:
                return dark ? 0xFFFF4433 : 0xFFB02A18;
            case FLIP:
                return dark ? 0xFFF2F2F2 : 0xFF232323;
            case MATRIX:
                return dark ? 0xFF39FF88 : 0xFF0B5A2A;
            case NEON:
                return dark ? 0xFFFF4FD8 : 0xFFA8117F;
            case CRT:
                return dark ? 0xFF5CFF9A : 0xFF1E6B41;
            case SCAN:
                return dark ? 0xFF3BE0FF : 0xFF0C5468;
            case CYBER:
                return dark ? 0xFF2BE8FF : 0xFF0A4C6E;
            default:
                return 0;      // 现代：沿用主题色，由调用方决定
        }
    }

    /** 未点亮（暗段 / 管中隐约可见的其它电极） */
    public static int dimColor(int style, boolean dark) {
        switch (norm(style)) {
            case NIXIE:
                return dark ? 0x30FF8A3C : 0x248A3A10;
            case NUMITRON:
                return dark ? 0x22FFA23A : 0x20A9541A;
            case VFD:
                return dark ? 0x1C6BF0CE : 0x1A0E6B58;
            case HOLO:
                return dark ? 0x1E8FEEFF : 0x1A0B5A78;
            case DOT:
                return dark ? 0x18FF4433 : 0x18B02A18;
            case FLIP:
                return dark ? 0x14FFFFFF : 0x14000000;
            case MATRIX:
                return dark ? 0x2439FF88 : 0x220B5A2A;
            case NEON:
                return dark ? 0x28FF4FD8 : 0x24A8117F;
            case CRT:
                return dark ? 0x245CFF9A : 0x201E6B41;
            case SCAN:
                return dark ? 0x243BE0FF : 0x220C5468;
            case CYBER:
                return dark ? 0x242BE8FF : 0x220A4C6E;
            default:
                return 0;
        }
    }

    /**
     * 时钟自带底板的颜色。
     *
     * <p>这套底色的意义：发光样式在浅色主题下如果直接画在白底上，辉光根本看不出来。
     * 所以每种样式都配一块自己的底——辉光管配暖米、VFD 配淡青、灯丝管配浅橙、
     * 点阵配淡红、全息配淡蓝，翻页钟配浅灰卡片。
     */
    public static int backdropColor(int style, boolean dark) {
        switch (norm(style)) {
            case NIXIE:
                return dark ? 0xFF140C07 : 0xFFF4E7D6;
            case NUMITRON:
                return dark ? 0xFF100A06 : 0xFFF8ECDC;
            case VFD:
                return dark ? 0xFF06120F : 0xFFE4F3EF;
            case HOLO:
                return dark ? 0xFF05141C : 0xFFE9F5FB;
            case DOT:
                return dark ? 0xFF0B0706 : 0xFFF9ECE8;
            case FLIP:
                return dark ? 0xFF1C1C1C : 0xFFECECEC;
            case MATRIX:
                return dark ? 0xFF03130A : 0xFFE2F5E9;
            case NEON:
                return dark ? 0xFF10040F : 0xFFF6E6F3;
            case CRT:
                return dark ? 0xFF04120B : 0xFFE6F3EA;
            case SCAN:
                return dark ? 0xFF050F14 : 0xFFE7F1F6;
            case CYBER:
                return dark ? 0xFF050D18 : 0xFFE4EEF7;
            default:
                return 0;
        }
    }

    /** 翻页钟 / 灯丝管的卡片底色（每个字符一块） */
    public static int cardColor(int style, boolean dark) {
        switch (norm(style)) {
            case FLIP:
                return dark ? 0xFF2A2A2A : 0xFFF6F6F6;
            case NUMITRON:
                return dark ? 0xFF1A110B : 0xFFF1E2CE;
            case DOT:
                return dark ? 0xFF120C0A : 0xFFF3E2DC;
            default:
                return 0;
        }
    }

    /** 玻璃/面板的描边色 */
    public static int frameColor(int style, boolean dark) {
        switch (norm(style)) {
            case NIXIE:
                return dark ? 0xFF3A2416 : 0xFFE0CBAE;
            case NUMITRON:
                return dark ? 0xFF33210F : 0xFFE4D2B8;
            case VFD:
                return dark ? 0xFF123A33 : 0xFFBFE0D6;
            case HOLO:
                return dark ? 0xFF12414F : 0xFFBEDCE8;
            case DOT:
                return dark ? 0xFF3A1A14 : 0xFFE7CFC7;
            case FLIP:
                return dark ? 0xFF0E0E0E : 0xFFCFCFCF;
            case MATRIX:
                return dark ? 0xFF0E3A22 : 0xFFBFDCC8;
            case NEON:
                return dark ? 0xFF3A0E33 : 0xFFE3C6DC;
            case CRT:
                return dark ? 0xFF0F3A24 : 0xFFC2DCCB;
            case SCAN:
                return dark ? 0xFF123A4A : 0xFFBCD8E2;
            case CYBER:
                return dark ? 0xFF123A52 : 0xFFBED6E6;
            default:
                return 0;
        }
    }

    // ------------------------------------------------------------------ 尺寸比例

    /**
     * 单个字符的步进宽度（相对字高）。
     *
     * <p>七段和点阵都不是等宽字形，必须给每个字符一个明确的步进，
     * 否则秒数变化时整块会左右抖——和原来用 monoWidth 是一个道理。
     */
    public static float advance(char ch, int style) {
        switch (norm(style)) {
            case NUMITRON:
            case VFD:
                return ch == ':' ? 0.34f : (ch == ' ' ? 0.30f : 0.66f);
            case DOT:
                return ch == ':' ? 0.30f : (ch == ' ' ? 0.28f : 0.62f);
            case NIXIE:
                return ch == ':' ? 0.36f : (ch == ' ' ? 0.34f : 0.70f);
            case HOLO:
                return ch == ':' ? 0.32f : (ch == ' ' ? 0.30f : 0.64f);
            case FLIP:
                return ch == ':' ? 0.34f : (ch == ' ' ? 0.32f : 0.74f);
            case MATRIX:
                return ch == ':' ? 0.34f : (ch == ' ' ? 0.32f : 0.66f);
            case NEON:
                return ch == ':' ? 0.34f : (ch == ' ' ? 0.32f : 0.70f);
            case CRT:
                return ch == ':' ? 0.34f : (ch == ' ' ? 0.32f : 0.64f);
            case SCAN:
                return ch == ':' ? 0.34f : (ch == ' ' ? 0.32f : 0.66f);
            case CYBER:
                return ch == ':' ? 0.34f : (ch == ' ' ? 0.32f : 0.68f);
            default:
                return ch == ':' ? 0.34f : 0.60f;
        }
    }

    /** 字符实际占宽（相对步进），用于留缝 */
    public static float inkRatio(char ch, int style) {
        if (ch == ':') {
            return 0.42f;
        }
        switch (norm(style)) {
            case DOT:
                return 0.84f;
            case FLIP:
                return 0.90f;
            case MATRIX:
                return 0.82f;
            case NEON:
                return 0.86f;
            case CRT:
                return 0.80f;
            case SCAN:
                return 0.82f;
            case CYBER:
                return 0.84f;
            default:
                return 0.80f;
        }
    }

    /** 底板在整串左右各留的空白（相对字高） */
    public static final float PAD_RATIO = 0.20f;

    /** 整串的步进宽度之和（相对字高） */
    public static float advanceSum(String text, int style) {
        if (text == null) {
            return 0f;
        }
        float sum = 0f;
        for (int i = 0; i < text.length(); i++) {
            sum += advance(text.charAt(i), style);
        }
        return sum;
    }

    /**
     * 反推字高：让整块（含右侧预留给「上午/下午」的 {@code reservedW}）刚好放进 w×h。
     *
     * <p>放回这里而不是留在 Canvas 层，是因为「放不下就返回 0、调用方退回现代样式」
     * 这条分支在真机上很难遇到（只有把时钟缩到极小才触发），必须能被穷举测到。
     *
     * @return 字高；框太小放不下时返回 0
     */
    public static float fitUnit(String text, int style, float w, float h, float reservedW) {
        float sum = advanceSum(text, style);
        if (!(sum > 0f) || w <= 4f || h <= 4f) {
            return 0f;
        }
        float maxUnitH = h * (kind(style) == KIND_GLYPH ? 0.76f : 0.88f);
        // 底板在整串左右各留 PAD_RATIO，所以占位按 (步进和 + 两侧留白) 算，
        // 否则窄屏上会出现「数字在框里、底板捅出屏幕」这种差几像素的溢出
        float need = sum + PAD_RATIO * 2f;
        float unit = Math.min(maxUnitH, Math.max(0f, w * 0.98f - reservedW) / need);
        return unit > 4f ? unit : 0f;
    }

    /** 发光强度滑杆（0~100）换算成绘制用的倍率 */
    public static float glowScale(int level) {
        int lv = level < 0 ? 0 : (level > 100 ? 100 : level);
        return lv / 100f * 1.5f;
    }

    // ------------------------------------------------------------------ 时间串

    /**
     * 拼出要显示的字符序列。
     *
     * <p>字符只能是 {@code 0-9}、{@code :}、空格。12 小时制下不足两位时补空格
     * （七段/点阵里就是「这一位不亮」，比补 0 更像真管子）。
     */
    public static String text(int hour, int minute, int second, boolean use24h, boolean showSeconds) {
        int h = hour;
        if (!use24h) {
            h = hour % 12;
            if (h == 0) {
                h = 12;
            }
        }
        String hh = use24h ? pad2(h) : pad2LeadSpace(h);
        StringBuilder sb = new StringBuilder();
        sb.append(hh).append(':').append(pad2(minute));
        if (showSeconds) {
            sb.append(':').append(pad2(second));
        }
        return sb.toString();
    }

    private static String pad2(int v) {
        String s = String.valueOf(v);
        return s.length() >= 2 ? s : ("0" + s);
    }

    private static String pad2LeadSpace(int v) {
        String s = String.valueOf(v);
        return s.length() >= 2 ? s : (" " + s);
    }

    /** 12 小时制下的上午/下午标记；24 小时制返回空串 */
    public static String ampm(int hour, boolean use24h) {
        if (use24h) {
            return "";
        }
        return hour < 12 ? "上午" : "下午";
    }

    // ------------------------------------------------------------------ 七段字模

    // 位序：0=a(上) 1=b(右上) 2=c(右下) 3=d(下) 4=e(左下) 5=f(左上) 6=g(中)
    public static final int SEG_A = 1;
    public static final int SEG_B = 2;
    public static final int SEG_C = 4;
    public static final int SEG_D = 8;
    public static final int SEG_E = 16;
    public static final int SEG_F = 32;
    public static final int SEG_G = 64;

    /** 七段掩码；不支持的字符返回 0（全部熄灭），冒号/空格返回 -1 由调用方特殊处理 */
    public static int segMask(char ch) {
        switch (ch) {
            case '0':
                return SEG_A | SEG_B | SEG_C | SEG_D | SEG_E | SEG_F;
            case '1':
                return SEG_B | SEG_C;
            case '2':
                return SEG_A | SEG_B | SEG_G | SEG_E | SEG_D;
            case '3':
                return SEG_A | SEG_B | SEG_G | SEG_C | SEG_D;
            case '4':
                return SEG_F | SEG_G | SEG_B | SEG_C;
            case '5':
                return SEG_A | SEG_F | SEG_G | SEG_C | SEG_D;
            case '6':
                return SEG_A | SEG_F | SEG_G | SEG_E | SEG_C | SEG_D;
            case '7':
                return SEG_A | SEG_B | SEG_C;
            case '8':
                return SEG_A | SEG_B | SEG_C | SEG_D | SEG_E | SEG_F | SEG_G;
            case '9':
                return SEG_A | SEG_B | SEG_C | SEG_D | SEG_F | SEG_G;
            default:
                return 0;
        }
    }

    // ------------------------------------------------------------------ 点阵字模

    /** 点阵宽高（列 × 行） */
    public static final int DOT_COLS = 5;
    public static final int DOT_ROWS = 7;

    /**
     * 5×7 点阵字模：每行一个 5 位整数，最高位是最左边那一列。
     * 数组长度恒为 {@link #DOT_ROWS}，取不到返回 null。
     */
    public static int[] dotRows(char ch) {
        switch (ch) {
            case '0':
                return new int[]{14, 17, 19, 21, 25, 17, 14};
            case '1':
                return new int[]{4, 12, 4, 4, 4, 4, 14};
            case '2':
                return new int[]{14, 17, 1, 2, 4, 8, 31};
            case '3':
                return new int[]{31, 2, 4, 2, 1, 17, 14};
            case '4':
                return new int[]{2, 6, 10, 18, 31, 2, 2};
            case '5':
                return new int[]{31, 16, 30, 1, 1, 17, 14};
            case '6':
                return new int[]{6, 8, 16, 30, 17, 17, 14};
            case '7':
                return new int[]{31, 1, 2, 4, 8, 8, 8};
            case '8':
                return new int[]{14, 17, 17, 14, 17, 17, 14};
            case '9':
                return new int[]{14, 17, 17, 15, 1, 2, 12};
            case ':':
                return new int[]{0, 4, 4, 0, 4, 4, 0};
            case ' ':
                return new int[]{0, 0, 0, 0, 0, 0, 0};
            default:
                return null;
        }
    }

    /** 第 row 行第 col 列是否点亮（col 0 是最左列） */
    public static boolean dotOn(int[] rows, int row, int col) {
        if (rows == null || row < 0 || row >= rows.length || col < 0 || col >= DOT_COLS) {
            return false;
        }
        return (rows[row] & (1 << (DOT_COLS - 1 - col))) != 0;
    }
}
