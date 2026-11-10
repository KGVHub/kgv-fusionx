/*
 * Android 8.1 libbase ABI for the stock Wi-Fi HAL, libwifi-hal and
 * libwifi-system (gen-vendor.py points their libbase.so NEEDED entry here;
 * this library needs the real libbase, so everything else still resolves).
 */
#include <string>

namespace android {
template <typename T> class sp;
namespace hardware::wifi::V1_0 {
struct IWifiChipEventCallback;
// Debug-only helper the 8.1 generated code had; Android 13's doesn't.
std::string toString(const ::android::sp<IWifiChipEventCallback>&) {
    return "[IWifiChipEventCallback]";
}
}  // namespace hardware::wifi::V1_0
}  // namespace android

// 8.1: LogMessage(file, line, LogId, LogSeverity, error). Android 13 still has
// that constructor with a tag before the error; the 8.1 one is it with no tag.
extern "C" void a13_LogMessage(void* self, const char* file, unsigned line, int id,
        int severity, const char* tag, int error)
        __asm__("_ZN7android4base10LogMessageC1EPKcjNS0_5LogIdENS0_11LogSeverityES3_i");

extern "C" void LogMessage81_C1(void* self, const char* file, unsigned line, int id,
        int severity, int error)
        __asm__("_ZN7android4base10LogMessageC1EPKcjNS0_5LogIdENS0_11LogSeverityEi");
extern "C" void LogMessage81_C2(void* self, const char* file, unsigned line, int id,
        int severity, int error)
        __asm__("_ZN7android4base10LogMessageC2EPKcjNS0_5LogIdENS0_11LogSeverityEi");

extern "C" void LogMessage81_C1(void* self, const char* file, unsigned line, int id,
        int severity, int error) {
    a13_LogMessage(self, file, line, id, severity, nullptr, error);
}
extern "C" void LogMessage81_C2(void* self, const char* file, unsigned line, int id,
        int severity, int error) {
    a13_LogMessage(self, file, line, id, severity, nullptr, error);
}
