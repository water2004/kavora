/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.entity;

import com.mojang.blaze3d.vertex.PoseStack;

import org.edtp.kavora.sdk.InstanceCategoryBit;
import org.edtp.kavora.sdk.Transform;
import org.edtp.kavora.texture.BlockTextureManager;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.phys.Vec3;

/**
 * Captures Minecraft's real first-person arm/item render and submits it as
 * Remix view-model geometry.
 *
 * <p>Minecraft remains responsible for hand selection, swing/equip/use
 * animations, maps, and item-specific transforms. The captured rigid-part
 * transforms include the hand FOV and scale. They use world axes with the
 * camera eye as the origin so Remix can trace the same pose at any world position.
 */
public final class KavoraFirstPersonPass implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(KavoraFirstPersonPass.class);
    private static final String CAPTURE_TYPE_KEY = "kavora:first_person_model";
    private static final float[] EMPTY_BONES = new float[0];

    /**
     * Camera-relative scale used to pull first-person geometry in front of
     * nearby world surfaces. This is baked into the captured pivots.
     */
    public static final float VIEW_MODEL_SCALE = 0.1f;

    private final KavoraVertexConsumerProvider provider = new KavoraVertexConsumerProvider();
    private final KavoraSubmitNodeCollector collector = new KavoraSubmitNodeCollector(provider);
    private final Transform eyeToWorldAxes = new Transform();
    private final Transform heldLightTransform = new Transform();
    private final Matrix4f correctedBone = new Matrix4f();
    private final Matrix4f viewCorrection = new Matrix4f();
    private final Matrix4f inverseModelView = new Matrix4f();
    private final Vector3f eye = new Vector3f();

    private KavoraEntityPass.EntityCaptureResult pendingResult;
    private Vec3 pendingMainHandLightPosition;
    private Vec3 pendingOffHandLightPosition;
    private float[] reusableBoneData = EMPTY_BONES;

    /**
     * Runs the supplied vanilla held-item renderer against a Remix-only
     * {@link KavoraSubmitNodeCollector}.
     */
    public void capture(FirstPersonHandsAndItemsRenderer heldItemRenderer,
                        float tickDelta,
                        PoseStack matrices,
                        LocalPlayer player,
                        PlayerRenderState playerState, Matrix4fc capturedModelView) {
        pendingResult = null;
        pendingMainHandLightPosition = null;
        pendingOffHandLightPosition = null;

        Minecraft client = Minecraft.getInstance();
        if (client == null || client.gameRenderer == null || player == null) {
            return;
        }

        provider.beginEntity();
        EntityRenderContext context = EntityRenderContext.get();
        context.begin(true);

        try {
            // Vanilla roots the hand stack at the inverse camera rotation and carries
            // the camera rotation, tilt and bob in the model-view, so the model-view's
            // origin is where the eye sits in the capture's space.
            Matrix4f modelView = new Matrix4f(capturedModelView);
            modelView.invert(inverseModelView).transformPosition(eye.set(0.0f, 0.0f, 0.0f));
            context.setEye(eye.x, eye.y, eye.z);

            heldItemRenderer.submitHandsWithItems(tickDelta, matrices, collector, playerState,
                    playerState.firstPersonHandsAndItems);
            collector.capture();

            List<KavoraVertexConsumerProvider.CapturedLayer> layers = provider.endEntity();
            if (layers.isEmpty()) {
                return;
            }

            Camera camera = client.gameRenderer.mainCamera();
            float fovScale = calculateFovScale(client, camera, tickDelta);
            setViewCorrection(viewCorrection, modelView, fovScale);

            int boneCount = context.getBoneCount();
            reusableBoneData = snapshotCorrectedBones(context, boneCount, reusableBoneData);
            setWorldAxes(camera, eyeToWorldAxes);
            Vec3 cameraPosition = camera.position();
            pendingMainHandLightPosition = findHeldBlockLightPosition(
                    layers, context, reusableBoneData, boneCount, InteractionHand.MAIN_HAND, cameraPosition);
            pendingOffHandLightPosition = findHeldBlockLightPosition(
                    layers, context, reusableBoneData, boneCount, InteractionHand.OFF_HAND, cameraPosition);

            pendingResult = new KavoraEntityPass.EntityCaptureResult(
                    player,
                    Vec3.ZERO,
                    0.0f,
                    layers,
                    reusableBoneData,
                    boneCount,
                    CAPTURE_TYPE_KEY,
                    false);
        } catch (Exception e) {
            pendingResult = null;
            pendingMainHandLightPosition = null;
            pendingOffHandLightPosition = null;
            LOGGER.error("Error capturing first-person model", e);
        } finally {
            context.reset();
        }
    }

    public boolean hasPendingCapture() {
        return pendingResult != null;
    }

    /** World-space emitter anchor used by the shaped held-beacon light path. */
    public Vec3 getPendingHeldLightPosition(EquipmentSlot slot) {
        if (pendingResult == null) return null;
        return switch (slot) {
            case MAINHAND -> pendingMainHandLightPosition;
            case OFFHAND -> pendingOffHandLightPosition;
            default -> null;
        };
    }

    /**
     * Submits the most recently captured first-person model. The result must be
     * consumed before the next {@link #capture} call because captured vertex
     * arrays are pooled by {@link KavoraVertexConsumerProvider}.
     */
    public void submitPending(KavoraEntitySubmitter submitter) {
        KavoraEntityPass.EntityCaptureResult result = pendingResult;
        pendingResult = null;
        if (result == null) {
            return;
        }
        submitter.submitEntity(result, eyeToWorldAxes,
                InstanceCategoryBit.VIEW_MODEL | InstanceCategoryBit.CAMERA_RELATIVE_VIEW_MODEL,
                KavoraEntitySubmitter.NO_EXTRA_CATEGORIES);
    }

    public void clearPending() {
        pendingResult = null;
        pendingMainHandLightPosition = null;
        pendingOffHandLightPosition = null;
    }

    private Vec3 findHeldBlockLightPosition(
            List<KavoraVertexConsumerProvider.CapturedLayer> layers,
            EntityRenderContext context,
            float[] transformData,
            int transformCount,
            InteractionHand hand,
            Vec3 cameraPosition) {
        KavoraCapturingConsumer bestConsumer = null;
        int bestTransform = -1;
        int bestVertexCount = 0;

        // A consumer may be reused for both hands. Pick the transform carrying
        // the most block-atlas vertices for this hand; that is the baked item,
        // while skin/arm cuboids use the player texture instead.
        for (KavoraVertexConsumerProvider.CapturedLayer layer : layers) {
            if (!BlockTextureManager.BLOCK_ATLAS_ID.equals(layer.textureId())
                    && !TextureAtlas.LOCATION_ITEMS.equals(layer.textureId())) continue;
            KavoraCapturingConsumer consumer = layer.consumer();
            int[] indices = consumer.getBoneIndices();
            for (int candidate = 0; candidate < transformCount; candidate++) {
                if (context.handForTransform(candidate) != hand) continue;
                int count = 0;
                for (int i = 0; i < layer.vertexCount(); i++) {
                    if (indices[i] == candidate) count++;
                }
                if (count > bestVertexCount) {
                    bestVertexCount = count;
                    bestTransform = candidate;
                    bestConsumer = consumer;
                }
            }
        }
        if (bestConsumer == null || bestVertexCount == 0) return null;

        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        int[] indices = bestConsumer.getBoneIndices();
        float[] px = bestConsumer.getPx(), py = bestConsumer.getPy(), pz = bestConsumer.getPz();
        for (int i = 0; i < bestConsumer.getVertexCount(); i++) {
            if (indices[i] != bestTransform) continue;
            minX = Math.min(minX, px[i]); maxX = Math.max(maxX, px[i]);
            minY = Math.min(minY, py[i]); maxY = Math.max(maxY, py[i]);
            minZ = Math.min(minZ, pz[i]); maxZ = Math.max(maxZ, pz[i]);
        }

        KavoraEntitySubmitter.composeRigidTransform(eyeToWorldAxes, transformData,
                transformCount, bestTransform, heldLightTransform);
        return transformPoint(heldLightTransform,
                (minX + maxX) * 0.5f,
                (minY + maxY) * 0.5f,
                (minZ + maxZ) * 0.5f).add(cameraPosition);
    }

    static Vec3 transformPoint(Transform transform, float x, float y, float z) {
        float[] m = transform.matrix;
        return new Vec3(
                m[0] * x + m[1] * y + m[2] * z + m[3],
                m[4] * x + m[5] * y + m[6] * z + m[7],
                m[8] * x + m[9] * y + m[10] * z + m[11]);
    }

    private float[] snapshotCorrectedBones(EntityRenderContext context,
                                           int boneCount,
                                           float[] reuse) {
        if (boneCount == 0) {
            return EMPTY_BONES;
        }

        int required = boneCount * 12;
        float[] data = reuse.length >= required ? reuse : new float[required];
        List<Matrix4f> bones = context.getBoneTransforms();
        for (int i = 0; i < boneCount; i++) {
            viewCorrection.mul(bones.get(i), correctedBone);
            int off = i * 12;
            data[off] = correctedBone.m00();
            data[off + 1] = correctedBone.m10();
            data[off + 2] = correctedBone.m20();
            data[off + 3] = correctedBone.m30();
            data[off + 4] = correctedBone.m01();
            data[off + 5] = correctedBone.m11();
            data[off + 6] = correctedBone.m21();
            data[off + 7] = correctedBone.m31();
            data[off + 8] = correctedBone.m02();
            data[off + 9] = correctedBone.m12();
            data[off + 10] = correctedBone.m22();
            data[off + 11] = correctedBone.m32();
        }
        return data;
    }

    private static float calculateFovScale(Minecraft client, Camera camera, float tickDelta) {
        double worldFov = camera.getFov();
        double handFov = client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.hudFov;
        double worldTangent = Math.tan(Math.toRadians(worldFov) * 0.5);
        double handTangent = Math.tan(Math.toRadians(handFov) * 0.5);
        if (!Double.isFinite(worldTangent) || !Double.isFinite(handTangent)
                || Math.abs(handTangent) < 1.0e-6) {
            return 1.0f;
        }
        return (float) (worldTangent / handTangent);
    }

    static void setViewCorrection(Matrix4f destination, Matrix4f modelView, float fovScale) {
        destination.identity()
                .scale(
                        fovScale * VIEW_MODEL_SCALE,
                        fovScale * VIEW_MODEL_SCALE,
                        VIEW_MODEL_SCALE)
                .mul(modelView);
    }

    /**
     * Expresses the hand axes in world space with translation left relative to the eye.
     */
    private static void setWorldAxes(Camera camera, Transform transform) {
        float yawRad = (float) Math.toRadians(-camera.yRot());
        float pitchRad = (float) Math.toRadians(camera.xRot());

        float forwardX = (float) (Math.sin(yawRad) * Math.cos(pitchRad));
        float forwardY = (float) (-Math.sin(pitchRad));
        float forwardZ = (float) (Math.cos(yawRad) * Math.cos(pitchRad));

        float rightX = (float) (-Math.cos(yawRad));
        float rightY = 0.0f;
        float rightZ = (float) Math.sin(yawRad);

        float upX = rightY * forwardZ - rightZ * forwardY;
        float upY = rightZ * forwardX - rightX * forwardZ;
        float upZ = rightX * forwardY - rightY * forwardX;

        transform.set(0, 0, rightX);
        transform.set(0, 1, upX);
        transform.set(0, 2, -forwardX);
        transform.set(1, 0, rightY);
        transform.set(1, 1, upY);
        transform.set(1, 2, -forwardY);
        transform.set(2, 0, rightZ);
        transform.set(2, 1, upZ);
        transform.set(2, 2, -forwardZ);
    }

    @Override
    public void close() {
        clearPending();
    }
}
