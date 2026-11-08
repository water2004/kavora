/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.edtp.kavora.settings.KavoraSetting;
import org.edtp.kavora.sdk.ErrorCode;
import org.edtp.kavora.sdk.RemixException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class KavoraSettingsFactoryTest {

    private static final String OPTION = "rtx.materials.smoothReliefMapFiltering";

    private final Map<String, String> writes = new HashMap<>();

    @Test
    void aRemixBoolStartsFromTheResolvedValueWithoutWriting() {
        KavoraSetting<Boolean> setting = remixBool(option -> "True");

        assertTrue(setting.get());
        assertTrue(setting.defaultValue());
        assertTrue(writes.isEmpty());
    }

    @Test
    void aRemixBoolReportsItsWriteBeforeRemixResolvesIt() {
        KavoraSetting<Boolean> setting = remixBool(option -> "False");

        setting.apply(true);

        assertTrue(setting.get());
        assertFalse(setting.isDefault());
        assertEquals("true", writes.get(OPTION));
    }

    @Test
    void resettingARemixBoolWritesTheStartupValue() {
        KavoraSetting<Boolean> setting = remixBool(option -> "False");
        setting.apply(true);

        setting.reset();

        assertFalse(setting.get());
        assertEquals("false", writes.get(OPTION));
    }

    @Test
    void aRemixBoolIsLeftOutWhenTheRuntimeCannotResolveTheOption() {
        Optional<KavoraSetting<Boolean>> setting = KavoraSettingsFactory.remixBool("t.bool",
                SettingGroup.MATERIALS, "k", OPTION,
                option -> {
                    throw new RemixException("GetConfigVariableResolved failed for " + option,
                            ErrorCode.GENERAL_FAILURE);
                },
                writes::put);

        assertTrue(setting.isEmpty());
        assertTrue(writes.isEmpty());
    }

    private KavoraSetting<Boolean> remixBool(Function<String, String> configReader) {
        return KavoraSettingsFactory.remixBool("t.bool", SettingGroup.MATERIALS, "k", OPTION,
                configReader, writes::put).orElseThrow();
    }
}
