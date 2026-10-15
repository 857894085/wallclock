# -*- coding: utf-8 -*-
"""校验产物 dex 里确实包含预期的类和内置证书。

为什么单独做这一步：verify_apk.py 查的是 APK 结构（对齐 / 压缩 / 组件），
但「某个类有没有真的被打进 dex」它管不到。本项目的 TlsAnchors 里藏着三张
Base64 证书（约 5.5 KB 字符串常量），是最容易被 d8 悄悄丢掉、又最难在真机上
反查的东西——一旦丢了，Android 4.4 上表现只是「天气永远加载不出来」。

用法：
    python tools/check_dex_classes.py [apk路径]
"""
from __future__ import print_function

import io
import os
import sys
import zipfile

try:
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
except Exception:
    pass

HERE = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(HERE)

# 必须出现在 dex 里的类（用 dex 里的描述符写法，避免被字符串池折叠干扰）
REQUIRED = [
    "Lcom/vincent/wallclock/MainActivity;",
    "Lcom/vincent/wallclock/SettingsActivity;",
    "Lcom/vincent/wallclock/BootReceiver;",
    "Lcom/vincent/wallclock/Watchdog;",
    "Lcom/vincent/wallclock/WatchdogReceiver;",
    "Lcom/vincent/wallclock/AppState;",
    "Lcom/vincent/wallclock/DashboardView;",
    "Lcom/vincent/wallclock/AlmanacPage;",
    "Lcom/vincent/wallclock/WeatherDetailPage;",
    "Lcom/vincent/wallclock/MonthPage;",
    "Lcom/vincent/wallclock/Almanac;",
    "Lcom/vincent/wallclock/LifeIndex;",
    "Lcom/vincent/wallclock/TextDraw;",
    "Lcom/vincent/wallclock/gen/Type;",
    "Lcom/vincent/wallclock/ClockStyle;",
    "Lcom/vincent/wallclock/gen/ClockSpec;",
    "Lcom/vincent/wallclock/TlsCompat;",
    "Lcom/vincent/wallclock/WeatherData;",
    "Lcom/vincent/wallclock/Palette;",
    "Lcom/vincent/wallclock/gen/TlsAnchors;",
    "Lcom/vincent/wallclock/gen/Layout;",
    "Lcom/vincent/wallclock/gen/Cards;",
    "Lcom/vincent/wallclock/gen/LightRule;",
    "Lcom/vincent/wallclock/gen/LunarTables;",
]

# 内置证书必须完整存在。用每张证书开头的一段 base64 做指纹，
# 只要有一段缺失就说明常量被截断或丢掉了。
ANCHOR_FINGERPRINTS = [
    "MIIE2jCCAsKgAwIBAgIQTr0klH4k05SALYSlL9WzGTANBgkqhkiG9w0BAQsFADAu",
    "MIIF9DCCA9ygAwIBAgIRAPJLbRf52a18scn+p4eCaZ8wDQYJKoZIhvcNAQELBQAw",
    "MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw",
]


def main(argv):
    apk = argv[1] if len(argv) > 1 else os.path.join(PROJ, "output", "WallClock.apk")
    if not os.path.exists(apk):
        print("找不到 APK: %s" % apk)
        return 2

    z = zipfile.ZipFile(apk)
    names = [n for n in z.namelist() if n.startswith("classes") and n.endswith(".dex")]
    if not names:
        print("APK 里没有 dex")
        return 1

    blobs = [z.read(n) for n in names]
    total = sum(len(b) for b in blobs)
    print("APK: %s" % apk)
    print("dex 文件: %s（合计 %.1f KB）" % (", ".join(sorted(names)), total / 1024.0))
    print()

    failed = 0

    print("1) 必需类")
    for desc in REQUIRED:
        hit = any(desc.encode("utf-8") in b for b in blobs)
        print("  [%s] %s" % ("OK" if hit else "MISS", desc))
        if not hit:
            failed += 1

    print()
    print("2) 内置 CA 证书完整性")
    for i, fp in enumerate(ANCHOR_FINGERPRINTS):
        hit = any(fp.encode("ascii") in b for b in blobs)
        print("  [%s] 证书 #%d" % ("OK" if hit else "MISS", i))
        if not hit:
            failed += 1

    print()
    if failed == 0:
        print("结论: 全部命中")
        return 0
    print("结论: 有 %d 项缺失" % failed)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
