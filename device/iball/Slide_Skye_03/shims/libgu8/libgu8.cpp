/*
 * The libgui symbols the stock 8.1 libcamera_client imports. Android 13 gives
 * vendor no libgui, so gen-vendor.py points its libgui.so NEEDED entry here.
 * They back the camera1 binder interface (ICamera/Surface parceling), which
 * runs in cameraserver; the camera HAL only uses libcamera_client's
 * CameraParameters and CameraMetadata, so nothing here is ever called.
 */
#include <stdlib.h>

#define STUB(sym) \
    extern "C" void sym##_stub() __asm__(#sym); \
    extern "C" void sym##_stub() { abort(); }

STUB(_ZN7android22IGraphicBufferProducer11asInterfaceERKNS_2spINS_7IBinderEEE)
STUB(_ZN7android4view7Surface14readFromParcelEPKNS_6ParcelE)
STUB(_ZN7android7SurfaceC1ERKNS_2spINS_22IGraphicBufferProducerEEEb)
STUB(_ZNK7android4view7Surface13writeToParcelEPNS_6ParcelE)
STUB(_ZNK7android7Surface25getIGraphicBufferProducerEv)

// vtable for android::view::Surface (only read by the unused constructors)
extern "C" void* const view_surface_vtable[16] __asm__("_ZTVN7android4view7SurfaceE");
extern "C" void* const view_surface_vtable[16] = {};
