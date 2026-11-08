/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.sdk;

import java.nio.file.Files;
import java.nio.file.Path;

/** Isolates a mock runtime whose unload terminates its process, just as Tracy does on Windows. */
public final class RemixRuntimeLifecycleTest {
    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("--child")) {
            for (int session = 0; session < 3; session++) {
                if (RemixBridge.initialize(args[1]) != ErrorCode.SUCCESS) throw new AssertionError("Reinitialize failed");
                if (RemixBridge.shutdown() != ErrorCode.SUCCESS) throw new AssertionError("Shutdown failed");
            }
            Files.writeString(Path.of(args[2]), "completed all sessions");
            return;
        }
        Path marker = Files.createTempFile("runtime-lifecycle-", ".txt");
        try {
            Process child = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                    "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
                    RemixRuntimeLifecycleTest.class.getName(), "--child", args[0], marker.toString())
                    .inheritIO().start();
            int status = child.waitFor();
            if (status != 0 || !Files.readString(marker).equals("completed all sessions"))
                throw new AssertionError("Runtime unload terminated the child during shutdown/reinitialize; exit=" + status);
            System.out.println("Verified runtime DLL remains loaded across three shutdown/reinitialize sessions");
        } finally {
            Files.deleteIfExists(marker);
        }
    }
}
