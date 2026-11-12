/* SPDX-License-Identifier: LGPL-3.0-only */
package org.edtp.kavora.horizon;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.BitSet;
import java.util.HexFormat;

/**
 * Observes delivered chunks only. No generation requests, forced chunks, or live-world worker
 * reads.
 */
public final class HorizonClient implements ClientModInitializer {
    private static volatile VoxelSession current;
    private static volatile ClientPacketListener currentConnection;
    private static java.util.concurrent.CompletableFuture<Void> previousClose =
            java.util.concurrent.CompletableFuture.completedFuture(null);
    private static final Long2ObjectOpenHashMap<BitSet> dirty = new Long2ObjectOpenHashMap<>();
    private static boolean beaconCacheReconciled;

    @Override
    public void onInitializeClient() {
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            VoxelSession store = session(level);
            store.observe(chunk);
            if (store.beacons.reconcile(chunk)) store.schedulePump();
        });
        ClientChunkEvents.CHUNK_UNLOAD.register(
                (level, chunk) -> {
                    if (current != null && current.level == level) {
                        current.observe(chunk);
                        dirty.remove(chunk.getPos().pack());
                    }
                });
        ClientTickEvents.END_CLIENT_TICK.register(
                client -> {
                    if (client.level == null) {
                        disconnect();
                        return;
                    }
                    VoxelSession store = session(client.level);
                    store.checkFailure();
                    if (!beaconCacheReconciled && store.ready()) {
                        beaconCacheReconciled = true;
                        store.beacons.forEachColumn((column, ignored) -> {
                            var chunk = client.level.getChunkSource().getChunk(ChunkPos.getX(column), ChunkPos.getZ(column), false);
                            if (chunk != null && store.beacons.reconcile(chunk)) store.schedulePump();
                        });
                    }
                    for (var it = dirty.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
                        var entry = it.next();
                        long key = entry.getLongKey();
                        BitSet sections = entry.getValue();
                        it.remove();
                        int x = ChunkPos.getX(key), z = ChunkPos.getZ(key);
                        var chunk = client.level.getChunkSource().getChunk(x, z, false);
                        if (chunk != null) store.observeSections(chunk, sections);
                    }
                });
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> {
                    VoxelSession departed = current;
                    if (departed != null && currentConnection == handler)
                        client.execute(
                                () -> {
                                    if (current == departed) disconnect();
                                });
                });
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING
                .register(
                        client -> {
                            disconnect();
                            previousClose.join();
                        });
    }

    static VoxelSession session(ClientLevel level) {
        if (current == null || current.level != level) {
            disconnect();
            currentConnection = Minecraft.getInstance().getConnection();
            current = new VoxelSession(level, cachePath(level), previousClose);
        }
        return current;
    }

    public static void changed(ClientLevel level, BlockPos pos, BlockState state) {
        if (current != null && current.level == level) {
            if (!state.is(Blocks.BEACON) && current.beacons.forget(pos)) current.schedulePump();
            int section = (pos.getY() >> 4) - level.getMinSectionY();
            if (section < 0 || section >= level.getSectionsCount()) return;
            BitSet sections =
                    dirty.computeIfAbsent(
                            ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4),
                            ignored -> new BitSet());
            sections.set(section);
            // The top layer of the section below depends on this section's bottom layer.
            if ((pos.getY() & 15) == 0 && section > 0) sections.set(section - 1);
        }
    }

    public static void observedBeacon(ClientLevel level, BeaconBlockEntity beacon) {
        if (current == null || current.level != level) return;
        if (!hasBeaconBase(level, beacon.getBlockPos())) return;
        if (current.beacons.observe(beacon)) current.schedulePump();
    }

    /** The beam column and first pyramid layer must be resident to determine whether a beacon is active. */
    public static boolean hasBeaconBase(ClientLevel level, BlockPos pos) {
        int minX = (pos.getX() - 1) >> 4, maxX = (pos.getX() + 1) >> 4;
        int minZ = (pos.getZ() - 1) >> 4, maxZ = (pos.getZ() + 1) >> 4;
        var chunks = level.getChunkSource();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (chunks.getChunk(x, z, false) == null) return false;
            }
        }
        return true;
    }

    private static void disconnect() {
        if (current != null) {
            previousClose = current.close();
            current = null;
        }
        currentConnection = null;
        beaconCacheReconciled = false;
        dirty.clear();
    }

    private static Path cachePath(ClientLevel world) {
        Minecraft client = Minecraft.getInstance();
        Path root;
        if (client.getSingleplayerServer() != null)
            root =
                    client.getSingleplayerServer()
                            .getWorldPath(LevelResource.ROOT)
                            .resolve("kavora-horizon");
        else {
            var server = client.getCurrentServer();
            String address = server == null ? "local-connection" : server.ip;
            root =
                    client.gameDirectory
                            .toPath()
                            .resolve("kavora-horizon/servers")
                            .resolve(hash(address));
        }
        return root.resolve(hash(world.dimension().identifier().toString())).resolve("voxels-v1");
    }

    private static String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)),
                            0,
                            12);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
