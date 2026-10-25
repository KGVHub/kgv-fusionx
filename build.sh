#!/usr/bin/env bash
# KGV FusionX (LineageOS 20, Redmi Note 4 "mido") build for a 7-8 GB RAM host
# WITHOUT a swap file (never add one on this machine). Memory strategy:
#   1. zstd zram swap at 50% of RAM (system-wide, /etc/default/zramswap); checked below.
#   2. One build job by default (JOBS=1); raise only for C-heavy stages.
#   3. Cap every JVM (metalava, R8/D8, javac, kotlinc, dex2oat via Soong).
#   4. Skip API doc/check generation (metalava is the largest RAM user).
#   5. The build is the OOM killer's first victim (oom_score_adj 500), not the
#      desktop or this shell.
#   6. Staged: soong/kati graph first (STAGE=graph), then the image.
# usage: [JOBS=n] [STAGE=graph|full|<target>] [DEVICE=mido] [SRC=<tree>] <this dir>/build.sh
#        NO_PRUNE=1 keeps the whole tree visible; SKIP_HOST_CHECKS=1 skips the host checks.
set -euo pipefail
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)

# Host checks (once, before the memory-capped scope): what this build needs on a
# 7-8 GB machine. SKIP_HOST_CHECKS=1 skips them.
if [ -z "${KGV_IN_SCOPE:-}" ] && [ "${SKIP_HOST_CHECKS:-}" != 1 ]; then
  fail() { echo "build.sh: $*" >&2; echo "  (SKIP_HOST_CHECKS=1 to build anyway)" >&2; exit 1; }
  mem_kb=$(awk '/^MemTotal/{print $2}' /proc/meminfo)
  swap_kb=$(awk '/^SwapTotal/{print $2}' /proc/meminfo)
  [ "$mem_kb" -ge 6800000 ] || fail "needs about 7 GB of RAM or more (found $((mem_kb/1024)) MB)."
  # soong_build peaks at about 6.1 GB inside a 6.0 GB cap: the rest goes to compressed swap.
  if [ "$mem_kb" -lt 15000000 ] && [ "$swap_kb" -lt 2000000 ]; then
    fail "with under 16 GB of RAM, needs 2 GB or more of zram swap (found $((swap_kb/1024)) MB).
  Ubuntu/Debian: sudo apt install zram-tools, set ALGO=zstd and PERCENT=50 in
  /etc/default/zramswap, then sudo systemctl restart zramswap. Don't use a disk swap file."
  fi
  for lib in libncurses.so.5 libtinfo.so.5; do
    ldconfig -p 2>/dev/null | grep -q "$lib" || [ -e "/usr/lib/x86_64-linux-gnu/$lib" ] || \
      fail "llvm-rs-cc needs $lib. On distros with only ncurses 6:
  sudo ln -s libncurses.so.6 /usr/lib/x86_64-linux-gnu/libncurses.so.5
  sudo ln -s libtinfo.so.6 /usr/lib/x86_64-linux-gnu/libtinfo.so.5"
  done
  if [ "${MEM_LIMIT:-6000M}" != none ] && ! systemd-run --user --scope --quiet true 2>/dev/null; then
    fail "needs systemd user sessions (systemd-run --user) for the memory cap. Log in to a
  normal desktop/SSH session, or run with MEM_LIMIT=none (no cap: a 16 GB host only)."
  fi
fi

# Hard memory budget: run inside a cgroup capped at MEM_LIMIT (zram swap capped at SWAP_MAX),
# so a breach kills only the build. MEM_LIMIT=none disables it.
# The build also gets the lowest CPU and disk priority so the desktop stays
# usable while it runs.
MEM_LIMIT=${MEM_LIMIT:-6000M}
if [ "$MEM_LIMIT" != none ] && [ -z "${KGV_IN_SCOPE:-}" ]; then
  exec systemd-run --user --scope --quiet -p MemoryMax="$MEM_LIMIT" -p MemoryHigh="${MEM_HIGH:-5700M}" -p MemorySwapMax="${SWAP_MAX:-2G}" \
       -p CPUWeight=10 -p IOWeight=10 \
       nice -n 19 ionice -c 2 -n 7 env KGV_IN_SCOPE=1 "$0" "$@"
