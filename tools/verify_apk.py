# -*- coding: utf-8 -*-
"""
独立校验产出的 APK（不依赖 aapt2 的输出编码，直接用二进制检查）。

检查项：
  1. classes.dex 存在、dex 魔数正确
  2. resources.arsc 存在且为 STORED（Android 要求）
  3. 未压缩条目 4 字节对齐（zipalign 结果）
  4. 中文应用名在 resources.arsc 中以 UTF-8 正确存放
  5. 各密度图标与自适应图标资源齐全
  6. 清单里声明的权限/组件存在
"""
import os
import sys
import zipfile

sys.stdout.reconfigure(encoding='utf-8')

apk = sys.argv[1] if len(sys.argv) > 1 else os.path.join("output", "WallClock.apk")
if not os.path.exists(apk):
    print("找不到 APK:", apk)
    sys.exit(2)

z = zipfile.ZipFile(apk)
names = z.namelist()
ok = True


def check(label, cond, extra=""):
    global ok
    print(("  [OK]   " if cond else "  [FAIL] ") + label + ("  " + extra if extra else ""))
    if not cond:
        ok = False


print("APK:", apk, "|", round(os.path.getsize(apk) / 1024, 1), "KB |", len(names), "entries")
print("")
print("1) dex")
check("classes.dex 存在", "classes.dex" in names)
if "classes.dex" in names:
    data = z.read("classes.dex")
    check("dex 魔数正确", data[:4] == b"dex\n", repr(data[:8]))
    check("dex 版本 035/037/038",
          data[4:7] in (b"035", b"037", b"038"), repr(data[4:7]))
    print("         dex 大小: %.1f KB" % (len(data) / 1024))

print("")
print("2) resources.arsc")
check("resources.arsc 存在", "resources.arsc" in names)
if "resources.arsc" in names:
    zi = z.getinfo("resources.arsc")
    check("resources.arsc 为 STORED 不压缩",
          zi.compress_type == zipfile.ZIP_STORED,
          "compress_type=%d" % zi.compress_type)

print("")
print("3) 对齐")
bad = []
for zi in z.infolist():
    if zi.compress_type == zipfile.ZIP_STORED:
        header_off = zi.header_offset
        with open(apk, "rb") as f:
            f.seek(header_off + 26)
            nlen = int.from_bytes(f.read(2), "little")
            elen = int.from_bytes(f.read(2), "little")
        data_off = header_off + 30 + nlen + elen
        if data_off % 4 != 0:
            bad.append((zi.filename, data_off % 4))
check("未压缩条目 4 字节对齐", not bad, str(bad[:5]) if bad else "")

print("")
print("4) 中文资源编码")
arsc = z.read("resources.arsc") if "resources.arsc" in names else b""
label = "挂钟日历"
check("应用名 UTF-8 正确写入", label.encode("utf-8") in arsc, repr(label.encode("utf-8")))
check("设置页标题存在", "挂钟设置".encode("utf-8") in arsc)

print("")
print("5) 图标")
for d in ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"):
    for f in ("ic_launcher.png", "ic_launcher_fg.png"):
        # aapt2 会给目录补上最低版本限定符，例如 res/mipmap-mdpi-v4/...
        cands = ["res/mipmap-%s/%s" % (d, f), "res/mipmap-%s-v4/%s" % (d, f)]
        p = next((c for c in cands if c in names), cands[0])
        check(p, p in names and z.getinfo(p).file_size > 0)
p = next((c for c in ("res/mipmap-anydpi-v26/ic_launcher.xml",
                      "res/mipmap-anydpi-v26-v26/ic_launcher.xml") if c in names),
         "res/mipmap-anydpi-v26/ic_launcher.xml")
check(p, p in names)

print("")
print("6) 清单")
man = z.read("AndroidManifest.xml") if "AndroidManifest.xml" in names else b""
for s in ("MainActivity", "SettingsActivity", "BootReceiver",
          "ACCESS_COARSE_LOCATION", "INTERNET", "RECEIVE_BOOT_COMPLETED"):
    hit = s.encode("utf-16-le") in man or s.encode("utf-8") in man
    check("清单包含 " + s, hit)

print("")
print("结论:", "全部通过" if ok else "存在问题")
sys.exit(0 if ok else 1)
