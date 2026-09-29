package dev.enxvinity.villagerchatter.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/** Server -> client: what the villager is saying in the trade screen, and the player's reply options. */
public record DialogueStateS2C(String speaker, String line, List<String> replies, boolean thinking) implements CustomPacketPayload {
	public static final Type<DialogueStateS2C> TYPE = new Type<>(Identifier.fromNamespaceAndPath("villagerchatter", "dialogue_state"));
	public static final StreamCodec<RegistryFriendlyByteBuf, DialogueStateS2C> CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8, DialogueStateS2C::speaker,
			ByteBufCodecs.STRING_UTF8, DialogueStateS2C::line,
			ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(4)), DialogueStateS2C::replies,
			ByteBufCodecs.BOOL, DialogueStateS2C::thinking,
			DialogueStateS2C::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
