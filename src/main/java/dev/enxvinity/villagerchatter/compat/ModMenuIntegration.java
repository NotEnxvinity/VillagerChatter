package dev.enxvinity.villagerchatter.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.enxvinity.villagerchatter.client.ChatterSettingsScreen;

/** Only loaded by Mod Menu when it's installed; adds the "Configure" button for this mod. */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return ChatterSettingsScreen::new;
	}
}
