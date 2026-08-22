package dev.tiltedlunar.hunted.hunter;

import java.util.List;

import dev.tiltedlunar.hunted.path.BlockClass;
import dev.tiltedlunar.hunted.path.LevelWorldView;
import dev.tiltedlunar.hunted.path.MoveKind;
import dev.tiltedlunar.hunted.path.PathStep;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.Vec3;

/**
 * Turns a list of coordinates into an entity that is actually walking, digging
 * and building.
 *
 * <p>The planner produces intent. This produces motion, and the two disagree
 * constantly: terrain changes, the hunter gets knocked back, a step turns out
 * to be blocked by something that moved. So the follower is defensive. It
 * re-checks the world in front of it every tick and reports {@link State#STUCK}
 * rather than grinding against a wall forever, which lets the brain re-plan.
 *
 * <p>Motion goes through the vanilla movement inputs rather than direct
 * velocity writes. Setting {@code zza} and letting {@code travel()} run means
 * the hunter gets real friction, real collision and real step-up behaviour for
 * free, and it moves at exactly the speed its attributes claim.
 */
public final class PathFollower {

	/** Ticks of no progress before the follower admits it is stuck. */
	private static final int STUCK_LIMIT = 40;

	/** How close counts as standing on a step, horizontally. */
	private static final double ARRIVE_RADIUS = 0.7D;

	/**
	 * How far the hunter has to get from where it was to count as having gone
	 * anywhere. Wider than a jump is tall, so bobbing on the spot does not read
	 * as travel, and narrower than two blocks, so a tower climbing one block at
	 * a time still clears it.
	 */
	private static final double PROGRESS_RADIUS = 1.5D;

	/** Furthest a hunter will reach to break a block. */
	private static final double REACH = 4.5D;

	/** What the follower is doing right now. */
	public enum State {
		/** No path assigned. */
		IDLE,
		/** Walking toward the next step. */
		MOVING,
		/** Standing still, breaking a block that is in the way. */
		MINING,
		/** Standing still, placing a block to stand on. */
		PLACING,
		/** Reached the end of the path. */
		ARRIVED,
		/** Made no progress for a while. The brain should re-plan. */
		STUCK
	}

	private final BridgeBuilder bridge = new BridgeBuilder();
	private List<PathStep> path = List.of();
	private int index;

	private BlockPos mining;
	private float miningProgress;
	private float miningRequired;

	private Vec3 anchor;
	private int stuckTicks;

	/** Assigns a fresh path and forgets everything about the previous one. */
	public void setPath(Level level, int breakerId, List<PathStep> steps) {
		clearMining(level, breakerId);
		this.path = steps == null ? List.of() : steps;
		this.index = 0;
		this.stuckTicks = 0;
		this.anchor = null;
	}

	public List<PathStep> path() {
		return path;
	}

	/** Advances the hunter by one tick along its path. */
	public State tick(HunterEntity hunter) {
		Level level = hunter.level();
		if (index >= path.size()) {
			return path.isEmpty() ? State.IDLE : State.ARRIVED;
		}

		PathStep step = path.get(index);
		BlockPos target = new BlockPos(step.x(), step.y(), step.z());

		if (hasReached(hunter, step, target, stepGoesDown(hunter, step, index))) {
			index++;
			clearMining(level, hunter.getId());
			stuckTicks = 0;
			return index >= path.size() ? State.ARRIVED : State.MOVING;
		}

		// Keep ground under the next few steps. Holds position rather than
		// walking into a gap it has not filled yet.
		bridge.clutchSave(hunter, level);
		if (bridge.maintain(hunter, level, path, index)) {
			hunter.zza = 0.0f;
			hunter.xxa = 0.0f;
			// Waiting to place is still standing still, so it counts towards
			// being stuck. Without this a block it can never manage to put
			// down holds the hunter at the edge of the gap permanently, and
			// the brain never finds out it should try another way round.
			return trackProgress(hunter) == State.STUCK ? State.STUCK : State.PLACING;
		}

		BlockPos obstruction = firstObstruction(hunter, level, target);
		if (obstruction != null) {
			return chew(hunter, level, obstruction);
		}

		clearMining(level, hunter.getId());

		// Straight up. There is no heading to steer towards when the next step
		// is directly overhead, so steering at it produces a stale yaw and full
		// forward input, which walks the hunter off the one block tower it just
		// built. Hold still and climb.
		//
		// A pillar counts even once the hunter has risen level with the step it
		// is climbing to, because the moment after the block goes down it is
		// stood on top of a column exactly one block wide, and that is the worst
		// possible moment to be given full forward input.
		if (step.x() == hunter.getBlockX() && step.z() == hunter.getBlockZ()
				&& (step.y() > hunter.getBlockY() || step.kind() == MoveKind.PILLAR)) {
			hunter.zza = 0.0f;
			hunter.xxa = 0.0f;
			hunter.getLookControl().setLookAt(Vec3.atCenterOf(target));
			hunter.getJumpControl().jump();
			return trackProgress(hunter);
		}

		// Walking into the column of a tower it has not finished. The step is
		// only ever a block or so away here, and the arrival window is wide
		// enough to tick off the step before it, so the hunter routinely ends
		// up balanced on the very edge of what it has built with a fraction of
		// a block still to cover. Cover it on foot: the tower's own jump
		// belongs to the bridge builder, timed to the moment there is room for
		// the block, and jumping on the approach instead throws the hunter off
		// the one block wide top of its own tower every time.
		if (step.kind() == MoveKind.PILLAR) {
			steer(hunter, Vec3.atBottomCenterOf(target), false);
			if (hunter.horizontalCollision && hunter.onGround()) {
				hunter.getJumpControl().jump();
			}
			return trackProgress(hunter);
		}

		// A gap jump only clears at a run, so it overrules the quiet approach.
		// Better to be heard coming than to sneak neatly into the ravine.
		if (step.kind().needsSprint()) {
			hunter.setShiftKeyDown(false);
		}
		steer(hunter, Vec3.atBottomCenterOf(target), hunter.tier().canSprint());

		if (step.kind().needsJump() || needsHop(hunter, target)) {
			hunter.getJumpControl().jump();
		}

		return trackProgress(hunter);
	}

