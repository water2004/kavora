/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.asset;

/**
 * Lightweight record pushed by the Cleaner thread when a leaked asset is
 * detected. The owning store drains these during its {@code gc()} sweep.
 */
public record LeakRecord(long hash, long handle) {}
