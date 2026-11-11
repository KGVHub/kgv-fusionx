/*
 * Android 13's audio HAL service (android.hardware.audio@7.1-impl) over the
 * stock Android 8.1 audio.primary.sp7731e.so.
 *
 * Android 9 inserted get_microphones into the middle of struct
 * audio_hw_device, so every later slot is off by one for an 8.1 module (a
 * call to set_master_mute lands on the old get_master_mute), and it appended
 * fields to audio_stream_out and audio_stream_in, which an 8.1 module never
 * sets (the service reads whatever its private data holds there). This module
 * (selected with ro.hardware.audio.primary=sp7731e_a13) loads the stock one
 * and hands the service Android 13-layout device and stream structs whose
 * functions forward to the stock ones; the newer functions are left NULL,
 * which the service treats as unsupported.
 */
#define LOG_TAG "audio_wrap"

#include <dlfcn.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>

#include <hardware/audio.h>
#include <hardware/hardware.h>
#include <log/log.h>

#define STOCK_HAL "/vendor/lib/hw/audio.primary.sp7731e.so"

/* struct audio_hw_device as of Android 8.1 (no get_microphones, nothing after
 * set_audio_port_config). The stream structs are prefixes of today's. */
struct audio_hw_device_v8 {
    struct hw_device_t common;
    uint32_t (*get_supported_devices)(const struct audio_hw_device_v8* dev);
    int (*init_check)(const struct audio_hw_device_v8* dev);
    int (*set_voice_volume)(struct audio_hw_device_v8* dev, float volume);
    int (*set_master_volume)(struct audio_hw_device_v8* dev, float volume);
    int (*get_master_volume)(struct audio_hw_device_v8* dev, float* volume);
    int (*set_mode)(struct audio_hw_device_v8* dev, audio_mode_t mode);
    int (*set_mic_mute)(struct audio_hw_device_v8* dev, bool state);
    int (*get_mic_mute)(const struct audio_hw_device_v8* dev, bool* state);
    int (*set_parameters)(struct audio_hw_device_v8* dev, const char* kv_pairs);
    char* (*get_parameters)(const struct audio_hw_device_v8* dev, const char* keys);
    size_t (*get_input_buffer_size)(const struct audio_hw_device_v8* dev,
                                    const struct audio_config* config);
    int (*open_output_stream)(struct audio_hw_device_v8* dev, audio_io_handle_t handle,
                              audio_devices_t devices, audio_output_flags_t flags,
                              struct audio_config* config, struct audio_stream_out** stream_out,
                              const char* address);
    void (*close_output_stream)(struct audio_hw_device_v8* dev,
                                struct audio_stream_out* stream_out);
    int (*open_input_stream)(struct audio_hw_device_v8* dev, audio_io_handle_t handle,
                             audio_devices_t devices, struct audio_config* config,
                             struct audio_stream_in** stream_in, audio_input_flags_t flags,
                             const char* address, audio_source_t source);
    void (*close_input_stream)(struct audio_hw_device_v8* dev, struct audio_stream_in* stream_in);
    int (*dump)(const struct audio_hw_device_v8* dev, int fd);
    int (*set_master_mute)(struct audio_hw_device_v8* dev, bool mute);
    int (*get_master_mute)(struct audio_hw_device_v8* dev, bool* mute);
    int (*create_audio_patch)(struct audio_hw_device_v8* dev, unsigned int num_sources,
                              const struct audio_port_config* sources, unsigned int num_sinks,
                              const struct audio_port_config* sinks,
                              audio_patch_handle_t* handle);
    int (*release_audio_patch)(struct audio_hw_device_v8* dev, audio_patch_handle_t handle);
    int (*get_audio_port)(struct audio_hw_device_v8* dev, struct audio_port* port);
    int (*set_audio_port_config)(struct audio_hw_device_v8* dev,
                                 const struct audio_port_config* config);
};

struct wrap_dev {
    struct audio_hw_device dev; /* first: the service's pointer is ours */
    struct audio_hw_device_v8* old;
    void* dso;
};

/* The stock stream pointer is stored just before the struct handed out, so
 * the audio_stream functions (shared by output and input) can find it. */
