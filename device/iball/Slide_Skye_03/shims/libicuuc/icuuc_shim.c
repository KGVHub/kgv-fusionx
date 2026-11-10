/*
 * The 8.1 power HAL (power.sprd.so) links ICU 58's converter API, which vendor
 * code can't load on Android 13. Without it vendor.sprd.hardware.power@2.0
 * never starts, and hwcomposer.sp7731e.so waits for it forever, which hangs
 * SurfaceFlinger and the system_server boot. The HAL only converts its ASCII
 * config, so a converter that copies bytes is enough.
 */
#include <stdint.h>
#include <string.h>

typedef int UErrorCode;
typedef int8_t UBool;
typedef uint16_t UChar;
typedef void UConverterCallback(void);

#define U_BUFFER_OVERFLOW_ERROR 15

struct UConverter { int unused; };
static struct UConverter sConverter;

struct UConverter *ucnv_open_58(const char *name, UErrorCode *err) {
    (void)name;
    if (err && *err > 0) return NULL;
    return &sConverter;
}

void ucnv_close_58(struct UConverter *c) { (void)c; }

void ucnv_convertEx_58(struct UConverter *targetCnv, struct UConverter *sourceCnv,
        char **target, const char *targetLimit, const char **source,
        const char *sourceLimit, UChar *pivotStart, UChar **pivotSource,
        UChar **pivotTarget, const UChar *pivotLimit, UBool reset, UBool flush,
        UErrorCode *err) {
    (void)targetCnv; (void)sourceCnv; (void)pivotStart; (void)pivotSource;
    (void)pivotTarget; (void)pivotLimit; (void)reset; (void)flush;
    if (!err || *err > 0 || !target || !*target || !source || !*source) return;
    size_t in = (size_t)(sourceLimit - *source), room = (size_t)(targetLimit - *target);
    size_t n = in < room ? in : room;
    memcpy(*target, *source, n);
    *target += n;
    *source += n;
    if (n < in) *err = U_BUFFER_OVERFLOW_ERROR;
}

static void setCallback(UConverterCallback **oldAction, const void **oldContext,
        UErrorCode *err) {
    if (err && *err > 0) return;
    if (oldAction) *oldAction = NULL;
    if (oldContext) *oldContext = NULL;
}

void ucnv_setFromUCallBack_58(struct UConverter *c, UConverterCallback *action,
        const void *context, UConverterCallback **oldAction, const void **oldContext,
        UErrorCode *err) {
    (void)c; (void)action; (void)context;
    setCallback(oldAction, oldContext, err);
}

void ucnv_setToUCallBack_58(struct UConverter *c, UConverterCallback *action,
        const void *context, UConverterCallback **oldAction, const void **oldContext,
        UErrorCode *err) {
    (void)c; (void)action; (void)context;
    setCallback(oldAction, oldContext, err);
}

void UCNV_FROM_U_CALLBACK_STOP_58(void) {}
void UCNV_TO_U_CALLBACK_STOP_58(void) {}
