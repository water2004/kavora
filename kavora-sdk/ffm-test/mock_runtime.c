/* SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0 */
#define REMIX_LIBRARY_EXPORTS
#include "remix_c.h"
#include <string.h>
#include <wchar.h>
#define CHECK(c) do { if (!(c)) return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS; } while(0)
static remixapi_ErrorCode REMIXAPI_CALL mock_Shutdown(void) {  return REMIXAPI_ERROR_CODE_SUCCESS; }
static remixapi_ErrorCode REMIXAPI_CALL mock_CreateMaterial(
    const remixapi_MaterialInfo*  info,
    remixapi_MaterialHandle*      out_handle) {
    CHECK(info && info->sType == 2 && info->hash == 123);
    CHECK(info->albedoTexture == (void*)0x1111 && info->emissiveIntensity == 7.5f);
    if (info->pNext) {
        const remixapi_MaterialInfoTranslucentEXT* translucent = info->pNext;
        const remixapi_MaterialInfoWaterSurfaceEXT* water = translucent->pNext;
        CHECK(translucent->sType == REMIXAPI_STRUCT_TYPE_MATERIAL_INFO_TRANSLUCENT_EXT);
        CHECK(water && water->sType == REMIXAPI_STRUCT_TYPE_MATERIAL_INFO_WATER_SURFACE_EXT && !water->pNext);
    }
    *out_handle = (void*)0x1234;
    return REMIXAPI_ERROR_CODE_SUCCESS;
}
static remixapi_ErrorCode REMIXAPI_CALL mock_DestroyMaterial(
    remixapi_MaterialHandle       handle) {  return REMIXAPI_ERROR_CODE_SUCCESS; }
static remixapi_ErrorCode REMIXAPI_CALL mock_CreateMesh(
    const remixapi_MeshInfo*  info,
    remixapi_MeshHandle*      out_handle) {
    CHECK(info && info->sType == 12);
    if (info->pNext) {
      const remixapi_MeshInfoSharedBuffersEXT* shared=info->pNext;
      CHECK(!info->surfaces_count && !info->surfaces_values && shared->sType == 36 && shared->surfaceCount == 1);
      CHECK(shared->surfaces[0].vertexBuffer == (void*)0x7777 && shared->surfaces[0].vertexOffset == 64 && shared->surfaces[0].vertexCount == 4);
      CHECK(shared->surfaces[0].indexOffset == 320 && shared->surfaces[0].indexCount == 6 && shared->surfaces[0].material == (void*)0x1234);
    } else {
      CHECK(info->surfaces_count == 1 && info->surfaces_values[0].vertices_count == 3 && info->surfaces_values[0].indices_count == 3);
      CHECK(info->surfaces_values[0].vertices_values[0].position[0] == 1.5f);
      if (info->surfaces_values[0].flags & REMIXAPI_MESH_INFO_SURFACE_TRIANGLES_BIT_PREVIOUS_POSITIONS) {
        CHECK(info->surfaces_values[0].vertices_values[0].previousPosition[0] == -3.25f);
      }
    }
    *out_handle=(void*)0x1234; return REMIXAPI_ERROR_CODE_SUCCESS;
}
static remixapi_ErrorCode REMIXAPI_CALL mock_DestroyMesh(
    remixapi_MeshHandle       handle) {  return REMIXAPI_ERROR_CODE_SUCCESS; }
static remixapi_ErrorCode REMIXAPI_CALL mock_SetupCamera(
    const remixapi_CameraInfo* info) { CHECK(info && info->sType == 16 && info->view[0][0] == 1.0f && info->projection[3][3] == 16.0f); return REMIXAPI_ERROR_CODE_SUCCESS; }
static remixapi_ErrorCode REMIXAPI_CALL mock_DrawInstance(
    const remixapi_InstanceInfo* info) { CHECK(info && info->sType == 13 && info->mesh == (void*)0x1234 && info->transform.matrix[0][0] == 1.0f); return REMIXAPI_ERROR_CODE_SUCCESS; }
static unsigned light_creations, light_destructions;
static remixapi_ErrorCode REMIXAPI_CALL mock_CreateLight(
    const remixapi_LightInfo* info,
    remixapi_LightHandle*     out_handle) { ++light_creations; *out_handle=(void*)0x1234; return REMIXAPI_ERROR_CODE_SUCCESS; }
