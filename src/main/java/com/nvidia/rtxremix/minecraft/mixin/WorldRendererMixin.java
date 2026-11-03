/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.profiling.WorldRendererProfilingAccess;
import com.nvidia.rtxremix.minecraft.terrain.WorldRendererTerrainAccess;
import com.nvidia.rtxremix.minecraft.terrain.visibility.BuiltChunkStorageSectionAccess;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.SubmitNodeCollector;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;

/** Keeps vanilla's traversal and section scheduling while Remix captures its submitted geometry. */
@Mixin(LevelRenderer.class)
public abstract class WorldRendererMixin implements WorldRendererTerrainAccess, WorldRendererProfilingAccess {
    @Shadow private ViewArea viewArea;
    private final ArrayDeque<ProfileScope> rtxremix$renderScopes = new ArrayDeque<>();
    private final ArrayDeque<ProfileScope> rtxremix$compileScopes = new ArrayDeque<>();
    private ProfileScope rtxremix$profilerPhase;

    @Inject(method = "submitBlockDestroyAnimation", at = @At("HEAD"), cancellable = true)
    private void rtxremix$captureBlockDamage(PoseStack matrices, SubmitNodeCollector collector,
                                            LevelRenderState state, CallbackInfo ci) {
        if (!RtxRemixMod.isRemixWorldRendering() || !RtxRemixMod.isWorldEnabled()) return;
        var renderer = RtxRemixMod.renderer;
        for (var breaking : state.blockBreakingRenderStates) {
            renderer.renderBlockDamageInPlace(breaking);
        }
        ci.cancel();
    }

    @Override
    public LongSet rtxremix$builtSectionPositions() {
        return com.nvidia.rtxremix.minecraft.compat.sodium.SodiumCapture.builtSections();
    }

    @Override
    public LongSet rtxremix$queueSectionsForRemix(LongSet sections) {
        return com.nvidia.rtxremix.minecraft.compat.sodium.SodiumCapture.queueSections(sections);
    }

    @Override
    public void rtxremix$scheduleResidentSectionRebuild(int cx, int sy, int cz) {
        com.nvidia.rtxremix.minecraft.compat.sodium.SodiumCapture.scheduleRebuild(cx, sy, cz);
    }

    @Override
    public void rtxremix$onNeighbourColumnInvalidated(int cx, int cz) {
        if (viewArea != null) {
            ((BuiltChunkStorageSectionAccess) viewArea).rtxremix$clearNeighbourSnapshotBits(cx, cz);
        }
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void rtxremix$beginRender(CallbackInfo ci) {
        rtxremix$renderScopes.push(ProfileScope.begin("WorldRenderer.render"));
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void rtxremix$endRender(CallbackInfo ci) {
        rtxremix$closeProfilerPhaseForFabricEvent();
        rtxremix$renderScopes.pop().close();
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V"))
    private void rtxremix$profilePhase(ProfilerFiller profiler, String phase, Operation<Void> original) {
        original.call(profiler, phase);
        rtxremix$closeProfilerPhaseForFabricEvent();
        if (ProfileScope.isJavaScopeEnabled()) {
            rtxremix$profilerPhase = ProfileScope.begin("WorldRenderer." + phase);
        }
    }

    @Override
    public void rtxremix$closeProfilerPhaseForFabricEvent() {
        if (rtxremix$profilerPhase != null) {
            rtxremix$profilerPhase.close();
            rtxremix$profilerPhase = null;
        }
    }

    @Inject(method = "compileSections", at = @At("HEAD"))
    private void rtxremix$beginCompile(CameraRenderState camera, CallbackInfo ci) {
        rtxremix$compileScopes.push(ProfileScope.begin("WorldRenderer.updateChunks"));
    }

    @Inject(method = "compileSections", at = @At("RETURN"))
    private void rtxremix$endCompile(CameraRenderState camera, CallbackInfo ci) {
        rtxremix$compileScopes.pop().close();
    }
}
