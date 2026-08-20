package dev.tiltedlunar.hunted.hunter;

import java.util.List;
import java.util.UUID;

import dev.tiltedlunar.hunted.tactics.Scout;
import dev.tiltedlunar.hunted.tactics.Tactics;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Decides what the hunter knows about its quarry, and when.
 *
 * <p>This is the difference between a chase and a countdown. An entity that
 * always has your exact coordinates cannot be escaped, only outrun, and once
 * players work that out they stop trying to hide. So the lower tiers get a
 * deliberately lossy picture: they update their fix on you only when they can
 * actually see or hear you, and otherwise they commit to your last known
 * position and search around it.
 *
 * <p>The practical upshot is that breaking line of sight is a real tactic
 * against tiers 1 and 2, a delaying tactic against 3 and 4, and worth nothing
 * at all against tier 5.
 */
public final class TargetTracker {

	/** How far a sensory hunter can see you in open ground. */
	private static final double SIGHT_RANGE = 48.0D;

	/** How far a sensory hunter hears a sprinting player. */
	private static final double HEARING_RANGE = 24.0D;

	/** A sensory hunter forgets a stale trail after this long. */
	private static final int TRAIL_TIMEOUT = 1_200;

	/** Ticks between re-scoring the field. Five seconds is often enough to
	 *  notice someone better without the hunter dithering. */
	private static final int RECONSIDER_INTERVAL = 100;

	private UUID targetId;
	private BlockPos lastKnown;
	private ResourceKey<Level> lastKnownDimension;
	private int ticksSinceFix = Integer.MAX_VALUE;
	private int ticksSinceScan;
	private boolean cold;

	/** The player being hunted, if one is currently assigned. */
	public UUID targetId() {
		return targetId;
	}

	public void setTargetId(UUID id) {
		this.targetId = id;
		this.cold = false;
		this.lastKnown = null;
		this.ticksSinceFix = Integer.MAX_VALUE;
	}

	/**
	 * Hands the hunter a starting scent.
	 *
	 * <p>Without one a fair tier never moves. Its knowledge comes entirely from
	 * sight and sound, it spawns far enough away to have neither, and a hunter
	 * with no fix at all has nowhere to walk, so it stands where it appeared
	 * waiting to see a player who is busy leaving. Being told roughly where the
	 * target was is the reason it was sent, and it is the first thing any real
	 * manhunt starts with.
	 *
	 * <p>This is a single stale fix, not ongoing knowledge. It walks to where
	 * you were. Finding you from there is still its own problem.
	 */
	public void setInitialFix(BlockPos where, ResourceKey<Level> dimension) {
		this.cold = false;
		this.lastKnown = where;
		this.lastKnownDimension = dimension;
		this.ticksSinceFix = 0;
	}

	/** Last position the hunter believes its quarry occupied. May be null. */
	public BlockPos lastKnown() {
		return lastKnown;
	}

	/** True when the hunter has any usable idea where its quarry is. */
	public boolean hasFix() {
		return lastKnown != null;
	}

	/** Ticks since the belief was last refreshed from reality. */
	public int staleness() {
		return ticksSinceFix;
	}

	/**
	 * Updates the tracker for one tick.
	 *
	 * @param hunter the hunter doing the tracking
	 * @param tier   the tier, which decides the knowledge model
	 * @return the live player being hunted, or null if none is reachable
	 */
	public ServerPlayer tick(HunterEntity hunter, HunterTier tier) {
		ServerLevel level = (ServerLevel) hunter.level();
		if (ticksSinceFix < Integer.MAX_VALUE) {
			ticksSinceFix++;
		}

		ServerPlayer target = resolve(level);

		// Re-score the field on a slow cadence even while a valid target
		// exists. Without this the hunter locks onto whoever it saw first and
		// ignores the unarmoured player who walks past it later.
		if (target == null || ticksSinceScan++ >= RECONSIDER_INTERVAL) {
			ticksSinceScan = 0;
			ServerPlayer better = chooseTarget(level, hunter, tier, target);
			if (better != null) {
				target = better;
			}
		}

		if (target == null) {
			expireStaleTrail(tier);
			return null;
		}

		if (shouldRefresh(hunter, target, tier)) {
			lastKnown = target.blockPosition();
			lastKnownDimension = target.level().dimension();
			ticksSinceFix = 0;
			cold = false;
		} else {
			expireStaleTrail(tier);
		}

		return target;
	}

	/** Whether the hunter's information updates this tick. */
	private boolean shouldRefresh(HunterEntity hunter, ServerPlayer target, HunterTier tier) {
		return switch (tier.knowledge()) {
			case OMNISCIENT -> true;
			case PERIODIC -> ticksSinceFix >= tier.refreshTicks() || lastKnown == null;
			case SENSORY -> perceives(hunter, target);
		};
	}

