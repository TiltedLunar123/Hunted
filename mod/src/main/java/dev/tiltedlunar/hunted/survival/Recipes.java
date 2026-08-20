package dev.tiltedlunar.hunted.survival;

import java.util.List;

import dev.tiltedlunar.hunted.survival.CraftRecipe.Need;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * The hunter's crafting knowledge.
 *
 * <p>Every count here was extracted from {@code data/minecraft/recipe} in the
 * Minecraft 26.2 jar rather than recalled, because a wrong ingredient count
 * does not crash. It produces a bot that stands in a forest forever waiting for
 * a fourth plank it does not need, which is a far worse bug to find.
 *
 * <p>{@code ProgressionTest} pins the item based costs and checks that the gear
 * list fits the iron the ladder actually gathers. Tag based amounts are not
 * covered there, since tags only exist once a world has loaded its data pack.
 */
public final class Recipes {

	/** Cobblestone, cobbled deepslate or blackstone. Read from the real tag. */
	public static final TagKey<Item> STONE_TOOL_MATERIALS = TagKey.create(
			Registries.ITEM, Identifier.withDefaultNamespace("stone_tool_materials"));

	public static final CraftRecipe PLANKS = new CraftRecipe(
			Items.OAK_PLANKS, 4, List.of(Need.ofTag(ItemTags.LOGS, 1)), false);

	public static final CraftRecipe STICKS = new CraftRecipe(
			Items.STICK, 4, List.of(Need.ofTag(ItemTags.PLANKS, 2)), false);

	public static final CraftRecipe CRAFTING_TABLE = new CraftRecipe(
			Items.CRAFTING_TABLE, 1, List.of(Need.ofTag(ItemTags.PLANKS, 4)), false);

	public static final CraftRecipe WOODEN_PICKAXE = new CraftRecipe(
			Items.WOODEN_PICKAXE, 1,
			List.of(Need.ofTag(ItemTags.PLANKS, 3), Need.of(Items.STICK, 2)), true);

	public static final CraftRecipe STONE_PICKAXE = new CraftRecipe(
			Items.STONE_PICKAXE, 1,
			List.of(Need.ofTag(STONE_TOOL_MATERIALS, 3), Need.of(Items.STICK, 2)), true);

	public static final CraftRecipe STONE_SWORD = new CraftRecipe(
			Items.STONE_SWORD, 1,
			List.of(Need.ofTag(STONE_TOOL_MATERIALS, 2), Need.of(Items.STICK, 1)), true);

	public static final CraftRecipe STONE_AXE = new CraftRecipe(
			Items.STONE_AXE, 1,
			List.of(Need.ofTag(STONE_TOOL_MATERIALS, 3), Need.of(Items.STICK, 2)), true);

	public static final CraftRecipe FURNACE = new CraftRecipe(
			Items.FURNACE, 1, List.of(Need.ofTag(STONE_TOOL_MATERIALS, 8)), true);

	public static final CraftRecipe IRON_PICKAXE = new CraftRecipe(
			Items.IRON_PICKAXE, 1,
			List.of(Need.of(Items.IRON_INGOT, 3), Need.of(Items.STICK, 2)), true);

	public static final CraftRecipe IRON_SWORD = new CraftRecipe(
			Items.IRON_SWORD, 1,
			List.of(Need.of(Items.IRON_INGOT, 2), Need.of(Items.STICK, 1)), true);

	public static final CraftRecipe IRON_AXE = new CraftRecipe(
			Items.IRON_AXE, 1,
			List.of(Need.of(Items.IRON_INGOT, 3), Need.of(Items.STICK, 2)), true);

	/** Six planks and one ingot. The single best value item in the early game. */
	public static final CraftRecipe SHIELD = new CraftRecipe(
			Items.SHIELD, 1,
			List.of(Need.ofTag(ItemTags.PLANKS, 6), Need.of(Items.IRON_INGOT, 1)), true);

