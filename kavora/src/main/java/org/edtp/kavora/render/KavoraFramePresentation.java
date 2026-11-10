/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.render;

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
 * A null window frame still completes the shared-image handoff. The Vulkan
 * surface owns the window swapchain and its independent source-image lifetime.
 */
public final class KavoraFramePresentation implements AutoCloseable {
    private final RemixAPI api;
    private final KavoraInterop images;
    private final long window;
    private final SubmitInfo submit = new SubmitInfo();
    private final PresentInfo present = new PresentInfo();
    private final NativeMenuOverlay menu = new NativeMenuOverlay();
    private boolean submitted;

    public KavoraFramePresentation(RemixAPI api, KavoraInterop images, long window) {
        this.api = api;
        this.images = images;
        this.window = window;
    }

    /** Seals the scene after capture and makes its producer signal available to the host. */
    public void submitScene() {
        api.submit(submit);
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

    public void present(VulkanClientFrameInfo frame) {
        present.hwndOverride = window;
        present.vulkanFrame = frame;
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
