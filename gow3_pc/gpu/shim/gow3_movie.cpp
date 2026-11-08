#include "gow3_movie.h"
#include "gow3_movie_bridge.h"
#include "core/memory.h"

#include <array>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <unordered_map>
#include <vector>

extern "C" {
#include <libswscale/swscale.h>
}

extern "C" void* runtime_map_guest_memory(std::uint64_t size);

namespace Gow3Movie {
namespace {

struct Size {
    std::uint32_t width = 0, height = 0;
};

const Size& Display() {
    static const Size size = [] {
        Size s;
        if (const char* env = std::getenv("GOW3_MOVIE_SIZE")) {
            if (std::sscanf(env, "%ux%u", &s.width, &s.height) != 2 || s.width < 64 || s.height < 64 ||
                s.width > 7680 || s.height > 4320) {
                s = {};
            }
        }
        return s;
    }();
    return size;
}

constexpr std::uint32_t Align64(std::uint32_t value) {
    return (value + 63) & ~63u;
}

// The frame buffers of the open decoders (a game cycles through a handful): base, and the layout
// packed as width | height << 16 | pitch << 32 | rows << 48; and their scratch frames.
struct Frame {
    std::atomic<std::uint64_t> base{0};
    std::atomic<std::uint64_t> layout{0};
};
std::array<Frame, 32> frames;
std::atomic<std::uint32_t> next{0};

struct Scratch {
    std::atomic<std::uint64_t> base{0};
    std::atomic<std::uint64_t> address{0};
};
std::array<Scratch, 16> scratch;
std::mutex scratch_mutex;

std::uint64_t FindScratch(std::uint64_t base) {
    for (auto& entry : scratch) {
        if (entry.base.load(std::memory_order_acquire) == base) {
            return entry.address.load(std::memory_order_acquire);
        }
    }
    return 0;
}

} // namespace

bool DisplaySize(std::uint32_t& width, std::uint32_t& height) {
    width = Display().width;
    height = Display().height;
    return width != 0;
}

std::uint32_t ScaledPitch() {
    return Align64(Display().width);
}

std::uint32_t ScaledRows() {
    return Align64(Display().height);
}

std::uint32_t ScaledChromaPitch() {
    return Align64(Display().width / 2) * 2;
}

std::uint64_t ScaledFrameBytes() {
    return std::uint64_t(ScaledPitch()) * ScaledRows() + std::uint64_t(ScaledChromaPitch()) * ScaledRows() / 2;
}

void RecordFrame(std::uint64_t base, std::uint32_t width, std::uint32_t height, std::uint32_t pitch,
                 std::uint32_t rows) {
    if (!Display().width || !base) {
        return;
    }
    const std::uint64_t layout = std::uint64_t(width & 0xffff) | std::uint64_t(height & 0xffff) << 16 |
                                 std::uint64_t(pitch & 0xffff) << 32 | std::uint64_t(rows & 0xffff) << 48;
    for (auto& frame : frames) {
        if (frame.base.load(std::memory_order_acquire) == base) {
            frame.layout.store(layout, std::memory_order_release);
            return;
        }
    }
    auto& frame = frames[next.fetch_add(1, std::memory_order_relaxed) % frames.size()];
    frame.base.store(0, std::memory_order_release);
    frame.layout.store(layout, std::memory_order_release);
    frame.base.store(base, std::memory_order_release);
}

std::uint8_t* ScratchFrame(std::uint64_t base) {
    if (!Display().width || !base) {
        return nullptr;
    }
    if (const std::uint64_t address = FindScratch(base)) {
        return reinterpret_cast<std::uint8_t*>(address);
    }
    std::scoped_lock lock{scratch_mutex};
    if (const std::uint64_t address = FindScratch(base)) {
        return reinterpret_cast<std::uint8_t*>(address);
    }
    for (auto& entry : scratch) {
        if (entry.base.load(std::memory_order_acquire)) {
            continue;
        }
        const std::uint64_t bytes = ScaledFrameBytes();
        void* address = runtime_map_guest_memory(bytes);
        if (!address) {
            std::printf("Movie: no scratch frame (%llu bytes) for %#llx; movies are shown as decoded\n",
                        (unsigned long long)bytes, (unsigned long long)base);
            std::fflush(stdout);
            return nullptr;
        }
        entry.address.store(reinterpret_cast<std::uint64_t>(address), std::memory_order_release);
        entry.base.store(base, std::memory_order_release);
        std::printf("Movie: frame buffer %#llx read as %ux%u from %p\n", (unsigned long long)base,
                    Display().width, Display().height, address);
        std::fflush(stdout);
        return static_cast<std::uint8_t*>(address);
    }
    return nullptr;
}

namespace {
// The literals of a shader made for 1920x1088 movie frames: 1/1920, 0.5/1920, 1/1088, 0.5/1088.
constexpr std::uint32_t kU = 0x3a088889, kHalfU = 0x39888889, kV = 0x3a70f0f1, kHalfV = 0x39f0f0f1;
} // namespace

bool IsMovieShader(const std::uint32_t* code, std::size_t words) {
    bool u = false, v = false;
    for (std::size_t i = 0; i < words && !(u && v); ++i) {
        u |= code[i] == kU;
        v |= code[i] == kV;
    }
    return u && v;
}

std::uint64_t ShaderKey(std::uint64_t hash, const std::uint32_t* code, std::size_t words) {
    if (!Display().width || !code) {
        return hash;
    }
    // Per thread: no lock on the draw path; one scan per shader and thread.
    thread_local std::unordered_map<std::uint64_t, std::uint64_t> keys;
    if (const auto it = keys.find(hash); it != keys.end()) {
        return it->second;
    }
    std::uint64_t key = hash;
    if (IsMovieShader(code, words)) {
        key ^= (std::uint64_t(Display().width) << 32 | Display().height) * 0x9e3779b97f4a7c17ull;
        static std::atomic<unsigned> matched{0};
        if (matched.fetch_add(1, std::memory_order_relaxed) < 4) {
            std::printf("TLOU movie NV12 shader: rescaled 1920x1088 Y/UV coordinates "
                        "for %ux%u; shader=%llx\n", Display().width, Display().height,
                        static_cast<unsigned long long>(hash));
            std::fflush(stdout);
        }
    }
    keys.emplace(hash, key);
    return key;
}

bool MovieShaderConstant(std::uint32_t bits, float& patched) {
    const Size& display = Display();
    if (!display.width) {
        return false;
    }
    // The frame is the display size now; vertically the shader keeps its 1080 / 1088 proportion.
    const float width = float(display.width), height = float(display.height) * 1088.0f / 1080.0f;
    switch (bits) {
    case kU:
        patched = 1.0f / width;
        return true;
    case kHalfU:
        patched = 0.5f / width;
        return true;
    case kV:
        patched = 1.0f / height;
        return true;
    case kHalfV:
        patched = 0.5f / height;
        return true;
    default:
        return false;
    }
}

bool Remap(std::uint64_t& address, std::uint32_t& width, std::uint32_t& height, std::uint32_t& pitch,
           std::uint32_t bits) {
    const Size& display = Display();
    if (!display.width) {
        return false;
    }
    const bool luma = bits == 8 && width == display.width && height == display.height;
    const bool chroma = bits == 16 && width == display.width / 2 && height == display.height / 2;
    if (!luma && !chroma) {
        return false;
    }
    // The player's chroma offset: (width * align(height, 64)) & ~63, in 32 bits.
    const std::uint64_t chroma_offset = std::uint32_t(display.width * Align64(display.height)) & ~63u;
    for (auto& frame : frames) {
        const std::uint64_t base = frame.base.load(std::memory_order_acquire);
        if (!base || address != (luma ? base : base + chroma_offset)) {
            continue;
        }
        const std::uint64_t layout = frame.layout.load(std::memory_order_acquire);
        const std::uint32_t w = layout & 0xffff, h = (layout >> 16) & 0xffff;
        const std::uint32_t p = (layout >> 32) & 0xffff, rows = (layout >> 48) & 0xffff;
        // The actual Android C decoder downscales into its existing guest buffer if
        // the result fits. Detect that layout before searching for an older scratch
        // entry; Y begins at base and UV begins at base + scaled Y plane bytes.
        if (w == display.width && h == display.height &&
            p == ScaledPitch() && rows == ScaledRows()) {
            pitch = luma ? p : ScaledChromaPitch() / 2;
            static std::atomic<unsigned> inplace_remaps{0};
            if (inplace_remaps.fetch_add(1, std::memory_order_relaxed) < 8) {
                std::printf("TLOU movie NV12 texture: in-place %s %#llx %ux%u pitch=%u\n",
                            luma ? "Y" : "UV", static_cast<unsigned long long>(address),
                            width, height, pitch);
                std::fflush(stdout);
            }
            return true;
        }
        if (const std::uint64_t copy = FindScratch(base)) {
            address = luma ? copy : copy + std::uint64_t(ScaledPitch()) * ScaledRows();
            pitch = luma ? ScaledPitch() : ScaledChromaPitch() / 2;
            static std::atomic<unsigned> remapped{0};
            if (remapped.fetch_add(1, std::memory_order_relaxed) < 8) {
                std::printf("TLOU movie NV12 texture: %s remapped to scratch "
                            "%#llx %ux%u pitch=%u (no gameplay texture changes)\n",
                            luma ? "Y" : "UV", static_cast<unsigned long long>(address),
                            width, height, pitch);
                std::fflush(stdout);
            }
            return true;
        }
        if (luma) {
            width = w;
            height = h;
            pitch = p;
        } else {
            address = base + std::uint64_t(p) * rows;
            width = w / 2;
            height = h / 2;
            pitch = p / 2;
        }
        return true;
    }
    return false;
}

} // namespace Gow3Movie

