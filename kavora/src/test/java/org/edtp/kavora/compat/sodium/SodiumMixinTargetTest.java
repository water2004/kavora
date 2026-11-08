/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.compat.sodium;

import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MethodInsnNode;
import static org.junit.jupiter.api.Assertions.*;

/** Checks the optional adapter against the pinned upstream binary without initializing its renderer. */
class SodiumMixinTargetTest {
    @Test
    void configuredOptionalMixinTargetsExistInTheOfficialSodiumBinary() throws Exception {
        try (InputStream config = getClass().getResourceAsStream("/kavora-sodium.mixins.json")) {
            assertNotNull(config);
            var json = JsonParser.parseReader(new InputStreamReader(config, StandardCharsets.UTF_8)).getAsJsonObject();
            String prefix = json.get("package").getAsString().replace('.', '/') + "/";
            for (var entry : json.getAsJsonArray("client")) {
                ClassNode mixin = read(prefix + entry.getAsString());
                var targetAnnotation = annotations(mixin.visibleAnnotations, mixin.invisibleAnnotations).stream()
                        .filter(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;"))
                        .findFirst().orElseThrow();
                @SuppressWarnings("unchecked")
                List<Type> targets = (List<Type>) value(targetAnnotation, "value");
                assertNotNull(targets, mixin.name);
                for (Type type : targets) audit(mixin, read(type.getInternalName()));
            }
        }
    }

    private static void audit(ClassNode mixin, ClassNode target) throws Exception {
        for (MethodNode handler : mixin.methods) {
            for (AnnotationNode annotation : annotations(handler.visibleAnnotations, handler.invisibleAnnotations)) {
                Object selectors = value(annotation, "method");
                if (selectors instanceof List<?> list) {
                    for (Object selector : list) {
                        String name = (String) selector;
                        var matches = target.methods.stream().filter(method -> name.contains("(")
                                ? name.equals(method.name + method.desc) : name.equals(method.name)).toList();
                        assertFalse(matches.isEmpty(), mixin.name + ": missing " + target.name + "." + name);
                        Object at = value(annotation, "at");
                        if (at instanceof AnnotationNode point) auditInvocation(mixin, point, matches);
                        else if (at instanceof List<?> points) for (Object point : points)
                            if (point instanceof AnnotationNode node) auditInvocation(mixin, node, matches);
                    }
                }
                if (annotation.desc.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;")) {
                    String field = (String) value(annotation, "value");
                    assertTrue(target.fields.stream().anyMatch(candidate -> candidate.name.equals(field)),
                            mixin.name + ": missing " + target.name + "." + field);
                }
            }
        }
    }

    private static void auditInvocation(ClassNode mixin, AnnotationNode at, List<MethodNode> methods) {
        if (!"INVOKE".equals(value(at, "value"))) return;
        String target = (String) value(at, "target");
        if (target == null) return;
        for (MethodNode method : methods) for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && target.equals("L" + call.owner + ";" + call.name + call.desc)) return;
        }
        fail(mixin.name + ": invocation not found " + target);
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values == null) return null;
        for (int i = 0; i < annotation.values.size(); i += 2)
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        return null;
    }

    private static List<AnnotationNode> annotations(List<AnnotationNode> first, List<AnnotationNode> second) {
        ArrayList<AnnotationNode> all = new ArrayList<>();
        if (first != null) all.addAll(first);
        if (second != null) all.addAll(second);
        return all;
    }

    private static ClassNode read(String name) throws Exception {
        try (InputStream source = SodiumMixinTargetTest.class.getResourceAsStream("/" + name + ".class")) {
            assertNotNull(source, name);
            ClassNode node = new ClassNode();
            new ClassReader(source).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }
}
