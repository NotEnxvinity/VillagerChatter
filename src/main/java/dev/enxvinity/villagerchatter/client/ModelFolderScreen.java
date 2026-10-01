package dev.enxvinity.villagerchatter.client;

import com.mojang.blaze3d.Blaze3D;
import dev.enxvinity.villagerchatter.ChatterConfig;
import dev.enxvinity.villagerchatter.LocalRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the AI files (~2.5 GB) are kept. By default one shared folder serves every Minecraft instance,
 * so the model is only downloaded once. Players can point it somewhere else (another drive, say).
 */
public class ModelFolderScreen extends OptionsSubScreen {
	private final ChatterConfig config = ChatterConfig.get();
	private final String before = config.modelFolder;
	private EditBox path;

	public ModelFolderScreen(Screen parent) {
		super(parent, Minecraft.getInstance().options, Component.translatable("villagerchatter.options.model_folder.title"));
	}

	@Override
	protected void addOptions() {
		this.list.addHeader(Component.translatable("villagerchatter.options.model_folder.custom"));
		path = new EditBox(this.font, 0, 0, 310, 20, Component.translatable("villagerchatter.options.model_folder.custom"));
		path.setMaxLength(1024);
		path.setValue(config.modelFolder);
		path.setHint(Component.literal(LocalRuntime.defaultDataDir().toString()));
		path.setTooltip(Tooltip.create(Component.translatable("villagerchatter.options.model_folder.custom.tooltip")));
		this.list.addBig(path);

		Button open = Button.builder(Component.translatable("villagerchatter.options.model_folder.open"), b -> openFolder())
				.width(150)
				.tooltip(Tooltip.create(Component.translatable("villagerchatter.options.model_folder.open.tooltip")))
				.build();
		Button reset = Button.builder(Component.translatable("villagerchatter.options.model_folder.default"), b -> path.setValue(""))
				.width(150)
				.tooltip(Tooltip.create(Component.translatable("villagerchatter.options.model_folder.default.tooltip",
						LocalRuntime.defaultDataDir().toString())))
				.build();
		this.list.addSmall(open, reset);
		this.list.addHeader(Component.translatable("villagerchatter.options.model_folder.restart"));
	}

	/** Opens the folder currently typed in (or the shared one) in Finder / Explorer / the file manager. */
	private void openFolder() {
		config.modelFolder = path.getValue().trim();
		Path dir = LocalRuntime.dataDir();
		try {
			Files.createDirectories(dir);
			Blaze3D.openPath(dir);
		} catch (Exception e) {
			path.setTooltip(Tooltip.create(Component.translatable("villagerchatter.options.model_folder.error", e.getMessage())));
		}
	}

	@Override
	public void removed() {
		super.removed();
		config.modelFolder = path == null ? before : path.getValue().trim();
		config.save();
	}
}
