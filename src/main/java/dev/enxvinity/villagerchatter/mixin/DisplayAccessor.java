package dev.enxvinity.villagerchatter.mixin;

import com.mojang.math.Transformation;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Minecraft keeps these setters private; this mixin lets us call them. */
@Mixin(Display.class)
public interface DisplayAccessor {
	@Invoker("setBillboardConstraints")
	void villagerchatter$setBillboardConstraints(Display.BillboardConstraints constraints);

	@Invoker("setTransformation")
	void villagerchatter$setTransformation(Transformation transformation);

	@Invoker("setPosRotInterpolationDuration")
	void villagerchatter$setPosRotInterpolationDuration(int ticks);
}
