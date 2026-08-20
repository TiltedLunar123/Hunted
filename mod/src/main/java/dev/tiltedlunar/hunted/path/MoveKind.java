package dev.tiltedlunar.hunted.path;

/**
 * How the hunter gets from one node to the next.
 *
 * <p>The follower needs this because walking, jumping, digging and bridging
 * look nothing alike once the path stops being a list of coordinates and starts
 * being actual entity motion.
 */
public enum MoveKind {

	/** Flat step to an adjacent block. */
	WALK,

	/** Flat step to a diagonal neighbour, with both corners verified clear. */
	DIAGONAL,

	/** Step up one block. Needs a jump. */
	ASCEND,

	/** Step down one block. */
	DESCEND,

	/** Free fall of two or more blocks onto a landing spot. */
	FALL,

	/** Jump straight up and place a block underfoot. */
	PILLAR,

	/** Step out over nothing and place a block to land on. */
	BRIDGE,

	/** Break the block underfoot and drop into the hole. */
	DIG_DOWN,

	/** Sprint jump across a gap of two or more blocks. */
	PARKOUR,

	/** Move through water. */
	SWIM,

	/** Move along a ladder or vine. */
	CLIMB;

	/** Whether this move involves leaving the ground deliberately. */
	public boolean needsJump() {
		return this == ASCEND || this == PILLAR || this == PARKOUR;
	}

	/** Whether this move only works at a run. */
	public boolean needsSprint() {
		return this == PARKOUR;
	}

	/**
	 * Whether this move ends below where it started.
	 *
	 * <p>The follower needs to know, because arriving at a step below your feet
	 * has to mean having gone down to it rather than still standing over it.
	 */
	public boolean descends() {
		return this == DESCEND || this == FALL || this == DIG_DOWN;
	}
}
