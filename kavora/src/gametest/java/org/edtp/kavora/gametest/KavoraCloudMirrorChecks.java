/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.gametest;

import org.edtp.kavora.sdk.HardcodedVertex;
import org.edtp.kavora.sdk.InstanceCategoryBit;
import org.edtp.kavora.sdk.MaterialInfo;
import org.edtp.kavora.sdk.MaterialInfoOpaqueEXT;
import org.edtp.kavora.sdk.MaterialInfoOpaqueSubsurfaceEXT;
import org.edtp.kavora.sdk.MaterialInfoTranslucentEXT;
import org.edtp.kavora.sdk.MeshInfo;
import org.edtp.kavora.sdk.MeshInfoSurfaceTriangles;
import org.edtp.kavora.sdk.RemixAPI;
import org.edtp.kavora.sdk.Transform;
import org.edtp.kavora.KavoraMod;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CloudStatus;
import net.minecraft.world.level.GameType;

/** A fixed cloud slab seen through water and from the geometrically equivalent mirror camera. */
final class KavoraCloudMirrorChecks {
    private static final String[] KEYS = {
            "rtx.debugView.debugViewIdx", "rtx.debugView.maxValue", "rtx.debugView.replaceCompositeOutput",
            "rtx.debugView.accumulation.enable", "rtx.debugView.accumulation.numberOfFramesToAccumulate",
            "rtx.enableFog", "rtx.volumetrics.enable", "rtx.viewDistance.distanceMode",
            "rtx.sparseRendering.enableSparseRendering", "rtx.useRTXDI",
            "rtx.spatiallyHashedRadianceCache.enableResolve", "rtx.translucentMaterial.normalIntensity",
            "rtx.graphicsPreset", "rtx.integrateIndirectMode", "rtx.neeCache.enable",
            "rtx.enableTransmissionApproximationInIndirectRays", "rtx.enableFirstBounceLobeProbabilityDithering",
            "rtx.enablePSRR", "rtx.enablePSTR"
    };

    private KavoraCloudMirrorChecks() { }

