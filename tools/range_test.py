# -*- coding: utf-8 -*-
import datetime, sys
sys.stdout.reconfigure(encoding='utf-8')
from lunardate import LunarDate
for d in [(2100, 12, 31), (2101, 1, 1), (2101, 6, 1), (2101, 12, 31), (2102, 6, 1), (1900, 1, 30), (1900, 1, 31), (1900, 1, 1)]:
    try:
        r = LunarDate.from_solar_date(*d)
        print(d, "->", r.year, r.month, r.day, r.isLeapMonth)
    except Exception as e:
        print(d, "FAIL", type(e).__name__, e)
print("--- lunar->solar range test ---")
for d in [(2100, 12, 29), (2100, 1, 1), (1899, 12, 1)]:
    pass
import cnlunar
for y in (2100, 2101, 1900):
    try:
        a = cnlunar.Lunar(datetime.datetime(y, 7, 1, 12, 0), godType='8char')
        print("cnlunar", y, "ok, dic len", len(a.thisYearSolarTermsDic))
    except Exception as e:
        print("cnlunar", y, "FAIL", e)
