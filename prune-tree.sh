#!/usr/bin/env bash
# Hide source trees a mido ROM never builds from Soong/Kati (".find-ignore"
# markers; no files are deleted). Smaller module graph -> less soong_build RAM.
# usage: prune-tree.sh [--undo]
set -euo pipefail
SRC=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../fusionx" && pwd)
DIRS="cts platform_testing device/google hardware/google
      device/generic/goldfish device/generic/goldfish-opengl device/generic/car
      device/generic/trusty external/crosvm development
      test/catbox test/cts-root test/framework test/mlts test/mts test/vts test/vts-testcase
      tools/tradefederation tools/asuite tools/test tools/acloud"
for d in $DIRS; do
    [ -d "$SRC/$d" ] || continue
    if [ "${1:-}" = --undo ]; then rm -f "$SRC/$d/.find-ignore"; echo "unpruned $d"
    else touch "$SRC/$d/.find-ignore"; echo "pruned   $d"; fi
done
# The finder caches its results; force a rescan.
rm -rf "$SRC/out/.module_paths" "$SRC/out/soong/.bootstrap/build-globs.ninja" 2>/dev/null || true
