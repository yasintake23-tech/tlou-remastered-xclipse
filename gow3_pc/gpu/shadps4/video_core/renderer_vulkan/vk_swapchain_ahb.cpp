// Adapted from Bloodborne-Android 2e472653; Android presentation infrastructure.
// SPDX-License-Identifier: GPL-2.0-or-later
// gow3: see vk_swapchain_ahb.h.
#ifdef __ANDROID__
#include <android/hardware_buffer.h>
#include <poll.h>
#include <unistd.h>
#include <array>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include "common/assert.h"
#include "gow3_settings.h"
#include "gow3_toggles.h"
#include "common/logging/log.h"
#include "imgui/renderer/imgui_core.h"
#include "sdl_window.h"
#include "video_core/renderer_vulkan/vk_instance.h"
#include "video_core/renderer_vulkan/vk_swapchain_ahb.h"

namespace Vulkan {

namespace {

constexpr u32 AhbFormat = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
constexpr u64 AhbLinearCpuUsage = AHARDWAREBUFFER_USAGE_CPU_READ_RARELY |
                                  AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY;
constexpr u64 AhbLinearCpuOftenUsage = AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN |
                                       AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN;
constexpr u64 AhbBaseGpuUsage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT |
                                AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
constexpr u64 AhbUsage = AhbBaseGpuUsage |
                         AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY |
                         AhbLinearCpuUsage;

struct NativeHandleHeader {
    int version;
    int num_fds;
    int num_ints;
};

constexpr u32 SamsungGrallocMagic = 0x59700795u;
constexpr u32 SamsungGrallocLayoutLinear = 1u;

bool CheckSamsungGrallocLinear(const AHardwareBuffer* ahb, u32& out_layout, u32& out_stride) {
    out_layout = 0;
    out_stride = 0;
    if (!ahb) {
        return false;
    }
    const auto* raw =
        reinterpret_cast<const NativeHandleHeader*>(AHardwareBuffer_getNativeHandle(ahb));
    if (!raw || raw->num_fds < 0 || raw->num_ints < 0 || (raw->num_fds + raw->num_ints) < 51) {
        return true;
    }
    const auto* words = reinterpret_cast<const u32*>(raw);
    if (words[8] != SamsungGrallocMagic) {
        return true;
    }
    out_stride = words[38];
    out_layout = words[41];
    return out_layout == SamsungGrallocLayoutLinear;
}

/// GOW3_PRESENT_BUFFERS: the number of buffers (3 by default: one shown, one pending, one rendered).
u32 BufferCount() {
    if (const char* value = std::getenv("GOW3_PRESENT_BUFFERS")) {
        const long count = std::strtol(value, nullptr, 10);
        if (count >= 2 && count <= static_cast<long>(8)) {
            return static_cast<u32>(count);
        }
    }
    return 3;
}

/// Waits (up to timeout_ms) for a sync file to signal. True when signaled.
bool WaitSyncFile(int fd, int timeout_ms) {
    pollfd pfd{fd, POLLIN, 0};
    int ready;
    do {
        ready = poll(&pfd, 1, timeout_ms);
    } while (ready < 0 && errno == EINTR);
    return ready > 0;
}

} // namespace

Swapchain::Swapchain(const Instance& instance_, const Frontend::WindowSDL& window_)
    : instance{instance_}, window{window_} {
    image_count = BufferCount();
    const vk::PhysicalDeviceExternalSemaphoreInfo semaphore_info{
        .handleType = vk::ExternalSemaphoreHandleTypeFlagBits::eSyncFd,
    };
    const auto semaphore_props = instance.GetPhysicalDevice().getExternalSemaphoreProperties(semaphore_info);
    ASSERT_MSG(
        (semaphore_props.externalSemaphoreFeatures & vk::ExternalSemaphoreFeatureFlagBits::eExportable) &&
            (semaphore_props.externalSemaphoreFeatures & vk::ExternalSemaphoreFeatureFlagBits::eImportable),
        "The Vulkan driver cannot export and import sync-file semaphores (SYNC_FD): the AHB "
        "swapchain cannot synchronize with the app");
    if (Gow3Present::Client::Configured()) {
        if (!client.Connect(window.GetWidth(), window.GetHeight(), AhbFormat, image_count,
                            Gow3Settings::Get().vsync.load())) {
            std::printf("Present: no connection to the app: frames are rendered but not shown\n");
        }
    } else {
        std::printf("Present: GOW3_PRESENT_SOCKET is not set: frames are rendered but not shown\n");
    }
    Create(window.GetWidth(), window.GetHeight());
}

void Swapchain::UpdateVsync() {
    const bool fifo = Gow3Settings::Get().vsync;
    if (client.Connected()) client.SetFifo(fifo);
}

Swapchain::~Swapchain() {
    Destroy();
    client.Disconnect();
}

std::pair<u32, u32> Swapchain::AcquireQueues() const {
    return {VK_QUEUE_FAMILY_FOREIGN_EXT, instance.GetGraphicsQueueFamilyIndex()};
}

std::pair<u32, u32> Swapchain::ReleaseQueues() const {
    return {instance.GetGraphicsQueueFamilyIndex(), VK_QUEUE_FAMILY_FOREIGN_EXT};
}

void Swapchain::Create(u32 width_, u32 height_) {
    width = width_;
    height = height_;
    crop_offset = crop_extent = 0;
    crop_pending = false;
    needs_recreation = false;
    // A 0x0 output (no screen size) has no buffers; they come when the size is back.
    if (width == 0 || height == 0) {
        zero_extent = true;
        needs_recreation = true;
        return;
    }
    zero_extent = false;
    Destroy();
    extent = vk::Extent2D{width, height};
    ++generation;
    if (client.Connected()) {
        if (!client.BeginGeneration(generation, width, height, AhbFormat, image_count)) {
            PeerGone();
        }
    }
    const vk::Device device = instance.GetDevice();
    buffers.resize(image_count);
    image_acquired.resize(image_count);
    present_ready.resize(image_count);
    for (u32 i = 0; i < image_count; ++i) {
        CreateBuffer(i);
        auto [acquired_result, acquired] = device.createSemaphore({});
        ASSERT_MSG(acquired_result == vk::Result::eSuccess, "Failed to create semaphore: {}",
                   vk::to_string(acquired_result));
        image_acquired[i] = acquired;
        // The present-ready semaphore is exported as a sync file after its signal is submitted.
        const vk::ExportSemaphoreCreateInfo export_info{
            .handleTypes = vk::ExternalSemaphoreHandleTypeFlagBits::eSyncFd,
        };
        auto [ready_result, ready] = device.createSemaphore({.pNext = &export_info});
        ASSERT_MSG(ready_result == vk::Result::eSuccess, "Failed to create exportable semaphore: {}",
                   vk::to_string(ready_result));
        present_ready[i] = ready;
        SetObjectName(device, image_acquired[i], "AHB swapchain image_acquired {}", i);
        SetObjectName(device, present_ready[i], "AHB swapchain present_ready {}", i);
        free_queue.push_back(i);
        if (client.Connected() &&
            !client.SendBuffer(generation, i, width, height, AhbFormat, buffers[i].ahb)) {
            PeerGone();
        }
    }
    image_index = 0;
    LOG_INFO(Render_Vulkan, "AHB swapchain: {} buffers {}x{} (generation {}, {})", image_count, width,
             height, generation, client.Connected() ? "app connected" : "no app");
}

void Swapchain::CreateBuffer(u32 index) {
    const vk::Device device = instance.GetDevice();
    Buffer& buffer = buffers[index];

    constexpr std::array<u64, 5> candidate_usages = {
        AhbBaseGpuUsage | AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY | AhbLinearCpuUsage,
        AhbBaseGpuUsage | AhbLinearCpuUsage,
        AhbBaseGpuUsage | AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY | AhbLinearCpuOftenUsage,
        AhbBaseGpuUsage | AhbLinearCpuOftenUsage,
        AhbBaseGpuUsage | AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY,
    };

    int last_allocated = -1;
    u64 chosen_usage = 0;
    u32 chosen_layout = 0;
    u32 chosen_stride = 0;
    AHardwareBuffer* fallback_ahb = nullptr;
    u64 fallback_usage = 0;
    u32 fallback_layout = 0;
    u32 fallback_stride = 0;

    for (const u64 candidate_usage : candidate_usages) {
        const AHardwareBuffer_Desc desc{
            .width = width,
            .height = height,
            .layers = 1,
            .format = AhbFormat,
            .usage = candidate_usage,
        };
        AHardwareBuffer* candidate_ahb = nullptr;
        last_allocated = AHardwareBuffer_allocate(&desc, &candidate_ahb);
        if (last_allocated != 0 || !candidate_ahb) {
            continue;
        }
        u32 sgr_layout = 0;
        u32 sgr_stride = 0;
        if (CheckSamsungGrallocLinear(candidate_ahb, sgr_layout, sgr_stride)) {
            if (fallback_ahb) {
                AHardwareBuffer_release(fallback_ahb);
                fallback_ahb = nullptr;
            }
            buffer.ahb = candidate_ahb;
            chosen_usage = candidate_usage;
            chosen_layout = sgr_layout;
            chosen_stride = sgr_stride;
            break;
        }
        LOG_WARNING(Render_Vulkan,
                    "AHB buffer {} with usage {:#x} allocated non-linear Samsung Gralloc layout {} "
                    "(stride {}); retrying for linear layout",
                    index, candidate_usage, sgr_layout, sgr_stride);
        if (!fallback_ahb) {
            fallback_ahb = candidate_ahb;
            fallback_usage = candidate_usage;
            fallback_layout = sgr_layout;
            fallback_stride = sgr_stride;
        } else {
            AHardwareBuffer_release(candidate_ahb);
        }
    }

    if (!buffer.ahb && fallback_ahb) {
        buffer.ahb = fallback_ahb;
        chosen_usage = fallback_usage;
        chosen_layout = fallback_layout;
        chosen_stride = fallback_stride;
    }

    ASSERT_MSG(buffer.ahb != nullptr, "AHardwareBuffer_allocate {}x{} failed: {}", width,
               height, last_allocated);
    LOG_INFO(Render_Vulkan, "AHB buffer {}: {}x{} usage={:#x} sgr1_layout={} sgr1_stride={}",
             index, width, height, chosen_usage, chosen_layout, chosen_stride);

    auto [props_result, props_chain] = device.getAndroidHardwareBufferPropertiesANDROID<
        vk::AndroidHardwareBufferPropertiesANDROID, vk::AndroidHardwareBufferFormatPropertiesANDROID>(
        *buffer.ahb);
    ASSERT_MSG(props_result == vk::Result::eSuccess,
               "vkGetAndroidHardwareBufferPropertiesANDROID failed: {}", vk::to_string(props_result));
    const auto& props = props_chain.get<vk::AndroidHardwareBufferPropertiesANDROID>();
    const auto& format_props = props_chain.get<vk::AndroidHardwareBufferFormatPropertiesANDROID>();
    ASSERT_MSG(format_props.format != vk::Format::eUndefined,
               "The driver maps the AHardwareBuffer to no Vulkan format (external format {:#x})",
               format_props.externalFormat);
    const auto required = vk::FormatFeatureFlagBits::eColorAttachment | vk::FormatFeatureFlagBits::eBlitDst;
    ASSERT_MSG((format_props.formatFeatures & required) == required,
               "The driver cannot render/blit into the AHardwareBuffer format {} (features {})",
               vk::to_string(format_props.format), vk::to_string(format_props.formatFeatures));
    surface_format.format = format_props.format;

    vk::ImageUsageFlags usage =
        vk::ImageUsageFlagBits::eColorAttachment | vk::ImageUsageFlagBits::eTransferDst;
    if (format_props.formatFeatures & vk::FormatFeatureFlagBits::eTransferSrc) {
        usage |= vk::ImageUsageFlagBits::eTransferSrc;
    }
    if (format_props.formatFeatures & vk::FormatFeatureFlagBits::eSampledImage) {
        usage |= vk::ImageUsageFlagBits::eSampled;
    }
    const vk::ExternalMemoryImageCreateInfo external_info{
        .handleTypes = vk::ExternalMemoryHandleTypeFlagBits::eAndroidHardwareBufferANDROID,
    };
    const vk::ImageCreateInfo image_info{
        .pNext = &external_info,
        .imageType = vk::ImageType::e2D,
        .format = format_props.format,
        .extent = {width, height, 1},
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = vk::SampleCountFlagBits::e1,
        .tiling = vk::ImageTiling::eOptimal,
        .usage = usage,
        .sharingMode = vk::SharingMode::eExclusive,
        .initialLayout = vk::ImageLayout::eUndefined,
    };
    auto [image_result, image] = device.createImage(image_info);
    ASSERT_MSG(image_result == vk::Result::eSuccess, "Failed to create the AHB image: {}",
               vk::to_string(image_result));
    buffer.image = image;

    const auto memory_properties = instance.GetPhysicalDevice().getMemoryProperties();
    u32 memory_type = ~0u;
    for (u32 i = 0; i < memory_properties.memoryTypeCount; ++i) {
        if (!(props.memoryTypeBits & (1u << i))) {
            continue;
        }
        if (memory_type == ~0u) {
            memory_type = i;
        }
        if (memory_properties.memoryTypes[i].propertyFlags & vk::MemoryPropertyFlagBits::eDeviceLocal) {
            memory_type = i;
            break;
        }
    }
    ASSERT_MSG(memory_type != ~0u, "No memory type for the AHardwareBuffer (bits {:#x})",
               props.memoryTypeBits);
    const vk::MemoryDedicatedAllocateInfo dedicated{.image = image};
    const vk::ImportAndroidHardwareBufferInfoANDROID import_info{
        .pNext = &dedicated,
        .buffer = buffer.ahb,
    };
    auto [memory_result, memory] = device.allocateMemory({
        .pNext = &import_info,
        .allocationSize = props.allocationSize,
        .memoryTypeIndex = memory_type,
    });
    ASSERT_MSG(memory_result == vk::Result::eSuccess, "Failed to import the AHardwareBuffer: {}",
               vk::to_string(memory_result));
    buffer.memory = memory;
    const auto bind_result = device.bindImageMemory(image, memory, 0);
    ASSERT_MSG(bind_result == vk::Result::eSuccess, "Failed to bind the AHB image: {}",
               vk::to_string(bind_result));

    auto [view_result, view] = device.createImageView({
        .image = image,
        .viewType = vk::ImageViewType::e2D,
        .format = format_props.format,
        .subresourceRange = {.aspectMask = vk::ImageAspectFlagBits::eColor,
                             .levelCount = 1,
                             .layerCount = 1},
    });
    ASSERT_MSG(view_result == vk::Result::eSuccess, "Failed to create the AHB image view: {}",
               vk::to_string(view_result));
    buffer.view = view;
    SetObjectName(device, image, "AHB swapchain image {}", index);
    SetObjectName(device, view, "AHB swapchain view {}", index);
}

void Swapchain::Recreate(u32 width_, u32 height_) {
    LOG_DEBUG(Render_Vulkan, "Recreate the AHB swapchain: width={} height={}", width_, height_);
    Create(width_, height_);
}

void Swapchain::Destroy() {
    vk::Device device = instance.GetDevice();
    const auto wait_result = device.waitIdle();
    if (wait_result != vk::Result::eSuccess) {
        LOG_WARNING(Render_Vulkan, "Failed to wait for device to become idle: {}",
                    vk::to_string(wait_result));
    }
    for (Buffer& buffer : buffers) {
        device.destroyImageView(buffer.view);
        device.destroyImage(buffer.image);
        device.freeMemory(buffer.memory);
        // The app and the compositor keep the memory while they use it (reference counted).
        if (buffer.ahb) {
            AHardwareBuffer_release(buffer.ahb);
        }
        if (buffer.release_fd >= 0) {
            close(buffer.release_fd);
        }
    }
    buffers.clear();
    for (const auto sem : image_acquired) {
        device.destroySemaphore(sem);
    }
    for (const auto sem : present_ready) {
        device.destroySemaphore(sem);
    }
    image_acquired.clear();
    present_ready.clear();
    free_queue.clear();
}

void Swapchain::PeerGone() const {
    // The app is the game's screen: without it (closed, killed) the process ends, as when the
    // window of the glibc build is closed.
    LOG_INFO(Frontend, "The app closed the frame connection");
    std::fflush(stdout);
    std::_Exit(0);
}

void Swapchain::HandleRelease(Gow3Present::Release& release) {
    if (release.generation != generation || release.index >= buffers.size()) {
        if (release.fence_fd >= 0) {
            close(release.fence_fd); // a buffer of a set that is gone
        }
        return;
    }
    Buffer& buffer = buffers[release.index];
    if (buffer.release_fd >= 0) {
        close(buffer.release_fd);
    }
    buffer.release_fd = release.fence_fd;
    free_queue.push_back(release.index);
}

bool Swapchain::AcquireNextImage() {
    Gow3Stats::WaitTimer timer{Gow3Stats::acquire_ns};
    if (zero_extent || buffers.empty()) {
        needs_recreation = true;
        return false;
    }
    Gow3Present::Release release;
    // Releases that arrived meanwhile.
    while (client.Connected()) {
        const int got = client.WaitRelease(release, 0);
        if (got < 0) {
            PeerGone();
        }
        if (got == 0) {
            break;
        }
        HandleRelease(release);
    }
    // All buffers are at the app: the display is slower than the game. Wait for one.
    const auto wait_start = std::chrono::steady_clock::now();
    bool warned = false;
    while (free_queue.empty()) {
        const int got = client.WaitRelease(release, 1000);
        if (got < 0) {
            PeerGone();
        }
        if (got > 0) {
            HandleRelease(release);
        } else if (!warned && std::chrono::steady_clock::now() - wait_start > std::chrono::seconds(5)) {
            warned = true;
            LOG_WARNING(Render_Vulkan, "AHB swapchain: no buffer released by the app for 5 s");
        }
    }
    image_index = free_queue.front();
    free_queue.pop_front();
    ImportAcquire(image_index);
    return !needs_recreation;
}

void Swapchain::ImportAcquire(u32 index) {
    Buffer& buffer = buffers[index];
    buffer.acquire_imported = false;
    const int fd = buffer.release_fd;
    if (fd < 0) {
        return; // never shown, or released at once: nothing to wait for
    }
    buffer.release_fd = -1;
    if (sync_import_ok) {
        const vk::ImportSemaphoreFdInfoKHR import_info{
            .semaphore = image_acquired[index],
            .flags = vk::SemaphoreImportFlagBits::eTemporary,
            .handleType = vk::ExternalSemaphoreHandleTypeFlagBits::eSyncFd,
            .fd = fd,
        };
        const auto result = instance.GetDevice().importSemaphoreFdKHR(import_info);
        if (result == vk::Result::eSuccess) {
            buffer.acquire_imported = true; // the driver owns the fd now
            return;
        }
        LOG_WARNING(Render_Vulkan,
                    "Importing the release fence failed ({}): waiting for fences on the CPU instead",
                    vk::to_string(result));
        sync_import_ok = false;
    }
    // CPU wait: the fence signals when the display is done with the buffer.
    while (!WaitSyncFile(fd, 1000)) {
        LOG_WARNING(Render_Vulkan, "AHB swapchain: release fence of buffer {} still pending", index);
    }
    close(fd);
}

int Swapchain::ExportPresentReady() {
    const auto [result, fd] = instance.GetDevice().getSemaphoreFdKHR({
        .semaphore = present_ready[image_index],
        .handleType = vk::ExternalSemaphoreHandleTypeFlagBits::eSyncFd,
    });
    if (result != vk::Result::eSuccess) {
        // Without the fence the app would read a half-drawn buffer: wait for the GPU here.
        static bool logged = false;
        if (!logged) {
            logged = true;
            LOG_ERROR(Render_Vulkan,
                      "Exporting the present-ready semaphore as a sync file failed ({}): falling "
                      "back to waiting for the GPU on every frame",
                      vk::to_string(result));
        }
        const auto idle = instance.GetDevice().waitIdle();
        ASSERT_MSG(idle == vk::Result::eSuccess, "waitIdle failed: {}", vk::to_string(idle));
        return -1;
    }
    return fd; // -1 is possible too: the semaphore was already signaled
}

bool Swapchain::Present() {
    Gow3Stats::WaitTimer timer{Gow3Stats::present_ns};
    const int fence_fd = ExportPresentReady();
    ++frame_counter;
    if (client.Connected()) {
        // The previous Presenter::MakeImageBlitFit identified the exact
        // viewport. Other producers/blank frames send a full-buffer rect.
        const u32 sent_offset = crop_pending ? crop_offset : 0;
        const u32 sent_extent = crop_pending ? crop_extent : 0;
        crop_pending = false;
        if (!client.SendFrame(generation, image_index, frame_counter, width, height,
                              fence_fd, sent_offset, sent_extent)) {
            PeerGone();
        }
        // The buffer is the app's until its release.
    } else {
        // Nobody shows it: the sync file is dropped, the buffer is free at once.
        free_queue.push_back(image_index);
        buffers[image_index].release_fd = -1;
    }
    if (fence_fd >= 0) {
        close(fence_fd);
    }
    buffers[image_index].acquire_imported = false;
    return !needs_recreation;
}

} // namespace Vulkan
#endif // __ANDROID__
