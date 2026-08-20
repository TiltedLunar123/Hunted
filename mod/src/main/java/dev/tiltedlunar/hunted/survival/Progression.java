package dev.tiltedlunar.hunted.survival;

import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

/**
 * The ladder from empty handed to armed.
 *
 * <p>The order is the one a speedrunner uses, for the reason a speedrunner uses
 * it: every rung is the cheapest thing that unlocks the next one. Wood before
 * stone because stone needs a pickaxe. A shield early because six planks and a
 * single ingot buy more survivability than any armour piece at that price. Food
 * before iron armour, because a hunter that cannot heal loses a war of
 * attrition it would otherwise win.
 *
 * <p>The whole thing is a pure function of what the hunter is carrying. There
 * is no stored state and nothing to desynchronise, so a hunter that loses its
 * inventory to a death simply starts again from whichever rung it now fails.
 */
public final class Progression {

	/** Six logs is twenty four planks, which covers every wooden step with slack. */
	private static final int LOG_TARGET = 6;

	/** Table (4), sticks (4), pickaxe (3), shield (6), with margin. */
	private static final int PLANK_TARGET = 17;

	/** Pickaxes (4), swords (2), axe (2). */
	private static final int STICK_TARGET = 8;

	/** Pickaxe (3), sword (2), axe (3), furnace (8). */
	private static final int STONE_TARGET = 16;

	/** Sword (2), shield (1), bucket (3), chestplate (8), helmet (5). */
	private static final int IRON_TARGET = 19;

	/** Enough meals to survive a long fight and a longer walk. */
	private static final int FOOD_TARGET = 4;

	/** Iron is densest here in the overworld, and it is above the lava line. */
	public static final int IRON_DIG_Y = 16;

	/** What the hunter should be doing right now. */
	public sealed interface Task {
		/** Chop or mine blocks carrying {@code target} until the item count is met. */
		record Gather(TagKey<Block> target, Item expected, int untilCount, int preferredY)
				implements Task {
		}

		/**
		 * Look for one specific block, and do something else if there is none.
		 *
		 * <p>Separate from {@link Gather} because hay bales have no block tag
		 * to search by, and because a hay bale is opportunistic: if there is no
		 * farm nearby the hunter should go and find an animal instead of
		 * standing still wanting bread.
		 */
		record GatherBlock(Block target, Item expected, int untilCount, Task orElse)
				implements Task {
		}

		/** Turn what is carried into something better. */
		record Craft(CraftRecipe recipe) implements Task {
		}

		/** Burn ore into ingots, or meat into a meal. */
		record Smelt(Item input, int count) implements Task {
		}

		/** Find water and fill the bucket. */
		record FetchWater() implements Task {
		}

		/** Find an animal and turn it into dinner. */
		record HuntFood() implements Task {
		}

		/** Nothing left to do but hunt. */
		record Hunt() implements Task {
		}
	}

	/** A reason to depart from the normal order of the ladder. */
	public enum Focus {
		/** Work the ladder as written. */
		NONE,
		/** Get an axe as soon as possible, because the target is using a shield. */
		SHIELD_BREAKER
	}

	private static final Task HUNT = new Task.Hunt();
	private static final Task FETCH_WATER = new Task.FetchWater();
	private static final Task HUNT_FOOD = new Task.HuntFood();

	private Progression() {
	}

	/** True when the hunter is already carrying something that breaks shields. */
	public static boolean hasAxe(HunterInventory carrying) {
		return carrying.has(Items.STONE_AXE, 1)
				|| carrying.has(Items.IRON_AXE, 1)
				|| carrying.has(Items.WOODEN_AXE, 1);
	}

	/** How many meals the hunter is carrying, counting only food worth eating. */
	public static int mealsOnHand(HunterInventory carrying) {
		int total = 0;
		for (Item food : Recipes.PREPARED_FOOD) {
			total += carrying.count(food);
		}
		return total;
	}

	public static Task next(HunterInventory carrying) {
		return next(carrying, Focus.NONE, true);
	}

	public static Task next(HunterInventory carrying, Focus focus) {
		return next(carrying, focus, true);
	}

