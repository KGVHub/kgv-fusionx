DEVICE_PATH := device/iball/Slide_Skye_03

# Architecture: Cortex-A7 x4, ARM32 only.
TARGET_ARCH := arm
TARGET_ARCH_VARIANT := armv7-a-neon
TARGET_CPU_ABI := armeabi-v7a
TARGET_CPU_ABI2 := armeabi
TARGET_CPU_VARIANT := cortex-a7
# CONFIG_ANDROID_BINDER_IPC_32BIT=y: the stock vendor ships its own
# 32-bit-ABI libbinder/libhwbinder.
TARGET_USES_64_BIT_BINDER := false

TARGET_BOARD_PLATFORM := sp7731e
TARGET_BOOTLOADER_BOARD_NAME := sp7731e_1h10
TARGET_NO_BOOTLOADER := true

# Kernel: KGV-Pro-X (kernel-pike2), built outside the tree. The SPRD boot
# header (DT region, dt_size at 0x28) and the AVB testkey footer are added
# by tools/pack-boot.sh, not by mkbootimg.
TARGET_NO_KERNEL := false
TARGET_PREBUILT_KERNEL := $(DEVICE_PATH)/prebuilt/zImage
BOARD_KERNEL_BASE := 0x00000000
BOARD_KERNEL_PAGESIZE := 2048
# Bring-up: permissive until the vendor policy is written for 33.0.
BOARD_KERNEL_CMDLINE := console=ttyS1,115200n8 androidboot.selinux=permissive

# Partitions (bytes, from /proc/partitions)
BOARD_FLASH_BLOCK_SIZE := 131072
BOARD_BOOTIMAGE_PARTITION_SIZE := 36700160
BOARD_RECOVERYIMAGE_PARTITION_SIZE := 36700160
BOARD_SYSTEMIMAGE_PARTITION_SIZE := 1363148800
BOARD_VENDORIMAGE_PARTITION_SIZE := 209715200
BOARD_CACHEIMAGE_PARTITION_SIZE := 157286400
BOARD_USERDATAIMAGE_PARTITION_SIZE := 5932825600
BOARD_CACHEIMAGE_FILE_SYSTEM_TYPE := ext4
BOARD_VENDORIMAGE_FILE_SYSTEM_TYPE := ext4
TARGET_COPY_OUT_VENDOR := vendor
TARGET_USERIMAGES_USE_EXT4 := true
TARGET_USERIMAGES_USE_F2FS := true
# Not system-as-root: first-stage init in the boot ramdisk mounts system and vendor.
BOARD_BUILD_SYSTEM_ROOT_IMAGE := false

# Recovery stays the KGV TWRP; this build doesn't make one.
TARGET_NO_RECOVERY := true
TARGET_RECOVERY_FSTAB := $(DEVICE_PATH)/rootdir/etc/fstab.sp7731e_1h10

# Display: 600x1024 panel, scanned out in landscape.
TARGET_SCREEN_DENSITY := 160
TARGET_USES_HWC2 := true

# Treble: our own vendor image (stock blobs + rebuilt HAL services).
PRODUCT_FULL_TREBLE_OVERRIDE := true
BOARD_VNDK_VERSION := current
DEVICE_MANIFEST_FILE := $(DEVICE_PATH)/manifest.xml
BUILD_BROKEN_ELF_PREBUILT_PRODUCT_COPY_FILES := true
BUILD_BROKEN_DUP_RULES := true

TARGET_SYSTEM_PROP := $(DEVICE_PATH)/system.prop
TARGET_VENDOR_PROP := $(DEVICE_PATH)/vendor.prop

# SELinux
BOARD_VENDOR_SEPOLICY_DIRS += $(DEVICE_PATH)/sepolicy/vendor

MALLOC_SVELTE := true
VENDOR_SECURITY_PATCH := 2019-05-05

-include vendor/iball/Slide_Skye_03/BoardConfigVendor.mk
