/* SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.gbwater.rtxremix.horizons;

/** Render-thread accounting includes the unuploaded tail of every admitted section. */
final class TerrainResidencyBudget {
    private final long limit, steadyLimit;
    private long resident, promised;

    TerrainResidencyBudget(long limit) {
        this(limit, 0);
    }

    TerrainResidencyBudget(long limit, long replacementHeadroom) {
        if (limit < 0 || replacementHeadroom < 0 || replacementHeadroom > limit)
            throw new IllegalArgumentException("Invalid terrain budget");
        this.limit = limit;
        steadyLimit = limit - replacementHeadroom;
    }

    /**
     * Both the temporary overlap and the complete replacement must fit; unrelated nodes retain
     * their quality.
     */
    Reservation reserveTransition(long bytes, long replacedBytes) {
        if (bytes < 0 || replacedBytes < 0 || replacedBytes > resident)
            throw new IllegalArgumentException("Invalid terrain transition");
        if (bytes > steadyLimit - resident - promised + replacedBytes) return null;
        return reserveWithin(bytes, limit);
    }

    private Reservation reserveWithin(long bytes, long ceiling) {
        if (bytes > ceiling - resident - promised) return null;
        promised += bytes;
        return new Reservation(bytes);
    }

    long residentBytes() {
        return resident;
    }

    long promisedBytes() {
        return promised;
    }

    void retire(long bytes) {
        if (bytes < 0 || bytes > resident)
            throw new IllegalStateException("Invalid terrain retirement");
        resident -= bytes;
    }

    final class Reservation implements AutoCloseable {
        private long remaining;
        private boolean closed;

        private Reservation(long bytes) {
            remaining = bytes;
        }

        void uploaded(long bytes) {
            if (closed || bytes < 0 || bytes > remaining)
                throw new IllegalStateException("Terrain upload exceeds its reservation");
            remaining -= bytes;
            promised -= bytes;
            resident += bytes;
        }

        @Override
        public void close() {
            if (closed) return;
            promised -= remaining;
            remaining = 0;
            closed = true;
        }
    }
}
