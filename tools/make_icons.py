"""生成应用图标（纯标准库，无 Pillow 依赖）。

输出：
  src-tauri/icons/32x32.png
  src-tauri/icons/128x128.png
  src-tauri/icons/128x128@2x.png
  src-tauri/icons/icon.ico
"""
import os
import struct
import zlib

BG = (20, 184, 166, 255)      # #14B8A6 青绿
FG = (255, 255, 255, 255)     # 白色箭头

ARROW = [
    (0.50, 0.19),
    (0.78, 0.50),
    (0.61, 0.50),
    (0.61, 0.81),
    (0.39, 0.81),
    (0.39, 0.50),
    (0.22, 0.50),
]

SAMPLES = 3  # 超采样倍数


def rounded_rect(x, y, size, radius):
    """判断归一化坐标 (x, y) 是否落在圆角矩形内"""
    r = radius
    if x < r and y < r:
        return (x - r) ** 2 + (y - r) ** 2 <= r * r
    if x > 1 - r and y < r:
        return (x - (1 - r)) ** 2 + (y - r) ** 2 <= r * r
    if x < r and y > 1 - r:
        return (x - r) ** 2 + (y - (1 - r)) ** 2 <= r * r
    if x > 1 - r and y > 1 - r:
        return (x - (1 - r)) ** 2 + (y - (1 - r)) ** 2 <= r * r
    return True


def in_polygon(px, py, poly):
    inside = False
    n = len(poly)
    j = n - 1
    for i in range(n):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if (yi > py) != (yj > py):
            if px < (xj - xi) * (py - yi) / (yj - yi) + xi:
                inside = not inside
        j = i
    return inside


def render(size):
    """渲染 size x size 的 RGBA 像素，返回 bytearray"""
    buf = bytearray(size * size * 4)
    step = 1.0 / (size * SAMPLES)
    radius = 0.24

    for py in range(size):
        for px in range(size):
            hit_bg = 0
            hit_fg = 0
            for sy in range(SAMPLES):
                for sx in range(SAMPLES):
                    nx = (px * SAMPLES + sx + 0.5) * step
                    ny = (py * SAMPLES + sy + 0.5) * step
                    if not rounded_rect(nx, ny, size, radius):
                        continue
                    hit_bg += 1
                    if in_polygon(nx, ny, ARROW):
                        hit_fg += 1

            total = SAMPLES * SAMPLES
            idx = (py * size + px) * 4
            if hit_bg == 0:
                buf[idx:idx + 4] = bytes((0, 0, 0, 0))
                continue

            fg_ratio = hit_fg / hit_bg
            alpha = hit_bg / total
            r = int(BG[0] * (1 - fg_ratio) + FG[0] * fg_ratio)
            g = int(BG[1] * (1 - fg_ratio) + FG[1] * fg_ratio)
            b = int(BG[2] * (1 - fg_ratio) + FG[2] * fg_ratio)
            buf[idx:idx + 4] = bytes((r, g, b, int(255 * alpha)))

    return buf


def chunk(tag, data):
    return (
        struct.pack('>I', len(data))
        + tag
        + data
        + struct.pack('>I', zlib.crc32(tag + data) & 0xFFFFFFFF)
    )


def make_png(size):
    pixels = render(size)
    raw = bytearray()
    stride = size * 4
    for y in range(size):
        raw.append(0)
        raw += pixels[y * stride:(y + 1) * stride]

    return (
        b'\x89PNG\r\n\x1a\n'
        + chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0))
        + chunk(b'IDAT', zlib.compress(bytes(raw), 9))
        + chunk(b'IEND', b'')
    )


def make_ico(sizes):
    images = [make_png(s) for s in sizes]
    header = struct.pack('<HHH', 0, 1, len(sizes))

    offset = 6 + 16 * len(sizes)
    entries = b''
    for size, data in zip(sizes, images):
        dim = 0 if size >= 256 else size
        entries += struct.pack(
            '<BBBBHHII', dim, dim, 0, 0, 1, 32, len(data), offset
        )
        offset += len(data)

    return header + entries + b''.join(images)


def main():
    out_dir = os.path.join(os.path.dirname(__file__), '..', 'src-tauri', 'icons')
    out_dir = os.path.abspath(out_dir)
    os.makedirs(out_dir, exist_ok=True)

    for name, size in (('32x32.png', 32), ('128x128.png', 128), ('128x128@2x.png', 256)):
        path = os.path.join(out_dir, name)
        with open(path, 'wb') as f:
            f.write(make_png(size))
        print('written', path)

    ico_path = os.path.join(out_dir, 'icon.ico')
    with open(ico_path, 'wb') as f:
        f.write(make_ico([32, 48, 128, 256]))
    print('written', ico_path)


if __name__ == '__main__':
    main()
