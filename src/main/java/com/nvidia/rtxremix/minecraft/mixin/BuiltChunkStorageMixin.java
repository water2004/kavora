/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.BuiltChunkNeighbourhoodAccess;
import com.nvidia.rtxremix.minecraft.terrain.visibility.BuiltChunkStorageSectionAccess;
import com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter;
import com.nvidia.rtxremix.minecraft.terrain.SkippedSectionRebuilds;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
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

    @Override public Iterable<SectionRenderDispatcher.RenderSection> rtxremix$sections() { return sections; }
    @Override public SectionRenderDispatcher.RenderSection rtxremix$section(long sectionNode) { return sections.getValue(sectionNode); }

    /** The slot vanilla aliases the section onto, or null unless that slot currently holds the section. */
    private SectionRenderDispatcher.RenderSection rtxremix$residentSlot(int cx, int sy, int cz) {
        return sections.getValue(cx, sy, cz);
    }

    @Override
    public void rtxremix$scheduleRebuildIfResident(int cx, int sy, int cz) {
        SectionRenderDispatcher.RenderSection slot = rtxremix$residentSlot(cx, sy, cz);
        if (slot != null) {
            net.minecraft.client.Minecraft.getInstance().levelExtractor.setSectionDirty(
                    SectionPos.x(slot.getSectionNode()), SectionPos.y(slot.getSectionNode()), SectionPos.z(slot.getSectionNode()));
        }
    }

    @Override
    public void rtxremix$flagLoadedSection(int cx, int sy, int cz) {
        if (!NeighbourReflagFilter.sectionInRange(sy, sections.minY(), sections.height())) {
            return;
        }
        rtxremix$scheduleRebuildIfResident(cx, sy, cz);
        for (int k = 0; k < NeighbourReflagFilter.NEIGHBOUR_COUNT; k++) {
            SectionRenderDispatcher.RenderSection slot = rtxremix$residentSlot(
                    cx + NeighbourReflagFilter.DX[k], sy, cz + NeighbourReflagFilter.DZ[k]);
            boolean resident = slot != null;
            boolean awaitingBuild = resident && rtxremix$isDirty(slot.getSectionNode());
            int snapshotMask = resident ? ((BuiltChunkNeighbourhoodAccess) slot).rtxremix$snapshotMask() : 0;
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
    public void rtxremix$clearNeighbourSnapshotBits(int cx, int cz) {
        int bottomSection = sections.minY();
        for (int k = 0; k < NeighbourReflagFilter.NEIGHBOUR_COUNT; k++) {
            int bit = NeighbourReflagFilter.bit(NeighbourReflagFilter.mirroredIndex(k));
            int neighbourX = cx + NeighbourReflagFilter.DX[k];
            int neighbourZ = cz + NeighbourReflagFilter.DZ[k];
            for (int row = 0; row < sections.height(); row++) {
                SectionRenderDispatcher.RenderSection slot = rtxremix$residentSlot(neighbourX, bottomSection + row, neighbourZ);
                if (slot != null) {
                    ((BuiltChunkNeighbourhoodAccess) slot).rtxremix$clearSnapshotBit(bit);
                }
            }
        }
    }
    private static boolean rtxremix$isDirty(long sectionNode) {
        var tracker = ((LevelExtractorSectionAccessor) net.minecraft.client.Minecraft.getInstance().levelExtractor)
                .rtxremix$sectionUpdateTracker();
        var state = tracker.getDirtyState(sectionNode);
        return state != null && state.isDirty();
    }

}
