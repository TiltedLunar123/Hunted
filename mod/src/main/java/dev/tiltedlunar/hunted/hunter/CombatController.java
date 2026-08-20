package dev.tiltedlunar.hunted.hunter;

import dev.tiltedlunar.hunted.tactics.Tactic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.level.block.Blocks;

/**
 * Melee behaviour built on the mechanics a competent player actually uses.
 *
 * <p>A mob that walks up and swings on cooldown is trivially beaten by circle
 * strafing, so this does the four things that separate a player from a zombie.
 *
 * <p>It <b>crits</b>. In Java Edition a critical hit needs the attacker to be
 * falling, off the ground, and not sprinting, for 1.5x damage. So the hunter
 * hops just before its cooldown expires and swings on the way down, which is
 * the same rhythm a player uses.
 *
 * <p>It <b>strafes</b>, circling rather than charging, because a straight line
 * approach is free damage for anyone holding a sword.
 *
 * <p>It <b>uses a shield</b> when it has one, and breaks yours with an axe when
 * it sees you blocking.
 *
 * <p>And it <b>respects the cooldown</b>. Swinging early does a fraction of the
 * damage, so spamming attacks is strictly worse than waiting the 13 ticks.
 */
public final class CombatController {

	/** Sword attack speed is 1.6, so a full charge takes 12.5 ticks. */
	private static final int SWING_COOLDOWN = 13;

	/**
	 * Ticks before the swing to start the crit hop.
	 *
	 * <p>A vanilla jump peaks around tick six and lands around tick eleven, so
	 * seven puts the swing in the middle of the descent with room on either
	 * side for a tick of lag.
	 */
	private static final int CRIT_HOP_LEAD = 7;

	/** Vanilla critical hit bonus. */
	private static final double CRIT_BONUS = 0.5D;

	/**
	 * How far above or below the target can be and still be hittable.
	 *
	 * <p>A little over a block. Past this it is a climbing problem, not a
	 * fighting one, and pretending otherwise is how a hunter ends up circling
	 * the foot of a dirt pillar for the rest of the game.
	 */
	private static final double VERTICAL_REACH = 1.5D;

	/** A player's entity reach in survival, to the near face of the target. */
	private static final double PLAYER_REACH = 3.0D;

	/** Seconds an axe keeps a shield down. */
	private static final float SHIELD_DISABLE_SECONDS = 5.0f;

	/** Where the hunter tries to stand relative to its quarry. */
	private static final double IDEAL_RANGE = 2.6D;

	/** Beyond this the pathfinder takes over again. */
	private static final double ENGAGE_RANGE = 6.0D;

	/** Where a defending hunter sits: just outside a sword's reach. */
	private static final double DEFENSIVE_RANGE = 4.2D;

	/** Ticks between blocks thrown down behind a fleeing hunter. */
	private static final int WALL_INTERVAL = 12;

	private static final Identifier CRIT_MODIFIER =
			Identifier.fromNamespaceAndPath("hunted", "critical_strike");

	private int swingCooldown;
	private int strafeDirection = 1;
	private int strafeTimer;
	private int shieldHold;
	private int wallTimer;

	/**
	 * Runs one tick of melee.
	 *
	 * @return true when combat is driving the hunter, false to hand control
	 *         back to the pathfinder
	 */
	public boolean tick(HunterEntity hunter, ServerLevel level, LivingEntity quarry,
			Tactic tactic) {
		double distance = hunter.distanceTo(quarry);
		// A rush is worth starting from further out, because the whole point
		// is to arrive before the window closes.
		double engageRange = tactic == Tactic.RUSH || tactic == Tactic.PRESS
				? ENGAGE_RANGE * 2.0D
				: ENGAGE_RANGE;
		if (distance > engageRange || !quarry.isAlive()) {
			releaseShield(hunter);
			return false;
		}

		// Close on the flat but well above or below: a pillar, a roof, a hole.
		// Melee cannot answer that, and holding the tick to strafe around the
		// bottom of it means the pathfinder never gets a chance to tower up or
		// dig down. Hand it back and let the route solve the height.
		double climb = Math.abs(quarry.getY() - hunter.getY());
		if (climb > VERTICAL_REACH && distance > reachOf(hunter, quarry)) {
			releaseShield(hunter);
			return false;
		}

		if (swingCooldown > 0) {
			swingCooldown--;
		}
		if (shieldHold > 0) {
			shieldHold--;
		}

		hunter.getLookControl().setLookAt(quarry, 45.0f, 45.0f);
		faceTarget(hunter, quarry);

		double reach = reachOf(hunter, quarry);
		boolean inReach = distance <= reach;

		if (shouldRetreat(hunter, tactic)) {
			retreat(hunter, level, quarry);
			return true;
		}

		if (tactic == Tactic.DEFEND) {
			defend(hunter, level, quarry, distance, inReach);
			return true;
		}

		manageShield(hunter, quarry, distance);
		manoeuvre(hunter, quarry, distance, tactic);

		// Jump early enough that the swing lands on the way back down, which is
		// the only way a melee hit crits. A jump takes about six ticks to reach
		// its apex, so hopping one tick before the swing would have it striking
		// while still going up, with fallDistance at zero and no crit at all.
		if (inReach && swingCooldown == CRIT_HOP_LEAD && hunter.onGround()) {
			hunter.setSprinting(false);
			hunter.getJumpControl().jump();
		}

		if (inReach && swingCooldown <= 0) {
			strike(hunter, level, quarry);
		}

		return true;
	}

