/* SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.shaderc.Shaderc;
import static org.junit.jupiter.api.Assertions.*;

/** The shipped shader must compile with Vulkan semantics without a graphics device. */
class AtmosphericSkyShaderTest {
    @Test void packagedAtmosphereCompilesAsVulkanSpirv() throws Exception {
        String source;
        try (var stream = getClass().getResourceAsStream("/assets/rtx-remix-renderer/shaders/sky_atmosphere.comp")) {
            assertNotNull(stream);
            source = AtmosphericSkyGenerator.withSkyDimensions(
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8), 256, 128);
        }
        long compiler = Shaderc.shaderc_compiler_initialize();
        assertNotEquals(0, compiler);
        long result = 0;
        try {
            result = Shaderc.shaderc_compile_into_spv(compiler, source, Shaderc.shaderc_compute_shader,
                    "sky_atmosphere.comp", "main", 0);
            assertNotEquals(0, result);
            assertEquals(Shaderc.shaderc_compilation_status_success,
                    Shaderc.shaderc_result_get_compilation_status(result), Shaderc.shaderc_result_get_error_message(result));
        } finally {
            if (result != 0) Shaderc.shaderc_result_release(result);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }
}
