/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.gametest;

import org.edtp.kavora.KavoraMod;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** A diagnostic user-layer option restored when its scope ends. */
record RuntimeOptionScope(ClientGameTestContext context, String key, String original) implements AutoCloseable {
    static RuntimeOptionScope acquire(ClientGameTestContext context, String key, String value) {
        String original = context.computeOnClient(client -> read(key));
        try {
            apply(context, Map.of(key, value));
            return new RuntimeOptionScope(context, key, original);
        } catch (RuntimeException | Error failure) {
            apply(context, Map.of(key, original));
            throw failure;
        }
    }

    static void apply(ClientGameTestContext context, Map<String, String> values) {
        context.runOnClient(client -> values.forEach((key, value) ->
                KavoraMod.renderer.getRemixApi().setConfigVariable(key, value)));
        try {
            context.waitFor(client -> mismatches(values).isEmpty(), 200);
        } catch (AssertionError timeout) {
            throw new AssertionError("Diagnostic user options did not resolve: "
                    + context.computeOnClient(client -> mismatches(values)), timeout);
        }
    }

    static Map<String, String> mismatches(Map<String, String> expected) {
        Map<String, String> mismatch = new LinkedHashMap<>();
        expected.forEach((key, value) -> {
            String actual = read(key);
            if (!matches(actual, value)) mismatch.put(key, "expected " + value + ", resolved " + actual);
        });
        return mismatch;
    }

    private static boolean matches(String actual, String expected) {
        String[] left = actual.strip().split("\\s*,\\s*");
        String[] right = expected.strip().split("\\s*,\\s*");
        if (left.length != right.length) return false;
        for (int i = 0; i < left.length; i++) {
            if (left[i].equalsIgnoreCase(right[i])) continue;
            try {
                if (!(Math.abs(Double.parseDouble(left[i]) - Double.parseDouble(right[i])) < .0001)) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private static String read(String key) { return KavoraMod.renderer.getRemixApi().getConfigVariableResolved(key); }

    @Override
    public void close() { apply(context, Map.of(key, original)); }
}
