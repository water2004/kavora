/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(LevelExtractor.class)
public interface LevelExtractorSectionAccessor {
    @Accessor("sectionUpdateTracker") SectionUpdateTracker kavora$sectionUpdateTracker();
}
