#!/bin/bash
# Builds the /vendor/k414 overlay for running the 4.4 ROM (A13 + 8.1 vendor)
# on kernel 4.14: the Nokia C1 2E (Android 11) DRM hwcomposer, ION gralloc,
# Mali r28p0 GLES and libmemion (bind-mounted over the stock files by
# k414.rc), the libraries they add, and the 4.14 kernel modules.
# usage: stage.sh <nokia dump root> <4.14 modules dir> <out dir>
set -euo pipefail
N=$1 MODS=$2 OUT=$3
V=$N/vendor/lib
STRIP=${STRIP:-llvm-strip}
rm -rf "$OUT"; mkdir -p "$OUT"/k414/lib/hw "$OUT"/k414/lib/egl "$OUT"/k414/modules "$OUT"/lib-new
cp "$V"/hw/gralloc.sp7731e.so "$V"/hw/hwcomposer.sp7731e.so "$OUT"/k414/lib/hw/
cp "$V"/egl/libGLES_mali.so "$OUT"/k414/lib/egl/
# Display controller (DPU) and 2D engine (GSP) HALs for the DRM display.
# DpuLiteR1P0::prepare rejects every layer when more than 2 premultiplied
# layers are on screen (Android's layers all are), so the composer falls
# back to GPU composition for everything. Patch that limit (cmp.w r10, #2
# at 0x3d72) to the controller's 4 layers: wallpaper goes to GSP, the rest
# to DPU overlays (app-drawer scroll: median frame 42 -> 27 ms).
cp "$V"/hw/gsp.sp7731e.so "$OUT"/k414/lib/hw/
cp "$V"/hw/dpu.sp7731e.so "$OUT"/k414/lib/hw/dpu.sp7731e.orig.so
python3 - "$V"/hw/dpu.sp7731e.so "$OUT"/k414/lib/hw/dpu.sp7731e.so <<'PY'
import sys
d = bytearray(open(sys.argv[1], 'rb').read())
off = 0x3d72 - 0x1000          # .text is loaded at file offset + 0x1000
assert d[off:off + 4] == bytes.fromhex('baf1020f'), 'unexpected dpu.sp7731e.so'
d[off + 2] = 0x04              # cmp.w r10, #2 -> #4
open(sys.argv[2], 'wb').write(d)
PY
cp "$V"/libmemion.so "$OUT"/k414/lib/
# New to the stock vendor: hwcomposer needs libdrm and the Unisoc power-hint
# client (and its AIDL stubs; the power service itself isn't there).
cp "$V"/libdrm.so "$V"/libpowerhal_cli.so "$V"/vendor.unisoc.hardware.power-V1-ndk_platform.so "$OUT"/lib-new/
cp "$N"/system/system/lib/android.hardware.power-V1-ndk_platform.so "$OUT"/lib-new/
for m in "$MODS"/*.ko; do
    "$STRIP" --strip-debug -o "$OUT/k414/modules/$(basename "$m")" "$m"
done
find "$OUT" -type f | sort
