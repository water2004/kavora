/*
 * SPDX-FileCopyrightText: Copyright (c) Kavora contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.terrain.capture;

import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.Arrays;
import java.util.List;

/** Subtracts opaque model faces lying on the same plane as a full glass face. */
final class GlassFaceCoverage {
    private static final float EPSILON = 0.0001f;

    private final float[] clippedPositions = new float[12];
    private final float[] clippedUvs = new float[8];
    private final int[] clippedColors = new int[4];
    private final float[] backing = new float[12];
    private final int[] corners = new int[4];

    boolean clip(DirectQuadWriter writer, float[] positions, float[] uvs, float nx, float ny, float nz,
            TextureAtlasSprite sprite, String layer, BlockPos block, Direction face,
            List<BlockStateModelPart> backingParts, boolean wet) {
        int axis = switch (face.getAxis()) {
            case X -> 0;
            case Y -> 1;
            case Z -> 2;
        };
        int s = (axis + 1) % 3;
        int t = (axis + 2) % 3;
        float originS = SectionPos.sectionRelative(coordinate(block, s));
        float originT = SectionPos.sectionRelative(coordinate(block, t));
        boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        float plane = SectionPos.sectionRelative(coordinate(block, axis)) + (positive ? 1 : 0);
        Rectangle glass = rectangle(positions, axis, plane, s, t, originS, originT, corners);
        if (glass == null) return false;

        VoxelShape visible = glass.shape();
        for (int i = 0; i < backingParts.size(); i++) {
            BlockStateModelPart part = backingParts.get(i);
            visible = subtract(visible, part.getQuads(face.getOpposite()), face.getOpposite(), axis, s, t, positive);
            visible = subtract(visible, part.getQuads(null), face.getOpposite(), axis, s, t, positive);
        }
        if (visible == glass.shape()) return false;

        Arrays.fill(clippedColors, wet ? 0x00ffffff : 0xffffffff);
        visible.forAllBoxes((minS, minT, z0, maxS, maxT, z1) -> {
            for (int vertex = 0; vertex < 4; vertex++) {
                float localS = (float) ((corners[vertex] & 1) == 0 ? minS : maxS);
                float localT = (float) ((corners[vertex] & 2) == 0 ? minT : maxT);
                clippedPositions[vertex * 3 + axis] = plane;
                clippedPositions[vertex * 3 + s] = originS + localS;
                clippedPositions[vertex * 3 + t] = originT + localT;
                float a = (localS - glass.minS) / (glass.maxS - glass.minS);
                float b = (localT - glass.minT) / (glass.maxT - glass.minT);
                clippedUvs[vertex * 2] = interpolate(uvs, 0, a, b);
                clippedUvs[vertex * 2 + 1] = interpolate(uvs, 1, a, b);
            }
            writer.stageColoredQuad(clippedPositions, clippedUvs, nx, ny, nz, clippedColors,
                    sprite.contents().name(), sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1(), layer);
        });
        return true;
    }

    private float interpolate(float[] uvs, int offset, float a, float b) {
        float result = 0;
        for (int vertex = 0; vertex < 4; vertex++) {
            float x = (corners[vertex] & 1) != 0 ? a : 1 - a;
            float y = (corners[vertex] & 2) != 0 ? b : 1 - b;
            result += x * y * uvs[vertex * 2 + offset];
        }
        return result;
    }

    private VoxelShape subtract(VoxelShape visible, List<BakedQuad> quads, Direction outward,
            int axis, int s, int t, boolean positive) {
        for (int q = 0; q < quads.size(); q++) {
            BakedQuad quad = quads.get(q);
            if (quad.direction() != outward || quad.materialInfo().layer() != ChunkSectionLayer.SOLID) continue;
            for (int v = 0; v < 4; v++) {
                var p = quad.position(v);
                backing[v * 3] = p.x();
                backing[v * 3 + 1] = p.y();
                backing[v * 3 + 2] = p.z();
            }
            Rectangle cover = rectangle(backing, axis, positive ? 0 : 1, s, t, 0, 0, null);
            if (cover != null) {
                VoxelShape coverShape = cover.shape();
                if (!Shapes.joinIsNotEmpty(visible, coverShape, BooleanOp.AND)) continue;
                visible = Shapes.join(visible, coverShape, BooleanOp.ONLY_FIRST);
                if (visible.isEmpty()) return visible;
            }
        }
        return visible;
    }

    private static Rectangle rectangle(float[] pos, int axis, float plane, int s, int t,
            float originS, float originT, int[] corners) {
        float minS = Float.POSITIVE_INFINITY, minT = minS;
        float maxS = Float.NEGATIVE_INFINITY, maxT = maxS;
        for (int v = 0; v < 12; v += 3) {
            if (Math.abs(pos[v + axis] - plane) > EPSILON) return null;
            minS = Math.min(minS, pos[v + s] - originS);
            maxS = Math.max(maxS, pos[v + s] - originS);
            minT = Math.min(minT, pos[v + t] - originT);
            maxT = Math.max(maxT, pos[v + t] - originT);
        }
        if (maxS - minS <= EPSILON || maxT - minT <= EPSILON) return null;
        int mask = 0;
        for (int v = 0; v < 4; v++) {
            float a = pos[v * 3 + s] - originS;
            float b = pos[v * 3 + t] - originT;
            boolean right = Math.abs(a - maxS) <= EPSILON;
            boolean top = Math.abs(b - maxT) <= EPSILON;
            if ((!right && Math.abs(a - minS) > EPSILON) || (!top && Math.abs(b - minT) > EPSILON)) return null;
            int corner = (right ? 1 : 0) | (top ? 2 : 0);
            mask |= 1 << corner;
            if (corners != null) corners[v] = corner;
        }
        return mask == 15 ? new Rectangle(minS, minT, maxS, maxT, Shapes.box(minS, minT, 0, maxS, maxT, 1)) : null;
    }

    private static int coordinate(BlockPos pos, int axis) {
        return switch (axis) {
            case 0 -> pos.getX();
            case 1 -> pos.getY();
            default -> pos.getZ();
        };
    }

    private record Rectangle(float minS, float minT, float maxS, float maxT, VoxelShape shape) {}
}
