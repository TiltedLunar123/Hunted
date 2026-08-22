package dev.tiltedlunar.hunted.hunter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.tiltedlunar.hunted.HuntedConfig;
import dev.tiltedlunar.hunted.registry.HuntedEntities;
import dev.tiltedlunar.hunted.taunt.Taunts;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Brings a killed hunter back.
 *
 * <p>A hunter you can kill once is a hunter you have beaten, and the whole
 * point of the thing is that it does not stop. So it comes back, the way you
 * do: at the world spawn, with nothing, a long walk from wherever it died.
 * That is the punishment for losing the fight and it is the same one the
 * player gets.
 *
 * <p>The timer cannot live on the hunter, because by the time it is needed the
 * hunter does not exist. It lives here, on the server tick, keyed by the
 * target rather than by the entity.
 */
public final class Respawns {

	/**
	 * How long the world gets without it.
	 *
	 * <p>Short, because the walk back from spawn is the real cost and that is
	 * usually long enough on its own. Making this longer as well would just be
	 * two punishments for the same thing.
	 */
	private static final int DELAY = 100;

	/** Highest a respawn will look for open sky before giving up on a column. */
	private static final int HEADROOM = 2;

	private record Pending(
			ResourceKey<Level> dimension,
			UUID target,
			HunterTier tier,
			boolean survival,
			int at) {
	}

	private static final List<Pending> WAITING = new ArrayList<>();

	private Respawns() {
	}

	/**
	 * Books a replacement for a hunter that has just died.
	 *
	 * <p>Nothing it was carrying comes with it. It had a pickaxe and a furnace
	 * and a plan, and it lost all of that by being killed, which is exactly
	 * what happens to the person it was chasing.
	 */
	public static void schedule(HunterEntity hunter) {
		if (!HuntedConfig.get().respawn()) {
			return;
		}
		UUID target = hunter.tracker().targetId();
		if (target == null || !(hunter.level() instanceof ServerLevel level)) {
			return;
		}
		WAITING.add(new Pending(
				level.dimension(),
				target,
				hunter.tier(),
				hunter.survivalMode(),
				DELAY));
	}

	/** Forgets every booked respawn. Used by the clear command. */
	public static void cancelAll() {
		WAITING.clear();
	}

	public static int waiting() {
		return WAITING.size();
	}

	/** Counts down and places whatever is due. */
	public static void tick(MinecraftServer server) {
		if (WAITING.isEmpty()) {
			return;
		}

		for (int i = WAITING.size() - 1; i >= 0; i--) {
			Pending pending = WAITING.get(i);
			if (pending.at() > 1) {
				WAITING.set(i, new Pending(pending.dimension(), pending.target(),
						pending.tier(), pending.survival(), pending.at() - 1));
				continue;
			}
			WAITING.remove(i);
			place(server, pending);
		}
	}

	private static void place(MinecraftServer server, Pending pending) {
		ServerPlayer quarry = server.getPlayerList().getPlayer(pending.target());
		// Nobody left to hunt. A player who logged out has not beaten it, but
		// there is nothing to point a new one at either, so let it go.
		if (quarry == null || !quarry.isAlive()) {
			return;
		}
		if (chasing(server, pending.target()) >= HuntedConfig.get().maxHuntersPerPlayer()) {
			return;
		}

		// Back at the world spawn, like anyone else who just died. Not on top
		// of the player: being killed has to buy them the walk.
		ServerLevel level = server.overworld();
		BlockPos spawn = level.getRespawnData().pos();
		BlockPos surface = standingSpotNear(level, spawn);

		HunterEntity hunter = HuntedEntities.HUNTER.create(level, EntitySpawnReason.COMMAND);
		if (hunter == null) {
			return;
		}
		hunter.snapTo(surface.getX() + 0.5D, surface.getY(), surface.getZ() + 0.5D, 0.0f, 0.0f);
		// Survival mode first. It is what decides whether the tier hands out a
		// free kit, and a hunter that respawned into full iron would make
		// killing it pointless.
		hunter.setSurvivalMode(pending.survival());
		hunter.setTier(pending.tier());
		hunter.tracker().setTargetId(pending.target());
		hunter.tracker().setInitialFix(quarry.blockPosition(), quarry.level().dimension());
		hunter.setGlowingTag(HuntedConfig.get().glowing());
		hunter.markRespawned();
		level.addFreshEntity(hunter);

		hunter.taunter().announce(quarry, hunter.getRandom(), Taunts.RESPAWN);
	}

	/**
	 * The top of the column at the world spawn, with room to stand.
	 *
	 * <p>The stored spawn point is a block position, not necessarily a place a
	 * body fits: it can be inside the ground on a resurfaced world, or under a
	 * tree that grew over it.
	 */
	private static BlockPos standingSpotNear(ServerLevel level, BlockPos around) {
		BlockPos surface = level.getHeightmapPos(
				Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, around);
		for (int up = 0; up < HEADROOM; up++) {
			BlockPos at = surface.above(up);
			if (level.getBlockState(at).isAir() && level.getBlockState(at.above()).isAir()) {
				return at;
			}
		}
		return surface;
	}

	private static int chasing(MinecraftServer server, UUID target) {
		int total = 0;
		for (ServerLevel level : server.getAllLevels()) {
			for (HunterEntity hunter : level.getEntities(HuntedEntities.HUNTER, h -> true)) {
				// The body of the one that just died is still in the world for
				// the length of the death animation. Counting it would refuse
				// the replacement it is the reason for.
				if (!hunter.isDeadOrDying() && target.equals(hunter.tracker().targetId())) {
					total++;
				}
			}
		}
		return total;
	}
}
