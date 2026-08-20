package dev.tiltedlunar.hunted.path;

/**
 * The narrow window the planner gets onto the world.
 *
 * <p>Everything the A* search needs is behind this interface, which is the
 * reason the search can be tested without starting Minecraft. The production
 * implementation reads the live level; the tests hand it a string drawn map.
 *
 * <p>Implementations must be cheap. {@link #classify} is on the hot path and is
 * called several times per expanded node.
 */
public interface WorldView {

	/** Classifies the block at the given position. Never returns null. */
	BlockClass classify(int x, int y, int z);

	/**
	 * How long the hunter needs to break the block at this position.
	 *
	 * @return time in ticks, or {@link Float#POSITIVE_INFINITY} if the block
	 *         cannot be broken at all
	 */
	float breakTicks(int x, int y, int z);

	/** Lowest buildable Y in this dimension, inclusive. */
	int minY();

	/** Highest buildable Y in this dimension, exclusive. */
	int maxY();

	/**
	 * Whether the position has been loaded and is safe to reason about.
	 *
	 * <p>The planner refuses to route through unloaded chunks rather than
	 * guessing, because guessing produces paths that walk confidently into
	 * terrain that turns out to be a mountain.
	 */
	default boolean isLoaded(int x, int y, int z) {
		return true;
	}

	/** True when a body of the hunter's size fits at this foot position. */
	default boolean fits(int x, int y, int z) {
		return classify(x, y, z).passable() && classify(x, y + 1, z).passable();
	}

	/** True when the block below this foot position can carry weight. */
	default boolean hasFloor(int x, int y, int z) {
		return classify(x, y - 1, z).standable();
	}
}
