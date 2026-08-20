package dev.tiltedlunar.hunted.hunter;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;

/**
 * What the hunter knows about everything else in the world.
 *
 * <p>Most of fighting Minecraft mobs well is knowing which ones not to walk up
 * to. A zombie is a nuisance you can ignore. A creeper adjacent to you is seven
 * hearts and a hole in the ground, and the correct play is to hit it once and
 * leave rather than to trade. A warden is not a fight at all.
 *
 * <p>The hunter is not here to clear the map, so the default answer is to walk
 * past. This exists so that the exceptions are handled properly rather than
 * everything being treated as a zombie.
 */
public final class MobLore {

	/** How the hunter should deal with something in its way. */
	public enum Approach {
		/** Not a threat and not worth the seconds. Keep walking. */
		IGNORE,
		/** Kill it, it is between the hunter and its target. */
		KILL,
		/** Hit it once and immediately back out of range. */
		HIT_AND_RUN,
		/** Do not engage, and put distance between you. */
		FLEE
	}

	private MobLore() {
	}

	/**
	 * What to do about this creature.
	 *
	 * <p>Matched on the registry name rather than the class so that modded mobs
	 * with familiar names behave sensibly and anything unrecognised falls into
	 * the safe default of leaving it alone.
	 */
	public static Approach approach(LivingEntity mob) {
		String name = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath();

		return switch (name) {
			// Explodes. One hit, then out of the blast radius, every time.
			case "creeper" -> Approach.HIT_AND_RUN;

			// Not survivable, and not worth finding out.
			case "warden" -> Approach.FLEE;

			// Ranged and airborne. Nothing on the ground answers these.
			case "ghast", "phantom", "ender_dragon", "wither" -> Approach.FLEE;

			// Teleports when hit and hits hard. Only worth it if already angry.
			case "enderman" -> mob instanceof net.minecraft.world.entity.Mob angry
					&& angry.isAggressive() ? Approach.KILL : Approach.IGNORE;

			// Weak in melee, dangerous at range. Closing is the whole answer.
			case "skeleton", "stray", "bogged", "witch", "pillager", "blaze"
					-> Approach.KILL;

			// Ordinary hostiles. Worth the two seconds if they are in the way.
			case "zombie", "husk", "drowned", "spider", "cave_spider", "silverfish",
					"zombified_piglin", "vindicator", "slime", "magma_cube"
					-> Approach.KILL;

			default -> Approach.IGNORE;
		};
	}

	/**
	 * How far the hunter wants to stay from this thing while dealing with it.
	 *
	 * <p>Six blocks puts it outside a creeper's blast. Sixteen is for the
	 * things there is no answer to: standing two and a half blocks from a
	 * warden, which is what the old single number worked out to, is not keeping
	 * your distance, it is melee range.
	 */
	public static double preferredRange(LivingEntity mob) {
		return switch (approach(mob)) {
			case HIT_AND_RUN -> 6.0D;
			case FLEE -> 16.0D;
			default -> 2.6D;
		};
	}

	/** Whether this thing is currently a reason to stop what you were doing. */
	public static boolean isUrgent(LivingEntity mob) {
		Approach approach = approach(mob);
		return approach == Approach.FLEE || approach == Approach.HIT_AND_RUN;
	}
}
