package dev.tiltedlunar.hunted.path;

/**
 * The pathfinder's entire vocabulary for describing a block.
 *
 * <p>Collapsing a thousand block types down to eight cases is what keeps the
 * search cheap. The planner runs this classification tens of thousands of times
 * per path, so it must be an array lookup and never a chain of instanceof
 * checks against the block registry.
 */
public enum BlockClass {

	/** Air, grass, torches. Walk straight through at no cost. */
	PASSABLE(true, false, false),

	/** Ordinary terrain. Blocks movement, holds weight, can be mined. */
	SOLID(false, true, true),

	/** Bedrock, barriers, the portal frame. Blocks movement and cannot be mined. */
	OBSTRUCTION(false, true, false),

	/** Water. Passable but slow, and it does not hold weight. */
	WATER(true, false, false),

	/** Lava. Passable in the physics sense and lethal in every other sense. */
	LAVA(true, false, false),

	/** Fire, magma, cactus, berry bushes. Passable but it costs health. */
	HARMFUL(true, false, false),

	/** Ladders and vines. Passable, and climbable in the vertical direction. */
	CLIMBABLE(true, false, true),

	/** A door or gate. Solid until opened, then passable. */
	DOOR(false, true, true);

	private final boolean passable;
	private final boolean standable;
	private final boolean breakable;

	BlockClass(boolean passable, boolean standable, boolean breakable) {
		this.passable = passable;
		this.standable = standable;
		this.breakable = breakable;
	}

	/** Whether a body can occupy this block without first removing it. */
	public boolean passable() {
		return passable;
	}

	/** Whether this block can be stood on top of. */
	public boolean standable() {
		return standable;
	}

	/** Whether mining can turn this into {@link #PASSABLE}. */
	public boolean breakable() {
		return breakable;
	}
}
