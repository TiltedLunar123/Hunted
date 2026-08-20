package dev.tiltedlunar.hunted.tactics;

/**
 * The same measurement, pointed at the hunter itself.
 *
 * <p>Scored with the same weights as {@link Appraisal#threat()} so the two are
 * directly comparable. The whole tactical layer is one subtraction: what can
 * they do to me, minus what I can do to them.
 *
 * @param armour         the hunter's armour points
 * @param weapon         what it is holding
 * @param shield         whether it has a shield
 * @param healthFraction its own health, which it always knows
 * @param hasAxe         whether it can break a shield
 * @param ticksToDeath   how long it survives at the rate it is currently being
 *                       hurt, or infinity when nothing is hurting it
 */
public record Readiness(
		int armour,
		WeaponClass weapon,
		boolean shield,
		double healthFraction,
		boolean hasAxe,
		double ticksToDeath
) {
	/** About to die. Three seconds is roughly four more exchanges. */
	private static final double FATAL_HORIZON = 60.0D;

	/** Losing badly enough that the next few seconds decide the fight. */
	private static final double PRESSURE_HORIZON = 200.0D;

	private static final double ARMOUR_WEIGHT = 0.35D;
	private static final double SHIELD_VALUE = 3.0D;
	private static final double HEALTH_FLOOR = 0.4D;

	public double score() {
		double gear = armour * ARMOUR_WEIGHT
				+ weapon.damage()
				+ (shield ? SHIELD_VALUE : 0.0D);
		double condition = HEALTH_FLOOR
				+ (1.0D - HEALTH_FLOOR) * Math.max(0.0D, Math.min(1.0D, healthFraction));
		return gear * condition;
	}

	/** True when the hunter is hurt badly enough that another trade is a gamble. */
	public boolean hurt() {
		return healthFraction <= 0.3D;
	}

	/**
	 * True when it dies within a few seconds if nothing changes.
	 *
	 * <p>This is the one that overrides tier bravado. Standing your ground is a
	 * personality; standing there until you are destroyed is a bug.
	 */
	public boolean dying() {
		return ticksToDeath <= FATAL_HORIZON;
	}

	/** True when it is losing the exchange fast enough to need to change something. */
	public boolean pressured() {
		return ticksToDeath <= PRESSURE_HORIZON;
	}

	/** An empty handed hunter that has not gathered anything yet. */
	public static Readiness empty() {
		return new Readiness(0, WeaponClass.NONE, false, 1.0D, false, Double.POSITIVE_INFINITY);
	}
}
