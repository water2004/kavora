/* SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gametest;

import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.MeshInfo;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Opt-in test instrumentation; no diagnostic copy or hide toggle is in the release JAR. */
public final class RemixWaterGeometryProbe {
    public static final boolean ENABLED = Boolean.getBoolean("rtxremix.gametest.waterGeometry");
    private record Mesh(float[] vertices, int[] indices) { }
    private record Instance(long mesh, float[] transform, int flags, int doubleSided, long pNext) { }
    private static final Map<Long, Mesh> meshes = new HashMap<>();
    private static final Map<Long, Instance> instances = new HashMap<>();
    private static boolean hidden, replaying;

    private RemixWaterGeometryProbe() { }

    public static void created(MeshInfo info, long handle) {
        if (!ENABLED || RtxRemixMod.renderer == null) return;
        var water = RtxRemixMod.renderer.getTextureManager().getWaterSurfaceMaterial();
        if (water == null || info.surfaces.length != 1 || info.surfaces[0].material != water.getHandle()) return;
        var surface = info.surfaces[0];
        var source = surface.vertexBuffer.duplicate().clear().order(ByteOrder.LITTLE_ENDIAN);
        float[] vertices = new float[surface.vertexCount * 6];
        for (int v = 0; v < surface.vertexCount; v++) for (int c = 0; c < 6; c++)
            vertices[v * 6 + c] = source.getFloat(source.position() + v * 64 + c * 4);
        var indices = surface.indexBuffer.duplicate().clear().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        int[] copied = new int[surface.indexCount];
        indices.get(copied);
        meshes.put(handle, new Mesh(vertices, copied));
    }

    public static int submitted(long handle, long mesh, float[] transform, int flags, int doubleSided, long pNext) {
        if (!ENABLED || replaying || !meshes.containsKey(mesh)) return flags;
        instances.put(handle, new Instance(mesh, transform.clone(), flags, doubleSided, pNext));
        return flagsFor(mesh, flags);
    }

    public static int flagsFor(long mesh, int flags) {
        return ENABLED && hidden && !replaying && meshes.containsKey(mesh) ? flags | InstanceCategoryBit.HIDDEN : flags;
    }

    public static void removed(long handle) { if (ENABLED) instances.remove(handle); }
    public static void destroyed(long handle) { if (ENABLED) meshes.remove(handle); }

    public static void hide(RemixAPI api, boolean value) {
        hidden = value;
        replaying = true;
        try {
            for (var entry : instances.entrySet()) {
                Instance i = entry.getValue();
                api.sceneInstanceUpdate(entry.getKey(), i.mesh, i.transform,
                        value ? i.flags | InstanceCategoryBit.HIDDEN : i.flags, i.doubleSided, i.pNext);
            }
        } finally { replaying = false; }
    }

    public static void dump(Path path) {
        try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            out.writeInt(1);
            for (var entry : instances.entrySet()) {
                Instance i = entry.getValue();
                if ((i.flags & InstanceCategoryBit.HIDDEN) != 0) continue;
                Mesh m = meshes.get(i.mesh);
                out.writeLong(entry.getKey());
                for (float f : i.transform) out.writeFloat(f);
                out.writeInt(m.vertices.length);
                for (float f : m.vertices) out.writeFloat(f);
                out.writeInt(m.indices.length);
                for (int index : m.indices) out.writeInt(index);
            }
        } catch (IOException failure) { throw new AssertionError("Cannot dump water geometry", failure); }
    }
}
