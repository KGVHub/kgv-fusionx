#!/bin/bash
# Packs the build's ramdisk with the prebuilt KGV-Pro-X kernel and Spreadtrum
# DT region into the boot layout this bootloader accepts, then signs it with
# the AVB testkey that vbmeta-v3 chains boot to.
# usage: pack-boot.sh <out/target/product/Slide_Skye_03> <out.img>
set -euo pipefail
DEV=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
P=$1 OUT=$2
AVB=${AVBTOOL:-/home/kgvarunkanth/claude/tools/avb}
CMDLINE=$(sed -n 's/^BOARD_KERNEL_CMDLINE := //p' "$DEV/BoardConfig.mk")
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
# DT region -> plain DTB (offset at 0x18, size from the FDT header)
python3 - "$DEV/prebuilt/dt.img" "$T/dtb" <<'P'
import struct, sys
d = open(sys.argv[1], 'rb').read()
b = d[struct.unpack_from('<I', d, 24)[0]:]
open(sys.argv[2], 'wb').write(b[:struct.unpack_from('>I', b, 4)[0]])
P
python3 "$DEV/tools/pack_sprd_boot.py" "$DEV/prebuilt/boot-header.bin" "$DEV/prebuilt/zImage" \
    "$P/ramdisk.img" "$T/dtb" "$CMDLINE" "$OUT"
python3 "$AVB/avbtool.py" add_hash_footer --image "$OUT" --partition_name boot \
    --partition_size 36700160 --key "$AVB/testkey_rsa4096.pem" --algorithm SHA256_RSA4096
echo "wrote $OUT (cmdline: $CMDLINE)"
