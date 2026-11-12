/* SPDX-License-Identifier: LGPL-3.0-only */
package org.edtp.kavora.horizon.mixin;

import java.util.List;
import org.edtp.kavora.horizon.HorizonClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeaconBeamOwner;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BeaconBlockEntity.class)
abstract class BeaconBlockEntityMixin {
    @Shadow private List<BeaconBeamOwner.Section> beamSections;
    @Unique private boolean horizons$baseChecked;

    @Inject(method = "tick", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER,
            target = "Lnet/minecraft/world/level/block/entity/BeaconBlockEntity;levels:I"))
    private static void horizons$recordBaseCheck(Level level, BlockPos pos, BlockState state,
                                                  BeaconBlockEntity beacon, CallbackInfo ci) {
        if (level instanceof ClientLevel client) {
            ((BeaconBlockEntityMixin) (Object) beacon).horizons$baseChecked = HorizonClient.hasBeaconBase(client, pos);
        }
    }

    @Inject(method = "tick", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER,
            target = "Lnet/minecraft/world/level/block/entity/BeaconBlockEntity;beamSections:Ljava/util/List;"))
    private static void horizons$recordCompleteBeam(Level level, BlockPos pos, BlockState state,
                                                     BeaconBlockEntity beacon, CallbackInfo ci) {
        if (level instanceof ClientLevel client) {
            var observed = (BeaconBlockEntityMixin) (Object) beacon;
            // A newly loaded beacon starts at zero levels until its periodic pyramid check.
            if (observed.horizons$baseChecked || observed.beamSections.isEmpty()) {
                HorizonClient.observedBeacon(client, beacon);
            }
        }
    }
}
