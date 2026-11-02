#!/bin/bash
# KGV FusionX for the Redmi Note 4 (mido).
set -euo pipefail
# CI starts steps with a minimal environment; build.sh's memory cap
# (systemd-run --user) needs the user session bus.
export XDG_RUNTIME_DIR=/run/user/$(id -u)
export DBUS_SESSION_BUS_ADDRESS=unix:path=$XDG_RUNTIME_DIR/bus
# 11 GB host: let the build use RAM up to 8.5 GB before its cgroup swaps to zram.
export MEM_HIGH=${MEM_HIGH:-8500M} MEM_LIMIT=${MEM_LIMIT:-9000M}
export JAVA_HEAP=${JAVA_HEAP:-3g}
# The mido tree on the SSD (not the /mypool HDD tree).
cd /home/kgvarunkanth/claude/fusionx
DEVICE=mido STAGE=${STAGE:-full} JOBS=${JOBS:-4} /home/kgvarunkanth/claude/fusionx-port/build.sh
P=out/target/product/mido
for f in system.img $(ls -t $P/*.zip 2>/dev/null | head -1 | xargs -r basename); do
  if [ -f "$P/$f" ]; then sha256sum "$P/$f"; fi
done
