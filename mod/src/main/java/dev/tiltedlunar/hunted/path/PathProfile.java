package dev.tiltedlunar.hunted.path;

import dev.tiltedlunar.hunted.hunter.HunterTier;

/**
 * What the hunter is allowed to do to the world, and what each option costs.
 *
 * <p>All costs are in ticks, because ticks are the only unit that lets the
 * search compare "walk around the hill" against "tunnel through it" honestly.
 * A block of stone is roughly a second and a half of mining, which is roughly
 * seven blocks of walking, and the A* weighs exactly that trade.
 *
 * @param canMine       may break blocks that are in the way
 * @param canBridge     may place blocks to cross gaps or gain height
 * @param canOpenDoors  may open a door instead of destroying it
 * @param canParkour    may sprint jump across gaps rather than going around
 * @param sprint        moves at sprint speed rather than walk speed
 * @param fireImmune    ignores lava and fire when costing a route
 * @param waterClutch   carries a water bucket, so any fall it can see the
 *                      bottom of is survivable and therefore cheap
 * @param miningSpeed   multiplier on block breaking speed, higher is faster
 * @param maxFall       tallest drop the hunter will take on purpose, in blocks
 */
public record PathProfile(
		boolean canMine,
		boolean canBridge,
		boolean canOpenDoors,
		boolean canParkour,
		boolean sprint,
		boolean fireImmune,
		boolean waterClutch,
		double miningSpeed,
		int maxFall
) {
	/** Ticks to cross one block at walking pace (4.317 blocks per second). */
	public static final double WALK_ONE = 20.0D / 4.317D;

	/** Ticks to cross one block at sprinting pace (5.612 blocks per second). */
	public static final double SPRINT_ONE = 20.0D / 5.612D;

	/** Extra cost for leaving the ground to gain a block of height. */
	public static final double JUMP_PENALTY = 3.0D;

	/** Ticks per block of free fall. Falling is the cheapest way to travel. */
	public static final double FALL_PER_BLOCK = 1.2D;

	/** Ticks to place a block and step onto it. */
	public static final double PLACE = 18.0D;

	/** Multiplier applied to movement through water. */
	public static final double SWIM_MULTIPLIER = 2.2D;

	/** Ticks to move one block along a ladder or vine. */
	public static final double CLIMB_ONE = 9.0D;

	/** Ticks to open a door rather than break it. */
	public static final double DOOR_OPEN = 8.0D;

	/** Discouragement for routing through fire, cactus, or berry bushes. */
	public static final double HARMFUL_PENALTY = 140.0D;

	/** Effectively a ban on lava, without hard coding an impossibility. */
	public static final double LAVA_PENALTY = 4_000.0D;

	/** Blocks of free fall a mob absorbs before it starts taking damage. */
	public static final int SAFE_FALL = 3;

	/** How far a hunter with a water bucket will happily drop. */
	public static final int CLUTCH_MAX_FALL = 80;

	/** Ticks spent pouring the water and picking it back up again. */
	public static final double CLUTCH_COST = 25.0D;

	/** Cost charged per block of fall damage taken. */
	public static final double FALL_DAMAGE_PENALTY = 40.0D;

	/**
	 * Widest gap of open air a sprint jump crosses on flat ground.
	 *
	 * <p>Three blocks of air, landing on the fourth. That is the standard
	 * running jump every Minecraft player has in their fingers, and it needs no
	 * timing trick. Four blocks of air is only reachable with a speed effect or
	 * a drop on landing, so the planner refuses to route through it.
	 */
	public static final int PARKOUR_FLAT_GAP = 3;

	/** Extra air the hunter clears when the landing is at least one block lower. */
	public static final int PARKOUR_DROP_BONUS = 1;

	/** Surcharge on a jump, covering both airtime and the risk of missing. */
	public static final double PARKOUR_RISK = 6.0D;

	/**
	 * The tallest drop worth taking.
	 *
	 * <p>A water bucket changes this completely. Three iron converts every
	 * cliff in the world from a wall into a shortcut, and a route down a
	 * ravine that would be suicide without one becomes the fastest way
	 * through with it.
	 */
	public int effectiveMaxFall() {
		return waterClutch ? Math.max(maxFall, CLUTCH_MAX_FALL) : maxFall;
	}

	/** Base cost of moving one block horizontally under this profile. */
	public double stepCost() {
		return sprint ? SPRINT_ONE : WALK_ONE;
	}

	/** Widest air gap this profile will attempt, given the landing height change. */
	public int parkourGap(int dropBlocks) {
		if (!canParkour || !sprint) {
			return 0;
		}
		return PARKOUR_FLAT_GAP + (dropBlocks > 0 ? PARKOUR_DROP_BONUS : 0);
	}

	/** Builds the profile matching a tier's capabilities. */
	public static PathProfile fromTier(HunterTier tier) {
		return new PathProfile(
				tier.canMine(),
				tier.canBridge(),
				tier.canOpenDoors(),
				tier.canParkour(),
				tier.canSprint(),
				tier == HunterTier.RELENTLESS,
				false,
				tier.miningSpeed(),
				tier.canBridge() ? 20 : 4
		);
	}
}
