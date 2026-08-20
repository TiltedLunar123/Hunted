package dev.tiltedlunar.hunted.survival;

import java.util.Set;
import java.util.function.BiPredicate;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

/**
 * Finds the nearest block of interest, a few thousand positions at a time.
 *
 * <p>Searching a 24 block radius means looking at roughly 117,000 positions.
 * Doing that in one tick is a visible stutter, so the scan carries a cursor and
 * resumes where it left off, exactly like the pathfinder does. A full sweep
 * finishes in about two seconds of game time and nobody notices.
 *
 * <p>When a sweep finds nothing the scanner reports itself exhausted, which is
 * the signal for the caller to stop looking around and start digging.
 */
public final class ResourceScanner {

	private static final int RADIUS = 24;
	private static final int VERTICAL = 16;

	/** Sweep identities, so changing what it looks for restarts the cursor. */
	private static final Object WATER = new Object();
	private static final Object LAVA = new Object();
	private static final Object CONTAINER = new Object();

	private int cursor;
	private BlockPos origin;
	private Object looking;
	private BlockPos best;
	private double bestDistanceSq = Double.MAX_VALUE;
	private boolean exhausted;

	/** Nearest block carrying a tag. */
	public BlockPos scan(Level level, BlockPos from, TagKey<Block> target, int budget) {
		return sweep(level, from, target, (pos, state) -> state.is(target), budget);
	}

	/**
	 * Nearest water source.
	 *
	 * <p>Source blocks only. Flowing water cannot be picked up, and a hunter
	 * that walked to the edge of a stream and then failed to fill its bucket
	 * would stand there trying forever.
	 */
	public BlockPos scanForWater(Level level, BlockPos from, int budget) {
		return sweep(level, from, WATER,
				(pos, state) -> state.getFluidState().isSourceOfType(Fluids.WATER), budget);
	}

	/**
	 * Nearest block of one specific kind.
	 *
	 * <p>For the things that have no useful tag. A hay bale is the obvious one:
	 * it is the cheapest food in the game and there is no {@code BlockTags}
	 * entry that would find it.
	 */
	public BlockPos scanForBlock(Level level, BlockPos from, Block target, int budget) {
		return sweep(level, from, target, (pos, state) -> state.is(target), budget);
	}

	/**
	 * Nearest container the hunter has not already emptied.
	 *
	 * @param blocks which block types count as a container
	 * @param skip   packed positions already looted
	 */
	public BlockPos scanForContainer(Level level, BlockPos from, Set<Block> blocks,
			Set<Long> skip, int budget) {
		return sweep(level, from, CONTAINER,
				(pos, state) -> blocks.contains(state.getBlock()) && !skip.contains(pos.asLong()),
				budget);
	}

	/**
	 * Advances a sweep.
	 *
	 * @param key    identifies this search, so switching targets starts over
	 * @param match  tested against every position in range
	 * @param budget how many positions to examine this tick
	 * @return the closest match found so far, or null if nothing yet
	 */
	private BlockPos sweep(Level level, BlockPos from, Object key,
			BiPredicate<BlockPos, BlockState> match, int budget) {
		if (origin == null || looking != key || from.distSqr(origin) > 64.0D) {
			restart(from, key);
		}
		if (exhausted) {
			return best;
		}

		int width = RADIUS * 2 + 1;
		int height = VERTICAL * 2 + 1;
		int total = width * width * height;
		BlockPos.MutableBlockPos cursorPos = new BlockPos.MutableBlockPos();

		for (int i = 0; i < budget && cursor < total; i++) {
			int index = cursor++;
			int dx = index % width - RADIUS;
			int dz = index / width % width - RADIUS;
			int dy = index / (width * width) - VERTICAL;

			int x = origin.getX() + dx;
			int y = origin.getY() + dy;
			int z = origin.getZ() + dz;

			if (y < level.getMinY() || y > level.getMaxY()) {
				continue;
			}
			cursorPos.set(x, y, z);
			if (!level.isLoaded(cursorPos)) {
				continue;
			}
			if (!match.test(cursorPos, level.getBlockState(cursorPos))) {
				continue;
			}

			double distance = cursorPos.distSqr(origin);
			if (distance < bestDistanceSq) {
				bestDistanceSq = distance;
				best = cursorPos.immutable();
			}
		}

		if (cursor >= total) {
			exhausted = true;
		}
		return best;
	}

	/** True once a full sweep completed. Check {@link #found()} for the result. */
	public boolean exhausted() {
		return exhausted;
	}

	public BlockPos found() {
		return best;
	}

	/** Forgets the current sweep. Call after dealing with the block that was found. */
	public void restart(BlockPos from, Object target) {
		origin = from.immutable();
		looking = target;
		cursor = 0;
		best = null;
		bestDistanceSq = Double.MAX_VALUE;
		exhausted = false;
	}

	public void clear() {
		origin = null;
		looking = null;
		cursor = 0;
		best = null;
		bestDistanceSq = Double.MAX_VALUE;
		exhausted = false;
	}
}
