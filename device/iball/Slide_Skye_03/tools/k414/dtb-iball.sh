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
# No UART console: nothing reads it and 115200-baud printk slows boot; with
# loglevel=7 the full log still reaches the pstore console (console-ramoops).
STOCK="loglevel=7 init=/init root=/dev/ram0 rw androidboot.hardware=sp7731e_1h10"
fdtput -c $W/iball.dtb /chosen 2>/dev/null || true
# panic=5: restart on a panic; selinux permissive and k414=1 for the 4.4 ROM
# on 4.14 (gfx414/k414.rc). The bootloader uses these, not the header cmdline.
fdtput -t s $W/iball.dtb /chosen bootargs "$STOCK lcd_name=lcd_kgv_iball_mipi panic=5 androidboot.selinux=permissive androidboot.k414=1"
ls -la $W/iball.dtb
fdtget $W/iball.dtb / model
fdtget $W/iball.dtb /chosen bootargs
fdtget -l $W/iball.dtb /lcds
