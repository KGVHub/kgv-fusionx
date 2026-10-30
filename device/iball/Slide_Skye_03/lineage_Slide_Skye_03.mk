# KGV FusionX for the iBall Slide Skye 03 (Unisoc SC7731E, 1 GB, ARM32 only).

$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base_telephony.mk)
$(call inherit-product, device/iball/Slide_Skye_03/device.mk)

# Lineage Go: the tablet has 900 MB of RAM and a 1.3 GB system partition.
$(call inherit-product, vendor/lineage/config/common_mini_go_phone.mk)

PRODUCT_DEVICE := Slide_Skye_03
PRODUCT_NAME := lineage_Slide_Skye_03
PRODUCT_BRAND := iBall
PRODUCT_MODEL := Slide Skye 03
PRODUCT_MANUFACTURER := iBall
PRODUCT_CHARACTERISTICS := tablet

PRODUCT_BUILD_PROP_OVERRIDES += \
    PRIVATE_BUILD_DESC="Slide_Skye_03-user 8.1.0 OPM2.171019.012 32419 release-keys"

BUILD_FINGERPRINT := iBall/Slide_Skye_03/Slide_Skye_03:8.1.0/OPM2.171019.012/32419:user/release-keys
