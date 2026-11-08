/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import org.edtp.kavora.profiling.ProfileScope;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brackets each {@code ThreadExecutor} task with a profiling scope so the work a
 * client task drain performs is attributed to the task rather than to the frame
 * around it. Runs on whichever thread owns the executor, and does nothing unless
 * a client task drain is already open on that thread.
 */
@Mixin(BlockableEventLoop.class)
public abstract class ThreadExecutorMixin {
    @Shadow
    public abstract String name();

    @Shadow
    public abstract int getPendingTasksCount();

    @Inject(method = "doRunTask", at = @At("HEAD"))
    private void kavora$beforeExecuteTask(Runnable task, CallbackInfo ci) {
        // This fires for every task on every ThreadExecutor, the integrated
        // server's included. getTaskCount() is ConcurrentLinkedQueue.size(), so
        // it walks the whole backlog: computing it for a hook that is about to
        // return makes draining n tasks O(n^2) for nothing. Ask first.
        if (!ProfileScope.isClientTaskDrainActive()) {
            return;
        }
        ProfileScope.beginThreadExecutorTask(name(), getPendingTasksCount(), task);
    }

    @Inject(method = "doRunTask", at = @At("RETURN"))
    private void kavora$afterExecuteTask(Runnable task, CallbackInfo ci) {
        ProfileScope.endThreadExecutorTask();
    }
}
