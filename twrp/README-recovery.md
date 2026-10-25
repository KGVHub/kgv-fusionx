# Recovery for KGV FusionX (mido)

Use **OrangeFox R11.1_4 Stable (unified)**, official build for mido, released 2024-05-21:
https://orangefox.download/release/664c9e7e84cde7c4d32d3cc9
(zip sha256 in candidates/ofox/SHA256SUMS). Flash only its `recovery.img`:

    fastboot flash recovery recovery.img

It runs a 4.9 kernel and decrypts KGV FusionX's FBE (v1, aes-256-xts) /data, so
/data files can be fixed from recovery. The older TWRP 3.7.0_9 (3.18 kernel) can't
mount /data read-write (ext4 project quota) or decrypt it; it's kept only as a fallback.

Notes:
- `fastboot reboot recovery` isn't supported by mido's bootloader; use `adb reboot recovery`.
- In OrangeFox, mount system with `twrp mount /system_root` (plain `mount` doesn't work).
- OrangeFox in recovery uses USB id 18d1:2d08; Linux needs a udev rule for vendor 18d1.
