/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.sdk;

/**
 * Structure type identifiers for Remix API structures
 */
public final class StructType {
    public static final int NONE = 0;
    public static final int INITIALIZE_LIBRARY_INFO = 1;
    public static final int MATERIAL_INFO = 2;
    public static final int MATERIAL_INFO_PORTAL_EXT = 3;
    public static final int MATERIAL_INFO_TRANSLUCENT_EXT = 4;
    public static final int MATERIAL_INFO_OPAQUE_EXT = 5;
    public static final int LIGHT_INFO = 6;
    public static final int LIGHT_INFO_DISTANT_EXT = 7;
    public static final int LIGHT_INFO_CYLINDER_EXT = 8;
    public static final int LIGHT_INFO_DISK_EXT = 9;
    public static final int LIGHT_INFO_RECT_EXT = 10;
    public static final int LIGHT_INFO_SPHERE_EXT = 11;
    public static final int MESH_INFO = 12;
    public static final int INSTANCE_INFO = 13;
    public static final int INSTANCE_INFO_BONE_TRANSFORMS_EXT = 14;
    public static final int INSTANCE_INFO_BLEND_EXT = 15;
    public static final int CAMERA_INFO = 16;
    public static final int CAMERA_INFO_PARAMETERIZED_EXT = 17;
    public static final int MATERIAL_INFO_OPAQUE_SUBSURFACE_EXT = 18;
    public static final int INSTANCE_INFO_OBJECT_PICKING_EXT = 19;
    public static final int LIGHT_INFO_DOME_EXT = 20;
    public static final int LIGHT_INFO_USD_EXT = 21;
    public static final int STARTUP_INFO = 22;
    public static final int PRESENT_INFO = 23;
    public static final int DEPRECATED_LEGACY_PARTICLE_SYSTEM = 24;
    public static final int INSTANCE_INFO_PARTICLE_SYSTEM_EXT = 25;
    public static final int INSTANCE_INFO_GPU_INSTANCING_EXT = 26;
    public static final int CAMERA_MEDIUM_INFO = 27;
    public static final int TEXTURE_FROM_MEMORY_INFO = 28;
    public static final int TEXTURE_PATCH_INFO = 29;
    public static final int SUBMIT_INFO = 30;
    public static final int CONFIG_VARIABLE_RESOLVED_INFO = 31;

    public static final int VULKAN_INTEROP_INFO = 32;
    public static final int INTEROP_FRAME_STATUS_EXT = 33;
    public static final int SHARED_BUFFER_INFO = 34;
    public static final int SHARED_BUFFER_COMMIT_INFO = 35;
    public static final int MESH_INFO_SHARED_BUFFERS_EXT = 36;
    public static final int TEXTURE_MIP_CHAIN_EXT = 37;
    public static final int INSTANCE_INFO_VOXEL_COVERAGE_EXT = 38;
    public static final int VOXEL_COVERAGE_INFO = 39;
    public static final int MATERIAL_INFO_VERTEX_COLOR_MASK_EXT = 40;
    public static final int TEXTURE_IMMUTABLE_EXT = 41;
    public static final int HOST_VULKAN_DEVICE_INFO = 42;
    public static final int FRAME_GENERATION_CAPABILITIES_EXT = 43;
    public static final int VULKAN_CLIENT_FRAME_INFO_EXT = 44;
    public static final int LIGHT_INFO_DOME_TRANSMITTANCE_EXT = 45;

    public static final int LIGHT_INFO_DOME_PROJECTION_EXT = 46;
    public static final int MESH_INFO_QUADS_EXT = 47;
    public static final int MATERIAL_INFO_WATER_SURFACE_EXT = 48;
    public static final int SUBMIT_INFO_WATER_SIM_EXT = 49;

    private StructType() {}
}
