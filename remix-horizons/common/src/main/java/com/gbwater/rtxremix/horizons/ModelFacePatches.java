/* SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.gbwater.rtxremix.horizons;

/** Reuses one output quad. Only model discontinuities split a greedy terrain rectangle. */
final class ModelFacePatches {
    private static final TerrainMaterial.Side[] SIDES = TerrainMaterial.Side.values();
    final float[] positions = new float[12];
    private final float[] source = new float[12];
    private TerrainMaterial.FaceShape shape;
    private int axis, s, t, stepAxis, stepS, stepT, countS, countT, index;
    private float minS, minT, maxS, maxT, firstS, firstT, baseAxis;

    static int cellCount(float min, float max, int step) {
        return Math.max(1, (int) Math.ceil(max / step) - (int) Math.floor(min / step));
    }

    void reset(TerrainMaterial.FaceShape shape, int side, int cellWidth, float[] input) {
        this.shape = shape;
        System.arraycopy(input, 0, source, 0, 12);
        index = 0;
        if (shape == null) {
            countS = countT = 1;
            return;
        }
        var direction = SIDES[side];
        axis = direction.axis;
        s = (axis + 1) % 3;
        t = (axis + 2) % 3;
        stepAxis = cellWidth;
        stepS = stepT = cellWidth;
        minS = minT = Float.POSITIVE_INFINITY;
        maxS = maxT = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 12; i += 3) {
            minS = Math.min(minS, input[i + s]);
            maxS = Math.max(maxS, input[i + s]);
            minT = Math.min(minT, input[i + t]);
            maxT = Math.max(maxT, input[i + t]);
        }
        firstS = (float) Math.floor(minS / stepS) * stepS;
        firstT = (float) Math.floor(minT / stepT) * stepT;
        countS = shape.repeatS() ? cellCount(minS, maxS, stepS) : 1;
        countT = shape.repeatT() ? cellCount(minT, maxT, stepT) : 1;
        baseAxis = input[axis] - (direction.sign > 0 ? stepAxis : 0);
    }

    boolean hasNext() {
        return index < countS * countT;
    }

    /** False means a projected patch was clipped away; the cursor still advances. */
    boolean next() {
        if (!hasNext()) throw new IllegalStateException("Model face exhausted");
        if (shape == null) {
            index++;
            System.arraycopy(source, 0, positions, 0, 12);
            return true;
        }
        float cellS = firstS + (index % countS) * stepS;
        float cellT = firstT + (index / countS) * stepT;
        index++;
        float loS = shape.repeatS() ? Math.max(minS, cellS + shape.minS() * stepS) : minS;
        float hiS = shape.repeatS() ? Math.min(maxS, cellS + shape.maxS() * stepS) : maxS;
        float loT = shape.repeatT() ? Math.max(minT, cellT + shape.minT() * stepT) : minT;
        float hiT = shape.repeatT() ? Math.min(maxT, cellT + shape.maxT() * stepT) : maxT;
        if (!(hiS > loS) || !(hiT > loT)) return false;
        for (int i = 0; i < 12; i += 3) {
            float ps = source[i + s] < (minS + maxS) * .5f ? loS : hiS;
            float pt = source[i + t] < (minT + maxT) * .5f ? loT : hiT;
            positions[i + s] = ps;
            positions[i + t] = pt;
            positions[i + axis] =
                    baseAxis
                            + stepAxis
                                    * (shape.origin()
                                            + shape.ds() * ((ps - cellS) / stepS)
                                            + shape.dt() * ((pt - cellT) / stepT));
        }
        return true;
    }
}
