#!/bin/bash
# Build the out-of-tree pike2 drivers from sprd_bsp_modules against the 4.14
# kernel in out-414: Mali r28p0 (pike2), WCN Wi-Fi (sc2332), BT and FM (sipc).
# Output: /mypool/claude/mainline/a11/modules-414/*.ko
set -u
A=/mypool/claude/mainline/a11
# K414_SRC/K414_OUT/K414_MODS select another tree (e.g. the 4.14 LTS merge).
K=${K414_SRC:-$A/kernel-unisoc-4.14}
O=${K414_OUT:-$A/out-414}
S=$A/sprd_bsp_modules
OUT=${K414_MODS:-$A/modules-414}; mkdir -p $OUT; rm -f $OUT/*.ko
STAMP=$(mktemp); trap 'rm -f $STAMP' EXIT
export PATH=/mypool/claude/fusionx/prebuilts/clang/host/linux-x86/clang-r450784d/bin:/mypool/claude/fusionx/prebuilts/gcc/linux-x86/arm/arm-linux-androideabi-4.9/bin:$PATH
export KCFLAGS="-gdwarf-4 -fno-dwarf-directory-asm -fno-addrsig -Wno-error"
M="make -C $K O=$O ARCH=arm CC=clang CLANG_TRIPLE=arm-linux-gnueabi- CROSS_COMPILE=arm-linux-androideabi- -j6"
[ "${K414_LD:-}" = bfd ] && M="$M LD=arm-linux-androideabi-ld.bfd"
fail=0
build() { # name dir extra-args...
  local name=$1 dir=$2; shift 2
  echo "== $name"
  if $M M=$dir "$@" modules > $A/mod-$name.log 2>&1; then
    find $dir -name '*.ko' -exec cp {} $OUT/ \; ; echo "   OK"
  else
    echo "   FAILED (log: $A/mod-$name.log)"; grep -E ' error:|Error [0-9]' $A/mod-$name.log | sort -u | head -15; fail=1
  fi
}
build mali $S/gpu/midgard/mali CONFIG_MALI_MIDGARD=m CONFIG_MALI_PLATFORM_NAME=pike2 \
  CONFIG_MALI_BOARD_PLATFORM=sp7731e CONFIG_TARGET_BOARD=sp7731e_1h10 BUILD=no
build wlan $S/wcn/wlan BSP_BOARD_WLAN_DEVICE=sc2332
build bt $S/wcn/bluetooth/driver BSP_BOARD_UNISOC_WCN_SOCKET=sipc
build fm $S/wcn/fm/driver BSP_BOARD_UNISOC_WCN_SOCKET=sipc
echo "== modules:"; ls -la $OUT
for k in $OUT/*.ko; do [ -f "$k" ] && echo "$(basename $k): $(modinfo -F vermagic $k)"; done
exit $fail
