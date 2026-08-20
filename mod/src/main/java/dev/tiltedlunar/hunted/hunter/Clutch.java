package dev.tiltedlunar.hunted.hunter;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The water bucket save.
 *
 * <p>This is the move that makes people say a player is good. You step off a
 * cliff, and somewhere in the last few blocks you put water underneath yourself,
 * land in it, and pick the bucket back up. It converts every drop in the world
 * into a shortcut, and a hunter that can do it takes routes that look reckless
 * and are not.
 *
 * <p>Worth being precise about why it is water and not blocks. Fall damage in
 * Minecraft is calculated from the total distance fallen, so placing a block
 * under yourself part way down saves you exactly the height of that block and
 * nothing more. Landing in water zeroes the whole thing. Block placement is for
 * not falling into a ravine in the first place; water is for surviving the fall
 * you already committed to.
 *
 * <p>The bucket is picked back up afterwards, because a one use bucket is not
 * worth three iron and the hunter knows it.
 */
public final class Clutch {

	/** How far above the landing spot to place. Late enough to be sure, early
	 *  enough that the block is definitely there before arrival. */
	private static final int PLACE_WINDOW = 5;

	/** How far down to look for something to land on. */
	private static final int SCAN_DEPTH = 96;

	/** Below this, the fall is survivable and the bucket stays in the pack. */
	private static final float WORTH_SAVING = 3.5f;

	/** Ticks to wait after landing before scooping the water back up. */
	private static final int RETRIEVE_DELAY = 6;

	private BlockPos placedWater;
	private int retrieveTimer;

	/**
	 * Runs one tick of self preservation.
	 *
	 * @return true when the hunter is busy saving itself and should not be
	 *         steered this tick
	 */
	public boolean tick(HunterEntity hunter, Level level) {
		// Still falling towards the water, or standing over it waiting to scoop
		// it back up. Six ticks of holding still is the price of keeping the
		// bucket, and walking away mid pickup loses it for the rest of the run.
		if (recoverBucket(hunter, level)) {
			return true;
		}

		if (hunter.onGround() || hunter.getDeltaMovement().y > -0.15D) {
			return false;
		}
		if (hunter.isInWater()) {
			return false;
		}

		BlockPos landing = findLanding(hunter, level);
		if (landing == null) {
			// Nothing below but void or lava. A bucket does not help with that.
			return false;
		}

		double drop = hunter.getY() - (landing.getY() + 1);
		double total = hunter.fallDistance + Math.max(0.0D, drop);
		if (total < WORTH_SAVING) {
			return false;
		}

		// Wait until close, so the water is not sitting there for three seconds
		// advertising where the hunter is about to be.
		if (drop > PLACE_WINDOW) {
			return true;
		}

		return pourWater(hunter, level, landing.above());
	}

	/**
	 * Whether water placed here would survive long enough to land in.
	 *
	 * <p>It does not, in the Nether. Pouring a bucket there is the worst of
	 * both outcomes: the water flashes to steam, the bucket is spent, and the
	 * fall still happens. Asking the game rather than checking the dimension
	 * by name also covers any other place that behaves the same way.
	 */
	public static boolean waterWorksAt(Level level, BlockPos at) {
		return !level.environmentAttributes()
				.getValue(net.minecraft.world.attribute.EnvironmentAttributes.WATER_EVAPORATES, at);
	}

	/** Puts the water down and remembers where, so it can be collected. */
	private boolean pourWater(HunterEntity hunter, Level level, BlockPos at) {
		if (placedWater != null) {
			return true;
		}
		if (!waterWorksAt(level, at)) {
			return false;
		}
		if (!level.getBlockState(at).canBeReplaced()) {
			return false;
		}
		if (!hunter.takeWaterBucket()) {
			return false;
		}

		level.setBlockAndUpdate(at, Blocks.WATER.defaultBlockState());
		level.playSound(null, at, SoundEvents.BUCKET_EMPTY, hunter.getSoundSource(), 1.0f, 1.0f);
		placedWater = at.immutable();
		retrieveTimer = 0;
		return true;
	}

	/**
	 * Collects the water again once the hunter is safely down.
	 *
	 * @return true when it is still busy collecting
	 */
	private boolean recoverBucket(HunterEntity hunter, Level level) {
		if (placedWater == null) {
			return false;
		}

		// Wandered off, or something else removed it. Write the bucket off
		// rather than walking back across the world for it.
		if (hunter.distanceToSqr(placedWater.getX() + 0.5D, placedWater.getY() + 0.5D,
				placedWater.getZ() + 0.5D) > 36.0D) {
			placedWater = null;
			return false;
		}

		if (!hunter.onGround() && !hunter.isInWater()) {
			return true;
		}
		if (retrieveTimer++ < RETRIEVE_DELAY) {
			return true;
		}

		BlockState state = level.getBlockState(placedWater);
		if (state.getFluidState().isSourceOfType(net.minecraft.world.level.material.Fluids.WATER)) {
			level.setBlockAndUpdate(placedWater, Blocks.AIR.defaultBlockState());
			level.playSound(null, placedWater, SoundEvents.BUCKET_FILL,
					hunter.getSoundSource(), 1.0f, 1.0f);
			hunter.returnWaterBucket();
		}

		placedWater = null;
		retrieveTimer = 0;
		return false;
	}

	/**
	 * The first solid block below the hunter.
	 *
	 * @return the block it will land on, or null when there is nothing
	 *         survivable down there
	 */
	private BlockPos findLanding(HunterEntity hunter, Level level) {
		BlockPos.MutableBlockPos cursor = hunter.blockPosition().mutable();

		for (int step = 0; step < SCAN_DEPTH; step++) {
			cursor.move(0, -1, 0);
			if (cursor.getY() < level.getMinY()) {
				return null;
			}

			BlockState state = level.getBlockState(cursor);
			if (!state.getFluidState().isEmpty()) {
				// Already landing in something. Water is fine, lava is not, and
				// neither wants a bucket poured into it.
				return null;
			}
			if (!state.canBeReplaced() && !state.isAir()) {
				return cursor.immutable();
			}
		}
		return null;
	}
}
