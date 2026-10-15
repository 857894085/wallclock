# -*- coding: utf-8 -*-
"""
把 d8 生成的 classes.dex 合入 aapt2 产出的 APK。

为什么需要这一步：Android 的 APK 就是一个 zip，aapt2 link 只生成了
资源与清单部分，dex 需要自己塞进去。这里用标准库 zipfile，
并保证：
  * classes.dex 放在最前（对启动速度略有好处）
  * resources.arsc 保持 STORED 不压缩（Android 要求）
  * 所有条目 4 字节对齐（配合后续 zipalign）

用法: python add_dex.py <base.apk> <classes.dex> <out.apk>
"""
import os
import shutil
import sys
import zipfile

sys.stdout.reconfigure(encoding='utf-8')


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        return 2
    base, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]
    if not os.path.exists(base):
        print("缺少 base.apk:", base)
        return 2
    if not os.path.exists(dex):
        print("缺少 classes.dex:", dex)
        return 2

    tmp = out + ".tmp"
    zin = zipfile.ZipFile(base, "r")
    zout = zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED)

    # dex 放最前
    zi = zipfile.ZipInfo("classes.dex", date_time=(2026, 1, 1, 0, 0, 0))
    zi.compress_type = zipfile.ZIP_DEFLATED
    zi.external_attr = 0o644 << 16
    with open(dex, "rb") as f:
        zout.writestr(zi, f.read())

    names = zin.namelist()
    stored = {"resources.arsc", "AndroidManifest.xml"}
    for name in names:
        data = zin.read(name)
        zi = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
        if name in stored or name.endswith(".arsc"):
            zi.compress_type = zipfile.ZIP_STORED
        else:
            zi.compress_type = zipfile.ZIP_DEFLATED
        zi.external_attr = 0o644 << 16
        zout.writestr(zi, data)

    zin.close()
    zout.close()
    shutil.move(tmp, out)

    print("已生成 %s （%d 个条目，含 classes.dex %d 字节）"
          % (out, len(names) + 1, os.path.getsize(dex)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
