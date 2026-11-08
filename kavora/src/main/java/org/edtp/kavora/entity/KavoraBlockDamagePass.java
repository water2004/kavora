/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.entity;

import org.edtp.kavora.sdk.InstanceCategoryBit;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.state.level.BlockBreakingRenderState;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.phys.Vec3;

/**
 * Captures the block-breaking crack where vanilla draws it and submits it through the
 * entity submitter as a decal over the terrain.
 *
 * <p>The extracted breaking model goes through vanilla's block feature builder and
 * decal projector on a block-local stack. The retained instance places that model at
 * the broken block's world position.
 *
 * <p>Vanilla allocates a fresh {@code BlockPos} for every broken block every frame, and the
 * submitter's retained registry keys parts by owner identity, so each broken position is
 * lent one owner token for as long as it keeps being rendered. A position vanilla stops
 * rendering, because the block broke, the player let go, or it left the 32-block range,
 * loses its token at {@link #finishFrame}, and the submitter's {@code endFrame} retires
 * its parts the same frame. The pass holds no native state of its own.
 */
public final class KavoraBlockDamagePass {

    private static final Logger LOGGER = LoggerFactory.getLogger(KavoraBlockDamagePass.class);

    private static final String TYPE_KEY = "minecraft:block_breaking";

    /**
     * The crack is coplanar with the terrain face it is drawn on, which is the case
     * terrain's own overlay category already solves with {@code DECAL_STATIC}: the
     * runtime offsets decal quads off the surface beneath and blends them over it. The
     * runtime handles {@code DECAL_DYNAMIC} identically and has deprecated its option,
     * so the static bit is used for both.
     */
    static final KavoraEntitySubmitter.PartCategoryPolicy TERRAIN_DECAL =
            role -> InstanceCategoryBit.DECAL_STATIC;

    private final RecyclingMatrixStack matrices = new RecyclingMatrixStack();
    private final KavoraVertexConsumerProvider provider = new KavoraVertexConsumerProvider();
    private final KavoraSubmitNodeCollector collector = new KavoraSubmitNodeCollector(provider);
    private final RandomSource modelRandom = RandomSource.create();
    private final ArrayList<BlockStateModelPart> modelParts = new ArrayList<>();
    private final PositionOwners owners = new PositionOwners();
    private final FailureLogThrottle failures = new FailureLogThrottle(LOGGER, "Block damage");
    private float[] reusableBoneData = new float[0];

    /** Captures the extracted destroy animation with vanilla's block feature builder. */
    public void capture(BlockBreakingRenderState state,
                        KavoraEntitySubmitter submitter) {
        if (state.blockState().getRenderShape() != RenderShape.MODEL) return;
        var client = Minecraft.getInstance();
        EntityRenderContext ctx = EntityRenderContext.get();
        provider.beginEntity();
        matrices.pushPose();
        ctx.begin(true);
        List<KavoraVertexConsumerProvider.CapturedLayer> layers;
        try {
            matrices.translate(state.blockState().getOffset(state.blockPos()));
            var model = client.getModelManager().getBlockStateModelSet().get(state.blockState());
            modelRandom.setSeed(state.blockState().getSeed(state.blockPos()));
            modelParts.clear();
            model.collectParts(modelRandom, modelParts);
            collector.submitBreakingBlockModel(matrices, modelParts, state.progress(), model.hasMaterialFlag(1));
            collector.capture();
            layers = provider.endEntity();
        } catch (Exception e) {
            failures.noteFailure("capturing", "block damage", state.blockPos(), e);
            return;
        } finally {
            modelParts.clear();
            matrices.reset();
            ctx.reset();
        }
        if (layers.isEmpty()) return;
        reusableBoneData = ctx.snapshotBoneTransforms(reusableBoneData);
        var result = new KavoraEntityPass.EntityCaptureResult(owners.ownerFor(state.blockPos().asLong()),
                Vec3.atLowerCornerOf(state.blockPos()), 0, layers, reusableBoneData, ctx.getBoneCount(), TYPE_KEY, false);
        try {
            submitter.submitEntity(result, TERRAIN_DECAL);
        } catch (Exception e) {
            failures.noteFailure("submitting", "block damage", state.blockPos(), e);
        }
    }

    /**
     * Drops the owner tokens of positions not captured this frame. Called once per frame
     * from {@code renderAndSubmitFrame}; retiring their scene instances belongs to the
     * submitter's {@code endFrame}.
     */
    public void finishFrame() {
        owners.endFrame();
        failures.endFrame();
    }

    /**
     * One identity token per broken block position, kept while the position is rendered.
     * Tokens are handed out during a frame and the ones not handed out are dropped at
     * {@link #endFrame}, so a position rendered every frame keeps one token and a
     * position that stops being rendered starts over with a new one if it returns.
     */
    static final class PositionOwners {
        private static final class Owner {
            long lastSeenFrame;
        }

        private final Long2ObjectOpenHashMap<Owner> owners = new Long2ObjectOpenHashMap<>();
        private long frame;

        Object ownerFor(long packedPos) {
            Owner owner = owners.get(packedPos);
            if (owner == null) {
                owner = new Owner();
                owners.put(packedPos, owner);
            }
            owner.lastSeenFrame = frame;
            return owner;
        }

        void endFrame() {
            if (!owners.isEmpty()) {
                owners.values().removeIf(owner -> owner.lastSeenFrame != frame);
            }
            frame++;
        }

        int size() {
            return owners.size();
        }
    }

}