struct wrap_out {
    void* old;
    struct audio_stream_out out;
};
struct wrap_in {
    void* old;
    struct audio_stream_in in;
};

#define OLD_DEV(d) (((struct wrap_dev*)(d))->old)
#define OLD_STREAM(s) ((struct audio_stream*)((void* const*)(s))[-1])
#define OLD_OUT(s) ((struct audio_stream_out*)((void* const*)(s))[-1])
#define OLD_IN(s) ((struct audio_stream_in*)((void* const*)(s))[-1])

/* ---- struct audio_stream ---- */
static uint32_t s_get_sample_rate(const struct audio_stream* s) {
    return OLD_STREAM(s)->get_sample_rate(OLD_STREAM(s));
}
static int s_set_sample_rate(struct audio_stream* s, uint32_t rate) {
    return OLD_STREAM(s)->set_sample_rate(OLD_STREAM(s), rate);
}
static size_t s_get_buffer_size(const struct audio_stream* s) {
    return OLD_STREAM(s)->get_buffer_size(OLD_STREAM(s));
}
static audio_channel_mask_t s_get_channels(const struct audio_stream* s) {
    return OLD_STREAM(s)->get_channels(OLD_STREAM(s));
}
static audio_format_t s_get_format(const struct audio_stream* s) {
    return OLD_STREAM(s)->get_format(OLD_STREAM(s));
}
static int s_set_format(struct audio_stream* s, audio_format_t format) {
    return OLD_STREAM(s)->set_format(OLD_STREAM(s), format);
}
static int s_standby(struct audio_stream* s) {
    return OLD_STREAM(s)->standby(OLD_STREAM(s));
}
static int s_dump(const struct audio_stream* s, int fd) {
    return OLD_STREAM(s)->dump(OLD_STREAM(s), fd);
}
static audio_devices_t s_get_device(const struct audio_stream* s) {
    return OLD_STREAM(s)->get_device(OLD_STREAM(s));
}
static int s_set_device(struct audio_stream* s, audio_devices_t device) {
    return OLD_STREAM(s)->set_device(OLD_STREAM(s), device);
}
static int s_set_parameters(struct audio_stream* s, const char* kv_pairs) {
    return OLD_STREAM(s)->set_parameters(OLD_STREAM(s), kv_pairs);
}
static char* s_get_parameters(const struct audio_stream* s, const char* keys) {
    return OLD_STREAM(s)->get_parameters(OLD_STREAM(s), keys);
}
static int s_add_audio_effect(const struct audio_stream* s, effect_handle_t effect) {
    return OLD_STREAM(s)->add_audio_effect(OLD_STREAM(s), effect);
}
static int s_remove_audio_effect(const struct audio_stream* s, effect_handle_t effect) {
    return OLD_STREAM(s)->remove_audio_effect(OLD_STREAM(s), effect);
}

static void wrap_common(struct audio_stream* w, const struct audio_stream* old) {
#define FWD(f) w->f = old->f ? s_##f : NULL
    FWD(get_sample_rate);
    FWD(set_sample_rate);
    FWD(get_buffer_size);
    FWD(get_channels);
    FWD(get_format);
    FWD(set_format);
    FWD(standby);
    FWD(dump);
    FWD(get_device);
    FWD(set_device);
    FWD(set_parameters);
    FWD(get_parameters);
    FWD(add_audio_effect);
    FWD(remove_audio_effect);
#undef FWD
}

