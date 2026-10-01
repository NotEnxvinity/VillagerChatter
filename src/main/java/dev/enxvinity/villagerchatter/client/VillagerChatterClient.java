package dev.enxvinity.villagerchatter.client;

import dev.enxvinity.villagerchatter.net.DialogueStateS2C;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;

public class VillagerChatterClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(DialogueStateS2C.TYPE,
				(payload, context) -> context.client().execute(() -> ClientDialogue.set(payload)));

		// "/villagerchatter settings" opens the settings screen, for players without Mod Menu.
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
				ClientCommands.literal("villagerchatter").then(ClientCommands.literal("settings").executes(ctx -> {
					var client = ctx.getSource().getClient();
					// Wait one frame: the chat screen closes right after the command runs.
					client.schedule(() -> client.gui.setScreen(new ChatterSettingsScreen(null)));
					return 1;
				}))));

		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof MerchantScreen merchant) {
				DialoguePanel.attach(merchant);
			}
		});
	}
}
