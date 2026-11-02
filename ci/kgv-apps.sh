#!/bin/bash
# KGV apps for mido, without the ROM image: KGV X Intelligence, its stock-ROM test
# build and kgv_rootd. Builds in the mido tree on the SSD (the working tree as it is),
# and copies the test APK to $DIST (served over Tailscale for installing on test
# phones).
# Params: TARGETS (space separated), JOBS.
set -euo pipefail
# CI starts steps with a minimal environment; build.sh's memory cap
# (systemd-run --user) needs the user session bus.
export XDG_RUNTIME_DIR=/run/user/$(id -u)
export DBUS_SESSION_BUS_ADDRESS=unix:path=$XDG_RUNTIME_DIR/bus
# 11 GB host: let the build use RAM up to 8.5 GB before its cgroup swaps to zram.
export MEM_HIGH=${MEM_HIGH:-8500M} MEM_LIMIT=${MEM_LIMIT:-9000M}
export JAVA_HEAP=${JAVA_HEAP:-3g}
DIST=${DIST:-/home/kgvarunkanth/claude/kgv-dist}
cd /home/kgvarunkanth/claude/fusionx
for repo in packages/apps/KGVIntelligence device/xiaomi/mido; do
  echo "$repo: $(git -C "$repo" log --oneline -1)$(git -C "$repo" diff --quiet HEAD || echo ' (+ uncommitted changes)')"
done
DEVICE=mido STAGE="${TARGETS:-KGVIntelligence KGVIntelligenceTest kgv_rootd}" JOBS=${JOBS:-4} \
  /home/kgvarunkanth/claude/fusionx-port/build.sh
P=out/target/product/mido
apk=$(find "$P/system" -name KGVIntelligenceTest.apk | head -1)
if [ -n "$apk" ]; then
  mkdir -p "$DIST"
  name=KGVIntelligenceTest-$(git -C packages/apps/KGVIntelligence log -1 --format=%cd-%h --date=format:%Y%m%d-%H%M).apk
  cp "$apk" "$DIST/$name"
  ln -sfn "$name" "$DIST/KGVIntelligenceTest-latest.apk"
  sha256sum "$DIST/$name"
fi
