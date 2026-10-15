# -*- coding: utf-8 -*-
import datetime, sys, collections
sys.stdout.reconfigure(encoding='utf-8')
from lunardate import LunarDate
import cnlunar

d = datetime.date(1900, 2, 1)
end = datetime.date(2099, 12, 31)
rows = []
while d <= end:
    a = LunarDate.from_solar_date(d.year, d.month, d.day)
    try:
        b = cnlunar.Lunar(datetime.datetime(d.year, d.month, d.day, 12, 0), godType='8char')
        lb = (b.lunarYear, b.lunarMonth, b.lunarDay, bool(b.isLunarLeapMonth))
    except Exception:
        lb = None
    la = (a.year, a.month, a.day, bool(a.isLeapMonth))
    if lb is not None and la != lb:
        rows.append((d, la, lb))
    d += datetime.timedelta(days=1)

print("total mismatch days:", len(rows))
years = collections.Counter(r[0].year for r in rows)
print("mismatch years:", sorted(years.items()))

# 按年份看是否属于整段偏移（闰月差异）还是单日边界差
for y in sorted(set(r[0].year for r in rows)):
    grp = [r for r in rows if r[0].year == y]
    print("---", y, "days:", len(grp), "first:", grp[0][0], grp[0][1], grp[0][2], "last:", grp[-1][0], grp[-1][1], grp[-1][2])
