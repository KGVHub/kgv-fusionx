# Recovery for KGV FusionX (mido)

## KGV TWRP (current choice, being tested)

`build-kgv-twrp.sh` builds `kgv-twrp/kgv-twrp-3.7.0_12-mido.img` from the unofficial
TWRP 3.7.0_12 A13 image for mido (alone0316, sha256 checked by the script). That image
brings a 4.9 kernel and Android 12/13 FBE decryption, but its partition table is for
"retrofit" ROMs (dynamic partitions inside system, /metadata on oem). The script swaps in
KGV FusionX's classic layout: system -> /system_root, vendor -> /vendor (cust partition),
no logical partitions, no /metadata on oem, no androidboot.super_partition, and starts
decryption without the dynamic-partitions condition.

RAM-tested (`fastboot boot`): unlocks /data (FBE v1 aes-256-xts), mounts /sdcard,
/system_root and /vendor.

## Others tried

- OrangeFox R11.1_4 (official): decrypts /data, but has no system entry in its fstab and
  its sideload screen got stuck with no cancel.
- TWRP 3.7.0_12 A13 unmodified: decrypts /data, but expects dynamic partitions (can't
  mount system; "wipe Metadata" would wipe oem).
- TWRP 3.7.0_9 (old): mounts system, but its 3.18 kernel can't mount /data read-write
  (ext4 project quota) or decrypt it.

## Notes

- `fastboot reboot recovery` isn't supported by mido's bootloader; use `adb reboot recovery`.
- Recovery uses USB id 18d1:2d08; Linux needs a udev rule for vendor 18d1.
