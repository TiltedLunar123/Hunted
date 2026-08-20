package dev.tiltedlunar.hunted.hunter;

import java.util.List;

import dev.tiltedlunar.hunted.path.MoveKind;
import dev.tiltedlunar.hunted.path.PathStep;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Builds the ground the hunter is about to walk on.
 *
 * <p>The naive version of this places a block, then steps onto it, then places
 * the next one. It works, and it looks terrible: the hunter stutters at every
 * edge, and any tick where placement fails is a tick where it walks into a
 * hole. Players do not bridge like that.
 *
 * <p>What players actually do is keep the ground <em>ahead</em> of themselves.
 * They place the next block before they need it, so their feet never arrive
 * somewhere unsupported and their momentum never breaks. This does the same
 * thing with two rules:
 *
 * <ol>
 *   <li>Never advance onto a block that has nothing under it. If the support is
 *       missing, stop moving and place it first. This is the rule that makes
 *       falling off impossible rather than unlikely.</li>
 *   <li>While walking, opportunistically place the support for the step after
 *       next, so the common case never has to stop at all.</li>
 * </ol>
 *
 * <p>It also handles the save: a hunter that ends up falling will place a block
 * underneath itself on the way down, which is the block version of the water
 * bucket clutch and costs it nothing but one cobblestone.
 */
public final class BridgeBuilder {

	/** How far the hunter can reach to place, matching a player's build range. */
	private static final double REACH = 4.5D;

	/** Ticks between placements. A player managing better than this is rare. */
	private static final int PLACE_INTERVAL = 3;

	/** How many steps ahead to keep supported. */
	private static final int LOOKAHEAD = 3;

	/** Fall distance at which the hunter starts trying to save itself. */
	private static final float CLUTCH_HEIGHT = 3.5f;

	private int cooldown;

	/**
	 * Keeps the path ahead walkable.
	 *
	 * @param index the step the follower is currently heading for
	 * @return true when the hunter must hold position this tick, because the
	 *         very next block it would stand on has nothing under it yet
	 */
	public boolean maintain(HunterEntity hunter, Level level, List<PathStep> path, int index) {
		if (cooldown > 0) {
			cooldown--;
		}
		if (!hunter.tier().canBridge() || !hunter.canModifyTerrain()) {
			return false;
		}

		boolean blockImmediate = false;

		for (int ahead = 0; ahead < LOOKAHEAD; ahead++) {
			int at = index + ahead;
			if (at >= path.size()) {
				break;
			}

			PathStep step = path.get(at);
			BlockPos foot = new BlockPos(step.x(), step.y(), step.z());
			BlockPos support = foot.below();

			if (!needsSupport(level, step, support)) {
				continue;
			}

			// A pillar puts the block where the hunter is standing, so it has
			// to be in the air before there is room for it.
			if (step.kind() == MoveKind.PILLAR && hunter.getY() < support.getY() + 0.9D) {
				hunter.getJumpControl().jump();
				return true;
			}

			if (place(hunter, level, support)) {
				continue;
			}

			// Could not place it. If it is the step we are about to walk onto,
			// stand still rather than stroll into the gap.
			if (ahead == 0) {
				blockImmediate = true;
			}
			break;
		}

		return blockImmediate;
	}

	/**
	 * Drops a block underfoot mid fall.
	 *
	 * <p>Only fires once the fall is already going to hurt, so it does not spend
	 * blocks on every hop off a kerb.
	 */
	public boolean clutchSave(HunterEntity hunter, Level level) {
		if (hunter.onGround() || hunter.fallDistance < CLUTCH_HEIGHT) {
			return false;
		}
		if (!hunter.tier().canBridge() || !hunter.canModifyTerrain()) {
			return false;
		}
		if (hunter.getDeltaMovement().y > -0.1D) {
			return false;
		}

		BlockPos below = hunter.blockPosition().below();
		if (!level.getBlockState(below).canBeReplaced()) {
			return false;
		}
		return place(hunter, level, below);
	}

	/** Whether this step is standing on nothing. */
	private boolean needsSupport(Level level, PathStep step, BlockPos support) {
		if (step.kind() != MoveKind.BRIDGE && step.kind() != MoveKind.PILLAR) {
			// Terrain changes under a hunter more often than you would think,
			// so a step that was solid when planned gets re-checked anyway.
			return level.getBlockState(support).canBeReplaced()
					&& level.getBlockState(support).getFluidState().isEmpty();
		}
		return level.getBlockState(support).canBeReplaced();
	}

	/**
	 * Places one block, if the hunter can reach it, has one, and the cadence
	 * allows it.
	 */
	private boolean place(HunterEntity hunter, Level level, BlockPos at) {
		if (cooldown > 0) {
			return false;
		}
		if (hunter.distanceToSqr(Vec3.atCenterOf(at)) > REACH * REACH) {
			return false;
		}
		if (!level.getBlockState(at).canBeReplaced()) {
			return true;
		}

		BlockState block = hunter.takeBuildingBlock();
		if (block == null) {
			return false;
		}

		level.setBlockAndUpdate(at, block);
		hunter.onPlacedBlock(at);
		hunter.getLookControl().setLookAt(Vec3.atCenterOf(at));
		level.playSound(null, at, SoundEvents.STONE_PLACE, hunter.getSoundSource(), 0.6f, 1.0f);
		cooldown = PLACE_INTERVAL;
		return true;
	}
}
