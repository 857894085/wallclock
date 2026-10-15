# -*- coding: utf-8 -*-
"""
生成 Android 用的农历 / 节气数据表（v3，定稿）。

数据源裁决（依据外部权威资料）：
  * 1933 年确有闰五月（多个农历闰月表一致）—— cnlunar 对，lunardate 错
  * 1978-09-02 = 农历七月三十（多个黄历站一致）—— cnlunar 对，lunardate 错
  * 1900-01-31 = 农历正月初一（经典 lunarInfo[1900]=0x04bd8 的表锚点）—— lunardate 对
  因此：以 cnlunar 为主源，1900 年单独用 lunardate 覆盖。

校验：
  1. 1900 年编码必须等于经典值 0x04bd8
  2. 1900..2031 闰月必须与外部权威闰月表一致
  3. 每个农历年天数 = 下一年正月初一 - 本年正月初一
  4. 节气：芒种 2026=6/5 2027=6/6 2028=6/5 2029=6/5 2030=6/5 2031=6/6

输出：
  * src/com/vincent/wallclock/gen/LunarTables.java
  * tools/reference_lunar.csv   （逐日基准，供 Java 端回归测试）
"""
import datetime, os, sys, collections

sys.stdout.reconfigure(encoding='utf-8')

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
GEN_DIR = os.path.join(ROOT, "src", "com", "vincent", "wallclock", "gen")
os.makedirs(GEN_DIR, exist_ok=True)

YEAR_MIN, YEAR_MAX = 1900, 2099

TERM_NAMES = ["小寒", "大寒", "立春", "雨水", "惊蛰", "春分", "清明", "谷雨",
              "立夏", "小满", "芒种", "夏至", "小暑", "大暑", "立秋", "处暑",
              "白露", "秋分", "寒露", "霜降", "立冬", "小雪", "大雪", "冬至"]

# 外部权威闰月表（1900..2031，0 表示无闰月）
AUTH_LEAP = {
    1900: 8, 1903: 5, 1906: 4, 1909: 2, 1911: 6, 1914: 5, 1917: 2, 1919: 7,
    1922: 5, 1925: 4, 1928: 2, 1930: 6, 1933: 5, 1936: 3, 1938: 7, 1941: 6,
    1944: 4, 1947: 2, 1949: 7, 1952: 5, 1955: 3, 1957: 8, 1960: 6, 1963: 4,
    1966: 3, 1968: 7, 1971: 5, 1974: 4, 1976: 8, 1979: 6, 1982: 4, 1984: 10,
    1987: 6, 1990: 5, 1993: 3, 1995: 8, 1998: 5, 2001: 4, 2004: 2, 2006: 7,
    2009: 5, 2012: 4, 2014: 9, 2017: 6, 2020: 4, 2023: 2, 2025: 6, 2028: 5,
    2031: 3,
}

from lunardate import LunarDate
import cnlunar

# cnlunar 只支持 1900..2099，且 1900 年内存在定朔偏差，故：
#   1900 年      -> lunardate 反向 API（与经典 0x04bd8 锚点一致）
#   1901..2098   -> cnlunar 逐日扫描
#   2099 年      -> lunardate 反向 API（cnlunar 无法提供次年锚点）
SCAN_START = datetime.date(1901, 1, 1)
SCAN_END = datetime.date(2099, 12, 31)

# ---------------------------------------------------------------- 逐日扫描
first_days = collections.defaultdict(list)      # lunar_year -> [(solar_date, month, is_leap)]
d = SCAN_START
while d <= SCAN_END:
    b = cnlunar.Lunar(datetime.datetime(d.year, d.month, d.day, 12, 0), godType='8char')
    if b.lunarDay == 1:
        first_days[b.lunarYear].append((d, b.lunarMonth, bool(b.isLunarLeapMonth)))
    d += datetime.timedelta(days=1)

