#!/usr/bin/env python3
"""
Check the microphone button icon alignment/size from a real device/emulator screenshot.

Usage:
    python scripts/check_mic_icon.py [serial]

It captures a screenshot, locates the purple circular mic button, and measures the
white icon inside it. Fails if the icon is too small or off-center.
"""
import subprocess
import sys
import tempfile
import os
import time
from pathlib import Path

from PIL import Image
import numpy as np
from scipy import ndimage

PURPLE = (0x67, 0x50, 0xA4)
TOL = 40
MIN_ICON_W = 50   # physical px; ~18dp at 440dpi
MIN_ICON_H = 70   # physical px; ~25dp at 440dpi
MAX_CENTER_OFFSET = 8  # px


def run_adb(serial, args):
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += args
    return subprocess.check_output(cmd)


def has_purple(a):
    return (
        (abs(a[:, :, 0].astype(int) - PURPLE[0]) < TOL)
        & (abs(a[:, :, 1].astype(int) - PURPLE[1]) < TOL)
        & (abs(a[:, :, 2].astype(int) - PURPLE[2]) < TOL)
    )


def capture(serial):
    remote = "/sdcard/voice_mic_check.png"
    for attempt in range(8):
        path = os.path.join(tempfile.gettempdir(), f"mic_check_{attempt}.png")
        try:
            data = run_adb(serial, ["exec-out", "screencap", "-p"])
            with open(path, "wb") as f:
                f.write(data)
            img = Image.open(path).convert("RGB")
            a = np.array(img)
            if has_purple(a).sum() > 1000:
                return img, a
        except Exception:
            pass

        # Fallback: screencap to device storage then pull (more reliable on some phones).
        try:
            run_adb(serial, ["shell", "screencap", "-p", remote])
            run_adb(serial, ["pull", remote, path])
            img = Image.open(path).convert("RGB")
            a = np.array(img)
            if has_purple(a).sum() > 1000:
                return img, a
        except Exception:
            pass
        time.sleep(1)
    raise RuntimeError("Could not get a non-blank screenshot (no purple UI found)")


def main():
    serial = sys.argv[1] if len(sys.argv) > 1 else None
    img, a = capture(serial)
    purple = has_purple(a)
    lab, n = ndimage.label(purple)
    sizes = ndimage.sum(purple, lab, range(1, n + 1))

    mic = None
    for i in range(1, n + 1):
        if sizes[i - 1] < 500:
            continue
        ys, xs = np.where(lab == i)
        cx = (xs.min() + xs.max()) / 2
        cy = (ys.min() + ys.max()) / 2
        # mic button is the first large purple circle in the upper-left area
        if 30 <= cx <= 300 and 200 <= cy <= 600:
            mic = (xs.min(), xs.max(), ys.min(), ys.max(), cx, cy)
            break

    if mic is None:
        print("FAIL: could not locate mic button")
        return 1

    x1, x2, y1, y2, cx, cy = mic
    r = (x2 - x1 + 1) / 2
    yy, xx = np.mgrid[y1:y2 + 1, x1:x2 + 1]
    inside = ((xx - cx) ** 2 + (yy - cy) ** 2) <= (r * 0.98) ** 2
    white = (a[:, :, 0] > 230) & (a[:, :, 1] > 230) & (a[:, :, 2] > 230)
    icon = white[y1:y2 + 1, x1:x2 + 1] & inside
    iy, ix = np.where(icon)
    if len(ix) == 0:
        print("FAIL: no white icon found inside mic button")
        return 1

    ix = ix + x1
    iy = iy + y1
    icon_w = ix.max() - ix.min() + 1
    icon_h = iy.max() - iy.min() + 1
    icon_cx = (ix.min() + ix.max()) / 2
    icon_cy = (iy.min() + iy.max()) / 2
    off_x = abs(icon_cx - cx)
    off_y = abs(icon_cy - cy)

    print(f"button: {x1},{y1}-{x2},{y2} center=({cx:.1f},{cy:.1f})")
    print(f"icon: size={icon_w}x{icon_h} center=({icon_cx:.1f},{icon_cy:.1f}) offset=({off_x:.1f},{off_y:.1f})")

    ok = True
    if icon_w < MIN_ICON_W or icon_h < MIN_ICON_H:
        print(f"FAIL: icon too small ({icon_w}x{icon_h}, min {MIN_ICON_W}x{MIN_ICON_H})")
        ok = False
    if off_x > MAX_CENTER_OFFSET or off_y > MAX_CENTER_OFFSET:
        print(f"FAIL: icon off-center ({off_x:.1f},{off_y:.1f}, max {MAX_CENTER_OFFSET})")
        ok = False
    if ok:
        print("PASS")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
