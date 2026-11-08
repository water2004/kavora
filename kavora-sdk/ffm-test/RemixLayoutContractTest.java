/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.sdk;

import java.nio.file.Files;
import java.nio.file.Path;

/** Compares every structure and field with the compiler that builds the Windows ABI. */
public final class RemixLayoutContractTest {
    public static void main(String[] args) throws Exception {
        int checked = 0;
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            String[] parts = line.trim().split("\\s+");
            long expected = Long.parseLong(parts[2]);
            long actual = switch (parts[1]) {
                case "sizeof" -> RemixLayouts.size(parts[0]);
                case "alignof" -> RemixLayouts.layout(parts[0]).byteAlignment();
                default -> RemixLayouts.offset(parts[0], parts[1]);
            };
            if (actual != expected) {
                throw new AssertionError(parts[0] + "." + parts[1]
                        + ": Java=" + actual + ", MSVC=" + expected);
            }
            checked++;
        }
        if (RemixLayouts.size("Interface") != 44 * Long.BYTES) {
            throw new AssertionError("The pinned interface must contain exactly 44 function slots");
        }
        System.out.println("Verified " + checked + " C ABI size, alignment, and field-offset contracts");
    }
}

