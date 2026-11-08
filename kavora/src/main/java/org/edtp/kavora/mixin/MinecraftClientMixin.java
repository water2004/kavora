/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import org.edtp.kavora.profiling.ProfileScope;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import net.minecraft.util.thread.BlockableEventLoop;
import org.edtp.kavora.KavoraRenderer;
import org.edtp.kavora.KavoraMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks into MinecraftClient lifecycle events.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftClientMixin {
    private static final ThreadLocal<ProfileScope> kavora$renderScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> kavora$renderPhaseScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> kavora$tickScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> kavora$tickPhaseScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> kavora$preGamePrepScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> kavora$beginRenderTickScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> kavora$scheduledTasksScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> kavora$soundScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> kavora$displayPrepScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> kavora$mouseScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> kavora$blitScope = new ThreadLocal<>();
    private static final ThreadLocal<Long> kavora$renderStartNanos = new ThreadLocal<>();

    @Inject(method = "runTick", at = @At("HEAD"))
    private void kavora$renderHead(boolean tick, CallbackInfo ci) {
        ProfileScope.beginFrame();
        kavora$renderStartNanos.set(System.nanoTime());
        kavora$openRenderScope();
        kavora$transitionRenderPhase("MinecraftClient.render.preGameRenderer");
        kavora$openScope(kavora$preGamePrepScope, "MinecraftClient.render.preGamePrep");
    }

    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/DeltaTracker$Timer;advanceGameTime(J)I"
        )
    )
    private void kavora$beforeBeginRenderTick(boolean tick, CallbackInfo ci) {
        kavora$closeScope(kavora$preGamePrepScope);
        kavora$openScope(kavora$beginRenderTickScope, "MinecraftClient.render.beginRenderTick");
    }

    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/DeltaTracker$Timer;advanceGameTime(J)I",
            shift = At.Shift.AFTER
        )
    )
    private void kavora$afterBeginRenderTick(boolean tick, CallbackInfo ci) {
        kavora$closeScope(kavora$beginRenderTickScope);
    }

    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;runAllTasks()V"
        )
    )
    private void kavora$beforeRunTasks(boolean tick, CallbackInfo ci) {
        kavora$closeScope(kavora$preGamePrepScope);
        kavora$closeScope(kavora$beginRenderTickScope);
        ProfileScope.beginClientTaskDrain("MinecraftClient", kavora$getTaskCount());
        kavora$openScope(kavora$scheduledTasksScope, "MinecraftClient.render.runTasks");
    }

    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;runAllTasks()V",
            shift = At.Shift.AFTER
        )
    )
    private void kavora$afterRunTasks(boolean tick, CallbackInfo ci) {
        kavora$closeScope(kavora$scheduledTasksScope);
        kavora$closeClientTaskDrain();
    }

    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/sounds/SoundManager;updateSource(Lnet/minecraft/client/Camera;)V"
        )
    )
    private void kavora$beforeSoundListener(boolean tick, CallbackInfo ci) {
        kavora$closeScope(kavora$preGamePrepScope);
        kavora$closeScope(kavora$beginRenderTickScope);
        kavora$closeScope(kavora$scheduledTasksScope);
        kavora$closeClientTaskDrain();
        kavora$openScope(kavora$soundScope, "MinecraftClient.render.soundListener");
    }

    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/sounds/SoundManager;updateSource(Lnet/minecraft/client/Camera;)V",
            shift = At.Shift.AFTER
        )
    )
    private void kavora$afterSoundListener(boolean tick, CallbackInfo ci) {
        kavora$closeScope(kavora$soundScope);
    }

    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuSurface;acquireNextTexture()V"
        )
    )
    private void kavora$beforeDisplayPrep(boolean tick, CallbackInfo ci) {
        kavora$closeScope(kavora$preGamePrepScope);
        kavora$closeScope(kavora$beginRenderTickScope);
        kavora$closeScope(kavora$scheduledTasksScope);
        kavora$closeClientTaskDrain();
        kavora$closeScope(kavora$soundScope);
        kavora$openScope(kavora$displayPrepScope, "MinecraftClient.render.displayPrep");
    }

    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/MouseHandler;handleAccumulatedMovement()V"
        )
    )
    private void kavora$beforeMouseTick(boolean tick, CallbackInfo ci) {
        kavora$openScope(kavora$mouseScope, "MinecraftClient.render.mouseTick");
    }

    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/MouseHandler;handleAccumulatedMovement()V",
            shift = At.Shift.AFTER
        )
    )
    private void kavora$afterMouseTick(boolean tick, CallbackInfo ci) {
        kavora$closeScope(kavora$mouseScope);
    }

    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;update(Lnet/minecraft/client/DeltaTracker;)V"
        )
    )
    private void kavora$beforeGameRenderer(boolean tick, CallbackInfo ci) {
        kavora$closeScope(kavora$preGamePrepScope);
        kavora$closeScope(kavora$beginRenderTickScope);
        kavora$closeScope(kavora$scheduledTasksScope);
        kavora$closeClientTaskDrain();
        kavora$closeScope(kavora$soundScope);
        kavora$closeScope(kavora$mouseScope);
        kavora$closeScope(kavora$displayPrepScope);
        kavora$transitionRenderPhase("MinecraftClient.render.gameRenderer");

        // Apply interactive or benchmark camera poses before GameRenderer.renderWorld
        // constructs Minecraft's interpolated Camera for this frame.
        KavoraRenderer renderer = KavoraMod.renderer;
        if (renderer != null) {
            renderer.onMinecraftWorldRenderFrameStart((Minecraft) (Object) this, tick);
        }
    }

    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;render()V",
            shift = At.Shift.AFTER
        )
    )
    private void kavora$afterGameRenderer(boolean tick, CallbackInfo ci) {
        kavora$transitionRenderPhase("MinecraftClient.render.postGameRenderer");
    }

    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuSurface;blitFromTexture(Lcom/mojang/renderpearl/api/commands/CommandEncoder;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V"
        )
    )
    private void kavora$beforeBlit(boolean tick, CallbackInfo ci) {
        kavora$closeScope(kavora$preGamePrepScope);
        kavora$closeScope(kavora$beginRenderTickScope);
        kavora$closeScope(kavora$scheduledTasksScope);
        kavora$closeClientTaskDrain();
        kavora$closeScope(kavora$soundScope);
        kavora$closeScope(kavora$displayPrepScope);
        kavora$closeScope(kavora$mouseScope);
        kavora$openScope(kavora$blitScope, "MinecraftClient.render.blitFramebuffer");
    }

    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuSurface;blitFromTexture(Lcom/mojang/renderpearl/api/commands/CommandEncoder;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V",
            shift = At.Shift.AFTER
        )
    )
    private void kavora$afterBlit(boolean tick, CallbackInfo ci) {
        kavora$closeScope(kavora$blitScope);
    }

    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V"
        )
    )
    private void kavora$beforeSwapBuffers(boolean tick, CallbackInfo ci) {
        kavora$transitionRenderPhase("MinecraftClient.render.swapBuffers");
    }

    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V",
            shift = At.Shift.AFTER
        )
    )
    private void kavora$afterSwapBuffers(boolean tick, CallbackInfo ci) {
        kavora$transitionRenderPhase("MinecraftClient.render.frameLimit");
    }

    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/FramerateLimiter;limitDisplayFPS(I)V"
        )
    )
    private void kavora$beforeLimitDisplayFps(boolean tick, CallbackInfo ci) {
        kavora$transitionRenderPhase("MinecraftClient.render.limitDisplayFPS");
    }

    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/FramerateLimiter;limitDisplayFPS(I)V",
            shift = At.Shift.AFTER
        )
    )
    private void kavora$afterLimitDisplayFps(boolean tick, CallbackInfo ci) {
        kavora$transitionRenderPhase("MinecraftClient.render.afterFrameLimit");
    }

    // Tracy frames come from the runtime's FrameMark inside Present; a second
    // mark here would split every frame at the present boundary.
    @Inject(method = "runTick", at = @At("RETURN"))
    private void kavora$renderReturn(boolean tick, CallbackInfo ci) {
        kavora$closeRenderChildScopes();
        kavora$closeRenderPhase();
        kavora$closeRenderScope();
        Long startedAt = kavora$renderStartNanos.get();
        if (startedAt != null) {
            kavora$renderStartNanos.remove();
            long fullFrameNanos = System.nanoTime() - startedAt;
            ProfileScope.emitFrameLedger(fullFrameNanos);
            KavoraRenderer renderer = KavoraMod.renderer;
            if (renderer != null) {
                renderer.onMinecraftRenderFrameEnd(fullFrameNanos);
            }
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void kavora$tickHead(CallbackInfo ci) {
        if (ProfileScope.isDeepProfilingEnabled()) {
            kavora$tickScope.set(ProfileScope.begin("MinecraftClient.tick")
                    .plotOnClose("Java.MinecraftClient.tick.ms"));
        }
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Gui;tick()V"
        )
    )
    private void kavora$beforeHudTick(CallbackInfo ci) {
        kavora$openTickPhase("MinecraftClient.tick.hud");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Gui;tick()V",
            shift = At.Shift.AFTER
        )
    )
    private void kavora$afterHudTick(CallbackInfo ci) {
        kavora$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;pick(F)V"
        )
    )
    private void kavora$beforeCrosshairTarget(CallbackInfo ci) {
        kavora$openTickPhase("MinecraftClient.tick.crosshairTarget");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;pick(F)V",
            shift = At.Shift.AFTER
        )
    )
    private void kavora$afterCrosshairTarget(CallbackInfo ci) {
        kavora$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;tick()V"
        )
    )
    private void kavora$beforeInteractionTick(CallbackInfo ci) {
        kavora$openTickPhase("MinecraftClient.tick.interactionManager");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;tick()V",
            shift = At.Shift.AFTER
        )
    )
    private void kavora$afterInteractionTick(CallbackInfo ci) {
        kavora$closeTickPhase();
    }

    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/TextureManager;tick()V"
        )
    )
    private void kavora$beforeTextureTick(CallbackInfo ci) {
        kavora$openTickPhase("MinecraftClient.tick.textures");
    }

    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/TextureManager;tick()V",
            shift = At.Shift.AFTER
        )
    )
    private void kavora$afterTextureTick(CallbackInfo ci) {
        kavora$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;handleKeybinds()V"
        )
    )
    private void kavora$beforeInputEvents(CallbackInfo ci) {
        kavora$openTickPhase("MinecraftClient.tick.inputEvents");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;handleKeybinds()V",
            shift = At.Shift.AFTER
        )
    )
    private void kavora$afterInputEvents(CallbackInfo ci) {
        kavora$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;tick()V"
        )
    )
    private void kavora$beforeGameRendererTick(CallbackInfo ci) {
        kavora$openTickPhase("MinecraftClient.tick.gameRenderer");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;tick()V",
            shift = At.Shift.AFTER
        )
    )
    private void kavora$afterGameRendererTick(CallbackInfo ci) {
        kavora$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;tickBlockEntities()V"
        )
    )
    private void kavora$beforeWorldRendererTick(CallbackInfo ci) {
        kavora$openTickPhase("MinecraftClient.tick.worldBlockEntities");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;tickBlockEntities()V",
            shift = At.Shift.AFTER
        )
    )
    private void kavora$afterWorldRendererTick(CallbackInfo ci) {
        kavora$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;tickEntities()V"
        )
    )
    private void kavora$beforeWorldEntitiesTick(CallbackInfo ci) {
        kavora$openTickPhase("MinecraftClient.tick.worldEntities");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;tickEntities()V",
            shift = At.Shift.AFTER
        )
    )
    private void kavora$afterWorldEntitiesTick(CallbackInfo ci) {
        kavora$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;tick(Ljava/util/function/BooleanSupplier;)V"
        )
    )
    private void kavora$beforeWorldTick(CallbackInfo ci) {
        kavora$openTickPhase("MinecraftClient.tick.world");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;tick(Ljava/util/function/BooleanSupplier;)V",
            shift = At.Shift.AFTER
        )
    )
    private void kavora$afterWorldTick(CallbackInfo ci) {
        kavora$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;animateTick(III)V"
        )
    )
    private void kavora$beforeRandomBlockDisplayTicks(CallbackInfo ci) {
        kavora$openTickPhase("MinecraftClient.tick.randomBlockDisplay");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;animateTick(III)V",
            shift = At.Shift.AFTER
        )
    )
    private void kavora$afterRandomBlockDisplayTicks(CallbackInfo ci) {
        kavora$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/particle/ParticleEngine;tick()V"
        )
    )
    private void kavora$beforeParticleManagerTick(CallbackInfo ci) {
        kavora$openTickPhase("MinecraftClient.tick.particles");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/particle/ParticleEngine;tick()V",
            shift = At.Shift.AFTER
        )
    )
    private void kavora$afterParticleManagerTick(CallbackInfo ci) {
        kavora$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/sounds/SoundManager;tick(Z)V"
        )
    )
    private void kavora$beforeSoundTick(CallbackInfo ci) {
        kavora$openTickPhase("MinecraftClient.tick.sound");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/sounds/SoundManager;tick(Z)V",
            shift = At.Shift.AFTER
        )
    )
    private void kavora$afterSoundTick(CallbackInfo ci) {
        kavora$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/KeyboardHandler;tick()V"
        )
    )
    private void kavora$beforeKeyboardTick(CallbackInfo ci) {
        kavora$openTickPhase("MinecraftClient.tick.keyboard");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/KeyboardHandler;tick()V",
            shift = At.Shift.AFTER
        )
    )
    private void kavora$afterKeyboardTick(CallbackInfo ci) {
        kavora$closeTickPhase();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void kavora$tickReturn(CallbackInfo ci) {
        kavora$closeTickPhase();
        ProfileScope scope = kavora$tickScope.get();
        if (scope != null) {
            kavora$tickScope.remove();
            scope.close();
        }
    }

    @WrapMethod(method = "close")
    private void kavora$close(Operation<Void> original) {
        try {
            original.call();
        } finally {
            KavoraMod.shutdownAfterMinecraftClose();
        }
    }
    
    private static void kavora$transitionRenderPhase(String phaseName) {
        kavora$closeRenderPhase();
        // The phases partition MinecraftClient.render into consecutive spans that
        // give the frame a top-level Tracy skeleton. Injected child scopes close
        // before their phase transitions; when a nested render (world join or
        // disconnect from inside tick) closes a phase under an open child,
        // ProfileScope defers the Tracy end so the zones stay well-formed.
        if (ProfileScope.isJavaScopeEnabled()) {
            kavora$renderPhaseScope.set(ProfileScope.begin(phaseName)
                    .plotOnClose("Java." + phaseName + ".ms"));
        }
    }

    private static void kavora$openRenderScope() {
        kavora$closeRenderScope();
        if (ProfileScope.isDeepProfilingEnabled()) {
            kavora$renderScope.set(ProfileScope.beginJfrOnly("MinecraftClient.render")
                    .plotOnClose("Java.MinecraftClient.render.ms"));
        }
    }

    private static void kavora$closeRenderScope() {
        ProfileScope scope = kavora$renderScope.get();
        if (scope != null) {
            kavora$renderScope.remove();
            scope.close();
        }
    }

    private static void kavora$closeRenderPhase() {
        ProfileScope scope = kavora$renderPhaseScope.get();
        if (scope != null) {
            kavora$renderPhaseScope.remove();
            scope.close();
        }
    }

    private static void kavora$openTickPhase(String phaseName) {
        kavora$closeTickPhase();
        if (ProfileScope.isDeepProfilingEnabled()) {
            kavora$tickPhaseScope.set(ProfileScope.begin(phaseName)
                    .plotOnClose("Java." + phaseName + ".ms"));
        }
    }

    private static void kavora$closeTickPhase() {
        ProfileScope scope = kavora$tickPhaseScope.get();
        if (scope != null) {
            kavora$tickPhaseScope.remove();
            scope.close();
        }
    }

    private static void kavora$openScope(ThreadLocal<ProfileScope> holder, String phaseName) {
        kavora$closeScope(holder);
        // Guard the begin() call and the plot-name concatenation: these run
        // every frame for each render sub-phase, so skipping them when no Java
        // scope is enabled avoids per-frame string allocation on the hot path.
        if (ProfileScope.isJavaScopeEnabled()) {
            holder.set(ProfileScope.begin(phaseName)
                    .plotOnClose("Java." + phaseName + ".ms"));
        }
    }

    private static void kavora$closeScope(ThreadLocal<ProfileScope> holder) {
        ProfileScope scope = holder.get();
        if (scope != null) {
            holder.remove();
            scope.close();
        }
    }

    private static void kavora$closeRenderChildScopes() {
        kavora$closeScope(kavora$preGamePrepScope);
        kavora$closeScope(kavora$beginRenderTickScope);
        kavora$closeScope(kavora$scheduledTasksScope);
        kavora$closeClientTaskDrain();
        kavora$closeScope(kavora$soundScope);
        kavora$closeScope(kavora$displayPrepScope);
        kavora$closeScope(kavora$mouseScope);
        kavora$closeScope(kavora$blitScope);
    }

    private int kavora$getTaskCount() {
        // O(n) in the queued task backlog; skip it when nothing records the value.
        if (!ProfileScope.isClientTaskProfilingEnabled()) {
            return 0;
        }
        return ((BlockableEventLoop<?>)(Object)this).getPendingTasksCount();
    }

    private static void kavora$closeClientTaskDrain() {
        Minecraft client = Minecraft.getInstance();
        int taskCount = 0;
        if (client != null && ProfileScope.isClientTaskProfilingEnabled()) {
            taskCount = ((BlockableEventLoop<?>)client).getPendingTasksCount();
        }
        ProfileScope.endClientTaskDrain(taskCount);
    }
}
