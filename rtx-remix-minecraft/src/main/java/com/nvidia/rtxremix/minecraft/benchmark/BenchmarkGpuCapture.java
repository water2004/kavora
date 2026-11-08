/* SPDX-License-Identifier: Apache-2.0 */
package com.nvidia.rtxremix.minecraft.benchmark;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/** Loads an explicitly supplied development bridge to the installed Nsight Graphics SDK. */
final class BenchmarkGpuCapture {
    private final Path bridge;
    private final int frame;
    private boolean started;
    private MethodHandle start;

    static BenchmarkGpuCapture fromProperties() {
        String library = System.getProperty("rtxremix.benchmark.nsightBridge");
        if (library == null) return null;
        int frame = Integer.parseInt(System.getProperty("rtxremix.benchmark.nsightCaptureFrame", "300"));
        if (frame < 0) throw new IllegalArgumentException("Nsight capture frame must be nonnegative");
        Path bridge = Path.of(library);
        if (!bridge.isAbsolute()) throw new IllegalArgumentException("Nsight bridge path must be absolute");
        return new BenchmarkGpuCapture(bridge, frame);
    }

    private BenchmarkGpuCapture(Path bridge, int frame) {
        this.bridge = bridge;
        this.frame = frame;
    }

    void prepare() {
        if (start != null) return;
        // The SDK retains function pointers and activity state for the process lifetime.
        var library = SymbolLookup.libraryLookup(bridge, Arena.global());
        var initialize = Linker.nativeLinker().downcallHandle(
                library.find("rtx_profile_prepare_trace").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
        invoke(initialize);
        start = Linker.nativeLinker().downcallHandle(
                library.find("rtx_profile_start_trace").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
    }

    boolean startAt(int currentFrame) {
        if (started || currentFrame < frame) return false;
        if (currentFrame != frame) throw new IllegalStateException("Missed the requested Nsight benchmark frame");
        if (start == null) throw new IllegalStateException("Nsight bridge was not prepared before capture");
        invoke(start);
        started = true;
        return true;
    }

    private static void invoke(MethodHandle function) {
        final int result;
        try {
            result = (int) function.invokeExact();
        } catch (Throwable failure) {
            throw new IllegalStateException("Nsight GPU Trace SDK call failed", failure);
        }
        if (result != 0) throw new IllegalStateException("Nsight GPU Trace SDK returned " + result);
    }
}