	public static final CraftRecipe IRON_HELMET = new CraftRecipe(
			Items.IRON_HELMET, 1, List.of(Need.of(Items.IRON_INGOT, 5)), true);

	public static final CraftRecipe IRON_CHESTPLATE = new CraftRecipe(
			Items.IRON_CHESTPLATE, 1, List.of(Need.of(Items.IRON_INGOT, 8)), true);

	public static final CraftRecipe IRON_LEGGINGS = new CraftRecipe(
			Items.IRON_LEGGINGS, 1, List.of(Need.of(Items.IRON_INGOT, 7)), true);

	public static final CraftRecipe IRON_BOOTS = new CraftRecipe(
			Items.IRON_BOOTS, 1, List.of(Need.of(Items.IRON_INGOT, 4)), true);

	/** Diamond tier, for when it finds diamonds. Same shapes, better material. */
	public static final TagKey<Item> DIAMOND_TOOL_MATERIALS = TagKey.create(
			Registries.ITEM, Identifier.withDefaultNamespace("diamond_tool_materials"));

	public static final CraftRecipe DIAMOND_PICKAXE = new CraftRecipe(
			Items.DIAMOND_PICKAXE, 1,
			List.of(Need.of(Items.DIAMOND, 3), Need.of(Items.STICK, 2)), true);

	public static final CraftRecipe DIAMOND_SWORD = new CraftRecipe(
			Items.DIAMOND_SWORD, 1,
			List.of(Need.of(Items.DIAMOND, 2), Need.of(Items.STICK, 1)), true);

	public static final CraftRecipe DIAMOND_AXE = new CraftRecipe(
			Items.DIAMOND_AXE, 1,
			List.of(Need.of(Items.DIAMOND, 3), Need.of(Items.STICK, 2)), true);

	public static final CraftRecipe DIAMOND_HELMET = new CraftRecipe(
			Items.DIAMOND_HELMET, 1, List.of(Need.of(Items.DIAMOND, 5)), true);

	public static final CraftRecipe DIAMOND_CHESTPLATE = new CraftRecipe(
			Items.DIAMOND_CHESTPLATE, 1, List.of(Need.of(Items.DIAMOND, 8)), true);

	public static final CraftRecipe DIAMOND_LEGGINGS = new CraftRecipe(
			Items.DIAMOND_LEGGINGS, 1, List.of(Need.of(Items.DIAMOND, 7)), true);

	public static final CraftRecipe DIAMOND_BOOTS = new CraftRecipe(
			Items.DIAMOND_BOOTS, 1, List.of(Need.of(Items.DIAMOND, 4)), true);

	public static final CraftRecipe IRON_SHOVEL = new CraftRecipe(
			Items.IRON_SHOVEL, 1,
			List.of(Need.of(Items.IRON_INGOT, 1), Need.of(Items.STICK, 2)), true);

	/** Three iron, and the single best value item in the game after a shield. */
	public static final CraftRecipe BUCKET = new CraftRecipe(
			Items.BUCKET, 1, List.of(Need.of(Items.IRON_INGOT, 3)), true);

	/** Four logs around a furnace. Cooks food twice as fast as a furnace. */
	public static final CraftRecipe SMOKER = new CraftRecipe(
			Items.SMOKER, 1,
			List.of(Need.ofTag(ItemTags.LOGS, 4), Need.of(Items.FURNACE, 1)), true);

	/** Three wheat. The cheapest food in the game if a hay bale is nearby. */
	public static final CraftRecipe BREAD = new CraftRecipe(
			Items.BREAD, 1, List.of(Need.of(Items.WHEAT, 3)), true);

	/** One hay bale unpacks into nine wheat, which is three loaves. */
	public static final CraftRecipe WHEAT_FROM_HAY = new CraftRecipe(
			Items.WHEAT, 9, List.of(Need.of(Items.HAY_BLOCK, 1)), false);

