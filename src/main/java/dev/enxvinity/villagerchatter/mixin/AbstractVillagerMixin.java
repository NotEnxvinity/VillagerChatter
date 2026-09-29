package dev.enxvinity.villagerchatter.mixin;

import dev.enxvinity.villagerchatter.VillagerChatter;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells the dialogue system when a player starts trading with a villager. */
@Mixin(AbstractVillager.class)
public abstract class AbstractVillagerMixin {
	@Inject(method = "setTradingPlayer", at = @At("TAIL"))
	private void villagerchatter$onTradingPlayer(Player player, CallbackInfo ci) {
		if (player instanceof ServerPlayer sp && (Object) this instanceof Villager villager) {
			VillagerChatter.dialogue().onTradeStart(sp, villager);
		}
	}
}
