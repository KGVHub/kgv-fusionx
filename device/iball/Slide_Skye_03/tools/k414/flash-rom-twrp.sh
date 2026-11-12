#!/bin/bash
# Flash the latest FusionX system + vendor images from the 4.14 TWRP, streaming
# them over adb (TWRP's /tmp is too small for a 1.3 GB image), then reboot.
set -euo pipefail
A="adb -s 1900567000077"
P=/mypool/claude/fusionx/out/target/product/Slide_Skye_03
M=/tmp/claude-1000/-mypool-claude/cb00e309-7f00-42ac-9c63-a73fd3d86562/scratchpad/misc-recovery.img
if [ "$($A get-state)" = device ]; then
    $A root >/dev/null 2>&1 || true; sleep 3; $A wait-for-device
    $A push $M /data/local/tmp/misc.img >/dev/null
    $A shell 'dd if=/data/local/tmp/misc.img of=/dev/block/by-name/misc bs=2048 count=1 2>/dev/null; sync; echo b > /proc/sysrq-trigger' || true
    echo "$(date +%T) rebooting to TWRP"
    sleep 10
fi
until [ "$($A get-state 2>/dev/null)" = recovery ]; do sleep 2; done; sleep 5
echo "$(date +%T) in TWRP"
$A shell 'umount /system /vendor /system_root 2>/dev/null; true'
# system: stream all but the last 32 MB (adb exec-in drops up to ~10 MB at the end
# when the stream closes), then push and write the tail. vendor fits in /tmp.
img=$P/system-signed.img; size=$(stat -c %s $img); tailmb=32
head -c $((size - tailmb*1048576)) $img | $A exec-in "dd of=/dev/block/by-name/system bs=1048576"
T=$(mktemp); tail -c $((tailmb*1048576)) $img > $T; $A push $T /tmp/sys-tail.bin >/dev/null; rm -f $T
$A shell "dd if=/tmp/sys-tail.bin of=/dev/block/by-name/system bs=1048576 seek=$((size/1048576 - tailmb)) 2>/dev/null; rm /tmp/sys-tail.bin; sync"
$A push $P/vendor-signed.img /tmp/vendor.img >/dev/null
$A shell 'dd if=/tmp/vendor.img of=/dev/block/by-name/vendor bs=1048576 2>/dev/null; rm /tmp/vendor.img; sync'
for p in system vendor; do
    want=$(sha256sum $P/$p-signed.img | cut -c1-64)
    got=$($A shell "sha256sum /dev/block/by-name/$p" | cut -c1-64)
    [ "$want" = "$got" ] && echo "$(date +%T) $p written, checksum OK" || { echo "$p CHECKSUM MISMATCH"; exit 1; }
done
$A shell 'dd if=/dev/zero of=/dev/block/by-name/misc bs=2048 count=1 2>/dev/null; sync'
$A reboot
echo "$(date +%T) rebooting into the new ROM"
