/*
 * Copyright (c) 2023-2026, NVIDIA CORPORATION. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

#ifndef REMIX_C_H_
#define REMIX_C_H_

#include <stdint.h>

#ifndef REMIX_WINAPI_NO_INCLUDE
#include <windows.h>
#endif


#ifndef REMIX_ALLOW_X86
#if _WIN64 != 1
  #error Remix API requires 64-bit for the ray tracing features.
#endif
#endif


// __stdcall convention
#ifndef REMIXAPI_CALL
  #define REMIXAPI_CALL __stdcall
#endif
#define REMIXAPI_PTR  REMIXAPI_CALL

#if defined(__cplusplus) && __cplusplus >= 201103L
#define REMIXAPI_INLINE inline
#define REMIXAPI_HELPER constexpr
#else // C99 / C++98
#define REMIXAPI_INLINE static inline
#define REMIXAPI_HELPER static inline
#endif

#ifndef REMIXAPI
  #ifdef REMIX_LIBRARY_EXPORTS
    #define REMIXAPI __declspec(dllexport)
  #else
    #define REMIXAPI __declspec(dllimport)
  #endif
#endif // REMIXAPI


#define REMIXAPI_VERSION_MAKE(major, minor, patch) ( \
    (((uint64_t)(major)) << 48) | \
    (((uint64_t)(minor)) << 16) | \
    (((uint64_t)(patch))      ) )
#define REMIXAPI_VERSION_GET_MAJOR(version) (((uint64_t)(version) >> 48) & (uint64_t)0xFFFF)
#define REMIXAPI_VERSION_GET_MINOR(version) (((uint64_t)(version) >> 16) & (uint64_t)0xFFFFFFFF)
#define REMIXAPI_VERSION_GET_PATCH(version) (((uint64_t)(version)      ) & (uint64_t)0xFFFF)

#define REMIXAPI_VERSION_MAJOR 1
#define REMIXAPI_VERSION_MINOR 5
#define REMIXAPI_VERSION_PATCH 0
#define REMIXAPI_FORK_IDENTIFIER "water2004/dxvk-remix"


// External
typedef struct IDirect3D9Ex       IDirect3D9Ex;
typedef struct IDirect3DDevice9Ex IDirect3DDevice9Ex;
typedef struct IDirect3DSurface9  IDirect3DSurface9;

#ifndef REMIX_WINAPI_NO_INCLUDE
  typedef HWND remixapi_HWND;
  typedef HMODULE remixapi_HMODULE;
  typedef FARPROC remixapi_loader_PROC;
  #define REMIX_WINAPI_LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR
  #define REMIX_WINAPI_LOAD_LIBRARY_SEARCH_DEFAULT_DIRS LOAD_LIBRARY_SEARCH_DEFAULT_DIRS
  #define REMIX_WINAPI_MAX_PATH                         MAX_PATH
#else // if there's no 'Windows.h', then declare WINAPI types/functions manually
  typedef struct HWND__* remixapi_HWND;
  typedef struct HINSTANCE__* remixapi_HMODULE;
  typedef long long(__stdcall* remixapi_loader_PROC)();
  #define REMIX_WINAPI_LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR 0x00000100
  #define REMIX_WINAPI_LOAD_LIBRARY_SEARCH_DEFAULT_DIRS 0x00001000
  #define REMIX_WINAPI_MAX_PATH                         260
  #ifndef REMIX_WINAPI_NO_LIBRARY_LOADER // if loader is needed
  #ifdef __cplusplus
  extern "C" {
  #endif // __cplusplus
    __declspec(dllimport) unsigned long        __stdcall GetDllDirectoryW(unsigned long nBufferLength, wchar_t* lpBuffer);
    __declspec(dllimport) int                  __stdcall FreeLibrary(remixapi_HMODULE hLibModule);
    __declspec(dllimport) remixapi_HMODULE     __stdcall LoadLibraryW(const wchar_t* lpLibFileName);
    __declspec(dllimport) remixapi_HMODULE     __stdcall LoadLibraryExW(const wchar_t* lpLibFileName, void* hFile, unsigned long dwFlags);
    __declspec(dllimport) remixapi_loader_PROC __stdcall GetProcAddress(remixapi_HMODULE hModule, const char* lpProcName);
    __declspec(dllimport) int                  __stdcall SetDllDirectoryW(const wchar_t* lpPathName);
    __declspec(dllimport) unsigned long        __stdcall GetFullPathNameW(const wchar_t* lpFileName, unsigned long nBufferLength, wchar_t* lpBuffer, wchar_t** lpFilePart);
  #ifdef __cplusplus
  }
  #endif // __cplusplus
  #endif // !REMIX_WINAPI_NO_LIBRARY_LOADER
#endif // !REMIX_WINAPI_NO_INCLUDE


#ifdef __cplusplus
extern "C" {
#endif // __cplusplus

  typedef enum remixapi_StructType {
    REMIXAPI_STRUCT_TYPE_NONE                                 = 0,
    REMIXAPI_STRUCT_TYPE_INITIALIZE_LIBRARY_INFO              = 1,
    REMIXAPI_STRUCT_TYPE_MATERIAL_INFO                        = 2,
    REMIXAPI_STRUCT_TYPE_MATERIAL_INFO_PORTAL_EXT             = 3,
    REMIXAPI_STRUCT_TYPE_MATERIAL_INFO_TRANSLUCENT_EXT        = 4,
    REMIXAPI_STRUCT_TYPE_MATERIAL_INFO_OPAQUE_EXT             = 5,
    REMIXAPI_STRUCT_TYPE_LIGHT_INFO                           = 6,
    REMIXAPI_STRUCT_TYPE_LIGHT_INFO_DISTANT_EXT               = 7,
    REMIXAPI_STRUCT_TYPE_LIGHT_INFO_CYLINDER_EXT              = 8,
    REMIXAPI_STRUCT_TYPE_LIGHT_INFO_DISK_EXT                  = 9,
    REMIXAPI_STRUCT_TYPE_LIGHT_INFO_RECT_EXT                  = 10,
    REMIXAPI_STRUCT_TYPE_LIGHT_INFO_SPHERE_EXT                = 11,
    REMIXAPI_STRUCT_TYPE_MESH_INFO                            = 12,
    REMIXAPI_STRUCT_TYPE_INSTANCE_INFO                        = 13,
    REMIXAPI_STRUCT_TYPE_INSTANCE_INFO_BONE_TRANSFORMS_EXT    = 14,
    REMIXAPI_STRUCT_TYPE_INSTANCE_INFO_BLEND_EXT              = 15,
    REMIXAPI_STRUCT_TYPE_CAMERA_INFO                          = 16,
    REMIXAPI_STRUCT_TYPE_CAMERA_INFO_PARAMETERIZED_EXT        = 17,
    REMIXAPI_STRUCT_TYPE_MATERIAL_INFO_OPAQUE_SUBSURFACE_EXT  = 18,
    REMIXAPI_STRUCT_TYPE_INSTANCE_INFO_OBJECT_PICKING_EXT     = 19,
    REMIXAPI_STRUCT_TYPE_LIGHT_INFO_DOME_EXT                  = 20,
    REMIXAPI_STRUCT_TYPE_LIGHT_INFO_USD_EXT                   = 21,
    REMIXAPI_STRUCT_TYPE_STARTUP_INFO                         = 22,
    REMIXAPI_STRUCT_TYPE_PRESENT_INFO                         = 23,
    REMIXAPI_STRUCT_TYPE_DEPRECATED_LEGACY_PARTICLE_SYSTEM    = 24,
    REMIXAPI_STRUCT_TYPE_INSTANCE_INFO_PARTICLE_SYSTEM_EXT    = 25,
    REMIXAPI_STRUCT_TYPE_INSTANCE_INFO_GPU_INSTANCING_EXT     = 26,
    REMIXAPI_STRUCT_TYPE_CAMERA_MEDIUM_INFO                   = 27,
    REMIXAPI_STRUCT_TYPE_TEXTURE_FROM_MEMORY_INFO             = 28,
    REMIXAPI_STRUCT_TYPE_TEXTURE_PATCH_INFO                   = 29,
    REMIXAPI_STRUCT_TYPE_SUBMIT_INFO                          = 30,
    REMIXAPI_STRUCT_TYPE_CONFIG_VARIABLE_RESOLVED_INFO        = 31,
    REMIXAPI_STRUCT_TYPE_VULKAN_INTEROP_INFO                  = 32,
    REMIXAPI_STRUCT_TYPE_INTEROP_FRAME_STATUS_EXT             = 33,
    REMIXAPI_STRUCT_TYPE_SHARED_BUFFER_INFO                  = 34,
    REMIXAPI_STRUCT_TYPE_SHARED_BUFFER_COMMIT_INFO           = 35,
    REMIXAPI_STRUCT_TYPE_MESH_INFO_SHARED_BUFFERS_EXT         = 36,
    REMIXAPI_STRUCT_TYPE_TEXTURE_MIP_CHAIN_EXT                = 37,
    REMIXAPI_STRUCT_TYPE_INSTANCE_INFO_VOXEL_COVERAGE_EXT      = 38,
    REMIXAPI_STRUCT_TYPE_VOXEL_COVERAGE_INFO                   = 39,
    REMIXAPI_STRUCT_TYPE_MATERIAL_INFO_VERTEX_COLOR_MASK_EXT   = 40,
    REMIXAPI_STRUCT_TYPE_TEXTURE_IMMUTABLE_EXT                 = 41,
    REMIXAPI_STRUCT_TYPE_HOST_VULKAN_DEVICE_INFO               = 42,
    REMIXAPI_STRUCT_TYPE_FRAME_GENERATION_CAPABILITIES_EXT     = 43,
    REMIXAPI_STRUCT_TYPE_VULKAN_CLIENT_FRAME_INFO_EXT          = 44,
    // NOTE: always leave ',' after the number, so the git diff is cleaner!
    // NOTE: if adding a new struct, register it in 'rtx_remix_specialization.inl'
    //       and only extend this enum by appending, never adjust the order of these 
    //       as that will break backwards compatibility.
  } remixapi_StructType;

  typedef enum remixapi_ErrorCode {
    REMIXAPI_ERROR_CODE_SUCCESS                           = 0,
    REMIXAPI_ERROR_CODE_GENERAL_FAILURE                   = 1,
    // WinAPI's LoadLibrary has failed
    REMIXAPI_ERROR_CODE_LOAD_LIBRARY_FAILURE              = 2,
    REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS                 = 3,
    // Couldn't find 'remixInitialize' function in the .dll
    REMIXAPI_ERROR_CODE_GET_PROC_ADDRESS_FAILURE          = 4,
    // CreateD3D9 / RegisterD3D9Device can be called only once
    REMIXAPI_ERROR_CODE_ALREADY_EXISTS                    = 5,
    // RegisterD3D9Device requires the device that was created with IDirect3DDevice9Ex, returned by CreateD3D9
    REMIXAPI_ERROR_CODE_REGISTERING_NON_REMIX_D3D9_DEVICE = 6,
    // RegisterD3D9Device was not called
    REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED   = 7,
    REMIXAPI_ERROR_CODE_INCOMPATIBLE_VERSION              = 8,
    // WinAPI's SetDllDirectory has failed
    REMIXAPI_ERROR_CODE_SET_DLL_DIRECTORY_FAILURE         = 9,
    // WinAPI's GetFullPathName has failed
    REMIXAPI_ERROR_CODE_GET_FULL_PATH_NAME_FAILURE        = 10,
    REMIXAPI_ERROR_CODE_NOT_INITIALIZED                   = 11,
    REMIXAPI_ERROR_CODE_INDEX_OUT_OF_RANGE                = 12,
    // An output buffer is too small. The call reports the size it needs.
    REMIXAPI_ERROR_CODE_BUFFER_TOO_SMALL                  = 13,
    // Error codes that are encoded as HRESULT, i.e. returned from D3D9 functions.
    // Look MAKE_D3DHRESULT, but with _FACD3D=0x896, instead of D3D9's 0x876
    REMIXAPI_ERROR_CODE_HRESULT_NO_REQUIRED_GPU_FEATURES      = 0x88960001,
    REMIXAPI_ERROR_CODE_HRESULT_DRIVER_VERSION_BELOW_MINIMUM  = 0x88960002,
    REMIXAPI_ERROR_CODE_HRESULT_DXVK_INSTANCE_EXTENSION_FAIL  = 0x88960003,
    REMIXAPI_ERROR_CODE_HRESULT_VK_CREATE_INSTANCE_FAIL       = 0x88960004,
    REMIXAPI_ERROR_CODE_HRESULT_VK_CREATE_DEVICE_FAIL         = 0x88960005,
    REMIXAPI_ERROR_CODE_HRESULT_GRAPHICS_QUEUE_FAMILY_MISSING = 0x88960006,
  } remixapi_ErrorCode;

  typedef uint32_t remixapi_Bool;

  typedef struct remixapi_Rect2D {
    int32_t left;
    int32_t top;
    int32_t right;
    int32_t bottom;
  } remixapi_Rect2D;

  typedef struct remixapi_Float2D {
    float x;
    float y;
  } remixapi_Float2D;

  typedef struct remixapi_Float3D {
    float x;
    float y;
    float z;
  } remixapi_Float3D;

  typedef struct remixapi_Float4D {
    float x;
    float y;
    float z;
    float w;
  } remixapi_Float4D;

  typedef struct remixapi_Transform {
    float matrix[3][4];
  } remixapi_Transform;

  typedef struct remixapi_MaterialHandle_T* remixapi_MaterialHandle;
  typedef struct remixapi_MeshHandle_T* remixapi_MeshHandle;
  typedef struct remixapi_LightHandle_T* remixapi_LightHandle;
  typedef struct remixapi_TextureHandle_T* remixapi_TextureHandle;
  typedef struct remixapi_SceneInstanceHandle_T* remixapi_SceneInstanceHandle;
  typedef struct remixapi_ConfigLayerHandle_T* remixapi_ConfigLayerHandle;
  typedef struct remixapi_SharedBufferHandle_T* remixapi_SharedBufferHandle;

  typedef const wchar_t* remixapi_Path;



  typedef struct remixapi_StartupInfo {
    remixapi_StructType sType;
    void*               pNext;
    remixapi_HWND       hwnd;
    remixapi_Bool       disableSrgbConversionForOutput;
    // If true, 'dxvk_GetExternalSwapchain' can be used to retrieve a raw VkImage,
    // so the application can present it, for example by using OpenGL interop:
    // converting VkImage to OpenGL, and presenting it via OpenGL.
    // Default: false. Use VkSwapchainKHR to present frame into HWND.
    remixapi_Bool       forceNoVkSwapchain;
    remixapi_Bool       editorModeEnabled;
    // With this disabled, the user must fetch the GUI buffer using 
    // the, remixapi_dxvk_CopyRenderingOutputType, api with the, 
    // remixapi_dxvk_CopyRenderingOutputType, field set to: 'REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_GUI'
    // Otherwise the GUI will be drawn in the final color buffer.
    remixapi_Bool       combineGuiInFinalColor;
    // If true, Remix allocates an interop image (along with semaphores) that can be used with non-Vulkan API.
    // See comments on 'remixapi_dxvk_InteropInfo' on the interop flow.
    remixapi_Bool       interopImage;
  } remixapi_StartupInfo;

  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_Startup)(const remixapi_StartupInfo* info);
  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_Shutdown)(void);



  typedef struct remixapi_MaterialInfoOpaqueEXT {
    remixapi_StructType     sType;
    void*                   pNext;
    remixapi_TextureHandle  roughnessTexture;
    remixapi_TextureHandle  metallicTexture;
    float                   anisotropy;
    remixapi_Float3D        albedoConstant;
    float                   opacityConstant;
    float                   roughnessConstant;
    float                   metallicConstant;
    remixapi_Bool           thinFilmThickness_hasvalue;
    float                   thinFilmThickness_value;
    remixapi_Bool           alphaIsThinFilmThickness;
    remixapi_TextureHandle  heightTexture;
    float                   displaceIn;
    // If true, InstanceInfoBlendEXT is used as a source for alpha state
    remixapi_Bool           useDrawCallAlphaState;
    remixapi_Bool           blendType_hasvalue;
    int                     blendType_value;
    remixapi_Bool           invertedBlend;
    int                     alphaTestType;
    uint8_t                 alphaReferenceValue;
    float                   displaceOut;
    remixapi_Bool       enableDlssControlMask;
    float               dlssControlMaskIntensity;
    float               dlssControlMaskToneStrength;
    float               dlssControlMaskStructuralStrength;
  } remixapi_MaterialInfoOpaqueEXT;

  // Valid only if remixapi_MaterialInfo contains remixapi_MaterialInfoOpaqueEXT in pNext chain
  typedef struct remixapi_MaterialInfoOpaqueSubsurfaceEXT {
    remixapi_StructType     sType;
    void*                   pNext;
    remixapi_TextureHandle  subsurfaceTransmittanceTexture;
    remixapi_TextureHandle  subsurfaceThicknessTexture;
    remixapi_TextureHandle  subsurfaceSingleScatteringAlbedoTexture;
    remixapi_Float3D        subsurfaceTransmittanceColor;
    float                   subsurfaceMeasurementDistance;
    remixapi_Float3D        subsurfaceSingleScatteringAlbedo;
    float                   subsurfaceVolumetricAnisotropy;
    remixapi_Bool           subsurfaceDiffusionProfile;
    remixapi_Float3D        subsurfaceRadius;
    float                   subsurfaceRadiusScale;
    float                   subsurfaceMaxSampleRadius;
    remixapi_TextureHandle  subsurfaceRadiusTexture;
  } remixapi_MaterialInfoOpaqueSubsurfaceEXT;

  typedef struct remixapi_MaterialInfoTranslucentEXT {
    remixapi_StructType     sType;
    void*                   pNext;
    remixapi_TextureHandle  transmittanceTexture;
    float                   refractiveIndex;
    remixapi_Float3D        transmittanceColor;
    float                   transmittanceMeasurementDistance;
    remixapi_Bool           thinWallThickness_hasvalue;
    float                   thinWallThickness_value;
    remixapi_Bool           useDiffuseLayer;
  } remixapi_MaterialInfoTranslucentEXT;

  typedef struct remixapi_MaterialInfoPortalEXT {
    remixapi_StructType sType;
    void*               pNext;
    uint8_t             rayPortalIndex;
    float               rotationSpeed;
  } remixapi_MaterialInfoPortalEXT;

  typedef struct remixapi_MaterialInfo {
    remixapi_StructType     sType;
    void*                   pNext;
    uint64_t                hash;
    remixapi_TextureHandle  albedoTexture;
    remixapi_TextureHandle  normalTexture;
    remixapi_TextureHandle  tangentTexture;
    remixapi_TextureHandle  emissiveTexture;
    float                   emissiveIntensity;
    remixapi_Float3D        emissiveColorConstant;
    uint8_t                 spriteSheetRow;
    uint8_t                 spriteSheetCol;
    uint8_t                 spriteSheetFps;
    uint8_t                 filterMode;
    uint8_t                 wrapModeU;
    uint8_t                 wrapModeV;
  } remixapi_MaterialInfo;


  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_CreateMaterial)(
    const remixapi_MaterialInfo*  info,
    remixapi_MaterialHandle*      out_handle);

  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_DestroyMaterial)(
    remixapi_MaterialHandle       handle);

  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_UpdateMaterial)(
    remixapi_MaterialHandle       handle,
    const remixapi_MaterialInfo*  info);

  typedef enum remixapi_TextureFormat {
    REMIXAPI_TEXTURE_FORMAT_RGBA8_UNORM    = 0,  // 4 bytes/pixel
    REMIXAPI_TEXTURE_FORMAT_RGBA8_SRGB     = 1,  // 4 bytes/pixel
    REMIXAPI_TEXTURE_FORMAT_BGRA8_UNORM    = 2,  // 4 bytes/pixel
    REMIXAPI_TEXTURE_FORMAT_BGRA8_SRGB     = 3,  // 4 bytes/pixel
    REMIXAPI_TEXTURE_FORMAT_R8_UNORM       = 4,  // 1 byte/pixel  (roughness, metallic, height)
    REMIXAPI_TEXTURE_FORMAT_RG8_UNORM      = 5,  // 2 bytes/pixel (normal XY)
    REMIXAPI_TEXTURE_FORMAT_RGBA16_SFLOAT  = 6,  // 8 bytes/pixel (HDR half-float)
    REMIXAPI_TEXTURE_FORMAT_RGBA32_SFLOAT  = 7,  // 16 bytes/pixel (HDR full-float)
    REMIXAPI_TEXTURE_FORMAT_R32_SFLOAT     = 8,  // 4 bytes/pixel (single-channel float)
  } remixapi_TextureFormat;

  // Describes how the texture will be used, affecting mip generation, encoding conversion,
  // and sampling behavior. Format (SRGB vs UNORM etc.) controls bit interpretation;
  // purpose controls processing semantics.
  typedef enum remixapi_TexturePurpose {
    // Color/albedo/emissive: standard linear mip filtering
    REMIXAPI_TEXTURE_PURPOSE_COLOR       = 0,
    // Linear data (roughness, metallic, opacity): linear mip filtering, no sRGB
    REMIXAPI_TEXTURE_PURPOSE_DATA        = 1,
    // Normal map in DirectX convention (Y+ points down): converted to octahedral internally
    REMIXAPI_TEXTURE_PURPOSE_NORMAL_DX   = 2,
    // Normal map in OpenGL convention (Y+ points up): converted to octahedral internally
    REMIXAPI_TEXTURE_PURPOSE_NORMAL_OGL  = 3,
    // Normal map already in octahedral encoding (2-channel): used as-is
    REMIXAPI_TEXTURE_PURPOSE_NORMAL_OTH  = 4,
    // Height/displacement map: mip levels store max values for hierarchical POM raymarching
    REMIXAPI_TEXTURE_PURPOSE_HEIGHTMAP   = 5,
  } remixapi_TexturePurpose;

  typedef struct remixapi_TextureFromMemoryInfo {
    remixapi_StructType      sType;
    void*                    pNext;
    uint64_t                 hash;
    const uint8_t*           pixelData;
    uint32_t                 width;
    uint32_t                 height;
    remixapi_TextureFormat   format;
    remixapi_TexturePurpose  purpose;  // How to process mips and sample this texture
  } remixapi_TextureFromMemoryInfo;

  // Packed authored mip levels, beginning at pixelData. Dimensions halve to at least 1.
  // No mip filtering is performed. Raw normal inputs (purpose 2/3) are unsupported.
  typedef struct remixapi_TextureMipChainEXT {
    remixapi_StructType sType;
    void* pNext;
    uint32_t mipLevels;
    uint64_t dataSize;
  } remixapi_TextureMipChainEXT;

  // Optional creation-only marker. Releases CPU pixels after upload and rejects all patches.
  // May be chained with TextureMipChainEXT. Without this marker, textures remain updateable.
  typedef struct remixapi_TextureImmutableEXT {
    remixapi_StructType sType;
    void* pNext;
  } remixapi_TextureImmutableEXT;

  // A single sub-region patch applied to a target texture's mipLevel. Pixel
  // data must be in the destination texture's format (no conversion is
  // performed at upload time; see remixapi_UpdateTextureFromMemory).
  typedef struct remixapi_TexturePatch {
    uint32_t dstX;
    uint32_t dstY;
    uint32_t width;
    uint32_t height;
    const uint8_t* pixelData;
    uint32_t rowPitch;
    remixapi_TextureFormat format;
    uint32_t mipLevel;
  } remixapi_TexturePatch;

  // A batch entry groups one or more patches targeting the same texture.
  // Auto-generated textures accept mip 0 and regenerate affected mip regions.
  // Authored mip chains accept explicit levels and never regenerate them.
  typedef struct remixapi_TextureBatchEntry {
    remixapi_TextureHandle        handle;
    uint32_t                      patchCount;
    const remixapi_TexturePatch*  patches;
  } remixapi_TextureBatchEntry;

  // Multi-handle batch info. A single remixapi_UpdateTextureFromMemory call
  // can target any number of textures by supplying multiple entries; this
  // amortizes API-crossing cost and lets the runtime coalesce work for
  // callers that update several related atlases per frame.
  typedef struct remixapi_TexturePatchInfo {
    remixapi_StructType                sType;
    void*                              pNext;
    uint32_t                           entryCount;
    const remixapi_TextureBatchEntry*  entries;
  } remixapi_TexturePatchInfo;

  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_CreateTextureFromMemory)(
    const remixapi_TextureFromMemoryInfo* info,
    remixapi_TextureHandle*               out_handle);

  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_CreateTextureFromFile)(
    remixapi_Path                         filePath,
    remixapi_TextureHandle*               out_handle);

  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_DestroyTexture)(
    remixapi_TextureHandle                handle);

  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_UpdateTextureFromMemory)(
    const remixapi_TexturePatchInfo* info);

  typedef struct remixapi_HardcodedVertex {
    float    position[3];
    float    normal[3];
    float    texcoord[2];
    uint32_t color;
    // Optional texture atlas region for UV wrapping: min.u, min.v, max.u, max.v as IEEE 754 half-floats.
    // Enabled if remixapi_MeshInfoSurfaceTriangles sets REMIXAPI_MESH_INFO_SURFACE_TRIANGLES_BIT_TEXCOORD_BOUNDS.
    uint16_t texcoordBounds[4];
    uint32_t _pad0;
    uint32_t _pad1;
    uint32_t _pad2;
    uint32_t _pad3;
    uint32_t _pad4;
  } remixapi_HardcodedVertex;

  // CPU uploads can omit trailing padding with COMPACT_VERTICES. Shared-buffer surfaces
  // use the full remixapi_HardcodedVertex stride.
  enum { REMIXAPI_HARDCODED_VERTEX_STORAGE_STRIDE = 44 };

  typedef struct remixapi_MeshInfoSkinning {
    uint32_t                        bonesPerVertex;
    // Each tuple of 'bonesPerVertex' float-s defines a vertex.
    // I.e. the size must be (bonesPerVertex * vertexCount).
    const float*                    blendWeights_values;
    uint32_t                        blendWeights_count;
    // Each tuple of 'bonesPerVertex' uint32_t-s defines a vertex.
    // I.e. the size must be (bonesPerVertex * vertexCount).
    const uint32_t*                 blendIndices_values;
    uint32_t                        blendIndices_count;
  } remixapi_MeshInfoSkinning;

  typedef enum remixapi_MeshInfoSurfaceTrianglesFlagBit {
    REMIXAPI_MESH_INFO_SURFACE_TRIANGLES_BIT_TEXCOORD_BOUNDS = 1 << 0,
    // CreateMesh CPU input only: vertices_values points to consecutive 44-byte records,
    // containing all fields before _pad0. The runtime copies them before returning.
    REMIXAPI_MESH_INFO_SURFACE_TRIANGLES_BIT_COMPACT_VERTICES = 1 << 1,
  } remixapi_MeshInfoSurfaceTrianglesFlagBit;
  typedef uint32_t remixapi_MeshInfoSurfaceTrianglesFlags;

  typedef struct remixapi_MeshInfoSurfaceTriangles {
    const remixapi_HardcodedVertex* vertices_values;
    uint64_t                        vertices_count;
    const uint32_t*                 indices_values;
    uint64_t                        indices_count;
    remixapi_Bool                   skinning_hasvalue;
    remixapi_MeshInfoSkinning       skinning_value;
    remixapi_MaterialHandle         material;
    remixapi_MeshInfoSurfaceTrianglesFlags flags;
    uint64_t                               reserved; // Reserved for future use, must be 0
  } remixapi_MeshInfoSurfaceTriangles;

  typedef struct remixapi_MeshInfo {
    remixapi_StructType                      sType;
    void*                                    pNext;
    uint64_t                                 hash; // deprecated: 'CreateMesh' generates a unique remixapi_MeshHandle independent of the 'hash'
    const remixapi_MeshInfoSurfaceTriangles* surfaces_values;
    uint32_t                                 surfaces_count;
  } remixapi_MeshInfo;

  // A write-once GPU allocation. The runtime owns every returned Win32 handle.
  // Import into OpenGL, write, signal producerSemaphoreHandle to readyValue, and
  // CommitSharedBuffers before referencing this allocation from a mesh.
  // A committed buffer is immutable until all meshes referencing it are destroyed.
  typedef struct remixapi_SharedBufferInfo {
    remixapi_StructType sType;
    void* pNext;
    uint64_t size;
    remixapi_SharedBufferHandle buffer;
    uint64_t memoryHandle;
    uint64_t allocationSize;
    uint64_t bindOffset;
    uint64_t producerSemaphoreHandle;
    uint64_t readyValue;
  } remixapi_SharedBufferInfo;

  typedef struct remixapi_SharedBufferCommitInfo {
    remixapi_StructType sType;
    void* pNext;
    const remixapi_SharedBufferHandle* buffers;
    uint32_t bufferCount;
    uint64_t readyValue;
  } remixapi_SharedBufferCommitInfo;

  typedef struct remixapi_MeshSharedSurface {
    remixapi_SharedBufferHandle vertexBuffer;
    uint64_t vertexOffset;
    uint64_t vertexCount;
    remixapi_SharedBufferHandle indexBuffer;
    uint64_t indexOffset;
    uint64_t indexCount;
    remixapi_MaterialHandle material;
    remixapi_MeshInfoSurfaceTrianglesFlags flags;
    uint32_t reserved;
  } remixapi_MeshSharedSurface;

  // Extends MeshInfo with GPU-resident HardcodedVertex and uint32 index slices.
  // The base MeshInfo's surfaces_values and surfaces_count must be empty.
  // CreateMesh retains the allocations independently of their public buffer handles.
  typedef struct remixapi_MeshInfoSharedBuffersEXT {
    remixapi_StructType sType;
    void* pNext;
    const remixapi_MeshSharedSurface* surfaces;
    uint32_t surfaceCount;
  } remixapi_MeshInfoSharedBuffersEXT;

  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_CreateSharedBuffer)(remixapi_SharedBufferInfo* info);
  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_CommitSharedBuffers)(const remixapi_SharedBufferCommitInfo* info);
  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_DestroySharedBuffer)(remixapi_SharedBufferHandle buffer);

  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_CreateMesh)(
    const remixapi_MeshInfo*  info,
    remixapi_MeshHandle*      out_handle);

  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_DestroyMesh)(
    remixapi_MeshHandle       handle);


  typedef enum remixapi_CameraType {
    REMIXAPI_CAMERA_TYPE_WORLD,
    REMIXAPI_CAMERA_TYPE_SKY,
    REMIXAPI_CAMERA_TYPE_VIEW_MODEL,
  } remixapi_CameraType;

  typedef struct remixapi_CameraInfoParameterizedEXT {
    remixapi_StructType sType;
    void*               pNext;
    remixapi_Float3D    position;
    remixapi_Float3D    forward;
    remixapi_Float3D    up;
    remixapi_Float3D    right;
    float               fovYInDegrees;
    float               aspect;
    float               nearPlane;
    float               farPlane;
  } remixapi_CameraInfoParameterizedEXT;

  typedef struct remixapi_CameraInfo {
    remixapi_StructType sType;
    void*               pNext;
    remixapi_CameraType type;
    float               view[4][4];
    float               projection[4][4];
  } remixapi_CameraInfo;

  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_SetupCamera)(
    const remixapi_CameraInfo* info);

  typedef struct remixapi_CameraMediumInfo {
    remixapi_StructType     sType;
    void*                   pNext;
    remixapi_MaterialHandle medium;
  } remixapi_CameraMediumInfo;

  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_SetCameraMediumMaterial)(
    const remixapi_CameraMediumInfo* info);


#define REMIXAPI_INSTANCE_INFO_MAX_BONES_COUNT 256

  typedef struct remixapi_InstanceInfoBoneTransformsEXT {
      remixapi_StructType       sType;
      void*                     pNext;
      const remixapi_Transform* boneTransforms_values;
      uint32_t                  boneTransforms_count;
  } remixapi_InstanceInfoBoneTransformsEXT;

  typedef struct remixapi_InstanceInfoBlendEXT {
    remixapi_StructType sType;
    void*               pNext;
    remixapi_Bool       alphaTestEnabled;
    uint8_t             alphaTestReferenceValue;
    uint32_t            alphaTestCompareOp;
    remixapi_Bool       alphaBlendEnabled;
    uint32_t            srcColorBlendFactor;
    uint32_t            dstColorBlendFactor;
    uint32_t            colorBlendOp;
    uint32_t            textureColorArg1Source;
    uint32_t            textureColorArg2Source;
    uint32_t            textureColorOperation;
    uint32_t            textureAlphaArg1Source;
    uint32_t            textureAlphaArg2Source;
    uint32_t            textureAlphaOperation;
    uint32_t            tFactor;
    remixapi_Bool       isTextureFactorBlend;
    uint32_t            srcAlphaBlendFactor;
    uint32_t            dstAlphaBlendFactor;
    uint32_t            alphaBlendOp;
    uint32_t            writeMask;
    remixapi_Bool       isVertexColorBakedLighting;
  } remixapi_InstanceInfoBlendEXT;

  typedef struct remixapi_InstanceInfoObjectPickingEXT {
    remixapi_StructType            sType;
    void*                          pNext;
    // A value to write for 'RequestObjectPicking'
    uint32_t                       objectPickingValue;
  } remixapi_InstanceInfoObjectPickingEXT;

  typedef enum remixapi_InstanceCategoryBit {
    REMIXAPI_INSTANCE_CATEGORY_BIT_WORLD_UI                  = 1 << 0,
    REMIXAPI_INSTANCE_CATEGORY_BIT_WORLD_MATTE               = 1 << 1,
    REMIXAPI_INSTANCE_CATEGORY_BIT_SKY                       = 1 << 2,
    REMIXAPI_INSTANCE_CATEGORY_BIT_IGNORE                    = 1 << 3,
    REMIXAPI_INSTANCE_CATEGORY_BIT_IGNORE_LIGHTS             = 1 << 4,
    REMIXAPI_INSTANCE_CATEGORY_BIT_IGNORE_ANTI_CULLING       = 1 << 5,
    REMIXAPI_INSTANCE_CATEGORY_BIT_IGNORE_MOTION_BLUR        = 1 << 6,
    REMIXAPI_INSTANCE_CATEGORY_BIT_IGNORE_OPACITY_MICROMAP   = 1 << 7,
    REMIXAPI_INSTANCE_CATEGORY_BIT_HIDDEN                    = 1 << 8,
    REMIXAPI_INSTANCE_CATEGORY_BIT_PARTICLE                  = 1 << 9,
    REMIXAPI_INSTANCE_CATEGORY_BIT_BEAM                      = 1 << 10,
    REMIXAPI_INSTANCE_CATEGORY_BIT_DECAL_STATIC              = 1 << 11,
    REMIXAPI_INSTANCE_CATEGORY_BIT_DECAL_DYNAMIC             = 1 << 12,
    REMIXAPI_INSTANCE_CATEGORY_BIT_DECAL_SINGLE_OFFSET       = 1 << 13,
    REMIXAPI_INSTANCE_CATEGORY_BIT_DECAL_NO_OFFSET           = 1 << 14,
    REMIXAPI_INSTANCE_CATEGORY_BIT_ALPHA_BLEND_TO_CUTOUT     = 1 << 15,
    REMIXAPI_INSTANCE_CATEGORY_BIT_TERRAIN                   = 1 << 16,
    REMIXAPI_INSTANCE_CATEGORY_BIT_ANIMATED_WATER            = 1 << 17,
    REMIXAPI_INSTANCE_CATEGORY_BIT_THIRD_PERSON_PLAYER_MODEL = 1 << 18,
    REMIXAPI_INSTANCE_CATEGORY_BIT_THIRD_PERSON_PLAYER_BODY  = 1 << 19,
    REMIXAPI_INSTANCE_CATEGORY_BIT_IGNORE_BAKED_LIGHTING     = 1 << 20,
    REMIXAPI_INSTANCE_CATEGORY_BIT_IGNORE_ALPHA_CHANNEL      = 1 << 21,
    // Deprecated. Reserved for API compatibility and ignored by the runtime.
    REMIXAPI_INSTANCE_CATEGORY_BIT_IGNORE_TRANSPARENCY_LAYER = 1 << 22,
    REMIXAPI_INSTANCE_CATEGORY_BIT_PARTICLE_EMITTER          = 1 << 23,
    REMIXAPI_INSTANCE_CATEGORY_BIT_SMOOTH_NORMALS            = 1 << 24,
    REMIXAPI_INSTANCE_CATEGORY_BIT_HAIR_CARDS                = 1 << 25,
    REMIXAPI_INSTANCE_CATEGORY_BIT_VIEW_MODEL                = 1 << 26,
  } remixapi_InstanceCategoryBit;

  typedef uint32_t remixapi_InstanceCategoryFlags;


  typedef struct remixapi_AnimatedFloat1D {
    float*     pData;
    uint32_t   numberElements;
  } remixapi_AnimatedFloat1D;
  
  typedef struct remixapi_AnimatedFloat2D {
    remixapi_Float2D*   pData;
    uint32_t            numberElements;
  } remixapi_AnimatedFloat2D;
  
  typedef struct remixapi_AnimatedFloat3D {
    remixapi_Float3D*   pData;
    uint32_t            numberElements;
  } remixapi_AnimatedFloat3D;

  typedef struct remixapi_AnimatedFloat4D {
    remixapi_Float4D*   pData;
    uint32_t            numberElements;
  } remixapi_AnimatedFloat4D;


  // New particle system struct with animated curve support
  typedef struct remixapi_InstanceInfoParticleSystemEXT {
    remixapi_StructType sType;
    void*               pNext;
    uint32_t            maxNumParticles;
    remixapi_Bool       useTurbulence;
    remixapi_Bool       alignParticlesToVelocity;
    remixapi_Bool       useSpawnTexcoords;
    remixapi_Bool       enableCollisionDetection;
    remixapi_Bool       enableMotionTrail;
    remixapi_Bool       hideEmitter;
    remixapi_Bool       restrictVelocityX;
    remixapi_Bool       restrictVelocityY;
    remixapi_Bool       restrictVelocityZ;
    remixapi_AnimatedFloat4D   minColor;
    remixapi_AnimatedFloat4D   maxColor;
    remixapi_AnimatedFloat1D   minRotationSpeed;
    remixapi_AnimatedFloat1D   maxRotationSpeed;
    remixapi_AnimatedFloat2D   minSize;
    remixapi_AnimatedFloat2D   maxSize;
    remixapi_AnimatedFloat3D   maxVelocity;
    remixapi_Float3D    attractorPosition;
    float               minTimeToLive;
    float               maxTimeToLive;
    float               initialVelocityFromNormal;
    float               initialVelocityConeAngleDegrees;
    float               dragCoefficient;
    float               initialRotationDeviationDegrees;
    float               gravityForce;
    float               turbulenceFrequency;
    float               turbulenceForce;
    float               spawnRatePerSecond;
    float               collisionThickness;
    float               collisionRestitution;
    float               motionTrailMultiplier;
    float               initialVelocityFromMotion;
    float               spawnBurstDuration;
    float               attractorRadius;
    float               attractorForce;
    uint8_t             billboardType;
    uint8_t             spriteSheetMode;
    uint8_t             collisionMode;
    uint8_t             randomFlipAxis;
  } remixapi_InstanceInfoParticleSystemEXT;

  typedef struct remixapi_InstanceInfoGpuInstancingEXT {
    remixapi_StructType       sType;
    void*                     pNext;
    const remixapi_Transform* instanceTransforms_values;
    uint32_t                  instanceTransforms_count;
  } remixapi_InstanceInfoGpuInstancingEXT;

  typedef struct remixapi_InstanceInfo {
    remixapi_StructType            sType;
    void*                          pNext;
    remixapi_InstanceCategoryFlags categoryFlags;
    remixapi_MeshHandle            mesh;
    remixapi_Transform             transform;
    remixapi_Bool                  doubleSided;
  } remixapi_InstanceInfo;

  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_DrawInstance)(
    const remixapi_InstanceInfo* info);


  // Add a retained instance to the scene. The instance will be rendered until explicitly removed by 'SceneInstanceRemove'.
  // This avoids the overhead of calling 'DrawInstance' every frame for many objects.
  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_SceneInstanceAdd)(
    const remixapi_InstanceInfo* info,
    remixapi_SceneInstanceHandle* out_handle);

  // Update the info for a previously added retained instance (by 'SceneInstanceAdd').
  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_SceneInstanceUpdate)(
    remixapi_SceneInstanceHandle handle,
    const remixapi_InstanceInfo* info);

  // Remove a retained instance from the scene.
  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_SceneInstanceRemove)(
    remixapi_SceneInstanceHandle handle);

  typedef struct remixapi_LightInfoLightShaping {
    // The direction the Light Shaping is pointing in. Must be normalized.
    remixapi_Float3D               direction;
    float                          coneAngleDegrees;
    float                          coneSoftness;
    float                          focusExponent;
  } remixapi_LightInfoLightShaping;

  typedef struct remixapi_LightInfoSphereEXT {
    remixapi_StructType            sType;
    void*                          pNext;
    remixapi_Float3D               position;
    float                          radius;
    remixapi_Bool                  shaping_hasvalue;
    remixapi_LightInfoLightShaping shaping_value;
    float                          volumetricRadianceScale;
  } remixapi_LightInfoSphereEXT;

  typedef struct remixapi_LightInfoRectEXT {
    remixapi_StructType            sType;
    void*                          pNext;
    remixapi_Float3D               position;
    // The X axis of the Rect Light. Must be normalized and orthogonal to the Y and direction axes.
    remixapi_Float3D               xAxis;
    float                          xSize;
    // The Y axis of the Rect Light. Must be normalized and orthogonal to the X and direction axes.
    remixapi_Float3D               yAxis;
    float                          ySize;
    // The direction the Rect Light is pointing in, should match the Shaping direction if present.
    // Must be normalized and orthogonal to the X and Y axes.
    remixapi_Float3D               direction;
    remixapi_Bool                  shaping_hasvalue;
    remixapi_LightInfoLightShaping shaping_value;
    float                          volumetricRadianceScale;
  } remixapi_LightInfoRectEXT;

  typedef struct remixapi_LightInfoDiskEXT {
    remixapi_StructType            sType;
    void*                          pNext;
    remixapi_Float3D               position;
    // The X axis of the Disk Light. Must be normalized and orthogonal to the Y and direction axes.
    remixapi_Float3D               xAxis;
    float                          xRadius;
    // The Y axis of the Disk Light. Must be normalized and orthogonal to the X and direction axes.
    remixapi_Float3D               yAxis;
    float                          yRadius;
    // The direction the Disk Light is pointing in, should match the Shaping direction if present
    // Must be normalized and orthogonal to the X and Y axes.
    remixapi_Float3D               direction;
    remixapi_Bool                  shaping_hasvalue;
    remixapi_LightInfoLightShaping shaping_value;
    float                          volumetricRadianceScale;
  } remixapi_LightInfoDiskEXT;

  typedef struct remixapi_LightInfoCylinderEXT {
    remixapi_StructType            sType;
    void*                          pNext;
    remixapi_Float3D               position;
    float                          radius;
    // The "center" axis of the Cylinder Light. Must be normalized.
    remixapi_Float3D               axis;
    float                          axisLength;
    float                          volumetricRadianceScale;
  } remixapi_LightInfoCylinderEXT;

  typedef struct remixapi_LightInfoDistantEXT {
    remixapi_StructType             sType;
    void*                           pNext;
    // The direction the Distant Light is pointing in. Must be normalized.
    remixapi_Float3D                direction;
    float                           angularDiameterDegrees;
    float                           volumetricRadianceScale;
  } remixapi_LightInfoDistantEXT;

  typedef struct remixapi_LightInfoDomeEXT {
    remixapi_StructType             sType;
    void*                           pNext;
    remixapi_Transform              transform;
    remixapi_TextureHandle          colorTexture;
    float                           opacity;
    int                             blendType;
  } remixapi_LightInfoDomeEXT;

  // Attachable to remixapi_LightInfo.
  // If attached, 'remixapi_LightInfo::radiance' is ignored.
  // Any other attached 'remixapi_LightInfo*EXT' are ignored.
  // Most fields correspond to a usd token. Set to null, if no value.
  typedef struct remixapi_LightInfoUSDEXT {
    remixapi_StructType             sType;
    void*                           pNext;
    remixapi_StructType             lightType;
    remixapi_Transform              transform;
    const float*                    pRadius;                  // "radius"
    const float*                    pWidth;                   // "width"
    const float*                    pHeight;                  // "height"
    const float*                    pLength;                  // "length"
    const float*                    pAngleRadians;            // "angle"
    const remixapi_Bool*            pEnableColorTemp;         // "enableColorTemperature"
    const remixapi_Float3D*         pColor;                   // "color"
    const float*                    pColorTemp;               // "colorTemperature"
    const float*                    pExposure;                // "exposure"
    const float*                    pIntensity;               // "intensity"
    const float*                    pConeAngleRadians;        // "shaping:cone:angle"
    const float*                    pConeSoftness;            // "shaping:cone:softness"
    const float*                    pFocus;                   // "shaping:focus"
    const float*                    pVolumetricRadianceScale; // "volumetric_radiance_scale"
  } remixapi_LightInfoUSDEXT;

  typedef struct remixapi_LightInfo {
    remixapi_StructType             sType;
    void*                           pNext;
    uint64_t                        hash;
    remixapi_Float3D                radiance;
  } remixapi_LightInfo;

  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_CreateLight)(
    const remixapi_LightInfo* info,
    remixapi_LightHandle*     out_handle);

  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_DestroyLight)(
    remixapi_LightHandle      handle);

  // In-place update. The light retains its handle and temporal identity. Dome and
  // analytical lights cannot be converted into each other. Input data is copied before return.
  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_UpdateLight)(
    remixapi_LightHandle handle, const remixapi_LightInfo* info);
  REMIXAPI remixapi_ErrorCode REMIXAPI_CALL remixapi_UpdateLight(
    remixapi_LightHandle handle, const remixapi_LightInfo* info);


  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_DrawLightInstance)(
    remixapi_LightHandle      lightHandle);


  // Writes an RtxOption value to the User Settings layer.
  // An option the runtime never saves, such as rtx.showUI, is written to the Derived Settings layer instead,
  // where the runtime writes its own changes to it.
  // A later change from the runtime's menus then takes effect, and GetConfigVariableResolved reports it.
  // The key and value are validated immediately, and the value takes effect at the next option update.
  // A call made before Startup takes effect at the first option update after it.
  // The call can wait briefly while the runtime resolves options or updates its menus.
  // Returns INVALID_ARGUMENTS for an invalid key or value.
  // Returns GENERAL_FAILURE for an unknown key.
  // See documentation/RemixConfig.md.
  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_SetConfigVariable)(
    const char*               key,
    const char*               value);

  // Reads an RtxOption's resolved value: the value the renderer uses after all option layers are
  // combined. It accepts the keys SetConfigVariable accepts. A key in a registered dynamic option
  // namespace that no layer sets reads as the namespace default.
  //
  // The value is text in the form .conf files use, so it can be passed back to SetConfigVariable.
  // Booleans read as True or False, enums as their integer value, vectors as comma-separated components,
  // and floats with 6 significant digits, so writing back a float or vector with more precision rounds it.
  //
  // Most changes, including those made by SetConfigVariable and config layers, resolve at the runtime's option update,
  // which runs on the render thread at the end of each frame.
  // A few of the runtime's own changes, such as starting a scene capture from its menu, resolve immediately.
  // The render thread can trail the application by a few frames,
  // so a read returns the value the render thread uses now, not one tied to the application's current frame.
  //
  // A read right after SetConfigVariable may still return the previous value.
  // It can also return the new value, because an option update can run between the two calls,
  // so a read does not show where a frame ends.
  // To change a value based on its current one, track your own writes instead of reading the value back.
  // Reading several keys does not give a consistent snapshot, because an option update can happen between calls.
  //
  // On input, valueSize is the capacity of value in bytes. On output, it is the size of the value
  // including its NUL terminator. Pass a NULL value to query the size. If the value does not fit,
  // nothing is written and the call returns REMIXAPI_ERROR_CODE_BUFFER_TOO_SMALL.
  // A value can change between calls, so retry until the call succeeds.
  //
  // Returns INVALID_ARGUMENTS for a NULL info, a wrong sType, a NULL or empty key,
  // or a malformed key in a dynamic namespace.
  // Returns GENERAL_FAILURE for a key that no option or namespace owns.
  // Returns NOT_INITIALIZED before the runtime has loaded its configuration, which happens during Startup.
  // May be called from any application thread. See documentation/RemixConfig.md.
  typedef struct remixapi_ConfigVariableResolvedInfo {
    remixapi_StructType       sType;
    // Reserved for extension structs that request more outputs. None exist yet, and the runtime ignores the chain.
    void*                     pNext;
    const char*               key;
    char*                     value;
    uint32_t                  valueSize;
  } remixapi_ConfigVariableResolvedInfo;
  REMIXAPI_HELPER remixapi_ConfigVariableResolvedInfo remixapi_ConfigVariableResolvedInfo_init() {
    remixapi_ConfigVariableResolvedInfo info = { (remixapi_StructType)0 };
    info.sType = REMIXAPI_STRUCT_TYPE_CONFIG_VARIABLE_RESOLVED_INFO;
    info.pNext = NULL;
    info.key = NULL;
    info.value = NULL;
    info.valueSize = 0;
    return info;
  }

  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_GetConfigVariableResolved)(
    remixapi_ConfigVariableResolvedInfo* inout_info);


  // Config-layer functions require a successful Startup call. With otherwise
  // valid arguments, calls made before Startup return
  // REMIXAPI_ERROR_CODE_REMIX_DEVICE_WAS_NOT_REGISTERED. They may be called
  // from any application thread; AcquireConfigLayer waits for its CS operation,
  // while blend and release requests are enqueued in call order.
  //
  // Acquires (or references, if already acquired at the same priority and
  // path) a conf-file option layer. Absolute paths are recommended; relative
  // paths resolve against the process working directory. When no matching
  // layer is cached, the conf is parsed synchronously: missing or unreadable
  // files fail, while files containing no options acquire as no-op layers.
  // Repeated acquires return the cached handle without reading the file and
  // must be balanced by an equal number of releases.
  // 'priority' and 'blendThreshold' must be finite. Priority orders layers and
  // is clamped to the dynamic range; blendThreshold is clamped to [0,1] and is
  // the initial threshold for non-float options. A newly created layer starts
  // disabled at strength 0. See documentation/RemixConfig.md for ordering and blending details.
  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_AcquireConfigLayer)(
    const char*                 path,
    float                       priority,
    float                       blendThreshold,
    remixapi_ConfigLayerHandle* out_handle);

  // Requests the layer's blend strength and non-float blend threshold. Both
  // values must be finite and are clamped to [0,1]. Float/vector options LERP
  // by strength; other option types switch when strength reaches threshold.
  // Requests resolve with option updates and persist until changed. If this is
  // called multiple times for one layer between resolves, the maximum strength
  // and minimum threshold are used.
  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_SetConfigLayerBlending)(
    remixapi_ConfigLayerHandle  handle,
    float                       strength,
    float                       blendThreshold);

  // Releases one reference. At zero references the layer is removed and
  // affected options re-resolve without it during the next option resolution.
  // The handle must not be used after the final release.
  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_ReleaseConfigLayer)(
    remixapi_ConfigLayerHandle  handle);



  typedef enum remixapi_SubmitInteropFlagBit {
    REMIXAPI_SUBMIT_INTEROP_BIT_FORCE_WAIT_DEVICE_IDLE          = 1 << 0,
    REMIXAPI_SUBMIT_INTEROP_BIT_DISABLE_CPU_WAIT_FOR_VK_SUBMIT  = 1 << 1,
    REMIXAPI_SUBMIT_INTEROP_BIT_DISABLE_QUEUE_SUBMIT            = 1 << 2,
  } remixapi_SubmitInteropFlagBit;
  typedef uint32_t remixapi_SubmitInteropFlags;

  typedef struct remixapi_SubmitInfo {
    remixapi_StructType           sType;
    void*                         pNext;
    remixapi_SubmitInteropFlags   interopFlags;
  } remixapi_SubmitInfo;
  REMIXAPI_HELPER remixapi_SubmitInfo remixapi_SubmitInfo_init() {
    remixapi_SubmitInfo info = { (remixapi_StructType)0 };
    info.sType = REMIXAPI_STRUCT_TYPE_SUBMIT_INFO;
    info.pNext = NULL;
    info.interopFlags = 0;
    return info;
  }

  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_Submit)(const remixapi_SubmitInfo* info);

  typedef struct remixapi_PresentInfo {
    remixapi_StructType       sType;
    void*                     pNext;
    remixapi_HWND             hwndOverride; // Can be NULL
    remixapi_Bool             skipVulkanSwapchain;
    remixapi_Bool             interopFlipY; // flip interop image vertically when copying into the swapchain (e.g. user app API is OpenGL)
    uint64_t                  clientDoneSemaphore_ValueToWait; // for interop: client signals this semaphore value, Remix waits for it before present, see 'remixapi_dxvk_InteropInfo' for reference
    remixapi_Bool             disableCpuWaitForExternalSemaphores;
  } remixapi_PresentInfo;
  REMIXAPI_HELPER remixapi_PresentInfo remixapi_PresentInfo_init() {
    remixapi_PresentInfo info = { (remixapi_StructType)0 };
    info.sType = REMIXAPI_STRUCT_TYPE_PRESENT_INFO;
    info.pNext = NULL;
    info.hwndOverride = NULL;
    info.skipVulkanSwapchain = 0;
    info.interopFlipY = 0;
    info.clientDoneSemaphore_ValueToWait = 0;
    return info;
  }

  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_Present)(const remixapi_PresentInfo* info);

  typedef void (REMIXAPI_PTR* PFN_remixapi_pick_RequestObjectPickingUserCallback)(
    const uint32_t*           objectPickingValues_values,
    uint32_t                  objectPickingValues_count,
    void*                     callbackUserData);

  // Invokes 'callback' on a successful readback of 'remixapi_InstanceInfoObjectPickingEXT::objectPickingValue'
  // of objects that are drawn in the 'pixelRegion'. 'pixelRegion' specified relative to the output size,
  // not render size. 'callback' can be invoked from any thread.
  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_pick_RequestObjectPicking)(
    const remixapi_Rect2D*                              pixelRegion,
    PFN_remixapi_pick_RequestObjectPickingUserCallback  callback,
    void*                                               callbackUserData);

  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_pick_HighlightObjects)(
    const uint32_t*           objectPickingValues_values,
    uint32_t                  objectPickingValues_count,
    uint8_t                   colorR,
    uint8_t                   colorG,
    uint8_t                   colorB);



  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_dxvk_CreateD3D9)(
    remixapi_Bool       editorModeEnabled,
    IDirect3D9Ex**      out_pD3D9);

  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_dxvk_RegisterD3D9Device)(
    IDirect3DDevice9Ex* d3d9Device);

  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_dxvk_GetExternalSwapchain)(
    uint64_t*           out_vkImage,
    uint64_t*           out_vkSemaphoreRenderingDone,
    uint64_t*           out_vkSemaphoreResumeSemaphore);

  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_dxvk_GetVkImage)(
    IDirect3DSurface9*  source,
    uint64_t*           out_vkImage);

  typedef enum remixapi_dxvk_CopyRenderingOutputType {
    REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_FINAL_COLOR = 0,
    REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_DEPTH = 1,
    REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_NORMALS = 2,
    REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_OBJECT_PICKING = 3,
    REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_GUI = 4,
    REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_WORLD_POSITION_RAW = 5,
    REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_TEXCOORDS_RAW = 6,
    REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_NORMAL_F32 = 7,
    REMIXAPI_DXVK_COPY_RENDERING_OUTPUT_TYPE_COVERAGE = 8,
  } remixapi_dxvk_CopyRenderingOutputType;

  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_dxvk_CopyRenderingOutput)(
    IDirect3DSurface9*                    destination,
    remixapi_dxvk_CopyRenderingOutputType type);

  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_dxvk_SetDefaultOutput)(
    remixapi_dxvk_CopyRenderingOutputType type,
    const remixapi_Float4D* color);


  // With 'remixapi_StartupInfo::interopImage' enabled, the interop render flow with OpenGL is:
  // 1. User app (client) calls remix.Startup(), and remix.dxvk_GetInteropInfo() immediately after to create semaphores:
  //     * glImportSemaphoreWin32HandleEXT( dxvkDoneSemaphore_Handle )
  //     * glImportSemaphoreWin32HandleEXT( clientDoneSemaphore_Handle )
  // 2. User app calls remix.Submit()
  //     * Remix allocates the interop image of a window size
  //     * Remix renders ray-traced output into the interop image
  //     * Remix signals 'dxvkDoneSemaphore_Handle' semaphore
  // 3. User app calls remix.dxvk_GetInteropInfo(), Remix returns the interop image info
  //     * glImportMemoryWin32HandleEXT( mymem, allocationSize, GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, imageMemory_Handle )
  //     * Import depthImageMemory_Handle into a separate GL_R32F texture when nonzero
  //     * Translate 'interopImageFormat' to 'glFormat'
  //     * glTextureStorageMem2DEXT( mytex, 1, glFormat, width, height, mymem, 0 )
  //     * Recreate the image when imageMemory_Handle/width/height change
  // 4. User app calls glWaitSemaphoreEXT( dxvkDoneSemaphore_gl, .., colorAndDepthTextures )
  //     * This requests GPU to wait for Remix to complete rendering. After that, user app can work with the image.
  //     * User app needs to call glSemaphoreParameterui64vEXT with 'dxvkDoneSemaphore_ValueToWait', if timeline semaphore,
  //       so that GL will wait for that value
  // 5. User app does the OpenGL work (e.g. post-effects, UI)
  // 6. User app calls glSignalSemaphoreEXT( clientDoneSemaphore_gl, .., mytex )
  //     * This signals Remix that the user app is done with the image, and Remix can copy the image into the swapchain.
  //     * User app needs to call glSemaphoreParameterui64vEXT with a value that is then passed as 'remixapi_PresentInfo::clientDoneSemaphore_ValueToWait'
  // 7. User app calls remix.Present()
  //     * Remix presents into the swapchain
  //     * use 'remixapi_PresentInfo::interopFlipY' for OpenGL, if presented image is flipped vertically
  //     * NOTE: user app MUST remove client API swapchain calls (e.g. SwapBuffers),
  //             as Remix itself is responsible for presenting into the swapchain.
  // For the sample app, see 'remixapi_opengl.cpp'
  typedef struct remixapi_dxvk_InteropInfo {
    uint64_t  dxvkDoneSemaphore_Handle;   // Win32 HANDLE - DXVK's Vulkan signals this semaphore after writing
    uint64_t  dxvkDoneSemaphore_ValueToWait; // timeline value signaled by Remix after rendering; wait for this value
    uint64_t  clientDoneSemaphore_Handle; // Win32 HANDLE - client signals this semaphore after writing
    uint64_t  imageMemory_Handle;         // Win32 HANDLE - image memory, import into the client graphics API
    uint64_t  allocationSize;             // interop image memory byte size
    int32_t   width;                      // interop image width
    int32_t   height;                     // interop image height
    remixapi_TextureFormat interopImageFormat; // format of the interop image, translate to user app API

    uint64_t  depthImageMemory_Handle;    // Win32 HANDLE for read-only primary depth, or 0 if unavailable
    uint64_t  depthAllocationSize;        // depth image memory byte size
    int32_t   depthWidth;                 // primary depth width (may differ from color)
    int32_t   depthHeight;                // primary depth height (may differ from color)
    remixapi_TextureFormat interopDepthImageFormat; // single-channel projected depth format
  } remixapi_dxvk_InteropInfo;

  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_dxvk_GetInteropInfo)(remixapi_dxvk_InteropInfo* out_info);

  // Exact Vulkan image/allocation parameters for OPAQUE_WIN32 imports on the same physical device and driver.
  // Handles are borrowed until the next Submit or Shutdown. Never close them in the client.
  // Imports must use the original allocationSize and memoryTypeIndex, with a dedicated image allocation.
  typedef struct remixapi_VulkanInteropImage {
    uint64_t memoryHandle;
    uint64_t allocationSize;
    uint64_t bindOffset;
    uint64_t generation;
    uint32_t memoryTypeIndex;
    uint32_t allocationFlags;
    uint32_t allocationDeviceMask;
    uint32_t flags;
    uint32_t imageType;
    uint32_t format;
    uint32_t width;
    uint32_t height;
    uint32_t depth;
    uint32_t mipLevels;
    uint32_t arrayLayers;
    uint32_t samples;
    uint32_t tiling;
    uint32_t usage;
    uint32_t sharingMode;
    uint32_t initialLayout;
    uint32_t externalLayout;
    uint32_t ownerQueueFamily;
    uint32_t externalMemoryHandleType;
    remixapi_Bool dedicatedAllocation;
    uint32_t viewFormatCount;
    uint32_t viewFormats[16];
    // Read-only borrowed VkImage, usable only when VulkanInteropInfo.device is the caller's VkDevice.
    // It may alias the producer's color/depth output. Both frame timelines protect its reuse.
    uint64_t image;
  } remixapi_VulkanInteropImage;

  // Minecraft owns presentation. Submit signals the done timeline value after releasing both images to
  // VK_QUEUE_FAMILY_EXTERNAL in GENERAL. The client waits, acquires EXTERNAL -> its queue family with
  // GENERAL -> GENERAL, uses the images, releases its queue family -> EXTERNAL with GENERAL -> GENERAL,
  // and signals the client timeline. Present(skipVulkanSwapchain=true) queues the matching runtime acquire
  // after waiting for clientDoneSemaphore_ValueToWait. Both operations must balance before another Submit.
  // Identical deviceUUID and driverUUID are required. Changing an image generation requires a fresh import.
  // Output extension for the exact frame paired with dxvkDoneSemaphore_ValueToWait.
  typedef struct remixapi_InteropFrameStatusEXT {
    remixapi_StructType sType;
    void* pNext;
    uint64_t frameId;
    // Bit 0: ray traced. Bit 1: camera valid. Bit 2: compiling shaders.
    uint32_t flags;
    uint32_t shaderCompileCount;
  } remixapi_InteropFrameStatusEXT;

  // Output extension of VulkanInteropInfo. Limits come from NGX, independently of user settings.
  // maxGeneratedFrames excludes the rendered frame: N generated frames produce an (N + 1)x mode.
  typedef struct remixapi_FrameGenerationCapabilitiesEXT {
    remixapi_StructType sType;
    void* pNext;
    uint32_t initializationComplete;
    uint32_t supported;
    uint32_t maxGeneratedFrames;
    // UTF-8, nul terminated. Empty when supported; distinguishes initializing from unsupported.
    uint8_t notSupportedReason[256];
  } remixapi_FrameGenerationCapabilitiesEXT;

  // PresentInfo extension for a host-owned image on the runtime's VkDevice and graphics family.
  // The source image must remain in GENERAL and alive until sourceConsumedValue is signaled.
  // Subsequent host writes must wait for that value. The host owns sourceReadySemaphore;
  // the runtime owns sourceConsumedSemaphore. Both are timeline semaphores.
  // image == 0 releases borrowed views synchronously without advancing a frame. Use it
  // before destroying a previously presented image. Other image fields are then ignored.
  typedef struct remixapi_VulkanClientFrameInfoEXT {
    remixapi_StructType sType;
    void* pNext;
    uint64_t device;
    uint64_t image;
    uint64_t imageGeneration;
    uint32_t width;
    uint32_t height;
    uint32_t format;
    uint32_t bottomUp;
    uint32_t vsync;
    uint32_t allowFrameGeneration;
    uint64_t sourceReadySemaphore;
    uint64_t sourceReadyValue;
    uint64_t sourceConsumedSemaphore;
    uint64_t sourceConsumedValue;
    // Presentation counters are asynchronous and restart when presentationEpoch changes.
    uint64_t presentationEpoch;
    uint64_t renderedFrames;
    uint64_t generatedFrames;
  } remixapi_VulkanClientFrameInfoEXT;

  typedef struct remixapi_VulkanInteropInfo {
    remixapi_StructType sType;
    void* pNext;
    uint8_t deviceUUID[16];
    uint8_t driverUUID[16];
    uint8_t deviceLUID[8];
    uint32_t deviceNodeMask;
    remixapi_Bool deviceLUIDValid;
    uint32_t queueFamilyIndex;
    uint32_t semaphoreType;
    uint64_t dxvkDoneSemaphore_Handle;
    uint64_t dxvkDoneSemaphore_ValueToWait;
    uint64_t clientDoneSemaphore_Handle;
    remixapi_VulkanInteropImage color;
    remixapi_VulkanInteropImage depth;
    // Premultiplied transparent native UI, composite after the client's screens and HUD.
    remixapi_VulkanInteropImage uiOverlay;
    // Exact logical device identity. Borrowed images and semaphores are not destroyed by the host.
    uint64_t device;
  } remixapi_VulkanInteropInfo;

  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_dxvk_GetVulkanInteropInfo)(remixapi_VulkanInteropInfo* out_info);

  // Creates one logical device with the union of the host and Remix requirements.
  // instance must enable Vulkan 1.4; deviceCreateInfo points to a VkDeviceCreateInfo
  // whose entire pointer graph remains valid until the call returns. Allocation
  // callbacks and protected/custom queue creation are not supported. AdoptVulkanDevice
  // transfers destruction to the host after its dispatch table was initialized.
  // The host must call Shutdown before vkDestroyDevice or instance
  // destruction, including when host initialization fails before Startup.
  // The enabledExtensions output is borrowed until Shutdown and must be used to
  // initialize the host's Vulkan dispatch table. Startup reuses this device.
  typedef struct remixapi_HostVulkanDeviceInfo {
    remixapi_StructType sType;
    void* pNext;
    uint64_t instance;
    uint64_t physicalDevice;
    uint32_t instanceApiVersion;
    uint32_t instanceExtensionCount;
    const char* const* instanceExtensions;
    uint32_t graphicsQueueFamily;
    const void* deviceCreateInfo;
    uint64_t device;
    uint32_t enabledExtensionCount;
    const char* const* enabledExtensions;
  } remixapi_HostVulkanDeviceInfo;

  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_dxvk_CreateVulkanDevice)(remixapi_HostVulkanDeviceInfo* info);
  // Loads rendering configuration and capture tooling before the host creates its instance.
  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_dxvk_PrepareVulkan)(void);
  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_dxvk_AdoptVulkanDevice)(uint64_t device);


  typedef struct remixapi_InitializeLibraryInfo {
    remixapi_StructType sType;
    void*               pNext;
    uint64_t            version;
  } remixapi_InitializeLibraryInfo;

  // Extends an opaque MaterialInfo. Red channel 0 neutralizes vertex RGB; 1 preserves
  // it. Intermediate values blend linearly. Vertex alpha and opacity are unchanged.
  // Uses the albedo UVs and sampler; an R8_UNORM texture avoids redundant channels.
  typedef struct remixapi_MaterialInfoVertexColorMaskEXT {
    remixapi_StructType sType;
    void* pNext;
    remixapi_TextureHandle texture;
  } remixapi_MaterialInfoVertexColorMaskEXT;

  // Clip a retained instance against a world-space voxel coverage grid. Zero disables
  // clipping. Nonzero levels discard hits owned by a lower nonzero grid level.
  // Mesh triangle winding must point outward; cell ownership samples just inside it.
  typedef struct remixapi_InstanceInfoVoxelCoverageEXT {
    remixapi_StructType sType;
    void* pNext;
    uint32_t level;
    uint32_t nodeIndex; // Optional sparse-tree node; zero disables tree clipping.
  } remixapi_InstanceInfoVoxelCoverageEXT;

  // R8 levels in x-major order: x + sizeX * (y + sizeY * z). Origins are signed
  // CELL coordinates, not world coordinates. cellSize is a positive power of two.
  // Tree nodes use world-block coordinates. Index zero is reserved. A published
  // or authoritatively empty child owns its whole cell. Missing children keep
  // the parent; flags bits 8..15 mark known-empty child octants (x | y<<1 | z<<2).
  typedef struct remixapi_VoxelCoverageNode {
    int32_t originX;
    int32_t originY;
    int32_t originZ;
    uint32_t sizeBlocks;
    uint32_t flags; // bit 0: mesh published, bit 1: empty, bits 8..15: empty children.
    uint32_t children[8];
    uint32_t reserved[3];
  } remixapi_VoxelCoverageNode;

  // Zero dimensions disable the dense grid. flags bit 0 clips outside it.
  // Sparse children must be half their parent's size and occupy their octant.
  // levels is copied before the function returns; it need not outlive this call.
  typedef struct remixapi_VoxelCoverageInfo {
    remixapi_StructType sType;
    void* pNext;
    int32_t originX;
    int32_t originY;
    int32_t originZ;
    uint32_t sizeX;
    uint32_t sizeY;
    uint32_t sizeZ;
    uint32_t cellSize;
    uint64_t dataSize;
    const uint8_t* levels;
    uint32_t flags;
    const remixapi_VoxelCoverageNode* treeNodes;
    uint32_t treeNodeCount;
  } remixapi_VoxelCoverageInfo;
  typedef remixapi_ErrorCode(REMIXAPI_PTR* PFN_remixapi_SetVoxelCoverage)(const remixapi_VoxelCoverageInfo* info);

  // NOTE: If adding a new function, append at the end of the struct.
  //       Reordering breaks backwards compatibility.
  typedef struct remixapi_Interface {
    PFN_remixapi_Shutdown           Shutdown;
    PFN_remixapi_CreateMaterial     CreateMaterial;
    PFN_remixapi_DestroyMaterial    DestroyMaterial;
    PFN_remixapi_CreateMesh         CreateMesh;
    PFN_remixapi_DestroyMesh        DestroyMesh;
    PFN_remixapi_SetupCamera        SetupCamera;
    PFN_remixapi_DrawInstance       DrawInstance;
    PFN_remixapi_CreateLight        CreateLight;
    PFN_remixapi_DestroyLight       DestroyLight;
    PFN_remixapi_DrawLightInstance  DrawLightInstance;
    PFN_remixapi_SetConfigVariable  SetConfigVariable;

    // DXVK interoperability
    PFN_remixapi_dxvk_CreateD3D9            dxvk_CreateD3D9;
    PFN_remixapi_dxvk_RegisterD3D9Device    dxvk_RegisterD3D9Device;
    PFN_remixapi_dxvk_GetExternalSwapchain  dxvk_GetExternalSwapchain;
    PFN_remixapi_dxvk_GetVkImage            dxvk_GetVkImage;
    PFN_remixapi_dxvk_CopyRenderingOutput   dxvk_CopyRenderingOutput;
    PFN_remixapi_dxvk_SetDefaultOutput      dxvk_SetDefaultOutput;
    // Object picking utils
    PFN_remixapi_pick_RequestObjectPicking  pick_RequestObjectPicking;
    PFN_remixapi_pick_HighlightObjects      pick_HighlightObjects;

    PFN_remixapi_Startup                    Startup;
    PFN_remixapi_Present                    Present;

    PFN_remixapi_CreateTextureFromMemory    CreateTextureFromMemory;
    PFN_remixapi_CreateTextureFromFile      CreateTextureFromFile;
    PFN_remixapi_DestroyTexture             DestroyTexture;

    PFN_remixapi_SetCameraMediumMaterial SetCameraMediumMaterial;
    PFN_remixapi_UpdateTextureFromMemory UpdateTextureFromMemory;

    PFN_remixapi_dxvk_GetInteropInfo        dxvk_GetInteropInfo;
    PFN_remixapi_Submit                     Submit;

    PFN_remixapi_AcquireConfigLayer     AcquireConfigLayer;
    PFN_remixapi_SetConfigLayerBlending SetConfigLayerBlending;
    PFN_remixapi_ReleaseConfigLayer     ReleaseConfigLayer;

    PFN_remixapi_SceneInstanceAdd           SceneInstanceAdd;
    PFN_remixapi_SceneInstanceUpdate        SceneInstanceUpdate;
    PFN_remixapi_SceneInstanceRemove        SceneInstanceRemove;
    PFN_remixapi_UpdateMaterial             UpdateMaterial;

    PFN_remixapi_GetConfigVariableResolved  GetConfigVariableResolved;
    PFN_remixapi_dxvk_GetVulkanInteropInfo  dxvk_GetVulkanInteropInfo;
    PFN_remixapi_CreateSharedBuffer CreateSharedBuffer;
    PFN_remixapi_CommitSharedBuffers CommitSharedBuffers;
    PFN_remixapi_DestroySharedBuffer DestroySharedBuffer;
    PFN_remixapi_SetVoxelCoverage SetVoxelCoverage;
    PFN_remixapi_dxvk_CreateVulkanDevice dxvk_CreateVulkanDevice;
    PFN_remixapi_dxvk_PrepareVulkan dxvk_PrepareVulkan;
    PFN_remixapi_dxvk_AdoptVulkanDevice dxvk_AdoptVulkanDevice;
  } remixapi_Interface;

  REMIXAPI remixapi_ErrorCode REMIXAPI_CALL remixapi_InitializeLibrary(
    const remixapi_InitializeLibraryInfo* info,
    remixapi_Interface*                   out_result);

  typedef remixapi_ErrorCode(REMIXAPI_CALL* PFN_remixapi_InitializeLibrary)(
    const remixapi_InitializeLibraryInfo* info,
    remixapi_Interface*                   out_result);

#ifndef REMIX_WINAPI_NO_LIBRARY_LOADER
  REMIXAPI_INLINE remixapi_ErrorCode REMIXAPI_CALL remixapi_lib_loadRemixDllAndInitialize(
    const wchar_t*      remixD3D9DllPath,
    remixapi_Interface* out_remixInterface,
    remixapi_HMODULE*   out_remixDll
  ) {
    if (remixD3D9DllPath == NULL || remixD3D9DllPath[0] == '\0') {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }
    if (out_remixInterface == NULL || out_remixDll == NULL) {
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }

    remixapi_HMODULE remixDll = NULL;
    PFN_remixapi_InitializeLibrary pfn_InitializeLibrary = NULL;
    {
      // firstly, try the default method first, e.g. DLL is already loaded, 
      // DLL-s are around .exe, or an app has called SetDllDirectory
      {
        remixapi_HMODULE dll = LoadLibraryW(remixD3D9DllPath);
        if (dll) {
          remixapi_loader_PROC func = GetProcAddress(dll, "remixapi_InitializeLibrary");
          if (func) {
            remixDll = dll;
            pfn_InitializeLibrary = (PFN_remixapi_InitializeLibrary) func;
          } else {
            FreeLibrary(dll);
          }
        }
      }

      // then try raw user-provided 'remixD3D9DllPath' file
      if (!pfn_InitializeLibrary) {
        // set LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR to search 
        // dependency DLL-s in the folder of 'remixD3D9DllPath'
        remixapi_HMODULE dll = LoadLibraryExW(remixD3D9DllPath, NULL,
                                     REMIX_WINAPI_LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR |
                                     REMIX_WINAPI_LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);
        if (dll) {
          remixapi_loader_PROC func = GetProcAddress(dll, "remixapi_InitializeLibrary");
          if (func) {
            remixDll = dll;
            pfn_InitializeLibrary = (PFN_remixapi_InitializeLibrary) func;
          } else {
            FreeLibrary(dll);
          }
        }
      }

      // at last, try to SetDllDirectory manually
      if (!pfn_InitializeLibrary) {
        wchar_t absoluteD3D9DllPath[REMIX_WINAPI_MAX_PATH];
        {
          unsigned ret = (unsigned)GetFullPathNameW(remixD3D9DllPath, REMIX_WINAPI_MAX_PATH, absoluteD3D9DllPath, NULL);
          if (ret == 0) {
            return REMIXAPI_ERROR_CODE_GET_FULL_PATH_NAME_FAILURE;
          }
        }
        wchar_t parentDir[REMIX_WINAPI_MAX_PATH];
        {
          int len = 0;
          for (int i = 0; i < REMIX_WINAPI_MAX_PATH; i++) {
            if (absoluteD3D9DllPath[i] == '\0') {
              break;
            }
            parentDir[i] = absoluteD3D9DllPath[i] == '/' ? '\\' : absoluteD3D9DllPath[i];
            ++len;
          }
          if (len <= 0 || len >= REMIX_WINAPI_MAX_PATH) {
            return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
          }
          parentDir[len] = '\0';
          for (int i = len - 1; i >= 0; --i) {
            if (parentDir[i] == '\\') {
              // remove one or more path separators
              for (int k = i; k >= 0; --k) {
                if (parentDir[k] == '\\') {
                  parentDir[k] = '\0';
                  continue;
                }
                break;
              }
              break;
            }
          }
          if (parentDir[0] == '\0') {
            return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
          }
        }

        // save the previous value that is in SetDllDirectory
        wchar_t dirToRestore[REMIX_WINAPI_MAX_PATH];
        {
          unsigned len = (unsigned)GetDllDirectoryW(REMIX_WINAPI_MAX_PATH, dirToRestore);
          if (len > 0 && len < REMIX_WINAPI_MAX_PATH - 1) {
            dirToRestore[REMIX_WINAPI_MAX_PATH - 1] = '\0';
          }
        }

        {
          int s = SetDllDirectoryW(parentDir);
          if (!s) {
            return REMIXAPI_ERROR_CODE_SET_DLL_DIRECTORY_FAILURE;
          }
        }

        remixapi_HMODULE dll = LoadLibraryW(absoluteD3D9DllPath);
        if (dll) {
          remixapi_loader_PROC func = GetProcAddress(dll, "remixapi_InitializeLibrary");
          if (func) {
            remixDll = dll;
            pfn_InitializeLibrary = (PFN_remixapi_InitializeLibrary) func;
          } else {
            FreeLibrary(dll);
          }
        }

        // restore the previous value
        {
          SetDllDirectoryW(dirToRestore);
        }
      }
    }

    if (!remixDll) {
      return REMIXAPI_ERROR_CODE_LOAD_LIBRARY_FAILURE;
    }
    if (!pfn_InitializeLibrary){
      return REMIXAPI_ERROR_CODE_GET_PROC_ADDRESS_FAILURE;
    }

    remixapi_InitializeLibraryInfo info = { (remixapi_StructType)0 };
    {
      info.sType = REMIXAPI_STRUCT_TYPE_INITIALIZE_LIBRARY_INFO;
      info.version = REMIXAPI_VERSION_MAKE(REMIXAPI_VERSION_MAJOR,
                                           REMIXAPI_VERSION_MINOR,
                                           REMIXAPI_VERSION_PATCH);
    }
    remixapi_Interface remixInterface = { 0 };

    remixapi_ErrorCode status = pfn_InitializeLibrary(&info, &remixInterface);
    if (status != REMIXAPI_ERROR_CODE_SUCCESS) {
      FreeLibrary(remixDll);
      return status;
    }

    *out_remixInterface = remixInterface;
    *out_remixDll = remixDll;
    return status;
  }

  inline remixapi_ErrorCode REMIXAPI_CALL remixapi_lib_shutdownAndUnloadRemixDll(
    remixapi_Interface* remixInterface,
    remixapi_HMODULE    remixDll
  ) {
    if (remixInterface == NULL || remixInterface->Shutdown == NULL) {
      if (remixDll != NULL) {
        FreeLibrary(remixDll);
      }
      return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }

    remixapi_ErrorCode status = remixInterface->Shutdown();
    if (remixDll != NULL) {
      FreeLibrary(remixDll);
    }

    remixapi_Interface nullInterface = { 0 };
    *remixInterface = nullInterface;
    return status;
  }
#endif // !REMIX_WINAPI_NO_LIBRARY_LOADER

#ifdef __cplusplus
}
#endif // __cplusplus

#endif // REMIX_C_H_
