#!/bin/bash
# Build the Unisoc 4.14 BSP kernel for pike2: Nokia C1 2E config + KGV fragment.
# Output: /mypool/claude/mainline/a11/out-414/arch/arm/boot/Image and dtbs.
set -eu
A=/mypool/claude/mainline/a11
K=$A/kernel-unisoc-4.14
O=$A/out-414
cd "$K"
git rev-parse --verify -q kgv/pike2-4.14 >/dev/null || git checkout -q -b kgv/pike2-4.14
mkdir -p "$O"
cp $A/nokia_iru_dump/ikconfig "$O/.config"
# Apply the fragment: drop any existing line for each option, then append ours.
python3 - "$O/.config" "$A/pike2-kgv.fragment" <<'EOF'
import re, sys
cfg, frag = sys.argv[1], sys.argv[2]
want = [l.rstrip('\n') for l in open(frag) if re.match(r'(CONFIG_\w+=|# CONFIG_\w+ is not set)', l)]
names = {re.search(r'CONFIG_\w+', l).group(0) for l in want}
keep = [l for l in open(cfg) if not (m := re.search(r'CONFIG_\w+', l)) or m.group(0) not in names]
open(cfg, 'w').write(''.join(keep) + '\n'.join(want) + '\n')
EOF
export PATH=/mypool/claude/fusionx/prebuilts/clang/host/linux-x86/clang-r450784d/bin:/mypool/claude/fusionx/prebuilts/gcc/linux-x86/arm/arm-linux-androideabi-4.9/bin:$PATH
# clang 14 + the GCC 4.9 GNU assembler: avoid DWARF 5 .file/md5 and .addrsig,
# which that assembler can't parse.
M="make O=$O ARCH=arm CC=clang CLANG_TRIPLE=arm-linux-gnueabi- CROSS_COMPILE=arm-linux-androideabi-"
export KCFLAGS="-gdwarf-4 -fno-dwarf-directory-asm -fno-addrsig -Wno-error"
$M olddefconfig >/dev/null 2>&1
echo "== fragment check (lines missing from the final config):"
grep -E '^(CONFIG_|# CONFIG_)' $A/pike2-kgv.fragment | while read -r l; do grep -qxF "$l" "$O/.config" || echo "  $l"; done
echo "== building Image + dtbs (log: $A/build-414.log)"
if $M -k -j6 Image dtbs > $A/build-414.log 2>&1; then echo "BUILD OK"; else echo "BUILD FAILED"; fi
grep -E ' error:|Error [0-9]' $A/build-414.log | sort -u | head -40
ls -la "$O/arch/arm/boot/Image" 2>/dev/null; ls "$O/arch/arm/boot/dts/" 2>/dev/null | grep -iE '7731|pike' || true

echo "== merged iBall DTB"
bash /mypool/claude/mainline/a11/dtb-iball.sh 2>&1 | tail -8
