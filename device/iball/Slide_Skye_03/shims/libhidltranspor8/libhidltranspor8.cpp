/*
 * 8.1 HIDL proxies turn a local interface object into a binder with an inline
 * toBinder() that only looks in the old details::gBnConstructorMap. Android 13
 * still exports that map for prebuilts but registers interfaces only in
 * getBnConstructorMap(), so the old lookup finds nothing, and the proxy fails
 * the call with UNKNOWN_ERROR. The stock camera HAL passes a plain IBase as
 * the token of every vendor.sprd power hint, and aborts on that error.
 *
 * gen-vendor.py points the blob's libhidltransport.so NEEDED entry (an empty
 * library on Android 13) here; loading this registers IBase in the old map.
 */
#include <android/hidl/base/1.0/BnHwBase.h>
#include <hidl/ConcurrentMap.h>
#include <hidl/Static.h>

namespace android::hardware::details {
extern DoNotDestruct<BnConstructorMap> gBnConstructorMap;
}  // namespace android::hardware::details

using android::sp;
using android::hardware::IBinder;
using android::hidl::base::V1_0::BnHwBase;
using android::hidl::base::V1_0::IBase;

__attribute__((constructor)) static void registerLegacyBnConstructors() {
    android::hardware::details::gBnConstructorMap->set(
            IBase::descriptor,
            [](void* iface) -> sp<IBinder> { return new BnHwBase(static_cast<IBase*>(iface)); });
}
