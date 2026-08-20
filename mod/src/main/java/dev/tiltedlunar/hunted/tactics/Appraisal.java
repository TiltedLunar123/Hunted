package dev.tiltedlunar.hunted.tactics;

/**
 * What the hunter currently believes about a person, and how dangerous that
 * makes them.
 *
 * <p>Pure data on purpose. Nothing here touches Minecraft, so the threat maths
 * and every decision built on it can be tested directly instead of inferred
 * from watching a mob wander around.
 *
 * <p>The important field is {@link #healthKnown}. Lower tiers cannot see how
 * hurt you are, only what you are wearing and holding, and they have to commit
 * on that. Pretending otherwise would quietly make every tier omniscient.
 *
 * @param distance      blocks between hunter and quarry
 * @param armour        armour points, 0 to 20, as seen
 * @param weapon        what they appear to be holding
 * @param shield        whether a shield is in their off hand
 * @param healthFraction their health from 0 to 1, meaningful only when known
 * @param healthKnown   whether the tier can actually see their health
 * @param opening       an exploitable moment, if there is one
 */
public record Appraisal(
		double distance,
		int armour,
		WeaponClass weapon,
		boolean shield,
		double healthFraction,
		boolean healthKnown,
		Opening opening
) {
	/** Armour points are worth this much threat each. */
	private static final double ARMOUR_WEIGHT = 0.35D;

	/** A raised shield is worth roughly an iron sword in a fight. */
	private static final double SHIELD_VALUE = 3.0D;

	/** Even a dying player can still land a hit, so health never scales to zero. */
	private static final double HEALTH_FLOOR = 0.4D;

	/** An unknown, assumed healthy quarry. */
	public static Appraisal unknown(double distance) {
		return new Appraisal(distance, 0, WeaponClass.NONE, false, 1.0D, false, Opening.NONE);
	}

	/**
	 * How dangerous this person is right now, in the same units as
	 * {@link Readiness#score()} so the two can simply be subtracted.
	 *
	 * <p>Health scales the whole score rather than adding to it, because a
	 * player at two hearts in full diamond is still a real problem, just less of
	 * one. An opening subtracts, because it is a window rather than a weakness.
	 */
	public double threat() {
		double gear = armour * ARMOUR_WEIGHT
				+ weapon.damage()
				+ (shield ? SHIELD_VALUE : 0.0D);

		double condition = healthKnown
				? HEALTH_FLOOR + (1.0D - HEALTH_FLOOR) * clamp(healthFraction)
				: 1.0D;

		return Math.max(0.0D, gear * condition - opening.value());
	}

	/** True when this looks like someone who cannot meaningfully fight back. */
	public boolean defenceless() {
		return armour <= 2 && !weapon.isWeapon() && !shield;
	}

	/** True when the hunter can see they are nearly dead. */
	public boolean nearlyDead() {
		return healthKnown && healthFraction <= 0.35D;
	}

	private static double clamp(double value) {
		return Math.max(0.0D, Math.min(1.0D, value));
	}
}
