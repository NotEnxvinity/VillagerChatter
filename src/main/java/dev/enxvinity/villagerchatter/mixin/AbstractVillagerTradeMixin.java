package dev.enxvinity.villagerchatter.mixin;

import dev.enxvinity.villagerchatter.VillagerChatter;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fires when a trade actually completes. */
@Mixin(AbstractVillager.class)
public abstract class AbstractVillagerTradeMixin {
	@Inject(method = "notifyTrade", at = @At("TAIL"))
	private void villagerchatter$onTrade(MerchantOffer offer, CallbackInfo ci) {
		AbstractVillager self = (AbstractVillager) (Object) this;
		if (self instanceof Villager villager && self.getTradingPlayer() instanceof ServerPlayer player) {
			VillagerChatter.instance().onTrade(player, villager, offer);
		}
	}
}