    static void run(ClientGameTestContext context, TestSingleplayerContext world) {
        String[] previous = context.computeOnClient(client -> Arrays.stream(KEYS)
                .map(api()::getConfigVariableResolved).toArray(String[]::new));
        CloudStatus clouds = context.computeOnClient(client -> client.options.cloudStatus().get());
        int fov = context.computeOnClient(client -> client.options.fov().get());
        boolean hidden = context.computeOnClient(client -> client.gui.hud.isHidden());
        Probe probe = new Probe();
        try {
            context.runOnClient(client -> {
                client.options.cloudStatus().set(CloudStatus.OFF);
                client.options.fov().set(70);
                if (!hidden) client.gui.hud.toggle();
            });
            world.getServer().runOnServer(server -> {
                KavoraSceneFixtures.commands(server, "weather clear");
                server.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, false, server);
                server.clockManager().setTotalTicks(server.registryAccess()
                        .get(net.minecraft.world.clock.WorldClocks.OVERWORLD).orElseThrow(), 6000);
            });
            options(context, Arrays.copyOf(KEYS, 12), new String[]{"0", "64", "False", "False", "256",
                    "False", "False", "0", "False", "False", "False", "0"});
            camera(context, world, 110);
            context.runOnClient(client -> probe.create());
            if (Boolean.getBoolean("kavora.gametest.sharedRadianceOnly")) {
                assertSharedRadianceLinearity(context, probe);
                return;
            }
            context.runOnClient(client -> System.out.println("Mirror probe options: "
                    + Arrays.toString(Arrays.stream(KEYS).map(k -> k + "=" + api().getConfigVariableResolved(k)).toArray())));
            capture(context, "water", new int[]{0, 150, 155, 156, 158, 159, 160, 203, 204, 205, 206, 202});
            options(context, new String[]{KEYS[12]}, new String[]{"4"});
            options(context, new String[]{KEYS[13], KEYS[14], KEYS[15]}, new String[]{"0", "False", "False"});
            var plainPath = capture(context, "plain-path", new int[]{0, 150, 156, 159});
            options(context, new String[]{KEYS[16]}, new String[]{"False"});
            var randomLobe = capture(context, "random-lobe", new int[]{150, 159});
            options(context, new String[]{KEYS[16]}, new String[]{"True"});
            var ditheredLobe = capture(context, "dithered-lobe", new int[]{150});
            context.runOnClient(client -> probe.openWaterAperture());
            camera(context, world, 90);
            var mirror = capture(context, "mirror-camera", new int[]{0, 150, 155, 156, 157});
            requireEquivalentRadiance(plainPath.get(150), mirror.get(150));
            requireEquivalentRadiance(randomLobe.get(150), mirror.get(150));
            requireEquivalentRadiance(ditheredLobe.get(150), mirror.get(150));
            camera(context, world, 110);
            assertSharedRadianceLinearity(context, probe);
        } finally {
            context.runOnClient(client -> {
                probe.close();
                client.options.cloudStatus().set(clouds);
                client.options.fov().set(fov);
                if (!hidden) client.gui.hud.toggle();
            });
            options(context, KEYS, previous);
        }
    }

    private static void camera(ClientGameTestContext context, TestSingleplayerContext world, double eyeY) {
        world.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            player.setGameMode(GameType.CREATIVE);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            if (!player.teleportTo(server.overworld(), 0, eyeY - player.getEyeHeight(), 0, Set.of(), 0, 0, true)) {
                throw new AssertionError("Cloud mirror camera teleport failed");
            }
        });
        context.waitFor(client -> Math.abs(client.player.getEyePosition().y - eyeY) < 0.001, 200);
    }

    private static Map<Integer, Path> capture(ClientGameTestContext context, String name, int[] indices) {
        Map<Integer, Path> screenshots = new HashMap<>();
        for (int index : indices) {
            options(context, new String[]{KEYS[0], KEYS[1], KEYS[3]},
                    new String[]{Integer.toString(index), index == 204 ? "1" : "64", "False"});
            if (index != 0) options(context, new String[]{KEYS[3]}, new String[]{"True"});
            long frame = context.computeOnClient(client -> KavoraFrameProbe.presents);
            context.waitFor(client -> KavoraFrameProbe.presents >= frame + 256, 1200);
            screenshots.put(index, KavoraWorldGameTest.captureCompletedFrame(context, "cloud-mirror-" + name + "-" + index));
        }
        return screenshots;
    }

    private static void requireEquivalentRadiance(Path reflected, Path mirrorCamera) {
        // These interior regions see the same cloud underside through the flat mirror and its camera aperture.
        // Raw radiance bypasses denoising, exposure, tonemapping, and water's Fresnel composite.
        double[] expected = meanRadiance(mirrorCamera, 180, 195);
        double[] actual = meanRadiance(reflected, 525, 540);
        for (int channel = 0; channel < 3; channel++) {
            double ratio = actual[channel] / expected[channel];
            if (expected[channel] < 0.005 || ratio < 0.85 || ratio > 1.15) {
                throw new AssertionError("Reflected cloud radiance differs from its mirror camera: "
                        + reflected + ", channel=" + channel + ", ratio=" + ratio);
            }
        }
        System.out.println("Cloud mirror raw indirect matched: " + reflected.getFileName()
                + ", reflected=" + Arrays.toString(actual) + ", mirror=" + Arrays.toString(expected));
    }

    private static void assertSharedRadianceLinearity(ClientGameTestContext context, Probe probe) {
        // A single thin pane adds its emission once, regardless of which PSR lobes read the sky.
        // Taking differences removes the sky contribution without depending on its absolute brightness.
        Path[] screenshots = new Path[4];
        for (int psr = 0; psr < 2; psr++) {
            options(context, new String[]{KEYS[17], KEYS[18]},
                    new String[]{Boolean.toString(psr != 0), Boolean.toString(psr != 0)});
            for (int emission = 0; emission < 2; emission++) {
                int level = emission;
                context.runOnClient(client -> probe.emissivePane(level));
                screenshots[psr * 2 + emission] = capture(context,
                        "shared-" + psr + "-" + emission, new int[]{205}).get(205);
            }
        }
        double[][] values = Arrays.stream(screenshots).map(path -> meanRadiance(path, 525, 540))
                .toArray(double[][]::new);
        for (int channel = 0; channel < 3; channel++) {
            double reference = values[1][channel] - values[0][channel];
            double measured = values[3][channel] - values[2][channel];
            double ratio = measured / reference;
            if (reference < 0.01 || ratio < 0.95 || ratio > 1.05) {
                throw new AssertionError("PSR repeated the pane's shared emission: channel="
                        + channel + ", noPSR=" + reference + ", PSR=" + measured + ", ratio=" + ratio);
            }
            System.out.println("PSR shared emission matched: channel=" + channel + ", ratio=" + ratio);
        }
    }

    private static double[] meanRadiance(Path path, int y0, int y1) {
        try {
            var image = ImageIO.read(path.toFile());
            if (image == null || image.getWidth() * 9 != image.getHeight() * 16) {
                throw new AssertionError("Cloud mirror probe requires a 16:9 image: " + path);
            }
            int x0 = 550 * image.getWidth() / 1280;
            int x1 = 730 * image.getWidth() / 1280;
            y0 = y0 * image.getHeight() / 720;
            y1 = y1 * image.getHeight() / 720;
            double[] sum = new double[3];
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    int rgb = image.getRGB(x, y);
                    sum[0] += (rgb >>> 16) & 255;
                    sum[1] += (rgb >>> 8) & 255;
                    sum[2] += rgb & 255;
                }
            }
            double divisor = 255.0 * (x1 - x0) * (y1 - y0);
            for (int i = 0; i < sum.length; i++) sum[i] /= divisor;
            return sum;
        } catch (IOException e) {
            throw new AssertionError("Could not read cloud mirror radiance: " + path, e);
        }
    }

    private static void options(ClientGameTestContext context, String[] keys, String[] values) {
        context.runOnClient(client -> {
            for (int i = 0; i < keys.length; i++) api().setConfigVariable(keys[i], values[i]);
        });
        context.waitFor(client -> {
            for (int i = 0; i < keys.length; i++) {
                if (!values[i].equalsIgnoreCase(api().getConfigVariableResolved(keys[i]))) return false;
            }
            return true;
        }, 100);
    }

    private static RemixAPI api() { return KavoraMod.renderer.getRemixApi(); }

    private static final class Probe {
        private long cloudMaterial, waterMaterial, cloudMesh, waterMesh, apertureMesh, cloudInstance, waterInstance;
        private final long[] paneMaterials = new long[2];
        private final long[] paneMeshes = new long[2];

        void create() {
            var subsurface = MaterialInfoOpaqueSubsurfaceEXT.builder().diffusionProfile(true)
                    .transmittanceColor(.999f, .999f, .999f).radius(.98f, .98f, .98f)
                    .radiusScale(5000).maxSampleRadius(1).volumetricAnisotropy(0).build();
            cloudMaterial = api().createMaterial(MaterialInfo.builder().hash(0x7c100)
                    .opaqueExt(MaterialInfoOpaqueEXT.builder().albedoConstant(.99f, .99f, .99f)
                            .roughnessConstant(1).metallicConstant(0).subsurfaceExt(subsurface).build()).build());
            waterMaterial = api().createMaterial(MaterialInfo.builder().hash(0x7c101)
                    .translucentExt(MaterialInfoTranslucentEXT.builder().refractiveIndex(1.33f)
                            .transmittanceColor(1, 1, 1).transmittanceMeasurementDistance(1).build()).build());
            cloudMesh = box(0x7c102, cloudMaterial, -30, 128, 90, 30, 132, 110);
            waterMesh = box(0x7c103, waterMaterial, -500, 99, -500, 500, 100, 500);
            int flags = InstanceCategoryBit.IGNORE_BAKED_LIGHTING;
            cloudInstance = api().sceneInstanceAdd(cloudMesh, Transform.identity().matrix, flags, 0, 0);
            waterInstance = api().sceneInstanceAdd(waterMesh, Transform.identity().matrix, flags, 0, 0);
        }

        private long box(long hash, long material, float x0, float y0, float z0, float x1, float y1, float z1) {
            return api().createMesh(MeshInfo.builder().hash(hash).surfaces(new MeshInfoSurfaceTriangles[]{
                    boxSurface(material, x0, y0, z0, x1, y1, z1)}).build());
        }

        private MeshInfoSurfaceTriangles boxSurface(long material, float x0, float y0, float z0, float x1, float y1, float z1) {
            float[][] corners = {{x0,y0,z0}, {x1,y0,z0}, {x1,y1,z0}, {x0,y1,z0},
                    {x0,y0,z1}, {x1,y0,z1}, {x1,y1,z1}, {x0,y1,z1}};
            int[][] faces = {{3,2,1,0}, {4,5,6,7}, {0,4,7,3}, {2,6,5,1}, {1,5,4,0}, {3,7,6,2}};
            float[][] normals = {{0,0,-1}, {0,0,1}, {-1,0,0}, {1,0,0}, {0,-1,0}, {0,1,0}};
            var surface = new MeshInfoSurfaceTriangles();
            surface.material = material;
            surface.vertexCount = 24;
            surface.vertexBuffer = ByteBuffer.allocateDirect(24 * HardcodedVertex.STRIDE).order(ByteOrder.nativeOrder());
            surface.indexCount = 36;
            surface.indexBuffer = ByteBuffer.allocateDirect(36 * Integer.BYTES).order(ByteOrder.nativeOrder());
            for (int face = 0; face < faces.length; face++) {
                float[] n = normals[face];
                for (int vertex = 0; vertex < 4; vertex++) {
                    float[] p = corners[faces[face][vertex]];
                    HardcodedVertex.create(p[0], p[1], p[2], n[0], n[1], n[2], 0, 0, -1).writeTo(surface.vertexBuffer);
                }
                for (int index : new int[]{0, 1, 2, 0, 2, 3}) surface.indexBuffer.putInt(face * 4 + index);
            }
            surface.vertexBuffer.flip();
            surface.indexBuffer.flip();
            return surface;
        }

        void openWaterAperture() {
            // Keep the surrounding water for indirect illumination. Only the camera-to-cloud rays cross this opening.
            apertureMesh = api().createMesh(MeshInfo.builder().hash(0x7c104).surfaces(new MeshInfoSurfaceTriangles[]{
                    boxSurface(waterMaterial, -500, 99, -500, -10, 100, 500),
                    boxSurface(waterMaterial, 10, 99, -500, 500, 100, 500),
                    boxSurface(waterMaterial, -10, 99, -500, 10, 100, 20),
                    boxSurface(waterMaterial, -10, 99, 31, 10, 100, 500)
            }).build());
            api().sceneInstanceUpdate(waterInstance, apertureMesh, Transform.identity().matrix,
                    InstanceCategoryBit.IGNORE_BAKED_LIGHTING, 0, 0);
        }

        void removeWater() {
            if (waterInstance != 0) { api().sceneInstanceRemove(waterInstance); waterInstance = 0; }
        }

        void emissivePane(int emission) {
            if (cloudInstance != 0) { api().sceneInstanceRemove(cloudInstance); cloudInstance = 0; }
            if (paneMeshes[emission] == 0) {
                paneMaterials[emission] = api().createMaterial(MaterialInfo.builder().hash(0x7c110 + emission)
                        .emissiveIntensity(emission * 4).emissiveColorConstant(1, 1, 1)
                        .translucentExt(MaterialInfoTranslucentEXT.builder().refractiveIndex(1.33f)
                                .transmittanceColor(1, 1, 1).thinWall(.001f).build()).build());
                var surface = new MeshInfoSurfaceTriangles();
                surface.material = paneMaterials[emission];
                surface.vertexCount = 4;
                surface.vertexBuffer = ByteBuffer.allocateDirect(4 * HardcodedVertex.STRIDE).order(ByteOrder.nativeOrder());
                for (float[] p : new float[][]{{-500,100,-500}, {-500,100,500}, {500,100,500}, {500,100,-500}}) {
                    HardcodedVertex.create(p[0], p[1], p[2], 0, 1, 0, 0, 0, -1).writeTo(surface.vertexBuffer);
                }
                surface.vertexBuffer.flip();
                surface.indexCount = 6;
                surface.indexBuffer = ByteBuffer.allocateDirect(6 * Integer.BYTES).order(ByteOrder.nativeOrder());
                for (int i : new int[]{0, 1, 2, 0, 2, 3}) surface.indexBuffer.putInt(i);
                surface.indexBuffer.flip();
                paneMeshes[emission] = api().createMesh(MeshInfo.builder().hash(0x7c112 + emission)
                        .surfaces(new MeshInfoSurfaceTriangles[]{surface}).build());
            }
            api().sceneInstanceUpdate(waterInstance, paneMeshes[emission], Transform.identity().matrix,
                    InstanceCategoryBit.IGNORE_BAKED_LIGHTING, 0, 0);
        }

        void close() {
            removeWater();
            if (cloudInstance != 0) api().sceneInstanceRemove(cloudInstance);
            if (waterMesh != 0) api().destroyMesh(waterMesh);
            if (apertureMesh != 0) api().destroyMesh(apertureMesh);
            if (cloudMesh != 0) api().destroyMesh(cloudMesh);
            if (waterMaterial != 0) api().destroyMaterial(waterMaterial);
            if (cloudMaterial != 0) api().destroyMaterial(cloudMaterial);
            for (long mesh : paneMeshes) if (mesh != 0) api().destroyMesh(mesh);
            for (long material : paneMaterials) if (material != 0) api().destroyMaterial(material);
        }
    }
}
