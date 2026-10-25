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
| `patches/<repo>` | Every change to the LineageOS 20 tree, as `git format-patch` series ([how to apply](patches/README.md)) |
| `apps/KGVIntelligence` | The KGV X Intelligence app (`packages/apps/KGVIntelligence`) |
| `external-unbound` | Android build files for Unbound 1.26.2, the DNS redirect and AdBlock list (`external/unbound`, [README](external-unbound/README.md)) |
| `branding` | Boot animation generator |
| `twrp` | **KGV TWRP** build script and notes ([README](twrp/README-recovery.md)) |
| `prune-tree.sh` | Trims the source tree for a low-RAM build |

## Build

1. Sync LineageOS 20 (`m/lineage-20.0`) with the mido device, kernel and vendor trees
   into a folder named `fusionx`, and clone this repo next to it as `fusionx-port`:

       <dir>/fusionx         # LineageOS 20 tree
       <dir>/fusionx-port    # this repo

2. Apply the patches ([patches/README.md](patches/README.md)), copy
   `apps/KGVIntelligence` to `packages/apps/KGVIntelligence`, and set up
   `external/unbound` ([external-unbound/README.md](external-unbound/README.md)).
3. Build:

       JOBS=4 STAGE=full fusionx-port/build.sh

   Output: `fusionx/out/target/product/mido/KGV-FusionX-<version>-<date>-mido.zip`.

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
