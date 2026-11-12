/*
 * Copyright (c) 2023, NVIDIA CORPORATION. All rights reserved.
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

namespace {
  typedef struct remixapi_InstanceInfoParticleSystemLegacyEXT {
    remixapi_StructType      sType;
    void* pNext;
    uint32_t         maxNumParticles;
    remixapi_Bool    useTurbulence;
    remixapi_Bool    alignParticlesToVelocity;
    remixapi_Bool    useSpawnTexcoords;
    remixapi_Bool    enableCollisionDetection;
    remixapi_Bool    enableMotionTrail;
    remixapi_Bool    hideEmitter;
    remixapi_Float4D minSpawnColor;
    remixapi_Float4D maxSpawnColor;
    float            minTimeToLive;
    float            maxTimeToLive;
    float            initialVelocityFromNormal;
    float            initialVelocityConeAngleDegrees;
    float            minSpawnSize;
    float            maxSpawnSize;
    float            gravityForce;
    float            maxSpeed;
    float            turbulenceFrequency;
    float            turbulenceForce;
    float            minSpawnRotationSpeed;
    float            maxSpawnRotationSpeed;
    float            spawnRatePerSecond;
    float            collisionThickness;
    float            collisionRestitution;
    float            motionTrailMultiplier;
    float            initialVelocityFromMotion;
    float            minTargetSize;
    float            maxTargetSize;
    float            minTargetRotationSpeed;
    float            maxTargetRotationSpeed;
    remixapi_Float4D minTargetColor;
    remixapi_Float4D maxTargetColor;
    uint32_t         billboardType;
  } remixapi_InstanceInfoParticleSystemLegacyEXT;
}

namespace pnext::detail {

  // NOTE: To add a new type:
  //  1) Add the type to 'AllTypes'.
  //  2) Add 'ToEnum' specifying the struct type and corresponding remixapi_StructType.
  //  3) If the new type is an extension
  //       (i.e. can be put linked into 'pNext' chain of a parent type)
  //       (e.g. 'remixapi_LightInfoSphereEXT' is an extension of 'remixapi_LightInfo'):
  //     * Then add 'Root' specifying the extension type and root (parent) type.


  // clang-format off
  using AllTypes = TypeList<
    remixapi_MaterialInfo,
    remixapi_MaterialInfoPortalEXT,
    remixapi_MaterialInfoTranslucentEXT,
    remixapi_MaterialInfoOpaqueEXT,
    remixapi_MaterialInfoOpaqueSubsurfaceEXT,
    remixapi_LightInfoSphereEXT,
    remixapi_LightInfoRectEXT,
    remixapi_LightInfoDiskEXT,
    remixapi_LightInfoCylinderEXT,
    remixapi_LightInfoDistantEXT,
    remixapi_LightInfoDomeEXT,
    remixapi_LightInfoDomeTransmittanceEXT,
    remixapi_LightInfoDomeProjectionEXT,
    remixapi_LightInfoUSDEXT,
    remixapi_LightInfo,
    remixapi_MeshInfo,
    remixapi_InstanceInfo,
    remixapi_InstanceInfoBoneTransformsEXT,
    remixapi_InstanceInfoBlendEXT,
    remixapi_InstanceInfoObjectPickingEXT,
    remixapi_InstanceInfoParticleSystemEXT,
    remixapi_InstanceInfoParticleSystemLegacyEXT,
    remixapi_InstanceInfoGpuInstancingEXT,
    remixapi_CameraInfo,
    remixapi_CameraInfoParameterizedEXT,
    remixapi_CameraInfoSceneOriginEXT,
    remixapi_CameraMediumInfo,
    remixapi_TextureFromMemoryInfo,
    remixapi_TexturePatchInfo,
    remixapi_SubmitInfo,
    remixapi_ConfigVariableResolvedInfo,
    remixapi_VulkanInteropInfo,
    remixapi_FrameGenerationCapabilitiesEXT,
    remixapi_VulkanClientFrameInfoEXT,
    remixapi_SharedBufferInfo,
    remixapi_SharedBufferCommitInfo,
    remixapi_MeshInfoSharedBuffersEXT,
    remixapi_MeshInfoQuadsEXT,
    remixapi_TextureMipChainEXT,
    remixapi_TextureImmutableEXT,
    remixapi_InstanceInfoVoxelCoverageEXT,
    remixapi_VoxelCoverageInfo,
    remixapi_MaterialInfoVertexColorMaskEXT
  >;

  template< typename T > constexpr remixapi_StructType ToEnum                 = REMIXAPI_STRUCT_TYPE_NONE;
  template<> constexpr auto ToEnum< remixapi_MaterialInfo                   > = REMIXAPI_STRUCT_TYPE_MATERIAL_INFO;
  template<> constexpr auto ToEnum< remixapi_MaterialInfoPortalEXT          > = REMIXAPI_STRUCT_TYPE_MATERIAL_INFO_PORTAL_EXT;
  template<> constexpr auto ToEnum< remixapi_MaterialInfoTranslucentEXT     > = REMIXAPI_STRUCT_TYPE_MATERIAL_INFO_TRANSLUCENT_EXT;
  template<> constexpr auto ToEnum< remixapi_MaterialInfoOpaqueEXT          > = REMIXAPI_STRUCT_TYPE_MATERIAL_INFO_OPAQUE_EXT;
  template<> constexpr auto ToEnum< remixapi_MaterialInfoOpaqueSubsurfaceEXT> = REMIXAPI_STRUCT_TYPE_MATERIAL_INFO_OPAQUE_SUBSURFACE_EXT;
  template<> constexpr auto ToEnum< remixapi_LightInfoSphereEXT             > = REMIXAPI_STRUCT_TYPE_LIGHT_INFO_SPHERE_EXT;
  template<> constexpr auto ToEnum< remixapi_LightInfoRectEXT               > = REMIXAPI_STRUCT_TYPE_LIGHT_INFO_RECT_EXT;
  template<> constexpr auto ToEnum< remixapi_LightInfoDiskEXT               > = REMIXAPI_STRUCT_TYPE_LIGHT_INFO_DISK_EXT;
  template<> constexpr auto ToEnum< remixapi_LightInfoCylinderEXT           > = REMIXAPI_STRUCT_TYPE_LIGHT_INFO_CYLINDER_EXT;
  template<> constexpr auto ToEnum< remixapi_LightInfoDistantEXT            > = REMIXAPI_STRUCT_TYPE_LIGHT_INFO_DISTANT_EXT;
  template<> constexpr auto ToEnum< remixapi_LightInfoDomeEXT               > = REMIXAPI_STRUCT_TYPE_LIGHT_INFO_DOME_EXT;
  template<> constexpr auto ToEnum< remixapi_LightInfoDomeTransmittanceEXT  > = REMIXAPI_STRUCT_TYPE_LIGHT_INFO_DOME_TRANSMITTANCE_EXT;
  template<> constexpr auto ToEnum< remixapi_LightInfoDomeProjectionEXT > = REMIXAPI_STRUCT_TYPE_LIGHT_INFO_DOME_PROJECTION_EXT;
  template<> constexpr auto ToEnum< remixapi_LightInfoUSDEXT                > = REMIXAPI_STRUCT_TYPE_LIGHT_INFO_USD_EXT;
  template<> constexpr auto ToEnum< remixapi_LightInfo                      > = REMIXAPI_STRUCT_TYPE_LIGHT_INFO;
  template<> constexpr auto ToEnum< remixapi_MeshInfo                       > = REMIXAPI_STRUCT_TYPE_MESH_INFO;
  template<> constexpr auto ToEnum< remixapi_InstanceInfo                   > = REMIXAPI_STRUCT_TYPE_INSTANCE_INFO;
  template<> constexpr auto ToEnum< remixapi_InstanceInfoBoneTransformsEXT  > = REMIXAPI_STRUCT_TYPE_INSTANCE_INFO_BONE_TRANSFORMS_EXT;
  template<> constexpr auto ToEnum< remixapi_InstanceInfoBlendEXT           > = REMIXAPI_STRUCT_TYPE_INSTANCE_INFO_BLEND_EXT;
  template<> constexpr auto ToEnum< remixapi_InstanceInfoObjectPickingEXT   > = REMIXAPI_STRUCT_TYPE_INSTANCE_INFO_OBJECT_PICKING_EXT;
  template<> constexpr auto ToEnum< remixapi_InstanceInfoParticleSystemLegacyEXT > = REMIXAPI_STRUCT_TYPE_DEPRECATED_LEGACY_PARTICLE_SYSTEM;
  template<> constexpr auto ToEnum< remixapi_InstanceInfoParticleSystemEXT  > = REMIXAPI_STRUCT_TYPE_INSTANCE_INFO_PARTICLE_SYSTEM_EXT;
  template<> constexpr auto ToEnum< remixapi_InstanceInfoGpuInstancingEXT   > = REMIXAPI_STRUCT_TYPE_INSTANCE_INFO_GPU_INSTANCING_EXT;
  template<> constexpr auto ToEnum< remixapi_CameraInfo                     > = REMIXAPI_STRUCT_TYPE_CAMERA_INFO;
  template<> constexpr auto ToEnum< remixapi_CameraInfoParameterizedEXT     > = REMIXAPI_STRUCT_TYPE_CAMERA_INFO_PARAMETERIZED_EXT;
  template<> constexpr auto ToEnum< remixapi_CameraMediumInfo               > = REMIXAPI_STRUCT_TYPE_CAMERA_MEDIUM_INFO;

  template<> constexpr auto ToEnum<remixapi_TextureFromMemoryInfo> = REMIXAPI_STRUCT_TYPE_TEXTURE_FROM_MEMORY_INFO;
  template<> constexpr auto ToEnum<remixapi_TexturePatchInfo> = REMIXAPI_STRUCT_TYPE_TEXTURE_PATCH_INFO;
  template<> constexpr auto ToEnum<remixapi_SubmitInfo> = REMIXAPI_STRUCT_TYPE_SUBMIT_INFO;
  template<> constexpr auto ToEnum<remixapi_ConfigVariableResolvedInfo> = REMIXAPI_STRUCT_TYPE_CONFIG_VARIABLE_RESOLVED_INFO;
  template<> constexpr auto ToEnum<remixapi_VulkanInteropInfo> = REMIXAPI_STRUCT_TYPE_VULKAN_INTEROP_INFO;
  template<> constexpr auto ToEnum<remixapi_SharedBufferInfo> = REMIXAPI_STRUCT_TYPE_SHARED_BUFFER_INFO;
  template<> constexpr auto ToEnum<remixapi_SharedBufferCommitInfo> = REMIXAPI_STRUCT_TYPE_SHARED_BUFFER_COMMIT_INFO;
  template<> constexpr auto ToEnum<remixapi_MeshInfoSharedBuffersEXT> = REMIXAPI_STRUCT_TYPE_MESH_INFO_SHARED_BUFFERS_EXT;
  template<> constexpr auto ToEnum<remixapi_MeshInfoQuadsEXT> = REMIXAPI_STRUCT_TYPE_MESH_INFO_QUADS_EXT;
  template<> constexpr auto ToEnum<remixapi_TextureMipChainEXT> = REMIXAPI_STRUCT_TYPE_TEXTURE_MIP_CHAIN_EXT;
  template<> constexpr auto ToEnum<remixapi_TextureImmutableEXT> = REMIXAPI_STRUCT_TYPE_TEXTURE_IMMUTABLE_EXT;

  template<> constexpr auto ToEnum<remixapi_VulkanClientFrameInfoEXT> = REMIXAPI_STRUCT_TYPE_VULKAN_CLIENT_FRAME_INFO_EXT;
  template<> constexpr auto ToEnum<remixapi_FrameGenerationCapabilitiesEXT> = REMIXAPI_STRUCT_TYPE_FRAME_GENERATION_CAPABILITIES_EXT;

  template< typename T > struct Root                                         { using Type = T; /* by default, a root is self */ };
  template<> struct Root<remixapi_VulkanClientFrameInfoEXT> { using Type = remixapi_PresentInfo; };
  template<> struct Root<remixapi_FrameGenerationCapabilitiesEXT> { using Type = remixapi_VulkanInteropInfo; };
  template<> struct Root<remixapi_MeshInfoSharedBuffersEXT> { using Type = remixapi_MeshInfo; };
  template<> struct Root<remixapi_MeshInfoQuadsEXT> { using Type = remixapi_MeshInfo; };
  template<> struct Root<remixapi_TextureMipChainEXT> { using Type = remixapi_TextureFromMemoryInfo; };
  template<> struct Root<remixapi_TextureImmutableEXT> { using Type = remixapi_TextureFromMemoryInfo; };
  template<>           struct Root< remixapi_MaterialInfoPortalEXT          >{ using Type = remixapi_MaterialInfo;              };
  template<>           struct Root< remixapi_MaterialInfoTranslucentEXT     >{ using Type = remixapi_MaterialInfo;              };
  template<>           struct Root< remixapi_MaterialInfoOpaqueEXT          >{ using Type = remixapi_MaterialInfo;              };
  template<>           struct Root< remixapi_MaterialInfoOpaqueSubsurfaceEXT>{ using Type = remixapi_MaterialInfo;              };
  template<>           struct Root< remixapi_LightInfoSphereEXT             >{ using Type = remixapi_LightInfo;                 };
  template<>           struct Root< remixapi_LightInfoRectEXT               >{ using Type = remixapi_LightInfo;                 };
  template<>           struct Root< remixapi_LightInfoDiskEXT               >{ using Type = remixapi_LightInfo;                 };
  template<>           struct Root< remixapi_LightInfoCylinderEXT           >{ using Type = remixapi_LightInfo;                 };
  template<>           struct Root< remixapi_LightInfoDistantEXT            >{ using Type = remixapi_LightInfo;                 };
  template<>           struct Root< remixapi_LightInfoDomeEXT               >{ using Type = remixapi_LightInfo;                 };
  template<>           struct Root< remixapi_LightInfoDomeTransmittanceEXT  >{ using Type = remixapi_LightInfo;                 };
  template<>           struct Root< remixapi_LightInfoDomeProjectionEXT >{ using Type = remixapi_LightInfo; };
  template<>           struct Root< remixapi_LightInfoUSDEXT                >{ using Type = remixapi_LightInfo;                 };
  template<>           struct Root< remixapi_InstanceInfoBoneTransformsEXT  >{ using Type = remixapi_InstanceInfo;              };
  template<> struct Root<remixapi_InstanceInfoVoxelCoverageEXT> { using Type = remixapi_InstanceInfo; };
  template<>           struct Root< remixapi_InstanceInfoBlendEXT           >{ using Type = remixapi_InstanceInfo;              };
  template<>           struct Root< remixapi_InstanceInfoObjectPickingEXT   >{ using Type = remixapi_InstanceInfo;              };
  template<>           struct Root< remixapi_InstanceInfoParticleSystemEXT  >{ using Type = remixapi_InstanceInfo;              };
  template<>           struct Root< remixapi_InstanceInfoParticleSystemLegacyEXT >{ using Type = remixapi_InstanceInfo;         };
  template<>           struct Root< remixapi_InstanceInfoGpuInstancingEXT   >{ using Type = remixapi_InstanceInfo;              };
  template<>           struct Root< remixapi_CameraInfoParameterizedEXT     >{ using Type = remixapi_CameraInfo;                };
  template<> constexpr auto ToEnum<remixapi_InstanceInfoVoxelCoverageEXT> = REMIXAPI_STRUCT_TYPE_INSTANCE_INFO_VOXEL_COVERAGE_EXT;
  template<> constexpr auto ToEnum<remixapi_VoxelCoverageInfo> = REMIXAPI_STRUCT_TYPE_VOXEL_COVERAGE_INFO;
  template<> constexpr auto ToEnum<remixapi_MaterialInfoVertexColorMaskEXT> = REMIXAPI_STRUCT_TYPE_MATERIAL_INFO_VERTEX_COLOR_MASK_EXT;
  template<> struct Root<remixapi_MaterialInfoVertexColorMaskEXT> { using Type = remixapi_MaterialInfo; };
  template<> constexpr auto ToEnum<remixapi_MaterialInfoWaterSurfaceEXT> = REMIXAPI_STRUCT_TYPE_MATERIAL_INFO_WATER_SURFACE_EXT;
  template<> struct Root<remixapi_MaterialInfoWaterSurfaceEXT> { using Type = remixapi_MaterialInfo; };
  template<> constexpr auto ToEnum<remixapi_CameraInfoSceneOriginEXT> = REMIXAPI_STRUCT_TYPE_CAMERA_INFO_SCENE_ORIGIN_EXT;
  template<> struct Root<remixapi_CameraInfoSceneOriginEXT> { using Type = remixapi_CameraInfo; };

  template<> constexpr auto ToEnum<remixapi_MaterialInfoEndPortalEXT> = REMIXAPI_STRUCT_TYPE_MATERIAL_INFO_END_PORTAL_EXT;
  template<> struct Root<remixapi_MaterialInfoEndPortalEXT> { using Type = remixapi_MaterialInfo; };
  template<> constexpr auto ToEnum<remixapi_SubmitInfoWaterSimEXT> = REMIXAPI_STRUCT_TYPE_SUBMIT_INFO_WATER_SIM_EXT;
  template<> constexpr auto ToEnum<remixapi_SubmitInfoWaterRainEXT> = REMIXAPI_STRUCT_TYPE_SUBMIT_INFO_WATER_RAIN_EXT;
  template<> struct Root<remixapi_SubmitInfoWaterSimEXT> { using Type = remixapi_SubmitInfo; };
  template<> constexpr auto ToEnum<remixapi_HostVulkanDeviceInfo> = REMIXAPI_STRUCT_TYPE_HOST_VULKAN_DEVICE_INFO;
  // clang-format on
}