REMIXAPI remixapi_ErrorCode REMIXAPI_CALL remixapi_UpdateLight(remixapi_LightHandle handle, const remixapi_LightInfo* info) {
    CHECK(handle == (void*)0x1234 && info && info->sType == REMIXAPI_STRUCT_TYPE_LIGHT_INFO);
    CHECK(light_creations == 1 && light_destructions == 0);
    CHECK(info->hash == 123 && info->radiance.x == 4 && info->radiance.y == 5 && info->radiance.z == 6);
    const remixapi_LightInfoDistantEXT* distant = info->pNext;
    CHECK(distant && distant->sType == REMIXAPI_STRUCT_TYPE_LIGHT_INFO_DISTANT_EXT);
    CHECK(distant->direction.x == 0 && distant->direction.y == -1 && distant->direction.z == 0);
    CHECK(distant->angularDiameterDegrees == 0.5f && distant->volumetricRadianceScale == 1);
    return REMIXAPI_ERROR_CODE_SUCCESS;
}
static remixapi_ErrorCode REMIXAPI_CALL mock_DestroyLight(
    remixapi_LightHandle      handle) { ++light_destructions; return REMIXAPI_ERROR_CODE_SUCCESS; }
static remixapi_ErrorCode REMIXAPI_CALL mock_DrawLightInstance(
    remixapi_LightHandle      lightHandle) {  return REMIXAPI_ERROR_CODE_SUCCESS; }
static remixapi_ErrorCode REMIXAPI_CALL mock_SetConfigVariable(
    const char*               key,
    const char*               value) { CHECK(strcmp(key,"rtx.test") == 0 && strcmp(value,"True") == 0); return REMIXAPI_ERROR_CODE_SUCCESS; }
static remixapi_ErrorCode REMIXAPI_CALL mock_dxvk_CreateD3D9(
    remixapi_Bool       editorModeEnabled,
    IDirect3D9Ex**      out_pD3D9) { CHECK(editorModeEnabled == 1); *out_pD3D9=(void*)0x1234; return REMIXAPI_ERROR_CODE_SUCCESS; }
static remixapi_ErrorCode REMIXAPI_CALL mock_dxvk_RegisterD3D9Device(
    IDirect3DDevice9Ex* d3d9Device) {  return REMIXAPI_ERROR_CODE_SUCCESS; }
static remixapi_ErrorCode REMIXAPI_CALL mock_dxvk_GetExternalSwapchain(
    uint64_t*           out_vkImage,
    uint64_t*           out_vkSemaphoreRenderingDone,
    uint64_t*           out_vkSemaphoreResumeSemaphore) { *out_vkImage=0x1111; *out_vkSemaphoreRenderingDone=0x2222; *out_vkSemaphoreResumeSemaphore=0x3333; return REMIXAPI_ERROR_CODE_SUCCESS; }
static remixapi_ErrorCode REMIXAPI_CALL mock_dxvk_GetVkImage(
    IDirect3DSurface9*  source,
    uint64_t*           out_vkImage) { CHECK(source == (void*)0x1234); *out_vkImage=0x5678; return REMIXAPI_ERROR_CODE_SUCCESS; }
static remixapi_ErrorCode REMIXAPI_CALL mock_dxvk_CopyRenderingOutput(
    IDirect3DSurface9*                    destination,
    remixapi_dxvk_CopyRenderingOutputType type) {  return REMIXAPI_ERROR_CODE_SUCCESS; }
static remixapi_ErrorCode REMIXAPI_CALL mock_dxvk_SetDefaultOutput(
    remixapi_dxvk_CopyRenderingOutputType type,
    const remixapi_Float4D* color) {  return REMIXAPI_ERROR_CODE_SUCCESS; }
static remixapi_ErrorCode REMIXAPI_CALL mock_pick_RequestObjectPicking(
    const remixapi_Rect2D*                              pixelRegion,
    PFN_remixapi_pick_RequestObjectPickingUserCallback  callback,
    void*                                               callbackUserData) { CHECK(pixelRegion != NULL && callback == NULL && callbackUserData == NULL); return REMIXAPI_ERROR_CODE_SUCCESS; }
static remixapi_ErrorCode REMIXAPI_CALL mock_pick_HighlightObjects(
    const uint32_t*           objectPickingValues_values,
    uint32_t                  objectPickingValues_count,
    uint8_t                   colorR,
    uint8_t                   colorG,
    uint8_t                   colorB) { CHECK(objectPickingValues_count == 2 && objectPickingValues_values[0] == 1 && objectPickingValues_values[1] == 2); CHECK(colorR == 255 && colorG == 3 && colorB == 4); return REMIXAPI_ERROR_CODE_SUCCESS; }
