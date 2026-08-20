package dev.tiltedlunar.hunted.path;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.PathComputationType;

/**
 * Presents a live Minecraft level to the planner.
 *
 * <p>This class is deliberately single threaded. Reading block state off the
 * server thread is the classic way to make a pathfinding mod crash with a
 * concurrent modification deep inside a chunk section, so the search runs on
 * the server thread with a per tick node budget instead. That trade buys
 * correctness for a cost the budget already caps.
 *
 * <p>Because it is single threaded it can cache aggressively. Neighbouring
 * nodes ask about the same blocks over and over, and the cache turns most of
 * those repeat questions into a map hit rather than a chunk lookup.
 */
public final class LevelWorldView implements WorldView {

	/** Blocks that are walkable but cost health. */
	private static final Set<Block> HARMFUL = Set.of(
			Blocks.FIRE,
			Blocks.SOUL_FIRE,
			Blocks.MAGMA_BLOCK,
			Blocks.CACTUS,
			Blocks.SWEET_BERRY_BUSH,
			Blocks.POWDER_SNOW,
			Blocks.WITHER_ROSE,
			Blocks.CAMPFIRE
	);

	/** Cache ceiling. Long searches drop the cache rather than grow without bound. */
	private static final int CACHE_LIMIT = 262_144;

	private final Level level;
	private final ItemStack tool;
	private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
	private final Map<Long, BlockClass> classCache = new HashMap<>();
	private final Map<Long, Float> breakCache = new HashMap<>();
	private final int minY;
	private final int maxY;

	/**
	 * @param level the level to read
	 * @param tool  the stack the hunter is holding, used to price block breaking
	 */
	public LevelWorldView(Level level, ItemStack tool) {
		this.level = level;
		this.tool = tool;
		this.minY = level.getMinY();
		this.maxY = level.getMaxY() + 1;
	}

	@Override
	public BlockClass classify(int x, int y, int z) {
		if (y < minY || y >= maxY) {
			return y < minY ? BlockClass.OBSTRUCTION : BlockClass.PASSABLE;
		}

		long key = PosCodec.pack(x, y, z);
		BlockClass cached = classCache.get(key);
		if (cached != null) {
			return cached;
		}

		BlockClass result = compute(x, y, z);
		if (classCache.size() >= CACHE_LIMIT) {
			classCache.clear();
		}
		classCache.put(key, result);
		return result;
	}

	private BlockClass compute(int x, int y, int z) {
		cursor.set(x, y, z);
		if (!level.isLoaded(cursor)) {
			return BlockClass.OBSTRUCTION;
		}

		BlockState state = level.getBlockState(cursor);

		if (state.isAir()) {
			return BlockClass.PASSABLE;
		}

		FluidState fluid = state.getFluidState();
		if (!fluid.isEmpty()) {
			return fluid.is(net.minecraft.tags.FluidTags.LAVA) ? BlockClass.LAVA : BlockClass.WATER;
		}

		if (HARMFUL.contains(state.getBlock())) {
			return BlockClass.HARMFUL;
		}

		if (state.is(BlockTags.CLIMBABLE)) {
			return BlockClass.CLIMBABLE;
		}

		if (state.is(BlockTags.DOORS) || state.is(BlockTags.FENCE_GATES)) {
			return BlockClass.DOOR;
		}

		// The engine's own "can a land mob walk through this" question, which
		// already understands torches, carpets, open trapdoors and the rest.
		if (state.isPathfindable(PathComputationType.LAND)) {
			return BlockClass.PASSABLE;
		}

		// A negative destroy speed is vanilla's way of saying "never".
		return state.getDestroySpeed(level, cursor) < 0.0f
				? BlockClass.OBSTRUCTION
				: BlockClass.SOLID;
	}

	@Override
	public float breakTicks(int x, int y, int z) {
		if (y < minY || y >= maxY) {
			return Float.POSITIVE_INFINITY;
		}

		long key = PosCodec.pack(x, y, z);
		Float cached = breakCache.get(key);
		if (cached != null) {
			return cached;
		}

		float result = computeBreakTicks(x, y, z);
		if (breakCache.size() >= CACHE_LIMIT) {
			breakCache.clear();
		}
		breakCache.put(key, result);
		return result;
	}

	private float computeBreakTicks(int x, int y, int z) {
		cursor.set(x, y, z);
		if (!level.isLoaded(cursor)) {
			return Float.POSITIVE_INFINITY;
		}
		return breakTicks(level, cursor, tool);
	}

	/**
	 * How long a block takes to break, in ticks, for a given tool.
	 *
	 * <p>Shared between the planner and the follower on purpose. If the planner
	 * priced a tunnel using one formula and the follower dug it using another,
	 * the hunter would spend its life abandoning half finished tunnels because
	 * they turned out to cost more than advertised.
	 *
	 * @return ticks required, or {@link Float#POSITIVE_INFINITY} for blocks
	 *         that cannot be broken at all
	 */
	public static float breakTicks(Level level, BlockPos pos, ItemStack tool) {
		BlockState state = level.getBlockState(pos);
		float hardness = state.getDestroySpeed(level, pos);
		if (hardness < 0.0f) {
			return Float.POSITIVE_INFINITY;
		}
		if (hardness == 0.0f) {
			return 0.0f;
		}

		float speed = tool.isEmpty() ? 1.0f : tool.getDestroySpeed(state);
		if (speed <= 0.0f) {
			speed = 1.0f;
		}
		boolean correctTool = !tool.isEmpty() && tool.isCorrectToolForDrops(state);

		// Vanilla: a block takes hardness * 30 ticks with the right tool and
		// hardness * 100 without, divided by the tool's speed multiplier.
		return hardness * (correctTool ? 30.0f : 100.0f) / speed;
	}

	@Override
	public int minY() {
		return minY;
	}

	@Override
	public int maxY() {
		return maxY;
	}

	@Override
	public boolean isLoaded(int x, int y, int z) {
		cursor.set(x, y, z);
		return level.isLoaded(cursor);
	}

	/** Drops every cached answer. Call after the world changes underneath a search. */
	public void invalidate() {
		classCache.clear();
		breakCache.clear();
	}
}
