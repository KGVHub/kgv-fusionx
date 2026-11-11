#!/bin/bash
# Build one merged DTB for the iBall: sp7731e base + sp7731e-1h10-iball overlay.
# The 8.1 bootloader takes a single DTB from the boot image and boots with its
# /chosen/bootargs, so lcd_name (4.14 panel selection) goes there too.
set -eu
A=/mypool/claude/mainline/a11
K=$A/kernel-unisoc-4.14
O=$A/out-414
W=$A/dtb-iball; mkdir -p $W
DTS=$K/arch/arm/boot/dts
DTC=$O/scripts/dtc/dtc
CPP="clang -E -nostdinc -undef -D__DTS__ -x assembler-with-cpp -I$DTS -I$DTS/include -I$K/include -I$K/scripts/dtc/include-prefixes"
export PATH=/mypool/claude/fusionx/prebuilts/clang/host/linux-x86/clang-r450784d/bin:$PATH
$CPP -o $W/base.pre $DTS/sp7731e.dts
$CPP -o $W/ovl.pre $DTS/sp7731e-1h10-iball-overlay.dts
$DTC -@ -q -I dts -O dtb -o $W/base.dtb $W/base.pre
$DTC -@ -q -I dts -O dtb -o $W/ovl.dtbo $W/ovl.pre
fdtoverlay -i $W/base.dtb -o $W/iball.dtb $W/ovl.dtbo
# bootargs: the stock ones plus the 4.14 panel name.
STOCK="earlycon=sprd_serial,0x70100000,115200n8 loglevel=1 console=ttyS1,115200n8 init=/init root=/dev/ram0 rw androidboot.hardware=sp7731e_1h10"
fdtput -c $W/iball.dtb /chosen 2>/dev/null || true
fdtput -t s $W/iball.dtb /chosen bootargs "$STOCK lcd_name=lcd_kgv_iball_mipi"
ls -la $W/iball.dtb
fdtget $W/iball.dtb / model
fdtget $W/iball.dtb /chosen bootargs
fdtget -l $W/iball.dtb /lcds
