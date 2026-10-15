# -*- coding: utf-8 -*-
"""由 tools/ca_chain.pem 生成 src/com/vincent/wallclock/gen/TlsAnchors.java。

背景
----
Open-Meteo 走的是 Let's Encrypt 的**新层级**：

    open-meteo.com  <-  YR2  <-  Root YR  <-  ISRG Root X1

Android 4.4（API 19）的信任库停在 2014 年，里面没有 ISRG Root X1（它到
Android 7.1/API 25 才进系统信任库），也没有 Root YR / YR2。
所以旧机器即便把 TLS 1.2 打开，握手后仍会在**证书校验**这一步失败。
解决办法只能是把这几张根/中间证书随 APK 一起带上，运行时并入自定义 TrustManager。

只用标准库，且输出的是纯 Java 常量类，方便 Almanac/LunarCalendar 那套桌面单测体系复用。
"""
from __future__ import print_function

import base64
import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(HERE)
SRC = os.path.join(HERE, "ca_chain.pem")
DST = os.path.join(PROJ, "src", "com", "vincent", "wallclock", "gen", "TlsAnchors.java")

WRAP = 76


def parse_pem(text):
    """-> [(subject_comment, der_bytes)]"""
    out = []
    cur_subject = ""
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        if line.startswith("#"):
            cur_subject = line.lstrip("#").strip()
            continue
        if line.startswith("-----BEGIN"):
            buf = []
            continue
        if line.startswith("-----END"):
            out.append((cur_subject, base64.b64decode("".join(buf))))
            buf = []
            continue
        buf.append(line)
    return out


# ---------------------------------------------------------------- 极简 DER 解析
def der_walk(der, pos, end):
    """生成 (tag, start, len_start, len_end) 序列，只做一层 + 递归 contents。"""
    while pos < end:
        tag = der[pos]
        p = pos + 1
        ln = der[p]
        p += 1
        if ln & 0x80:
            n = ln & 0x7F
            ln = int.from_bytes(der[p:p + n], "big")
            p += n
        yield tag, pos, p, p + ln
        pos = p + ln


def cert_subject_cn(der):
    """从 Subject 里取 CN，纯粹用于生成注释做自查；取不到就退回整段 Subject 的 CN 列表。"""
    try:
        cns = []
        # Certificate ::= SEQUENCE { tbsCertificate, sigAlg, sigValue }
        for tag, s, le, e in der_walk(der, 0, len(der)):
            if tag == 0x30:
                for t2, s2, le2, e2 in der_walk(der, le, e):
                    if t2 == 0x30:  # tbsCertificate
                        seqs = list(der_walk(der, le2, e2))
                        for t3, s3, le3, e3 in seqs:
                            if t3 == 0x30:  # Name (issuer 或 subject)
                                cn = _name_cn(der, le3, e3)
                                if cn:
                                    cns.append(cn)
                        break
                break
        # 第一个 Name 是 issuer，第二个是 subject
        return cns[1] if len(cns) >= 2 else (cns[0] if cns else "")
    except Exception:
        return ""


def _name_cn(der, start, end):
    for tag, s, le, e in der_walk(der, start, end):
        if tag == 0x31:  # SET
            for t2, s2, le2, e2 in der_walk(der, le, e):
                if t2 == 0x30:  # AttributeTypeAndValue
                    vals = list(der_walk(der, le2, e2))
                    if len(vals) >= 2:
                        oid = der[vals[0][2]:vals[0][3]]
                        if oid == b"\x55\x04\x03":  # 2.5.4.3 commonName
                            return der[vals[1][2]:vals[1][3]].decode("utf-8", "replace")
    return ""


def main():
    if not os.path.exists(SRC):
        sys.stderr.write("missing %s\n" % SRC)
        return 1
    text = io.open(SRC, "r", encoding="utf-8").read()
    certs = parse_pem(text)
    if not certs:
        sys.stderr.write("no certificate found in %s\n" % SRC)
        return 1

    entries = []
    for i, (subj, der) in enumerate(certs):
        b64 = base64.b64encode(der).decode("ascii")
        cn = cert_subject_cn(der) or subj
        entries.append((cn, subj, der, b64))
        print("  [%d] %-22s %5d bytes  %s" % (i, cn, len(der), subj))

    lines = []
    A = lines.append
    A("package com.vincent.wallclock.gen;")
    A("")
    A("/**")
    A(" * 随包内置的 CA 信任锚（自动生成，请勿手改）。")
    A(" *")
    A(" * <p>生成方式：{@code python tools/extract_ca.py} 抓链，再 {@code python")
    A(" * tools/gen_tls_anchors.py} 出这个文件。证书原文见 {@code tools/ca_chain.pem}。")
    A(" *")
    A(" * <p>只用于 Android 4.4（API 19）这类系统信任库过旧、又没有 ISRG Root X1 的设备。")
    A(" * 新系统上完全不需要，代码里也会跳过。")
    A(" *")
    A(" * <p>当前内置：")
    for i, (cn, subj, der, b64) in enumerate(entries):
        A(" * <ul><li>[%d] %s</li></ul>" % (i, subj))
    A(" */")
    A("public final class TlsAnchors {")
    A("")
    A("    private TlsAnchors() {")
    A("    }")
    A("")
    A("    /** 每张证书的 X.509 DER 的 Base64；顺序为由中间到根 */")
    A("    public static final String[] CERTS = {")
    for cn, subj, der, b64 in entries:
        A("            // %s" % subj)
        chunks = [b64[i:i + WRAP] for i in range(0, len(b64), WRAP)]
        for k, ch in enumerate(chunks):
            head = '            "' if k == 0 else '                    + "'
            tail = '",' if k == len(chunks) - 1 else '"'
            A(head + ch + tail)
    A("    };")
    A("")
    A("    /** 与 CERTS 一一对应的说明，仅用于日志与排查 */")
    A("    public static final String[] NAMES = {")
    for cn, subj, der, b64 in entries:
        A('            "%s",' % cn.replace('"', ''))
    A("    };")
    A("}")
    A("")

    dstdir = os.path.dirname(DST)
    if not os.path.isdir(dstdir):
        os.makedirs(dstdir)
    io.open(DST, "w", encoding="utf-8", newline="\n").write("\n".join(lines))
    print("wrote %s (%d certs, %d bytes base64)"
          % (DST, len(entries), sum(len(e[3]) for e in entries)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
