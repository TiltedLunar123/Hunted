package dev.tiltedlunar.hunted.hunter;

import java.util.Locale;

/**
 * The five escalation tiers.
 *
 * <p>The reference point is {@link #RIVAL}, and it is the default. A Rival has
 * exactly what a player has: twenty health, player walking speed, one point of
 * base attack damage with the weapon supplying the rest, a normal step height,
 * no regeneration, no mining bonus, and no knowledge of you beyond what it can
 * see and hear. It starts with nothing and has to go and get its own equipment.
 *
 * <p>That constraint is the point. A mob with sixty health and your exact
 * coordinates is not difficult, it is just tedious, and beating it proves
 * nothing. Something that plays by your rules and still catches you is a
 * different experience entirely.
 *
 * <p>{@link #SCOUT} and {@link #STALKER} sit below a player and are handicapped
 * rather than boosted. {@link #ENFORCER} and {@link #RELENTLESS} are openly
 * unfair, and {@link #fair()} reports which is which so nothing has to be
 * guessed at.
 */
public enum HunterTier {

	/** Slower than you, cannot touch the terrain, gives up easily. */
	SCOUT(
			"Scout", 20.0D, 0.085D, 1.0D, 48.0D,
			false, false, false, false,
			1.0D, Knowledge.SENSORY, 200, 1_500, 0.0D, true
	),

	/** Player health, slightly slower, breaks blocks but cannot build. */
	STALKER(
			"Stalker", 20.0D, 0.095D, 1.0D, 96.0D,
			true, false, true, false,
			1.0D, Knowledge.SENSORY, 160, 3_000, 0.0D, true
	),

	/**
	 * A player's body with a compass. The default, and the only tier worth
	 * losing to.
	 *
	 * <p>Every number here is a player's: twenty health, walking speed, one
	 * point of base damage, no free gear. What it does have is your position,
	 * always, which is the one thing a manhunt runner has never been able to
	 * take away from a hunter. Hiding behind a hill does not end it. Leaving
	 * does.
	 *
	 * <p>That is a deliberate split. Making it weaker than you is not
	 * interesting; making it blind is what made it stop dead in a hole and look
	 * broken. It knows where you are and still has to get there on foot, with
	 * gear it went and mined.
	 */
	RIVAL(
			"Rival", 20.0D, 0.1D, 1.0D, 192.0D,
			true, true, true, true,
			1.0D, Knowledge.OMNISCIENT, 1, 6_000, 0.0D, true
	),

	/** Faster than a sprinting player, mines quicker, and heals. Openly unfair. */
	ENFORCER(
			"Enforcer", 30.0D, 0.13D, 1.0D, 384.0D,
			true, true, true, true,
			1.8D, Knowledge.PERIODIC, 60, 10_000, 0.01D, false
	),

	/** Always knows where you are, in any dimension. Not a fair fight. */
	RELENTLESS(
			"Relentless", 40.0D, 0.16D, 1.0D, 1_024.0D,
			true, true, true, true,
			2.6D, Knowledge.OMNISCIENT, 1, 20_000, 0.04D, false
	);

	/** How good the hunter's information about its quarry is. */
	public enum Knowledge {
		/** Must see, hear, or smell you. Loses the trail if you are careful. */
		SENSORY,
		/** Gets a fresh fix on you every {@code refreshTicks}, and nothing in between. */
		PERIODIC,
		/** Knows your exact position every tick, in every dimension. */
		OMNISCIENT
	}

	/** A player's health, movement speed and base attack damage, for comparison. */
	public static final double PLAYER_HEALTH = 20.0D;
	public static final double PLAYER_SPEED = 0.1D;
	public static final double PLAYER_ATTACK = 1.0D;

	private final String displayName;
	private final double maxHealth;
	private final double moveSpeed;
	private final double attackDamage;
	private final double followRange;
	private final boolean canMine;
	private final boolean canBridge;
	private final boolean canOpenDoors;
	private final boolean canSprint;
	private final double miningSpeed;
	private final Knowledge knowledge;
	private final int refreshTicks;
	private final int pathBudget;
	private final double regenPerTick;
	private final boolean fair;

