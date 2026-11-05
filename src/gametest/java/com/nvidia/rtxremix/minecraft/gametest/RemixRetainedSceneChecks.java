/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gametest;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.MeshInfo;
import com.nvidia.rtxremix.MeshInfoSurfaceTriangles;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureBatchEntry;
import com.nvidia.rtxremix.TextureFromMemoryInfo;
import com.nvidia.rtxremix.TexturePatch;
import com.nvidia.rtxremix.TexturePatchInfo;
import com.nvidia.rtxremix.Transform;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.Set;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** Pixel checks for retained multi-material geometry shared by independently mutable instances. */
final class RemixRetainedSceneChecks {
    private static final String[] OPTIONS = {
            "rtx.enablePreservePath", "rtx.debugView.debugViewIdx",
            "rtx.debugView.showFirstGBufferHit", "rtx.debugView.replaceCompositeOutput",
            "rtx.cameraJitterSequenceLength"
    };

    static void run(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            require(player.teleportTo(server.overworld(), 0, 120.38, -7, Set.of(), 0, 0, true),
                    "Retained scene camera teleport failed");
        });
        context.waitFor(client -> Math.abs(client.player.getY() - 120.38) < 0.01, 200);
        String[] previous = context.computeOnClient(client -> {
            var result = new String[OPTIONS.length];
            for (int i = 0; i < result.length; i++) result[i] = api().getConfigVariableResolved(OPTIONS[i]);
            return result;
        });
        Probe probe = new Probe();
        try {
            context.runOnClient(client -> {
                api().setConfigVariable(OPTIONS[0], "False");
                api().setConfigVariable(OPTIONS[1], "32");
                api().setConfigVariable(OPTIONS[2], "True");
                // Inspect albedo after postprocessing so exposure cannot alter the palette.
                api().setConfigVariable(OPTIONS[3], "False");
                // Pixel comparisons require the same subpixel ray origins in every capture.
                api().setConfigVariable(OPTIONS[4], "1");
                probe.create();
            });
            Pixels dynamic = capture(context, "dynamic");
            require(dynamic.red > 500 && dynamic.green > 500, "Retained fixture's two materials did not render: " + dynamic);
            context.runOnClient(client -> api().setConfigVariable(OPTIONS[0], "True"));
            Pixels preserved = capture(context, "preserved");
            require(closeCount(dynamic.red, preserved.red) && closeCount(dynamic.green, preserved.green),
                    "Preserved geometry changed its material coverage: " + dynamic + " -> " + preserved);
            if (Boolean.getBoolean("rtxremix.gametest.heightFog")) {
                checkHeightFog(context, probe);
                return;
            }
            checkImmutableGeometry(context, probe);
            checkDecalCacheTransitions(context, probe);

            context.runOnClient(client -> api().updateMaterial(probe.red, material(0x7f001, 0, 0, 1)));
            Pixels recolored = capture(context, "material-updated");
            require(recolored.red < preserved.red / 10 && recolored.blue > preserved.red * 0.8
                            && closeCount(preserved.green, recolored.green),
                    "UpdateMaterial did not refresh both shared instances: " + recolored);

            context.runOnClient(client -> api().updateMaterial(probe.green, material(0x7f002, 1, 1, 0)));
            Pixels secondMaterial = capture(context, "second-material-updated");
            require(closeCount(recolored.blue, secondMaterial.blue)
                            && closeCount(recolored.green, secondMaterial.yellow) && secondMaterial.green < 50,
                    "Updating the second surface altered unrelated materials: " + secondMaterial);
            context.runOnClient(client -> api().updateMaterial(probe.green, material(0x7f002, 0, 1, 0)));

            context.runOnClient(client -> probe.addTextures());
            Pixels textured = capture(context, "textures-created");
            require(closeCount(recolored.blue, textured.blue) && closeCount(recolored.green, textured.green),
                    "Textured materials changed retained coverage: " + textured);
            context.runOnClient(client -> probe.patchTextures(true));
            Pixels patched = capture(context, "textures-patched");
            require(closeCount(textured.blue, patched.red) && closeCount(textured.green, patched.yellow)
                            && patched.blue < 50 && patched.green < 50,
                    "Batched texture patches did not refresh shared instances: " + patched);
            context.runOnClient(client -> probe.patchTextures(false));
            Pixels restored = capture(context, "textures-restored");
            require(closeCount(textured.blue, restored.blue) && closeCount(textured.green, restored.green),
                    "Repeated texture patches did not restore their pixels: " + restored);
            context.runOnClient(client -> probe.replaceWithImmutableTextures());
            Pixels immutable = capture(context, "textures-immutable");
            require(closeCount(restored.blue, immutable.blue) && closeCount(restored.green, immutable.green),
                    "Immutable textures lost their uploaded pixels: " + immutable);

            context.runOnClient(client -> probe.updateFirst(-1.6f, 123.5f, 0));
            Pixels moved = capture(context, "instance-moved");
            require(Math.abs(moved.blueY - recolored.blueY) > 20, "Retained transform did not update: " + moved);
            context.runOnClient(client -> probe.updateFirst(-1.6f, 123.5f, InstanceCategoryBit.HIDDEN));
            Pixels hidden = capture(context, "instance-hidden");
            require(hidden.blue > 500 && hidden.blue < moved.blue * 0.7,
                    "Hiding one retained instance changed neither or both copies: " + hidden);

            context.runOnClient(client -> {
                probe.yellow = api().createMaterial(material(0x7f003, 1, 1, 0));
                probe.replacement = mesh(0x7f005, probe.yellow, probe.green);
                api().sceneInstanceUpdate(probe.first, probe.replacement, transform(-1.6f, 122), 0, 1, 0);
            });
            Pixels replaced = capture(context, "mesh-replaced");
            require(replaced.yellow > 500 && closeCount(hidden.blue, replaced.blue)
                            && replaced.green > hidden.green * 1.7,
                    "Replacing one mesh lost shared geometry or retained stale materials: " + replaced);
            context.runOnClient(client -> {
                api().destroyMaterial(probe.red);
                probe.red = 0;
            });
            Pixels materialRemoved = capture(context, "material-removed");
            require(materialRemoved.blue < 50 && closeCount(replaced.green, materialRemoved.green)
                            && closeCount(replaced.yellow, materialRemoved.yellow),
                    "Destroyed material remained cached or altered unrelated surfaces: " + materialRemoved);
            context.runOnClient(client -> probe.removeInstances());
            Pixels removed = capture(context, "instances-removed");
            require(removed.red < 50 && removed.green < 50 && removed.blue < 50 && removed.yellow < 50,
                    "Removed instances still render: " + removed);
        } finally {
            context.runOnClient(client -> {
                probe.close();
                for (int i = 0; i < OPTIONS.length; i++) api().setConfigVariable(OPTIONS[i], previous[i]);
            });
            context.waitFor(client -> {
                for (int i = 0; i < OPTIONS.length; i++) {
                    if (!previous[i].equalsIgnoreCase(api().getConfigVariableResolved(OPTIONS[i]))) return false;
                }
                return true;
            }, 100);
        }
    }

    private static BufferedImage frame(ClientGameTestContext context, String name) {
        long frame = context.computeOnClient(client -> RemixFrameProbe.presents);
        context.waitFor(client -> RemixFrameProbe.presents >= frame + 12, 200);
        Path path = RemixWorldGameTest.captureCompletedFrame(context, "retained-" + name);
        try {
            return ImageIO.read(path.toFile());
        } catch (IOException e) {
            throw new AssertionError("Cannot read retained scene screenshot", e);
        }
    }

    private static void checkImmutableGeometry(ClientGameTestContext context, Probe probe) {
        BufferedImage palette = frame(context, "storage-palette");
        BitSet mask = new BitSet(palette.getWidth() * palette.getHeight());
        for (int y = 0; y < palette.getHeight(); y++) for (int x = 0; x < palette.getWidth(); x++) {
            int color = palette.getRGB(x, y), r = color >>> 16 & 255, g = color >>> 8 & 255, b = color & 255;
            if ((r > g + 100 && r > b + 100) || (g > r + 100 && g > b + 100))
                mask.set(y * palette.getWidth() + x);
        }
        require(mask.cardinality() > 1000, "Geometry storage fixture has no visible pixels");
        context.runOnClient(client -> api().setConfigVariable(OPTIONS[1], "15"));
        BufferedImage original = frame(context, "storage-original-normals");
        context.runOnClient(client -> probe.setFlags(InstanceCategoryBit.SMOOTH_NORMALS));
        BufferedImage smoothed = frame(context, "storage-smooth-normals");
        context.runOnClient(client -> probe.setFlags(0));
        BufferedImage restored = frame(context, "storage-restored-normals");
        require(difference(original, smoothed, mask) > mask.cardinality() * 10L,
                "SmoothNormals did not modify the private geometry");
        require(difference(original, restored, mask) < mask.cardinality() * 3L,
                "SmoothNormals overwrote the immutable mesh source");
        context.runOnClient(client -> api().setConfigVariable(OPTIONS[1], "32"));
    }

    private static void checkDecalCacheTransitions(ClientGameTestContext context, Probe probe) {
        String previousMode = context.computeOnClient(client -> api().getConfigVariableResolved("rtx.integrateIndirectMode"));
        String previousPreset = context.computeOnClient(client -> api().getConfigVariableResolved("rtx.graphicsPreset"));
        try {
            context.runOnClient(client -> {
                api().setConfigVariable("rtx.graphicsPreset", "4");
                api().setConfigVariable(OPTIONS[2], "False");
                api().updateMaterial(probe.red, MaterialInfo.builder().hash(0x7f001)
                        .opaqueExt(MaterialInfoOpaqueEXT.builder().albedoConstant(1, 0, 0)
                                .roughnessConstant(0).metallicConstant(1).build()).build());
                probe.createDecal();
            });
            for (String mode : new String[] { "3", "2" }) {
                context.runOnClient(client -> api().setConfigVariable("rtx.integrateIndirectMode", mode));
                context.waitFor(client -> mode.equals(api().getConfigVariableResolved("rtx.integrateIndirectMode")), 100);
                if (mode.equals("2")) context.waitFor(client -> api().getNeuralRadianceCacheTrainingRecords() > 0, 100);
                Pixels clear = capture(context, "cached-decal-" + mode + "-clear");
                context.runOnClient(client -> probe.showDecal());
                Pixels painted = capture(context, "cached-decal-" + mode + "-painted");
                require(painted.blue > clear.blue + 500,
                        "Decal is not visible with radiance cache mode " + mode + ": " + clear + " -> " + painted);
                context.runOnClient(client -> probe.hideDecal());
                Pixels removed = capture(context, "cached-decal-" + mode + "-removed");
                require(removed.blue < clear.blue + 50, "Removed cached decal remains visible: " + removed);
            }
        } finally {
            context.runOnClient(client -> {
                probe.closeDecal();
                api().updateMaterial(probe.red, material(0x7f001, 1, 0, 0));
                api().setConfigVariable(OPTIONS[2], "True");
                api().setConfigVariable("rtx.integrateIndirectMode", previousMode);
                api().setConfigVariable("rtx.graphicsPreset", previousPreset);
            });
        }
    }

    private static long difference(BufferedImage first, BufferedImage second, BitSet mask) {
        require(first.getWidth() == second.getWidth() && first.getHeight() == second.getHeight(),
                "Geometry capture resolution changed");
        long difference = 0;
        for (int index = mask.nextSetBit(0); index >= 0; index = mask.nextSetBit(index + 1)) {
            int a = first.getRGB(index % first.getWidth(), index / first.getWidth());
            int b = second.getRGB(index % first.getWidth(), index / first.getWidth());
            for (int channel = 0; channel < 24; channel += 8)
                difference += Math.abs((a >>> channel & 255) - (b >>> channel & 255));
        }
        return difference;
    }

    private static void checkHeightFog(ClientGameTestContext context, Probe probe) {
        BufferedImage palette = frame(context, "height-fog-palette");
        BitSet mask = new BitSet(palette.getWidth() * palette.getHeight());
        for (int y = 0; y < palette.getHeight(); y++) for (int x = 0; x < palette.getWidth(); x++) {
            int rgb = palette.getRGB(x, y), r = rgb >>> 16 & 255, g = rgb >>> 8 & 255, b = rgb & 255;
            if ((r > g + 100 && r > b + 100) || (g > r + 100 && g > b + 100))
                mask.set(y * palette.getWidth() + x);
        }
        require(mask.cardinality() > 1000, "Height fog fixture has no visible pixels");
        String[][] controls = {
                {"rtx.debugView.debugViewIdx", "53"}, {"rtx.volumetrics.enable", "False"},
                {"rtx.debugView.maxValue", "1"},
                {"rtx.debugView.replaceCompositeOutput", "False"},
                {"rtx.sparseRendering.enableSparseRendering", "False"},
                {"rtx.volumetrics.singleScatteringAlbedo", "0, 0, 0"},
                {"rtx.volumetrics.transmittanceColor", "0.5, 0.5, 0.5"},
                {"rtx.volumetrics.transmittanceMeasurementDistanceMeters", "5"},
                {"rtx.volumetrics.enableAtmosphere", "False"},
                {"rtx.volumetrics.enableFogRemap", "False"},
                {"rtx.volumetrics.enableHeterogeneousFog", "False"},
                {"rtx.volumetrics.heightFogBaseHeightMeters", "112"},
                {"rtx.volumetrics.heightFogFalloffMeters", "0"},
                {"rtx.autoExposure.enabled", "False"}, {"rtx.bloom.enable", "False"},
                {"rtx.enableRayReconstruction", "False"}, {"rtx.upscalerType", "0"},
                {"rtx.enableDirectLighting", "False"}
        };
        String[] previous = context.computeOnClient(client -> {
            String[] values = new String[controls.length];
            for (int i = 0; i < values.length; i++) values[i] = api().getConfigVariableResolved(controls[i][0]);
            return values;
        });
        try {
            context.runOnClient(client -> {
                for (String[] control : controls) api().setConfigVariable(control[0], control[1]);
                api().updateMaterial(probe.red, emitter(0x7f001, 1, 0, 0));
                api().updateMaterial(probe.green, emitter(0x7f002, 0, 1, 0));
            });
            BufferedImage clear = frame(context, "height-fog-clear");
            context.runOnClient(client -> api().setConfigVariable("rtx.volumetrics.enable", "True"));
            BufferedImage uniform = frame(context, "height-fog-uniform");
            context.runOnClient(client -> api().setConfigVariable("rtx.volumetrics.heightFogFalloffMeters", "2"));
            BufferedImage high = frame(context, "height-fog-above-layer");
            double clearLuma = mean(clear, mask), lowLuma = mean(uniform, mask), highLuma = mean(high, mask);
            require(clearLuma > lowLuma + 60, "Uniform fog did not attenuate the camera segment");
            require(highLuma > lowLuma + 10 && Math.abs(highLuma - clearLuma) < clearLuma * 0.1 + 1,
                    "High-altitude transmission did not approach clear air: " + lowLuma + " -> " + highLuma
                            + " (clear=" + clearLuma + ")");
            context.runOnClient(client -> api().setConfigVariable("rtx.volumetrics.heightFogBaseHeightMeters", "160"));
            BufferedImage below = frame(context, "height-fog-below-layer");
            require(Math.abs(mean(below, mask) - lowLuma) < 5,
                    "Fog below its base changed the uniform medium");
            context.runOnClient(client -> api().setConfigVariable("rtx.volumetrics.heightFogFalloffMeters", "0"));
            BufferedImage disabled = frame(context, "height-fog-disabled");
            require(Math.abs(mean(disabled, mask) - lowLuma) < 5, "Disabling height fog did not restore the medium");
            context.runOnClient(client -> {
                api().setConfigVariable("rtx.debugView.debugViewIdx", "205");
                api().setConfigVariable("rtx.volumetrics.enable", "False");
                api().setConfigVariable("rtx.volumetrics.heightFogBaseHeightMeters", "112");
            });
            double clearEmission = mean(frame(context, "height-fog-emission-clear"), mask);
            context.runOnClient(client -> api().setConfigVariable("rtx.volumetrics.enable", "True"));
            double absorbedEmission = mean(frame(context, "height-fog-emission-uniform"), mask);
            context.runOnClient(client -> api().setConfigVariable("rtx.volumetrics.heightFogFalloffMeters", "2"));
            double highEmission = mean(frame(context, "height-fog-emission-above"), mask);
            require(clearEmission > 20 && absorbedEmission < clearEmission * 0.5,
                    "Shared emissive radiance bypassed camera-medium absorption: " + clearEmission + " -> " + absorbedEmission);
            require(Math.abs(highEmission - clearEmission) < clearEmission * 0.1 + 1,
                    "High-altitude emissive radiance did not recover: " + clearEmission + " -> " + highEmission);
        } finally {
            context.runOnClient(client -> {
                for (int i = 0; i < controls.length; i++) api().setConfigVariable(controls[i][0], previous[i]);
            });
        }
    }

    private static double mean(BufferedImage image, BitSet mask) {
        long sum = 0;
        for (int i = mask.nextSetBit(0); i >= 0; i = mask.nextSetBit(i + 1)) {
            int rgb = image.getRGB(i % image.getWidth(), i / image.getWidth());
            sum += (rgb >>> 16 & 255) + (rgb >>> 8 & 255) + (rgb & 255);
        }
        return (double) sum / (mask.cardinality() * 3);
    }

    private static MaterialInfo emitter(long hash, float r, float g, float b) {
        return MaterialInfo.builder().hash(hash).emissiveIntensity(1).emissiveColorConstant(r, g, b)
                .opaqueExt(MaterialInfoOpaqueEXT.builder().albedoConstant(0, 0, 0).roughnessConstant(1).build()).build();
    }

    private static Pixels capture(ClientGameTestContext context, String name) {
        var image = frame(context, name);
        int red = 0, green = 0, blue = 0, yellow = 0;
        long blueY = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int r = rgb >>> 16 & 255, g = rgb >>> 8 & 255, b = rgb & 255;
                if (r > g + 100 && r > b + 100) red++;
                if (g > r + 100 && g > b + 100) green++;
                if (b > r + 100 && b > g + 100) { blue++; blueY += y; }
                if (r > b + 100 && g > b + 100) yellow++;
            }
        }
        return new Pixels(red, green, blue, yellow, blue == 0 ? 0 : (double) blueY / blue);
    }

    private static boolean closeCount(int a, int b) { return Math.abs(a - b) < Math.max(20, a * 0.03); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static RemixAPI api() { return RtxRemixMod.renderer.getRemixApi(); }
    private record Pixels(int red, int green, int blue, int yellow, double blueY) {}

    private static float[] transform(float x, float y) {
        Transform transform = Transform.identity();
        transform.setTranslation(x, y, 0);
        return transform.matrix;
    }

    private static MaterialInfo material(long hash, float r, float g, float b) {
        return MaterialInfo.builder().hash(hash)
                .opaqueExt(MaterialInfoOpaqueEXT.builder().albedoConstant(r, g, b).roughnessConstant(1).build()).build();
    }

    private static long mesh(long hash, long left, long right) {
        return api().createMesh(MeshInfo.builder().hash(hash).surfaces(new MeshInfoSurfaceTriangles[] {
                quad(left, -1.2f, -0.1f, true), quad(right, 0.1f, 1.2f, false)
        }).build());
    }

    private static MeshInfoSurfaceTriangles quad(long material, float left, float right, boolean compact) {
        var surface = new MeshInfoSurfaceTriangles();
        surface.material = material;
        surface.vertexCount = 4;
        int stride = compact ? HardcodedVertex.STORAGE_STRIDE : HardcodedVertex.STRIDE;
        surface.vertexBuffer = ByteBuffer.allocateDirect(4 * stride).order(ByteOrder.nativeOrder());
        for (HardcodedVertex vertex : new HardcodedVertex[] {
                HardcodedVertex.create(left, -0.6f, 0, 0.6f, 0, -0.8f, 0, 0, -1),
                HardcodedVertex.create(right, -0.6f, 0, 0.6f, 0, -0.8f, 1, 0, -1),
                HardcodedVertex.create(right, 0.6f, 0, 0.6f, 0, -0.8f, 1, 1, -1),
                HardcodedVertex.create(left, 0.6f, 0, 0.6f, 0, -0.8f, 0, 1, -1) }) {
            if (compact) vertex.writeCompactTo(surface.vertexBuffer);
            else vertex.writeTo(surface.vertexBuffer);
        }
        surface.flags = compact ? MeshInfoSurfaceTriangles.COMPACT_VERTICES : 0;
        surface.vertexBuffer.flip();
        surface.indexCount = 6;
        surface.indexBuffer = ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder());
        for (int index : new int[] {0, 1, 2, 0, 2, 3}) surface.indexBuffer.putInt(index);
        surface.indexBuffer.flip();
        return surface;
    }

    private static final class Probe {
        long red, green, yellow, mesh, replacement, first, second, leftTexture, rightTexture;
        long decalMaterial, decalMesh, decalInstance;
        void create() {
            red = api().createMaterial(material(0x7f001, 1, 0, 0));
            green = api().createMaterial(material(0x7f002, 0, 1, 0));
            mesh = mesh(0x7f004, red, green);
            first = api().sceneInstanceAdd(mesh, transform(-1.6f, 122), 0, 1, 0);
            second = api().sceneInstanceAdd(mesh, transform(1.6f, 122), 0, 1, 0);
        }
        void updateFirst(float x, float y, int flags) {
            api().sceneInstanceUpdate(first, mesh, transform(x, y), flags, 1, 0);
        }
        void setFlags(int flags) {
            updateFirst(-1.6f, 122, flags);
            api().sceneInstanceUpdate(second, mesh, transform(1.6f, 122), flags, 1, 0);
        }
        void createDecal() {
            decalMaterial = api().createMaterial(MaterialInfo.builder().hash(0x7f00a)
                    .opaqueExt(MaterialInfoOpaqueEXT.builder().albedoConstant(0, 0, 1)
                            .roughnessConstant(1).metallicConstant(0)
                            .blendType(MaterialInfoOpaqueEXT.BLEND_TYPE_ALPHA).build()).build());
            decalMesh = api().createMesh(MeshInfo.builder().hash(0x7f00b)
                    .surfaces(new MeshInfoSurfaceTriangles[] { quad(decalMaterial, -1.2f, -0.1f, false) }).build());
        }
        void showDecal() {
            float[] pose = transform(-1.6f, 122);
            pose[11] = -0.001f;
            decalInstance = api().sceneInstanceAdd(decalMesh, pose,
                    InstanceCategoryBit.DECAL_STATIC | InstanceCategoryBit.DECAL_NO_OFFSET, 1, 0);
        }
        void hideDecal() {
            if (decalInstance != 0) { api().sceneInstanceRemove(decalInstance); decalInstance = 0; }
        }
        void closeDecal() {
            hideDecal();
            if (decalMesh != 0) { api().destroyMesh(decalMesh); decalMesh = 0; }
            if (decalMaterial != 0) { api().destroyMaterial(decalMaterial); decalMaterial = 0; }
        }
        void addTextures() {
            leftTexture = texture(0x7f006, 0, 0, 255);
            rightTexture = texture(0x7f007, 0, 255, 0);
            MaterialInfo left = material(0x7f001, 1, 1, 1);
            left.albedoTexture = leftTexture;
            api().updateMaterial(red, left);
            MaterialInfo right = material(0x7f002, 1, 1, 1);
            right.albedoTexture = rightTexture;
            api().updateMaterial(green, right);
        }
        void patchTextures(boolean changed) {
            TexturePatchInfo info = new TexturePatchInfo();
            info.entries = new TextureBatchEntry[] {
                    patches(leftTexture, changed ? 255 : 0, 0, changed ? 0 : 255),
                    patches(rightTexture, changed ? 255 : 0, 255, 0)
            };
            api().updateTextureFromMemory(info);
        }

        void replaceWithImmutableTextures() {
            long oldLeft = leftTexture, oldRight = rightTexture;
            leftTexture = texture(0x7f008, 0, 0, 255, true);
            rightTexture = texture(0x7f009, 0, 255, 0, true);
            MaterialInfo left = material(0x7f001, 1, 1, 1);
            left.albedoTexture = leftTexture;
            api().updateMaterial(red, left);
            MaterialInfo right = material(0x7f002, 1, 1, 1);
            right.albedoTexture = rightTexture;
            api().updateMaterial(green, right);
            api().destroyTexture(oldLeft);
            api().destroyTexture(oldRight);
            boolean rejected = false;
            try {
                patchTextures(true);
            } catch (com.nvidia.rtxremix.RemixException expected) {
                rejected = expected.getErrorCode() == com.nvidia.rtxremix.ErrorCode.INVALID_ARGUMENTS;
            }
            require(rejected, "Immutable textures accepted a patch after discarding CPU pixels");
        }

        private static long texture(long hash, int r, int g, int b) {
            return texture(hash, r, g, b, false);
        }

        private static long texture(long hash, int r, int g, int b, boolean immutable) {
            TextureFromMemoryInfo info = new TextureFromMemoryInfo();
            info.hash = hash;
            info.immutable = immutable;
            info.width = info.height = 2;
            info.pixelData = ByteBuffer.allocateDirect(16);
            for (int i = 0; i < 4; i++) info.pixelData.put((byte) r).put((byte) g).put((byte) b).put((byte) 255);
            return api().createTextureFromMemory(info);
        }
        private static TextureBatchEntry patches(long texture, int r, int g, int b) {
            TextureBatchEntry entry = new TextureBatchEntry();
            entry.handle = texture;
            entry.patches = new TexturePatch[2];
            for (int x = 0; x < 2; x++) {
                TexturePatch patch = entry.patches[x] = new TexturePatch();
                patch.dstX = x;
                patch.width = 1;
                patch.height = 2;
                patch.rowPitch = 8;
                ByteBuffer data = ByteBuffer.allocateDirect(19).position(7).slice();
                for (int y = 0; y < 2; y++) {
                    data.position(y * patch.rowPitch).put((byte) r).put((byte) g).put((byte) b).put((byte) 255);
                }
                patch.pixelData = data.position(1).limit(1).asReadOnlyBuffer();
            }
            return entry;
        }
        void removeInstances() {
            if (first != 0) { api().sceneInstanceRemove(first); first = 0; }
            if (second != 0) { api().sceneInstanceRemove(second); second = 0; }
        }
        void close() {
            closeDecal();
            removeInstances();
            if (replacement != 0) api().destroyMesh(replacement);
            if (mesh != 0) api().destroyMesh(mesh);
            if (yellow != 0) api().destroyMaterial(yellow);
            if (green != 0) api().destroyMaterial(green);
            if (red != 0) api().destroyMaterial(red);
            if (leftTexture != 0) api().destroyTexture(leftTexture);
            if (rightTexture != 0) api().destroyTexture(rightTexture);
        }
    }
}
