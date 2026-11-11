#!/bin/bash
# Write a recovery image back from SPD download mode (e.g. after a hung test
# kernel in recovery loops on every boot because misc says "recovery").
# Needs spd_dump built with spd_dump-line-state-nonfatal.patch, and the FDLs
# from the stock .pac. keep_charge 0 matters: with it on, FDL1 re-enumerates
# USB and the session drops.
# Steps: run this, then Power + RESET until the screen is black, then hold
# Vol Down and plug USB straight into the PC. It waits for a FRESH bootrom
# device (bcdDevice 2.02) so it never grabs a stale one.
# usage: unbrick-recovery.sh <recovery.img> [spd_dump] [fdl dir]
set -u
IMG=${1:?recovery image}
SPD=${2:-spd_dump}
FDL=${3:-/mypool/claude/mainline/fw/unpacked}
bootrom() { for d in /sys/bus/usb/devices/*; do
  [ "$(cat $d/idVendor 2>/dev/null)" = 1782 ] && [ "$(cat $d/bcdDevice 2>/dev/null)" = 0202 ] && cat $d/devnum
done; }
old=$(bootrom); echo "waiting for a fresh download-mode connection (existing: ${old:-none})"
for i in $(seq 1 3000); do
  cur=$(bootrom); [ -n "$cur" ] && [ "$cur" != "$old" ] && break
  [ -z "$cur" ] && old=gone; sleep 0.2
done
cd "$FDL" && "$SPD" --wait 30 keep_charge 0 \
  fdl fdl1-sign.bin 0x50002E00 fdl fdl2-sign.bin 0x9EFFFE00 exec skip_confirm 1 \
  write_part recovery "$IMG" reset
