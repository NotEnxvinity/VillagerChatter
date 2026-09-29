package dev.enxvinity.villagerchatter.mixin;

import dev.enxvinity.villagerchatter.VillagerChatter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BellBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The village bell rang: let nearby villagers react. */
@Mixin(BellBlock.class)
public abstract class BellBlockMixin {
	@Inject(method = "attemptToRing(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z", at = @At("RETURN"))
	private void villagerchatter$onRing(Entity entity, Level level, BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && level instanceof ServerLevel serverLevel) {
			VillagerChatter.instance().onBell(serverLevel, pos);
		}
	}
}
