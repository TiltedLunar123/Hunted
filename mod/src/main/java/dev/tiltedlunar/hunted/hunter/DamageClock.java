package dev.tiltedlunar.hunted.hunter;

/**
 * Tracks how fast the hunter is losing health, and therefore how long it has.
 *
 * <p>A flat "run below 25 percent" rule is wrong in both directions, and both
 * failures are visible in play. A hunter on half health taking netherite crits
 * is already dead and should have left two swings ago. A hunter on two hearts
 * that nothing has touched in twenty seconds should not be cowering in a hole.
 *
 * <p>What matters is the rate. This keeps a five second rolling window of
 * damage and turns it into an estimated time of death, which is the number the
 * tactical layer actually wants.
 */
public final class DamageClock {

	/** Rolling window length in ticks. Five seconds is long enough to smooth
	 *  a single unlucky crit without being slow to notice a real beating. */
	private static final int WINDOW = 100;

	private final float[] buckets = new float[WINDOW];
	private int cursor;
	private float total;
	private int sinceDamage = Integer.MAX_VALUE;

	/** Advances one tick, retiring the oldest bucket. */
	public void tick() {
		cursor = (cursor + 1) % WINDOW;
		total -= buckets[cursor];
		buckets[cursor] = 0.0f;
		if (sinceDamage < Integer.MAX_VALUE) {
			sinceDamage++;
		}
	}

	/** Records damage taken this tick. */
	public void record(float amount) {
		if (amount <= 0.0f) {
			return;
		}
		buckets[cursor] += amount;
		total += amount;
		sinceDamage = 0;
	}

	/** Damage per tick averaged over the window. */
	public double rate() {
		return total / (double) WINDOW;
	}

	/**
	 * Estimated ticks until death at the current rate.
	 *
	 * @return a large number when nothing is hurting it, which is the honest
	 *         answer rather than a made up ceiling
	 */
	public double ticksToDeath(float currentHealth) {
		double perTick = rate();
		if (perTick <= 1.0e-4D) {
			return Double.POSITIVE_INFINITY;
		}
		return Math.max(0.0D, currentHealth / perTick);
	}

	/** True when nothing has touched it for the given number of ticks. */
	public boolean calmFor(int ticks) {
		return sinceDamage >= ticks;
	}

	public void reset() {
		java.util.Arrays.fill(buckets, 0.0f);
		total = 0.0f;
		cursor = 0;
		sinceDamage = Integer.MAX_VALUE;
	}
}