	/**
	 * Losing the exchange, but not yet dying.
	 *
	 * <p>The mistake a bot makes here is continuing to trade because its own
	 * cooldown came up. A player who is behind stops trading. They hold the
	 * shield, sit just outside reach, and only swing into the gap after the
	 * other person has committed, when the counter is free.
	 */
	private void defend(HunterEntity hunter, ServerLevel level, LivingEntity quarry,
			double distance, boolean inReach) {
		holdShield(hunter);

		// Sit at the edge of their reach rather than inside it.
		hunter.setSpeed((float) hunter.getAttributeValue(Attributes.MOVEMENT_SPEED));
		if (distance < DEFENSIVE_RANGE - 0.5D) {
			hunter.zza = -1.0f;
			hunter.setSprinting(false);
		} else if (distance > DEFENSIVE_RANGE + 1.5D) {
			hunter.zza = 0.6f;
		} else {
			hunter.zza = 0.0f;
		}

		if (strafeTimer-- <= 0) {
			strafeTimer = 20 + hunter.getRandom().nextInt(25);
			strafeDirection = -strafeDirection;
		}
		hunter.xxa = strafeDirection * 0.6f;

		// The free hit: their swing has just landed, so their sword is cold and
		// the counter costs nothing.
		if (inReach && swingCooldown <= 0 && swordIsCold(quarry)) {
			releaseShield(hunter);
			strike(hunter, level, quarry);
		}
	}

	/**
	 * Whether the target's own attack is still recharging.
	 *
	 * <p>Java melee is decided by this more than by anything else. A hit landed
	 * while their sword is charged buys a trade; the same hit landed just after
	 * they swing is free. Only players have the cooldown, so anything else is
	 * treated as always ready.
	 */
	private boolean swordIsCold(LivingEntity quarry) {
		if (quarry instanceof net.minecraft.world.entity.player.Player player) {
			return player.getAttackStrengthScale(0.0f) < 0.6f;
		}
		return false;
	}

	// -----------------------------------------------------------------

	/**
	 * One swing at something that is not the target, if the cooldown allows it.
	 *
	 * <p>Shares the cooldown with the real fight deliberately. Free hits on a
	 * creeper while the sword is still recharging would be a small piece of
	 * cheating, and the whole point is that there is none.
	 */
	public void strikeOnce(HunterEntity hunter, ServerLevel level, LivingEntity other) {
		if (swingCooldown > 0) {
			return;
		}
		faceTarget(hunter, other);
		strike(hunter, level, other);
	}

	/** Nothing about a fight is quiet, so the crouch ends here. */
	private static void standUp(HunterEntity hunter) {
		if (hunter.isShiftKeyDown()) {
			hunter.setShiftKeyDown(false);
		}
	}

