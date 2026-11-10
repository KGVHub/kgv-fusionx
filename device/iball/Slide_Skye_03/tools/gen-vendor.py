#!/usr/bin/env python3
"""Generates vendor/iball/Slide_Skye_03 (blob makefile) from an extracted stock
vendor image. Leaves out what the Android 13 build provides itself: old copies
of VNDK/LLNDK libraries, the HALs rebuilt from source, stock SELinux policy,
VINTF manifest and build props.
usage: gen-vendor.py <stock vendor dir> <tree top>"""
import os, re, sys

src, top = sys.argv[1:]
dev = "Slide_Skye_03"
out = os.path.join(top, "vendor/iball", dev)

vndk = set(re.findall(r"[\w.@+-]+\.so", open(os.path.join(top, "build/make/target/product/gsi/33.txt")).read()))
vndk |= {"libbinder.so", "libhwbinder.so", "libvndksupport.so"}
# Old copies of libraries the build installs on vendor: 8.1 libhidltransport
# (now an empty shim over libhidlbase), the keymaster libraries that the
# KeyMint service links against, libmemunreachable (the 8.1 one calls
# malloc_iterate, which libc no longer exports to vendor; breaks the audio
# HAL), libhwminijail (the 8.1 one wants an old libbase LogMessage) and
# libeffects (lacks EffectCreateOnDevice, which the effect HAL needs).
vndk |= {"libhidltransport.so", "libkeymaster_messages.so", "libkeymaster_portable.so",
         "libsoftkeymasterdevice.so", "libmemunreachable.so", "libhwminijail.so",
         "libeffects.so"}
# HALs rebuilt from source (impl libs, services, init scripts); configstore
# comes from base_vendor.mk as the 1.1 service
rebuilt = re.compile(r"android\.hardware\.(audio|graphics|health|memtrack|light|sensors|vibrator|"
                     r"keymaster|gatekeeper|camera\.provider|usb|cas|configstore)[.@]")
# Wi-Fi is the stock 8.1 stack (HAL, libwifi-hal, wpa_supplicant), which keeps
# the WCN chip alive; Android 13's generic supplicant leaves it hung after one
# scan. Only the HIDL interface libraries come from the Android 13 build.
rebuilt_files = {"lib/android.hardware.wifi@1.0.so"}
skip_top = {"build.prop", "default.prop", "manifest.xml", "compatibility_matrix.xml", "lost+found", "app", "overlay"}

copies, skipped = [], []
for root, dirs, files in os.walk(src):
    rel_root = os.path.relpath(root, src)
    if rel_root == ".":
        dirs[:] = [d for d in dirs if d not in skip_top]
    for f in sorted(files):
        rel = os.path.normpath(os.path.join(rel_root, f))
        if rel in skip_top or rel.startswith("etc/selinux/") \
                or (rel.startswith("lib/") and f in vndk) \
                or rebuilt.search(f) \
                or rel in rebuilt_files:
            skipped.append(rel)
            continue
        copies.append(rel)

