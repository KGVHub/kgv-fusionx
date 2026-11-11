#!/bin/bash
# Flash the 4.14 test image to the iBall's RECOVERY partition (boot untouched),
# reboot into it and wait for TWRP's adb. Restore: bash t414.sh restore
set -u
A="adb -s 1900567000077"; W=/mypool/claude/mainline/a11/rec414
[ "$($A shell getprop ro.product.device 2>/dev/null | tr -d '\r')" = Slide_Skye_03 ] || { echo "iBall not attached in Android; aborting"; exit 1; }
IMG=$W/recovery-414.img; [ "${1:-}" = restore ] && IMG=$W/recovery-live-backup.img
$A push "$IMG" /data/local/tmp/rec.img | tail -1
$A shell 'dd if=/data/local/tmp/rec.img of=/dev/block/by-name/recovery bs=1048576 2>&1 | tail -1; sync; rm /data/local/tmp/rec.img'
want=$(sha256sum "$IMG" | cut -c1-64); got=$($A shell 'sha256sum /dev/block/by-name/recovery' | cut -c1-64)
[ "$want" = "$got" ] && echo "recovery written, checksum OK" || { echo "CHECKSUM MISMATCH"; exit 1; }
[ "${1:-}" = restore ] && exit 0
$A reboot recovery
for i in $(seq 1 60); do s=$($A get-state 2>/dev/null); [ "$s" = recovery ] && break; sleep 5; done
echo "$(date +%T) state=$($A get-state 2>&1)"
$A shell 'uname -a; cat /proc/cmdline; dmesg' > $W/boot414-dmesg.txt 2>&1 && head -3 $W/boot414-dmesg.txt
