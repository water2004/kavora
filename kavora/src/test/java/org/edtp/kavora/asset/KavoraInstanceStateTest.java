/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.asset;

import org.edtp.kavora.sdk.InstanceInfoBlendEXT;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KavoraInstanceStateTest {

    @Test
    void meshChangesDirtyTheRetainedDescription() throws Exception {
        KavoraMesh firstMesh = new KavoraMesh(new KavoraMeshSurface[0]);
        KavoraMesh secondMesh = new KavoraMesh(new KavoraMeshSurface[0]);
        KavoraInstance instance = new KavoraInstance(firstMesh);

        setDirty(instance, false);
        instance.setMesh(firstMesh);
        assertFalse(isDirty(instance));

        instance.setMesh(secondMesh);
        assertTrue(isDirty(instance));
        assertSame(secondMesh, instance.getMesh());
    }

    @Test
    void unchangedImmutablePropertiesDoNotTriggerNativeUpdates() throws Exception {
        KavoraInstance instance = new KavoraInstance(new KavoraMesh(new KavoraMeshSurface[0]));
        InstanceInfoBlendEXT blend = new InstanceInfoBlendEXT();

        instance.setCategoryFlags(5).setBlendExt(blend).setDoubleSided(true);
        setDirty(instance, false);

        instance.setCategoryFlags(5).setBlendExt(blend).setDoubleSided(true);
        assertFalse(isDirty(instance));

        instance.setCategoryFlags(6);
        assertTrue(isDirty(instance));
    }

    private static boolean isDirty(KavoraInstance instance) throws Exception {
        Field field = KavoraInstance.class.getDeclaredField("sceneInfoDirty");
        field.setAccessible(true);
        return field.getBoolean(instance);
    }

    private static void setDirty(KavoraInstance instance, boolean value) throws Exception {
        Field field = KavoraInstance.class.getDeclaredField("sceneInfoDirty");
        field.setAccessible(true);
        field.setBoolean(instance, value);
    }
}
