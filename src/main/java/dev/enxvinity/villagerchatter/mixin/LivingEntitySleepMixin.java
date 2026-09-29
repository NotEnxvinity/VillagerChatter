package dev.enxvinity.villagerchatter.mixin;

import dev.enxvinity.villagerchatter.VillagerChatter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A villager just went to bed. */
@Mixin(LivingEntity.class)
public abstract class LivingEntitySleepMixin {
	@Inject(method = "startSleeping", at = @At("RETURN"))
	private void villagerchatter$onSleep(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && (Object) this instanceof Villager villager && !villager.level().isClientSide()) {
			VillagerChatter.instance().onBedtime(villager);
		}
	}
}