	/**
	 * One iron and one flint.
	 *
	 * <p>Worth more than it looks. Setting an animal alight before killing it
	 * makes it drop its meat already cooked, which skips the furnace entirely.
	 */
	public static final CraftRecipe FLINT_AND_STEEL = new CraftRecipe(
			Items.FLINT_AND_STEEL, 1,
			List.of(Need.of(Items.IRON_INGOT, 1), Need.of(Items.FLINT, 1)), false);

	/** Anything the hunter is willing to eat, best first. */
	public static final List<Item> FOOD = List.of(
			Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_MUTTON,
			Items.COOKED_CHICKEN, Items.BREAD, Items.COOKED_COD, Items.COOKED_SALMON,
			Items.BAKED_POTATO, Items.CARROT, Items.APPLE, Items.SWEET_BERRIES,
			Items.BEEF, Items.PORKCHOP, Items.MUTTON);

	/**
	 * The food worth counting as a meal.
	 *
	 * <p>Raw meat is edible, and {@link #FOOD} keeps it so a starving hunter
	 * will still eat it rather than die holding it. It does not count towards
	 * the stock the hunter is trying to build, though: raw beef restores three
	 * where cooked restores eight, so four raw steaks looked like a full larder
	 * and quietly cancelled the trip to the furnace.
	 */
	public static final List<Item> PREPARED_FOOD = List.of(
			Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_MUTTON,
			Items.COOKED_CHICKEN, Items.BREAD, Items.COOKED_COD, Items.COOKED_SALMON,
			Items.BAKED_POTATO, Items.CARROT, Items.APPLE, Items.SWEET_BERRIES);

	/** How much health one of these is worth, using its real nutrition value. */
	public static int nutrition(net.minecraft.world.item.ItemStack stack) {
		net.minecraft.world.food.FoodProperties food =
				stack.get(net.minecraft.core.component.DataComponents.FOOD);
		return food == null ? 0 : food.nutrition();
	}

	/** Raw meat worth putting in a furnace before eating. */
	public static final List<Item> RAW_FOOD = List.of(
			Items.BEEF, Items.PORKCHOP, Items.MUTTON, Items.CHICKEN,
			Items.COD, Items.SALMON, Items.POTATO);

	private Recipes() {
	}

	/** What a furnace turns each input into. Ores and meat both go in one. */
	public static Item smeltResult(Item input) {
		if (input == Items.RAW_IRON || input == Items.IRON_ORE || input == Items.DEEPSLATE_IRON_ORE) {
			return Items.IRON_INGOT;
		}
		if (input == Items.RAW_GOLD || input == Items.GOLD_ORE) {
			return Items.GOLD_INGOT;
		}
		if (input == Items.BEEF) {
			return Items.COOKED_BEEF;
		}
		if (input == Items.PORKCHOP) {
			return Items.COOKED_PORKCHOP;
		}
		if (input == Items.MUTTON) {
			return Items.COOKED_MUTTON;
		}
		if (input == Items.CHICKEN) {
			return Items.COOKED_CHICKEN;
		}
		if (input == Items.POTATO) {
			return Items.BAKED_POTATO;
		}
		if (input == Items.COD) {
			return Items.COOKED_COD;
		}
		if (input == Items.SALMON) {
			return Items.COOKED_SALMON;
		}
		return null;
	}

	/** The best thing the hunter is carrying to eat, or null if it has nothing. */
	public static Item bestFood(HunterInventory carrying) {
		for (Item food : FOOD) {
			if (carrying.has(food, 1)) {
				return food;
			}
		}
		return null;
	}

	/** Raw meat the hunter is carrying that a furnace would improve. */
	public static Item rawFoodOnHand(HunterInventory carrying) {
		for (Item raw : RAW_FOOD) {
			if (carrying.has(raw, 1)) {
				return raw;
			}
		}
		return null;
	}

	/** Items the hunter is willing to burn, and how many smelts each covers. */
	public static int fuelValue(Item item) {
		if (item == Items.COAL || item == Items.CHARCOAL) {
			return 8;
		}
		if (item == Items.OAK_PLANKS) {
			return 1;
		}
		if (item == Items.STICK) {
			return 1;
		}
		return 0;
	}
}
