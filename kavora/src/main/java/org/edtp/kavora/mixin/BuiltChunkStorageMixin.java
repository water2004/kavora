/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import org.edtp.kavora.terrain.BuiltChunkNeighbourhoodAccess;
import org.edtp.kavora.terrain.visibility.BuiltChunkStorageSectionAccess;
import org.edtp.kavora.terrain.NeighbourReflagFilter;
import org.edtp.kavora.terrain.SkippedSectionRebuilds;
import org.edtp.kavora.profiling.ProfileScope;
import org.edtp.kavora.KavoraMod;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ViewArea.class)
public abstract class BuiltChunkStorageMixin implements BuiltChunkStorageSectionAccess {
    @Shadow @Final private net.minecraft.client.RotatingSectionStorage<SectionRenderDispatcher.RenderSection> sections;

    @Override public Iterable<SectionRenderDispatcher.RenderSection> kavora$sections() { return sections; }
    @Override public SectionRenderDispatcher.RenderSection kavora$section(long sectionNode) { return sections.getValue(sectionNode); }

    /** The slot vanilla aliases the section onto, or null unless that slot currently holds the section. */
    private SectionRenderDispatcher.RenderSection kavora$residentSlot(int cx, int sy, int cz) {
        return sections.getValue(cx, sy, cz);
    }

    @Override
    public void kavora$scheduleRebuildIfResident(int cx, int sy, int cz) {
        SectionRenderDispatcher.RenderSection slot = kavora$residentSlot(cx, sy, cz);
        if (slot != null) {
            net.minecraft.client.Minecraft.getInstance().levelExtractor.setSectionDirty(
                    SectionPos.x(slot.getSectionNode()), SectionPos.y(slot.getSectionNode()), SectionPos.z(slot.getSectionNode()));
        }
    }

    @Override
    public void kavora$flagLoadedSection(int cx, int sy, int cz) {
        if (!NeighbourReflagFilter.sectionInRange(sy, sections.minY(), sections.height())) {
            return;
        }
        kavora$scheduleRebuildIfResident(cx, sy, cz);
        for (int k = 0; k < NeighbourReflagFilter.NEIGHBOUR_COUNT; k++) {
            SectionRenderDispatcher.RenderSection slot = kavora$residentSlot(
                    cx + NeighbourReflagFilter.DX[k], sy, cz + NeighbourReflagFilter.DZ[k]);
            boolean resident = slot != null;
            boolean awaitingBuild = resident && kavora$isDirty(slot.getSectionNode());
            int snapshotMask = resident ? ((BuiltChunkNeighbourhoodAccess) slot).kavora$snapshotMask() : 0;
            int loaderBit = NeighbourReflagFilter.bit(NeighbourReflagFilter.mirroredIndex(k));
            switch (NeighbourReflagFilter.decideNeighbour(resident, awaitingBuild, snapshotMask, loaderBit)) {
                case FLAG -> {
                    net.minecraft.client.Minecraft.getInstance().levelExtractor.setSectionDirty(
                    SectionPos.x(slot.getSectionNode()), SectionPos.y(slot.getSectionNode()), SectionPos.z(slot.getSectionNode()));
                    NeighbourReflagFilter.recordKept();
                }
                case DROP_IN_SNAPSHOT -> SkippedSectionRebuilds.recordNeighbourInSnapshot();
                case SKIP_NOT_RESIDENT, SKIP_AWAITING_BUILD -> {
                }
            }
        }
    }

    @Override
    public void kavora$clearNeighbourSnapshotBits(int cx, int cz) {
        int bottomSection = sections.minY();
        for (int k = 0; k < NeighbourReflagFilter.NEIGHBOUR_COUNT; k++) {
            int bit = NeighbourReflagFilter.bit(NeighbourReflagFilter.mirroredIndex(k));
            int neighbourX = cx + NeighbourReflagFilter.DX[k];
            int neighbourZ = cz + NeighbourReflagFilter.DZ[k];
            for (int row = 0; row < sections.height(); row++) {
                SectionRenderDispatcher.RenderSection slot = kavora$residentSlot(neighbourX, bottomSection + row, neighbourZ);
                if (slot != null) {
                    ((BuiltChunkNeighbourhoodAccess) slot).kavora$clearSnapshotBit(bit);
                }
            }
        }
    }
    private static boolean kavora$isDirty(long sectionNode) {
        var tracker = ((LevelExtractorSectionAccessor) net.minecraft.client.Minecraft.getInstance().levelExtractor)
                .kavora$sectionUpdateTracker();
        var state = tracker.getDirtyState(sectionNode);
        return state != null && state.isDirty();
    }

}
