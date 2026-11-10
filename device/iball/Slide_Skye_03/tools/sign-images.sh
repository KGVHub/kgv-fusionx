#!/bin/bash
# Makes flashable system/vendor images: unsparse, shrink the ext4 filesystem
# to leave room for the AVB hashtree + FEC, and sign with the testkey that
# vbmeta-v4 chains system and vendor to (same layout as the Lineage 15.1
# system the bootloader accepted).
# usage: sign-images.sh <out/target/product/Slide_Skye_03>
# writes <dir>/system-signed.img and <dir>/vendor-signed.img (raw, full partition size)
set -euo pipefail
P=$1
HOST=$(cd -- "$P/../../../host/linux-x86/bin" && pwd)
AVB=${AVBTOOL:-/home/kgvarunkanth/claude/tools/avb}
export PATH=$HOST:$PATH   # fec, simg2img
for p in "system 1363148800" "vendor 209715200"; do
  set -- $p
  name=$1 size=$2 img=$P/$1-signed.img
  simg2img "$P/$name.img" "$img"
  max=$(python3 "$AVB/avbtool.py" add_hashtree_footer --partition_size "$size" \
        --hash_algorithm sha1 --fec_num_roots 2 --algorithm SHA256_RSA4096 --calc_max_image_size)
  blocks=$((max / 4096))
  e2fsck -fy "$img" >/dev/null || [ $? -le 1 ]
  resize2fs "$img" "$blocks" >/dev/null 2>&1
  truncate -s $((blocks * 4096)) "$img"
  e2fsck -fn "$img" >/dev/null
  python3 "$AVB/avbtool.py" add_hashtree_footer --image "$img" --partition_name "$name" \
      --partition_size "$size" --hash_algorithm sha1 --fec_num_roots 2 \
      --algorithm SHA256_RSA4096 --key "$AVB/testkey_rsa4096.pem"
  echo "wrote $img"
done
