# Linux 4.14 for the iBall Slide Skye 03 (experimental)

The work in this folder is not yet booting. The shipping ROM uses the 4.4.302 kernel in `prebuilt/zImage`.

## Sources

| Source | Where |
|---|---|
| Kernel | [android_kernel_blu_pike2 `kgv/pike2-4.14`](https://github.com/KGVHub/android_kernel_blu_pike2/tree/kgv/pike2-4.14): a Unisoc 4.14.199 BSP (Samsung A032F source drop) with fixes for clang 14 and an iBall device-tree overlay and panel |
| Reference config and vendor | Nokia C1 2nd Edition (IRU, SC7731E, Android 11, kernel 4.14.193) dump: `leddaz-dump-stash/nokia_iru_dump`; `ikconfig` is the base config |
| Out-of-tree drivers | `copslock/sprd_bsp_modules`: Mali r28p0 (matches the Nokia GLES blobs), WCN, camera |

## Files

| File | What it does |
|---|---|
| `k414.sh` | Starts from the Nokia config, applies `pike2-kgv.fragment`, and builds `Image` + dtbs with clang-r450784d and GNU as 4.9. It needs `-gdwarf-4 -fno-dwarf-directory-asm -fno-addrsig`. |
| `dtb-iball.sh` | Merges `sp7731e.dtb` with `sp7731e-1h10-iball-overlay.dtbo` into one DTB, because the 8.1 bootloader takes a single DTB. It adds `lcd_name=lcd_kgv_iball_mipi` to `/chosen/bootargs`. |
| `mod414.sh` | Builds the out-of-tree drivers from `sprd_bsp_modules` against `out-414`: Mali r28p0 (pike2), `sprdwl_ng` (sc2332), `sprdbt_tty` and `sprd_fm` (sipc) |
| `dagu-k414.yaml` | The Dagu DAG that runs `k414.sh`, then `mod414.sh` |
| `test-in-recovery.sh` | Flashes a test image (4.14 kernel + TWRP ramdisk) to the recovery partition and boots it |
| `unbrick-recovery.sh` | Restores recovery from SPD download mode |
| `spd_dump-line-state-nonfatal.patch` | This tablet's bootrom rejects the CDC line-state request, so the patch makes that failure non-fatal |

## Status (2026-11-11)

- The kernel and DTB build, and so do the four out-of-tree drivers. Their vermagic is `4.14.199+`, with 0 CRC mismatches.
- The first boot test (in recovery) hung before USB came up, and no log was captured. The 4.14 ramoops area was at another address.
- Since then, the overlay matches the stock 8.1 memory map, and ramoops is at `0x9e524000`, so the 4.4 ROM can read the log of a failed boot. The next test image also boots with `panic=5`.

**Warning:** testing in recovery loops. After `adb reboot recovery`, misc stays set, so a hung kernel boots again on every power cycle. To get out:

1. Run `unbrick-recovery.sh` with the backed-up TWRP.
2. Press Power + RESET.
3. Hold Vol Down and plug in USB.
