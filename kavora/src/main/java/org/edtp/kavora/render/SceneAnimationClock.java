/* SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0 */
package org.edtp.kavora.render;

/** The client world's simulation ticks, independent of routine server time corrections. */
public interface SceneAnimationClock {
    long kavora$animationTicks();
}
