/* SPDX-License-Identifier: LGPL-3.0-only */
package org.edtp.kavora.horizon.mixin;

import org.edtp.kavora.horizon.HorizonClient;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
    @Inject(method = "setBlock", at = @At("RETURN"))
    private void horizons$observe(
            BlockPos pos,
            BlockState state,
            int flags,
            int recursion,
            CallbackInfoReturnable<Boolean> result) {
        if (result.getReturnValueZ()) HorizonClient.changed((ClientLevel) (Object) this, pos, state);
    }
}
