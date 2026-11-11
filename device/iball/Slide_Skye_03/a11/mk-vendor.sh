#!/bin/bash
# Builds the A11-mode vendor image: the Nokia C1 2nd Edition (IRU, SC7731E,
# Android 11) vendor, adapted for the iBall Slide Skye 03 on kernel 4.14:
#  - kernel modules from prebuilt/k414/modules in /vendor/socko, with
#    ro.vendor.ko.mount.point=/vendor (Nokia loads them from a socko partition)
#  - the iBall fstab (fixed partitions, unencrypted data) as fstab.sp7731e_1h10
# Output: a sparse ext4 image for BOARD_PREBUILT_VENDORIMAGE.
# usage: mk-vendor.sh <nokia vendor dir> <host bin dir> <out vendor.img>
set -euo pipefail
SRC=$1 HOST=$2 OUT=$3
DEV=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkdir -p "$T/vendor"
cp -a "$SRC/." "$T/vendor/"
V=$T/vendor
# Size: Nokia's vendor is 239 MB and the iBall partition is 200 MB. Drop the
# Unisoc face unlock (52 MB library + its lazy HAL) and a 19 MB CJK font that
# only Unisoc apps use.
rm -f "$V/lib/libsprdfaceid.so" "$V/lib/hw/face.default.so" \
      "$V/bin/hw/vendor.sprd.hardware.face@1.0-service-lazy" \
      "$V/etc/init/vendor.sprd.hardware.face@1.0-service-lazy.rc" \
      "$V/etc/vintf/manifest/manifest_face.xml" \
      "$V/etc/NotoSansCJK-Regular-sprd.ttc"
# kernel modules
mkdir -p "$V/socko"
cp "$DEV"/prebuilt/k414/modules/*.ko "$V/socko/"
sed -i 's#^ro.vendor.ko.mount.point=.*#ro.vendor.ko.mount.point=/vendor#' "$V/build.prop"
grep -q '^ro.vendor.ko.mount.point=/vendor$' "$V/build.prop"
# fstab
cp "$DEV/a11/fstab.sp7731e_1h10" "$V/etc/fstab.sp7731e_1h10"
# file contexts for the new files: socko modules are vendor_file like lib/modules
FC=$T/file_contexts
# The generic /vendor labels live in the platform's plat_file_contexts; put
# them first so Nokia's more specific entries win (the last match wins).
PLAT=${PLAT_FILE_CONTEXTS:-$HOST/../../../target/product/Slide_Skye_03/system/etc/selinux/plat_file_contexts}
grep -F '/(vendor|system/vendor)' "$PLAT" > "$FC"
[ "$(wc -l < "$FC")" -gt 10 ]
cat "$V/etc/selinux/vendor_file_contexts" >> "$FC"
echo '/vendor/socko(/.*)?  u:object_r:vendor_file:s0' >> "$FC"
SIZE=209715200
# leave room for the AVB hashtree + FEC that sign-images.sh adds
# --product_out: e2fsdroid applies Nokia's vendor/etc/fs_config_{dirs,files}
# (owners, modes, capabilities) on top of the built-in defaults.
PATH=$HOST:$PATH "$HOST/mkuserimg_mke2fs" -s -j 0 -D "$T" -L vendor -T 1230768000 \
    "$T/vendor" "$OUT" ext4 /vendor $((SIZE - 8 * 1024 * 1024)) "$FC"
ls -la "$OUT"
