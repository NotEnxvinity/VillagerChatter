package dev.enxvinity.villagerchatter.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Display.TextDisplay.class)
public interface TextDisplayAccessor {
	@Invoker("setText")
	void villagerchatter$setText(Component text);

	@Invoker("setLineWidth")
	void villagerchatter$setLineWidth(int width);

	@Invoker("setBackgroundColor")
	void villagerchatter$setBackgroundColor(int argb);
}
