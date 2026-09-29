package dev.enxvinity.villagerchatter.mixin.client;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
	@Accessor("leftPos")
	int villagerchatter$getLeftPos();

	@Accessor("topPos")
	int villagerchatter$getTopPos();

	@Accessor("imageWidth")
	int villagerchatter$getImageWidth();
}
