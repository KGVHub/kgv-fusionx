# KGV FusionX for the iBall Slide Skye 03 on kernel 4.14 with the Nokia C1
# 2nd Edition (Android 11, SC7731E) vendor image: a GSI-style system over a
# prebuilt VNDK 30 vendor. BoardConfig.mk and device.mk switch on this
# product name; the 4.4 ROM is lineage_Slide_Skye_03.
$(call inherit-product, device/iball/Slide_Skye_03/lineage_Slide_Skye_03.mk)

PRODUCT_NAME := lineage_Slide_Skye_03_a11
