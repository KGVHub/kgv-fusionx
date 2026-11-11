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

# Kernel 4.4 has no cgroup v2: mount the v1 cpuacct hierarchy on /acct, which
# libprocessgroup (patched) uses for process groups instead.
PRODUCT_COPY_FILES += \
    $(DEVICE_PATH)/rootdir/etc/cgroups.json:$(TARGET_COPY_OUT_VENDOR)/etc/cgroups.json

# Stock init scripts (paths moved from the ramdisk to /vendor)
PRODUCT_COPY_FILES += \
    $(DEVICE_PATH)/rootdir/stock-ramdisk/init.sp7731e_1h10.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.sp7731e_1h10.rc \
    $(DEVICE_PATH)/rootdir/stock-ramdisk/init.sp7731e_1h10.usb.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.sp7731e_1h10.usb.rc \
    $(DEVICE_PATH)/rootdir/etc/init.sp7731e_1h10.gadget.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/init.sp7731e_1h10.gadget.rc \
    $(DEVICE_PATH)/rootdir/stock-ramdisk/init.common.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.common.rc \
    $(DEVICE_PATH)/rootdir/stock-ramdisk/init.storage.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.storage.rc \
    $(DEVICE_PATH)/rootdir/stock-ramdisk/init.ram.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.ram.rc \
    $(DEVICE_PATH)/rootdir/stock-ramdisk/ueventd.sp7731e_1h10.rc:$(TARGET_COPY_OUT_VENDOR)/ueventd.rc

# KGV-Pro-X kernel modules: Mali GPU, Wi-Fi, BT, FM
PRODUCT_COPY_FILES += \
    $(foreach m,$(notdir $(wildcard $(DEVICE_PATH)/prebuilt/modules/*.ko)),$(DEVICE_PATH)/prebuilt/modules/$(m):$(TARGET_COPY_OUT_VENDOR)/lib/modules/$(m))

# 1024x600 panel. Half-res boot animation: its looping parts are cached as
# textures, and at the default 1080 width that is ~190 MB on this low-RAM device.
TARGET_SCREEN_WIDTH := 600
TARGET_SCREEN_HEIGHT := 1024
TARGET_BOOTANIMATION_HALF_RES := true

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
    android.hardware.light@2.0-impl \
    android.hardware.light@2.0-service \
    android.hardware.sensors@1.0-impl \
    android.hardware.sensors@1.0-service \
    android.hardware.vibrator-service.example \
    android.hardware.security.keymint-service \
    android.hardware.gatekeeper@1.0-service.software \
    android.hardware.camera.provider@2.4-impl \
    android.hardware.camera.provider@2.4-service \
    android.hardware.usb@1.0-service

# Android 13 merged libhwbinder and libhidltransport into libhidlbase; the
# 8.1 blobs still list them as NEEDED, so ship the empty vendor shims.
PRODUCT_PACKAGES += \
    libhidltransport.vendor \
    libhwbinder.vendor \
    android.hardware.thermal@1.1.vendor

# Android 13 builds of libraries the 8.1 blobs need on vendor (libstdc++ for the
# GNSS stack; power@1.1 for the SPRD power client that hwcomposer.sp7731e.so
# links, without which the composer falls back to the framebuffer path and
# shows nothing; the rest replace 8.1 copies dropped in gen-vendor.py)
PRODUCT_PACKAGES += \
    android.hardware.power@1.1.vendor \
    libu8 \
    libicuuc_iball_shim \
    libeffects \
    libstdc++.vendor \
    libhwminijail.vendor \
    libmemunreachable.vendor

# The Mali-T820 gralloc has no RGBA_FP16 buffers, so RenderEngine's shader
# cache priming (which draws an FP16 external texture) aborts SurfaceFlinger.
PRODUCT_VENDOR_PROPERTIES += \
    service.sf.prime_shader_cache=0

# Bring-up: persist logcat to /data/misc/logd so failed boots can be read from recovery
PRODUCT_VENDOR_PROPERTIES += \
    persist.logd.logpersistd=logcatd \
    persist.logd.logpersistd.size=64

# Bring-up: keep RescueParty from escalating crash loops into a factory-reset
# reboot to recovery, and enable adb by default
PRODUCT_SYSTEM_PROPERTIES += \
    persist.sys.disable_rescue=true \
    persist.sys.usb.config=adb

# Wi-Fi: the stock 8.1 stack, with the Android 13 HIDL interface libraries and
# shims for the 8.1 libbase/libhwbinder calls it makes
PRODUCT_PACKAGES += \
    android.hardware.wifi@1.0.vendor \
    android.hardware.wifi@1.1.vendor \
    libbas8 \
    libhwbinde8 \
    wificond
PRODUCT_COPY_FILES += \
    $(DEVICE_PATH)/rootdir/etc/init.sp7731e_1h10.wifi.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/init.sp7731e_1h10.wifi.rc \
    $(DEVICE_PATH)/rootdir/bin/wifi-driver.sh:$(TARGET_COPY_OUT_VENDOR)/bin/wifi-driver.sh

# Camera: the stock 8.1 HAL under Android 13's provider, with shims for the 8.1
# libgui/libhidltransport ABI it uses (libui is libu8 above), the Android 13
# sensorservice bridge it links, and a camera app (the go config has none)
PRODUCT_PACKAGES += \
    libgu8 \
    libhidltranspor8 \
    libsensorndkbridge \
    Aperture

# KGV X Intelligence root daemon (packages/apps/KGVIntelligence/rootd); root is on
# unless the user turns it off in the app (persist.kgv.root)
PRODUCT_PACKAGES += \
    kgv_rootd
PRODUCT_SYSTEM_PROPERTIES += \
    persist.kgv.root=1

# Stock blobs
$(call inherit-product, vendor/iball/Slide_Skye_03/Slide_Skye_03-vendor.mk)
