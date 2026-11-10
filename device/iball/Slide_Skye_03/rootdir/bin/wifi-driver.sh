#!/vendor/bin/sh
# Wi-Fi helpers for init.sp7731e_1h10.wifi.rc.
#
# "conf" (post-fs-data): the stock wpa_supplicant starts with
# -c/data/misc/wifi/wpa_supplicant.conf and exits if it's missing; 8.1 created
# it from the vendor template. Never overwrite it: it holds saved networks.
#
# No argument (boot completed): loads the driver. The stock HAL runs as wifi
# and, loaded straight at boot completion, sprdwl_ng comes up but every command
# to the WCN chip fails (no free swcnblk) until the module is loaded again;
# give the chip time, then load it twice.
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
sleep 30
insmod /vendor/lib/modules/sprdwl_ng.ko
sleep 5
rmmod sprdwl_ng
sleep 2
insmod /vendor/lib/modules/sprdwl_ng.ko
# The stock libwifi-hal only treats the driver as loaded with this set.
setprop wlan.driver.status ok
echo "wifi-driver: sprdwl_ng loaded" > /dev/kmsg