static remixapi_ErrorCode REMIXAPI_CALL mock_Startup(const remixapi_StartupInfo* info) { CHECK(info && info->sType == 22 && info->hwnd == (void*)0x1234); CHECK(info->disableSrgbConversionForOutput == 1 && info->interopImage == 1); return REMIXAPI_ERROR_CODE_SUCCESS; }
static remixapi_ErrorCode REMIXAPI_CALL mock_Present(const remixapi_PresentInfo* info) {
  CHECK(info && info->sType == 23 && info->hwndOverride == (void*)0x1234);
  CHECK(info->clientDoneSemaphore_ValueToWait == 0x123456789abcdef0ULL);
  if (info->pNext) {
    remixapi_VulkanClientFrameInfoEXT* frame = info->pNext;
    CHECK(frame->sType == 44 && !frame->pNext);
    CHECK(frame->device == 0xabcdef && frame->image == 0x99887766 && frame->imageGeneration == 0x100000001ULL);
    CHECK(frame->width == 1920 && frame->height == 1080 && frame->format == 37);
    CHECK(frame->bottomUp == 1 && frame->vsync == 0 && frame->allowFrameGeneration == 0);
    CHECK(frame->sourceReadySemaphore == 0x12345 && frame->sourceReadyValue == 0x200000002ULL);
    frame->sourceConsumedSemaphore = 0xf000;
    frame->sourceConsumedValue = 0x300000003ULL;
      frame->presentationEpoch = 0x400000004ULL;
      frame->renderedFrames = 0x500000005ULL;
      frame->generatedFrames = 0x600000006ULL;
  }
  return REMIXAPI_ERROR_CODE_SUCCESS;
}
static remixapi_ErrorCode REMIXAPI_CALL mock_CreateTextureFromMemory(
    const remixapi_TextureFromMemoryInfo* info,
    remixapi_TextureHandle*               out_handle) {
    CHECK(info && info->sType == 28 && info->width == 2 && info->height == 2);
    CHECK(info->pixelData[0] == 17 && info->pixelData[15] == 32);
    const remixapi_TextureImmutableEXT* next=info->pNext;
    if (next && next->sType == 37) {
      const remixapi_TextureMipChainEXT* mip=(const remixapi_TextureMipChainEXT*)next;
      CHECK(mip->mipLevels == 2 && mip->dataSize == 20);
      CHECK(info->pixelData[16] == 71 && info->pixelData[19] == 74);
      next=mip->pNext;
    }
    if (info->hash == 124) { CHECK(next && next->sType == 41 && !next->pNext); }
    else { CHECK(!next); }
    *out_handle=(void*)0x1234; return REMIXAPI_ERROR_CODE_SUCCESS;
}
static remixapi_ErrorCode REMIXAPI_CALL mock_CreateTextureFromFile(
    remixapi_Path                         filePath,
    remixapi_TextureHandle*               out_handle) { CHECK(filePath && wcscmp(filePath, L"C:/材质/光.png") == 0); *out_handle=(void*)0x1234; return REMIXAPI_ERROR_CODE_SUCCESS; }
static remixapi_ErrorCode REMIXAPI_CALL mock_DestroyTexture(
    remixapi_TextureHandle                handle) {  return REMIXAPI_ERROR_CODE_SUCCESS; }
static remixapi_ErrorCode REMIXAPI_CALL mock_SetCameraMediumMaterial(
    const remixapi_CameraMediumInfo* info) { CHECK(info && info->sType == 27 && info->medium == (void*)0x1234); return REMIXAPI_ERROR_CODE_SUCCESS; }
