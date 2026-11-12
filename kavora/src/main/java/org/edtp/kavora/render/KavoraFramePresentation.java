/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.render;

import com.mojang.blaze3d.systems.RenderSystem;
import org.edtp.kavora.profiling.ProfileScope;
import org.edtp.kavora.sdk.PresentInfo;
import org.edtp.kavora.sdk.RemixAPI;
import org.edtp.kavora.sdk.SubmitInfo;
import org.edtp.kavora.sdk.VulkanClientFrameInfo;

/**
 * Render-thread owner of the Submit/Present pair. The early scene hook submits
 * once. Menu, world composition and release can acquire the same frame. If the
 * scene hook was skipped, the first acquire submits the empty scene.
 *
 * <p>Release records Minecraft's image release and timeline signal. The host
 * submits that encoder before calling {@link #present(VulkanClientFrameInfo)}.
 * A direct GameRenderer call closes its frame at render return. It can finish
 * the handoff without a window image, including when a caller renders several
 * views consecutively. The Vulkan surface owns the window image separately.
 */
public final class KavoraFramePresentation implements AutoCloseable {
    private final RemixAPI api;
    private final KavoraInterop images;
    private final long window;
    private final SubmitInfo submit = new SubmitInfo();
    private final PresentInfo present = new PresentInfo();
    private final NativeMenuOverlay menu = new NativeMenuOverlay();
    private boolean submitted;
    private boolean renderingWindowFrame;

    public KavoraFramePresentation(RemixAPI api, KavoraInterop images, long window) {
        this.api = api;
        this.images = images;
        this.window = window;
    }

    /** Seals the scene after capture and makes its producer signal available to the host. */
    public void submitScene() {
        submit.waterSimulation = KavoraWaterRipples.pendingFrame();
        api.submit(submit);
        if (submit.waterSimulation != null) {
            KavoraWaterRipples.submitted();
            submit.waterSimulation = null;
        }
        submitted = true;
    }

    public void acquireImages() {
        if (!submitted) submitScene();
        images.refreshImages();
        images.acquireImages();
    }

    public void compositeMenu() {
        acquireImages();
        menu.render(images.menuTexture());
    }

    public void releaseImages() {
        acquireImages();
        images.releaseImages();
    }

    public void beginWindowRender() {
        renderingWindowFrame = true;
    }

    public void endWindowRender() {
        renderingWindowFrame = false;
    }

    /** Completes a scene whose caller owns no outer window frame. */
    public void finishDirectRender() {
        if (renderingWindowFrame || !submitted) return;
        releaseImages();
        RenderSystem.getDevice().createCommandEncoder().submit();
        present(null);
    }

    public void present(VulkanClientFrameInfo frame) {
        present.hwndOverride = window;
        present.vulkanFrame = frame;
        present.skipVulkanSwapchain = frame == null ? 1 : 0;
        present.clientDoneSemaphore_ValueToWait = images.clientDoneValue();
        try (ProfileScope ignored = ProfileScope.begin("Remix.Present")) {
            api.present(present);
        }
        submitted = false;
    }

    @Override
    public void close() {
        menu.close();
    }
}
