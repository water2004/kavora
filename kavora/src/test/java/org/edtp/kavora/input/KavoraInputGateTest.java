/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.input;

import org.junit.jupiter.api.Test;
import com.mojang.blaze3d.platform.InputConstants;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.edtp.kavora.input.KavoraInputGate.KeyDecision.BLOCK;
import static org.edtp.kavora.input.KavoraInputGate.KeyDecision.PASS;
import static org.edtp.kavora.input.KavoraInputGate.KeyDecision.TOGGLE_MENU;
import static org.edtp.kavora.input.KavoraInputGate.VisibilityChange.CLOSED;
import static org.edtp.kavora.input.KavoraInputGate.VisibilityChange.NONE;
import static org.edtp.kavora.input.KavoraInputGate.VisibilityChange.OPENED;
import static org.edtp.kavora.input.KavoraInputGate.VisibilityChange.OWN_WRITE_OVERDUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KavoraInputGateTest {

    @Test
    void altXOpensWheneverCaptureIsClosed() {
        KavoraInputGate gate = new KavoraInputGate();

        assertSame(TOGGLE_MENU, key(gate, InputConstants.KEY_X, InputConstants.PRESS, true));
        assertSame(PASS, key(gate, InputConstants.KEY_X, InputConstants.REPEAT, true));
        assertSame(PASS, key(gate, InputConstants.KEY_X, InputConstants.RELEASE, true));
        assertSame(PASS, key(gate, InputConstants.KEY_X, InputConstants.PRESS, false));
        assertSame(PASS, key(gate, InputConstants.KEY_W, InputConstants.PRESS, true));
    }

    @Test
    void openSessionBlocksEveryOtherKeyboardEventAndAllCharacters() {
        KavoraInputGate gate = new KavoraInputGate();
        open(gate);

        assertSame(BLOCK, key(gate, InputConstants.KEY_W, InputConstants.PRESS, false));
        assertSame(BLOCK, key(gate, InputConstants.KEY_W, InputConstants.PRESS, true));
        assertSame(BLOCK, key(gate, InputConstants.KEY_W, InputConstants.REPEAT, false));
        assertSame(BLOCK, key(gate, InputConstants.KEY_W, InputConstants.RELEASE, false));
        assertSame(BLOCK, key(gate, InputConstants.KEY_ESCAPE, InputConstants.PRESS, false));
        assertTrue(gate.isCaptureSessionOpen());
    }

    @Test
    void altXClosesWheneverCaptureIsOpen() {
        KavoraInputGate gate = new KavoraInputGate();
        open(gate);

        assertSame(TOGGLE_MENU, key(gate, InputConstants.KEY_X, InputConstants.PRESS, true));
        assertSame(BLOCK, key(gate, InputConstants.KEY_X, InputConstants.REPEAT, true));
        assertSame(BLOCK, key(gate, InputConstants.KEY_X, InputConstants.RELEASE, true));
        assertSame(BLOCK, key(gate, InputConstants.KEY_X, InputConstants.PRESS, false));
    }

    @Test
    void controlAltXDoesNotToggle() {
        KavoraInputGate gate = new KavoraInputGate();

        assertSame(PASS, key(gate, InputConstants.KEY_X, InputConstants.PRESS, true, true));
        assertSame(PASS, key(gate, InputConstants.KEY_X, InputConstants.PRESS, false, true));
        assertSame(PASS, key(gate, InputConstants.KEY_X, InputConstants.REPEAT, true, true));
        assertSame(PASS, key(gate, InputConstants.KEY_X, InputConstants.RELEASE, true, true));

        open(gate);

        assertSame(BLOCK, key(gate, InputConstants.KEY_X, InputConstants.PRESS, true, true));
        assertSame(BLOCK, key(gate, InputConstants.KEY_X, InputConstants.PRESS, false, true));
        assertSame(BLOCK, key(gate, InputConstants.KEY_X, InputConstants.REPEAT, true, true));
        assertSame(BLOCK, key(gate, InputConstants.KEY_X, InputConstants.RELEASE, true, true));
        assertTrue(gate.isCaptureSessionOpen());
    }

    @Test
    void closingReturnsHeldKeyEventsToMinecraftImmediately() {
        KavoraInputGate gate = new KavoraInputGate();
        assertSame(PASS, key(gate, InputConstants.KEY_W, InputConstants.PRESS, false));
        open(gate);
        assertSame(BLOCK, key(gate, InputConstants.KEY_W, InputConstants.REPEAT, false));

        close(gate);

        assertSame(PASS, key(gate, InputConstants.KEY_W, InputConstants.REPEAT, false));
        assertSame(PASS, key(gate, InputConstants.KEY_W, InputConstants.RELEASE, false));
    }

    @Test
    void keyPressedInsideOverlayPassesAfterClose() {
        KavoraInputGate gate = new KavoraInputGate();
        open(gate);
        assertSame(BLOCK, key(gate, InputConstants.KEY_E, InputConstants.PRESS, false));

        close(gate);

        assertSame(PASS, key(gate, InputConstants.KEY_E, InputConstants.REPEAT, false));
        assertSame(PASS, key(gate, InputConstants.KEY_E, InputConstants.RELEASE, false));
        assertSame(PASS, key(gate, InputConstants.KEY_E, InputConstants.PRESS, false));
    }

    @Test
    void menuClosedFromTheRuntimeReleasesCapture() {
        KavoraInputGate gate = new KavoraInputGate();
        open(gate);
        assertSame(NONE, follow(gate, "2"));

        assertSame(CLOSED, follow(gate, "0"));

        assertFalse(gate.isCaptureSessionOpen());
        assertSame(NONE, follow(gate, "0"));
    }

    @Test
    void menuOpenedByTheRuntimeTakesCapture() {
        KavoraInputGate gate = new KavoraInputGate();
        assertSame(NONE, follow(gate, "0"));

        assertSame(OPENED, follow(gate, "3"));

        assertTrue(gate.isCaptureSessionOpen());
        assertSame(NONE, follow(gate, "3"));
    }

    @Test
    void readFromBeforeTheModOpenedDoesNotClose() {
        KavoraInputGate gate = new KavoraInputGate();
        open(gate);

        assertSame(NONE, follow(gate, "0"));
        assertSame(NONE, follow(gate, "0"));

        assertTrue(gate.isCaptureSessionOpen());
        assertSame(NONE, follow(gate, "2"));
        assertSame(CLOSED, follow(gate, "0"));
    }

    @Test
    void readFromBeforeTheModClosedDoesNotReopen() {
        KavoraInputGate gate = new KavoraInputGate();
        open(gate);
        assertSame(NONE, follow(gate, "2"));
        close(gate);

        assertSame(NONE, follow(gate, "2"));

        assertFalse(gate.isCaptureSessionOpen());
        assertSame(NONE, follow(gate, "0"));
        assertSame(OPENED, follow(gate, "2"));
    }

    @Test
    void overdueOpenReleasesCaptureWhileNoMenuIsShown() {
        KavoraInputGate gate = new KavoraInputGate();
        open(gate);
        for (int frame = 1; frame < KavoraInputGate.OWN_WRITE_OVERDUE_FRAMES; frame++) {
            assertSame(NONE, follow(gate, "0"));
        }

        assertSame(OWN_WRITE_OVERDUE, follow(gate, "0"));
        assertTrue(gate.isCaptureSessionOpen());

        assertSame(CLOSED, follow(gate, "0"));
        assertFalse(gate.isCaptureSessionOpen());
        assertSame(NONE, follow(gate, "0"));
    }

    @Test
    void overdueCloseTakesCaptureWhileAMenuIsShown() {
        KavoraInputGate gate = new KavoraInputGate();
        open(gate);
        assertSame(NONE, follow(gate, "2"));
        close(gate);
        for (int frame = 1; frame < KavoraInputGate.OWN_WRITE_OVERDUE_FRAMES; frame++) {
            assertSame(NONE, follow(gate, "2"));
        }

        assertSame(OWN_WRITE_OVERDUE, follow(gate, "2"));
        assertFalse(gate.isCaptureSessionOpen());

        assertSame(OPENED, follow(gate, "2"));
        assertTrue(gate.isCaptureSessionOpen());
        assertSame(NONE, follow(gate, "2"));
    }

    @Test
    void eachOwnWriteGetsItsOwnOverdueCount() {
        KavoraInputGate gate = new KavoraInputGate();
        open(gate);
        for (int frame = 1; frame < KavoraInputGate.OWN_WRITE_OVERDUE_FRAMES; frame++) {
            assertSame(NONE, follow(gate, "0"));
        }
        close(gate);

        for (int frame = 1; frame < KavoraInputGate.OWN_WRITE_OVERDUE_FRAMES; frame++) {
            assertSame(NONE, follow(gate, "2"));
        }

        assertSame(OWN_WRITE_OVERDUE, follow(gate, "2"));
        assertFalse(gate.isCaptureSessionOpen());
    }

    @Test
    void switchingMenusKeepsCapture() {
        KavoraInputGate gate = new KavoraInputGate();
        gate.open(KavoraInputGate.Menu.GRAPHICS_SETTINGS, (option, value) -> { });
        assertSame(NONE, follow(gate, "1"));

        assertSame(NONE, follow(gate, "2"));

        assertTrue(gate.isCaptureSessionOpen());
    }

    @Test
    void anyShownMenuResolvesTheModsOpen() {
        KavoraInputGate gate = new KavoraInputGate();
        open(gate);

        assertSame(NONE, follow(gate, "3"));

        assertSame(CLOSED, follow(gate, "0"));
    }

    @Test
    void menuStillShownAfterAFailedCloseTakesCaptureBack() {
        KavoraInputGate gate = new KavoraInputGate();
        open(gate);
        assertSame(NONE, follow(gate, "2"));
        assertThrows(IllegalStateException.class,
                () -> gate.close((option, value) -> {
                    throw new IllegalStateException("close failed");
                }));

        assertSame(OPENED, follow(gate, "2"));

        assertTrue(gate.isCaptureSessionOpen());
    }

    @Test
    void gateOwnsTheNativeVisibilityValues() {
        KavoraInputGate gate = new KavoraInputGate();
        Map<String, String> writes = new HashMap<>();

        gate.open(KavoraInputGate.Menu.DEVELOPER, writes::put);
        assertEquals("2", writes.get("rtx.showUI"));
        gate.close(writes::put);
        assertEquals("0", writes.get("rtx.showUI"));

        gate.open(KavoraInputGate.Menu.GRAPHICS_SETTINGS, writes::put);
        assertEquals("1", writes.get("rtx.showUI"));
        gate.close(writes::put);

        assertEquals("0", writes.get("rtx.showUI"));
        assertFalse(gate.isCaptureSessionOpen());
        gate.followVisibility((option, buffer) -> {
            assertEquals("rtx.showUI", option);
            return resolve("0", buffer);
        });
    }

    @Test
    void aValueTooLongForTheReadBufferIsAShownMenu() {
        KavoraInputGate gate = new KavoraInputGate();

        assertSame(OPENED, follow(gate, "10"));

        assertSame(CLOSED, follow(gate, "0"));
    }

    @Test
    void everyReadReusesTheSameBuffer() {
        KavoraInputGate gate = new KavoraInputGate();
        List<byte[]> buffers = new ArrayList<>();

        for (int frame = 0; frame < 3; frame++) {
            gate.followVisibility((option, buffer) -> {
                buffers.add(buffer);
                return resolve("0", buffer);
            });
        }

        assertSame(buffers.get(0), buffers.get(1));
        assertSame(buffers.get(0), buffers.get(2));
    }

    @Test
    void openingWhileOpenKeepsTheMenuAlreadyShown() {
        KavoraInputGate gate = new KavoraInputGate();
        Map<String, String> writes = new HashMap<>();
        gate.open(KavoraInputGate.Menu.DEVELOPER, writes::put);

        gate.open(KavoraInputGate.Menu.GRAPHICS_SETTINGS, writes::put);

        assertEquals("2", writes.get("rtx.showUI"));
        assertTrue(gate.isCaptureSessionOpen());
    }

    @Test
    void failedVisibilityWritesDoNotCommitCaptureState() {
        KavoraInputGate gate = new KavoraInputGate();

        assertThrows(IllegalStateException.class,
                () -> gate.open(KavoraInputGate.Menu.DEVELOPER, (option, value) -> {
                    throw new IllegalStateException("open failed");
                }));
        assertFalse(gate.isCaptureSessionOpen());

        open(gate);
        assertThrows(IllegalStateException.class,
                () -> gate.close((option, value) -> {
                    throw new IllegalStateException("close failed");
                }));
        assertFalse(gate.isCaptureSessionOpen());
    }

    private static void open(KavoraInputGate gate) {
        gate.open(KavoraInputGate.Menu.DEVELOPER, (option, value) -> { });
    }

    private static void close(KavoraInputGate gate) {
        gate.close((option, value) -> { });
    }

    private static KavoraInputGate.VisibilityChange follow(KavoraInputGate gate, String visibility) {
        return gate.followVisibility((option, buffer) -> resolve(visibility, buffer));
    }

    /**
     * Writes {@code value} the way {@code GetConfigVariableResolved} does: UTF-8 and a NUL byte
     * when they fit, and the size they need either way.
     */
    private static int resolve(String value, byte[] buffer) {
        byte[] resolved = (value + '\0').getBytes(StandardCharsets.UTF_8);
        if (resolved.length <= buffer.length) {
            System.arraycopy(resolved, 0, buffer, 0, resolved.length);
        }
        return resolved.length;
    }

    private static KavoraInputGate.KeyDecision key(
            KavoraInputGate gate,
            int key,
            int action,
            boolean altDown) {
        return key(gate, key, action, altDown, false);
    }

    private static KavoraInputGate.KeyDecision key(
            KavoraInputGate gate,
            int key,
            int action,
            boolean altDown,
            boolean controlDown) {
        return gate.onKey(key, action, altDown, controlDown);
    }
}
