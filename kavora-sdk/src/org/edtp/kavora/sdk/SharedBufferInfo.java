/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.sdk;

/** Runtime-owned write-once GPU memory and its producer timeline. Never close the Win32 handles. */
public final class SharedBufferInfo {
    public long size;
    public long buffer;
    public long memoryHandle;
    public long allocationSize;
    public long bindOffset;
    public long producerSemaphoreHandle;
    public long readyValue;
}
