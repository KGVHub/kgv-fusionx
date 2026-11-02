#!/usr/bin/env python3
"""Renders the KGV X Intelligence orb (res/raw/kgv_orb.webp): a fluid dot-mesh core,
wide curling dotted sheets around the shell, electric sparks and a glass rim, in deep
reds with white-hot edges. A seamless 10 s loop; the light is added onto BG, the chat
card's colour (@color/sheet), so the orb sits seamlessly on the card.

usage: render_orb.py <frames> <size> [first last]   (frames as o%04d.ppm)
then:  magick -delay 1x60 -loop 0 o*.ppm -quality 75 -define webp:method=6 kgv_orb.webp
(600 frames at 336 px: 60 fps, the orb's size on a 3x screen; about 7 MB)
Pure Python; split the frame range over several processes to go faster."""
import math, random, sys
TAU = 2*math.pi
W = H = int(sys.argv[2]) if len(sys.argv) > 2 else 320
RED, ROSE, MAGENTA, SCARLET, HOT = 0xD4101F, 0xE0485A, 0xB01040, 0xD8302A, 0xFFE4E8
BG = 0x121014                      # @color/sheet in res/values/styles.xml
def rgb(c): return ((c >> 16) & 255, (c >> 8) & 255, c & 255)
def unit(v):
    l = math.sqrt(sum(x*x for x in v)); return [x/l for x in v]
def cross(a, b): return [a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]]
rnd = random.Random(5)
# Core lattice: Fibonacci sphere.
CORE_N = 4200
core = []
for i in range(CORE_N):
    y = 1 - 2*(i+0.5)/CORE_N; rr = math.sqrt(1-y*y); th = i*2.399963
    core.append((rr*math.cos(th), y, rr*math.sin(th)))
# Sheets: frame, arc length, width, colour, turns per loop.
SHEETS = []
for k, (col, turns) in enumerate([(ROSE, 1), (MAGENTA, -1), (SCARLET, 1), (RED, 2)]):
    n = unit([rnd.random()*2-1, rnd.random()*2-1, rnd.random()*2-1])
    u = unit(cross(n, [1,0,0] if abs(n[0]) < 0.9 else [0,1,0])); v = cross(n, u)
    SHEETS.append(dict(u=u, v=v, n=n, col=col, turns=turns, ph=rnd.random()*TAU,
                       arc=TAU*(0.45+0.2*rnd.random()), width=0.35+0.15*rnd.random()))
