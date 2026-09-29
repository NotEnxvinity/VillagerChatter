package dev.enxvinity.villagerchatter.client;

import dev.enxvinity.villagerchatter.net.DialogueStateS2C;

/** The latest dialogue state the server sent us (null = nothing to show). */
public final class ClientDialogue {
	private ClientDialogue() {}

	private static DialogueStateS2C current;

	public static DialogueStateS2C get() { return current; }

	public static void set(DialogueStateS2C state) { current = state; }

	public static void clear() { current = null; }
}