	private void strike(HunterEntity hunter, ServerLevel level, LivingEntity quarry) {
		swingCooldown = SWING_COOLDOWN;
		standUp(hunter);

		// Blocking has to stop before the hunter can swing.
		releaseShield(hunter);
		breakGuard(hunter, level, quarry);

		boolean critical = hunter.fallDistance > 0.0f
				&& !hunter.onGround()
				&& !hunter.isInWater()
				&& !hunter.onClimbable()
				&& !hunter.isSprinting();

		hunter.swing(InteractionHand.MAIN_HAND);

		AttributeInstance damage = hunter.getAttribute(Attributes.ATTACK_DAMAGE);
		AttributeModifier bonus = null;
		if (critical && damage != null) {
			bonus = new AttributeModifier(CRIT_MODIFIER, CRIT_BONUS,
					AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
			damage.addTransientModifier(bonus);
		}

		try {
			hunter.doHurtTarget(level, quarry);
		} finally {
			if (bonus != null) {
				damage.removeModifier(CRIT_MODIFIER);
			}
		}

		if (critical) {
			level.sendParticles(ParticleTypes.CRIT,
					quarry.getX(), quarry.getY() + quarry.getBbHeight() * 0.6D, quarry.getZ(),
					8, 0.3D, 0.3D, 0.3D, 0.2D);
			level.playSound(null, hunter.blockPosition(),
					SoundEvents.PLAYER_ATTACK_CRIT, hunter.getSoundSource(), 1.0f, 1.0f);
		}
	}

	/**
	 * An axe against a raised shield knocks the guard down for five seconds,
	 * which is the whole reason to carry one.
	 */
	private void breakGuard(HunterEntity hunter, ServerLevel level, LivingEntity quarry) {
		if (!hunter.getMainHandItem().is(ItemTags.AXES)) {
			return;
		}
		ItemStack blocking = quarry.getItemBlockingWith();
		if (blocking == null) {
			return;
		}
		BlocksAttacks component = blocking.get(DataComponents.BLOCKS_ATTACKS);
		if (component != null) {
			component.disable(level, quarry, SHIELD_DISABLE_SECONDS, blocking);
			if (quarry instanceof net.minecraft.server.level.ServerPlayer player) {
				hunter.taunter().announce(player, hunter.getRandom(),
						dev.tiltedlunar.hunted.taunt.Taunts.SHIELD_BREAK);
			}
		}
	}

	/** Raises the shield in the gaps between swings, lowers it to attack. */
	private void manageShield(HunterEntity hunter, LivingEntity quarry, double distance) {
		ItemStack offhand = hunter.getOffhandItem();
		if (offhand.isEmpty() || offhand.get(DataComponents.BLOCKS_ATTACKS) == null) {
			return;
		}

		boolean worthBlocking = swingCooldown > 4
				&& distance < 5.0D
				&& hunter.getHealth() < hunter.getMaxHealth() * 0.85f;

		if (worthBlocking) {
			if (!hunter.isUsingItem()) {
				hunter.startUsingItem(InteractionHand.OFF_HAND);
			}
			shieldHold = 6;
		} else if (shieldHold <= 0) {
			releaseShield(hunter);
		}
	}

	private void releaseShield(HunterEntity hunter) {
		if (hunter.isUsingItem()) {
			hunter.stopUsingItem();
		}
	}

	/**
	 * Circles the quarry at sword range instead of standing in front of it,
	 * flipping direction often enough to be hard to read.
	 */
	private void manoeuvre(HunterEntity hunter, LivingEntity quarry, double distance,
			Tactic tactic) {
		// Closing a gap is not the moment to circle. Strafing only starts
		// once the hunter is actually at sword range.
		boolean closing = distance > IDEAL_RANGE + 1.5D;
		if (closing && (tactic == Tactic.RUSH || tactic == Tactic.PRESS)) {
			hunter.setSpeed((float) hunter.getAttributeValue(Attributes.MOVEMENT_SPEED));
			hunter.setSprinting(hunter.tier().canSprint());
			hunter.xxa = 0.0f;
			hunter.zza = 1.0f;
			return;
		}

		if (strafeTimer-- <= 0) {
			strafeTimer = 25 + hunter.getRandom().nextInt(35);
			strafeDirection = -strafeDirection;
		}

		hunter.setSpeed((float) hunter.getAttributeValue(Attributes.MOVEMENT_SPEED));
		hunter.xxa = strafeDirection * 0.55f;

		if (distance > IDEAL_RANGE + 0.5D) {
			hunter.zza = 1.0f;
		} else if (distance < IDEAL_RANGE - 0.9D) {
			hunter.zza = -0.5f;
		} else {
			hunter.zza = 0.0f;
		}

		// Sprinting cancels a crit, so only sprint while closing a real gap.
		hunter.setSprinting(hunter.tier().canSprint() && distance > 4.0D);
	}

	/**
	 * Lower tiers break off when badly hurt. The top two never do, which is
	 * most of what makes them feel like a machine rather than a mob.
	 *
	 * <p>The tactic overrides both ways. A hunter told to press does not
	 * retreat at any health, because letting a nearly dead target walk away to
	 * eat is how a won fight gets lost.
	 */
	private boolean shouldRetreat(HunterEntity hunter, Tactic tactic) {
		if (tactic == Tactic.PRESS) {
			return false;
		}
		if (tactic == Tactic.WITHDRAW) {
			return true;
		}
		if (!hunter.tier().fair()) {
			return false;
		}
		return hunter.getHealth() < hunter.getMaxHealth() * 0.25f;
	}

	/**
	 * A real disengage rather than jogging backwards.
	 *
	 * <p>Backing away while facing someone is the slowest way to travel in
	 * Minecraft, so the hunter turns and runs at full speed. On the way out it
	 * throws a wall up behind itself, which is the cheap trick that actually
	 * works: it breaks line of sight and forces a pursuer to go around or dig,
	 * and either one buys the seconds the hunter needs.
	 */
	private void retreat(HunterEntity hunter, ServerLevel level, LivingEntity quarry) {
		standUp(hunter);
		holdShield(hunter);

		double dx = hunter.getX() - quarry.getX();
		double dz = hunter.getZ() - quarry.getZ();
		double length = Math.sqrt(dx * dx + dz * dz);
		if (length < 1.0e-4D) {
			dx = 1.0D;
			dz = 0.0D;
			length = 1.0D;
		}

		// Face the way out and sprint, rather than shuffling backwards.
		float yaw = (float) (Mth.atan2(dz / length, dx / length) * (180.0D / Math.PI)) - 90.0f;
		hunter.setYRot(yaw);
		hunter.yBodyRot = yaw;
		hunter.yHeadRot = yaw;
		hunter.setSpeed((float) hunter.getAttributeValue(Attributes.MOVEMENT_SPEED));
		hunter.setSprinting(true);
		hunter.xxa = 0.0f;
		hunter.zza = 1.0f;

		if (wallTimer-- <= 0 && hunter.distanceTo(quarry) < 8.0D) {
			wallTimer = WALL_INTERVAL;
			raiseWall(hunter, level, quarry);
		}
	}

	/** Drops a two block pillar between the hunter and whatever is chasing it. */
	private void raiseWall(HunterEntity hunter, ServerLevel level, LivingEntity quarry) {
		if (!hunter.tier().canBridge() || !hunter.canModifyTerrain()) {
			return;
		}

		double dx = quarry.getX() - hunter.getX();
		double dz = quarry.getZ() - hunter.getZ();
		double length = Math.sqrt(dx * dx + dz * dz);
		if (length < 1.0e-4D) {
			return;
		}

		BlockPos base = BlockPos.containing(
				hunter.getX() + dx / length * 1.6D,
				hunter.getY(),
				hunter.getZ() + dz / length * 1.6D);

		for (int dy = 0; dy < 2; dy++) {
			BlockPos at = base.above(dy);
			if (!level.getBlockState(at).canBeReplaced()) {
				continue;
			}
			// Spend a real block from the pack. Everywhere else in the mod the
			// hunter has to carry what it builds with, and a panic wall is the
			// last place it should get an exception.
			net.minecraft.world.level.block.state.BlockState block =
					hunter.takeBuildingBlock();
			if (block == null) {
				return;
			}
			level.setBlockAndUpdate(at, block);
			hunter.onPlacedBlock(at);
		}
	}

	/** Raises the shield and keeps it up, ignoring the usual economy of it. */
	private void holdShield(HunterEntity hunter) {
		ItemStack offhand = hunter.getOffhandItem();
		if (offhand.isEmpty() || offhand.get(DataComponents.BLOCKS_ATTACKS) == null) {
			return;
		}
		if (!hunter.isUsingItem()) {
			hunter.startUsingItem(InteractionHand.OFF_HAND);
		}
		shieldHold = 10;
	}

	private void faceTarget(HunterEntity hunter, LivingEntity quarry) {
		double dx = quarry.getX() - hunter.getX();
		double dz = quarry.getZ() - hunter.getZ();
		float yaw = (float) (net.minecraft.util.Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0f;
		hunter.setYRot(yaw);
		hunter.yBodyRot = yaw;
		hunter.yHeadRot = yaw;
	}

	/** Melee reach, scaled so bigger tiers do not have to be nose to nose. */
	/**
	 * How close it has to be to swing, measured centre to centre.
	 *
	 * <p>A player in survival reaches three blocks to the near face of what it
	 * is hitting, so the centre to centre figure is three plus half the target.
	 * The old formula worked out at 2.2 against another player sized body,
	 * nearly a block short, which meant the hunter had to walk inside your
	 * range and stand there being hit before it could answer.
	 */
	private double reachOf(HunterEntity hunter, LivingEntity quarry) {
		return PLAYER_REACH + quarry.getBbWidth() / 2.0D;
	}
}
