#!/vendor/bin/sh
# Wi-Fi helpers for init.sp7731e_1h10.wifi.rc.
#
# "conf" (post-fs-data): the stock wpa_supplicant starts with
# -c/data/misc/wifi/wpa_supplicant.conf and exits if it's missing; 8.1 created
# it from the vendor template. Never overwrite it: it holds saved networks.
#
# No argument (boot completed): loads the driver; the stock HAL runs as wifi
# and cannot load modules itself.
if [ "$1" = conf ]; then
    C=/data/misc/wifi/wpa_supplicant.conf
    if [ ! -s $C ]; then
        cp /vendor/etc/wifi/wpa_supplicant.conf $C
        chown wifi:wifi $C
        chmod 660 $C
        restorecon $C
    fi
    exit 0
fi
insmod /vendor/lib/modules/sprdwl_ng.ko
# The stock libwifi-hal only treats the driver as loaded with this set.
setprop wlan.driver.status ok
echo "wifi-driver: sprdwl_ng loaded" > /dev/kmsg
