package dev.tiltedlunar.hunted;

import dev.tiltedlunar.hunted.command.HuntedCommand;
import dev.tiltedlunar.hunted.registry.HuntedEntities;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import dev.tiltedlunar.hunted.hunter.Respawns;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mod entry point.
 *
 * <p>Runs on both the client and a dedicated server. Everything that decides
 * how a hunter behaves lives on the server side, so there is no second account
 * and nothing to pilot. The jar is still needed on every client, because a
 * client that has never heard of the hunter cannot join a server that has one.
 */
public final class Hunted implements ModInitializer {

	public static final String MOD_ID = "hunted";
	public static final Logger LOG = LoggerFactory.getLogger("Hunted");

	@Override
	public void onInitialize() {
		HuntedConfig.get();
		HuntedEntities.register();

		CommandRegistrationCallback.EVENT.register(
				(dispatcher, registry, environment) -> HuntedCommand.register(dispatcher));

		// A killed hunter books its replacement and then stops existing, so the
		// countdown has to be ticked by something that outlives it.
		ServerTickEvents.END_SERVER_TICK.register(Respawns::tick);

		LOG.info("Hunted ready. Default tier: {}.", HuntedConfig.get().defaultTier().displayName());
	}
}
