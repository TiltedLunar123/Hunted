package dev.tiltedlunar.hunted.tactics;

/**
 * A moment where the quarry is not able to fight back properly.
 *
 * <p>This is the single most important thing the hunter watches for. Players
 * are not dangerous continuously; they are dangerous in bursts, separated by
 * long stretches of standing still mining, or eating, or falling. A hunter that
 * only knows how to walk at you and swing will meet you at your strongest every
 * time. One that waits for these will not.
 */
public enum Opening {

	/** Nothing exploitable. They are facing you and ready. */
	NONE(0.0D, false),

	/** Mid swing on a block. Committed, facing away, and slow to react. */
	MINING(3.0D, true),

	/** Eating or drinking. Locked in the animation and cannot swing. */
	EATING(5.0D, true),

	/** In the air with no control. Cannot strafe, cannot crit, lands predictably. */
	FALLING(3.5D, true),

	/** Swimming. Slower, and cannot use a shield well. */
	SWIMMING(2.5D, true),

	/** Holding nothing that can hurt anyone. */
	UNARMED(4.0D, true),

	/** Standing still with their back turned. */
	DISTRACTED(2.0D, true);

	private final double value;
	private final boolean actionable;

	Opening(double value, boolean actionable) {
		this.value = value;
		this.actionable = actionable;
	}

	/** How much this reduces the effective threat, in threat points. */
	public double value() {
		return value;
	}

	/** Whether this is worth abandoning other plans to exploit. */
	public boolean actionable() {
		return actionable;
	}
}
