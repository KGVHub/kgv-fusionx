#!/usr/bin/env bash
# Hide source trees a mido ROM never builds from Soong/Kati (".find-ignore"
# markers; no files are deleted). Smaller module graph -> less soong_build RAM.
# usage: prune-tree.sh [--undo]
set -euo pipefail
SRC=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../fusionx" && pwd)
DIRS="cts/apps cts/common cts/development cts/helpers cts/hostsidetests cts/suite cts/tests cts/tools
      platform_testing device/google hardware/google
      device/generic/goldfish device/generic/goldfish-opengl device/generic/car
      device/generic/trusty external/crosvm
      development/apps development/build development/cmds development/gki development/gsi
      development/host development/ide development/python-packages development/samples
      development/sdk_overlay development/sys-img development/testrunner development/tools
      development/vendor_snapshot development/vndk
      test/catbox test/cts-root test/framework test/mlts test/mts test/vts
      tools/tradefederation tools/asuite tools/test tools/acloud
      frameworks/base/tests prebuilts/vndk
      hardware/qcom-caf/msm8996 hardware/qcom-caf/msm8998 hardware/qcom-caf/sdm660 hardware/qcom-caf/sdm845
      hardware/qcom-caf/sm8150 hardware/qcom-caf/sm8250 hardware/qcom-caf/sm8350 hardware/qcom-caf/sm8450 hardware/qcom-caf/sm8550"
for d in $DIRS; do
    [ -d "$SRC/$d" ] || continue
    if [ "${1:-}" = --undo ]; then rm -f "$SRC/$d/.find-ignore"; echo "unpruned $d"
    else touch "$SRC/$d/.find-ignore"; echo "pruned   $d"; fi
done
# The finder caches its results; force a rescan.
rm -rf "$SRC/out/.module_paths" "$SRC/out/soong/.bootstrap/build-globs.ninja" 2>/dev/null || true
