package dev.enxvinity.villagerchatter.client;

import dev.enxvinity.villagerchatter.ChatterConfig;
import dev.enxvinity.villagerchatter.LocalRuntime;
import dev.enxvinity.villagerchatter.VillagerChatter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

/**
 * In-game settings, built from vanilla option widgets so it looks like Minecraft's own menus.
 * Opened from Mod Menu (optional) or with /villagerchatter settings.
 *
 * Changes apply right away to worlds you play in singleplayer or host over LAN.
 * On a dedicated server the server's own config/villagerchatter.properties is used.
 */
public class ChatterSettingsScreen extends OptionsSubScreen {
	/** Percent sliders move in 5% steps. */
	private static final int STEP = 5;
	/** Width of a half-row widget, same as vanilla's. */
	private static final int WIDTH = 150;

	/** How lines are shown. */
	private enum Display {
		BUBBLES, CHAT, BOTH;

		Component label() {
			return Component.translatable("villagerchatter.options.display." + name().toLowerCase(java.util.Locale.ROOT));
		}
	}

	private final ChatterConfig config = ChatterConfig.get();
	private final boolean aiWasOn = config.aiEnabled;

	public ChatterSettingsScreen(Screen parent) {
		super(parent, Minecraft.getInstance().options, Component.translatable("villagerchatter.options.title"));
	}

	@Override
	protected void addOptions() {
		// --- Talking ---
		this.list.addHeader(Component.translatable("villagerchatter.options.header.talking"));
		this.list.addSmall(
				percent("chattiness", 0, 100, Math.round(config.talkChance * 100), v -> config.talkChance = v / 100f),
				percent("small_talk", 0, 100, Math.round(config.smallTalkChance * 100), v -> config.smallTalkChance = v / 100f),
				toggle("dialogue", config.dialogueEnabled, v -> config.dialogueEnabled = v)
		);

		// --- Bubbles & sounds ---
		this.list.addHeader(Component.translatable("villagerchatter.options.header.effects"));
		Display current = !config.showBubbles ? Display.CHAT : config.showInChat ? Display.BOTH : Display.BUBBLES;
		CycleButton<Display> display = CycleButton.builder(Display::label, current)
				.withValues(Display.values())
				.withTooltip(v -> Tooltip.create(Component.translatable("villagerchatter.options.display.tooltip")))
				.create(0, 0, WIDTH, 20, Component.translatable("villagerchatter.options.display"), (button, value) -> {
					config.showBubbles = value != Display.CHAT;
					config.showInChat = value != Display.BUBBLES;
				});
		this.list.addSmall(display, percent("bubble_size", 50, 200, config.bubbleSize, v -> config.bubbleSize = v)
				.createButton(this.options, 0, 0, WIDTH));
		this.list.addSmall(
				toggle("sounds", config.sounds, v -> config.sounds = v),
				toggle("particles", config.particles, v -> config.particles = v)
		);

		// --- AI ---
		this.list.addHeader(Component.translatable("villagerchatter.options.header.ai"));
		CycleButton<Boolean> ai = CycleButton.onOffBuilder(config.aiEnabled)
				.withTooltip(v -> Tooltip.create(Component.translatable("villagerchatter.options.ai.tooltip")
						.append("\n\n").append(aiStatus())))
				.create(0, 0, WIDTH, 20, Component.translatable("villagerchatter.options.ai"), (b, v) -> config.aiEnabled = v);
		Button folder = Button.builder(Component.translatable("villagerchatter.options.model_folder"),
						b -> this.minecraft.gui.setScreen(new ModelFolderScreen(this)))
				.width(WIDTH)
				.tooltip(Tooltip.create(Component.translatable("villagerchatter.options.model_folder.tooltip",
						LocalRuntime.dataDir().toString())))
				.build();
		this.list.addSmall(ai, folder);
	}

	/** The AI toggle's tooltip also says what the AI is doing right now. */
	private Component aiStatus() {
		VillagerChatter mod = VillagerChatter.instance();
		if (mod == null) return Component.empty();
		if (!config.aiEnabled) return Component.translatable("villagerchatter.options.ai.status", "off (hand-written lines only)");
		if ("ollama".equalsIgnoreCase(config.backend)) return Component.translatable("villagerchatter.options.ai.status", "using Ollama");
		LocalRuntime rt = mod.ai().runtime();
		String s = switch (rt.state()) {
			case READY -> "ready";
			case IDLE -> "starts when you open a world";
			default -> rt.status();
		};
		return Component.translatable("villagerchatter.options.ai.status", s);
	}

	private static OptionInstance<Integer> percent(String key, int min, int max, int value, IntConsumer setter) {
		String captionId = "villagerchatter.options." + key;
		return new OptionInstance<>(
				captionId,
				OptionInstance.cachedConstantTooltip(Component.translatable(captionId + ".tooltip")),
				(caption, v) -> v == 0
						? Options.genericValueLabel(caption, CommonComponents.OPTION_OFF)
						: Component.translatable("options.percent_value", caption, v * STEP),
				new OptionInstance.IntRange(min / STEP, max / STEP),
				Math.round(value / (float) STEP),
				v -> setter.accept(v * STEP)
		);
	}

	private static OptionInstance<Boolean> toggle(String key, boolean value, java.util.function.Consumer<Boolean> setter) {
		String captionId = "villagerchatter.options." + key;
		return OptionInstance.createBoolean(captionId,
				OptionInstance.cachedConstantTooltip(Component.translatable(captionId + ".tooltip")),
				value, setter::accept);
	}

	@Override
	protected void addFooter() {
		LinearLayout buttons = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
		buttons.addChild(Button.builder(Component.translatable("villagerchatter.options.reset"), button -> {
			config.resetToDefaults();
			// Reopen the screen so every widget shows its default again.
			this.minecraft.gui.setScreen(new ChatterSettingsScreen(this.lastScreen));
		}).tooltip(Tooltip.create(Component.translatable("villagerchatter.options.reset.tooltip"))).build());
		buttons.addChild(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).build());
	}

	@Override
	public void removed() {
		super.removed();
		VillagerChatter mod = VillagerChatter.instance();
		if (mod != null) mod.applySettings();
		else config.save();
		if (aiWasOn != config.aiEnabled) {
			VillagerChatter.LOGGER.info("Villager AI switched {} in the settings.", config.aiEnabled ? "on" : "off");
		}
	}
}
