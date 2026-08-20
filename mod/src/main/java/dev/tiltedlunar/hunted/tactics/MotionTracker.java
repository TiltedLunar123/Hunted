package dev.tiltedlunar.hunted.tactics;

/**
 * Watches how a target is moving, and how much that movement can be trusted.
 *
 * <p>Raw per-tick velocity in Minecraft is far too noisy to lead a shot with:
 * it spikes on jumps, drops to zero against a wall, and reverses whenever
 * someone taps a key. So this keeps an exponentially smoothed average, and
 * separately tracks how consistent the recent heading has been.
 *
 * <p>The consistency number is what stops the hunter looking stupid. A player
 * sprinting in a straight line is worth leading by a long way. A player
 * strafing back and forth in a doorway is worth leading by nothing at all, and
 * a hunter that leads them anyway will sprint confidently past them into a
 * wall. Confidence collapses toward zero the moment a heading stops being
 * stable, and recovers over about a second of steady movement.
 */
public final class MotionTracker {

	/** Weight of each new sample. Lower is smoother and slower to react. */
	private static final double SMOOTHING = 0.25D;

	/** How fast confidence recovers once movement settles. */
	private static final double CONFIDENCE_GAIN = 0.06D;

	/** How fast confidence collapses when the heading changes. */
	private static final double CONFIDENCE_LOSS = 0.35D;

	private double vx;
	private double vy;
	private double vz;
	private double confidence;
	private boolean seeded;

	/**
	 * Feeds in one observation.
	 *
	 * @param dx movement on x since the previous sample
	 * @param dy movement on y since the previous sample
	 * @param dz movement on z since the previous sample
	 * @param ticks how many ticks that movement took, at least 1
	 */
	public void observe(double dx, double dy, double dz, int ticks) {
		int span = Math.max(1, ticks);
		double sx = dx / span;
		double sy = dy / span;
		double sz = dz / span;

		if (!seeded) {
			vx = sx;
			vy = sy;
			vz = sz;
			seeded = true;
			return;
		}

		double alignment = alignmentWith(sx, sy, sz);

		vx += (sx - vx) * SMOOTHING;
		vy += (sy - vy) * SMOOTHING;
		vz += (sz - vz) * SMOOTHING;

		// Alignment runs from -1 to 1. Anything below a gentle turn is treated
		// as the target changing its mind, which is exactly when leading hurts.
		if (alignment > 0.7D) {
			confidence = Math.min(1.0D, confidence + CONFIDENCE_GAIN);
		} else {
			confidence = Math.max(0.0D, confidence - CONFIDENCE_LOSS);
		}
	}

	/** Cosine of the angle between the new sample and the running average. */
	private double alignmentWith(double sx, double sy, double sz) {
		double sampleLength = Math.sqrt(sx * sx + sy * sy + sz * sz);
		double averageLength = Math.sqrt(vx * vx + vy * vy + vz * vz);
		if (sampleLength < 1.0e-4D || averageLength < 1.0e-4D) {
			// Someone who has stopped is not turning, but they are also not
			// worth leading, so hold confidence where it is.
			return 1.0D;
		}
		return (sx * vx + sy * vy + sz * vz) / (sampleLength * averageLength);
	}

	/** Smoothed movement per tick. */
	public Interception.Point velocity() {
		return new Interception.Point(vx, vy, vz);
	}

	/** How much to trust the heading, from 0 to 1. */
	public double confidence() {
		return confidence;
	}

	/** Horizontal speed in blocks per tick, ignoring falling and jumping. */
	public double horizontalSpeed() {
		return Math.sqrt(vx * vx + vz * vz);
	}

	/** Forgets everything. Called when the hunter loses the target entirely. */
	public void reset() {
		vx = 0.0D;
		vy = 0.0D;
		vz = 0.0D;
		confidence = 0.0D;
		seeded = false;
	}
}