	/**
	 * The first unmet rung, with an optional reason to jump the queue.
	 *
	 * <p>A shield changes the shopping list. An axe is cheap, it disables a
	 * shield for five seconds, and no amount of armour substitutes for it, so
	 * when the target is blocking the axe is worth building out of order.
	 *
	 * @param waterWorks false where water evaporates, which skips the bucket
	 *                   rungs entirely. Without this a hunter in the Nether
	 *                   waits on a bucket of water it can never fill, and the
	 *                   ladder stops there for good.
	 */
	public static Task next(HunterInventory carrying, Focus focus, boolean waterWorks) {
		if (focus == Focus.SHIELD_BREAKER && !hasAxe(carrying)) {
			if (Recipes.IRON_AXE.canCraft(carrying)) {
				return new Task.Craft(Recipes.IRON_AXE);
			}
			if (Recipes.STONE_AXE.canCraft(carrying)) {
				return new Task.Craft(Recipes.STONE_AXE);
			}
			// Cannot make one yet. Fall through, because the normal ladder is
			// already gathering exactly the wood and stone an axe needs.
		}

		// Wood.
		if (!carrying.hasTag(ItemTags.PLANKS, PLANK_TARGET)
				&& !carrying.hasTag(ItemTags.LOGS, 1)) {
			return new Task.Gather(BlockTags.LOGS, Items.OAK_LOG, LOG_TARGET, Integer.MIN_VALUE);
		}
		if (!carrying.hasTag(ItemTags.PLANKS, PLANK_TARGET)) {
			return new Task.Craft(Recipes.PLANKS);
		}
		if (!carrying.has(Items.CRAFTING_TABLE, 1)) {
			return new Task.Craft(Recipes.CRAFTING_TABLE);
		}
		if (!carrying.has(Items.STICK, STICK_TARGET)) {
			return new Task.Craft(Recipes.STICKS);
		}
		if (!carrying.has(Items.WOODEN_PICKAXE, 1) && !carrying.has(Items.STONE_PICKAXE, 1)) {
			return new Task.Craft(Recipes.WOODEN_PICKAXE);
		}

		// Stone.
		if (!carrying.hasTag(Recipes.STONE_TOOL_MATERIALS, STONE_TARGET)
				&& !carrying.has(Items.FURNACE, 1)) {
			return new Task.Gather(BlockTags.BASE_STONE_OVERWORLD, Items.COBBLESTONE,
					STONE_TARGET, Integer.MIN_VALUE);
		}
		if (!carrying.has(Items.STONE_PICKAXE, 1)) {
			return new Task.Craft(Recipes.STONE_PICKAXE);
		}
		if (!carrying.has(Items.STONE_SWORD, 1) && !carrying.has(Items.IRON_SWORD, 1)) {
			return new Task.Craft(Recipes.STONE_SWORD);
		}
		if (!carrying.has(Items.FURNACE, 1) && !carrying.has(Items.IRON_INGOT, IRON_TARGET)) {
			return new Task.Craft(Recipes.FURNACE);
		}

		// Food, before armour. A hunter that cannot heal loses fights it won.
		if (mealsOnHand(carrying) < FOOD_TARGET) {
			// A hay bale is the cheapest food in the game. Nine wheat, three
			// loaves, and no animal to chase.
			if (carrying.has(Items.HAY_BLOCK, 1)) {
				return new Task.Craft(Recipes.WHEAT_FROM_HAY);
			}
			if (carrying.has(Items.WHEAT, 3)) {
				return new Task.Craft(Recipes.BREAD);
			}

			Item raw = Recipes.rawFoodOnHand(carrying);
			if (raw != null && Recipes.smeltResult(raw) != null) {
				// A smoker cooks food in half the time and costs four logs on
				// top of a furnace it already has. Worth it before a long cook.
				if (!carrying.has(Items.SMOKER, 1) && Recipes.SMOKER.canCraft(carrying)) {
					return new Task.Craft(Recipes.SMOKER);
				}
				return new Task.Smelt(raw, carrying.count(raw));
			}

			// A stack of hay is three loaves and no chasing. Worth a detour if
			// there is a farm or a village in range.
			return new Task.GatherBlock(net.minecraft.world.level.block.Blocks.HAY_BLOCK,
					Items.HAY_BLOCK, 1, HUNT_FOOD);
		}

		// Iron.
		int ingots = carrying.count(Items.IRON_INGOT);
		int rawIron = carrying.count(Items.RAW_IRON);
		if (ingots + rawIron < IRON_TARGET) {
			return new Task.Gather(BlockTags.IRON_ORES, Items.RAW_IRON,
					IRON_TARGET - ingots, IRON_DIG_Y);
		}
		if (rawIron > 0) {
			return new Task.Smelt(Items.RAW_IRON, rawIron);
		}

		// Gear, cheapest survivability first.
		if (!carrying.has(Items.IRON_SWORD, 1)) {
			return new Task.Craft(Recipes.IRON_SWORD);
		}
		if (!carrying.has(Items.SHIELD, 1)) {
			return new Task.Craft(Recipes.SHIELD);
		}

		Task bucket = bucketStep(carrying, waterWorks);
		if (bucket != null) {
			return bucket;
		}

		if (!carrying.has(Items.IRON_CHESTPLATE, 1)) {
			return new Task.Craft(Recipes.IRON_CHESTPLATE);
		}
		if (!carrying.has(Items.IRON_HELMET, 1)) {
			return new Task.Craft(Recipes.IRON_HELMET);
		}

		// Everything past this point is a bonus, not a rung. The hunter is
		// already armed, armoured and able to heal, so it will not go back down
		// the mine for any of it. It builds these only out of material it is
		// already carrying, which is why every one is guarded on canCraft
		// rather than on a gathering target.
		return spare(carrying);
	}

