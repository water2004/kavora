/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.gametest;

/** Test-only frame counters, with failures also published by section workers. */
public final class KavoraFrameProbe {
    public static long submits;
    public static long presents;
    public static volatile long failures;
    public static long playerTextureBuilds;
    public static java.util.function.LongConsumer frameAction;
    public static Runnable afterNativeMenus;

    private KavoraFrameProbe() {}
}
