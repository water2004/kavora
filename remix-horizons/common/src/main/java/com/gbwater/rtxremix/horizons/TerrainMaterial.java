/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.gbwater.rtxremix.horizons;

import java.util.List;

/** Immutable material descriptors shared with the Remix terrain atlas. */
public final class TerrainMaterial {
    /** Direction order matches Minecraft: down, up, north, south, west, east. */
    public enum Side {
        DOWN(1, -1),
        UP(1, 1),
        NORTH(2, -1),
        SOUTH(2, 1),
        WEST(0, -1),
        EAST(0, 1);
        public final int axis;
        public final int sign;

        Side(int axis, int sign) {
            this.axis = axis;
            this.sign = sign;
        }

        public int opposite() {
            return ordinal() ^ 1;
        }
    }

    /** Selects the existing atlas/PBR terrain material and its blend category. */
    public record SurfaceMaterial(int layer, boolean subsurface, boolean fluid, boolean overlay) {
        public SurfaceMaterial(int layer, boolean subsurface, boolean fluid) {
            this(layer, subsurface, fluid, false);
        }
    }

    /** Affine atlas coordinates along the face's cyclic U and V position axes. */
    public record FaceMaterial(
            SurfaceMaterial surface,
            int tintIndex,
            float minU,
            float minV,
            float maxU,
            float maxV,
            float originU,
            float originV,
            float duU,
            float duV,
            float dvU,
            float dvV,
            FaceShape shape) {
        public FaceMaterial(
                SurfaceMaterial surface,
                int tintIndex,
                float minU,
                float minV,
                float maxU,
                float maxV,
                float originU,
                float originV,
                float duU,
                float duV,
                float dvU,
                float dvV) {
            this(
                    surface, tintIndex, minU, minV, maxU, maxV, originU, originV, duU, duV, dvU,
                    dvV, null);
        }

        public FaceMaterial {
            if (!(maxU > minU) || !(maxV > minV))
                throw new IllegalArgumentException("Empty sprite bounds");
        }
    }

    /**
     * Model-local projected bounds and depth = origin + ds*s + dt*t. Null means a full cube face.
     */
    public record FaceShape(
            float minS, float minT, float maxS, float maxT, float origin, float ds, float dt) {
        public FaceShape {
            if (!(maxS > minS)
                    || !(maxT > minT)
                    || !Float.isFinite(minS + minT + maxS + maxT + origin + ds + dt))
                throw new IllegalArgumentException("Invalid model face geometry");
        }

        boolean repeatS() {
            return minS != 0 || maxS != 1 || ds != 0;
        }

        boolean repeatT() {
            return minT != 0 || maxT != 1 || dt != 0;
        }
    }

    /** Immutable face stacks in paint order. Empty stacks do not contribute geometry. */
    public static final class CellMaterials {
        private final List<FaceMaterial>[] faces;

        public CellMaterials(List<FaceMaterial>[] faces) {
            if (faces.length != 6)
                throw new IllegalArgumentException("Six face descriptors required");
            this.faces = faces.clone();
            for (int i = 0; i < 6; i++) this.faces[i] = List.copyOf(faces[i]);
        }

        public List<FaceMaterial> face(int side) {
            return faces[side];
        }
    }
}