// Android TLOU's actual guest runtime is compiled from runtime_videodec.c,
// NOT the C++ shim/videodec2/videodec2_impl.cpp. Without this bridge the
// existing GOW3_MOVIE_SIZE machinery receives no decoded frames, and 720p
// shaders read the native 1920x1080 luma/UV buffers using a 1280x720 pitch and
// chroma offset. That presents as green, oversized movies.
//
// This function is called only from the video decoder thread and only when
// GOW3_MOVIE_SIZE is explicitly enabled by the unified-resolution launcher.
// FFmpeg decoded source data stays intact. A reduced 720p movie instead uses the already
// allocated guest frame buffer; only larger targets need private guest scratch memory.
extern "C" int gow3_movie_scale_nv12(
    std::uint64_t guest_base, std::uint64_t guest_capacity, const std::uint8_t* src_y,
    const std::uint8_t* src_uv, std::uint32_t src_width,
    std::uint32_t src_height, int src_y_stride, int src_uv_stride,
    std::uint32_t* display_width, std::uint32_t* display_height) {
    std::uint32_t width = 0, height = 0;
    if (!guest_base || !src_y || !src_uv || !display_width || !display_height ||
        !Gow3Movie::DisplaySize(width, height) || !width || !height ||
        !src_width || !src_height || (src_width & 1) || (src_height & 1) ||
        (width & 1) || (height & 1) ||
        src_y_stride < int(src_width) || src_uv_stride < int(src_width) ||
        (src_width == width && src_height == height))
        return 0;

    // For downscales, reuse the decoded buffer's existing guest memory instead of
    // allocating another 1.47 MiB per frame. The source pointers belong to FFmpeg,
    // not to guest_base. Preserve a scratch fallback for oversized target frames.
    const std::uint32_t pitch = Gow3Movie::ScaledPitch();
    const std::uint32_t chroma_pitch = Gow3Movie::ScaledChromaPitch();
    const std::uint32_t rows = Gow3Movie::ScaledRows();
    const std::uint64_t bytes = Gow3Movie::ScaledFrameBytes();
    if (!bytes || bytes > 128ull * 1024 * 1024) return 0;
    const bool in_place = gow3_movie_can_scale_in_place(src_width, src_height, width, height,
                                                       bytes, guest_capacity) != 0;
    std::uint8_t* target = in_place
        ? reinterpret_cast<std::uint8_t*>(static_cast<std::uintptr_t>(guest_base))
        : Gow3Movie::ScratchFrame(guest_base);
    if (!target) return 0;

    struct Conversion {
        SwsContext* sws = nullptr;
        std::vector<std::uint8_t> output;
        ~Conversion() { sws_freeContext(sws); }
    };
    thread_local Conversion converter;
    converter.sws = sws_getCachedContext(
        converter.sws, int(src_width), int(src_height), AV_PIX_FMT_NV12,
        int(width), int(height), AV_PIX_FMT_NV12, SWS_FAST_BILINEAR,
        nullptr, nullptr, nullptr);
    if (!converter.sws) return 0;

    converter.output.resize(static_cast<std::size_t>(bytes));
    std::uint8_t* y_out = converter.output.data();
    std::uint8_t* uv_out = y_out + std::uint64_t(pitch) * rows;
    std::memset(y_out, 16, std::uint64_t(pitch) * rows);
    std::memset(uv_out, 128, std::uint64_t(chroma_pitch) * rows / 2);

    const std::uint8_t* src[4] = {src_y, src_uv, nullptr, nullptr};
    const int src_stride[4] = {src_y_stride, src_uv_stride, 0, 0};
    std::uint8_t* dst[4] = {y_out, uv_out, nullptr, nullptr};
    const int dst_stride[4] = {int(pitch), int(chroma_pitch), 0, 0};
    const int processed = sws_scale(converter.sws, src, src_stride, 0,
                                    int(src_height), dst, dst_stride);
    if (processed != int(height)) return 0;
    // The movie's shaders sample a 64-row-aligned layout, not the decoder's
    // 16-row-aligned source. Replicate edge rows in both NV12 planes to avoid
    // sampling uninitialized/padding chroma and producing green bands.
    for (std::uint32_t y = height; y < rows; ++y)
        std::memcpy(y_out + std::uint64_t(y) * pitch,
                    y_out + std::uint64_t(height - 1) * pitch, pitch);
    for (std::uint32_t y = height / 2; y < rows / 2; ++y)
        std::memcpy(uv_out + std::uint64_t(y) * chroma_pitch,
                    uv_out + std::uint64_t(height / 2 - 1) * chroma_pitch, chroma_pitch);

    const auto address = static_cast<VAddr>(reinterpret_cast<std::uintptr_t>(target));
    auto* memory = Core::Memory::Instance();
    if (!memory) return 0;
    memory->InvalidateMemory(address, bytes);
    if (!memory->TryWriteBacking(target, converter.output.data(), bytes))
        std::memcpy(target, converter.output.data(), bytes);
    memory->InvalidateMemory(address, bytes);
    // Do not report the source 1920x1088 layout after overwriting it with 720p NV12:
    // otherwise Remap fetches UV from the old offset and turns the picture green.
    // The entry is published only after swscale and both Y/UV writes complete.
    Gow3Movie::RecordFrame(
        guest_base, in_place ? width : src_width, in_place ? height : src_height,
        in_place ? pitch : (src_width + 63u) & ~63u,
        in_place ? rows : (src_height + 15u) & ~15u);
    *display_width = width;
    *display_height = height;
    static std::atomic<unsigned> recorded{0};
    if (recorded.fetch_add(1, std::memory_order_relaxed) < 4) {
        std::printf("TLOU movie NV12: decoded %ux%u -> %ux%u %s %#llx; "
                    "Y pitch %u rows %u UV pitch %u bytes %llu; gameplay unchanged\n",
                    src_width, src_height, width, height, in_place ? "in-place" : "scratch",
                    static_cast<unsigned long long>(address), pitch, rows,
                    chroma_pitch, static_cast<unsigned long long>(bytes));
        std::fflush(stdout);
    }
    return 1;
}