# Stock init scripts that Android 13's host_init_verifier rejects.
# (old, new) replacements; each old string must be present.
FIXUPS = {
    # "user" takes one name; the 8.1 init ignored the extra one. Android 13's
    # init gives services no capabilities unless asked, and the HAL needs them to
    # bring wlan0 up (and to load and unload the driver).
    "etc/init/android.hardware.wifi@1.0-service.rc": [
        (b"    user system wifi\n", b"    capabilities NET_ADMIN NET_RAW SYS_MODULE\n    user wifi\n")],
    # The 8.1 libbase and libhwbinder calls Android 13 dropped come from
    # shims/libbas8 and shims/libhwbinde8, which link the real libraries.
    "bin/hw/android.hardware.wifi@1.0-service": [(b"\0libbase.so\0", b"\0libbas8.so\0")],
    "lib/libwifi-hal.so": [(b"\0libbase.so\0", b"\0libbas8.so\0")],
    "lib/libwifi-system.so": [(b"\0libbase.so\0", b"\0libbas8.so\0")],
    "bin/hw/wpa_supplicant": [(b"\0libhwbinder.so\0", b"\0libhwbinde8.so\0")],
    # NAND-only command (this tablet is eMMC); fstab moved to /vendor.
    "etc/init/charge.rc": [
        (b"    ubiattach 0 ubipac\n", b""),
        (b"mount_all /fstab.${ro.hardware}", b"mount_all /vendor/etc/fstab.${ro.hardware}")],
    # The rpmbserver and ylog users don't exist on Android 13. Both sockets
    # are 0666, so system ownership changes nothing for their clients.
    "etc/init/rpmbserver_androido.rc": [
        (b"socket rpmbserver stream 0666 rpmbserver rpmbserver", b"socket rpmbserver stream 0666 system system")],
    "etc/init/ylog_lite.rc": [
        (b"socket ylog seqpacket 0666 ylog ylog", b"socket ylog seqpacket 0666 system system")],
    # libfmjni lists libmedia.so as NEEDED but uses none of its symbols, and the
    # 8.1 libmedia pulls in libicuuc, which vendor can't load; connmgr (wcnd,
    # which brings up Wi-Fi/BT) links libfmjni via libengfm. Point the NEEDED
    # entry at liblog.so (already NEEDED) instead.
    "lib/libfmjni.so": [
        (b"\0libmedia.so\0", b"\0liblog.so\0\0\0")],
    # The 8.1 libui ABI the composer blob needs comes from libu8 (shims/libu8),
    # which links the real libui.
    "lib/hw/hwcomposer.sp7731e.so": [
        (b"\0libui.so\0", b"\0libu8.so\0")],
}

# (file offset, old bytes, new bytes) patches for code the string fixups can't
# target. hwcomposer.sp7731e.so allocates libui objects itself with the 8.1
# sizes before calling Android 13's constructors: GraphicBuffer grew from 136
# to 160 bytes and GraphicBufferMapper from 4 to 8 (Thumb "movs r0, #size"
# before each operator new).
PATCHES = {
    "lib/hw/hwcomposer.sp7731e.so": [
        (0x1e588, b"\x88\x20", b"\xa0\x20"),
        (0x22c64, b"\x88\x20", b"\xa0\x20"),
        (0x1fae6, b"\x04\x20", b"\x08\x20")],
}

os.makedirs(out, exist_ok=True)
proprietary = os.path.join(out, "proprietary")
for rel in copies:
    dst = os.path.join(proprietary, "vendor", rel)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    s = os.path.join(src, rel)
    if os.path.islink(s):
        if os.path.lexists(dst):
            os.remove(dst)
        os.symlink(os.readlink(s), dst)
    else:
        with open(s, "rb") as i:
            data = i.read()
        for old, new in FIXUPS.get(rel, []):
            assert old in data, "%s: fixup no longer applies: %r" % (rel, old)
            data = data.replace(old, new)
        for off, old, new in PATCHES.get(rel, []):
            assert data[off:off + len(old)] == old, "%s: patch no longer applies at %#x" % (rel, off)
            data = data[:off] + new + data[off + len(new):]
        with open(dst, "wb") as o:
            o.write(data)

files = [r for r in copies if not os.path.islink(os.path.join(src, r))]
with open(os.path.join(out, dev + "-vendor.mk"), "w") as m:
    m.write("# Generated by device/iball/%s/tools/gen-vendor.py. Do not edit.\n\n" % dev)
    m.write("PRODUCT_COPY_FILES += \\\n")
    m.write(" \\\n".join("    vendor/iball/%s/proprietary/vendor/%s:$(TARGET_COPY_OUT_VENDOR)/%s" % (dev, r, r)
                         for r in sorted(files)))
    m.write("\n")
open(os.path.join(out, "Android.bp"), "w").write("soong_namespace {}\n")
open(os.path.join(out, "skipped.txt"), "w").write("\n".join(sorted(skipped)) + "\n")
print("%d files copied, %d skipped (see skipped.txt)" % (len(files), len(skipped)))
