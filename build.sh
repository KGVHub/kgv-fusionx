#!/usr/bin/env bash
# KGV FusionX (LineageOS 20, Redmi Note 4 "mido") build for a 7-8 GB RAM host
# WITHOUT a swap file (never add one on this machine). Memory strategy:
#   1. zstd zram at 100% of RAM (system-wide, /etc/default/zramswap).
#   2. One build job by default (JOBS=1); raise only for C-heavy stages.
#   3. Cap every JVM (metalava, R8/D8, javac, kotlinc, dex2oat via Soong).
#   4. Skip API doc/check generation (metalava is the largest RAM user).
#   5. The build is the OOM killer's first victim (oom_score_adj 500), not the
#      desktop or this shell.
#   6. Staged: soong/kati graph first (STAGE=graph), then the image.
# usage: [JOBS=n] [STAGE=graph|full|<target>] fusionx-port/build.sh
set -euo pipefail
# Hard memory budget: run inside a cgroup capped at MEM_LIMIT with no swap,
# so a breach kills only the build. MEM_LIMIT=none disables it.
MEM_LIMIT=${MEM_LIMIT:-5G}
if [ "$MEM_LIMIT" != none ] && [ -z "${KGV_IN_SCOPE:-}" ]; then
  exec systemd-run --user --scope --quiet -p MemoryMax="$MEM_LIMIT" -p MemorySwapMax=0 \
       env KGV_IN_SCOPE=1 "$0" "$@"
fi
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
SRC=$ROOT/fusionx
JOBS=${JOBS:-1}
STAGE=${STAGE:-full}
cd "$SRC"

echo 500 > /proc/self/oom_score_adj
export LC_ALL=C
# Pruned trees (prune-tree.sh) leave dangling references from modules we
# never build; tolerate them. Anything actually built still needs its deps.
export ALLOW_MISSING_DEPENDENCIES=true
# JVM heaps: small enough for 8 GB, big enough not to thrash
export _JAVA_OPTIONS="-Xmx1536m -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
export JAVA_TOOL_OPTIONS="$_JAVA_OPTIONS"
export ANDROID_JACK_VM_ARGS="-Xmx1536m"
# Metalava/doclava: do not regenerate or check public API stubs
export WITHOUT_CHECK_API=true
export DISABLE_STUB_VALIDATION=true
export SKIP_ABI_CHECKS=true
# Fewer heavy optional steps
export TARGET_BUILD_WITH_LTO=false
export USE_CCACHE=0
# No Windows cross-host variants of host tools (build-make-host-cross-none.patch)
export HOST_CROSS_OS=none
# soong_build (Go) peaked at 4.7 GB RSS and was OOM-killed on 7.2 GB RAM.
# A soft memory limit makes Go's GC work harder instead of growing the heap.
# GOMEMLIMIT needs Go 1.19+; this tree ships Go 1.18, so only GOGC applies.
export GOMEMLIMIT=${GOMEMLIMIT:-3GiB}
export GOGC=${GOGC:-25}

# envsetup/lunch rely on unset variables
set +u
source build/envsetup.sh
lunch lineage_mido-userdebug
case "$STAGE" in
  graph) m -j"$JOBS" nothing ;;          # soong + kati only (peak RAM ~4-5 GB)
  full)  m -j"$JOBS" bacon ;;            # flashable zip
  *)     m -j"$JOBS" "$STAGE" ;;          # single module/target
esac
