package dev.enxvinity.villagerchatter.client;

import dev.enxvinity.villagerchatter.net.DialogueStateS2C;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;

public class VillagerChatterClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(DialogueStateS2C.TYPE,
				(payload, context) -> context.client().execute(() -> ClientDialogue.set(payload)));

		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof MerchantScreen merchant) {
				DialoguePanel.attach(merchant);
			}
		});
	}
}
