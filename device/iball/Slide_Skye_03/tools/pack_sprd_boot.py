#!/usr/bin/env python3
"""Packs a kernel + ramdisk + DTB into the SPRD boot image layout used by the
iBall Slide Skye 03 bootloader: Android v0 header (copied from a stock image
for the load addresses), then the DT region: a 0x800-byte "SPRD" header and the
DTB padded to 0x1b000 bytes.
usage: pack_sprd_boot.py <stock.img> <kernel> <ramdisk> <dtb> <cmdline> <out.img>"""
import hashlib, struct, sys

stock, kern, rd, dtb, cmdline, out = sys.argv[1:]
hdr = bytearray(open(stock, 'rb').read(2048))
page = struct.unpack('<I', hdr[36:40])[0]
k, r, d = (open(p, 'rb').read() for p in (kern, rd, dtb))
DT_BODY = 0x1b000
assert len(d) <= DT_BODY, 'dtb too large'
dt = bytearray(0x800)
dt[0:36] = b'SPRD' + struct.pack('<8I', 1, 1, 7731, 1, 0x20000, 0x800, DT_BODY, 0)
dt += d + bytes(DT_BODY - len(d))

pad = lambda b: b + bytes(-len(b) % page)
struct.pack_into('<I', hdr, 8, len(k))
struct.pack_into('<I', hdr, 16, len(r))
struct.pack_into('<I', hdr, 24, 0)
struct.pack_into('<I', hdr, 40, len(dt))
c = cmdline.encode()
assert len(c) < 512
hdr[64:64 + 512] = c + bytes(512 - len(c))
sha = hashlib.sha1()
for blob in (k, r, b'', dt):
    sha.update(blob)
    sha.update(struct.pack('<I', len(blob)))
hdr[576:576 + 32] = sha.digest() + bytes(12)
open(out, 'wb').write(pad(bytes(hdr)) + pad(k) + pad(r) + pad(bytes(dt)))
