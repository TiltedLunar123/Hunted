package dev.tiltedlunar.hunted.path;

/**
 * One entry in a finished path: where to stand next, and how to get there.
 *
 * @param pos   packed foot position the hunter should end this step at
 * @param kind  the manoeuvre required
 * @param cost  the planner's estimate of this step in ticks
 */
public record PathStep(long pos, MoveKind kind, double cost) {

	public int x() {
		return PosCodec.x(pos);
	}

	public int y() {
		return PosCodec.y(pos);
	}

	public int z() {
		return PosCodec.z(pos);
	}

	@Override
	public String toString() {
		return kind + "(" + PosCodec.describe(pos) + ")";
	}
}
