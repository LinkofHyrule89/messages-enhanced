#!/usr/bin/env python3
"""Generate the extension icons (16/32/48/128 PNG) with only the stdlib.

Design: Tesla-red rounded square with a white chat bubble and three red dots.
Rendered with 4x4 supersampling for smooth edges. Run from the extension dir:
    python3 scripts/make_icons.py
"""
import os
import struct
import zlib

RED = (227, 25, 55)
WHITE = (255, 255, 255)
SS = 4  # supersampling factor per axis


def rounded_rect(x, y, x0, y0, x1, y1, r):
    """True if point (x,y) is inside the rounded rectangle [x0,x1]x[y0,y1]."""
    if x < x0 or x > x1 or y < y0 or y > y1:
        return False
    cx = min(max(x, x0 + r), x1 - r)
    cy = min(max(y, y0 + r), y1 - r)
    return (x - cx) ** 2 + (y - cy) ** 2 <= r * r


def in_tail(x, y):
    # Bubble tail: triangle below the bubble on the left.
    ax, ay = 0.30, 0.66
    bx, by = 0.46, 0.66
    cx, cy = 0.26, 0.82
    d = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
    l1 = ((by - cy) * (x - cx) + (cx - bx) * (y - cy)) / d
    l2 = ((cy - ay) * (x - cx) + (ax - cx) * (y - cy)) / d
    l3 = 1 - l1 - l2
    return l1 >= 0 and l2 >= 0 and l3 >= 0


def in_dot(x, y):
    # Three red dots inside the white bubble.
    r = 0.045
    cy = 0.44
    for cx in (0.36, 0.50, 0.64):
        if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
            return True
    return False


def sample(x, y):
    """Return (r,g,b) for normalized coords in [0,1], or None if transparent."""
    if not rounded_rect(x, y, 0.0, 0.0, 1.0, 1.0, 0.22):
        return None
    # White chat bubble body (rounded) plus its tail.
    bubble = rounded_rect(x, y, 0.22, 0.24, 0.78, 0.64, 0.12) or in_tail(x, y)
    if bubble and not in_dot(x, y):
        return WHITE
    return RED


def render(size):
    """Return raw RGBA bytes for a size x size icon (supersampled)."""
    rows = bytearray()
    for py in range(size):
        rows.append(0)  # PNG filter byte (none) per scanline
        for px in range(size):
            ar = ag = ab = aa = 0
            for sy in range(SS):
                for sx in range(SS):
                    nx = (px + (sx + 0.5) / SS) / size
                    ny = (py + (sy + 0.5) / SS) / size
                    c = sample(nx, ny)
                    if c is not None:
                        ar += c[0]
                        ag += c[1]
                        ab += c[2]
                        aa += 255
            n = SS * SS
            a = aa // n
            if a == 0:
                rows.extend((0, 0, 0, 0))
            else:
                # Un-premultiply: average color over covered subsamples only.
                covered = aa // 255
                rows.extend((ar // covered, ag // covered, ab // covered, a))
    return bytes(rows)


def write_png(path, size):
    raw = render(size)

    def chunk(tag, data):
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    ihdr = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)  # 8-bit RGBA
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )
    with open(path, "wb") as f:
        f.write(png)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    icons_dir = os.path.normpath(os.path.join(here, "..", "icons"))
    os.makedirs(icons_dir, exist_ok=True)
    for size in (16, 32, 48, 128):
        out = os.path.join(icons_dir, "icon%d.png" % size)
        write_png(out, size)
        print("wrote", out)


if __name__ == "__main__":
    main()
