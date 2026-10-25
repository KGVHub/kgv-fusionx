# KGV FusionX

LineageOS 20 (Android 13) for the **Xiaomi Redmi Note 4 / 4X Snapdragon, "mido"**
(msm8953), with the **KGV-Pro-X** kernel. Built for community-medicine students and
researchers: an AI assistant that finds and summarises literature and drives the
phone, plus privacy built into the system.

## Features

| | |
|---|---|
| **KGV X Intelligence** | AI assistant (hold Home). Searches PubMed, Europe PMC and the web, reads full text, saves references and exports Vancouver/BibTeX/RIS. Controls the phone: reads the screen, taps, swipes, types, opens apps, takes screenshots. Voice input. |
| **Your own AI** | Use llama.cpp, Ollama or LM Studio on your home PC over Wi-Fi ("Find my PC"), OpenRouter, or any OpenAI-compatible server. Type the server's address and its models load by themselves. Local-only mode keeps cloud models out. |
| **MCP server** | The phone's skills over MCP (Streamable HTTP), for local agents or desktop clients; token-protected. |
| **Private DNS (Unbound)** | An on-device recursive resolver with DNSSEC for every app: no DNS company sees your lookups. Or set your own DNS servers. Falls back to the network's DNS when recursion is blocked. |
| **KGV AdBlock** | Blocks ~71,000 ad, tracker and malware domains in every app (StevenBlack hosts, MIT), inside Unbound. |
| **Double tap to wake** | On by default. |

All four (KGV X AI, DNS, AdBlock, MCP server) have their own switch and page in
Settings › KGV X Intelligence, between Network and internet and Connected devices.

## Layout

| Path | What |
|---|---|
| `build.sh` | Builds the ROM on a 7–8 GB RAM PC (memory-capped, staged) |
| `manifest/kgv_fusionx.xml` | Local manifest: sync KGV FusionX with `repo` |
| `patches/<repo>` | Every change to the LineageOS 20 tree, as `git format-patch` series ([how to apply](patches/README.md)) |
| `apps/KGVIntelligence` | The KGV X Intelligence app (`packages/apps/KGVIntelligence`) |
| `external-unbound` | Android build files for Unbound 1.26.2, the DNS redirect and AdBlock list (`external/unbound`, [README](external-unbound/README.md)) |
| `branding` | Boot animation generator |
| `twrp` | **KGV TWRP** build script and notes ([README](twrp/README-recovery.md)) |
| `prune-tree.sh` | Trims the source tree for a low-RAM build |

## Sync and build

You need about 250 GB of free disk and 8 GB of RAM (no swap file needed). Install
the [LineageOS build packages](https://wiki.lineageos.org/devices/mido/build) and `repo`, then:

```sh
mkdir kgv-fusionx && cd kgv-fusionx
repo init -u https://github.com/LineageOS/android.git -b lineage-20.0 --git-lfs
mkdir -p .repo/local_manifests
curl -o .repo/local_manifests/kgv_fusionx.xml \
  https://raw.githubusercontent.com/KGVHub/kgv-fusionx/main/manifest/kgv_fusionx.xml
repo sync -c -j4 --no-tags --no-clone-bundle
JOBS=4 STAGE=full kgv/build.sh
```

The ROM lands in `out/target/product/mido/KGV-FusionX-<version>-<date>-mido.zip`.

[`manifest/kgv_fusionx.xml`](manifest/kgv_fusionx.xml) replaces these LineageOS 20
projects with KGVHub forks on branch `kgv-1.0`:

| Path | Why |
|---|---|
| `build/make`, `build/soong`, `build/blueprint` | Low-RAM build: soong_build fits in 6.3 GB, no 32-bit host build |
| `frameworks/base` | Package cache resets with every build (fixes stale app info after a flash) |
| `packages/modules/Permission` | A corrupt runtime-permissions.xml no longer bootloops |
| `packages/apps/Settings` | KGV X Intelligence entry on the Settings home page |
| `vendor/lineage`, `lineage-sdk` | KGV FusionX branding and version |
| `packages/apps/KGVIntelligence`, `external/unbound` | KGV X Intelligence, Unbound DNS and AdBlock (new) |
| `device/xiaomi/mido`, `kernel/xiaomi/mido` | mido trees (from [zeelog](https://github.com/zeelog)) with KGV changes and sepolicy |

`kgv/build.sh` options: `JOBS` (parallel jobs, 4 works on 8 GB), `STAGE` (`graph` for
the Soong graph only, `full` for the flashable zip, or a module name), `DEVICE`
(default `mido`), `SRC` (tree path; default: the current directory).

### Other devices

The low-RAM build works for any LineageOS 20 device: keep the `build/*` projects,
replace the mido device, kernel and vendor projects with your device's, and run
`DEVICE=<codename> kgv/build.sh`. To get the KGV features, add to your device's `device.mk`:

```make
PRODUCT_PACKAGES += KGVIntelligence unbound
```

and copy the KGV sepolicy (`sepolicy/private/kgv_*.te`, the `kgv` entries in
`property_contexts` and `file_contexts`) from the mido tree. The Settings entry and fixes
come with the forks.

### Without repo

[`patches/`](patches/README.md) holds every change as `git format-patch` series, to
apply on a LineageOS 20 tree yourself.

## Flash

Use **KGV TWRP** (`twrp/build-kgv-twrp.sh`): it decrypts the ROM's /data, mounts its
system and vendor partitions, and adb sideload works. Stock TWRP 3.7.0_9 can't open
/data; OrangeFox and the "A13 retrofit" TWRP expect a different partition layout.

    fastboot flash recovery twrp/kgv-twrp/kgv-twrp-3.7.0_12-mido.img
    adb reboot recovery          # fastboot reboot recovery boots the ROM on mido
    # TWRP: Advanced › ADB Sideload, then
    adb sideload KGV-FusionX-*.zip

## Credits

LineageOS, the mido device/kernel maintainers, TWRP and alone0316 (the TWRP base
image), NLnet Labs (Unbound), Steven Black (hosts list).