static int texture_patch_calls;
__declspec(dllexport) int mock_texture_patch_call_count(void) { return texture_patch_calls; }
static remixapi_ErrorCode REMIXAPI_CALL mock_UpdateTextureFromMemory(
    const remixapi_TexturePatchInfo* info) {
    ++texture_patch_calls;
    CHECK(info && info->sType == 29 && !info->pNext && info->entryCount == 2);
    if (info->entries[0].handle == (void*)0x3333) {
      const unsigned xs[] = {1, 5, 17, 3, 0}, ys[] = {2, 7, 11, 4, 0};
      const unsigned widths[] = {3, 5, 2, 2, 1}, heights[] = {2, 3, 2, 2, 2};
      const unsigned pitches[] = {16, 8, 0, 24, 0}, mips[] = {0, 1, 4, 7, 31};
      const unsigned formats[] = {0, 4, 5, 6, 7}, bytesPerPixel[] = {4, 1, 2, 8, 16};
      CHECK(info->entries[1].handle == (void*)0x4444);
      CHECK(info->entries[0].patchCount == 3 && info->entries[1].patchCount == 2);
      unsigned k = 0;
      for (unsigned e = 0; e < 2; ++e) {
        for (unsigned p = 0; p < info->entries[e].patchCount; ++p, ++k) {
          const remixapi_TexturePatch* patch = &info->entries[e].patches[p];
          CHECK(patch->dstX == xs[k] && patch->dstY == ys[k]);
          CHECK(patch->width == widths[k] && patch->height == heights[k]);
          CHECK(patch->format == formats[k] && patch->rowPitch == pitches[k] && patch->mipLevel == mips[k]);
          const unsigned rowSize = widths[k] * bytesPerPixel[k];
          const unsigned pitch = pitches[k] ? pitches[k] : rowSize;
          for (unsigned y = 0; y < heights[k]; ++y) {
            for (unsigned x = 0; x < rowSize; ++x) {
              CHECK(patch->pixelData[y * pitch + x] == (unsigned char)(17 + k * 13 + y * 3 + x));
            }
          }
        }
      }
    } else {
      CHECK(info->entries[0].handle == (void*)0x1111 && info->entries[1].handle == (void*)0x2222);
      CHECK(info->entries[0].patchCount == 1 && info->entries[1].patchCount == 1);
      CHECK(info->entries[1].patches[0].pixelData[0] == 17 && info->entries[1].patches[0].rowPitch == 8);
      CHECK(info->entries[0].patches[0].mipLevel == 0 && info->entries[1].patches[0].mipLevel <= 3);
    }
    return REMIXAPI_ERROR_CODE_SUCCESS;
}
static remixapi_ErrorCode REMIXAPI_CALL mock_dxvk_GetInteropInfo(remixapi_dxvk_InteropInfo* out_info) { memset(out_info,0,sizeof(*out_info)); out_info->width=640; out_info->height=480; out_info->dxvkDoneSemaphore_Handle=0x1234; out_info->depthImageMemory_Handle=0x5678; return REMIXAPI_ERROR_CODE_SUCCESS; }
static remixapi_ErrorCode REMIXAPI_CALL mock_Submit(const remixapi_SubmitInfo* info) {
    CHECK(info && info->sType == 30 && info->interopFlags == 5);
    if (info->pNext) {
        const remixapi_SubmitInfoWaterSimEXT* water = info->pNext;
        CHECK(water->sType == REMIXAPI_STRUCT_TYPE_SUBMIT_INFO_WATER_SIM_EXT && !water->pNext);
        CHECK(water->reset == 1 && water->impulseCount == 2 && water->impulses);
        CHECK(water->impulses[0].position.x == 1 && water->impulses[0].position.y == 2 && water->impulses[0].position.z == 3);
        CHECK(water->impulses[1].position.x == 4 && water->impulses[1].radius == 1.5f && water->impulses[1].strength == -0.5f);
    }
    return REMIXAPI_ERROR_CODE_SUCCESS;
}
static remixapi_ErrorCode REMIXAPI_CALL mock_AcquireConfigLayer(
    const char*                 path,
    float                       priority,
    float                       blendThreshold,
    remixapi_ConfigLayerHandle* out_handle) { CHECK(strcmp(path,"C:/配置.conf") == 0 && priority == 7.5f && blendThreshold == 0.25f); *out_handle=(void*)0x1234; return REMIXAPI_ERROR_CODE_SUCCESS; }
static remixapi_ErrorCode REMIXAPI_CALL mock_SetConfigLayerBlending(
    remixapi_ConfigLayerHandle  handle,
    float                       strength,
    float                       blendThreshold) { CHECK(handle == (void*)0x1234 && strength == 0.75f && blendThreshold == 0.25f); return REMIXAPI_ERROR_CODE_SUCCESS; }
static remixapi_ErrorCode REMIXAPI_CALL mock_ReleaseConfigLayer(
    remixapi_ConfigLayerHandle  handle) {  return REMIXAPI_ERROR_CODE_SUCCESS; }
static remixapi_ErrorCode REMIXAPI_CALL mock_SceneInstanceAdd(
    const remixapi_InstanceInfo* info,
    remixapi_SceneInstanceHandle* out_handle) { CHECK(info && info->sType == 13 && info->mesh == (void*)0x1234); *out_handle=(void*)0x1234; return REMIXAPI_ERROR_CODE_SUCCESS; }
static remixapi_ErrorCode REMIXAPI_CALL mock_SceneInstanceUpdate(
    remixapi_SceneInstanceHandle handle,
    const remixapi_InstanceInfo* info) { CHECK(handle == (void*)0x1234 && info && info->sType == 13); return REMIXAPI_ERROR_CODE_SUCCESS; }
static remixapi_ErrorCode REMIXAPI_CALL mock_SceneInstanceRemove(
    remixapi_SceneInstanceHandle handle) {  return REMIXAPI_ERROR_CODE_SUCCESS; }
static remixapi_ErrorCode REMIXAPI_CALL mock_UpdateMaterial(
    remixapi_MaterialHandle       handle,
    const remixapi_MaterialInfo*  info) { CHECK(info && info->sType == 2 && info->hash == 123); CHECK(handle == (void*)0x1234); return REMIXAPI_ERROR_CODE_SUCCESS; }