	// -----------------------------------------------------------------
	// Digging
	// -----------------------------------------------------------------

	/**
	 * The first block of the hunter's own body-space at the next step that it
	 * cannot simply walk into.
	 *
	 * @return the block to deal with, or null if the way is clear
	 */
	private BlockPos firstObstruction(HunterEntity hunter, Level level, BlockPos target) {
		BlockPos feet = target;
		BlockPos head = target.above();

		if (blocks(level, feet)) {
			return handleDoor(hunter, level, feet) ? null : feet;
		}
		if (blocks(level, head)) {
			return handleDoor(hunter, level, head) ? null : head;
		}
		return null;
	}

	private boolean blocks(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.isAir() || state.isPathfindable(PathComputationType.LAND)) {
			return false;
		}
		// Fluids are walked or swum through, never mined.
		return state.getFluidState().isEmpty();
	}

	/** Opens a door instead of destroying it, when the tier allows that. */
	private boolean handleDoor(HunterEntity hunter, Level level, BlockPos pos) {
		if (!hunter.tier().canOpenDoors()) {
			return false;
		}
		BlockState state = level.getBlockState(pos);
		if (!state.is(BlockTags.DOORS) || !(state.getBlock() instanceof DoorBlock door)) {
			return false;
		}
		if (state.getValue(DoorBlock.OPEN)) {
			return true;
		}
		door.setOpen(hunter, level, state, pos, true);
		return true;
	}

	/**
	 * Spends a tick breaking a block, using exactly the timing the planner
	 * assumed when it priced the route.
	 */
	private State chew(HunterEntity hunter, Level level, BlockPos pos) {
		if (!hunter.tier().canMine() || !hunter.canModifyTerrain()) {
			return trackProgress(hunter);
		}

		if (hunter.distanceToSqr(Vec3.atCenterOf(pos)) > REACH * REACH) {
			steer(hunter, Vec3.atBottomCenterOf(pos), false);
			return trackProgress(hunter);
		}

		if (!pos.equals(mining)) {
			clearMining(level, hunter.getId());
			ItemStack tool = hunter.getMainHandItem();
			float required = LevelWorldView.breakTicks(level, pos, tool);
			if (!Float.isFinite(required)) {
				return State.STUCK;
			}
			mining = pos.immutable();
			miningRequired = (float) Math.max(1.0D, required / Math.max(0.05D, hunter.tier().miningSpeed()));
			miningProgress = 0.0f;
		}

		hunter.getLookControl().setLookAt(Vec3.atCenterOf(pos));
		hunter.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
		hunter.zza = 0.0f;

		miningProgress++;
		int stage = (int) Math.min(9.0f, miningProgress / miningRequired * 10.0f);
		level.destroyBlockProgress(hunter.getId(), mining, stage);

		if (miningProgress >= miningRequired) {
			BlockPos broken = mining;
			clearMining(level, hunter.getId());
			level.destroyBlock(broken, false, hunter);
			hunter.onBrokeBlock(broken);
			stuckTicks = 0;
		}

		return State.MINING;
	}

	private void clearMining(Level level, int breakerId) {
		if (mining != null) {
			level.destroyBlockProgress(breakerId, mining, -1);
			mining = null;
		}
		miningProgress = 0.0f;
		miningRequired = 0.0f;
	}

	// -----------------------------------------------------------------
	// Motion
	// -----------------------------------------------------------------

	/** Points the hunter at a spot and applies forward movement input. */
	private void steer(HunterEntity hunter, Vec3 aim, boolean sprint) {
		double dx = aim.x - hunter.getX();
		double dz = aim.z - hunter.getZ();

		if (dx * dx + dz * dz > 1.0e-6D) {
			float yaw = (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0f;
			hunter.setYRot(yaw);
			hunter.yBodyRot = yaw;
			hunter.yHeadRot = yaw;
		}

		hunter.getLookControl().setLookAt(aim.x, aim.y + hunter.getEyeHeight(), aim.z);
		hunter.setSpeed((float) hunter.getAttributeValue(Attributes.MOVEMENT_SPEED));
		// Sneaking and sprinting at once is not a thing a player can do, and
		// asking for both would give away the quiet approach it just chose.
		hunter.setSprinting(sprint && !hunter.isShiftKeyDown());
		hunter.xxa = 0.0f;
		hunter.zza = 1.0f;
	}

	/** True when the hunter is pressed against something it can step over. */
	private boolean needsHop(HunterEntity hunter, BlockPos target) {
		return hunter.horizontalCollision && hunter.onGround()
				|| target.getY() > Mth.floor(hunter.getY());
	}

	/**
	 * Whether the hunter is standing on the step it was walking to.
	 *
	 * <p>The height window has to be tighter going down than going up. A step
	 * that drops one block sits directly below the one the hunter is on, so a
	 * generous upward tolerance counts it as already reached while the hunter
	 * is still standing on the floor it was supposed to break or step off.
	 * A whole shaft of dig-down steps would tick past in a few frames with the
	 * ground untouched, and the hunter would then aim at a cell several blocks
	 * under its feet.
	 */
	private boolean hasReached(HunterEntity hunter, PathStep step, BlockPos target, boolean goingDown) {
		double dx = hunter.getX() - (target.getX() + 0.5D);
		double dz = hunter.getZ() - (target.getZ() + 0.5D);
		double dy = hunter.getY() - target.getY();
		if (dx * dx + dz * dz > ARRIVE_RADIUS * ARRIVE_RADIUS) {
			return false;
		}

		// A tower step is only reached by standing on the block that was built
		// for it. The general window accepts anything above one block down, and
		// the first tick of the jump that is meant to make room for the block is
		// already inside that window, so the step was ticked off before the
		// block was ever placed. Every step of the tower went the same way, and
		// the hunter arrived at the top of a path it had not built, on the
		// ground, at the bottom.
		if (step.kind() == MoveKind.PILLAR) {
			return hunter.onGround() && dy > -0.2D && dy < 0.5D;
		}

		double above = goingDown ? 0.4D : 1.2D;
		return dy > -1.0D && dy < above;
	}

	/**
	 * Whether this step is below the one before it.
	 *
	 * <p>Asked geometrically rather than off {@link MoveKind}, because climbing
	 * and swimming go both ways and the kind alone cannot say which. Getting it
	 * wrong in the down direction is what makes the follower tick through a
	 * whole ladder or shaft without moving.
	 */
	private boolean stepGoesDown(HunterEntity hunter, PathStep step, int at) {
		int from = at > 0 ? path.get(at - 1).y() : hunter.getBlockY();
		return step.y() < from || step.kind().descends();
	}

	/**
	 * Watches for a hunter that is walking on the spot.
	 *
	 * <p>Measured against an anchor rather than against the previous tick. A
	 * hunter jumping in place moves a good third of a block every tick and
	 * never leaves the block it started on, and comparing consecutive ticks
	 * called that progress, so the one failure mode most likely to need
	 * catching was the one it could not see.
	 */
	private State trackProgress(HunterEntity hunter) {
		Vec3 now = hunter.position();
		if (anchor == null
				|| now.distanceToSqr(anchor) > PROGRESS_RADIUS * PROGRESS_RADIUS) {
			anchor = now;
			stuckTicks = 0;
		} else {
			stuckTicks++;
		}
		return stuckTicks >= STUCK_LIMIT ? State.STUCK : State.MOVING;
	}
}
