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

		if (index < path.size() && path.get(index).kind() == MoveKind.PILLAR) {
			return pillar(hunter, level, path.get(index));
		}

		boolean blockImmediate = false;
		PathStep now = path.get(index);
		BlockPos feet = new BlockPos(now.x(), now.y(), now.z());

		for (int ahead = 0; ahead < LOOKAHEAD; ahead++) {
			int at = index + ahead;
			if (at >= path.size()) {
				break;
			}

			PathStep step = path.get(at);

			// A tower further down the path is not this tick's problem. Its
			// support goes where the hunter will be standing later, not where
			// it is standing now, so there is nothing to build ahead of time
			// and the height test can never pass from over here. Looking ahead
			// at one anyway is what left the hunter jumping on the spot at the
			// foot of a pillar forever: the lookahead asked for a jump and held
			// position every tick, so it could never walk into the column that
			// would have let it build.
			if (step.kind() == MoveKind.PILLAR) {
				break;
			}

			BlockPos foot = new BlockPos(step.x(), step.y(), step.z());
			BlockPos support = foot.below();

			// Never fill a space something has to stand in. A route may well
			// walk through a block early on and want to stand on that same
			// block later, and the planner prices both without noticing the
			// contradiction. Building the later one now means the follower
			// mines it for headroom on the way past, the lookahead puts it
			// straight back, and the two of them trade the same block a
			// hundred times without the hunter moving at all.
			if (support.equals(feet) || support.equals(feet.above())
					|| support.equals(hunter.blockPosition())
					|| support.equals(hunter.blockPosition().above())) {
				break;
			}

			if (!needsSupport(level, step, support)) {
				continue;
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
	 * Towers up one block.
	 *
	 * <p>The block goes where the hunter's feet currently are, so this is the
	 * one placement it cannot make from a distance and cannot make while
	 * standing on the ground. It has to be in its own column, and it has to
	 * be a full block clear of the space, or the block spawns inside its
	 * hitbox and shoves it off its own tower.
	 *
	 * @return true while the hunter is busy towering and must not be steered
	 */
	private boolean pillar(HunterEntity hunter, Level level, PathStep step) {
		BlockPos support = new BlockPos(step.x(), step.y() - 1, step.z());

		if (!level.getBlockState(support).canBeReplaced()) {
			// Already built. Climbing it is the follower's job, not this one's.
			return false;
		}

		// Only the hunter standing in the column can build the column.
		if (hunter.getBlockX() != support.getX() || hunter.getBlockZ() != support.getZ()) {
			return false;
		}

		if (hunter.getY() < support.getY() + 1.0D) {
			hunter.getJumpControl().jump();
			return true;
		}

		place(hunter, level, support);
		return true;
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