static remixapi_ErrorCode REMIXAPI_CALL mock_GetConfigVariableResolved(
    remixapi_ConfigVariableResolvedInfo* inout_info) { CHECK(inout_info && inout_info->sType == 31); CHECK(strcmp(inout_info->key,"rtx.test") == 0); { uint32_t capacity=inout_info->valueSize; inout_info->valueSize=5; if(!inout_info->value) return 0; if(capacity < 5) return REMIXAPI_ERROR_CODE_BUFFER_TOO_SMALL; memcpy(inout_info->value,"True",5); } return REMIXAPI_ERROR_CODE_SUCCESS; }
static remixapi_ErrorCode REMIXAPI_CALL mock_dxvk_GetVulkanInteropInfo(remixapi_VulkanInteropInfo* info) {
    CHECK(info && info->sType == REMIXAPI_STRUCT_TYPE_VULKAN_INTEROP_INFO && info->pNext != NULL);
    remixapi_InteropFrameStatusEXT* header = info->pNext;
    CHECK(header->pNext == NULL);
    if (header->sType == REMIXAPI_STRUCT_TYPE_FRAME_GENERATION_CAPABILITIES_EXT) {
      static uint32_t capabilityQueries = 0;
      remixapi_FrameGenerationCapabilitiesEXT* capabilities = info->pNext;
      capabilityQueries++;
      capabilities->initializationComplete = capabilityQueries != 3;
      capabilities->supported = capabilityQueries == 1;
      capabilities->maxGeneratedFrames = capabilityQueries == 1 ? 6 : 0;
      memset(capabilities->notSupportedReason, 0, sizeof(capabilities->notSupportedReason));
      const char* reason = capabilityQueries == 1 ? "" : capabilityQueries == 2 ? "GPU scheduling unavailable" : "DLSS is initializing.";
      memcpy(capabilities->notSupportedReason, reason, strlen(reason));
    } else {
      CHECK(header->sType == REMIXAPI_STRUCT_TYPE_INTEROP_FRAME_STATUS_EXT);
      header->frameId = 1234; header->flags = 3; header->shaderCompileCount = 0;
    }
    for(int i=0;i<16;i++) { info->deviceUUID[i]=(uint8_t)i; info->driverUUID[i]=(uint8_t)(i+16); }
    for(int i=0;i<8;i++) info->deviceLUID[i]=(uint8_t)(i+32);
    info->deviceNodeMask=3; info->deviceLUIDValid=1; info->queueFamilyIndex=7; info->semaphoreType=1;
    info->device=0xabcdef;
    info->dxvkDoneSemaphore_Handle=0x1111; info->dxvkDoneSemaphore_ValueToWait=0x123456789abcdef0ULL; info->clientDoneSemaphore_Handle=0x2222;
    info->color.memoryHandle=0x3333; info->color.allocationSize=0x100000000ULL; info->color.bindOffset=4096;
    info->color.allocationFlags=2; info->color.allocationDeviceMask=3;
    info->color.generation=17; info->color.width=1920; info->color.height=1080; info->color.depth=1;
    info->color.mipLevels=1; info->color.arrayLayers=1; info->color.samples=1;
    info->color.viewFormatCount=2; info->color.viewFormats[0]=37; info->color.viewFormats[1]=43;
    info->color.image=0x9876;
    info->depth=info->color; info->depth.memoryHandle=0x4444; info->depth.generation=18;
    info->uiOverlay=info->color; info->uiOverlay.memoryHandle=0x5555; info->uiOverlay.generation=19;
    return REMIXAPI_ERROR_CODE_SUCCESS;
}
static remixapi_ErrorCode REMIXAPI_CALL mock_CreateSharedBuffer(remixapi_SharedBufferInfo* info) {
  CHECK(info && info->sType == 34 && !info->pNext && info->size == 4096);
  info->buffer=(void*)0x7777; info->memoryHandle=0x8888; info->allocationSize=65536;
  info->bindOffset=256; info->producerSemaphoreHandle=0x9999; info->readyValue=19;
  return REMIXAPI_ERROR_CODE_SUCCESS;
}
static remixapi_ErrorCode REMIXAPI_CALL mock_CommitSharedBuffers(const remixapi_SharedBufferCommitInfo* info) {
  CHECK(info && info->sType == 35 && info->bufferCount == 2 && info->readyValue == 19);
  CHECK(info->buffers[0] == (void*)0x7777 && info->buffers[1] == (void*)0x7778);
  return REMIXAPI_ERROR_CODE_SUCCESS;
}
static remixapi_ErrorCode REMIXAPI_CALL mock_DestroySharedBuffer(remixapi_SharedBufferHandle buffer) {
  CHECK(buffer == (void*)0x7777); return REMIXAPI_ERROR_CODE_SUCCESS;
}
static remixapi_ErrorCode REMIXAPI_CALL mock_SetVoxelCoverage(const remixapi_VoxelCoverageInfo* info) {
  CHECK(info && info->sType == 39 && !info->pNext);
  if (!info->sizeX) { CHECK(!info->sizeY && !info->sizeZ && !info->dataSize && !info->levels); }
  else { CHECK(info->originX == -7 && info->originY == -4 && info->originZ == 3);
    CHECK(info->sizeX == 2 && info->sizeY == 1 && info->sizeZ == 2 && info->cellSize == 16);
    CHECK(info->dataSize == 4 && info->levels[0] == 1 && info->levels[3] == 6); }
  return REMIXAPI_ERROR_CODE_SUCCESS;
}
static remixapi_ErrorCode REMIXAPI_CALL mock_PrepareVulkan(void) { return REMIXAPI_ERROR_CODE_SUCCESS; }
static remixapi_ErrorCode REMIXAPI_CALL mock_CreateVulkanDevice(remixapi_HostVulkanDeviceInfo* info) {
  static const char* extensions[] = {"VK_KHR_swapchain"};
  CHECK(info && info->sType == REMIXAPI_STRUCT_TYPE_HOST_VULKAN_DEVICE_INFO && !info->pNext);
  CHECK(info->instance == 0x1234 && info->physicalDevice == 0x5678 && info->instanceApiVersion == 0x404000);
  CHECK(info->instanceExtensionCount == 2 && info->instanceExtensions == (void*)0x9999);
  CHECK(info->graphicsQueueFamily == 7 && info->deviceCreateInfo == (void*)0x8888);
  info->device=0x7777; info->enabledExtensionCount=1; info->enabledExtensions=extensions;
  return REMIXAPI_ERROR_CODE_SUCCESS;
}
static remixapi_ErrorCode REMIXAPI_CALL mock_AdoptVulkanDevice(uint64_t device) {
  CHECK(device == 0x7777); return REMIXAPI_ERROR_CODE_SUCCESS;
}

