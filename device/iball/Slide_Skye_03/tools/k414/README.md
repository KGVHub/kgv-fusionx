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
| `gfx414/` | The 4.14 graphics overlay for the 4.4 ROM: `k414.rc`, `stage.sh`, `install-twrp.sh` |
| `spd_dump-line-state-nonfatal.patch` | This tablet's bootrom rejects the CDC line-state request, so the patch makes that failure non-fatal |

## Status (2026-11-12)

- **4.14.199 boots** (as TWRP in the recovery partition), with the display on (DRM; the 1024x600 `lcd_kgv_iball_mipi` panel shows TWRP), eMMC, USB/adb, Trusty (API 2 against the 2018 TOS) and the WCN SIPC channels.
- The kernel and DTB build, and so do the four out-of-tree drivers. Their vermagic is `4.14.199+`, with 0 CRC mismatches.
- What it took to boot:
  - `CONFIG_TRUSTY_LOG` off. Its panic notifier crashed with undefined instructions on the 8.1 bootloader's Trusty, in a loop.
  - `CONFIG_SPRD_SYSDUMP` off, so a panic restarts in mode NORMAL and goes back to the ROM instead of looping.
  - Lockup and hung-task panics, plus `panic=5`.
  - The overlay matches the stock 8.1 memory map, with ramoops at `0x9e524000`.
- The bootloader's `fastboot boot` ignores the image sent and boots the boot partition, so tests go through the recovery partition.

- **The 4.4 ROM boots on 4.14** (2026-11-12, to `sys.boot_completed` and the launcher; no wallpaper yet). What it took:
  - **Binder protocol 7.** The ROM is built with `TARGET_USES_64_BIT_BINDER := false`, so every service aborted with "Binder driver protocol(8) does not match user space protocol(7)". The kernel brings back `CONFIG_ANDROID_BINDER_IPC_32BIT` (the uapi header still has it). This was the "bootloop": init kept rebooting into the bootloader logo.
  - **Graphics.** The 8.1 hwcomposer needs ADF and Mali r21. On 4.14, `gfx414/k414.rc` bind-mounts the Nokia C1 2E DRM hwcomposer, ION gralloc, Mali r28p0 `libGLES_mali.so` and `libmemion.so` from `/vendor/k414` over the stock files, and `/vendor/k414/modules` over `/vendor/lib/modules`. It only acts with `androidboot.k414=1` in the DTB bootargs, so the 4.4 kernel keeps the stock stack. Build the overlay with `gfx414/stage.sh` and install it from TWRP with `gfx414/install-twrp.sh`.
  - The bootargs also need `androidboot.selinux=permissive`: the bootloader uses the DTB's `/chosen/bootargs`, not the boot header cmdline.
- Later the same day (kernel `kgv/pike2-4.14`):
  - **Wi-Fi** connects. The overlay keeps the Nokia WCN/GNSS layout at `0x84000000` and reserves it; the 4.14 WCN boot times out at the stock `0x88000000`. An earlier overlay reserved `0x88000000` while the WCN nodes still pointed at `0x84000000`, which put the firmware and SIPC rings in RAM the kernel handed out. That was the `sprdwl_ng` panic. `cpwcn_gnss` is disabled, because the WCN boot otherwise waits for a GNSS calibration that never completes, so GPS is off.
  - **Battery and charging.** The kernel enables `CONFIG_CHARGER_SC2720`. `charger-manager` uses the only `cm-chargers` entry instead of Samsung's entry 1. The fuel gauge gets `sprd,battery-always-present` and `sprd,no-battery-temp`, because this battery has no BAT_DET line and no thermistor.
  - **Panel wake.** `lcd_bias` skips the SM5109C bias chip, which this board doesn't have. Before, it caused a NULL dereference on every screen-on.
  - **Boot time drops from about 3 min to 53 s.** There is no hardware RNG, so the CRNG took 64 s and BoringSSL's self-test held up init. The kernel now has the upstream jitter-entropy wait backport (`50ee7529ec45`).
  - The power-off tracker is `powerwatch.sh`.
- Debugging a loop: a 35 MB `write_part boot` from download mode wipes the ramoops area. Write only `misc` (`boot-recovery`), let the 4.14 TWRP read `/sys/fs/pstore`, then restore boot from TWRP. The watchdog stops in the kernel (`sprd-adi` PMIC watchdog, `sprd_wdt` AP watchdog) turned out not to be needed: both were already off.
- **A11 mode** (`A11=1` on the `iball` DAG, product `lineage_Slide_Skye_03_a11`): boots kernel 4.14 with the Nokia C1 2E Android 11 vendor (`a11/mk-vendor.sh`) over VNDK 30. It is wired in but hasn't been built as a ROM yet.

**Warning:** testing in recovery loops. After `adb reboot recovery`, misc stays set, so a hung kernel boots again on every power cycle. To get out:

1. Run `unbrick-recovery.sh` with the backed-up TWRP.
2. Press Power + RESET.
3. Hold Vol Down and plug in USB.