# ---------------------------------------------------------------- 组装年表
def month_lengths_by_scan(y):
    """从扫描结果推导农历 y 年的月序；不完整返回 None"""
    if y not in first_days or (y + 1) not in first_days:
        return None
    cur = sorted(first_days[y])
    starts = cur + [sorted(first_days[y + 1])[0]]
    out = []
    for i in range(len(cur)):
        days = (starts[i + 1][0] - starts[i][0]).days
        if days not in (29, 30):
            return None
        out.append((cur[i][1], cur[i][2], days))
    normal = [m for (m, lp, _) in out if not lp]
    leap = [m for (m, lp, _) in out if lp]
    if normal != list(range(1, 13)) or len(leap) > 1:
        return None
    return out

def _solar_of(y, m, day, leap=False):
    try:
        return LunarDate(y, m, day, leap).to_solar_date()
    except Exception:
        return None

def month_lengths_by_reverse(y):
    """用 lunardate 反向 API 推导农历 y 年的月序（1900 与 2099 年使用）"""
    out = []
    for m in range(1, 13):
        if _solar_of(y, m, 1, False) is None:
            return None
        out.append((m, False, 30 if _solar_of(y, m, 30, False) else 29))
        if _solar_of(y, m, 1, True) is not None:
            out.append((m, True, 30 if _solar_of(y, m, 30, True) else 29))
    return out

lunar_info = {}
for y in range(YEAR_MIN, YEAR_MAX + 1):
    if y == 1900 or y == 2099:
        ms = month_lengths_by_reverse(y)
        src = "lunardate反向"
    else:
        ms = month_lengths_by_scan(y)
        src = "cnlunar扫描"
    if ms is None:
        raise RuntimeError("无法解析农历年 %d，需人工检查" % y)
    info = 0
    idx = 0
    for (m, lp, nd) in ms:
        if not lp:
            idx += 1
            if nd == 30:
                info |= (0x10000 >> idx)
        else:
            info |= m & 0x0F
            if nd == 30:
                info |= 0x10000
    lunar_info[y] = info
    if src == "cnlunar扫描" and y < 2098:
        a = sorted(first_days[y])[0][0]
        b = sorted(first_days[y + 1])[0][0]
        got = sum(nd for (_, _, nd) in ms)
        if (b - a).days != got:
            raise RuntimeError("年长不符 %d: %d vs %d" % (y, (b - a).days, got))

print("1900 lunarInfo = 0x%05x (classic 0x04bd8) %s"
      % (lunar_info[1900], "OK" if lunar_info[1900] == 0x04bd8 else "MISMATCH"))
assert lunar_info[1900] == 0x04bd8

bad = []
for y in range(1900, 2032):
    exp = AUTH_LEAP.get(y, 0)
    got = lunar_info[y] & 0xF
    if exp != got:
        bad.append((y, exp, got))
print("闰月与权威表比对 1900..2031：不一致", len(bad), bad)
assert not bad, bad

print("近 12 年闰月核对:", [(y, lunar_info[y] & 0xF) for y in range(2020, 2032)])

# ---------------------------------------------------------------- 节气表
term_days = []
for y in range(YEAR_MIN, YEAR_MAX + 1):
    a = cnlunar.Lunar(datetime.datetime(y, 7, 1, 12, 0), godType='8char')
    dic = a.thisYearSolarTermsDic
    row = [dic[n][1] for n in TERM_NAMES]
    assert len(row) == 24 and all(1 <= v <= 31 for v in row), (y, row)
    term_days.append(row)

expected_mangzhong = {2026: 5, 2027: 6, 2028: 5, 2029: 5, 2030: 5, 2031: 6}
for y, exp in expected_mangzhong.items():
    got = term_days[y - YEAR_MIN][10]
    print("芒种 %d -> %d (expect %d) %s" % (y, got, exp, "OK" if got == exp else "MISMATCH"))
    assert got == exp
print("2026 节气:", " ".join("%s%d" % (TERM_NAMES[i], term_days[2026 - YEAR_MIN][i]) for i in range(24)))

