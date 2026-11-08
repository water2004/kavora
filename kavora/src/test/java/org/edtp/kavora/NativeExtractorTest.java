/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeExtractorTest {

    /**
     * Deletes a retired SDK before extractIfNeeded returns. On the game's
     * daemon thread the delete can still be running when JUnit removes the
     * {@code @TempDir}, and on Windows the two deleters fail each other.
     */
    private static final Executor INLINE_CLEANUP = Runnable::run;

    @ParameterizedTest
    @CsvSource({
            "aarch64, kavora-runtime-arm64",
            "amd64, kavora-runtime-win64",
            "x86_64, kavora-runtime-win64",
            "ppc64le, kavora-runtime-win64"
    })
    void detectedArchitecturesResolveToTheirSdkDirectories(String arch, String expectedDirectory) {
        assertEquals(expectedDirectory, NativeExtractor.detectPlatformSdkDir(arch));
    }

    @Test
    void markerMatchesAcceptsAnIdenticalMarker() {
        String m = "0.9.0-202604191459-8f2ab12|kavora-runtime-win64";
        assertTrue(NativeExtractor.markerMatches(m, m));
    }

    @Test
    void markerMatchesRejectsADifferentVersion() {
        assertFalse(NativeExtractor.markerMatches(
            "0.9.0-202604191459-8f2ab12|kavora-runtime-win64",
            "0.9.0-202604191500-8f2ab12|kavora-runtime-win64"
        ));
    }

    @Test
    void extractTreeWithoutAKeptCountReplacesExistingFiles(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        Files.writeString(src.resolve("d3d9.dll"), "shipped");
        Files.writeString(dst.resolve("d3d9.dll"), "stale");

        AtomicInteger files = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        NativeExtractor.extractTree(src, dst, files, bytes);

        // Mod-owned payload: the shipped copy always wins.
        assertEquals("shipped", Files.readString(dst.resolve("d3d9.dll")));
        assertEquals(1, files.get());
    }

    @Test
    void extractTreeWithAKeptCountPreservesAuthoredEditsAndAddsNewFiles(@TempDir Path tmp) throws IOException {
        // The gameplay conf payload is edited in place by lighting authors, and
        // every build makes the version marker stale. Re-extraction must not
        // revert their tuning, while still delivering profiles they don't have.
        Path src = Files.createDirectories(tmp.resolve("src/confs/zones"));
        Path dst = Files.createDirectories(tmp.resolve("dst/confs/zones"));
        Files.writeString(src.resolve("cave.conf"), "shipped default");
        Files.writeString(src.resolve("swamp.conf"), "newly shipped profile");
        Files.writeString(dst.resolve("cave.conf"), "author's hand-tuned values");

        AtomicInteger files = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        AtomicInteger kept = new AtomicInteger();
        NativeExtractor.extractTree(tmp.resolve("src"), tmp.resolve("dst"), files, bytes, kept);

        assertEquals("author's hand-tuned values", Files.readString(dst.resolve("cave.conf")));
        assertEquals("newly shipped profile", Files.readString(dst.resolve("swamp.conf")));
        assertEquals(1, kept.get());
        assertEquals(1, files.get());
    }

    @Test
    void extractTreeWithAKeptCountRestoresADeletedFileToItsShippedDefault(@TempDir Path tmp) throws IOException {
        // The documented escape hatch: remove the file, relaunch, get the
        // shipped version back.
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        Files.writeString(src.resolve("water.conf"), "shipped default");

        AtomicInteger files = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        AtomicInteger kept = new AtomicInteger();
        NativeExtractor.extractTree(src, dst, files, bytes, kept);

        assertEquals("shipped default", Files.readString(dst.resolve("water.conf")));
        assertEquals(0, kept.get());
    }

    @Test
    void matchingMarkerWithMissingRuntimeDllReextractsTheSdk(@TempDir Path tmp) throws IOException {
        Path nativeRoot = createNativePayload(tmp);
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "kavora-runtime-win64", INLINE_CLEANUP);
        Files.delete(gameDir.resolve("kavora-runtime/d3d9.dll"));
        Files.writeString(gameDir.resolve("kavora-runtime/runtime.dll"), "stale runtime");
        Files.writeString(gameDir.resolve("rtx.conf"), "stale defaults");

        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "kavora-runtime-win64", INLINE_CLEANUP);

        assertEquals("shipped runtime", Files.readString(gameDir.resolve("kavora-runtime/d3d9.dll")));
        assertEquals("shipped companion", Files.readString(gameDir.resolve("kavora-runtime/runtime.dll")));
        assertEquals("shipped defaults", Files.readString(gameDir.resolve("rtx.conf")));
        assertEquals(NativeExtractor.buildMarker("1.0", "kavora-runtime-win64", nativeRoot.resolve("kavora-runtime-win64")),
                Files.readString(gameDir.resolve(".kavora-version")));
    }

    @ParameterizedTest
    @CsvSource({"kavora-support.dll", ".runtime-provenance.json"})
    void matchingMarkerRepairsAMissingRequiredRuntimeFile(String file, @TempDir Path tmp) throws IOException {
        Path nativeRoot = createNativePayload(tmp);
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "kavora-runtime-win64", INLINE_CLEANUP);
        Files.delete(gameDir.resolve("kavora-runtime").resolve(file));
        Files.writeString(gameDir.resolve("kavora-runtime/runtime.dll"), "stale runtime");

        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "kavora-runtime-win64", INLINE_CLEANUP);

        assertTrue(Files.isRegularFile(gameDir.resolve("kavora-runtime").resolve(file)));
        assertEquals("shipped companion", Files.readString(gameDir.resolve("kavora-runtime/runtime.dll")));
    }

    @Test
    void matchingMarkerWithMissingSdkDirectoryReextractsTheSdk(@TempDir Path tmp) throws IOException {
        Path nativeRoot = createNativePayload(tmp);
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        Files.writeString(gameDir.resolve(".kavora-version"), "1.0|kavora-runtime-win64");

        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "kavora-runtime-win64", INLINE_CLEANUP);

        assertEquals("shipped runtime", Files.readString(gameDir.resolve("kavora-runtime/d3d9.dll")));
        assertEquals("shipped companion", Files.readString(gameDir.resolve("kavora-runtime/runtime.dll")));
    }

    @Test
    void matchingMarkerRestoresADeletedShippedConfWithoutReplacingTheSdk(@TempDir Path tmp)
            throws IOException {
        Path nativeRoot = createNativePayload(tmp);
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "kavora-runtime-win64", INLINE_CLEANUP);
        Path waterConf = gameDir.resolve("rtx-remix/mods/minecraft-logic/confs/water.conf");
        Files.delete(waterConf);
        Files.writeString(gameDir.resolve("kavora-runtime/d3d9.dll"), "existing runtime");

        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "kavora-runtime-win64", INLINE_CLEANUP);

        assertEquals("shipped water", Files.readString(waterConf));
        assertEquals("existing runtime", Files.readString(gameDir.resolve("kavora-runtime/d3d9.dll")));
    }

    @Test
    void matchingMarkerNeverOverwritesAnEditedConf(@TempDir Path tmp) throws IOException {
        Path nativeRoot = createNativePayload(tmp);
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "kavora-runtime-win64", INLINE_CLEANUP);
        Path waterConf = gameDir.resolve("rtx-remix/mods/minecraft-logic/confs/water.conf");
        Files.writeString(waterConf, "author's water");
        Files.writeString(gameDir.resolve("rtx.conf"), "edited defaults");

        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "kavora-runtime-win64", INLINE_CLEANUP);

        assertEquals("author's water", Files.readString(waterConf));
        assertEquals("edited defaults", Files.readString(gameDir.resolve("rtx.conf")));

        Files.delete(gameDir.resolve("kavora-runtime/d3d9.dll"));
        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "kavora-runtime-win64", INLINE_CLEANUP);
        assertEquals("author's water", Files.readString(waterConf),
                "repairing a missing runtime must preserve author-owned files too");
    }

    @Test
    void extractionWritesTheShippedTexturesBesideOtherFilesInTheTexturesFolder(@TempDir Path tmp)
            throws IOException {
        // A JAR dropped into mods/ by a launcher has no installer to copy the
        // runtime textures, so the mod must put them where it loads them from.
        Path nativeRoot = createNativePayload(tmp);
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        Path textures = Files.createDirectories(gameDir.resolve("textures"));
        Files.writeString(textures.resolve("unrelated.png"), "someone else's file");

        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "kavora-runtime-win64", INLINE_CLEANUP);

        assertEquals("shipped water normals", Files.readString(textures.resolve("water_normals_flipbook.dds")));
        assertEquals("shipped milky way", Files.readString(textures.resolve("milky_way.dds")));
        assertEquals("someone else's file", Files.readString(textures.resolve("unrelated.png")));
        try (var listing = Files.list(textures)) {
            assertEquals(3, listing.count(), "no staging copies may be left behind");
        }
    }

    @Test
    void aVersionChangeReplacesAStaleTexture(@TempDir Path tmp) throws IOException {
        // Textures are mod-owned, unlike the author-owned conf payload: a
        // release that replaces a texture must reach installs that have the old one.
        Path nativeRoot = createNativePayload(tmp);
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        Path waterNormals = Files.createDirectories(gameDir.resolve("textures"))
                .resolve("water_normals_flipbook.dds");
        Files.writeString(waterNormals, "previous release's water normals");
        Files.writeString(gameDir.resolve(".kavora-version"), "0.9|kavora-runtime-win64");

        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "kavora-runtime-win64", INLINE_CLEANUP);

        assertEquals("shipped water normals", Files.readString(waterNormals));
    }

    @Test
    void matchingMarkerRestoresADeletedTextureWithoutReplacingTheSdk(@TempDir Path tmp) throws IOException {
        Path nativeRoot = createNativePayload(tmp);
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "kavora-runtime-win64", INLINE_CLEANUP);
        Path waterNormals = gameDir.resolve("textures/water_normals_flipbook.dds");
        Files.delete(waterNormals);
        Files.delete(gameDir.resolve("textures/milky_way.dds"));
        Files.writeString(gameDir.resolve("kavora-runtime/d3d9.dll"), "existing runtime");

        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "kavora-runtime-win64", INLINE_CLEANUP);

        assertEquals("shipped water normals", Files.readString(waterNormals));
        assertEquals("shipped milky way", Files.readString(gameDir.resolve("textures/milky_way.dds")));
        assertEquals("existing runtime", Files.readString(gameDir.resolve("kavora-runtime/d3d9.dll")));
    }

    @ParameterizedTest
    @CsvSource({".runtime-provenance.json", "kavora-support.dll"})
    void changedBundledRuntimeReextractsAtSameVersionAndPreservesAuthorConf(String changedFile, @TempDir Path tmp)
            throws IOException {
        Path nativeRoot = createNativePayload(tmp);
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "kavora-runtime-win64", INLINE_CLEANUP);
        Path sdk = nativeRoot.resolve("kavora-runtime-win64");
        Path conf = gameDir.resolve("rtx-remix/mods/minecraft-logic/confs/water.conf");
        Files.writeString(conf, "author edit");
        Files.writeString(gameDir.resolve("kavora-runtime/runtime.dll"), "old runtime");
        Files.writeString(sdk.resolve(changedFile), "changed content");

        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "kavora-runtime-win64", INLINE_CLEANUP);

        assertEquals("shipped companion", Files.readString(gameDir.resolve("kavora-runtime/runtime.dll")));
        assertEquals("changed content", Files.readString(gameDir.resolve("kavora-runtime").resolve(changedFile)));
        assertEquals("author edit", Files.readString(conf));
    }

    @Test
    void identicalBundledContentDoesNotReextract(@TempDir Path tmp) throws IOException {
        Path nativeRoot = createNativePayload(tmp);
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "kavora-runtime-win64", INLINE_CLEANUP);
        Files.writeString(gameDir.resolve("kavora-runtime/runtime.dll"), "existing runtime");
        Path provenance = nativeRoot.resolve("kavora-runtime-win64/.runtime-provenance.json");
        Files.write(provenance, Files.readAllBytes(provenance));

        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "kavora-runtime-win64", INLINE_CLEANUP);

        assertEquals("existing runtime", Files.readString(gameDir.resolve("kavora-runtime/runtime.dll")));
    }

    @Test
    void legacyMarkerTriggersMigration(@TempDir Path tmp) throws IOException {
        Path nativeRoot = createNativePayload(tmp);
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "kavora-runtime-win64", INLINE_CLEANUP);
        Files.writeString(gameDir.resolve(".kavora-version"), "1.0|kavora-runtime-win64");
        Files.writeString(gameDir.resolve("kavora-runtime/runtime.dll"), "old runtime");

        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "kavora-runtime-win64", INLINE_CLEANUP);

        assertEquals("shipped companion", Files.readString(gameDir.resolve("kavora-runtime/runtime.dll")));
        assertEquals(NativeExtractor.buildMarker("1.0", "kavora-runtime-win64", nativeRoot.resolve("kavora-runtime-win64")),
                Files.readString(gameDir.resolve(".kavora-version")));
    }

    @Test
    void missingArchitectureLeavesLivePayloadUntouched(@TempDir Path tmp) throws IOException {
        Path nativeRoot = createNativePayload(tmp);
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "kavora-runtime-win64", INLINE_CLEANUP);
        String marker = Files.readString(gameDir.resolve(".kavora-version"));
        Files.writeString(gameDir.resolve("kavora-runtime/runtime.dll"), "existing runtime");

        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "kavora-runtime-arm64", INLINE_CLEANUP);

        assertEquals(marker, Files.readString(gameDir.resolve(".kavora-version")));
        assertEquals("existing runtime", Files.readString(gameDir.resolve("kavora-runtime/runtime.dll")));
    }

    @Test
    void absentNativeRootSkipsExtraction(@TempDir Path tmp) throws IOException {
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        NativeExtractor.extractIfNeeded(tmp.resolve("absent-native"), gameDir, "1.0", "kavora-runtime-win64", INLINE_CLEANUP);
        assertFalse(Files.exists(gameDir.resolve(".kavora-version")));
    }

    private static Path createNativePayload(Path tmp) throws IOException {
        Path nativeRoot = Files.createDirectories(tmp.resolve("native"));
        Path sdk = Files.createDirectories(nativeRoot.resolve("kavora-runtime-win64"));
        Files.writeString(sdk.resolve("d3d9.dll"), "shipped runtime");
        Files.writeString(sdk.resolve("kavora-support.dll"), "shipped crash helper");
        Files.writeString(sdk.resolve(".runtime-provenance.json"), "source build metadata");
        Files.writeString(sdk.resolve("runtime.dll"), "shipped companion");
        Files.writeString(nativeRoot.resolve("rtx.conf"), "shipped defaults");
        Path confs = Files.createDirectories(nativeRoot.resolve("rtx-remix/mods/minecraft-logic/confs"));
        Files.writeString(confs.resolve("water.conf"), "shipped water");
        Path textures = Files.createDirectories(nativeRoot.resolve("textures"));
        Files.writeString(textures.resolve("water_normals_flipbook.dds"), "shipped water normals");
        Files.writeString(textures.resolve("milky_way.dds"), "shipped milky way");
        return nativeRoot;
    }

    @Test
    void restoreMissingRtxConfWritesTheShippedConfWhenTheLiveCopyWasDeleted(@TempDir Path tmp) throws IOException {
        // A matching version marker skips extraction, so this is the only path
        // that puts rtx.conf back after something deletes it.
        Path nativeRoot = Files.createDirectories(tmp.resolve("native"));
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        Files.writeString(nativeRoot.resolve("rtx.conf"), "rtx.showUICursor = false");

        assertTrue(NativeExtractor.restoreMissingRtxConf(nativeRoot, gameDir));
        assertEquals("rtx.showUICursor = false", Files.readString(gameDir.resolve("rtx.conf")));
    }

    @Test
    void restoreMissingRtxConfLeavesAnExistingConfAlone(@TempDir Path tmp) throws IOException {
        // Restoring runs on every version-stable launch, so it must never
        // overwrite a conf someone is editing between launches.
        Path nativeRoot = Files.createDirectories(tmp.resolve("native"));
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        Files.writeString(nativeRoot.resolve("rtx.conf"), "shipped");
        Files.writeString(gameDir.resolve("rtx.conf"), "hand tuned");

        assertFalse(NativeExtractor.restoreMissingRtxConf(nativeRoot, gameDir));
        assertEquals("hand tuned", Files.readString(gameDir.resolve("rtx.conf")));
    }

    @Test
    void restoreMissingRtxConfIsANoOpWhenTheJarCarriesNoNativePayload(@TempDir Path tmp) throws IOException {
        // Development runs load classes without a bundled native/ root.
        Path gameDir = Files.createDirectories(tmp.resolve("game"));

        assertFalse(NativeExtractor.restoreMissingRtxConf(tmp.resolve("absent-native"), gameDir));
        assertFalse(Files.exists(gameDir.resolve("rtx.conf")));
    }
}
