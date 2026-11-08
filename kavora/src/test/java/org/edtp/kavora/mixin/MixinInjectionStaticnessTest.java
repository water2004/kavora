/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Static vanilla helpers require static injector callbacks, including synthetic lambda methods. */
class MixinInjectionStaticnessTest {
    @Test
    void callbacksTargetingStaticMinecraftMethodsAreStatic() throws IOException {
        List<String> failures = new ArrayList<>();
        try (var stream = getClass().getResourceAsStream("/kavora.mixins.json")) {
            assertNotNull(stream);
            var config = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            String namespace = config.get("package").getAsString().replace('.', '/');
            for (String group : List.of("mixins", "client")) {
                for (var entry : config.getAsJsonArray(group)) {
                    ClassNode mixin = readClass(namespace + "/" + entry.getAsString());
                    for (String targetName : targets(mixin)) {
                        ClassNode target = readClass(targetName);
                        for (var callback : mixin.methods) {
                            for (var annotation : annotations(callback.visibleAnnotations, callback.invisibleAnnotations)) {
                                if (!isInjector(annotation.desc)) continue;
                                for (String selector : strings(attribute(annotation, "method"))) {
                                    int descriptorStart = selector.indexOf('(');
                                    String methodName = descriptorStart < 0 ? selector : selector.substring(0, descriptorStart);
                                    String descriptor = descriptorStart < 0 ? null : selector.substring(descriptorStart);
                                    for (var method : target.methods) {
                                        if (!method.name.equals(methodName) || descriptor != null && !method.desc.equals(descriptor)) continue;
                                        if ((method.access & Opcodes.ACC_STATIC) != 0 && (callback.access & Opcodes.ACC_STATIC) == 0) {
                                            failures.add(mixin.name + "." + callback.name + " targets static "
                                                    + targetName + "." + method.name + method.desc);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), failures, "Mixin rejects instance callbacks into static methods before world rendering starts");
    }

    private ClassNode readClass(String name) throws IOException {
        try (var stream = getClass().getResourceAsStream("/" + name + ".class")) {
            assertNotNull(stream, name);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static List<String> targets(ClassNode mixin) {
        List<String> targets = new ArrayList<>();
        for (var annotation : annotations(mixin.visibleAnnotations, mixin.invisibleAnnotations)) {
            if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) continue;
            Object values = attribute(annotation, "value");
            if (values instanceof List<?> types) {
                for (Object type : types) targets.add(((Type) type).getInternalName());
            }
            for (String name : strings(attribute(annotation, "targets"))) targets.add(name.replace('.', '/'));
        }
        return targets;
    }

    private static boolean isInjector(String descriptor) {
        return descriptor.startsWith("Lorg/spongepowered/asm/mixin/injection/")
                || descriptor.startsWith("Lcom/llamalad7/mixinextras/injector/");
    }

    private static Object attribute(AnnotationNode annotation, String key) {
        if (annotation.values != null) {
            for (int i = 0; i < annotation.values.size(); i += 2) {
                if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
            }
        }
        return null;
    }

    private static List<AnnotationNode> annotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        List<AnnotationNode> result = new ArrayList<>();
        if (visible != null) result.addAll(visible);
        if (invisible != null) result.addAll(invisible);
        return result;
    }

    private static List<String> strings(Object value) {
        if (value instanceof String string) return List.of(string);
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().map(String.class::cast).toList();
    }
}
