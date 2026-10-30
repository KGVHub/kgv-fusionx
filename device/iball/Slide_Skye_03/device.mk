DEVICE_PATH := device/iball/Slide_Skye_03

# Shipped with Android 8.1: no FBE, no dynamic partitions, no system-as-root.
PRODUCT_SHIPPING_API_LEVEL := 27
PRODUCT_USE_DYNAMIC_PARTITIONS := false

PRODUCT_AAPT_CONFIG := normal large mdpi
PRODUCT_AAPT_PREF_CONFIG := mdpi
PRODUCT_CHARACTERISTICS := tablet

DEVICE_PACKAGE_OVERLAYS += $(DEVICE_PATH)/overlay

# fstab: the ramdisk copy is read by first-stage init, the vendor copy by mount_all.
PRODUCT_COPY_FILES += \
    $(DEVICE_PATH)/rootdir/etc/fstab.sp7731e_1h10:$(TARGET_COPY_OUT_RAMDISK)/fstab.sp7731e_1h10 \
    $(DEVICE_PATH)/rootdir/etc/fstab.sp7731e_1h10:$(TARGET_COPY_OUT_VENDOR)/etc/fstab.sp7731e_1h10

# Stock init scripts (paths moved from the ramdisk to /vendor)
PRODUCT_COPY_FILES += \
    $(DEVICE_PATH)/rootdir/stock-ramdisk/init.sp7731e_1h10.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.sp7731e_1h10.rc \
    $(DEVICE_PATH)/rootdir/stock-ramdisk/init.sp7731e_1h10.usb.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.sp7731e_1h10.usb.rc \
    $(DEVICE_PATH)/rootdir/stock-ramdisk/init.common.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.common.rc \
    $(DEVICE_PATH)/rootdir/stock-ramdisk/init.storage.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.storage.rc \
    $(DEVICE_PATH)/rootdir/stock-ramdisk/init.ram.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.ram.rc \
    $(DEVICE_PATH)/rootdir/stock-ramdisk/ueventd.sp7731e_1h10.rc:$(TARGET_COPY_OUT_VENDOR)/ueventd.rc

# KGV-Pro-X kernel modules: Mali GPU, Wi-Fi, BT, FM
PRODUCT_COPY_FILES += \
    $(foreach m,$(notdir $(wildcard $(DEVICE_PATH)/prebuilt/modules/*.ko)),$(DEVICE_PATH)/prebuilt/modules/$(m):$(TARGET_COPY_OUT_VENDOR)/lib/modules/$(m))

# HALs rebuilt from Android 13 source around the stock hw modules
PRODUCT_PACKAGES += \
    android.hardware.graphics.allocator@2.0-impl \
    android.hardware.graphics.allocator@2.0-service \
    android.hardware.graphics.mapper@2.0-impl \
    android.hardware.graphics.composer@2.1-impl \
    android.hardware.graphics.composer@2.1-service \
    android.hardware.audio.service \
    android.hardware.audio@7.1-impl \
    android.hardware.audio.effect@7.0-impl \
    android.hardware.health-service.example \
    android.hardware.memtrack-service.example \
    android.hardware.lights-service.example \
    android.hardware.sensors@1.0-impl \
    android.hardware.sensors@1.0-service \
    android.hardware.vibrator-service.example \
    android.hardware.keymaster@3.0-impl \
    android.hardware.keymaster@3.0-service \
    android.hardware.gatekeeper@1.0-impl \
    android.hardware.gatekeeper@1.0-service \
    android.hardware.camera.provider@2.4-impl \
    android.hardware.camera.provider@2.4-service \
    android.hardware.usb@1.0-service

# Stock blobs
$(call inherit-product, vendor/iball/Slide_Skye_03/Slide_Skye_03-vendor.mk)
