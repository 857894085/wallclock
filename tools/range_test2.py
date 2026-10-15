# -*- coding: utf-8 -*-
import sys
sys.stdout.reconfigure(encoding='utf-8')
from lunardate import LunarDate
bad = []
ok = []
for y in range(1898, 2103):
    try:
        d = LunarDate(y, 1, 1, False).to_solar_date()
        LunarDate(y, 12, 30, False).to_solar_date()
        ok.append((y, str(d)))
    except Exception as e:
        bad.append((y, type(e).__name__))
print("OK range:", ok[0], "...", ok[-1], "count", len(ok))
print("BAD:", bad[:10], "...", bad[-5:] if bad else None)
