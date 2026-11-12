/* SPDX-License-Identifier: Apache-2.0 */
package org.edtp.kavora.logic;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** The vanilla dimensions with independent player atmosphere profiles. */
public enum AtmosphereDimension {
    OVERWORLD("overworld", LogicChannels.DIMENSION_OVERWORLD_LIGHTING, null),
    NETHER("nether", LogicChannels.DIMENSION_NETHER_LIGHTING, "nether-lighting.conf"),
    END("end", LogicChannels.DIMENSION_END_LIGHTING, "end-lighting.conf");

    private final String id;
    final String channel;
    final String authoredProfile;

    AtmosphereDimension(String id, String channel, String authoredProfile) {
        this.id = id;
        this.channel = channel;
        this.authoredProfile = authoredProfile;
    }

    public String id() { return id; }

    public static AtmosphereDimension of(ResourceKey<Level> dimension) {
        if (Level.OVERWORLD.equals(dimension)) return OVERWORLD;
        if (Level.NETHER.equals(dimension)) return NETHER;
        return Level.END.equals(dimension) ? END : null;
    }
}
