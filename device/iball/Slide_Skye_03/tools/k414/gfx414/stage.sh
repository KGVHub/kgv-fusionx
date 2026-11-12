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
cp "$V"/libmemion.so "$OUT"/k414/lib/
# New to the stock vendor: hwcomposer needs libdrm and the Unisoc power-hint
# client (and its AIDL stubs; the power service itself isn't there).
cp "$V"/libdrm.so "$V"/libpowerhal_cli.so "$V"/vendor.unisoc.hardware.power-V1-ndk_platform.so "$OUT"/lib-new/
cp "$N"/system/system/lib/android.hardware.power-V1-ndk_platform.so "$OUT"/lib-new/
# sprdwl_ng panics in sprdwl_sipc_msg_send on 4.14; left out until fixed.
for m in "$MODS"/*.ko; do
    [ "$(basename "$m")" = sprdwl_ng.ko ] && continue
    "$STRIP" --strip-debug -o "$OUT/k414/modules/$(basename "$m")" "$m"
done
find "$OUT" -type f | sort