REMIXAPI remixapi_ErrorCode REMIXAPI_CALL remixapi_InitializeLibrary(const remixapi_InitializeLibraryInfo* info, remixapi_Interface* out_result) { CHECK(info && info->sType == 1 && info->version == REMIXAPI_VERSION_MAKE(REMIXAPI_VERSION_MAJOR,REMIXAPI_VERSION_MINOR,REMIXAPI_VERSION_PATCH));
out_result->Shutdown=mock_Shutdown;
out_result->CreateMaterial=mock_CreateMaterial;
out_result->DestroyMaterial=mock_DestroyMaterial;
out_result->CreateMesh=mock_CreateMesh;
out_result->DestroyMesh=mock_DestroyMesh;
out_result->SetupCamera=mock_SetupCamera;
out_result->DrawInstance=mock_DrawInstance;
out_result->CreateLight=mock_CreateLight;
out_result->DestroyLight=mock_DestroyLight;
out_result->DrawLightInstance=mock_DrawLightInstance;
out_result->SetConfigVariable=mock_SetConfigVariable;
out_result->dxvk_CreateD3D9=mock_dxvk_CreateD3D9;
out_result->dxvk_RegisterD3D9Device=mock_dxvk_RegisterD3D9Device;
out_result->dxvk_GetExternalSwapchain=mock_dxvk_GetExternalSwapchain;
out_result->dxvk_GetVkImage=mock_dxvk_GetVkImage;
out_result->dxvk_CopyRenderingOutput=mock_dxvk_CopyRenderingOutput;
out_result->dxvk_SetDefaultOutput=mock_dxvk_SetDefaultOutput;
out_result->pick_RequestObjectPicking=mock_pick_RequestObjectPicking;
out_result->pick_HighlightObjects=mock_pick_HighlightObjects;
out_result->Startup=mock_Startup;
out_result->Present=mock_Present;
out_result->CreateTextureFromMemory=mock_CreateTextureFromMemory;
out_result->CreateTextureFromFile=mock_CreateTextureFromFile;
out_result->DestroyTexture=mock_DestroyTexture;
out_result->SetCameraMediumMaterial=mock_SetCameraMediumMaterial;
out_result->UpdateTextureFromMemory=mock_UpdateTextureFromMemory;
out_result->dxvk_GetInteropInfo=mock_dxvk_GetInteropInfo;
out_result->Submit=mock_Submit;
out_result->AcquireConfigLayer=mock_AcquireConfigLayer;
out_result->SetConfigLayerBlending=mock_SetConfigLayerBlending;
out_result->ReleaseConfigLayer=mock_ReleaseConfigLayer;
out_result->SceneInstanceAdd=mock_SceneInstanceAdd;
out_result->SceneInstanceUpdate=mock_SceneInstanceUpdate;
out_result->SceneInstanceRemove=mock_SceneInstanceRemove;
out_result->UpdateMaterial=mock_UpdateMaterial;
out_result->GetConfigVariableResolved=mock_GetConfigVariableResolved;
out_result->dxvk_GetVulkanInteropInfo=mock_dxvk_GetVulkanInteropInfo;
out_result->CreateSharedBuffer=mock_CreateSharedBuffer;
out_result->CommitSharedBuffers=mock_CommitSharedBuffers;
out_result->DestroySharedBuffer=mock_DestroySharedBuffer;
out_result->SetVoxelCoverage=mock_SetVoxelCoverage;
out_result->dxvk_CreateVulkanDevice=mock_CreateVulkanDevice;
out_result->dxvk_PrepareVulkan=mock_PrepareVulkan;
out_result->dxvk_AdoptVulkanDevice=mock_AdoptVulkanDevice;
return 0; }
__declspec(dllexport) int mock_validate_extension(int type, const void* ptr) {
  const remixapi_InitializeLibraryInfo* header=(const remixapi_InitializeLibraryInfo*)ptr;
  CHECK(header && header->sType == type);
  if (type != REMIXAPI_STRUCT_TYPE_LIGHT_INFO_DOME_EXT) CHECK(header->pNext == (void*)0x1234);
  switch(type) {
    case 40: { const remixapi_MaterialInfoVertexColorMaskEXT* p=ptr; CHECK(p->texture == (void*)0x1234); break; }
    case 38: { const remixapi_InstanceInfoVoxelCoverageEXT* p=ptr; CHECK(p->level == 1); break; }
    case 5: {
      const remixapi_MaterialInfoOpaqueEXT* p=ptr;
      CHECK(p->roughnessTexture == (void*)0x1234 && p->anisotropy == 1.5f);
      CHECK(p->albedoConstant.z == 1.5f && p->thinFilmThickness_hasvalue == 1);
      CHECK(p->heightTexture == (void*)0x1234 && p->alphaReferenceValue == 1 && p->displaceOut == 1.5f);
      CHECK(p->enableDlssControlMask == 1); break;
    }
    case 18: {
      const remixapi_MaterialInfoOpaqueSubsurfaceEXT* p=ptr;
      CHECK(p->subsurfaceTransmittanceTexture == (void*)0x1234 && p->subsurfaceMeasurementDistance == 1.5f);
      CHECK(p->subsurfaceDiffusionProfile == 1 && p->subsurfaceRadius.z == 1.5f);
      CHECK(p->subsurfaceRadiusTexture == (void*)0x1234); break;
    }
    case 4: {
      const remixapi_MaterialInfoTranslucentEXT* p=ptr;
      CHECK(p->transmittanceTexture == (void*)0x1234 && p->refractiveIndex == 1.5f);
      CHECK(p->transmittanceColor.z == 1.5f && p->thinWallThickness_hasvalue == 1 && p->useDiffuseLayer == 1); break;
    }
    case 15: {
      const remixapi_InstanceInfoBlendEXT* p=ptr;
      CHECK(p->alphaTestEnabled == 1 && p->alphaTestReferenceValue == 1 && p->alphaTestCompareOp == 1);
      CHECK(p->isTextureFactorBlend == 1 && p->alphaBlendOp == 1 && p->isVertexColorBakedLighting == 1); break;
    }
    case 14: {
      const remixapi_InstanceInfoBoneTransformsEXT* p=ptr;
      CHECK(p->boneTransforms_count == 1 && p->boneTransforms_values[0].matrix[0][0] == 1.5f); break;
    }
    case 26: {
      const remixapi_InstanceInfoGpuInstancingEXT* p=ptr;
      CHECK(p->instanceTransforms_count == 1 && p->instanceTransforms_values[0].matrix[0][0] == 1.5f); break;
    }
    case 11: {
      const remixapi_LightInfoSphereEXT* p=ptr;
      CHECK(p->position.z == 1.5f && p->radius == 1.5f && p->shaping_hasvalue == 1);
      CHECK(p->shaping_value.direction.x == 1.5f && p->shaping_value.focusExponent == 1.5f && p->volumetricRadianceScale == 1.5f); break;
    }
    case 7: {
      const remixapi_LightInfoDistantEXT* p=ptr;
      CHECK(p->direction.z == 1.5f && p->angularDiameterDegrees == 1.5f && p->volumetricRadianceScale == 1.5f); break;
    }
    case 10: {
      const remixapi_LightInfoRectEXT* p=ptr;
      CHECK(p->position.z == 1.5f && p->xAxis.x == 1.5f && p->xSize == 1.5f && p->ySize == 1.5f);
      CHECK(p->direction.x == 1.5f && p->shaping_hasvalue == 1 && p->shaping_value.coneAngleDegrees == 1.5f && p->volumetricRadianceScale == 1.5f); break;
    }
    case 9: {
      const remixapi_LightInfoDiskEXT* p=ptr;
      CHECK(p->position.z == 1.5f && p->xAxis.x == 1.5f && p->xRadius == 1.5f && p->yRadius == 1.5f);
      CHECK(p->direction.x == 1.5f && p->shaping_hasvalue == 1 && p->shaping_value.coneAngleDegrees == 1.5f && p->volumetricRadianceScale == 1.5f); break;
    }
    case 8: {
      const remixapi_LightInfoCylinderEXT* p=ptr;
      CHECK(p->position.z == 1.5f && p->radius == 1.5f && p->axis.z == 1.5f && p->axisLength == 1.5f && p->volumetricRadianceScale == 1.5f); break;
    }
    case 20: {
      const remixapi_LightInfoDomeEXT* p=ptr;
      CHECK(p->transform.matrix[0][0] == 0 && p->colorTexture == (void*)0x1234 && p->opacity == 1.5f && p->blendType == 1);
      const remixapi_LightInfoDomeTransmittanceEXT* transmission = p->pNext;
      CHECK(transmission && transmission->sType == REMIXAPI_STRUCT_TYPE_LIGHT_INFO_DOME_TRANSMITTANCE_EXT);
      CHECK(transmission->transmittanceTexture == (void*)0x1234);
      const remixapi_LightInfoDomeProjectionEXT* projection = transmission->pNext;
      CHECK(projection->sType == REMIXAPI_STRUCT_TYPE_LIGHT_INFO_DOME_PROJECTION_EXT);
      CHECK(projection->projection == 1 && projection->backgroundOnly == 1 && projection->pNext == (void*)0x1234);
      break;
    }
    case 17: {
      const remixapi_CameraInfoParameterizedEXT* p=ptr;
      CHECK(p->position.z == 1.5f && p->forward.z == 1.5f && p->up.z == 1.5f && p->right.z == 1.5f);
      CHECK(p->fovYInDegrees == 1.5f && p->aspect == 1.5f && p->nearPlane == 1.5f && p->farPlane == 1.5f); break;
    }
    default: return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
  }
  return 0;
}


