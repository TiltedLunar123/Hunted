package dev.tiltedlunar.hunted.client;

import dev.tiltedlunar.hunted.registry.HuntedEntities;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.renderer.entity.EntityRenderers;

/**
 * Client entry point.
 *
 * <p>Everything here is cosmetic. The mod is fully playable on a server whose
 * clients have never heard of it, which is the whole reason the brain lives on
 * the server side.
 */
public final class HuntedClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		// Vanilla's own registry, rather than Fabric's wrapper around it, which
		// is deprecated now that this one is directly usable.
		EntityRenderers.register(HuntedEntities.HUNTER, HunterRenderer::new);
	}
}
