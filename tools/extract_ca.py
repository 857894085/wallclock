# -*- coding: utf-8 -*-
"""从 tools/out_chain.txt（证书链抓取结果）里抽出非叶证书，写成 tools/ca_chain.pem。

只跑一次的小工具；产物 ca_chain.pem 才是 gen_tls_anchors.py 的输入。

为什么必须去掉叶子证书：叶子（CN=open-meteo.com）有效期只有 90 天，固化进 APK
很快就会过期反而造成校验失败；根与中间证书有效期到 2028-2032，可以安全固化。
"""
from __future__ import print_function

import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "out_chain.txt")
DST = os.path.join(HERE, "ca_chain.pem")


def main():
    if not os.path.exists(SRC):
        sys.stderr.write("missing %s\n" % SRC)
        return 1
    text = io.open(SRC, "r", encoding="utf-8-sig").read()

    certs = re.findall(
        r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", text, re.S
    )
    subjects = re.findall(r"=====\s*\[\d+\]\s*([^\n]+)", text)
    if len(certs) < 2:
        sys.stderr.write("expected >=2 certs, got %d\n" % len(certs))
        return 1

    keep = certs[1:]                       # 丢掉叶子
    keep_subjects = subjects[1:] if len(subjects) == len(certs) else []

    out = []
    for i, pem in enumerate(keep):
        subj = keep_subjects[i].strip() if i < len(keep_subjects) else "cert%d" % i
        out.append("# %s" % subj)
        out.append(pem.strip())
        out.append("")
    io.open(DST, "w", encoding="utf-8", newline="\n").write("\n".join(out))

    print("wrote %s with %d certs" % (DST, len(keep)))
    for s in keep_subjects:
        print("  - " + s.strip())
    return 0


if __name__ == "__main__":
    sys.exit(main())