/* ---- struct audio_stream_out (8.1 fields) ---- */
static uint32_t o_get_latency(const struct audio_stream_out* s) {
    return OLD_OUT(s)->get_latency(OLD_OUT(s));
}
static int o_set_volume(struct audio_stream_out* s, float left, float right) {
    return OLD_OUT(s)->set_volume(OLD_OUT(s), left, right);
}
static ssize_t o_write(struct audio_stream_out* s, const void* buffer, size_t bytes) {
    return OLD_OUT(s)->write(OLD_OUT(s), buffer, bytes);
}
static int o_get_render_position(const struct audio_stream_out* s, uint32_t* dsp_frames) {
    return OLD_OUT(s)->get_render_position(OLD_OUT(s), dsp_frames);
}
static int o_get_next_write_timestamp(const struct audio_stream_out* s, int64_t* timestamp) {
    return OLD_OUT(s)->get_next_write_timestamp(OLD_OUT(s), timestamp);
}
static int o_set_callback(struct audio_stream_out* s, stream_callback_t callback, void* cookie) {
    return OLD_OUT(s)->set_callback(OLD_OUT(s), callback, cookie);
}
static int o_pause(struct audio_stream_out* s) {
    return OLD_OUT(s)->pause(OLD_OUT(s));
}
static int o_resume(struct audio_stream_out* s) {
    return OLD_OUT(s)->resume(OLD_OUT(s));
}
static int o_drain(struct audio_stream_out* s, audio_drain_type_t type) {
    return OLD_OUT(s)->drain(OLD_OUT(s), type);
}
static int o_flush(struct audio_stream_out* s) {
    return OLD_OUT(s)->flush(OLD_OUT(s));
}
static int o_get_presentation_position(const struct audio_stream_out* s, uint64_t* frames,
                                       struct timespec* timestamp) {
    return OLD_OUT(s)->get_presentation_position(OLD_OUT(s), frames, timestamp);
}
static int o_start(const struct audio_stream_out* s) {
    return OLD_OUT(s)->start(OLD_OUT(s));
}
static int o_stop(const struct audio_stream_out* s) {
    return OLD_OUT(s)->stop(OLD_OUT(s));
}
static int o_create_mmap_buffer(const struct audio_stream_out* s, int32_t min_size_frames,
                                struct audio_mmap_buffer_info* info) {
    return OLD_OUT(s)->create_mmap_buffer(OLD_OUT(s), min_size_frames, info);
}
static int o_get_mmap_position(const struct audio_stream_out* s,
                               struct audio_mmap_position* position) {
    return OLD_OUT(s)->get_mmap_position(OLD_OUT(s), position);
}

/* ---- struct audio_stream_in (8.1 fields) ---- */
static int i_set_gain(struct audio_stream_in* s, float gain) {
    return OLD_IN(s)->set_gain(OLD_IN(s), gain);
}
static ssize_t i_read(struct audio_stream_in* s, void* buffer, size_t bytes) {
    return OLD_IN(s)->read(OLD_IN(s), buffer, bytes);
}
static uint32_t i_get_input_frames_lost(struct audio_stream_in* s) {
    return OLD_IN(s)->get_input_frames_lost(OLD_IN(s));
}
static int i_get_capture_position(const struct audio_stream_in* s, int64_t* frames,
                                  int64_t* time) {
    return OLD_IN(s)->get_capture_position(OLD_IN(s), frames, time);
}
static int i_start(const struct audio_stream_in* s) {
    return OLD_IN(s)->start(OLD_IN(s));
}
static int i_stop(const struct audio_stream_in* s) {
    return OLD_IN(s)->stop(OLD_IN(s));
}
static int i_create_mmap_buffer(const struct audio_stream_in* s, int32_t min_size_frames,
                                struct audio_mmap_buffer_info* info) {
    return OLD_IN(s)->create_mmap_buffer(OLD_IN(s), min_size_frames, info);
}
static int i_get_mmap_position(const struct audio_stream_in* s,
                               struct audio_mmap_position* position) {
    return OLD_IN(s)->get_mmap_position(OLD_IN(s), position);
}

