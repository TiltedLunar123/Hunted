package dev.tiltedlunar.hunted.command;

import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import dev.tiltedlunar.hunted.HuntedConfig;
import dev.tiltedlunar.hunted.hunter.HunterEntity;
import dev.tiltedlunar.hunted.hunter.HunterTier;
import dev.tiltedlunar.hunted.registry.HuntedEntities;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The {@code /hunted} command tree.
 *
 * <p>The whole mod is driven from here. There is no settings screen, on
 * purpose: a dedicated server has nowhere to put one, and every option worth
 * having is a single word after {@code /hunted}.
 */
public final class HuntedCommand {

	private static final SuggestionProvider<CommandSourceStack> TIERS =
			(context, builder) -> SharedSuggestionProvider.suggest(
					java.util.Arrays.stream(HunterTier.values()).map(HunterTier::id).toList(),
					builder);

	private HuntedCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("hunted")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));

		root.then(Commands.literal("spawn")
				.executes(context -> spawn(context, HuntedConfig.get().defaultTier(),
						self(context), HuntedConfig.get().survivalStart()))
				.then(Commands.argument("tier", StringArgumentType.word())
						.suggests(TIERS)
						.executes(context -> spawn(context, tierArg(context), self(context),
								HuntedConfig.get().survivalStart()))
						.then(Commands.argument("target", EntityArgument.player())
								.executes(context -> spawn(context, tierArg(context),
										EntityArgument.getPlayer(context, "target"),
										HuntedConfig.get().survivalStart()))
								.then(Commands.argument("survival", BoolArgumentType.bool())
										.executes(context -> spawn(context, tierArg(context),
												EntityArgument.getPlayer(context, "target"),
												BoolArgumentType.getBool(context, "survival")))))));

		root.then(Commands.literal("survival")
				.then(Commands.argument("enabled", BoolArgumentType.bool())
						.executes(context -> {
							boolean value = BoolArgumentType.getBool(context, "enabled");
							HuntedConfig.get().setSurvivalStart(value);
							return reply(context, value
									? "Hunters now start empty handed and gather their own gear."
									: "Hunters now spawn fully equipped for their tier.");
						})));

		root.then(Commands.literal("clear")
				.executes(HuntedCommand::clear));

		root.then(Commands.literal("status")
				.executes(HuntedCommand::status));

		root.then(Commands.literal("tier")
				.then(Commands.argument("tier", StringArgumentType.word())
						.suggests(TIERS)
						.executes(context -> {
							HunterTier tier = tierArg(context);
							HuntedConfig.get().setDefaultTier(tier);
							return reply(context, "Default tier set to " + tier.displayName() + ".");
						})));

		root.then(Commands.literal("terrain")
				.then(Commands.argument("enabled", BoolArgumentType.bool())
						.executes(context -> {
							boolean value = BoolArgumentType.getBool(context, "enabled");
							HuntedConfig.get().setAllowTerrainDamage(value);
							return reply(context, "Mining and building "
									+ (value ? "enabled." : "disabled."));
						})));

		root.then(Commands.literal("dimensions")
				.then(Commands.argument("enabled", BoolArgumentType.bool())
						.executes(context -> {
							boolean value = BoolArgumentType.getBool(context, "enabled");
							HuntedConfig.get().setCrossDimensions(value);
							return reply(context, "Chasing between dimensions "
									+ (value ? "enabled." : "disabled."));
						})));

		root.then(Commands.literal("respawn")
				.then(Commands.argument("enabled", BoolArgumentType.bool())
						.executes(context -> {
							boolean value = BoolArgumentType.getBool(context, "enabled");
							HuntedConfig.get().setRespawn(value);
							return reply(context, value
									? "Killing a hunter now buys you the walk back from spawn, nothing more."
									: "Hunters now stay dead.");
						})));

		root.then(Commands.literal("taunts")
				.then(Commands.argument("enabled", BoolArgumentType.bool())
						.executes(context -> {
							boolean value = BoolArgumentType.getBool(context, "enabled");
							HuntedConfig.get().setTaunts(value);
							return reply(context, value
									? "The hunter will speak to its target."
									: "The hunter will stay quiet.");
						})));

		root.then(Commands.literal("glow")
				.then(Commands.argument("enabled", BoolArgumentType.bool())
						.executes(context -> {
							boolean value = BoolArgumentType.getBool(context, "enabled");
							HuntedConfig.get().setGlowing(value);
							applyGlow(context.getSource().getServer(), value);
							return reply(context, "Outline through walls "
									+ (value ? "enabled." : "disabled."));
						})));

		root.then(Commands.literal("distance")
				.then(Commands.argument("blocks", IntegerArgumentType.integer(8, 256))
						.executes(context -> {
							int value = IntegerArgumentType.getInteger(context, "blocks");
							HuntedConfig.get().setSpawnDistance(value);
							return reply(context, "Hunters now spawn " + value + " blocks away.");
						})));

		root.then(Commands.literal("warn")
				.then(Commands.argument("enabled", BoolArgumentType.bool())
						.executes(context -> {
							boolean value = BoolArgumentType.getBool(context, "enabled");
							HuntedConfig.get().setAnnounceSpawn(value);
							return reply(context, value
									? "Targets are now told when a hunter spawns."
									: "Hunters now spawn without warning.");
						})));

		root.then(Commands.literal("maxhunters")
				.then(Commands.argument("count", IntegerArgumentType.integer(1, 16))
						.executes(context -> {
							int value = IntegerArgumentType.getInteger(context, "count");
							HuntedConfig.get().setMaxHuntersPerPlayer(value);
							return reply(context, "Each player can now be hunted by up to "
									+ value + " at once.");
						})));

		dispatcher.register(root);
	}

	// -----------------------------------------------------------------

	private static HunterTier tierArg(CommandContext<CommandSourceStack> context) {
		return HunterTier.byIdOrDefault(
				StringArgumentType.getString(context, "tier"),
				HuntedConfig.get().defaultTier());
	}

	private static ServerPlayer self(CommandContext<CommandSourceStack> context) {
		return context.getSource().getPlayer();
	}

	/**
	 * Places a hunter on the far side of a random bearing, at the configured
	 * distance, on whatever surface is there.
	 */
	private static int spawn(CommandContext<CommandSourceStack> context, HunterTier tier,
			ServerPlayer quarry, boolean survival) {
		CommandSourceStack source = context.getSource();
		if (quarry == null) {
			source.sendFailure(Component.literal("Run this as a player, or name a target."));
			return 0;
		}

		ServerLevel level = (ServerLevel) quarry.level();

		int already = huntersChasing(source, quarry);
		int allowed = HuntedConfig.get().maxHuntersPerPlayer();
		if (already >= allowed) {
			source.sendFailure(Component.literal(quarry.getName().getString()
					+ " already has " + already + " hunter" + (already == 1 ? "" : "s")
					+ ". The limit is " + allowed
					+ ", raise it with /hunted maxhunters."));
			return 0;
		}

		int distance = HuntedConfig.get().spawnDistance();
		double bearing = level.getRandom().nextDouble() * Math.PI * 2.0D;

		int x = (int) Math.round(quarry.getX() + Math.cos(bearing) * distance);
		int z = (int) Math.round(quarry.getZ() + Math.sin(bearing) * distance);
		BlockPos surface = level.getHeightmapPos(
				Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));

		HunterEntity hunter = HuntedEntities.HUNTER.create(level, EntitySpawnReason.COMMAND);
		if (hunter == null) {
			source.sendFailure(Component.literal("Could not create the hunter."));
			return 0;
		}

		hunter.snapTo(surface.getX() + 0.5D, (double) surface.getY(), surface.getZ() + 0.5D,
				(float) Math.toDegrees(bearing), 0.0f);
		// Survival mode has to be set first, because setTier is what hands out
		// the free kit that survival mode exists to withhold.
		hunter.setSurvivalMode(survival);
		hunter.setTier(tier);
		hunter.tracker().setTargetId(quarry.getUUID());
		// Point it at where the target is standing right now. It has to close
		// that distance on foot and find them again from there, but it starts
		// out walking rather than standing still waiting to be noticed.
		hunter.tracker().setInitialFix(quarry.blockPosition(), quarry.level().dimension());
		hunter.setGlowingTag(HuntedConfig.get().glowing());
		level.addFreshEntity(hunter);

		source.sendSuccess(() -> Component.literal(
				tier.displayName() + " is hunting " + quarry.getName().getString() + ".")
				.withStyle(ChatFormatting.RED), true);

		if (HuntedConfig.get().announceSpawn()) {
			quarry.sendSystemMessage(Component.literal("Something is coming for you.")
					.withStyle(ChatFormatting.DARK_RED));
		}
		return 1;
	}

	private static int clear(CommandContext<CommandSourceStack> context) {
		// Booked replacements count as hunters for this purpose. Clearing the
		// field and then having one stroll back out of spawn ten seconds later
		// is not what anybody meant by clear.
		dev.tiltedlunar.hunted.hunter.Respawns.cancelAll();
		int removed = 0;
		for (ServerLevel level : context.getSource().getServer().getAllLevels()) {
			for (HunterEntity hunter : hunters(level)) {
				hunter.discard();
				removed++;
			}
		}
		final int total = removed;
		context.getSource().sendSuccess(
				() -> Component.literal("Removed " + total + " hunter"
						+ (total == 1 ? "." : "s.")), true);
		return removed;
	}

	private static int status(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		int found = 0;

		for (ServerLevel level : source.getServer().getAllLevels()) {
			for (HunterEntity hunter : hunters(level)) {
				found++;
				String dimension = level.dimension().identifier().getPath();
				// Only report shopping while it is actually shopping. The
				// ladder will happily name the next thing it wants long after
				// the hunter has given up trying to get it, and a status line
				// that describes an intention rather than an action is worse
				// than no status line at all.
				String doing = hunter.plan().tactic().isEconomy()
						&& hunter.survivalMode()
						&& !hunter.shoppingAbandoned()
						? hunter.survival().describe()
						: hunter.activity().name().toLowerCase(java.util.Locale.ROOT);

				String line = String.format(
						"%s in %s at %d %d %d, %.0f/%.0f hp, %s, %d broken, %d placed",
						hunter.tier().displayName(),
						dimension,
						hunter.getBlockX(), hunter.getBlockY(), hunter.getBlockZ(),
						hunter.getHealth(), hunter.getMaxHealth(),
						doing,
						hunter.blocksBroken(), hunter.blocksPlaced());
				source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);

				// The decision and why, so a hunter doing something that looks
				// wrong can explain itself.
				String thinking = String.format("    %s: %s",
						hunter.plan().tactic().name().toLowerCase(java.util.Locale.ROOT),
						hunter.plan().reason());
				source.sendSuccess(() -> Component.literal(thinking)
						.withStyle(ChatFormatting.DARK_GRAY), false);
			}
		}

		if (found == 0) {
			int coming = dev.tiltedlunar.hunted.hunter.Respawns.waiting();
			source.sendSuccess(() -> Component.literal(coming > 0
					? "No hunters are active. " + coming
							+ (coming == 1 ? " is" : " are") + " on the way back from spawn."
					: "No hunters are active."), false);
		}
		return found;
	}

	private static void applyGlow(net.minecraft.server.MinecraftServer server, boolean value) {
		for (ServerLevel level : server.getAllLevels()) {
			for (HunterEntity hunter : hunters(level)) {
				hunter.setGlowingTag(value);
			}
		}
	}

	private static List<? extends HunterEntity> hunters(ServerLevel level) {
		return level.getEntities(HuntedEntities.HUNTER, hunter -> true);
	}

	/**
	 * How many hunters are already after this player, anywhere on the server.
	 *
	 * <p>Every dimension counts. One that followed you into the Nether has not
	 * stopped hunting you just because you cannot see it.
	 */
	private static int huntersChasing(CommandSourceStack source, ServerPlayer quarry) {
		int total = 0;
		for (ServerLevel level : source.getServer().getAllLevels()) {
			for (HunterEntity hunter : hunters(level)) {
				if (quarry.getUUID().equals(hunter.tracker().targetId())) {
					total++;
				}
			}
		}
		return total;
	}

	private static int reply(CommandContext<CommandSourceStack> context, String message) {
		context.getSource().sendSuccess(() -> Component.literal(message), true);
		return 1;
	}
}
