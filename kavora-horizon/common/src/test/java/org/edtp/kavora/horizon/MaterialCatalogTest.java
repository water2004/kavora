/* SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package org.edtp.kavora.horizon;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.*;

class MaterialCatalogTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void parallelRegistrationPublishesCompleteEntriesAndRestorableIdentities() throws Exception {
        var catalog = new MaterialCatalog();
        var palette = new TerrainMaterialPalette(null);
        catalog.view(palette);
        BlockState[] states = {Blocks.STONE.defaultBlockState(),
                Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true),
                Blocks.WATER.defaultBlockState()};
        String[] biomes = {"minecraft:plains", "minecraft:forest", "minecraft:desert", "minecraft:ocean"};
        var start = new CyclicBarrier(8);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Future<?>>();
            for (int worker = 0; worker < 8; worker++) {
                tasks.add(pool.submit(() -> {
                    start.await();
                    for (int repeat = 0; repeat < 64; repeat++)
                        for (String biome : biomes)
                            for (BlockState state : states) {
                                int id = catalog.register(state, biome);
                                var entry = catalog.entry(id);
                                assertSame(state, entry.key().state());
                                assertEquals(biome, entry.key().biome());
                                if (state == states[1]) {
                                    assertSame(states[2], catalog.entry(entry.fluidMaterial()).key().state());
                                    assertEquals(biome, catalog.entry(entry.fluidMaterial()).key().biome());
                                }
                            }
                    return null;
                }));
            }
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
        }
        var dictionary = catalog.drainDictionary();
        // Observation discovers each model before any mesh requests a face; biomes share that model.
        assertTrue(palette.diagnostics().startsWith("revision=0/pending=3/"), palette.diagnostics());
        assertEquals(states.length * biomes.length, dictionary.size());
        assertTrue(catalog.drainDictionary().isEmpty());
        var restored = new MaterialCatalog();
        restored.restore(dictionary);
        for (String biome : biomes)
            for (BlockState state : states)
                assertEquals(catalog.register(state, biome), restored.register(state, biome));
    }
}
