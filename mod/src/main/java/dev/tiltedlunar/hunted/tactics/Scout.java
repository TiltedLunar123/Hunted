package dev.tiltedlunar.hunted.tactics;

import dev.tiltedlunar.hunted.hunter.HunterEntity;
import dev.tiltedlunar.hunted.hunter.HunterTier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Turns a live player into an {@link Appraisal}.
 *
 * <p>This is where the honesty lives. It would be easy to read the target's
 * exact health and inventory every tick and call the result intelligence, but
 * an opponent that cannot be fooled is not an opponent. What each tier is
 * allowed to notice is limited here, and limited to things a person watching
 * from the same position could actually notice.
 *
 * <ul>
 *   <li>Sensory tiers see armour, held items and behaviour, and only with line
 *       of sight. They never learn how hurt you are.</li>
 *   <li>Periodic tiers additionally judge how hurt you look.</li>
 *   <li>The top tier sees everything, everywhere, which is the whole point of
 *       being the top tier.</li>
 * </ul>
 */
public final class Scout {

	/** Beyond this, gear cannot be made out even with a clear line of sight. */
	private static final double INSPECTION_RANGE = 40.0D;

	/** Cosine of the angle within which a player counts as facing the hunter. */
	private static final double FACING_DOT = 0.55D;

	private Scout() {
	}

	/** Reads a target through the filter of what this tier is allowed to know. */
	public static Appraisal appraise(HunterEntity hunter, Player quarry, HunterTier tier) {
		double distance = hunter.distanceTo(quarry);
		boolean omniscient = tier.knowledge() == HunterTier.Knowledge.OMNISCIENT;

		boolean canSee = omniscient
				|| (distance <= INSPECTION_RANGE && hunter.hasLineOfSight(quarry));

		if (!canSee) {
			// It knows someone is out there, and nothing else. Assuming the
			// worst here would make every hunter permanently cautious, and
			// assuming the best would make them all suicidal, so it assumes
			// average and finds out the hard way.
			return Appraisal.unknown(distance);
		}

		boolean healthKnown = tier.knowledge() != HunterTier.Knowledge.SENSORY;
		double healthFraction = quarry.getMaxHealth() > 0.0f
				? quarry.getHealth() / quarry.getMaxHealth()
				: 1.0D;

		return new Appraisal(
				distance,
				quarry.getArmorValue(),
				classify(quarry.getMainHandItem()),
				isShield(quarry.getOffhandItem()) || isShield(quarry.getMainHandItem()),
				healthFraction,
				healthKnown,
				spotOpening(hunter, quarry));
	}

	/** Scores the hunter's own equipment on the same scale. */
	public static Readiness readiness(HunterEntity hunter) {
		ItemStack held = hunter.getMainHandItem();
		boolean axe = held.is(ItemTags.AXES)
				|| hunter.survival().carrying().count(Items.STONE_AXE) > 0
				|| hunter.survival().carrying().count(Items.IRON_AXE) > 0;

		return new Readiness(
				hunter.getArmorValue(),
				classify(held),
				isShield(hunter.getOffhandItem()),
				hunter.getMaxHealth() > 0.0f ? hunter.getHealth() / hunter.getMaxHealth() : 1.0D,
				axe,
				hunter.damageClock().ticksToDeath(hunter.getHealth()));
	}

	/**
	 * Looks for a moment worth exploiting.
	 *
	 * <p>Ordered by how much the opening is worth, so the best one wins when
	 * several apply at once.
	 */
	public static Opening spotOpening(HunterEntity hunter, Player quarry) {
		if (quarry.isUsingItem() && !quarry.isBlocking()) {
			return Opening.EATING;
		}
		if (!classify(quarry.getMainHandItem()).isWeapon() && quarry.getArmorValue() <= 6) {
			return Opening.UNARMED;
		}
		if (!quarry.onGround() && quarry.fallDistance > 1.0f) {
			return Opening.FALLING;
		}
		if (quarry.swinging && !isFacing(quarry, hunter)) {
			return Opening.MINING;
		}
		if (quarry.isSwimming() || quarry.isInWater()) {
			return Opening.SWIMMING;
		}
		if (!isFacing(quarry, hunter)) {
			return Opening.DISTRACTED;
		}
		return Opening.NONE;
	}

	/** Whether the quarry is looking roughly at the hunter. */
	private static boolean isFacing(Player quarry, LivingEntity hunter) {
		Vec3 look = quarry.getLookAngle().normalize();
		Vec3 toward = hunter.position().subtract(quarry.position());
		if (toward.lengthSqr() < 1.0e-4D) {
			return true;
		}
		return look.dot(toward.normalize()) > FACING_DOT;
	}

	private static boolean isShield(ItemStack stack) {
		return !stack.isEmpty()
				&& stack.get(net.minecraft.core.component.DataComponents.BLOCKS_ATTACKS) != null;
	}

	/**
	 * Buckets an item by how much damage it represents.
	 *
	 * <p>Reads the material out of the registry name rather than matching a
	 * fixed list, so a modded iron sword still registers as an iron sword
	 * instead of quietly scoring zero.
	 */
	public static WeaponClass classify(ItemStack stack) {
		if (stack.isEmpty()) {
			return WeaponClass.NONE;
		}

		boolean weapon = stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES);
		if (!weapon) {
			return stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.SHOVELS)
					? WeaponClass.TOOL
					: WeaponClass.NONE;
		}

		String name = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		if (name.startsWith("netherite_")) {
			return WeaponClass.NETHERITE;
		}
		if (name.startsWith("diamond_")) {
			return WeaponClass.DIAMOND;
		}
		if (name.startsWith("iron_")) {
			return WeaponClass.IRON;
		}
		if (name.startsWith("stone_")) {
			return WeaponClass.STONE;
		}
		return WeaponClass.WOOD;
	}
}