struct mock_TracyCtx { uint32_t id; int32_t active; };
static uint32_t mock_tracy_calls;
static uint32_t mock_tracy_errors;
__declspec(dllexport) uint64_t ___tracy_alloc_srcloc_name(uint32_t line, const char* source, size_t sourceSize, const char* function, size_t functionSize, const char* name, size_t nameSize) {
    if(line != 0 || sourceSize != 4 || memcmp(source,"Java",4) || functionSize != 4 || nameSize != 4 || memcmp(name,"zone",4)) mock_tracy_errors++;
    mock_tracy_calls |= 1; return 123;
}
__declspec(dllexport) struct mock_TracyCtx ___tracy_emit_zone_begin_alloc(uint64_t location, int32_t active) {
    if(location != 123 || active != 1) mock_tracy_errors++;
    mock_tracy_calls |= 2; struct mock_TracyCtx result={77,1}; return result;
}
__declspec(dllexport) void ___tracy_emit_zone_end(struct mock_TracyCtx context) { if(context.id != 77 || context.active != 1) mock_tracy_errors++; mock_tracy_calls |= 4; }
__declspec(dllexport) void ___tracy_set_thread_name(const char* name) { if(strcmp(name,"thread")) mock_tracy_errors++; mock_tracy_calls |= 8; }
__declspec(dllexport) void ___tracy_emit_plot(const char* name, double value) { if(strcmp(name,"plot") || value != 2.5) mock_tracy_errors++; mock_tracy_calls |= 16; }
__declspec(dllexport) void ___tracy_emit_message(const char* text, size_t length, int callstack) { if(length != 7 || memcmp(text,"message",7) || callstack) mock_tracy_errors++; mock_tracy_calls |= 32; }
__declspec(dllexport) void ___tracy_emit_frame_mark(const char* name) { if(strcmp(name,"frame")) mock_tracy_errors++; mock_tracy_calls |= 64; }
__declspec(dllexport) int mock_tracy_validate(void) { return mock_tracy_calls == 127 && mock_tracy_errors == 0; }

__declspec(dllexport) int remixinternal_GetNrcStatus(uint32_t* records) {
    if (records) *records = 0;
    return 0;
}

/* Match the runtime's Tracy destructor: explicit unload terminates even with a success exit code. */
BOOL WINAPI DllMain(HINSTANCE module, DWORD reason, LPVOID reserved) {
    (void)module;
    if (reason == DLL_PROCESS_DETACH && reserved == NULL) ExitProcess(0);
    return TRUE;
}
