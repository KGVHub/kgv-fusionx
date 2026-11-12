#!/bin/bash
# From the 4.14 TWRP: installs the stage.sh output into the vendor partition
# (/vendor/k414, the new libs into /vendor/lib, k414.rc into etc/init) with
# SELinux labels, flashes a 4.14 boot image, clears misc and reboots.
# The boot image's DTB must carry androidboot.k414=1 in /chosen/bootargs.
# usage: install-twrp.sh <stage dir> <boot image> [adb serial]
set -euo pipefail
ST=$1 BOOT=$2 A="adb -s ${3:-1900567000077}"
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
[ "$($A get-state)" = recovery ]
$A shell 'mkdir -p /tmp/v; mountpoint -q /tmp/v || mount -t ext4 -o rw /dev/block/by-name/vendor /tmp/v; rm -rf /tmp/v/k414'
$A push "$ST"/k414 /tmp/v/ >/dev/null
$A push "$ST"/lib-new/. /tmp/v/lib/ >/dev/null
$A push "$HERE"/k414.rc /tmp/v/etc/init/k414.rc >/dev/null
NEW=$(cd "$ST"/lib-new && ls | sed 's#^#lib/#' | tr '\n' ' ')
$A shell "cd /tmp/v; find k414 -type d -exec chmod 755 {} +; find k414 -type f -exec chmod 644 {} +
  chmod 644 etc/init/k414.rc $NEW
  chcon -R u:object_r:vendor_file:s0 k414 $NEW
  chcon u:object_r:same_process_hal_file:s0 k414/lib/hw/gralloc.sp7731e.so k414/lib/egl/libGLES_mali.so k414/lib/libmemion.so lib/libdrm.so
  chcon u:object_r:vendor_configs_file:s0 etc/init/k414.rc
  df -h /tmp/v | tail -1; sync"
$A shell 'umount /tmp/v'
$A push "$BOOT" /tmp/b.img >/dev/null
$A shell 'dd if=/tmp/b.img of=/dev/block/by-name/boot bs=1048576 2>&1 | tail -1; dd if=/dev/zero of=/dev/block/by-name/misc bs=2048 count=1 2>/dev/null; sync'
[ "$(sha256sum "$BOOT" | cut -c1-64)" = "$($A shell 'sha256sum /dev/block/by-name/boot' | cut -c1-64)" ]
echo "boot written, misc cleared; rebooting"
$A reboot
