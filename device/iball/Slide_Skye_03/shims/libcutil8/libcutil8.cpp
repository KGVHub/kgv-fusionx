/*
 * The 8.1 libcutils call the stock audio HAL (audio.primary.sp7731e.so and its
 * libcompresssimu.so) makes that Android 13's libcutils no longer has:
 * set_sched_policy moved to libprocessgroup, which vendor code can't link.
 * gen-vendor.py points their libcutils.so NEEDED entry here; this library
 * links the real libcutils, so everything else still resolves there.
 *
 * The HAL only uses it to move its playback threads to the foreground cgroup,
 * a scheduling hint, so accept and ignore it.
 */
#include <cutils/properties.h>

extern "C" int set_sched_policy(int /* tid */, int /* policy */) {
    return 0;
}

// Keeps the libcutils NEEDED entry (the linker drops unused ones).
extern "C" int libcutil8_keep_libcutils() {
    return property_get_bool("ro.debuggable", false);
}
