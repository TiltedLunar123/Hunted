package dev.tiltedlunar.hunted.tactics;

/**
 * Works out where to run to, rather than where the target currently is.
 *
 * <p>Chasing a player's present position is the clearest sign you are fighting
 * a mob. It produces the tail-chase everyone recognises: the thing sits behind
 * you at a fixed distance and never closes, because it is always steering at
 * where you were a moment ago. Players do not do this. They cut the corner.
 *
 * <p>The maths is the standard interception problem. Given the target's
 * position and velocity, and the hunter's own speed, solve for the time at
 * which a straight line at full speed arrives at the same place the target
 * does:
 *
 * <pre>
 *   |D + V t| = s t          D = target - hunter, V = target velocity, s = speed
 *   t2 (V.V - s2) + 2t (D.V) + D.D = 0
 * </pre>
 *
 * <p>Take the smallest positive root. If there is none, the target is simply
 * faster in the direction it is going and no interception exists, so the hunter
 * falls back to running at it directly and hoping the terrain helps.
 *
 * <p>Pure static maths with no Minecraft types, so the awkward cases are
 * covered by tests rather than by standing in a field watching.
 */
public final class Interception {

	/** Never aim further ahead than three seconds. Beyond that it is fiction. */
	public static final double MAX_LEAD_TICKS = 60.0D;

	/** Below this speed the target counts as standing still. */
	private static final double MOVING_THRESHOLD = 0.02D;

	private Interception() {
	}

	/** A point in space, kept free of Minecraft types so the maths stays testable. */
	public record Point(double x, double y, double z) {
		public Point plus(Point other, double scale) {
			return new Point(x + other.x * scale, y + other.y * scale, z + other.z * scale);
		}

		public Point minus(Point other) {
			return new Point(x - other.x, y - other.y, z - other.z);
		}

		public double dot(Point other) {
			return x * other.x + y * other.y + z * other.z;
		}

		public double length() {
			return Math.sqrt(dot(this));
		}
	}

	/**
	 * The point the hunter should steer at.
	 *
	 * @param hunter      where the hunter is
	 * @param target      where the target is
	 * @param velocity    the target's movement per tick, already smoothed
	 * @param speed       the hunter's movement per tick
	 * @param confidence  0 to 1, how consistent the target's heading has been.
	 *                    Low confidence shortens the lead, because leading on a
	 *                    player who is jinking about just sends the hunter to a
	 *                    place nobody was ever going.
	 * @return the aim point, which is the target's own position when no useful
	 *         interception exists
	 */
	public static Point aim(Point hunter, Point target, Point velocity,
			double speed, double confidence) {
		if (velocity.length() < MOVING_THRESHOLD || speed <= 0.0D) {
			return target;
		}

		double lead = solveTime(hunter, target, velocity, speed);
		if (lead <= 0.0D) {
			return target;
		}

		double clamped = Math.min(lead, MAX_LEAD_TICKS) * clamp01(confidence);
		return target.plus(velocity, clamped);
	}

	/**
	 * Time in ticks until interception, or a negative number when the hunter
	 * cannot catch the target on its current heading.
	 */
	public static double solveTime(Point hunter, Point target, Point velocity, double speed) {
		Point delta = target.minus(hunter);

		double a = velocity.dot(velocity) - speed * speed;
		double b = 2.0D * delta.dot(velocity);
		double c = delta.dot(delta);

		// The target is moving at exactly the hunter's speed, so the quadratic
		// collapses to a straight line.
		if (Math.abs(a) < 1.0e-6D) {
			return Math.abs(b) < 1.0e-6D ? -1.0D : -c / b;
		}

		double discriminant = b * b - 4.0D * a * c;
		if (discriminant < 0.0D) {
			return -1.0D;
		}

		double root = Math.sqrt(discriminant);
		double first = (-b - root) / (2.0D * a);
		double second = (-b + root) / (2.0D * a);

		return smallestPositive(first, second);
	}

	private static double smallestPositive(double first, double second) {
		double low = Math.min(first, second);
		double high = Math.max(first, second);
		if (low > 0.0D) {
			return low;
		}
		return high > 0.0D ? high : -1.0D;
	}

	private static double clamp01(double value) {
		return Math.max(0.0D, Math.min(1.0D, value));
	}
}
