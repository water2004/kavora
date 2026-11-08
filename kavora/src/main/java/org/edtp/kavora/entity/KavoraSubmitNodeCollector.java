/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.entity;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.*;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import net.minecraft.client.renderer.feature.phase.TranslucentFeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import org.edtp.kavora.mixin.RenderTypeFeatureRendererAccessor;
import org.edtp.kavora.mixin.FeatureRenderDispatcherAccessor;

/** Replays vanilla's CPU feature builders into the retained Remix capture stream. */
public final class KavoraSubmitNodeCollector extends SubmitNodeStorage implements FeatureRenderPhase.Output {
    private static KavoraVertexConsumerProvider activeProvider;
    private static final FeatureRenderPhase.Output RASTER_ONLY = (submit, ordered) -> {};
    private final KavoraVertexConsumerProvider provider;
    private final List<SubmitNode> singleSubmit = new ArrayList<>(1);
    private final TranslucentFeatureRenderPhase seeThrough = new TranslucentFeatureRenderPhase();
    private FeatureFrameContext context;
    private boolean replaying;

    public KavoraSubmitNodeCollector(KavoraVertexConsumerProvider provider) {
        this.provider = provider;
    }

    public KavoraVertexConsumerProvider provider() { return provider; }

    /** Only consulted by the feature-builder mixin on the render thread. */
    public static KavoraVertexConsumerProvider activeProvider() { return activeProvider; }

    @Override
    public SubmitNodeCollection order(int order) {
        return getSubmitsPerOrder().computeIfAbsent(order, ignored -> new CaptureCollection(this, seeThrough));
    }

    @Override
    public TranslucentFeatureRenderPhase seeThrough() { return seeThrough; }

    /** Flushes before the submitting renderer can change its hand or material context. */
    public void capture() {
        if (replaying) return;
        Minecraft client = Minecraft.getInstance();
        if (context == null || context.blockStateModelSet() != client.getModelManager().getBlockStateModelSet()
                || context.lightmap() != client.gameRenderer.levelLightmap())
            context = new FeatureFrameContext(client.gameRenderer.gameRenderState().optionsRenderState,
                client.font, client.getModelManager().getBlockStateModelSet(), client.getBlockColors(),
                client.getTextureManager(), client.getAtlasManager(), client.gameRenderer.levelLightmap(), null);
        KavoraVertexConsumerProvider previous = activeProvider;
        activeProvider = provider;
        replaying = true;
        try {
            for (SubmitNodeCollection collection : getSubmitsPerOrder().values()) {
                for (FeatureRenderPhase<?> phase : collection.allPhases()) {
                    if (phase.isEmpty()) continue;
                    boolean rasterOnly = phase == collection.outline || phase == collection.waterMask
                            || phase == collection.shadows || phase == collection.shapeOutlines
                            || phase == collection.alwaysOnTopGizmos || phase == collection.translucentGizmos;
                    phase.sortInto(rasterOnly ? RASTER_ONLY : this);
                }
            }
            seeThrough.sortInto(this);
        } catch (RuntimeException | Error failure) {
            discard();
            throw failure;
        } finally {
            replaying = false;
            activeProvider = previous;

            singleSubmit.clear();
        }
    }

    /** Clears CPU submissions after a renderer failure so no stale state crosses captures. */
    public void discard() {
        for (SubmitNodeCollection collection : getSubmitsPerOrder().values()) {
            for (FeatureRenderPhase<?> phase : collection.allPhases()) {
                if (!phase.isEmpty()) phase.sortInto(RASTER_ONLY);
            }
        }
        seeThrough.sortInto(RASTER_ONLY);
    }
    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void accept(SubmitNode submit, boolean ordered) {
        // Raster-only shadows, masks, outlines and debug gizmos have no scene geometry.
        if (submit.featureType() == ShadowFeatureRenderer.TYPE
                || submit.featureType() == ShapeOutlineFeatureRenderer.TYPE
                || submit.featureType() == GizmoFeatureRenderer.TYPE) return;
        // Submission and capture run before vanilla's feature build, on the same render
        // thread. Borrow its registry so Fabric and other mods' feature types participate.
        var renderers = ((FeatureRenderDispatcherAccessor) Minecraft.getInstance().gameRenderer.featureRenderDispatcher())
                .kavora$getFeatureRenderers();
        FeatureRenderer renderer = renderers.getOrThrow((FeatureRendererType) submit.featureType());
        singleSubmit.clear();
        singleSubmit.add(submit);
        ((RenderTypeFeatureRendererAccessor) renderer).kavora$buildGroup(context, singleSubmit);
    }

    /** Marker used by SubmitNodeCollectionMixin to finish each synchronous submission. */
    public static final class CaptureCollection extends SubmitNodeCollection {
        private final KavoraSubmitNodeCollector owner;
        CaptureCollection(KavoraSubmitNodeCollector owner, TranslucentFeatureRenderPhase seeThrough) {
            super(false, seeThrough);
            this.owner = owner;
        }
        public void capture() { owner.capture(); }
    }
}
