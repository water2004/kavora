/* SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.gbwater.rtxremix.horizons;

import com.gbwater.rtxremix.horizons.voxel.CoveredVolume;
import com.gbwater.rtxremix.horizons.voxel.VoxelKey;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.minecraft.terrain.lod.NearSection;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;

/** Published near sections clip every far-ray hit, including shadows and reflections. */
final class NearCoverage {
    private ByteBuffer data;
    private int originX, originY, originZ, width, height;
    private boolean dirty = true;
    private long revision;
    private final CoveredVolume volume = new CoveredVolume();

    long revision() {
        return revision;
    }

    void dirty() {
        dirty = true;
    }

    void update(Vec3 camera, ClientLevel world) {
        int radius = Math.max(2, Minecraft.getInstance().options.getEffectiveRenderDistance()) + 4;
        int nextWidth = radius * 2 + 1, nextHeight = world.getSectionsCount();
        int x = Math.floorDiv((int) Math.floor(camera.x), 16) - radius;
        int z = Math.floorDiv((int) Math.floor(camera.z), 16) - radius;
        int y = world.getMinSectionY();
        if (data == null || width != nextWidth || height != nextHeight) {
            clear();
            width = nextWidth;
            height = nextHeight;
            data =
                    MemoryUtil.memCalloc(
                            Math.multiplyExact(Math.multiplyExact(width, width), height));
            dirty = true;
        }
        if (originX != x || originY != y || originZ != z) {
            originX = x;
            originY = y;
            originZ = z;
            dirty = true;
        }
    }

    void publish(RemixAPI api, Map<Long, Set<NearSection>> near) {
        if (!dirty || data == null) return;
        MemoryUtil.memSet(MemoryUtil.memAddress(data), 0, data.capacity());
        for (Set<NearSection> sections : near.values())
            for (NearSection cell : sections) {
                int x = cell.x() - originX, y = cell.y() - originY, z = cell.z() - originZ;
                if (x >= 0 && x < width && y >= 0 && y < height && z >= 0 && z < width) {
                    data.put(x + width * (y + height * z), (byte) 1);
                }
            }
        api.setVoxelCoverageTree(
                originX, originY, originZ, width, height, width, 16, data, 0, null, 0);
        volume.update(data, width, height, width);
        revision++;
        dirty = false;
    }

    int classify(long key, TerrainMeshConverter.Bounds bounds) {
        if (data == null) return CoveredVolume.NONE;
        double x = VoxelKey.blockX(key), y = VoxelKey.blockY(key), z = VoxelKey.blockZ(key);
        double x0 = x + bounds.minX(), y0 = y + bounds.minY(), z0 = z + bounds.minZ();
        double x1 = x + bounds.maxX(), y1 = y + bounds.maxY(), z1 = z + bounds.maxZ();
        double magnitude = Math.max(VoxelKey.size(key),
                Math.max(Math.max(Math.abs(x0), Math.abs(x1)),
                Math.max(Math.max(Math.abs(y0), Math.abs(y1)), Math.max(Math.abs(z0), Math.abs(z1)))));
        // Include the runtime's four-ULP normal offset and world-space interpolation rounding.
        double margin = 16 * Math.ulp((float) magnitude);
        return volume.classify(
                (int) Math.floor((x0 - margin) / 16) - originX,
                (int) Math.floor((y0 - margin) / 16) - originY,
                (int) Math.floor((z0 - margin) / 16) - originZ,
                (int) Math.floor((x1 + margin) / 16) + 1 - originX,
                (int) Math.floor((y1 + margin) / 16) + 1 - originY,
                (int) Math.floor((z1 + margin) / 16) + 1 - originZ);
    }

    void clear() {
        if (data != null) MemoryUtil.memFree(data);
        data = null;
        dirty = true;
    }
}
