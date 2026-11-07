/* HEX pre-game Vulkan driver test. All calls go through the selected driver's GIPA. */
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "gpu/shim/vk_driver_loader.h"

#define HEX_LOAD_GLOBAL(name) \
    PFN_vk##name vk##name=(PFN_vk##name)gipa(VK_NULL_HANDLE,"vk" #name); \
    if(!vk##name){fprintf(stderr,"HEX:missing_global=vk%s\n",#name);return 2;}
#define HEX_LOAD_INSTANCE(name) \
    PFN_vk##name vk##name=(PFN_vk##name)gipa(instance,"vk" #name); \
    if(!vk##name){fprintf(stderr,"HEX:missing_instance=vk%s\n",#name);return 2;}
#define HEX_LOAD_DEVICE(name) \
    PFN_vk##name vk##name=(PFN_vk##name)gipa(instance,"vk" #name); \
    if(!vk##name){fprintf(stderr,"HEX:missing_device=vk%s\n",#name);return 2;}

static const char* result_name(VkResult r) {
    switch(r){
    case VK_SUCCESS:return "VK_SUCCESS";
    case VK_INCOMPLETE:return "VK_INCOMPLETE";
    case VK_ERROR_INITIALIZATION_FAILED:return "VK_ERROR_INITIALIZATION_FAILED";
    case VK_ERROR_INCOMPATIBLE_DRIVER:return "VK_ERROR_INCOMPATIBLE_DRIVER";
    case VK_ERROR_FEATURE_NOT_PRESENT:return "VK_ERROR_FEATURE_NOT_PRESENT";
    case VK_ERROR_FORMAT_NOT_SUPPORTED:return "VK_ERROR_FORMAT_NOT_SUPPORTED";
    case VK_ERROR_DEVICE_LOST:return "VK_ERROR_DEVICE_LOST";
    case VK_ERROR_OUT_OF_HOST_MEMORY:return "VK_ERROR_OUT_OF_HOST_MEMORY";
    case VK_ERROR_OUT_OF_DEVICE_MEMORY:return "VK_ERROR_OUT_OF_DEVICE_MEMORY";
    default:return "VK_OTHER_ERROR";
    }
}
static VkResult report_format(VkPhysicalDevice physical,
                              PFN_vkGetPhysicalDeviceFormatProperties get_props,
                              PFN_vkGetPhysicalDeviceImageFormatProperties get_image_props,
                              const char* name,VkFormat format,
                              VkImageUsageFlags usage,VkImageCreateFlags flags) {
    VkFormatProperties props;
    get_props(physical,format,&props);
    VkImageFormatProperties image;
    VkResult r=get_image_props(physical,format,VK_IMAGE_TYPE_2D,
        VK_IMAGE_TILING_OPTIMAL,usage,flags,&image);
    VkImageFormatProperties sampled_image;
    VkResult sampled_result=get_image_props(physical,format,VK_IMAGE_TYPE_2D,
        VK_IMAGE_TILING_OPTIMAL,VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_SAMPLED_BIT,0,&sampled_image);
    int sampled=(props.optimalTilingFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT)!=0;
    printf("HEX:format.%s.sampled_feature=%s\n",name,sampled?"yes":"no");
    printf("HEX:format.%s.sampled_image_create=%s\n",name,result_name(sampled_result));
    printf("HEX:format.%s.game_image_create=%s\n",name,result_name(r));
    return r;
}
int vulkan_smoke(void) {
    const char* driver=getenv("GOW3_VULKAN_DRIVER");
    printf("HEX:driver_path=%s\n",(driver&&*driver)?driver:"system");
    Gow3VkGetInstanceProcAddr gipa=gow3_vk_get_instance_proc_addr();
    if(!gipa){printf("HEX:overall=FAIL_NO_VULKAN_ENTRYPOINT\n");return 2;}

    HEX_LOAD_GLOBAL(CreateInstance)
    PFN_vkEnumerateInstanceVersion enum_version=
        (PFN_vkEnumerateInstanceVersion)gipa(VK_NULL_HANDLE,"vkEnumerateInstanceVersion");
    uint32_t instance_version=VK_API_VERSION_1_0;
    if(enum_version) {
        VkResult vr=enum_version(&instance_version);
        if(vr!=VK_SUCCESS)instance_version=VK_API_VERSION_1_0;
    }
    printf("HEX:instance_api=%u.%u.%u\n",
        VK_VERSION_MAJOR(instance_version),VK_VERSION_MINOR(instance_version),VK_VERSION_PATCH(instance_version));
    printf("HEX:required_api_1_3=%s\n",instance_version>=VK_API_VERSION_1_3?"yes":"no");

    VkApplicationInfo app={.sType=VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pApplicationName="TLOU HEX Driver Test",.applicationVersion=1,
        .pEngineName="HEX isolated Vulkan diagnostics",.engineVersion=1,
        .apiVersion=VK_API_VERSION_1_3};
    VkInstanceCreateInfo ici={.sType=VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,.pApplicationInfo=&app};
    VkInstance instance=VK_NULL_HANDLE;
    VkResult r=vkCreateInstance(&ici,NULL,&instance);
    if(r!=VK_SUCCESS) {
        printf("HEX:create_instance=%s (%d)\n",result_name(r),r);
        printf("HEX:overall=FAIL_INSTANCE\n");
        return 3;
    }
    printf("HEX:create_instance=PASS\n");

    HEX_LOAD_INSTANCE(DestroyInstance)
    HEX_LOAD_INSTANCE(EnumeratePhysicalDevices)
    HEX_LOAD_INSTANCE(GetPhysicalDeviceProperties)
    HEX_LOAD_INSTANCE(GetPhysicalDeviceFeatures)
    HEX_LOAD_INSTANCE(GetPhysicalDeviceQueueFamilyProperties)
    HEX_LOAD_INSTANCE(GetPhysicalDeviceFormatProperties)
    HEX_LOAD_INSTANCE(GetPhysicalDeviceImageFormatProperties)
    HEX_LOAD_INSTANCE(GetPhysicalDeviceMemoryProperties)
    HEX_LOAD_INSTANCE(CreateDevice)

    uint32_t count=0;
    r=vkEnumeratePhysicalDevices(instance,&count,NULL);
    if(r!=VK_SUCCESS||count==0) {
        printf("HEX:physical_devices=none (%s)\n",result_name(r));
        vkDestroyInstance(instance,NULL);
        return 4;
    }
    VkPhysicalDevice* devices=(VkPhysicalDevice*)calloc(count,sizeof(*devices));
    if(!devices){vkDestroyInstance(instance,NULL);return 5;}
    r=vkEnumeratePhysicalDevices(instance,&count,devices);
    if(r!=VK_SUCCESS){free(devices);vkDestroyInstance(instance,NULL);return 5;}

    VkPhysicalDevice physical=VK_NULL_HANDLE;
    VkPhysicalDeviceProperties properties;
    VkPhysicalDeviceFeatures features;
    uint32_t selected_family=UINT32_MAX;
    for(uint32_t i=0;i<count;i++) {
        VkPhysicalDeviceProperties candidate;
        vkGetPhysicalDeviceProperties(devices[i],&candidate);
        uint32_t queue_count=0;
        vkGetPhysicalDeviceQueueFamilyProperties(devices[i],&queue_count,NULL);
        VkQueueFamilyProperties* queues=(VkQueueFamilyProperties*)calloc(queue_count,sizeof(*queues));
        if(!queues)continue;
        vkGetPhysicalDeviceQueueFamilyProperties(devices[i],&queue_count,queues);
        uint32_t family=UINT32_MAX;
        for(uint32_t q=0;q<queue_count;q++) {
            if(queues[q].queueCount && (queues[q].queueFlags&VK_QUEUE_GRAPHICS_BIT)) {family=q;break;}
        }
        free(queues);
        if(family!=UINT32_MAX) {physical=devices[i];properties=candidate;selected_family=family;break;}
    }
    free(devices);
    if(physical==VK_NULL_HANDLE) {
        printf("HEX:graphics_queue=FAIL_NO_GRAPHICS_QUEUE\n");
        vkDestroyInstance(instance,NULL);
        return 6;
    }
    vkGetPhysicalDeviceFeatures(physical,&features);
    printf("HEX:device=%s\n",properties.deviceName);
    printf("HEX:vendor_id=0x%04x\n",properties.vendorID);
    printf("HEX:device_id=0x%04x\n",properties.deviceID);
    printf("HEX:driver_version=%u\n",properties.driverVersion);
    printf("HEX:device_api=%u.%u.%u\n",VK_VERSION_MAJOR(properties.apiVersion),
        VK_VERSION_MINOR(properties.apiVersion),VK_VERSION_PATCH(properties.apiVersion));
    printf("HEX:textureCompressionBC=%s\n",features.textureCompressionBC?"yes":"no");
    printf("HEX:graphics_queue=PASS\n");

    const VkImageUsageFlags game_usage=VK_IMAGE_USAGE_TRANSFER_SRC_BIT|
        VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_STORAGE_BIT;
    const VkImageCreateFlags game_flags=VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT|
        VK_IMAGE_CREATE_EXTENDED_USAGE_BIT|VK_IMAGE_CREATE_BLOCK_TEXEL_VIEW_COMPATIBLE_BIT;
    printf("HEX:game_image_usage=TRANSFER_SRC|TRANSFER_DST|SAMPLED|STORAGE\n");
    printf("HEX:game_image_flags=MUTABLE|EXTENDED_USAGE|BLOCK_TEXEL_VIEW_COMPATIBLE\n");
    printf("HEX:surface_and_swapchain=NOT_TESTED_NO_WINDOW_SURFACE\n");
    report_format(physical,vkGetPhysicalDeviceFormatProperties,vkGetPhysicalDeviceImageFormatProperties,
        "BC4_UNORM",VK_FORMAT_BC4_UNORM_BLOCK,game_usage,game_flags);
    report_format(physical,vkGetPhysicalDeviceFormatProperties,vkGetPhysicalDeviceImageFormatProperties,
        "BC4_SNORM",VK_FORMAT_BC4_SNORM_BLOCK,game_usage,game_flags);
    report_format(physical,vkGetPhysicalDeviceFormatProperties,vkGetPhysicalDeviceImageFormatProperties,
        "BC5_UNORM",VK_FORMAT_BC5_UNORM_BLOCK,game_usage,game_flags);
    report_format(physical,vkGetPhysicalDeviceFormatProperties,vkGetPhysicalDeviceImageFormatProperties,
        "BC5_SNORM",VK_FORMAT_BC5_SNORM_BLOCK,game_usage,game_flags);
    report_format(physical,vkGetPhysicalDeviceFormatProperties,vkGetPhysicalDeviceImageFormatProperties,
        "BC6H_SFLOAT",VK_FORMAT_BC6H_SFLOAT_BLOCK,game_usage,game_flags);
    report_format(physical,vkGetPhysicalDeviceFormatProperties,vkGetPhysicalDeviceImageFormatProperties,
        "BC6H_UFLOAT",VK_FORMAT_BC6H_UFLOAT_BLOCK,game_usage,game_flags);
    report_format(physical,vkGetPhysicalDeviceFormatProperties,vkGetPhysicalDeviceImageFormatProperties,
        "BC7_UNORM",VK_FORMAT_BC7_UNORM_BLOCK,game_usage,game_flags);
    report_format(physical,vkGetPhysicalDeviceFormatProperties,vkGetPhysicalDeviceImageFormatProperties,
        "BC7_SRGB",VK_FORMAT_BC7_SRGB_BLOCK,game_usage,game_flags);
    report_format(physical,vkGetPhysicalDeviceFormatProperties,vkGetPhysicalDeviceImageFormatProperties,
        "ASTC_4x4_UNORM",VK_FORMAT_ASTC_4x4_UNORM_BLOCK,
        VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_SAMPLED_BIT,0);
    report_format(physical,vkGetPhysicalDeviceFormatProperties,vkGetPhysicalDeviceImageFormatProperties,
        "RGBA8_UNORM",VK_FORMAT_R8G8B8A8_UNORM,
        VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_STORAGE_BIT,0);
    report_format(physical,vkGetPhysicalDeviceFormatProperties,vkGetPhysicalDeviceImageFormatProperties,
        "RGBA16F",VK_FORMAT_R16G16B16A16_SFLOAT,
        VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_STORAGE_BIT,0);

    float priority=1.0f;
    VkDeviceQueueCreateInfo qci={.sType=VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
        .queueFamilyIndex=selected_family,.queueCount=1,.pQueuePriorities=&priority};
    VkDeviceCreateInfo dci={.sType=VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .queueCreateInfoCount=1,.pQueueCreateInfos=&qci};
    VkDevice device=VK_NULL_HANDLE;
    r=vkCreateDevice(physical,&dci,NULL,&device);
    if(r!=VK_SUCCESS) {
        printf("HEX:create_device=%s (%d)\n",result_name(r),r);
        vkDestroyInstance(instance,NULL);
        return 7;
    }
    printf("HEX:create_device=PASS\n");

    HEX_LOAD_DEVICE(DestroyDevice)
    HEX_LOAD_DEVICE(GetDeviceQueue)
    HEX_LOAD_DEVICE(CreateBuffer)
    HEX_LOAD_DEVICE(DestroyBuffer)
    HEX_LOAD_DEVICE(GetBufferMemoryRequirements)
    HEX_LOAD_DEVICE(AllocateMemory)
    HEX_LOAD_DEVICE(FreeMemory)
    HEX_LOAD_DEVICE(BindBufferMemory)
    HEX_LOAD_DEVICE(CreateCommandPool)
    HEX_LOAD_DEVICE(DestroyCommandPool)
    HEX_LOAD_DEVICE(AllocateCommandBuffers)
    HEX_LOAD_DEVICE(BeginCommandBuffer)
    HEX_LOAD_DEVICE(CmdFillBuffer)
    HEX_LOAD_DEVICE(CmdPipelineBarrier)
    HEX_LOAD_DEVICE(EndCommandBuffer)
    HEX_LOAD_DEVICE(QueueSubmit)
    HEX_LOAD_DEVICE(QueueWaitIdle)
    HEX_LOAD_DEVICE(MapMemory)
    HEX_LOAD_DEVICE(UnmapMemory)
    HEX_LOAD_DEVICE(InvalidateMappedMemoryRanges)

    VkQueue queue=VK_NULL_HANDLE;
    vkGetDeviceQueue(device,selected_family,0,&queue);
    VkBuffer buffer=VK_NULL_HANDLE;
    VkDeviceMemory memory=VK_NULL_HANDLE;
    VkCommandPool pool=VK_NULL_HANDLE;
    int transfer_ok=0;
    VkBufferCreateInfo bci={.sType=VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
        .size=4096,.usage=VK_BUFFER_USAGE_TRANSFER_DST_BIT,.sharingMode=VK_SHARING_MODE_EXCLUSIVE};
    r=vkCreateBuffer(device,&bci,NULL,&buffer);
    if(r!=VK_SUCCESS)goto cleanup;
    VkMemoryRequirements requirements;
    vkGetBufferMemoryRequirements(device,buffer,&requirements);
    VkPhysicalDeviceMemoryProperties memory_properties;
    vkGetPhysicalDeviceMemoryProperties(physical,&memory_properties);
    uint32_t memory_type=UINT32_MAX;
    VkMemoryPropertyFlags selected_flags=0;
    for(uint32_t i=0;i<memory_properties.memoryTypeCount;i++) {
        if((requirements.memoryTypeBits&(1u<<i)) &&
           (memory_properties.memoryTypes[i].propertyFlags&VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) {
            memory_type=i;
            selected_flags=memory_properties.memoryTypes[i].propertyFlags;
            if(selected_flags&VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)break;
        }
    }
    if(memory_type==UINT32_MAX)goto cleanup;
    VkMemoryAllocateInfo mai={.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize=requirements.size,.memoryTypeIndex=memory_type};
    r=vkAllocateMemory(device,&mai,NULL,&memory);
    if(r!=VK_SUCCESS)goto cleanup;
    r=vkBindBufferMemory(device,buffer,memory,0);
    if(r!=VK_SUCCESS)goto cleanup;
    VkCommandPoolCreateInfo pci={.sType=VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .queueFamilyIndex=selected_family};
    r=vkCreateCommandPool(device,&pci,NULL,&pool);
    if(r!=VK_SUCCESS)goto cleanup;
    VkCommandBuffer command=VK_NULL_HANDLE;
    VkCommandBufferAllocateInfo cai={.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .commandPool=pool,.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY,.commandBufferCount=1};
    r=vkAllocateCommandBuffers(device,&cai,&command);
    if(r!=VK_SUCCESS)goto cleanup;
    VkCommandBufferBeginInfo begin={.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    r=vkBeginCommandBuffer(command,&begin);
    if(r!=VK_SUCCESS)goto cleanup;
    vkCmdFillBuffer(command,buffer,0,4096,0xb100db0e);
    VkBufferMemoryBarrier barrier={.sType=VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
        .srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT,.dstAccessMask=VK_ACCESS_HOST_READ_BIT,
        .srcQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED,.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED,
        .buffer=buffer,.offset=0,.size=VK_WHOLE_SIZE};
    vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_HOST_BIT,
        0,0,NULL,1,&barrier,0,NULL);
    r=vkEndCommandBuffer(command);
    if(r!=VK_SUCCESS)goto cleanup;
    VkSubmitInfo submit={.sType=VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .commandBufferCount=1,.pCommandBuffers=&command};
    r=vkQueueSubmit(queue,1,&submit,VK_NULL_HANDLE);
    if(r!=VK_SUCCESS)goto cleanup;
    r=vkQueueWaitIdle(queue);
    if(r!=VK_SUCCESS)goto cleanup;
    uint32_t* mapped=NULL;
    r=vkMapMemory(device,memory,0,VK_WHOLE_SIZE,0,(void**)&mapped);
    if(r!=VK_SUCCESS)goto cleanup;
    if(!(selected_flags&VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
        VkMappedMemoryRange range={.sType=VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE,
            .memory=memory,.offset=0,.size=VK_WHOLE_SIZE};
        r=vkInvalidateMappedMemoryRanges(device,1,&range);
        if(r!=VK_SUCCESS){vkUnmapMemory(device,memory);goto cleanup;}
    }
    transfer_ok=1;
    for(int i=0;i<1024;i++)if(mapped[i]!=0xb100db0e){transfer_ok=0;break;}
    vkUnmapMemory(device,memory);
cleanup:
    if(pool)vkDestroyCommandPool(device,pool,NULL);
    if(buffer)vkDestroyBuffer(device,buffer,NULL);
    if(memory)vkFreeMemory(device,memory,NULL);
    vkDestroyDevice(device,NULL);
    vkDestroyInstance(instance,NULL);
    printf("HEX:transfer_readback=%s\n",transfer_ok?"PASS":"FAIL");
    printf("HEX:overall=%s\n",transfer_ok?"PASS_WITH_FORMAT_REPORT":"FAIL_TRANSFER");
    return transfer_ok?0:8;
}
