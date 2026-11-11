/*
 * Android 8.1 libui ABI for the stock hwcomposer.sp7731e.so, which gen-vendor.py
 * points at this library in place of libui.so (this library links the real
 * libui, so everything else still resolves there).
 *
 * - GraphicBuffer::lock and GraphicBufferMapper::lock gained two optional out
 *   parameters, so the old symbols no longer exist; forward them.
 * - Fence gained a virtual destructor, which moved its fields; the blob
 *   allocates and destroys 8.1-layout Fences itself, so implement the old
 *   constructor, destructor and wait() here on the old layout. The ARM C++
 *   ABI has constructors and destructors return `this`, and the blobs use
 *   it (the camera HAL frees the destructor's return value), so these do too.
 */
#include <errno.h>
#include <stdint.h>
#include <unistd.h>

#include <android/sync.h>
#include <ui/GraphicBuffer.h>
#include <ui/GraphicBufferMapper.h>
#include <utils/Errors.h>

using android::GraphicBuffer;
using android::GraphicBufferMapper;
using android::Rect;
using android::status_t;

namespace {
// android::Fence as of 8.1: LightRefBase<Fence> (the reference count), then
// the fd; 8 bytes, no vtable.
struct OldFence {
    int32_t refCount;
    int fd;
};
}  // namespace

extern "C" {

// android::GraphicBuffer::lock(uint32_t, void**)
status_t _ZN7android13GraphicBuffer4lockEjPPv(GraphicBuffer* buffer, uint32_t usage, void** vaddr) {
    return buffer->lock(usage, vaddr);
}

// android::GraphicBufferMapper::lock(buffer_handle_t, uint32_t, const Rect&, void**)
status_t _ZN7android19GraphicBufferMapper4lockEPK13native_handlejRKNS_4RectEPPv(
        GraphicBufferMapper* mapper, buffer_handle_t handle, uint32_t usage, const Rect& bounds,
        void** vaddr) {
    return mapper->lock(handle, usage, bounds, vaddr);
}

// android::Fence::Fence(int)
OldFence* _ZN7android5FenceC1Ei(OldFence* fence, int fd) {
    fence->refCount = 0;
    fence->fd = fd;
    return fence;
}

// android::Fence::~Fence()
OldFence* _ZN7android5FenceD1Ev(OldFence* fence) {
    if (fence->fd >= 0) close(fence->fd);
    return fence;
}

// android::Fence::wait(int)
status_t _ZN7android5Fence4waitEi(OldFence* fence, int timeout) {
    if (fence->fd == -1) return android::NO_ERROR;
    return sync_wait(fence->fd, timeout) < 0 ? -errno : android::NO_ERROR;
}

}  // extern "C"
