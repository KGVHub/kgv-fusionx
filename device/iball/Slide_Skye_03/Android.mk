LOCAL_PATH := $(call my-dir)

ifeq ($(TARGET_DEVICE),Slide_Skye_03)
# The images flashed from TWRP. boot.img is repacked for the SPRD bootloader
# from the ramdisk by tools/pack-boot.sh, so the OTA zip isn't built.
.PHONY: kgv-images
kgv-images: systemimage vendorimage bootimage
endif
