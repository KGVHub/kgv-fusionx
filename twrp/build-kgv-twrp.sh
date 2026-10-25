#!/usr/bin/env bash
# Builds KGV TWRP for mido: TWRP 3.7.0_12 A13 (alone0316, 4.9 kernel, Android 12/13
# FBE decryption) with KGV FusionX's partition layout instead of the retrofit one.
#   - system -> /system_root (the real system partition), vendor -> /vendor (cust)
#   - no dynamic/logical partitions, no /metadata on oem (wiping it would wipe oem)
#   - androidboot.super_partition=system dropped from the kernel command line
#   - decryption starts on ro.crypto.state=encrypted alone (it needed dynamic partitions)
# usage: fusionx-port/twrp/build-kgv-twrp.sh   -> kgv-twrp/kgv-twrp-3.7.0_12-mido.img
# Base image: https://sourceforge.net/projects/alone0316/files/recovery/twrp-3.7.0_12.0-mido-A13.img
set -euo pipefail
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
BIN=$HERE/../../fusionx/out/host/linux-x86/bin
BASE=$HERE/candidates/twrp13/twrp-3.7.0_12.0-mido-A13.img
echo "9fea9566bdb57024af5145288f0f1403eceae27d4e343ab6883e3bc7ef2a2eea  $BASE" | sha256sum -c -
W=$HERE/kgv-twrp
rm -rf "${W:?}/unpacked" "${W:?}/ramdisk"
mkdir -p "$W/unpacked" "$W/ramdisk"
"$BIN/unpack_bootimg" --boot_img "$BASE" --out "$W/unpacked" --format mkbootimg > "$W/mkbootimg.args"
cd "$W/ramdisk"
zcat ../unpacked/ramdisk | cpio -idm --quiet
python3 - <<'PY'
import re
p='system/etc/twrp.fstab'; s=open(p).read()
s=re.sub(r'(?m)^(system|system_ext|product|vendor|odm)\s+/\S+\s+ext4.*logical\s*\n','',s)
s=re.sub(r'(?m)^/metadata\s.*\n','',s)
s=re.sub(r'(?m)^/cust\s.*\n','',s)
s=s.replace('/data           ext4','''/system_root    ext4   /dev/block/bootdevice/by-name/system                      flags=display="System";backup=1
/vendor         ext4   /dev/block/bootdevice/by-name/cust                        flags=display="Vendor";backup=1
/data           ext4''',1)
open(p,'w').write('# KGV FusionX recovery: classic mido layout (system, vendor on cust), no dynamic partitions.\n'+s)
p='system/etc/recovery.fstab'; s=open(p).read()
s=re.sub(r'(?m)^(system_ext|product|odm)\s+/\S+\s+ext4.*logical\s*\n','',s)
s=re.sub(r'(?m)^system\s+/system\s+ext4.*logical\s*\n','/dev/block/bootdevice/by-name/system               /system        ext4      ro                                                            wait\n',s)
s=re.sub(r'(?m)^vendor\s+/vendor\s+ext4.*logical\s*\n','/dev/block/bootdevice/by-name/cust                 /vendor        ext4      ro                                                            wait\n',s)
s=re.sub(r'(?m)^/dev/block/bootdevice/by-name/oem\s.*\n','',s)
open(p,'w').write(s)
p='prop.default'; s=open(p).read()
for a,b in [('ro.boot.dynamic_partitions=true','ro.boot.dynamic_partitions=false'),
            ('ro.boot.dynamic_partitions_retrofit=true','ro.boot.dynamic_partitions_retrofit=false'),
            ('ro.build.system_root_image=false','ro.build.system_root_image=true')]:
    s=s.replace(a,b)
open(p,'w').write(s)
p='init.recovery.qcom_decrypt.rc'; s=open(p).read()
old='on property:ro.crypto.state=encrypted && property:ro.boot.dynamic_partitions=true'
assert old in s
open(p,'w').write(s.replace(old,'# KGV FusionX: mido has no dynamic partitions, so start decryption on encryption alone.\non property:ro.crypto.state=encrypted'))
PY
find . | LC_ALL=C sort | cpio -o -H newc -R 0:0 --quiet | gzip -9 > "$W/ramdisk.new.gz"
cd "$W"
args=$(sed -e "s| androidboot.super_partition=system||" -e "s|--ramdisk [^ ]*|--ramdisk $W/ramdisk.new.gz|" mkbootimg.args)
eval "$BIN/mkbootimg" $args --output "$W/kgv-twrp-3.7.0_12-mido.img"
sha256sum "$W/kgv-twrp-3.7.0_12-mido.img"
