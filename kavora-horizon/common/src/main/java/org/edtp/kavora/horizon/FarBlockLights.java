/* SPDX-License-Identifier: LGPL-3.0-only */
package org.edtp.kavora.horizon;

import org.edtp.kavora.horizon.voxel.VoxelKey;
import org.edtp.kavora.light.BlockLightKey;
import org.edtp.kavora.light.BlockLightRuleManager;
import org.edtp.kavora.light.LightEntry;
import org.edtp.kavora.sdk.RemixAPI;
import org.edtp.kavora.terrain.TerrainLight;
import org.edtp.kavora.texture.BlockTextureManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Retains analytic lights independently of the selected terrain mesh level. */
final class FarBlockLights {
    private static final int CREATES_PER_FRAME = 64;
    private static final BlockLightRuleManager.ModelSampler SOURCE_MODEL =
            BlockLightRuleManager.fixedCentroid(new float[] {0.5f, 0.5f, 0.5f});

    private static final class Emitter {
        final LightEntry entry;
        final float power;
        TerrainLight light;

        Emitter(LightEntry entry, float power) {
            this.entry = entry;
            this.power = power;
        }

        void release() {
            if (light != null) light.light.destroy();
            light = null;
        }
    }

    private final Map<BlockLightKey, Emitter> candidates = new LinkedHashMap<>();
    private long sourceRevision = Long.MIN_VALUE, ruleRevision = Long.MIN_VALUE;
    private long cameraCell, frame;
    private float threshold;
    private int horizonRadius;
    private float maxPower;

    void draw(VoxelSession session, RemixAPI api, BlockTextureManager textures, Vec3 camera,
              float threshold, int horizonRadius, Predicate<BlockLightKey> ownedByNear) {
        long sourceRevision = session.blockSourceRevision();
        if (sourceRevision == 0) return;
        long ruleRevision = BlockLightRuleManager.revision();
        long cameraCell = VoxelKey.containing(0, (int) Math.floor(camera.x),
                (int) Math.floor(camera.y), (int) Math.floor(camera.z));
        if (ruleRevision != this.ruleRevision) {
            clear();
            this.ruleRevision = ruleRevision;
            maxPower = 0;
            for (int source = 1; source <= 31; source++) {
                if ((source & 15) == 0) continue;
                for (LightEntry entry : resolve(BlockPos.ZERO, source))
                    maxPower = Math.max(maxPower, TerrainLight.emittedPower(entry, textures));
            }
        }
        if (sourceRevision != this.sourceRevision || cameraCell != this.cameraCell
                || threshold != this.threshold || horizonRadius != this.horizonRadius) {
            refresh(session, textures, cameraCell, threshold, horizonRadius);
            this.sourceRevision = sourceRevision;
            this.cameraCell = cameraCell;
            this.threshold = threshold;
            this.horizonRadius = horizonRadius;
        }

        frame++;
        int created = 0;
        for (Emitter emitter : candidates.values()) {
            LightEntry entry = emitter.entry;
            if (ownedByNear.test(entry.key())) continue;
            if (emitter.light == null) {
                if (created >= CREATES_PER_FRAME || !TerrainLight.withinReach(entry.x(), entry.y(), entry.z(),
                        emitter.power, camera.x, camera.y, camera.z, threshold)) continue;
                emitter.light = TerrainLight.create(entry, textures);
                created++;
            }
            if (emitter.light.shouldDraw(camera.x, camera.y, camera.z, threshold, frame)) {
                emitter.light.light.drawFast(api);
            }
        }
    }

    private void refresh(VoxelSession session, BlockTextureManager textures, long cameraCell,
                         float threshold, int horizonRadius) {
        double reach = Math.min(horizonRadius, TerrainLight.reach(maxPower, threshold));
        Map<BlockLightKey, Emitter> next = new LinkedHashMap<>();
        if (reach > 0) {
            // Both endpoints can lie anywhere in their cells. Leave one cell of padding.
            int radius = (int) Math.ceil(reach / VoxelKey.SIDE) + 1;
            session.forEachBlockSourceSection((section, packedSources) -> {
                if (Math.abs(VoxelKey.x(section) - VoxelKey.x(cameraCell)) > radius
                        || Math.abs(VoxelKey.y(section) - VoxelKey.y(cameraCell)) > radius
                        || Math.abs(VoxelKey.z(section) - VoxelKey.z(cameraCell)) > radius) return;
                for (int packed : packedSources) {
                    int index = packed & (VoxelKey.VOLUME - 1);
                    int source = packed / VoxelKey.VOLUME;
                    BlockPos pos = new BlockPos(VoxelKey.blockX(section) + (index & 31),
                            VoxelKey.blockY(section) + (index >>> 10),
                            VoxelKey.blockZ(section) + (index >>> 5 & 31));
                    for (LightEntry entry : resolve(pos, source)) {
                        Emitter previous = candidates.get(entry.key());
                        if (previous != null && previous.entry.equals(entry)) {
                            next.put(entry.key(), previous);
                        } else {
                            float power = TerrainLight.emittedPower(entry, textures);
                            if (power > 0) next.put(entry.key(), new Emitter(entry, power));
                        }
                    }
                }
            });
        }
        candidates.forEach((key, previous) -> {
            if (next.get(key) != previous) previous.release();
        });
        candidates.clear();
        candidates.putAll(next);
    }

    private static java.util.List<LightEntry> resolve(BlockPos pos, int source) {
        var state = Blocks.LIGHT.defaultBlockState()
                .setValue(LightBlock.LEVEL, source & 15)
                .setValue(LightBlock.WATERLOGGED, (source & MaterialCatalog.WATERLOGGED_SOURCE) != 0);
        return BlockLightRuleManager.resolve(state, pos, SOURCE_MODEL);
    }

    void clear() {
        candidates.values().forEach(Emitter::release);
        candidates.clear();
        sourceRevision = Long.MIN_VALUE;
        ruleRevision = Long.MIN_VALUE;
    }
}
