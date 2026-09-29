package dev.enxvinity.villagerchatter.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client -> server: the player clicked reply button #index (0-2 = AI replies, 3 = "Let's just trade."). */
public record DialogueChoiceC2S(int index) implements CustomPacketPayload {
	public static final Type<DialogueChoiceC2S> TYPE = new Type<>(Identifier.fromNamespaceAndPath("villagerchatter", "dialogue_choice"));
	public static final StreamCodec<RegistryFriendlyByteBuf, DialogueChoiceC2S> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, DialogueChoiceC2S::index,
			DialogueChoiceC2S::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
