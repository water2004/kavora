/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora;

import org.edtp.kavora.sky.SkyRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Truth-table coverage for the master/sub-flag "active" predicates.
 * The master flag (rtxEnabled) gates every sub-flag: a subsystem is only
 * "active" when RTX is on AND its own sub-flag is on.
 */
class KavoraToggleStateTest {

    @AfterEach
    void resetFlags() throws Exception {
        setFlag("rtxEnabled", true);
        setFlag("worldEnabled", true);
        setFlag("entitiesEnabled", true);
        setFlag("skyEnabled", true);
        setFlag("blockEntitiesEnabled", true);
        setFlag("particlesEnabled", true);
    }

    @Test
    void everyActivePredicateIsFalseWhenTheMasterToggleIsOff() throws Exception {
        setFlag("rtxEnabled", false);
        // All sub-flags remain true (defaults), but master off => nothing active.
        assertFalse(KavoraMod.isWorldActive(), "world");
        assertFalse(KavoraMod.isEntitiesActive(), "entities");
        assertFalse(KavoraMod.isBlockEntitiesActive(), "blockEntities");
        assertFalse(KavoraMod.isSkyActive(), "sky");
        assertFalse(KavoraMod.isParticlesActive(), "particles");
    }

    @Test
    void anActivePredicateIsTrueWhenTheMasterAndItsSubFlagAreOn() throws Exception {
        setFlag("rtxEnabled", true);
        assertTrue(KavoraMod.isWorldActive(), "world");
        assertTrue(KavoraMod.isEntitiesActive(), "entities");
        assertTrue(KavoraMod.isBlockEntitiesActive(), "blockEntities");
        assertTrue(KavoraMod.isSkyActive(), "sky");
        assertTrue(KavoraMod.isParticlesActive(), "particles");
    }

    @Test
    void eachActivePredicateIsolatesItsOwnSubFlagWhenTheMasterIsOn() throws Exception {
        setFlag("rtxEnabled", true);
        setFlag("entitiesEnabled", false);
        assertTrue(KavoraMod.isRtxEnabled());
        assertFalse(KavoraMod.isEntitiesActive(), "entities off");
        // A different sub-flag is unaffected.
        assertTrue(KavoraMod.isWorldActive(), "world still active");
    }

    @Test
    void enablingWithoutANativeRendererKeepsVanillaSectionBuildsEnabled() throws Exception {
        KavoraRenderer previousRenderer = KavoraMod.renderer;
        try {
            KavoraMod.renderer = null;
            setFlag("rtxEnabled", false);
            KavoraMod.setRtxEnabled(true);
            assertFalse(KavoraMod.isRtxEnabled());
            assertFalse(KavoraMod.isRemixWorldRendering());
        } finally {
            KavoraMod.renderer = previousRenderer;
        }
    }

    @Test
    void settingSkyEnabledKeepsBothGatesInSync() {
        KavoraMod.setSkyEnabled(false);
        assertFalse(KavoraMod.isSkyEnabled());
        assertFalse(SkyRenderer.isEnabled());

        KavoraMod.setSkyEnabled(true);
        assertTrue(KavoraMod.isSkyEnabled());
        assertTrue(SkyRenderer.isEnabled());
    }

    private static void setFlag(String name, boolean value) throws Exception {
        Field f = KavoraMod.class.getDeclaredField(name);
        f.setAccessible(true);
        f.setBoolean(null, value);
    }
}
