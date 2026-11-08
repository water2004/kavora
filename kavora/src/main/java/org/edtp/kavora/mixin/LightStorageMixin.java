/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import org.edtp.kavora.terrain.VanillaSectionBuilds;
import org.edtp.kavora.terrain.SkippedSectionRebuilds;
import org.edtp.kavora.terrain.ServerLightSnapshot;
import org.edtp.kavora.terrain.LightStorageSnapshotAccess;
import org.edtp.kavora.terrain.ClientLightSnapshotPolicy;
import org.edtp.kavora.terrain.ClientLightSnapshotPolicy.Decision;
import org.edtp.kavora.KavoraMod;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.BlockLightSectionStorage;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.SkyLightSectionStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces how a light storage publishes its thread-safe snapshot in {@code notifyChanges},
 * the only place vanilla writes {@code uncachedStorage}; it runs once per storage per
 * {@code doLightUpdates}.
 *
 * <p>Client engines publish the live section map itself while RTX capture is on, in place of
 * vanilla's per-pass clone; {@link ClientLightSnapshotPolicy} holds the reasoning and the
 * invariant this depends on. When the live map is published, the pass's pending
 * {@code onLightUpdate} notifications are counted as skipped light-only rebuilds and
 * dropped, which is what {@link ClientChunkManagerMixin} does to each of them while capture
 * is on, so {@code Chunk.LightRebuildsSkipped} keeps its meaning. {@code dirtySections} is
 * cleared as vanilla clears it after a clone: it drives the copy-on-write that protects a
 * separate snapshot, there is none while the live map is published, and the alias ends with
 * an unconditional real copy rather than a dirty check.
 *
 * <p>The integrated server's engines have real readers on other threads, so they keep a
 * separate snapshot, published as {@link ServerLightSnapshot}'s overlay view instead of a
 * whole-map clone. Its notifications run exactly as vanilla's do.
 */
@Mixin(LayerLightSectionStorage.class)
public abstract class LightStorageMixin<M extends DataLayerStorageMap<M>> implements LightStorageSnapshotAccess {

    @Shadow
    @Final
    protected LightChunkGetter chunkSource;

    @Shadow
    @Final
    protected M updatingSectionData;

    @Shadow
    protected volatile M visibleSectionData;

    @Shadow
    @Final
    protected LongSet changedSections;

    @Shadow
    @Final
    protected LongSet sectionsAffectedByLightUpdates;

    @Shadow
    @Final
    private LightLayer layer;

    /** Created on the first pass: classifying the engine needs the chunk provider's world. */
    private ClientLightSnapshotPolicy kavora$snapshotPolicy;

    /** Integrated-server engines only; null for client engines and while the kill switch is set. */
    private ServerLightSnapshot kavora$serverSnapshot;

    private boolean kavora$classified;

    @Inject(method = "swapSectionMap", at = @At("HEAD"), cancellable = true)
    private void kavora$publishSnapshotForCapture(CallbackInfo ci) {
        kavora$classify();
        if (kavora$serverSnapshot != null) {
            kavora$publishServerOverlay();
            ci.cancel();
            return;
        }
        Decision decision = kavora$snapshotPolicy.onNotifyChanges(
                KavoraMod.isRtxEnabled(), VanillaSectionBuilds.anyInFlight());
        switch (decision) {
            case ALIAS_LIVE_MAP -> {
                visibleSectionData = updatingSectionData;
                changedSections.clear();
                SkippedSectionRebuilds.recordLightOnly(sectionsAffectedByLightUpdates.size());
                sectionsAffectedByLightUpdates.clear();
                ci.cancel();
            }
            case REPUBLISH_THEN_VANILLA -> kavora$publishRealCopy();
            case VANILLA -> {
            }
        }
    }

    @Override
    public void kavora$publishRealSnapshot() {
        if (kavora$snapshotPolicy != null && kavora$snapshotPolicy.releaseLiveMap()) {
            kavora$publishRealCopy();
        }
    }

    /** Vanilla's publication sequence; the copy runs without its lookup cache because workers share it. */
    private void kavora$publishRealCopy() {
        M copy = updatingSectionData.copy();
        copy.disableCache();
        visibleSectionData = copy;
        changedSections.clear();
    }

    /** Vanilla's sequence with the clone replaced by the overlay view; notifications unchanged. */
    @SuppressWarnings("unchecked")
    private void kavora$publishServerOverlay() {
        if (!changedSections.isEmpty()) {
            M snapshot;
            if (updatingSectionData instanceof SkyLightSectionStorage.SkyDataLayerStorageMap sky) {
                ServerLightSnapshot.Published published =
                        kavora$serverSnapshot.publish(updatingSectionData.map, sky.topSections, changedSections);
                snapshot = (M) new SkyLightSectionStorage.SkyDataLayerStorageMap(published.sections(), published.columns(), sky.currentLowestY);
            } else {
                ServerLightSnapshot.Published published =
                        kavora$serverSnapshot.publish(updatingSectionData.map, null, changedSections);
                snapshot = (M) new BlockLightSectionStorage.BlockDataLayerStorageMap(published.sections());
            }
            snapshot.disableCache();
            visibleSectionData = snapshot;
            changedSections.clear();
        }
        if (!sectionsAffectedByLightUpdates.isEmpty()) {
            for (LongIterator sections = sectionsAffectedByLightUpdates.iterator(); sections.hasNext(); ) {
                chunkSource.onLightUpdate(layer, SectionPos.of(sections.nextLong()));
            }
            sectionsAffectedByLightUpdates.clear();
        }
    }

    private void kavora$classify() {
        if (kavora$classified) {
            return;
        }
        kavora$classified = true;
        boolean clientEngine = chunkSource.getLevel() instanceof ClientLevel;
        kavora$snapshotPolicy = new ClientLightSnapshotPolicy(
                clientEngine, false);
        if (!clientEngine && ClientLightSnapshotPolicy.ALIAS_ALLOWED) {
            kavora$serverSnapshot = new ServerLightSnapshot();
        }
    }
}