	HunterTier(String displayName, double maxHealth, double moveSpeed, double attackDamage,
			double followRange, boolean canMine, boolean canBridge, boolean canOpenDoors,
			boolean canSprint, double miningSpeed, Knowledge knowledge, int refreshTicks,
			int pathBudget, double regenPerTick, boolean fair) {
		this.displayName = displayName;
		this.maxHealth = maxHealth;
		this.moveSpeed = moveSpeed;
		this.attackDamage = attackDamage;
		this.followRange = followRange;
		this.canMine = canMine;
		this.canBridge = canBridge;
		this.canOpenDoors = canOpenDoors;
		this.canSprint = canSprint;
		this.miningSpeed = miningSpeed;
		this.knowledge = knowledge;
		this.refreshTicks = refreshTicks;
		this.pathBudget = pathBudget;
		this.regenPerTick = regenPerTick;
		this.fair = fair;
	}

	public String displayName() {
		return displayName;
	}

	public double maxHealth() {
		return maxHealth;
	}

	public double moveSpeed() {
		return moveSpeed;
	}

	/**
	 * Base attack damage, before the weapon.
	 *
	 * <p>Every tier sits at a player's 1.0. Whatever the hunter hits you for
	 * comes from the sword in its hand, exactly as it would for you, which is
	 * why an unarmed hunter is genuinely harmless.
	 */
	public double attackDamage() {
		return attackDamage;
	}

	public double followRange() {
		return followRange;
	}

	/** Whether the hunter may break blocks that stand between it and its quarry. */
	public boolean canMine() {
		return canMine;
	}

	/** Whether the hunter may place blocks to bridge gaps or gain height. */
	public boolean canBridge() {
		return canBridge;
	}

	public boolean canOpenDoors() {
		return canOpenDoors;
	}

	public boolean canSprint() {
		return canSprint;
	}

	/**
	 * Whether the hunter jumps gaps instead of walking around them.
	 *
	 * <p>Tied to sprinting because that is the physical truth: a running jump
	 * is the only one that clears anything worth calling a gap.
	 */
	public boolean canParkour() {
		return canSprint;
	}

	/**
	 * Multiplier on block breaking speed. Fair tiers sit at 1.0, meaning they
	 * mine at exactly the rate the tool in their hand allows.
	 */
	public double miningSpeed() {
		return miningSpeed;
	}

	public Knowledge knowledge() {
		return knowledge;
	}

	/** Ticks between position fixes under {@link Knowledge#PERIODIC}. */
	public int refreshTicks() {
		return refreshTicks;
	}

	/** Maximum A* nodes this tier may expand for a single path request. */
	public int pathBudget() {
		return pathBudget;
	}

	/** Health regained per tick. Zero for every fair tier. */
	public double regenPerTick() {
		return regenPerTick;
	}

	/**
	 * Whether this tier plays by a player's rules.
	 *
	 * <p>A fair tier has no stat above a player's, no regeneration, no mining
	 * bonus, and no information it did not earn by looking. Anything it kills
	 * you with, it had to go and find.
	 */
	public boolean fair() {
		return fair;
	}

	/** Whether this tier may cross dimensions without walking into a portal. */
	public boolean canPhase() {
		return !fair;
	}

	public HunterTier next() {
		HunterTier[] all = values();
		return all[Math.min(ordinal() + 1, all.length - 1)];
	}

	/** Lowercase id used in commands and in the config file. */
	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	/**
	 * Resolves a tier from a command argument or config value. Accepts the tier
	 * name in any case, the plain numbers 1 to 5, and "hunter" as a leftover
	 * alias for what is now the Rival tier.
	 *
	 * @return the matching tier, or {@code fallback} if nothing matched
	 */
	public static HunterTier byIdOrDefault(String raw, HunterTier fallback) {
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		String key = raw.trim().toLowerCase(Locale.ROOT);
		if (key.equals("hunter")) {
			return RIVAL;
		}
		for (HunterTier tier : values()) {
			if (tier.id().equals(key)) {
				return tier;
			}
		}
		try {
			int index = Integer.parseInt(key) - 1;
			if (index >= 0 && index < values().length) {
				return values()[index];
			}
		} catch (NumberFormatException ignored) {
			// Fall through to the default below.
		}
		return fallback;
	}
}
