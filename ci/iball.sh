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
cd /mypool/claude/fusionx
rsync -a --delete /home/kgvarunkanth/claude/fusionx-port/device/iball/Slide_Skye_03/ device/iball/Slide_Skye_03/
DEVICE=Slide_Skye_03 STAGE=${STAGE:-kgv-images} JOBS=${JOBS:-4} /home/kgvarunkanth/claude/fusionx-port/build.sh
P=out/target/product/Slide_Skye_03
device/iball/Slide_Skye_03/tools/pack-boot.sh $P $P/boot-sprd.img
for f in system.img vendor.img boot-sprd.img; do if [ -f $P/$f ]; then sha256sum $P/$f; else echo "missing: $f"; fi; done
