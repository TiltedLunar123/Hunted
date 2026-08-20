package dev.tiltedlunar.hunted.path;

/**
 * A hand drawn world for testing the planner.
 *
 * <p>Layers are given bottom up starting at y = 0. Within a layer each string
 * is one row of constant Z, and each character is one X. So the map reads the
 * way it looks from above.
 *
 * <pre>
 *   .  air            S  stone (breakable)
 *   #  bedrock        W  water
 *   L  lava           ^  ladder
 *   +  door           *  fire
 * </pre>
 */
final class GridWorld implements WorldView {

	private final String[][] layers;
	private final float breakTicks;

	private GridWorld(String[][] layers, float breakTicks) {
		this.layers = layers;
		this.breakTicks = breakTicks;
	}

	/** Builds a world from layers, each layer being one multi line string. */
	static GridWorld of(float breakTicks, String... layers) {
		String[][] parsed = new String[layers.length][];
		for (int i = 0; i < layers.length; i++) {
			parsed[i] = layers[i].strip().split("\\R");
			for (int r = 0; r < parsed[i].length; r++) {
				parsed[i][r] = parsed[i][r].strip();
			}
		}
		return new GridWorld(parsed, breakTicks);
	}

	/** A flat floor of stone with {@code height} layers of air above it. */
	static GridWorld flat(int size, int height, float breakTicks) {
		String solidRow = "S".repeat(size);
		String airRow = ".".repeat(size);
		String[] built = new String[height + 1];
		built[0] = (solidRow + "\n").repeat(size).strip();
		for (int i = 1; i <= height; i++) {
			built[i] = (airRow + "\n").repeat(size).strip();
		}
		return of(breakTicks, built);
	}

	private char at(int x, int y, int z) {
		// Outside the drawn footprint is bedrock at every height, so a map is a
		// sealed corridor. Without this the walls stop at the top layer and a
		// hunter simply climbs onto them and strolls over whatever obstacle the
		// test was trying to pose.
		if (outsideFootprint(x, z)) {
			return '#';
		}
		if (y < 0) {
			// Below the drawn map is void, not floor. Anything that walks off
			// the bottom layer falls forever, which is what makes a canyon a
			// real obstacle instead of a two block detour.
			return '.';
		}
		if (y >= layers.length) {
			return '.';
		}
		return layers[y][z].charAt(x);
	}

	/** Whether this column falls outside the drawn map. */
	private boolean outsideFootprint(int x, int z) {
		String[] ground = layers[0];
		return z < 0 || z >= ground.length || x < 0 || x >= ground[0].length();
	}

	@Override
	public BlockClass classify(int x, int y, int z) {
		return switch (at(x, y, z)) {
			case '.' -> BlockClass.PASSABLE;
			case 'S' -> BlockClass.SOLID;
			case '#' -> BlockClass.OBSTRUCTION;
			case 'W' -> BlockClass.WATER;
			case 'L' -> BlockClass.LAVA;
			case '^' -> BlockClass.CLIMBABLE;
			case '+' -> BlockClass.DOOR;
			case '*' -> BlockClass.HARMFUL;
			default -> BlockClass.SOLID;
		};
	}

	@Override
	public float breakTicks(int x, int y, int z) {
		return switch (at(x, y, z)) {
			case 'S', '+' -> breakTicks;
			case '^' -> 1.0f;
			default -> Float.POSITIVE_INFINITY;
		};
	}

	@Override
	public int minY() {
		return 0;
	}

	@Override
	public int maxY() {
		return layers.length + 8;
	}
}
