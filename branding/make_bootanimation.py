#!/usr/bin/env python3
"""Render the KGV FusionX boot animation.

Builds bootanimation.zip for a 1080x1920 screen from the KGVHub logo:
part0 plays once (lightning strike, neon ignition of the logo, the name
charged in by an electric scan line), part1 loops until boot completes
(pulsing glow, arcs crawling around the logo, sparks, a shimmer on the name).

usage: make_bootanimation.py <logo.jpg> <out dir>
"""
import math
import os
import random
import shutil
import subprocess
import sys

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

W, H, FPS = 1080, 1920, 30
INTRO, LOOP = 90, 60
HERE = os.path.dirname(os.path.abspath(__file__))
FONT = os.path.join(HERE, "fonts", "Orbitron.ttf")

RED = (255, 40, 40)
HOT = (255, 120, 90)
WHITE = (255, 255, 255)
LOGO_CY = 820          # logo centre on screen
TEXT_Y = 1240          # top of the name


def logo_layer(src):
    """The neon brain and KGV letters, without the KGVHub caption, on black."""
    im = Image.open(src).convert("RGB")
    w, h = im.size
    art = im.crop((0, int(h * 0.08), w, int(h * 0.66)))      # drop the caption
    scale = 1000 / art.width
    art = art.resize((1000, int(art.height * scale)), Image.LANCZOS)
    # Crush the background to pure black so the layer can be added on top.
    a = np.asarray(art).astype(np.float32)
    lum = a.max(axis=2, keepdims=True)
    a = a * np.clip((lum - 18) / 40, 0, 1)
    art = Image.fromarray(a.astype(np.uint8))
    layer = Image.new("RGB", (W, H))
    layer.paste(art, ((W - art.width) // 2, LOGO_CY - art.height // 2))
    return layer, (art.width, art.height)


def text_layer():
    layer = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(layer)
    font = ImageFont.truetype(FONT, 118)
    font.set_variation_by_name("Black")
    a, b = "KGV ", "FUSIONX"
    wa = d.textlength(a, font=font)
    wb = d.textlength(b, font=font)
    x = (W - wa - wb) / 2
    d.text((x, TEXT_Y), a, font=font, fill=WHITE)
    d.text((x + wa, TEXT_Y), b, font=font, fill=RED)
    small = ImageFont.truetype(FONT, 40)
    small.set_variation_by_name("Medium")
    tag = "POWERED BY KGV PRO X"
    d.text(((W - d.textlength(tag, font=small)) / 2, TEXT_Y + 170), tag,
           font=small, fill=(150, 150, 150))
    bbox = (int(x), TEXT_Y, int(x + wa + wb), TEXT_Y + 240)
    return layer, bbox


def glow(layer, radii=(6, 18, 48), gains=(1.0, 0.8, 0.6)):
    out = layer.copy()
    for r, g in zip(radii, gains):
        blur = layer.filter(ImageFilter.GaussianBlur(r))
        if g != 1.0:
            blur = Image.eval(blur, lambda v, g=g: int(v * g))
        out = ImageChops.add(out, blur)
    return out


def scale(layer, k):
    k = max(0.0, k)
    if k >= 0.999:
        return layer
    return Image.eval(layer, lambda v: int(v * k))


def bolt_points(rng, p0, p1, depth=7, spread=0.32):
    pts = [p0, p1]
    disp = math.dist(p0, p1) * spread
    for _ in range(depth):
        nxt = [pts[0]]
        for a, b in zip(pts, pts[1:]):
            mx, my = (a[0] + b[0]) / 2, (a[1] + b[1]) / 2
            dx, dy = b[0] - a[0], b[1] - a[1]
            n = math.hypot(dx, dy) or 1
            off = rng.uniform(-disp, disp)
            nxt += [(mx - dy / n * off, my + dx / n * off), b]
        pts = nxt
        disp *= 0.55
    return pts


def draw_bolt(img, rng, p0, p1, width=10, color=RED, branches=3, spread=0.32):
    layer = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(layer)
    pts = bolt_points(rng, p0, p1, spread=spread)
    d.line(pts, fill=color, width=width, joint="curve")
    d.line(pts, fill=WHITE, width=max(2, width // 3), joint="curve")
    for _ in range(branches):
        i = rng.randrange(len(pts) // 5, len(pts) * 4 // 5)
        a = pts[i]
        ang = rng.uniform(-1.2, 1.2) + math.atan2(p1[1] - p0[1], p1[0] - p0[0])
        ln = math.dist(p0, p1) * rng.uniform(0.12, 0.3)
        b = (a[0] + math.cos(ang) * ln, a[1] + math.sin(ang) * ln)
        bp = bolt_points(rng, a, b, depth=5)
        d.line(bp, fill=color, width=max(2, width // 2), joint="curve")
        d.line(bp, fill=WHITE, width=max(1, width // 5))
    return ImageChops.add(img, glow(layer, (4, 14, 40), (1.0, 0.9, 0.7)))


class Sparks:
    def __init__(self, rng):
        self.rng, self.p = rng, []

    def burst(self, x, y, n, speed=28, up=0.0):
        for _ in range(n):
            a = self.rng.uniform(0, 2 * math.pi)
            s = self.rng.uniform(0.3, 1.0) * speed
            self.p.append([x, y, math.cos(a) * s, math.sin(a) * s - up,
                           self.rng.randint(10, 26)])

    def step_draw(self, img):
        layer = Image.new("RGB", (W, H))
        d = ImageDraw.Draw(layer)
        alive = []
        for p in self.p:
            x, y, vx, vy, life = p
            nx, ny = x + vx, y + vy
            t = min(1.0, life / 18)
            col = (255, int(140 + 115 * t), int(80 + 175 * t * t))
            d.line([(x, y), (nx, ny)], fill=col, width=3 if life > 8 else 2)
            p[:] = [nx, ny, vx * 0.94, vy * 0.94 + 1.1, life - 1]
            if p[4] > 0 and -50 < nx < W + 50 and ny < H + 50:
                alive.append(p)
        self.p = alive
        return ImageChops.add(img, glow(layer, (3, 10), (1.0, 0.8)))


def background(k=1.0):
    y, x = np.mgrid[0:H, 0:W].astype(np.float32)
    r = np.hypot((x - W / 2) / W, (y - LOGO_CY) / H)
    v = np.clip(1 - r * 1.9, 0, 1) ** 2
    bg = np.zeros((H, W, 3), np.float32)
    bg[..., 0] = 34 * v * k
    bg[..., 1] = 4 * v * k
    bg[..., 2] = 6 * v * k
    return Image.fromarray(bg.astype(np.uint8))


def ring_point(rng, size):
    a = rng.uniform(0, 2 * math.pi)
    rx, ry = size[0] * 0.5, size[1] * 0.55
    return (W / 2 + math.cos(a) * rx, LOGO_CY + math.sin(a) * ry)


def render(src, out):
    rng = random.Random(20261025)
    logo, lsize = logo_layer(src)
    logo_glow = glow(logo, (8, 26, 70), (0.9, 0.7, 0.5))
    text, tb = text_layer()
    text_glow = glow(text, (5, 16, 44), (0.9, 0.7, 0.5))
    bg = background()
    sparks = Sparks(rng)
    strike = (W / 2 + 40, LOGO_CY - 60)

    for part in ("part0", "part1"):
        os.makedirs(os.path.join(out, part), exist_ok=True)

    def save(img, part, i):
        img.save(os.path.join(out, part, f"{i:03d}.jpg"), quality=90, subsampling=0)

    # part0: strike, ignition, charge-in
    for f in range(INTRO):
        img = scale(bg, min(1, f / 30))
        # crackle before the strike
        if f < 22 and rng.random() < 0.45:
            p = ring_point(rng, lsize)
            sparks.burst(p[0], p[1], 6, speed=10)
            if rng.random() < 0.4:
                img = draw_bolt(img, rng, p, ring_point(rng, lsize), width=4, branches=1)
        # main lightning strike from the top of the screen
        if 22 <= f <= 27:
            for _ in range(2 if f < 25 else 1):
                img = draw_bolt(img, rng, (rng.uniform(300, 780), -20), strike,
                                width=16 - (f - 22) * 2, branches=5, spread=0.22)
            if f == 22:
                sparks.burst(*strike, 140, speed=42, up=6)
            if f < 25:   # screen flash
                img = ImageChops.add(img, Image.new("RGB", (W, H), (120 - (f - 22) * 40,) * 3))
        # neon ignition of the logo
        if f >= 24:
            t = f - 24
            if t < 20:
                k = (rng.random() < 0.55) * (0.35 + t / 30)
            else:
                k = min(1.0, 0.7 + (t - 20) / 30)
            img = ImageChops.add(img, scale(logo_glow, k))
        # the name charged in by a scan line, left to right
        if f >= 50:
            prog = min(1.0, (f - 50) / 26)
            sx = int(tb[0] - 30 + (tb[2] - tb[0] + 60) * prog)
            mask = Image.new("L", (W, H))
            ImageDraw.Draw(mask).rectangle((0, 0, sx, H), fill=255)
            img = Image.composite(ImageChops.add(img, text_glow), img, mask)
            if prog < 1:
                sy0, sy1 = tb[1] - 20, tb[1] + 170
                img = draw_bolt(img, rng, (sx, sy0), (sx, sy1), width=6, branches=2, spread=0.15)
                sparks.burst(sx, rng.uniform(sy0, sy1), 10, speed=18, up=4)
        img = sparks.step_draw(img)
        save(img, "part0", f)

    # part1: idle loop, seamless pulse
    for f in range(LOOP):
        ph = 2 * math.pi * f / LOOP
        img = bg.copy()
        img = ImageChops.add(img, scale(logo_glow, 0.82 + 0.18 * math.sin(ph)))
        if rng.random() < 0.35:
            a = ring_point(rng, lsize)
            img = draw_bolt(img, rng, a, ring_point(rng, lsize), width=5, branches=1)
            sparks.burst(a[0], a[1], 8, speed=12)
        img = ImageChops.add(img, text_glow)
        sx = int(-200 + (W + 400) * f / LOOP)          # shimmer across the name
        band = Image.new("L", (W, H))
        ImageDraw.Draw(band).polygon([(sx - 60, tb[1]), (sx + 20, tb[1]),
                                      (sx - 40, tb[3]), (sx - 120, tb[3])], fill=110)
        band = band.filter(ImageFilter.GaussianBlur(18))
        img = Image.composite(ImageChops.add(img, text), img, band)
        img = sparks.step_draw(img)
        save(img, "part1", f)

    with open(os.path.join(out, "desc.txt"), "w") as fh:
        fh.write(f"{W} {H} {FPS}\np 1 0 part0\np 0 0 part1\n")
    zpath = os.path.join(out, "bootanimation.zip")
    if os.path.exists(zpath):
        os.remove(zpath)
    # Android needs the zip stored, not compressed.
    subprocess.run(["zip", "-0qr", "bootanimation.zip", "desc.txt", "part0", "part1"],
                   cwd=out, check=True)
    print(zpath, os.path.getsize(zpath) // 1024, "KB")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    out = os.path.abspath(sys.argv[2])
    for part in ("part0", "part1"):
        shutil.rmtree(os.path.join(out, part), ignore_errors=True)
    render(sys.argv[1], out)
