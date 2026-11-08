/* SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.light;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;

/** Coalesces adjacent uniform unit faces without changing emitting area, radiance or direction. */
public final class AreaLightMerger {
    private static final class Group {
        final LightEntry source;
        final int uAxis, vAxis;
        final int[] rows = new int[16];
        final Group next;

        Group(LightEntry source, int uAxis, int vAxis, Group next) {
            this.source = source;
            this.uAxis = uAxis;
            this.vAxis = vAxis;
            this.next = next;
        }
    }

    private final Long2ObjectOpenHashMap<Group> groups = new Long2ObjectOpenHashMap<>();

    public List<LightEntry> merge(List<LightEntry> lights, int originX, int originY, int originZ) {
        if (lights.size() < 2) return List.copyOf(lights);
        var output = new ArrayList<LightEntry>();
        try {
            for (LightEntry light : lights) {
                int uAxis = axis(light.xAxisX(), light.xAxisY(), light.xAxisZ());
                int vAxis = axis(light.yAxisX(), light.yAxisY(), light.yAxisZ());
                if (light.shape() != LightShape.RECT || light.hasShaping()
                        || light.xSize() != 1 || light.ySize() != 1 || uAxis < 0 || vAxis < 0 || uAxis == vAxis) {
                    output.add(light);
                    continue;
                }
                float pu = position(light, uAxis) - coordinate(originX, originY, originZ, uAxis) - .5f;
                float pv = position(light, vAxis) - coordinate(originX, originY, originZ, vAxis) - .5f;
                int u = Math.round(pu), v = Math.round(pv);
                if (u < 0 || u >= 16 || v < 0 || v >= 16 || Math.abs(pu - u) > .0001f || Math.abs(pv - v) > .0001f) {
                    output.add(light);
                    continue;
                }
                int normalAxis = 3 - uAxis - vAxis;
                long hash = signature(light, uAxis, vAxis, normalAxis);
                Group first = groups.get(hash), group = first;
                while (group != null && (group.uAxis != uAxis || group.vAxis != vAxis
                        || position(group.source, normalAxis) != position(light, normalAxis)
                        || !sameSurface(group.source, light))) group = group.next;
                if (group == null) {
                    group = new Group(light, uAxis, vAxis, first);
                    groups.put(hash, group);
                }
                int bit = 1 << u;
                if ((group.rows[v] & bit) != 0) output.add(light);
                else group.rows[v] |= bit;
            }
            for (Group first : groups.values()) {
                for (Group group = first; group != null; group = group.next) {
                    for (int v = 0; v < 16; v++) {
                        while (group.rows[v] != 0) {
                            int u = Integer.numberOfTrailingZeros(group.rows[v]);
                            int width = Math.min(16 - u, Integer.numberOfTrailingZeros(~(group.rows[v] >>> u)));
                            int mask = ((1 << width) - 1) << u, height = 1;
                            while (v + height < 16 && (group.rows[v + height] & mask) == mask) height++;
                            for (int row = v; row < v + height; row++) group.rows[row] &= ~mask;
                            output.add(rectangle(group, originX, originY, originZ, u, v, width, height));
                        }
                    }
                }
            }
            return List.copyOf(output);
        } finally {
            groups.clear();
        }
    }

    private static LightEntry rectangle(Group group, int x, int y, int z, int u, int v, int width, int height) {
        LightEntry source = group.source;
        float cx = source.x(), cy = source.y(), cz = source.z();
        float cu = coordinate(x, y, z, group.uAxis) + u + width * .5f;
        float cv = coordinate(x, y, z, group.vAxis) + v + height * .5f;
        if (group.uAxis == 0) cx = cu; else if (group.uAxis == 1) cy = cu; else cz = cu;
        if (group.vAxis == 0) cx = cv; else if (group.vAxis == 1) cy = cv; else cz = cv;
        BlockPos old = source.key().pos();
        int bx = group.uAxis == 0 ? x + u : group.vAxis == 0 ? x + v : old.getX();
        int by = group.uAxis == 1 ? y + u : group.vAxis == 1 ? y + v : old.getY();
        int bz = group.uAxis == 2 ? z + u : group.vAxis == 2 ? z + v : old.getZ();
        return source.withRectangle(new BlockLightKey(new BlockPos(bx, by, bz), source.key().ruleId(), source.key().lightIndex()),
                cx, cy, cz, width, height);
    }

    private static int axis(float x, float y, float z) {
        return x == 1 && y == 0 && z == 0 ? 0 : x == 0 && y == 1 && z == 0 ? 1 : x == 0 && y == 0 && z == 1 ? 2 : -1;
    }

    private static float position(LightEntry light, int axis) {
        return axis == 0 ? light.x() : axis == 1 ? light.y() : light.z();
    }

    private static int coordinate(int x, int y, int z, int axis) {
        return axis == 0 ? x : axis == 1 ? y : z;
    }

    private static long signature(LightEntry light, int u, int v, int normal) {
        long hash = 31L * System.identityHashCode(light.block()) + light.key().ruleId().hashCode();
        hash = hash * 31 + light.key().lightIndex();
        hash = hash * 31 + u * 3 + v;
        hash = hash * 31 + Float.floatToIntBits(position(light, normal));
        hash = hash * 31 + Float.floatToIntBits(light.intensity());
        return hash;
    }

    private static boolean sameSurface(LightEntry a, LightEntry b) {
        return a.block() == b.block() && a.key().ruleId().equals(b.key().ruleId())
                && a.key().lightIndex() == b.key().lightIndex() && a.lightLevel() == b.lightLevel()
                && a.hasColor() == b.hasColor() && a.colorR() == b.colorR() && a.colorG() == b.colorG() && a.colorB() == b.colorB()
                && a.hasIntensity() == b.hasIntensity() && a.intensity() == b.intensity()
                && a.authoredIntensityMultiplier() == b.authoredIntensityMultiplier()
                && a.volumetricRadianceScale() == b.volumetricRadianceScale()
                && a.xAxisX() == b.xAxisX() && a.xAxisY() == b.xAxisY() && a.xAxisZ() == b.xAxisZ()
                && a.yAxisX() == b.yAxisX() && a.yAxisY() == b.yAxisY() && a.yAxisZ() == b.yAxisZ()
                && a.directionX() == b.directionX() && a.directionY() == b.directionY() && a.directionZ() == b.directionZ()
                && (a.directionX() == 0 || a.x() == b.x())
                && (a.directionY() == 0 || a.y() == b.y()) && (a.directionZ() == 0 || a.z() == b.z());
    }
}
