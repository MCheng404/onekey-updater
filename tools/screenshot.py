"""纯标准库截屏工具（ctypes + GDI），用于验证 Tauri 窗口的真实合成效果。

用法:
    python screenshot.py <输出png> [窗口标题关键字]

不依赖 Pillow / pywin32。原理:
  1. 先把目标窗口置前（否则截不到被遮挡部分）
  2. 用 BitBlt 从桌面 DC 拷贝窗口所在矩形 —— 这样能拿到 DWM 合成后的
     真实画面，包含 acrylic 毛玻璃背景（PrintWindow 截不到毛玻璃）
  3. 手写 PNG（zlib + struct）

注意: 会调用 SetProcessDPIAware，避免高分屏下取到被虚拟化的坐标。
"""

import ctypes
import os
import struct
import sys
import time
import zlib
from ctypes import wintypes

# SHOT_MODE=print 用 PrintWindow 抓窗口自身内容（不怕被遮挡，但毛玻璃会丢）；
# 默认 screen 从桌面 DC 抓，能拿到真实合成效果，但窗口必须在最前。
MODE = os.environ.get("SHOT_MODE", "screen")
# PW_RENDERFULLCONTENT：让 PrintWindow 也能抓到 DirectComposition / WebView 内容
PW_RENDERFULLCONTENT = 2

user32 = ctypes.windll.user32
gdi32 = ctypes.windll.gdi32

SRCCOPY = 0x00CC0020
DIB_RGB_COLORS = 0

# SetWindowPos
HWND_TOPMOST = -1
HWND_NOTOPMOST = -2
SWP_NOSIZE = 0x0001
SWP_NOMOVE = 0x0002
SWP_SHOWWINDOW = 0x0040

SW_RESTORE = 9
SW_SHOW = 5


class BITMAPINFOHEADER(ctypes.Structure):
    _fields_ = [
        ("biSize", wintypes.DWORD),
        ("biWidth", wintypes.LONG),
        ("biHeight", wintypes.LONG),
        ("biPlanes", wintypes.WORD),
        ("biBitCount", wintypes.WORD),
        ("biCompression", wintypes.DWORD),
        ("biSizeImage", wintypes.DWORD),
        ("biXPelsPerMeter", wintypes.LONG),
        ("biYPelsPerMeter", wintypes.LONG),
        ("biClrUsed", wintypes.DWORD),
        ("biClrImportant", wintypes.DWORD),
    ]


class BITMAPINFO(ctypes.Structure):
    _fields_ = [("bmiHeader", BITMAPINFOHEADER), ("bmiColors", wintypes.DWORD * 3)]


def make_dpi_aware() -> None:
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(2)  # PER_MONITOR_AWARE_V2
    except Exception:
        try:
            user32.SetProcessDPIAware()
        except Exception:
            pass


def _enum_windows(predicate) -> list:
    """枚举可见顶层窗口，返回所有满足 predicate(hwnd, title) 的句柄。"""
    hits = []

    @ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    def enum_proc(hwnd, _lparam):
        if not user32.IsWindowVisible(hwnd):
            return True
        length = user32.GetWindowTextLengthW(hwnd)
        if length <= 0:
            return True
        buf = ctypes.create_unicode_buffer(length + 1)
        user32.GetWindowTextW(hwnd, buf, length + 1)
        if predicate(hwnd, buf.value):
            hits.append(hwnd)
        return True

    user32.EnumWindows(enum_proc, 0)
    return hits


def window_pid(hwnd: int) -> int:
    pid = wintypes.DWORD()
    user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
    return pid.value


def find_window(selector: str) -> int:
    """按 PID（纯数字）或标题关键字查找窗口。

    优先 PID：按标题匹配极易误伤（例如编辑器打开了 `一键更新.ps1`，
    标题里也含关键字），PID 是唯一可靠的。
    """
    if selector.isdigit():
        target = int(selector)
        hits = _enum_windows(lambda h, _t: window_pid(h) == target)
        # 同一个进程可能有多个窗口（主/设置/通知/关于），挑面积最大的那个
        best, best_area = 0, -1
        for h in hits:
            r = wintypes.RECT()
            user32.GetWindowRect(h, ctypes.byref(r))
            area = (r.right - r.left) * (r.bottom - r.top)
            if area > best_area:
                best, best_area = h, area
        return best

    keyword = selector
    hits = _enum_windows(lambda _h, t: keyword in t)
    return hits[0] if hits else 0


def capture_screen_region(left: int, top: int, width: int, height: int) -> bytes:
    """从桌面 DC 抓取指定区域，返回 BGRA 原始字节（top-down）。"""
    hdesktop = user32.GetDC(0)
    memdc = gdi32.CreateCompatibleDC(hdesktop)
    bmp = gdi32.CreateCompatibleBitmap(hdesktop, width, height)
    gdi32.SelectObject(memdc, bmp)

    gdi32.BitBlt(memdc, 0, 0, width, height, hdesktop, left, top, SRCCOPY)

    bi = BITMAPINFO()
    bi.bmiHeader.biSize = ctypes.sizeof(BITMAPINFOHEADER)
    bi.bmiHeader.biWidth = width
    bi.bmiHeader.biHeight = -height  # 负值 = top-down
    bi.bmiHeader.biPlanes = 1
    bi.bmiHeader.biBitCount = 32
    bi.bmiHeader.biCompression = 0

    buf = ctypes.create_string_buffer(width * height * 4)
    gdi32.GetDIBits(memdc, bmp, 0, height, buf, ctypes.byref(bi), DIB_RGB_COLORS)

    gdi32.DeleteObject(bmp)
    gdi32.DeleteDC(memdc)
    user32.ReleaseDC(0, hdesktop)
    return buf.raw