SU, SV = 220, 24
def frame(s, out, sparks_seed):
    a0 = TAU*s
    buf = [list(map(float, rgb(BG))) for _ in range(W*H)]
    def add(x, y, col, a, rad=0.6):
        xi, yi = int(x), int(y)
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                px, py = xi+dx, yi+dy
                if 0 <= px < W and 0 <= py < H:
                    w = a*math.exp(-((px+0.5-x)**2+(py+0.5-y)**2)/(rad*rad))
                    b = buf[py*W+px]; b[0] += col[0]*w; b[1] += col[1]*w; b[2] += col[2]*w
    cx = cy = W/2; R = W/2*0.78
    ay = a0; ax = 0.35*math.sin(a0)
    cyw, syw, cxw, sxw = math.cos(ay), math.sin(ay), math.cos(ax), math.sin(ax)
    def proj(x, y, z):
        x1 = x*cyw+z*syw; z1 = -x*syw+z*cyw
        y2 = y*cxw-z1*sxw; z2 = y*sxw+z1*cxw
        return cx+x1, cy+y2, z2
    # Fluid core: smooth travelling waves on a dot sphere.
    rc = R*0.62
    cr, cs = rgb(RED), rgb(HOT)
    for (x, y, z) in core:
        d = (0.10*math.sin(3*x + a0*2 + 1) + 0.09*math.sin(4*y - a0*3 + 2)
             + 0.08*math.sin(3*z + a0*2) + 0.06*math.sin(2*(x+y-z) + a0*4))
        r = rc*(1+d)
        px, py, pz = proj(x*r, y*r, z*r)
        depth = (pz/rc+1)/2
        add(px, py, cs if d > 0.14 else (rgb(ROSE) if depth > 0.7 else cr),
            (0.16 + 0.42*depth)*(1.5 if d > 0.14 else 1), 0.6)
    # Sheets: wide dotted surfaces sweeping around the shell, curling at the edges.
    for sh in SHEETS:
        u, v, n = sh['u'], sh['v'], sh['n']; col = rgb(sh['col']); hot = rgb(HOT)
        ph = sh['ph'] + sh['turns']*a0
        for i in range(SU):
            t = i/(SU-1)
            ang = ph + t*sh['arc']
            taper = math.sin(math.pi*t)**0.8            # wisp-like ends
            for j in range(SV):
                w = (j/(SV-1) - 0.5)*2                    # -1..1 across the sheet
                tilt = sh['width']*w*taper + 0.28*math.sin(2*ang - a0 + w)   # curl
                r = R*(0.93 + 0.05*math.sin(3*ang + a0) - 0.10*abs(w)*taper)
                ca, sa, ct, st = math.cos(ang), math.sin(ang), math.cos(tilt), math.sin(tilt)
                dx = (ca*u[0]+sa*v[0])*ct + n[0]*st
                dy = (ca*u[1]+sa*v[1])*ct + n[1]*st
                dz = (ca*u[2]+sa*v[2])*ct + n[2]*st
                px, py, pz = proj(dx*r, dy*r, dz*r)
                depth = (pz/R+1)/2
                edge = abs(w) > 0.78
                add(px, py, hot if edge and depth > 0.5 else col,
                    (0.10 + 0.40*depth)*taper*(2.6 if edge else 1), 0.6 if edge else 0.55)
    # Sparks: short jagged arcs on the core, re-drawn each frame.
    sr = random.Random(sparks_seed)
    for _ in range(3):
        p = unit([sr.random()*2-1, sr.random()*2-1, sr.random()*2-1])
        q = unit([p[i] + (sr.random()-0.5)*0.8 for i in range(3)])
        pts = []
        for m in range(9):
            f = m/12
            d = unit([p[i]*(1-f)+q[i]*f + (sr.random()-0.5)*0.09*(1-abs(2*f-1)) for i in range(3)])
            pts.append(proj(*[c*rc*1.05 for c in d]))
        for (x0, y0, z0), (x1, y1, z1) in zip(pts, pts[1:]):
            steps = max(2, int(math.hypot(x1-x0, y1-y0)*2))
            for st in range(steps):
                f = st/steps; x = x0+(x1-x0)*f; y = y0+(y1-y0)*f
                add(x, y, rgb(RED), 0.18, 2.6)      # glow
                add(x, y, rgb(HOT), 0.85, 0.5)      # bright core
    # Glass rim (as in the first orb): a thin bright edge, colours turning around it.
    rim = [HOT, RED, MAGENTA, ROSE, SCARLET, HOT]
    rot = -math.degrees(a0)
    rr = R*1.0
    for py in range(H):
        for px in range(W):
            d = math.hypot(px+0.5-cx, py+0.5-cy) - rr
            if abs(d) > 7: continue
            ang = (math.degrees(math.atan2(py-cy, px-cx)) - rot) % 360 / 360 * 5
            i0 = int(ang); f = ang-i0
            c0, c1 = rgb(rim[i0]), rgb(rim[min(i0+1, 5)])
            col = [c0[q]*(1-f)+c1[q]*f for q in range(3)]
            a = 0
            for wdt, al in ((8, 45), (3.8, 100), (1.5, 255)):
                if abs(d) < wdt/2: a += al/255*(1-abs(d)/(wdt/2))**0.5
            b = buf[py*W+px]; b[0] += col[0]*a; b[1] += col[1]*a; b[2] += col[2]*a
    # Soft red halo for the glass, clean falloff.
    for py in range(H):
        for px in range(W):
            dd = math.hypot(px+0.5-cx, py+0.5-cy)/R
            g = 0.16*math.exp(-(dd/0.55)**2) + 0.03*math.exp(-((dd-1)/0.1)**2)
            b = buf[py*W+px]; b[0] += 210*g; b[1] += 14*g; b[2] += 24*g
    with open(out, 'wb') as f:
        f.write(b'P6 %d %d 255\n' % (W, H))
        f.write(bytes(min(255, int(v)) for p in buf for v in p))
n = int(sys.argv[1]) if len(sys.argv) > 1 else 48
lo = int(sys.argv[3]) if len(sys.argv) > 3 else 0
hi = int(sys.argv[4]) if len(sys.argv) > 4 else n
fps = n / 10.0                      # the loop is 10 s, like the app
for fi in range(lo, hi):
    frame(fi/n, 'o%04d.ppm' % fi, int(fi * 1000 / fps / 70))   # sparks every 70 ms
