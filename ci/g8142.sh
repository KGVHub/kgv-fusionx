#!/bin/bash
# KGV FusionX for the Xperia XZ Premium Dual (G8142, maple_dsds) on SODP trees.
set -euo pipefail
# CI starts steps with a minimal environment; build.sh's memory cap
# (systemd-run --user) needs the user session bus.
export XDG_RUNTIME_DIR=/run/user/$(id -u)
export DBUS_SESSION_BUS_ADDRESS=unix:path=$XDG_RUNTIME_DIR/bus
# 11 GB host: cap the build at 7.5-8 GB so the rest of the PC stays responsive.
export MEM_HIGH=${MEM_HIGH:-7500M} MEM_LIMIT=${MEM_LIMIT:-8000M}
export JAVA_HEAP=${JAVA_HEAP:-2g}
cd /mypool/claude/fusionx
SRC=/mypool/claude/fusionx DEVICE=g8142 STAGE=${STAGE:-full} JOBS=${JOBS:-4} \
  /home/kgvarunkanth/claude/fusionx-port/build.sh 2>&1 | tee /mypool/claude/sony/sodp/build-full.log