# ---------------------------------------------------------------- Java 输出
out = []
out.append("package com.vincent.wallclock.gen;")
out.append("")
out.append("/**")
out.append(" * 本文件由 tools/gen_tables.py 自动生成，请勿手工修改。")
out.append(" *")
out.append(" * LUNAR_INFO: 1900-2099 年农历编排（经典编码）")
out.append(" *   bit0-3       闰月月份，0 表示无闰月")
out.append(" *   bit16        闰月天数，1 表示 30 天")
out.append(" *   bit15..bit4  正月..腊月天数，1 表示 30 天")
out.append(" * 数据源：cnlunar（1900 年取自 lunardate，与经典 lunarInfo[1900]=0x04bd8 锚点一致），")
out.append(" * 已用权威闰月表逐月比对 1900-2031 年，并用 1978-09-02=七月三十 等外部黄历数据裁决。")
out.append(" *")
out.append(" * TERM_DAYS: [(year-1900)*24 + n] = 第 n 个节气所在的公历日，n 顺序为")
out.append(" *   小寒 大寒 立春 雨水 惊蛰 春分 清明 谷雨 立夏 小满 芒种 夏至")
out.append(" *   小暑 大暑 立秋 处暑 白露 秋分 寒露 霜降 立冬 小雪 大雪 冬至")
out.append(" * 数据源：cnlunar，已用 2026-2031 芒种交节日校验。")
out.append(" */")
out.append("public final class LunarTables {")
out.append("")
out.append("    public static final int YEAR_MIN = %d;" % YEAR_MIN)
out.append("    public static final int YEAR_MAX = %d;" % YEAR_MAX)
out.append("")
out.append("    /** 节气名，下标即节气序号（小寒 = 0） */")
out.append("    public static final String[] TERM_NAMES = {")
out.append("        " + ", ".join('"%s"' % n for n in TERM_NAMES))
out.append("    };")
out.append("")
out.append("    /** 农历编排表，下标 [year - YEAR_MIN] */")
out.append("    public static final int[] LUNAR_INFO = {")
for y in range(YEAR_MIN, YEAR_MAX + 1):
    out.append("        0x%05x,%s" % (lunar_info[y], "   // %d%s" % (y, "  闰%d月" % (lunar_info[y] & 0xF) if (lunar_info[y] & 0xF) else "")))
out.append("    };")
out.append("")
out.append("    /** 节气日期表，下标 [(year - YEAR_MIN) * 24 + term] */")
out.append("    public static final byte[] TERM_DAYS = {")
for yi, row in enumerate(term_days):
    out.append("        " + ", ".join("%d" % v for v in row) + ",   // %d" % (YEAR_MIN + yi))
out.append("    };")
out.append("")
out.append("    private LunarTables() { }")
out.append("}")
out.append("")

path = os.path.join(GEN_DIR, "LunarTables.java")
with open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write("\n".join(out))
print("wrote", path, os.path.getsize(path), "bytes")

# ---------------------------------------------------------------- 基准 CSV
csv_path = os.path.join(HERE, "reference_lunar.csv")
start = datetime.date(1900, 2, 1)
end = datetime.date(2099, 12, 31)
days = (end - start).days + 1
d = start
with open(csv_path, "w", encoding="utf-8", newline="\n") as f:
    f.write("#solar_y,solar_m,solar_d,lunar_y,lunar_m,lunar_d,is_leap\n")
    while d <= end:
        if d.year == 1900 or d.year == 2099:
            a = LunarDate.from_solar_date(d.year, d.month, d.day)
            ly, lm, lday, lp = a.year, a.month, a.day, bool(a.isLeapMonth)
        else:
            b = cnlunar.Lunar(datetime.datetime(d.year, d.month, d.day, 12, 0), godType='8char')
            ly, lm, lday, lp = b.lunarYear, b.lunarMonth, b.lunarDay, bool(b.isLunarLeapMonth)
        f.write("%d,%d,%d,%d,%d,%d,%d\n" % (d.year, d.month, d.day, ly, lm, lday, 1 if lp else 0))
        d += datetime.timedelta(days=1)
print("wrote", csv_path, days, "days")
