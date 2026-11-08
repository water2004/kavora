/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora;

import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Holds world entry while the asynchronous runtime prepares its first ray-traced frame. */
public final class KavoraPreparationScreen extends GenericMessageScreen {
    private static final long TIMEOUT_NANOS = 120_000_000_000L;
    private final Screen resume;
    private final net.minecraft.client.multiplayer.ClientLevel world = net.minecraft.client.Minecraft.getInstance().level;
    private final long started = System.nanoTime();
    private boolean timedOut;

    public KavoraPreparationScreen(Screen resume) {
        super(Component.translatable("kavora.preparingRendering"));
        this.resume = resume;
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void tick() {
        if (this.minecraft.level != world) {
            this.minecraft.gui.setScreen(null);
            return;
        }
        KavoraRenderer renderer = KavoraMod.renderer;
        if (renderer == null || !renderer.isInitialized() || !KavoraMod.isRtxEnabled() || renderer.isSceneReady()) {
            this.minecraft.gui.setScreen(resume);
        } else if (!timedOut && System.nanoTime() - started >= TIMEOUT_NANOS) {
            timedOut = true;
            this.minecraft.execute(() -> {
                if (this.minecraft.gui.screen() == this && this.minecraft.level == world) {
                    this.minecraft.disconnectFromWorld(Component.translatable("kavora.preparingRenderingTimedOut"));
                }
            });
        }
    }
}
