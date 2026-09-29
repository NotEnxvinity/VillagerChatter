package dev.enxvinity.villagerchatter;

import com.mojang.math.Transformation;
import dev.enxvinity.villagerchatter.mixin.DisplayAccessor;
import dev.enxvinity.villagerchatter.mixin.TextDisplayAccessor;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Floating speech bubbles above villagers' heads.
 *
 * A bubble is a vanilla "text display" entity that we keep positioned above the villager
 * and remove after a few seconds. Because it's a normal entity, players see it even
 * without the mod installed on their side.
 */
public final class SpeechBubbles {
	public static final String TAG = "villagerchatter_bubble";
	private static final double ABOVE_HEAD = 0.45;

	private record Bubble(Display.TextDisplay display, Villager villager, long expiresAt) {}

	private final Map<UUID, Bubble> bubbles = new HashMap<>();

	public void show(ServerLevel level, Villager villager, String line) {
		remove(villager.getUUID());

		Display.TextDisplay display = new Display.TextDisplay(EntityTypes.TEXT_DISPLAY, level);
		((TextDisplayAccessor) display).villagerchatter$setText(Component.literal(line));
		((TextDisplayAccessor) display).villagerchatter$setLineWidth(140);
		((TextDisplayAccessor) display).villagerchatter$setBackgroundColor(0xB0000000); // translucent black
		((DisplayAccessor) display).villagerchatter$setBillboardConstraints(Display.BillboardConstraints.CENTER);
		((DisplayAccessor) display).villagerchatter$setTransformation(new Transformation(
				new Vector3f(0, 0, 0), new Quaternionf(), new Vector3f(0.6f, 0.6f, 0.6f), new Quaternionf()));
		((DisplayAccessor) display).villagerchatter$setPosRotInterpolationDuration(3); // smooth following
		display.addTag(TAG);
		display.setNoGravity(true);
		display.setPos(villager.getX(), villager.getY() + villager.getBbHeight() + ABOVE_HEAD, villager.getZ());

		// Longer lines stay up a bit longer: 3.5s + 60ms per character, max 8s.
		long ticks = Math.min(160, 70 + line.length() * 6L / 5);
		Bubble bubble = new Bubble(display, villager, level.getGameTime() + ticks);
		bubbles.put(villager.getUUID(), bubble);
		level.addFreshEntity(display);
	}

	/** Called every tick: follow the villager, and pop bubbles that have expired. */
	public void tick(ServerLevel level) {
		long now = level.getGameTime();
		Iterator<Map.Entry<UUID, Bubble>> it = bubbles.entrySet().iterator();
		while (it.hasNext()) {
			Bubble b = it.next().getValue();
			if (b.display().level() != level) continue;
			if (now >= b.expiresAt() || !b.villager().isAlive() || b.display().isRemoved()) {
				b.display().discard();
				it.remove();
				continue;
			}
			Villager v = b.villager();
			b.display().setPos(v.getX(), v.getY() + v.getBbHeight() + ABOVE_HEAD, v.getZ());
		}
	}

	public boolean isOurs(Display.TextDisplay display) {
		for (Bubble b : bubbles.values()) if (b.display() == display) return true;
		return false;
	}

	private void remove(UUID villagerId) {
		Bubble old = bubbles.remove(villagerId);
		if (old != null) old.display().discard();
	}
}