/* ---- struct audio_hw_device ---- */
static uint32_t d_get_supported_devices(const struct audio_hw_device* d) {
    return OLD_DEV(d)->get_supported_devices(OLD_DEV(d));
}
static int d_init_check(const struct audio_hw_device* d) {
    return OLD_DEV(d)->init_check(OLD_DEV(d));
}
static int d_set_voice_volume(struct audio_hw_device* d, float volume) {
    return OLD_DEV(d)->set_voice_volume(OLD_DEV(d), volume);
}
static int d_set_master_volume(struct audio_hw_device* d, float volume) {
    return OLD_DEV(d)->set_master_volume(OLD_DEV(d), volume);
}
static int d_get_master_volume(struct audio_hw_device* d, float* volume) {
    return OLD_DEV(d)->get_master_volume(OLD_DEV(d), volume);
}
static int d_set_mode(struct audio_hw_device* d, audio_mode_t mode) {
    return OLD_DEV(d)->set_mode(OLD_DEV(d), mode);
}
static int d_set_mic_mute(struct audio_hw_device* d, bool state) {
    return OLD_DEV(d)->set_mic_mute(OLD_DEV(d), state);
}
static int d_get_mic_mute(const struct audio_hw_device* d, bool* state) {
    return OLD_DEV(d)->get_mic_mute(OLD_DEV(d), state);
}
static int d_set_parameters(struct audio_hw_device* d, const char* kv_pairs) {
    return OLD_DEV(d)->set_parameters(OLD_DEV(d), kv_pairs);
}
static char* d_get_parameters(const struct audio_hw_device* d, const char* keys) {
    return OLD_DEV(d)->get_parameters(OLD_DEV(d), keys);
}
static size_t d_get_input_buffer_size(const struct audio_hw_device* d,
                                      const struct audio_config* config) {
    return OLD_DEV(d)->get_input_buffer_size(OLD_DEV(d), config);
}
/* The service calls dump without a NULL check, and the stock module has none. */
static int d_dump(const struct audio_hw_device* d, int fd) {
    return OLD_DEV(d)->dump ? OLD_DEV(d)->dump(OLD_DEV(d), fd) : 0;
}
static int d_set_master_mute(struct audio_hw_device* d, bool mute) {
    return OLD_DEV(d)->set_master_mute(OLD_DEV(d), mute);
}
static int d_get_master_mute(struct audio_hw_device* d, bool* mute) {
    return OLD_DEV(d)->get_master_mute(OLD_DEV(d), mute);
}

static int d_open_output_stream(struct audio_hw_device* d, audio_io_handle_t handle,
                                audio_devices_t devices, audio_output_flags_t flags,
                                struct audio_config* config,
                                struct audio_stream_out** stream_out, const char* address) {
    struct audio_stream_out* old = NULL;
    *stream_out = NULL;
    int ret = OLD_DEV(d)->open_output_stream(OLD_DEV(d), handle, devices, flags, config, &old,
                                             address);
    if (ret != 0 || old == NULL) return ret != 0 ? ret : -EINVAL;
    struct wrap_out* w = calloc(1, sizeof(*w));
    if (w == NULL) {
        OLD_DEV(d)->close_output_stream(OLD_DEV(d), old);
        return -ENOMEM;
    }
    w->old = old;
    wrap_common(&w->out.common, &old->common);
#define FWD(f) w->out.f = old->f ? o_##f : NULL
    FWD(get_latency);
    FWD(set_volume);
    FWD(write);
    FWD(get_render_position);
    FWD(get_next_write_timestamp);
    FWD(set_callback);
    FWD(pause);
    FWD(resume);
    FWD(drain);
    FWD(flush);
    FWD(get_presentation_position);
    FWD(start);
    FWD(stop);
    FWD(create_mmap_buffer);
    FWD(get_mmap_position);
#undef FWD
    *stream_out = &w->out;
    return 0;
}

static void d_close_output_stream(struct audio_hw_device* d, struct audio_stream_out* s) {
    if (s == NULL) return;
    struct wrap_out* w = (struct wrap_out*)((char*)s - offsetof(struct wrap_out, out));
    OLD_DEV(d)->close_output_stream(OLD_DEV(d), w->old);
    free(w);
}

static int d_open_input_stream(struct audio_hw_device* d, audio_io_handle_t handle,
                               audio_devices_t devices, struct audio_config* config,
                               struct audio_stream_in** stream_in, audio_input_flags_t flags,
                               const char* address, audio_source_t source) {
    struct audio_stream_in* old = NULL;
    *stream_in = NULL;
    int ret = OLD_DEV(d)->open_input_stream(OLD_DEV(d), handle, devices, config, &old, flags,
                                            address, source);
    if (ret != 0 || old == NULL) return ret != 0 ? ret : -EINVAL;
    struct wrap_in* w = calloc(1, sizeof(*w));
    if (w == NULL) {
        OLD_DEV(d)->close_input_stream(OLD_DEV(d), old);
        return -ENOMEM;
    }
    w->old = old;
    wrap_common(&w->in.common, &old->common);
#define FWD(f) w->in.f = old->f ? i_##f : NULL
    FWD(set_gain);
    FWD(read);
    FWD(get_input_frames_lost);
    FWD(get_capture_position);
    FWD(start);
    FWD(stop);
    FWD(create_mmap_buffer);
    FWD(get_mmap_position);
#undef FWD
    *stream_in = &w->in;
    return 0;
}