fi
# The source tree: $SRC if set, else the current directory when it is a tree (synced
# with manifest/kgv_fusionx.xml, run as `kgv/build.sh` from the top), else ../fusionx.
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
if [ -z "${SRC:-}" ]; then
  if [ -f "$PWD/build/envsetup.sh" ]; then SRC=$PWD; else SRC=$ROOT/fusionx; fi
fi
JOBS=${JOBS:-1}
STAGE=${STAGE:-full}
DEVICE=${DEVICE:-mido}          # lunch lineage_$DEVICE-userdebug
cd "$SRC"
[ -f build/envsetup.sh ] || { echo "build.sh: $SRC is not an Android tree (no build/envsetup.sh)" >&2; exit 1; }

# Hide source the ROM never builds from Soong, every time (repo sync keeps the
# markers, and new projects get them). This is a large part of the RAM savings.
[ "${NO_PRUNE:-}" = 1 ] || SRC=$SRC "$HERE/prune-tree.sh" >/dev/null

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
# No 32-bit x86 host variants (build/make patch); fewer Soong modules.
export HOST_2ND_ARCH_OVERRIDE=none
# soong_build (Go) peaked at 4.7 GB RSS and was OOM-killed on 7.2 GB RAM.
# A soft memory limit makes Go's GC work harder instead of growing the heap.
# GOMEMLIMIT needs Go 1.19+; this tree ships Go 1.18, so only GOGC applies.
export GOMEMLIMIT=${GOMEMLIMIT:-3GiB}
export GOGC=${GOGC:-25}
# Serial GC: much less memory beyond the heap than G1 for R8, D8, javac,
# metalava and the other Java build tools.
export JAVA_TOOL_OPTIONS=${JAVA_TOOL_OPTIONS:--XX:+UseSerialGC}
# Fewer parallel mutator goroutines keep less transient garbage alive.
export GOMAXPROCS=${GOMAXPROCS:-4}
# Render and compress each module's ninja actions as soon as they are
# generated (build/blueprint patch); the parsed form dominated the heap.
export BLUEPRINT_COMPACT_ACTIONS=${BLUEPRINT_COMPACT_ACTIONS:-1}
# Never create test, benchmark and fuzz modules (build/blueprint patch): no
# image installs them and they were about 12k of the 91k module variants.
SKIP_TYPES="cc_test cc_test_host cc_benchmark cc_benchmark_host cc_fuzz
  cc_prebuilt_test_library_shared art_cc_test art_cc_test_library art_apex_test_host apex_test
  rust_test rust_test_host rust_benchmark rust_benchmark_host rust_fuzz sh_test sh_test_host
  java_test java_test_host java_test_import java_test_helper_library java_fuzz_host
  android_test android_test_import android_test_helper_app android_robolectric_test
  override_android_test python_test python_test_host csuite_test"
export BLUEPRINT_SKIP_MODULE_TYPES=$(echo $SKIP_TYPES | tr ' ' ,)
# No control-flow-integrity variants (6.6k module variants); CFI checks also
# cost runtime speed on a phone this old.
export ENABLE_CFI=false

# envsetup/lunch rely on unset variables
set +u
source build/envsetup.sh
lunch "lineage_${DEVICE}-userdebug"
case "$STAGE" in
  graph) m -j"$JOBS" nothing ;;          # soong + kati only (peak RAM ~4-5 GB)
  full)
    # Fresh build date and number in every image: the build doesn't regenerate
    # build.prop when only the date changes, and Android keys its parsed-package
    # cache on them, so stale app info would otherwise survive a flash.
    find "out/target/product/$DEVICE" -maxdepth 3 -name build.prop -delete 2>/dev/null || true
    rm -rf "out/target/product/$DEVICE"/obj/ETC/*build.prop_intermediates \
           "out/target/product/$DEVICE"/obj/PACKAGING/*build_prop_intermediates 2>/dev/null || true
    m -j"$JOBS" bacon ;;                 # flashable zip
  *)     m -j"$JOBS" "$STAGE" ;;          # single module/target
esac
