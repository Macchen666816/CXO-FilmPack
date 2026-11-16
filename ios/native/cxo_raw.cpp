#include "cxo_raw.h"
#include <libraw/libraw.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>
#include <memory>
#include <cerrno>
#if defined(DEBUG) && DEBUG
#include <mach/mach.h>
#endif

namespace {
void check(int result, const char *stage) {
    if (result) throw std::runtime_error(std::string(stage) + ": " + LibRaw::strerror(result));
}

struct Footprint {
#if defined(DEBUG) && DEBUG
    uint64_t sampled = 0;
    void record(const char *stage) {
        task_vm_info_data_t vm{};
        mach_msg_type_number_t count = TASK_VM_INFO_COUNT;
        if (task_info(mach_task_self(), TASK_VM_INFO, reinterpret_cast<task_info_t>(&vm), &count) == KERN_SUCCESS) {
            sampled = std::max(sampled, uint64_t(vm.phys_footprint));
            std::fprintf(stderr, "[CxoRaw memory] %s phys_footprint=%llu sampled_peak=%llu process_lifetime_peak=%lld resident=%llu resident_peak=%llu bytes\n",
                         stage, (unsigned long long)vm.phys_footprint, (unsigned long long)sampled,
                         (long long)vm.ledger_phys_footprint_peak, (unsigned long long)vm.resident_size,
                         (unsigned long long)vm.resident_size_peak);
        }
    }
    static int progress(void *context, LibRaw_progress, int, int) {
        static_cast<Footprint *>(context)->record("LibRaw progress"); return 0;
    }
#else
    void record(const char *) {}
#endif
    Footprint() { record("decode begin"); }
    ~Footprint() { record("decode end (LibRaw destroyed)"); }
};

// The fixed camera route's copy_mem_image RGB16 conversion, streamed in <= 1 MiB bands.
// LibRaw 0.22.3 has no public band-copy API. Keep its curve lookup and flip mapping exactly.
class CameraRaw : public LibRaw {
public:
    void writeCameraCache(int fd, int w, int h, Footprint &footprint) {
        if ((imgdata.progress_flags & LIBRAW_PROGRESS_THUMB_MASK) < LIBRAW_PROGRESS_PRE_INTERPOLATE)
            throw std::runtime_error("RAW image not developed");
        const auto &p = imgdata.params;
        if (p.output_bps != 16 || imgdata.idata.colors != 3 || !p.no_auto_bright ||
            p.bright != 1 || p.gamm[0] != 1 || p.gamm[1] != 1)
            throw std::runtime_error("Unsupported camera cache conversion parameters");
        // Identical to copy_mem_image: no_auto_bright leaves t_white=0x2000.
        if (libraw_internal_data.output_data.histogram) gamma_curve(p.gamm[0], p.gamm[1], 2, 65536);
        const int rows = std::max(1, int(1024 * 1024 / (size_t(w) * 6)));
        std::vector<uint16_t> band(size_t(w) * std::min(rows, h) * 3);
        const auto &sizes = imgdata.sizes;
        for (int top = 0; top < h; top += rows) {
            const int count = std::min(rows, h - top);
            for (int y = 0; y < count; ++y) for (int x = 0; x < w; ++x) {
                int sy = top + y, sx = x;
                if (sizes.flip & 4) std::swap(sy, sx);
                if (sizes.flip & 2) sy = sizes.height - 1 - sy;
                if (sizes.flip & 1) sx = sizes.width - 1 - sx;
                const auto &pixel = imgdata.image[size_t(sy) * sizes.width + sx];
                auto *dst = band.data() + (size_t(y) * w + x) * 3;
                for (int c = 0; c < 3; ++c) dst[c] = imgdata.color.curve[pixel[c]];
            }
            const auto *bytes = reinterpret_cast<const char *>(band.data());
            size_t remaining = size_t(w) * count * 6;
            while (remaining) {
                const ssize_t n = ::write(fd, bytes, remaining);
                if (n < 0 && errno == EINTR) continue;
                if (n <= 0) throw std::runtime_error("RAW cache write failed");
                bytes += n; remaining -= size_t(n);
            }
            footprint.record("cache band written");
        }
    }
};
struct CacheFile {
    int fd;
    explicit CacheFile(const char *path) : fd(::open(path, O_WRONLY | O_CREAT | O_EXCL, 0600)) {
        if (fd < 0) throw std::runtime_error("Cannot create RAW cache");
    }
    ~CacheFile() { if (fd >= 0) close(fd); }
    void finish() { int f = fd; fd = -1; if (close(f)) throw std::runtime_error("RAW cache close failed"); }
};

struct Mapping {
    int fd = -1;
    void *data = MAP_FAILED;
    size_t bytes;
    Mapping(const char *path, size_t size, bool write) : bytes(size) {
        fd = ::open(path, write ? O_RDWR | O_CREAT | O_EXCL : O_RDONLY, 0600);
        if (fd < 0) throw std::runtime_error("Cannot open RAW cache");
        struct stat st{};
        if ((write && ftruncate(fd, static_cast<off_t>(bytes))) ||
            (!write && (fstat(fd, &st) || st.st_size != static_cast<off_t>(bytes)))) {
            close(fd); fd = -1; if (write) unlink(path);
            throw std::runtime_error("Invalid RAW cache size or not enough space");
        }
        data = mmap(nullptr, bytes, write ? PROT_READ | PROT_WRITE : PROT_READ, MAP_SHARED, fd, 0);
        if (data == MAP_FAILED) { close(fd); fd = -1; if (write) unlink(path); throw std::runtime_error("Cannot map RAW cache"); }
    }
    ~Mapping() { if (data != MAP_FAILED) munmap(data, bytes); if (fd >= 0) close(fd); }
};
bool validSize(int w, int h) { return w > 0 && h > 0 && uint64_t(w) * h <= 64'000'000ULL; }  // 64MP: covers 61MP bodies (Sony A7R IV/V, Leica M11, Sigma fp L)
}
extern "C" int cxo_raw_probe(const char *path) {
    if (!path) return 0;
    try {
        // LibRaw is ~750KB; secondary threads only have 512KB of stack, so it must live on the heap.
        auto holder = std::make_unique<LibRaw>(); LibRaw &raw = *holder;
        raw.imgdata.rawparams.max_raw_memory_mb = 768;
        if (raw.open_file(path)) return 0;
        return raw.imgdata.idata.filters ? 1 : 2;
    } catch (...) { return 0; }
}
extern "C" int cxo_raw_decode_camera(const char *in, const char *out, cxo_raw_info *info, char *err, int errLen) {
    Footprint footprint;
    bool created = false;
    if (err && errLen > 0) err[0] = 0;
    try {
        if (!in || !out || !info || !strcmp(in, out)) throw std::runtime_error("Invalid RAW cache path");
        *info = {};
        auto holder = std::make_unique<CameraRaw>(); CameraRaw &raw = *holder;   // heap: see cxo_raw_probe
#if defined(DEBUG) && DEBUG
        raw.set_progress_handler(Footprint::progress, &footprint);
#endif
        raw.imgdata.rawparams.max_raw_memory_mb = 768;
        auto &p = raw.imgdata.params;
        p.use_camera_wb = 1; p.use_auto_wb = 0;
        p.output_color = 0; p.output_bps = 16;
        p.gamm[0] = 1; p.gamm[1] = 1;
        p.no_auto_bright = 1; p.bright = 1; p.highlight = 0;
        p.user_qual = 3; p.half_size = 0;
        // Keep LibRaw's default user_flip: cache pixels are already in display orientation.
        check(raw.open_file(in), "RAW metadata");
        const auto sizes = raw.imgdata.sizes;
        info->raw_bps = raw.imgdata.color.raw_bps;
        std::snprintf(info->model, sizeof(info->model), "%s", raw.imgdata.idata.model);
        std::snprintf(info->make, sizeof(info->make), "%s", raw.imgdata.idata.make);
        if (!raw.imgdata.idata.filters || !validSize(sizes.width, sizes.height))
            throw std::runtime_error("RAW exceeds supported Bayer/64MP range");
        const auto crop = sizes.raw_inset_crops[0];
        if (crop.cwidth > 0 && crop.cheight > 0 && crop.cleft >= sizes.left_margin && crop.ctop >= sizes.top_margin &&
            unsigned(crop.cleft - sizes.left_margin) + crop.cwidth <= sizes.width &&
            unsigned(crop.ctop - sizes.top_margin) + crop.cheight <= sizes.height) {
            p.cropbox[0] = crop.cleft - sizes.left_margin; p.cropbox[1] = crop.ctop - sizes.top_margin;
            p.cropbox[2] = crop.cwidth; p.cropbox[3] = crop.cheight;
        }
        check(raw.unpack(), "RAW unpack");
        footprint.record("unpacked");
        check(raw.dcraw_process(), "RAW develop");
        footprint.record("developed");
        int w, h, colors, bps;
        raw.get_mem_image_format(&w, &h, &colors, &bps);
        if (!validSize(w, h) || colors != 3 || bps != 16) throw std::runtime_error("Unsupported developed RAW format");
        {
            CacheFile cache(out);
            created = true;
            raw.writeCameraCache(cache.fd, w, h, footprint);
            cache.finish();
        }
        info->width = w; info->height = h;
        const auto &c = raw.imgdata.color;
        info->black = c.black; info->maximum_after_process = c.maximum; info->data_maximum = c.data_maximum;
        info->warnings = raw.imgdata.process_warnings;
        std::copy_n(c.cam_mul, 4, info->cam_mul); std::copy_n(c.pre_mul, 4, info->pre_mul);
        return 0;
    } catch (const std::exception &e) {
        if (created) unlink(out);
        if (err && errLen > 0) std::snprintf(err, errLen, "%s", e.what());
    } catch (...) {
        if (created) unlink(out);
        if (err && errLen > 0) std::snprintf(err, errLen, "RAW processing failed");
    }
    return -1;
}
extern "C" int cxo_raw_load_scaled(const char *path, int w, int h, int edge, uint16_t *out, int *outW, int *outH) {
    if (!path || !outW || !outH || !validSize(w, h)) return -1;
    const double scale = edge > 0 ? std::min(1.0, double(edge) / std::max(w, h)) : 1.0;
    const int dw = std::max(1, int(std::lround(w * scale))), dh = std::max(1, int(std::lround(h * scale)));
    *outW = dw; *outH = dh;
    if (!out) return 0;
    try {
        Mapping cache(path, size_t(w) * h * 6, false);
        const auto *pixels = static_cast<const uint16_t *>(cache.data);
        for (int y = 0; y < dh; ++y) {
            const int y0 = int(int64_t(y) * h / dh), y1 = int(int64_t(y + 1) * h / dh);
            for (int x = 0; x < dw; ++x) {
                const int x0 = int(int64_t(x) * w / dw), x1 = int(int64_t(x + 1) * w / dw);
                uint64_t sum[3] = {};
                for (int sy = y0; sy < y1; ++sy) {
                    const auto *row = pixels + (size_t(sy) * w + x0) * 3;
                    for (int sx = x0; sx < x1; ++sx, row += 3)
                        for (int c = 0; c < 3; ++c) sum[c] += row[c];
                }
                const uint64_t n = uint64_t(x1 - x0) * (y1 - y0);
                auto *dst = out + (size_t(y) * dw + x) * 4;
                for (int c = 0; c < 3; ++c) dst[c] = uint16_t((sum[c] + n / 2) / n);
                dst[3] = 65535;
            }
        }
        return 0;
    } catch (...) { return -1; }
}
