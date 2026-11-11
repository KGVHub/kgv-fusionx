#!/bin/bash
# KGV FusionX for the iBall Slide Skye 03.
# Params: STAGE (default kgv-images), JOBS (default 4).
set -euo pipefail
# CI starts steps with a minimal environment; build.sh's memory cap
# (systemd-run --user) needs the user session bus.
export XDG_RUNTIME_DIR=/run/user/$(id -u)
export DBUS_SESSION_BUS_ADDRESS=unix:path=$XDG_RUNTIME_DIR/bus
# 11 GB host: let the build use RAM up to 8.5 GB before its cgroup swaps to zram.
export MEM_HIGH=${MEM_HIGH:-8500M} MEM_LIMIT=${MEM_LIMIT:-9000M}
export JAVA_HEAP=${JAVA_HEAP:-3g}
cd /mypool/claude/fusionx
rsync -a --delete /home/kgvarunkanth/claude/fusionx-port/device/iball/Slide_Skye_03/ device/iball/Slide_Skye_03/
# A11=1: kernel 4.14 + the Nokia C1 2E Android 11 vendor (device a11/).
A11=${A11:-0}
P=out/target/product/Slide_Skye_03
if [ "$A11" = 1 ]; then
  mkdir -p vendor/iball/Slide_Skye_03_a11
  PLAT_FILE_CONTEXTS=$P/system/etc/selinux/plat_file_contexts \
    device/iball/Slide_Skye_03/a11/mk-vendor.sh /mypool/claude/mainline/a11/nokia_iru_dump/vendor \
    out/host/linux-x86/bin vendor/iball/Slide_Skye_03_a11/vendor.img
  export LUNCH_PRODUCT=lineage_Slide_Skye_03_a11 KGV_A11=1
fi
DEVICE=Slide_Skye_03 STAGE=${STAGE:-kgv-images} JOBS=${JOBS:-4} /home/kgvarunkanth/claude/fusionx-port/build.sh
device/iball/Slide_Skye_03/tools/pack-boot.sh $P $P/boot-sprd.img
device/iball/Slide_Skye_03/tools/sign-images.sh $P
for f in system-signed.img vendor-signed.img boot-sprd.img; do if [ -f $P/$f ]; then sha256sum $P/$f; else echo "missing: $f"; fi; done
