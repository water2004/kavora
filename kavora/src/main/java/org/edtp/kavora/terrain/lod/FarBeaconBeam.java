/* SPDX-License-Identifier: Apache-2.0 */
package org.edtp.kavora.terrain.lod;

import java.util.List;
import net.minecraft.core.BlockPos;

/** The last complete vanilla beam observed at a block position. */
public record FarBeaconBeam(BlockPos pos, List<Section> sections) {
    public FarBeaconBeam {
        pos = pos.immutable();
        sections = List.copyOf(sections);
    }

    public record Section(int color, int height) {}
}
