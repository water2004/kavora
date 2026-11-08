/* SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package org.edtp.kavora.horizon;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/** LOD simplification must not turn holes in a model into coverage of its neighbours. */
final class BlockFaceOcclusion {
    static final int VISIBLE = 0, OCCLUDED = 1, INTERNAL = 2;
    private static final Direction[] DIRECTIONS = Direction.values();

    private BlockFaceOcclusion() {}

    static boolean isFluidSurface(BlockState state) {
        return state.getBlock() instanceof LiquidBlock;
    }

    static int between(BlockState state, BlockState adjacent, int direction) {
        // Only boundary faces can disappear behind a neighbouring full cube. A slab's
        // recessed top and a plant's crossed planes remain visible below that boundary.
        if (adjacent.isSolidRender() && state.isSolidRender()) return OCCLUDED;
        // A liquid boundary against an opaque cube is inside the solid, not another
        // optical interface. The solid supplies the visible submerged surface.
        if (adjacent.isSolidRender() && isFluidSurface(state)) return OCCLUDED;
        // Water inside kelp/waterlogged blocks belongs to the same volume. The plant's
        // own cutout faces still render: this test is deliberately asymmetric.
        if (isFluidSurface(state)
                && !adjacent.getFluidState().isEmpty()
                && state.getFluidState().getType().isSame(adjacent.getFluidState().getType()))
            return INTERNAL;
        if (state.skipRendering(adjacent, DIRECTIONS[direction])) return INTERNAL;
        return VISIBLE;
    }
}