	/**
	 * Line of sight, or enough noise to give a position away.
	 *
	 * <p>A player who is sneaking, in a different dimension, or behind cover is
	 * invisible to this. A player who sprints past is not.
	 */
	private boolean perceives(HunterEntity hunter, ServerPlayer target) {
		if (target.level() != hunter.level()) {
			return false;
		}

		double distance = hunter.distanceTo(target);

		if (distance <= SIGHT_RANGE && hunter.hasLineOfSight(target) && !target.isInvisible()) {
			return true;
		}

		boolean noisy = target.isSprinting() || target.isSwimming();
		return noisy && !target.isCrouching() && distance <= HEARING_RANGE;
	}

	/**
	 * Lets a sensory hunter's information go cold without throwing it away.
	 *
	 * <p>Discarding the position outright is what a hunter that has lost you
	 * should not do. It leaves nothing to walk to, so the hunter stops where it
	 * happens to be standing and never moves again, and from your side the mod
	 * simply looks broken. Keeping the position and marking it cold gives it
	 * somewhere to go and something to do when it gets there.
	 */
	private void expireStaleTrail(HunterTier tier) {
		if (tier.knowledge() == HunterTier.Knowledge.SENSORY && ticksSinceFix > TRAIL_TIMEOUT) {
			cold = true;
		}
	}

	/**
	 * Whether the hunter is working from information it no longer trusts.
	 *
	 * <p>True once the trail has gone stale. It still knows where you were; it
	 * no longer believes you are there.
	 */
	public boolean cold() {
		return cold;
	}

	/** Looks up the assigned player anywhere on the server. */
	private ServerPlayer resolve(ServerLevel level) {
		if (targetId == null) {
			return null;
		}
		ServerPlayer player = level.getServer().getPlayerList().getPlayer(targetId);
		return player != null && isHuntable(player) ? player : null;
	}

	/**
	 * Scores everyone on the server and returns whoever is worth hunting.
	 *
	 * <p>The interesting behaviour is in {@link Tactics#priority}: it prefers
	 * the softest target rather than the nearest, and it counts how many friends
	 * are standing near each candidate. Against a group that produces the thing
	 * a person would do, which is to ignore the huddle and take whoever has
	 * wandered off.
	 *
	 * @param current the existing target, which gets a commitment bonus so the
	 *                hunter does not turn around every time the scores shift
	 * @return the chosen target, or null when nobody is huntable
	 */
	private ServerPlayer chooseTarget(ServerLevel level, HunterEntity hunter,
			HunterTier tier, ServerPlayer current) {
		List<ServerPlayer> everyone = level.getServer().getPlayerList().getPlayers();
		ServerPlayer best = null;
		double bestScore = Double.NEGATIVE_INFINITY;

		for (ServerPlayer player : everyone) {
			if (!isHuntable(player)) {
				continue;
			}

			double score;
			if (player.level() == hunter.level()) {
				score = Tactics.priority(
						Scout.appraise(hunter, player, tier),
						companionsNear(everyone, player),
						player == current);
			} else {
				// Still valid quarry, but crossing a dimension costs enough
				// that anything reachable here wins.
				score = -100.0D + (player == current ? 10.0D : 0.0D);
			}

			if (score > bestScore) {
				bestScore = score;
				best = player;
			}
		}

		if (best != null) {
			targetId = best.getUUID();
		}
		return best;
	}

	/** How many other huntable players are standing close enough to help. */
	private int companionsNear(List<ServerPlayer> everyone, ServerPlayer candidate) {
		int count = 0;
		for (ServerPlayer other : everyone) {
			if (other == candidate || !isHuntable(other) || other.level() != candidate.level()) {
				continue;
			}
			if (other.distanceTo(candidate) <= Tactics.COMPANION_RANGE) {
				count++;
			}
		}
		return count;
	}

	/** Creative and spectator players are off the menu. */
	public static boolean isHuntable(Player player) {
		return player != null
				&& player.isAlive()
				&& !player.isSpectator()
				&& !player.isCreative();
	}

	public void save(ValueOutput out) {
		if (targetId != null) {
			out.putString("TargetId", targetId.toString());
		}
		if (lastKnown != null) {
			out.putInt("LastKnownX", lastKnown.getX());
			out.putInt("LastKnownY", lastKnown.getY());
			out.putInt("LastKnownZ", lastKnown.getZ());
		}
		if (lastKnownDimension != null) {
			out.putString("LastKnownDim", lastKnownDimension.identifier().toString());
		}
		out.putInt("TicksSinceFix", Math.min(ticksSinceFix, TRAIL_TIMEOUT * 2));
	}

	public void load(ValueInput in) {
		String id = in.getStringOr("TargetId", "");
		if (!id.isBlank()) {
			try {
				targetId = UUID.fromString(id);
			} catch (IllegalArgumentException ignored) {
				targetId = null;
			}
		}

		if (in.getInt("LastKnownY").isPresent()) {
			lastKnown = new BlockPos(
					in.getIntOr("LastKnownX", 0),
					in.getIntOr("LastKnownY", 0),
					in.getIntOr("LastKnownZ", 0));
		}

		String dim = in.getStringOr("LastKnownDim", "");
		if (!dim.isBlank()) {
			Identifier parsed = Identifier.tryParse(dim);
			if (parsed != null) {
				lastKnownDimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, parsed);
			}
		}

		ticksSinceFix = in.getIntOr("TicksSinceFix", Integer.MAX_VALUE);
	}
}
