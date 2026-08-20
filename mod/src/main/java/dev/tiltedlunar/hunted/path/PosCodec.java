package dev.tiltedlunar.hunted.path;

/**
 * Packs a block position into a single {@code long}.
 *
 * <p>The search touches hundreds of thousands of positions. Allocating an
 * object for each one is what turns a pathfinder into a stutter, so positions
 * live as primitives from the moment they enter the open set until the final
 * path is reconstructed.
 *
 * <p>Layout matches Minecraft's own packing: 26 bits of X, 12 bits of Y, 26
 * bits of Z, each signed.
 */
public final class PosCodec {

	private static final int X_BITS = 26;
	private static final int Z_BITS = 26;
	private static final int Y_BITS = 64 - X_BITS - Z_BITS;
	private static final long X_MASK = (1L << X_BITS) - 1L;
	private static final long Y_MASK = (1L << Y_BITS) - 1L;
	private static final long Z_MASK = (1L << Z_BITS) - 1L;
	private static final int Y_SHIFT = Z_BITS;
	private static final int X_SHIFT = Z_BITS + Y_BITS;

	/** Sentinel for "no parent", used at the root of the search tree. */
	public static final long NONE = Long.MIN_VALUE;

	private PosCodec() {
	}

	public static long pack(int x, int y, int z) {
		return ((long) x & X_MASK) << X_SHIFT
				| ((long) y & Y_MASK) << Y_SHIFT
				| ((long) z & Z_MASK);
	}

	public static int x(long packed) {
		return (int) (packed << (64 - X_SHIFT - X_BITS) >> (64 - X_BITS));
	}

	public static int y(long packed) {
		return (int) (packed << (64 - Y_SHIFT - Y_BITS) >> (64 - Y_BITS));
	}

	public static int z(long packed) {
		return (int) (packed << (64 - Z_BITS) >> (64 - Z_BITS));
	}

	public static long offset(long packed, int dx, int dy, int dz) {
		return pack(x(packed) + dx, y(packed) + dy, z(packed) + dz);
	}

	public static double distance(long a, long b) {
		double dx = (double) x(a) - x(b);
		double dy = (double) y(a) - y(b);
		double dz = (double) z(a) - z(b);
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	public static String describe(long packed) {
		return x(packed) + ", " + y(packed) + ", " + z(packed);
	}
}