	/**
	 * The water bucket rungs, or null when there is nothing to do about them.
	 *
	 * <p>Three iron for the ability to survive any fall in the world is the
	 * best trade on the whole list, which is why it comes before armour. It is
	 * worth exactly nothing in the Nether, so it is skipped entirely there
	 * rather than spending the iron on a trick that cannot work and then
	 * waiting forever for water that will never be found.
	 *
	 * <p>Package private so it can be tested on its own. The rungs above it are
	 * tag based, and tags are empty outside a running world, so a test can
	 * never walk the ladder this far.
	 */
	static Task bucketStep(HunterInventory carrying, boolean waterWorks) {
		if (!waterWorks || carrying.has(Items.WATER_BUCKET, 1)) {
			return null;
		}
		if (!carrying.has(Items.BUCKET, 1)) {
			return new Task.Craft(Recipes.BUCKET);
		}
		return FETCH_WATER;
	}

	/**
	 * Upgrades worth making out of leftovers, best first.
	 *
	 * <p>Diamond first because it is strictly better and the hunter only ever
	 * has diamonds by luck: a chest, or a vein it happened to tunnel through on
	 * the way to iron. Nothing here ever sends it looking.
	 *
	 * <p>Package private for the same reason as {@link #bucketStep}: the rungs
	 * above it are tag based and unreachable from a test.
	 */
	static Task spare(HunterInventory carrying) {
		if (!carrying.has(Items.DIAMOND_SWORD, 1) && Recipes.DIAMOND_SWORD.canCraft(carrying)) {
			return new Task.Craft(Recipes.DIAMOND_SWORD);
		}
		if (!carrying.has(Items.DIAMOND_CHESTPLATE, 1)
				&& Recipes.DIAMOND_CHESTPLATE.canCraft(carrying)) {
			return new Task.Craft(Recipes.DIAMOND_CHESTPLATE);
		}
		if (!carrying.has(Items.DIAMOND_HELMET, 1)
				&& Recipes.DIAMOND_HELMET.canCraft(carrying)) {
			return new Task.Craft(Recipes.DIAMOND_HELMET);
		}
		if (!carrying.has(Items.DIAMOND_LEGGINGS, 1)
				&& Recipes.DIAMOND_LEGGINGS.canCraft(carrying)) {
			return new Task.Craft(Recipes.DIAMOND_LEGGINGS);
		}
		if (!carrying.has(Items.DIAMOND_BOOTS, 1) && Recipes.DIAMOND_BOOTS.canCraft(carrying)) {
			return new Task.Craft(Recipes.DIAMOND_BOOTS);
		}
		if (!carrying.has(Items.DIAMOND_PICKAXE, 1)
				&& Recipes.DIAMOND_PICKAXE.canCraft(carrying)) {
			return new Task.Craft(Recipes.DIAMOND_PICKAXE);
		}
		if (!carrying.has(Items.DIAMOND_AXE, 1) && Recipes.DIAMOND_AXE.canCraft(carrying)) {
			return new Task.Craft(Recipes.DIAMOND_AXE);
		}

		// The rest of the iron set, if the mine was generous.
		if (!carrying.has(Items.IRON_LEGGINGS, 1) && Recipes.IRON_LEGGINGS.canCraft(carrying)) {
			return new Task.Craft(Recipes.IRON_LEGGINGS);
		}
		if (!carrying.has(Items.IRON_BOOTS, 1) && Recipes.IRON_BOOTS.canCraft(carrying)) {
			return new Task.Craft(Recipes.IRON_BOOTS);
		}
		if (!carrying.has(Items.IRON_PICKAXE, 1) && Recipes.IRON_PICKAXE.canCraft(carrying)) {
			return new Task.Craft(Recipes.IRON_PICKAXE);
		}
		if (!hasAxe(carrying) && Recipes.IRON_AXE.canCraft(carrying)) {
			return new Task.Craft(Recipes.IRON_AXE);
		}
		if (!carrying.has(Items.IRON_SHOVEL, 1) && Recipes.IRON_SHOVEL.canCraft(carrying)) {
			return new Task.Craft(Recipes.IRON_SHOVEL);
		}

		// A flint and steel, for setting dinner on fire. Only ever from flint it
		// already picked up; it will not go digging gravel hoping for a drop.
		if (!carrying.has(Items.FLINT_AND_STEEL, 1)
				&& Recipes.FLINT_AND_STEEL.canCraft(carrying)) {
			return new Task.Craft(Recipes.FLINT_AND_STEEL);
		}

		return HUNT;
	}

	/** A short label for the status readout. */
	public static String describe(Task task) {
		return switch (task) {
			case Task.Gather gather -> "gathering " + name(gather.expected());
			case Task.GatherBlock gather -> "gathering " + name(gather.expected());
			case Task.Craft craft -> "crafting " + name(craft.recipe().result());
			case Task.Smelt smelt -> "smelting " + smelt.count() + " " + name(smelt.input());
			case Task.FetchWater ignored -> "looking for water";
			case Task.HuntFood ignored -> "hunting for food";
			case Task.Hunt ignored -> "hunting";
		};
	}

	private static String name(Item item) {
		return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath();
	}
}
