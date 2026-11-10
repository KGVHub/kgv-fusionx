/*
 * The 8.1 libhwbinder call the stock wpa_supplicant makes that Android 13's
 * libhidlbase no longer has (gen-vendor.py points its libhwbinder.so NEEDED
 * entry here). It only lowers thread priority, so it can do nothing.
 */
extern "C" void disableBackgroundScheduling(bool)
        __asm__("_ZN7android8hardware14IPCThreadState27disableBackgroundSchedulingEb");
extern "C" void disableBackgroundScheduling(bool) {}
