package dev.tiltedlunar.hunted.tactics;

/**
 * How dangerous the thing in someone's hand is.
 *
 * <p>Deliberately coarse. The hunter does not need to know that a sharpness III
 * iron sword does 8.5 damage; it needs to know whether walking into range is a
 * mistake. Four buckets answer that, and a coarse scale is also honest about
 * what can actually be seen from thirty blocks away.
 */
public enum WeaponClass {

	/** Empty hands, or something that is not a weapon at all. */
	NONE(0.0D),

	/** A pickaxe or shovel. It hurts, but not much. */
	TOOL(1.5D),

	/** Wooden or golden sword or axe. */
	WOOD(3.0D),

	/** Stone tier. */
	STONE(4.5D),

	/** Iron tier. The point where a player stops being easy. */
	IRON(6.0D),

	/** Diamond tier. */
	DIAMOND(7.5D),

	/** Netherite. Assume losing the trade. */
	NETHERITE(9.0D);

	private final double damage;

	WeaponClass(double damage) {
		this.damage = damage;
	}

	/** Rough damage per hit, used for threat scoring rather than simulation. */
	public double damage() {
		return damage;
	}

	/** Whether this is a real weapon rather than something grabbed in a hurry. */
	public boolean isWeapon() {
		return ordinal() >= WOOD.ordinal();
	}
}
