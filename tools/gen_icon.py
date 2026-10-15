# -*- coding: utf-8 -*-
"""
生成应用图标（纯 Python，不依赖 Pillow）：
  * res/mipmap-*/ic_launcher.png     传统图标（Android 4.4-7.1 用）
  * res/mipmap-*/ic_launcher_fg.png  自适应图标前景（Android 8+ 用）

用 SDF + 4x4 超采样做抗锯齿，肉眼干净。
"""
import math, os, struct, sys, zlib

sys.stdout.reconfigure(encoding='utf-8')
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RES = os.path.join(ROOT, "res")

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
ADAPTIVE = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

BG_TOP = (0x17, 0x24, 0x31)
BG_BOTTOM = (0x06, 0x0A, 0x0F)
ACCENT = (0x4F, 0xC3, 0xF7)
WHITE = (0xFF, 0xFF, 0xFF)
SUN = (0xFF, 0xC6, 0x4D)


def sd_round_rect(px, py, cx, cy, hw, hh, r):
    qx = abs(px - cx) - (hw - r)
    qy = abs(py - cy) - (hh - r)
    ax, ay = max(qx, 0.0), max(qy, 0.0)
    return math.hypot(ax, ay) + min(max(qx, qy), 0.0) - r


def sd_circle(px, py, cx, cy, r):
    return math.hypot(px - cx, py - cy) - r


def sd_seg(px, py, ax, ay, bx, by):
    vx, vy = bx - ax, by - ay
    wx, wy = px - ax, py - ay
    d = vx * vx + vy * vy
    t = 0.0 if d == 0 else max(0.0, min(1.0, (wx * vx + wy * vy) / d))
    return math.hypot(px - (ax + vx * t), py - (ay + vy * t))


def over(dst, rgb, a):
    """straight-alpha over 合成，dst = [r,g,b,a] 0..1"""
    if a <= 0:
        return dst
    da = dst[3]
    oa = a + da * (1 - a)
    if oa <= 0:
        return [0.0, 0.0, 0.0, 0.0]
    out = [(rgb[i] / 255.0 * a + dst[i] * da * (1 - a)) / oa for i in range(3)]
    return [out[0], out[1], out[2], oa]


def poly_coverage(dist, aa=1.0):
    """SDF -> 覆盖率（边缘 1px 过渡）"""
    if dist <= -aa:
        return 1.0
    if dist >= aa:
        return 0.0
    return (aa - dist) / (2.0 * aa)


def render(size, with_bg, scale=1.0):
    ss = 4
    sub = 1.0 / ss
    px = [[0.0, 0.0, 0.0, 0.0] for _ in range(size * size)]

    cx = cy = size / 2.0
    # 形状参数（相对 size），scale 用于自适应图标把内容缩进安全区
    bg_hw = size / 2.0
    bg_r = size * 0.22
    ring_r = size * 0.285 * scale
    ring_w = size * 0.055 * scale
    hand_w = size * 0.048 * scale
    hand_h = size * 0.125 * scale
    hand_m = size * 0.185 * scale
    sun_off = size * 0.255 * scale
    sun_x, sun_y = cx + sun_off, cy - sun_off
    sun_r = size * 0.095 * scale
    dot_r = size * 0.030 * scale

    for j in range(size):
        for i in range(size):
            acc = [0.0, 0.0, 0.0, 0.0]
            for sj in range(ss):
                for si in range(ss):
                    x = i + (si + 0.5) * sub
                    y = j + (sj + 0.5) * sub
                    c = [0.0, 0.0, 0.0, 0.0]
                    if with_bg:
                        d = sd_round_rect(x, y, cx, cy, bg_hw, bg_hw, bg_r)
                        a = poly_coverage(d, 1.0 / ss)
                        if a > 0:
                            t = y / size
                            rgb = tuple(BG_TOP[k] * (1 - t) + BG_BOTTOM[k] * t for k in range(3))
                            c = over(c, rgb, a)
                    # 表盘外圈
                    d = abs(sd_circle(x, y, cx, cy, ring_r)) - ring_w / 2.0
                    a = poly_coverage(d, 1.0 / ss)
                    if a > 0:
                        c = over(c, ACCENT, a)
                    # 时针指向 10 点方向，分针指向 12 点方向
                    ang_h = math.radians(-60 - 90)
                    ang_m = math.radians(-90)
                    d = sd_seg(x, y, cx, cy,
                               cx + math.cos(ang_h) * hand_h,
                               cy + math.sin(ang_h) * hand_h) - hand_w / 2.0
                    a = poly_coverage(d, 1.0 / ss)
                    if a > 0:
                        c = over(c, WHITE, a)
                    d = sd_seg(x, y, cx, cy,
                               cx + math.cos(ang_m) * hand_m,
                               cy + math.sin(ang_m) * hand_m) - hand_w * 0.85 / 2.0
                    a = poly_coverage(d, 1.0 / ss)
                    if a > 0:
                        c = over(c, WHITE, a)
                    d = sd_circle(x, y, cx, cy, dot_r)
                    a = poly_coverage(d, 1.0 / ss)
                    if a > 0:
                        c = over(c, WHITE, a)
                    # 右上角小太阳
                    d = sd_circle(x, y, sun_x, sun_y, sun_r)
                    a = poly_coverage(d, 1.0 / ss)
                    if a > 0:
                        c = over(c, SUN, a)
                    acc[0] += c[0] * c[3]
                    acc[1] += c[1] * c[3]
                    acc[2] += c[2] * c[3]
                    acc[3] += c[3]
            n = float(ss * ss)
            pa = acc[3] / n
            if pa <= 0:
                px[j * size + i] = [0, 0, 0, 0]
            else:
                pr = acc[0] / (acc[3] if acc[3] > 0 else 1)
                pg = acc[1] / (acc[3] if acc[3] > 0 else 1)
                pb = acc[2] / (acc[3] if acc[3] > 0 else 1)
                px[j * size + i] = [pr, pg, pb, pa]
    return px


def write_png(path, size, px):
    raw = bytearray()
    for j in range(size):
        raw.append(0)
        for i in range(size):
            r, g, b, a = px[j * size + i]
            raw.append(max(0, min(255, int(round(r * 255)))))
            raw.append(max(0, min(255, int(round(g * 255)))))
            raw.append(max(0, min(255, int(round(b * 255)))))
            raw.append(max(0, min(255, int(round(a * 255)))))

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)
    return len(png)


made = []
for name, size in DENSITIES.items():
    d = os.path.join(RES, "mipmap-" + name)
    os.makedirs(d, exist_ok=True)
    n1 = write_png(os.path.join(d, "ic_launcher.png"), size, render(size, True))
    made.append((os.path.join(d, "ic_launcher.png"), n1))

for name, size in ADAPTIVE.items():
    d = os.path.join(RES, "mipmap-" + name)
    os.makedirs(d, exist_ok=True)
    # 前景：透明底，图案缩进 108dp 画布的安全区
    px = render(size, False, 0.82)
    n2 = write_png(os.path.join(d, "ic_launcher_fg.png"), size, px)
    made.append((os.path.join(d, "ic_launcher_fg.png"), n2))

for p, n in made:
    print("%-72s %6d bytes" % (p.replace(ROOT + os.sep, ""), n))
print("done")