def capture_window_self(hwnd: int, width: int, height: int) -> bytes:
    """PrintWindow 抓窗口自身内容，不用管是否被遮挡。"""
    hdc = user32.GetWindowDC(hwnd)
    memdc = gdi32.CreateCompatibleDC(hdc)
    bmp = gdi32.CreateCompatibleBitmap(hdc, width, height)
    gdi32.SelectObject(memdc, bmp)

    user32.PrintWindow(hwnd, memdc, PW_RENDERFULLCONTENT)

    bi = BITMAPINFO()
    bi.bmiHeader.biSize = ctypes.sizeof(BITMAPINFOHEADER)
    bi.bmiHeader.biWidth = width
    bi.bmiHeader.biHeight = -height
    bi.bmiHeader.biPlanes = 1
    bi.bmiHeader.biBitCount = 32
    bi.bmiHeader.biCompression = 0

    buf = ctypes.create_string_buffer(width * height * 4)
    gdi32.GetDIBits(memdc, bmp, 0, height, buf, ctypes.byref(bi), DIB_RGB_COLORS)

    gdi32.DeleteObject(bmp)
    gdi32.DeleteDC(memdc)
    user32.ReleaseDC(hwnd, hdc)
    return buf.raw


def write_png(path: str, width: int, height: int, bgra: bytes) -> None:
    """把 BGRA 数据写成 PNG（alpha 一律丢弃，按不透明处理）。"""
    rows = bytearray()
    stride = width * 4
    for y in range(height):
        rows.append(0)  # filter type 0
        line = bgra[y * stride : (y + 1) * stride]
        # BGRA -> RGB
        rgb = bytearray(width * 3)
        rgb[0::3] = line[2::4]
        rgb[1::3] = line[1::4]
        rgb[2::3] = line[0::4]
        rows += rgb

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)  # 8bit, truecolor
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(bytes(rows), 9))
        + chunk(b"IEND", b"")
    )
    with open(path, "wb") as f:
        f.write(png)


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: screenshot.py <out.png> [pid | title-keyword] [x y w h]")
        return 1

    out = sys.argv[1]
    selector = sys.argv[2] if len(sys.argv) > 2 else "一键更新"

    make_dpi_aware()

    hwnd = find_window(selector)
    if not hwnd:
        print(f"NOT_FOUND: 未找到匹配「{selector}」的窗口")
        return 2

    # 置前。注意 SetForegroundWindow 对后台进程常常直接被系统拒绝，
    # 所以必须配合「短暂 TOPMOST → 取消 TOPMOST」这套经典手法，
    # 否则会截到压在上面的其它窗口。
    user32.ShowWindow(hwnd, SW_RESTORE)
    user32.BringWindowToTop(hwnd)
    # 保持 TOPMOST 直到截图完成再取消 —— 否则取消的瞬间就会被别的窗口盖回去，
    # 截到的就是别人（这坑踩过两次）。
    user32.SetWindowPos(
        hwnd, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW
    )
    user32.SetForegroundWindow(hwnd)
    # 等 DWM 完成合成，避免截到半透明中间态
    time.sleep(1.1)

    rect = wintypes.RECT()
    user32.GetWindowRect(hwnd, ctypes.byref(rect))
    width = rect.right - rect.left
    height = rect.bottom - rect.top
    if width <= 0 or height <= 0:
        print(f"BAD_RECT: {width}x{height}")
        return 3

    bgra = capture_screen_region(rect.left, rect.top, width, height)

    # 截图完成，恢复为非置顶
    user32.SetWindowPos(
        hwnd, HWND_NOTOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW
    )

    # 可选裁剪：screenshot.py out.png "标题" <x> <y> <w> <h>
    if len(sys.argv) >= 7:
        cx, cy, cw, ch = (int(v) for v in sys.argv[3:7])
        cx = max(0, min(cx, width - 1))
        cy = max(0, min(cy, height - 1))
        cw = max(1, min(cw, width - cx))
        ch = max(1, min(ch, height - cy))
        bgra = crop(bgra, width, cx, cy, cw, ch)
        width, height = cw, ch

    write_png(out, width, height, bgra)
    print(f"OK: {out} {width}x{height} at ({rect.left},{rect.top})")
    return 0


def crop(bgra: bytes, src_width: int, x: int, y: int, w: int, h: int) -> bytes:
    """从 BGRA 缓冲裁剪一块矩形。"""
    out = bytearray()
    for row in range(y, y + h):
        start = (row * src_width + x) * 4
        out += bgra[start : start + w * 4]
    return bytes(out)


if __name__ == "__main__":
    sys.exit(main())
