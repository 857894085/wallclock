# -*- coding: utf-8 -*-
"""探查 cnlunar / lunardate 能力，用于生成农历+节气数据表"""
import datetime, io, sys

print("=== lunardate ===")
try:
    from lunardate import LunarDate
    d = LunarDate.fromSolarDate(2026, 9, 15)
    print("2026-09-15 ->", d.year, d.month, d.day, "leap=", d.isLeapMonth)
    for s in [(2024,2,10),(2025,1,29),(2026,2,17),(2026,9,25),(2023,1,22)]:
        ld = LunarDate.fromSolarDate(*s)
        print(s, "->", ld.year, ld.month, ld.day, "leap=", ld.isLeapMonth)
except Exception as e:
    print("lunardate FAIL", e)

print("=== cnlunar ===")
try:
    import cnlunar
    a = cnlunar.Lunar(datetime.datetime(2026, 9, 15, 12, 0), godType='8char')
    print("lunarYear", a.lunarYear, "lunarMonth", a.lunarMonth, "lunarDay", a.lunarDay, "isLunarLeapMonth", a.isLunarLeapMonth)
    print("lunarMonthCn", a.lunarMonthCn, "lunarDayCn", a.lunarDayCn)
    print("todaySolarTerms", getattr(a, 'todaySolarTerms', None))
    print("thisYearSolarTermsDic", getattr(a, 'thisYearSolarTermsDic', None))
    print("year8Char", getattr(a, 'year8Char', None), "zodiac", getattr(a, 'zodiac', None))
    print("festivals keys", [k for k in a.__dict__.keys() if 'estival' in k or 'jie' in k.lower()])
except Exception as e:
    print("cnlunar FAIL", type(e).__name__, e)

print("=== sTermInfo formula ===")
sTermInfo = [0,21208,42467,63836,85337,107014,128867,150921,173149,195551,218072,
             240693,263343,285989,308563,331033,353350,375494,397447,419210,
             440795,462224,483532,504758]
names = ["小寒","大寒","立春","雨水","惊蛰","春分","清明","谷雨","立夏","小满","芒种","夏至",
         "小暑","大暑","立秋","处暑","白露","秋分","寒露","霜降","立冬","小雪","大雪","冬至"]
def term_day(y, n):
    ms = 31556925974.7 * (y - 1900) + sTermInfo[n] * 60000.0
    base = datetime.datetime(1900, 1, 6, 2, 5)  # UTC
    return (base + datetime.timedelta(milliseconds=ms)).date()
for n in range(24):
    print(names[n], term_day(2026, n))
print("--- 2025 ---")
for n in range(24):
    print(names[n], term_day(2025, n))
