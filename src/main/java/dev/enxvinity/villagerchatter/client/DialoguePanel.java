package dev.enxvinity.villagerchatter.client;

import dev.enxvinity.villagerchatter.mixin.client.AbstractContainerScreenAccessor;
import dev.enxvinity.villagerchatter.net.DialogueChoiceC2S;
import dev.enxvinity.villagerchatter.net.DialogueStateS2C;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;

import java.util.List;

/**
 * The conversation panel drawn above the villager trading window:
 *
 *   ┌──────────────────────────────────────────────┐
 *   │ <Librarian> Ah, a customer! Careful, it bites. │
 *   └──────────────────────────────────────────────┘
 *   [ What's the book for? ] [ Do you read all these? ]
 *   [ Books can't bite.    ] [ Let's just trade.      ]
 *   ┌──────────── vanilla trade window ────────────┐
 */
public final class DialoguePanel {
	private static final int BUTTON_H = 20;
	private static final int GAP = 2;
	private static final int PAD = 5;
	private static final int MAX_TEXT_LINES = 3;
	private static final String EXIT = "Let's just trade.";

	private final MerchantScreen screen;
	private final Font font;
	private final Button[] buttons = new Button[4];
	private final String[] lastLabels = new String[4];

	private DialoguePanel(MerchantScreen screen) {
		this.screen = screen;
		this.font = Screens.getFont(screen);
	}

	public static void attach(MerchantScreen screen) {
		DialoguePanel panel = new DialoguePanel(screen);
		for (int i = 0; i < 4; i++) {
			final int index = i;
			Button b = Button.builder(Component.empty(), btn -> panel.choose(index)).bounds(0, 0, 10, BUTTON_H).build();
			b.visible = false;
			panel.buttons[i] = b;
			Screens.getWidgets(screen).add(b);
		}
		panel.layout();
		ScreenEvents.afterTick(screen).register(s -> panel.layout());
		ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, delta) -> panel.draw(g));
		ScreenEvents.remove(screen).register(s -> ClientDialogue.clear());
	}

	private void choose(int index) {
		DialogueStateS2C state = ClientDialogue.get();
		if (state == null || state.thinking()) return;
		ClientPlayNetworking.send(new DialogueChoiceC2S(index));
	}

	private int left() { return ((AbstractContainerScreenAccessor) screen).villagerchatter$getLeftPos(); }
	private int top() { return ((AbstractContainerScreenAccessor) screen).villagerchatter$getTopPos(); }
	private int width() { return ((AbstractContainerScreenAccessor) screen).villagerchatter$getImageWidth(); }

	private List<FormattedCharSequence> textLines(DialogueStateS2C s) {
		String line = s.thinking() ? "." .repeat(1 + (int) (Util.getMillis() / 350 % 3)) : s.line();
		Component c = Component.literal("<" + s.speaker() + "> ").withStyle(ChatFormatting.GREEN)
				.append(Component.literal(line).withStyle(ChatFormatting.WHITE));
		List<FormattedCharSequence> lines = font.split(c, width() - PAD * 2);
		return lines.size() > MAX_TEXT_LINES ? lines.subList(0, MAX_TEXT_LINES) : lines;
	}

	/** Returns the y where the panel starts; slides to the top of the screen if there's no room. */
	private int panelTop(int textBoxH, boolean hasButtons) {
		int total = textBoxH + (hasButtons ? GAP + BUTTON_H * 2 + GAP : 0) + GAP;
		return Math.max(2, top() - total);
	}

	private void layout() {
		DialogueStateS2C s = ClientDialogue.get();
		boolean show = s != null && !s.replies().isEmpty();
		int textBoxH = s == null ? 0 : textLines(s).size() * 10 + PAD * 2;
		int y = panelTop(textBoxH, show) + textBoxH + GAP;
		int bw = (width() - GAP) / 2;
		for (int i = 0; i < 4; i++) {
			Button b = buttons[i];
			b.visible = show;
			if (!show) continue;
			String label = i < 3 ? (i < s.replies().size() ? s.replies().get(i) : "…") : EXIT;
			String shown = fit(label, bw - 8);
			b.setMessage(Component.literal(shown));
			if (!label.equals(lastLabels[i])) { // hover shows the full reply when it's cut off
				b.setTooltip(shown.equals(label) ? null : Tooltip.create(Component.literal(label)));
				lastLabels[i] = label;
			}
			b.active = !s.thinking() && (i == 3 || i < s.replies().size());
			b.setWidth(bw);
			b.setX(left() + (i % 2) * (bw + GAP));
			b.setY(y + (i / 2) * (BUTTON_H + GAP));
		}
	}

	private String fit(String text, int maxWidth) {
		if (font.width(text) <= maxWidth) return text;
		while (!text.isEmpty() && font.width(text + "…") > maxWidth) text = text.substring(0, text.length() - 1);
		return text + "…";
	}

	private void draw(GuiGraphicsExtractor g) {
		DialogueStateS2C s = ClientDialogue.get();
		if (s == null) return;
		List<FormattedCharSequence> lines = textLines(s);
		int textBoxH = lines.size() * 10 + PAD * 2;
		int x = left();
		int y = panelTop(textBoxH, !s.replies().isEmpty());
		g.fill(x, y, x + width(), y + textBoxH, 0xD0101010);
		g.fill(x, y, x + width(), y + 1, 0xFF6B8E3A); // thin green top edge
		for (int i = 0; i < lines.size(); i++) {
			g.text(font, lines.get(i), x + PAD, y + PAD + i * 10, 0xFFFFFFFF);
		}
	}
}
