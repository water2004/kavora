/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.edtp.kavora.sdk.RemixAPI;
import org.edtp.kavora.sdk.TextureBatchEntry;
import org.edtp.kavora.sdk.TextureFromMemoryInfo;
import org.edtp.kavora.sdk.TexturePatch;
import org.edtp.kavora.sdk.TexturePatchInfo;
import org.edtp.kavora.KavoraMod;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Compares every GPU mip with the CPU reference, including overlapping and authored patches. */
final class KavoraTextureMipChecks {
    private static final Logger LOGGER = LoggerFactory.getLogger(KavoraTextureMipChecks.class);
    private static final long CAPTURE_HASH = 0x7e57cafeL;

    static void run(ClientGameTestContext context) {
        Path fixtures = Path.of(System.getProperty("kavora.gametest.textureMipFixtures"));
        Path captures = Path.of(System.getenv("RTX_API_TEXTURE_CAPTURE_PATH"));
        JsonArray cases = JsonParser.parseString(readText(fixtures.resolve("manifest.json"))).getAsJsonArray();
        int sequence = 0;
        for (var entry : cases) {
            JsonObject fixture = entry.getAsJsonObject();
            int id = value(fixture, "id");
            var info = TextureFromMemoryInfo.builder().hash(CAPTURE_HASH)
                    .size(value(fixture, "width"), value(fixture, "height"))
                    .format(value(fixture, "format")).purpose(value(fixture, "purpose"))
                    .mipLevels(value(fixture, "mips")).immutable(fixture.get("immutable").getAsBoolean())
                    .pixelData(direct(read(fixtures.resolve(id + "-source.bin")))).build();
            long handle = context.computeOnClient(client -> api().createTextureFromMemory(info));
            try {
                check(context, captures, fixtures, fixture, sequence++, 0);
                if (!info.immutable) {
                    var entries = new ArrayList<TextureBatchEntry>();
                    for (int stage = 1; stage <= 2; stage++) {
                        var patches = new ArrayList<TexturePatch>();
                        for (var patchEntry : fixture.getAsJsonArray("patches")) {
                            JsonObject p = patchEntry.getAsJsonObject();
                            if (value(p, "stage") != stage) continue;
                            TexturePatch patch = new TexturePatch();
                            patch.mipLevel = value(p, "mip");
                            patch.dstX = value(p, "x");
                            patch.dstY = value(p, "y");
                            patch.width = value(p, "width");
                            patch.height = value(p, "height");
                            patch.rowPitch = value(p, "pitch");
                            patch.format = value(p, "format");
                            patch.pixelData = direct(read(fixtures.resolve(p.get("file").getAsString())));
                            patches.add(patch);
                        }
                        TextureBatchEntry batch = new TextureBatchEntry();
                        batch.handle = handle;
                        batch.patches = patches.toArray(TexturePatch[]::new);
                        entries.add(batch);
                    }
                    TexturePatchInfo update = new TexturePatchInfo();
                    update.entries = entries.toArray(TextureBatchEntry[]::new);
                    context.runOnClient(client -> api().updateTextureFromMemory(update));
                    check(context, captures, fixtures, fixture, sequence++, 1);
                    check(context, captures, fixtures, fixture, sequence++, 2);
                }
            } finally {
                context.runOnClient(client -> api().destroyTexture(handle));
            }
        }
        LOGGER.info("GPU mip reference: {} cases, {} captures passed", cases.size(), sequence);
    }

    private static void check(ClientGameTestContext context, Path captures, Path fixtures, JsonObject fixture, int sequence, int stage) {
        Path capture = captures.resolve("texture-" + ProcessHandle.current().pid() + "-" + sequence + ".bin");
        context.waitFor(client -> Files.exists(capture), 200);
        byte[] actual = read(capture);
        byte[] expected = read(fixtures.resolve(value(fixture, "id") + "-expected-" + stage + ".bin"));
        require(actual.length == expected.length, "GPU mip byte count: " + capture);
        ByteBuffer a = ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer e = ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 5; i++) require(a.getInt(i * 4) == e.getInt(i * 4), "GPU mip metadata: " + capture);
        int format = e.getInt(8), width = e.getInt(0), height = e.getInt(4);
        int bytes = switch (format) { case 4 -> 1; case 5 -> 2; case 6 -> 8; case 7 -> 16; default -> 4; };
        int baseEnd = 20 + width * height * bytes;
        boolean authored = value(fixture, "mips") > 0;
        for (int offset = 20; offset < actual.length;) {
            boolean exact = authored || offset < baseEnd;
            if (format < 6) {
                int difference = Math.abs((actual[offset] & 255) - (expected[offset] & 255));
                require(difference <= (exact ? 0 : 1), "GPU mip UNORM mismatch at " + offset + ": " + capture
                        + " expected=" + (expected[offset] & 255) + " actual=" + (actual[offset] & 255));
                offset++;
            } else {
                float av = format == 6 ? Float.float16ToFloat(a.getShort(offset)) : a.getFloat(offset);
                float ev = format == 6 ? Float.float16ToFloat(e.getShort(offset)) : e.getFloat(offset);
                float tolerance = exact ? 0 : format == 6 ? .0005f : .00001f;
                require(Math.abs(av - ev) <= tolerance, "GPU mip float mismatch at " + offset + ": " + capture + " expected=" + ev + " actual=" + av);
                offset += format == 6 ? 2 : 4;
            }
        }
    }

    private static RemixAPI api() { return KavoraMod.renderer.getRemixApi(); }
    private static int value(JsonObject object, String key) { return object.get(key).getAsInt(); }
    private static ByteBuffer direct(byte[] bytes) { return ByteBuffer.allocateDirect(bytes.length).put(bytes).flip(); }
    private static byte[] read(Path path) {
        try { return Files.readAllBytes(path); }
        catch (IOException failure) { throw new AssertionError("Cannot read mip fixture " + path, failure); }
    }
    private static String readText(Path path) {
        try { return Files.readString(path); }
        catch (IOException failure) { throw new AssertionError("Cannot read mip manifest " + path, failure); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