static void d_close_input_stream(struct audio_hw_device* d, struct audio_stream_in* s) {
    if (s == NULL) return;
    struct wrap_in* w = (struct wrap_in*)((char*)s - offsetof(struct wrap_in, in));
    OLD_DEV(d)->close_input_stream(OLD_DEV(d), w->old);
    free(w);
}

static int d_close(hw_device_t* device) {
    struct wrap_dev* w = (struct wrap_dev*)device;
    int ret = w->old->common.close(&w->old->common);
    free(w);
    return ret;
}

static int wrap_open(const hw_module_t* module, const char* name, hw_device_t** device) {
    *device = NULL;
    if (strcmp(name, AUDIO_HARDWARE_INTERFACE) != 0) return -EINVAL;

    void* dso = dlopen(STOCK_HAL, RTLD_NOW);
    if (dso == NULL) {
        ALOGE("can't load %s: %s", STOCK_HAL, dlerror());
        return -EINVAL;
    }
    struct hw_module_t* old_module = dlsym(dso, HAL_MODULE_INFO_SYM_AS_STR);
    if (old_module == NULL || old_module->methods == NULL) {
        ALOGE("%s has no %s", STOCK_HAL, HAL_MODULE_INFO_SYM_AS_STR);
        dlclose(dso);
        return -EINVAL;
    }
    old_module->dso = dso;

    hw_device_t* old_common = NULL;
    int ret = old_module->methods->open(old_module, name, &old_common);
    if (ret != 0 || old_common == NULL) {
        ALOGE("stock open failed: %d", ret);
        return ret != 0 ? ret : -EINVAL;
    }
    struct audio_hw_device_v8* old = (struct audio_hw_device_v8*)old_common;
    if (old->common.version >= AUDIO_DEVICE_API_VERSION_3_0) {
        /* Audio patches pass structs whose layout has changed since 8.1. */
        ALOGE("stock device is API %#x; only 2.x can be wrapped", old->common.version);
        old->common.close(&old->common);
        return -EINVAL;
    }

    struct wrap_dev* w = calloc(1, sizeof(*w));
    if (w == NULL) {
        old->common.close(&old->common);
        return -ENOMEM;
    }
    w->old = old;
    w->dso = dso;
    w->dev.common = old->common;
    w->dev.common.module = (struct hw_module_t*)module;
    w->dev.common.close = d_close;
#define FWD(f) w->dev.f = old->f ? d_##f : NULL
    FWD(get_supported_devices);
    FWD(init_check);
    FWD(set_voice_volume);
    FWD(set_master_volume);
    FWD(get_master_volume);
    FWD(set_mode);
    FWD(set_mic_mute);
    FWD(get_mic_mute);
    FWD(set_parameters);
    FWD(get_parameters);
    FWD(get_input_buffer_size);
    FWD(open_output_stream);
    FWD(close_output_stream);
    FWD(open_input_stream);
    FWD(close_input_stream);
    w->dev.dump = d_dump;
    FWD(set_master_mute);
    FWD(get_master_mute);
#undef FWD
    ALOGI("wrapped %s (device API %#x)", STOCK_HAL, old->common.version);
    *device = &w->dev.common;
    return 0;
}

static struct hw_module_methods_t wrap_methods = {
        .open = wrap_open,
};

__attribute__((visibility("default"))) struct audio_module HAL_MODULE_INFO_SYM = {
        .common =
                {
                        .tag = HARDWARE_MODULE_TAG,
                        .module_api_version = AUDIO_MODULE_API_VERSION_0_1,
                        .hal_api_version = HARDWARE_HAL_API_VERSION,
                        .id = AUDIO_HARDWARE_MODULE_ID,
                        .name = "Android 13 wrapper for the 8.1 SPRD audio HAL",
                        .author = "KGV",
                        .methods = &wrap_methods,
                },
};
