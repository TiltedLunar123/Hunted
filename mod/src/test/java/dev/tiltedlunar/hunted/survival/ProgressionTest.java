package dev.tiltedlunar.hunted.survival;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the inventory arithmetic and the item based recipes.
 *
 * <p>Tag based recipes (anything taking {@code #planks} or {@code #logs}) are
 * not covered here. Tags are loaded from the data pack when a world starts, so
 * they are empty in a bare bootstrap and any assertion about them would be
 * testing the harness rather than the code.
 */
class ProgressionTest {

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	@DisplayName("consuming more than is held changes nothing")
	void consumeIsAllOrNothing() {
		HunterInventory carrying = new HunterInventory();
		carrying.add(Items.IRON_INGOT, 3);

		assertFalse(carrying.consume(Items.IRON_INGOT, 4), "should refuse to overdraw");
		assertEquals(3, carrying.count(Items.IRON_INGOT), "a refused consume must not take any");

		assertTrue(carrying.consume(Items.IRON_INGOT, 3));
		assertEquals(0, carrying.count(Items.IRON_INGOT));
	}

	@Test
	@DisplayName("an iron sword costs exactly two ingots and one stick")
	void ironSwordCosts() {
		HunterInventory carrying = new HunterInventory();
		carrying.add(Items.IRON_INGOT, 2);
		carrying.add(Items.STICK, 1);

		assertTrue(Recipes.IRON_SWORD.canCraft(carrying));
		assertTrue(Recipes.IRON_SWORD.craft(carrying));

		assertEquals(1, carrying.count(Items.IRON_SWORD));
		assertEquals(0, carrying.count(Items.IRON_INGOT), "ingots should be spent");
		assertEquals(0, carrying.count(Items.STICK), "the stick should be spent");
	}

	@Test
	@DisplayName("a craft one ingredient short spends nothing")
	void shortCraftSpendsNothing() {
		HunterInventory carrying = new HunterInventory();
		carrying.add(Items.IRON_INGOT, 2);
		// No stick.

		assertFalse(Recipes.IRON_SWORD.canCraft(carrying));
		assertFalse(Recipes.IRON_SWORD.craft(carrying));
		assertEquals(2, carrying.count(Items.IRON_INGOT),
				"a failed craft must leave the inventory untouched");
	}

	@Test
	@DisplayName("iron armour costs match the game's own recipe data")
	void armourCosts() {
		assertEquals(5, ironCost(Recipes.IRON_HELMET), "helmet");
		assertEquals(8, ironCost(Recipes.IRON_CHESTPLATE), "chestplate");
		assertEquals(7, ironCost(Recipes.IRON_LEGGINGS), "leggings");
		assertEquals(4, ironCost(Recipes.IRON_BOOTS), "boots");
		assertEquals(2, ironCost(Recipes.IRON_SWORD), "sword");
		assertEquals(3, ironCost(Recipes.IRON_PICKAXE), "pickaxe");
		assertEquals(1, ironCost(Recipes.SHIELD), "shield");
	}

	@Test
	@DisplayName("the full iron kit fits inside the ladder's iron target")
	void kitFitsIronBudget() {
		// Progression buys sword, shield, chestplate and helmet before it
		// declares itself ready. If that total ever exceeds what it gathers,
		// the hunter would mine forever without finishing.
		int needed = ironCost(Recipes.IRON_SWORD)
				+ ironCost(Recipes.SHIELD)
				+ ironCost(Recipes.IRON_CHESTPLATE)
				+ ironCost(Recipes.IRON_HELMET);
		assertEquals(16, needed, "the gear list should exactly consume the iron target");
	}

	@Test
	@DisplayName("smelting maps ore to the right ingot")
	void smeltingMapping() {
		assertEquals(Items.IRON_INGOT, Recipes.smeltResult(Items.RAW_IRON));
		assertEquals(Items.IRON_INGOT, Recipes.smeltResult(Items.IRON_ORE));
		assertEquals(Items.IRON_INGOT, Recipes.smeltResult(Items.DEEPSLATE_IRON_ORE));
		assertEquals(null, Recipes.smeltResult(Items.COBBLESTONE), "stone does not smelt to metal");
	}

	@Test
	@DisplayName("a bucket costs three iron, matching the game's recipe")
	void bucketCosts() {
		assertEquals(3, ironCost(Recipes.BUCKET));
	}

	@Test
	@DisplayName("a hay bale unpacks into nine wheat, which is three loaves")
	void hayBaleFeedsIt() {
		HunterInventory carrying = new HunterInventory();
		carrying.add(Items.HAY_BLOCK, 1);

		assertTrue(Recipes.WHEAT_FROM_HAY.craft(carrying));
		assertEquals(9, carrying.count(Items.WHEAT));
		assertEquals(0, carrying.count(Items.HAY_BLOCK));

		// Three loaves out of the nine wheat, with nothing left over.
		for (int loaf = 0; loaf < 3; loaf++) {
			assertTrue(Recipes.BREAD.craft(carrying), "loaf " + (loaf + 1));
		}
		assertEquals(3, carrying.count(Items.BREAD));
		assertEquals(0, carrying.count(Items.WHEAT));
		assertFalse(Recipes.BREAD.craft(carrying), "there is no fourth loaf in a hay bale");
	}

	@Test
	@DisplayName("burning an animal skips the furnace")
	void fireCooksMeatDirectly() {
		// The point of carrying flint and steel: raw meat needs smelting, and
		// meat off a burning animal does not.
		assertEquals(Items.COOKED_BEEF, Recipes.smeltResult(Items.BEEF));
		assertEquals(Items.COOKED_PORKCHOP, Recipes.smeltResult(Items.PORKCHOP));
		assertTrue(Recipes.FOOD.indexOf(Items.COOKED_BEEF) < Recipes.FOOD.indexOf(Items.BEEF),
				"cooked meat should be preferred over raw");
	}

	@Test
	@DisplayName("the looter takes what it needs and leaves the rest")
	void looterIsSelective() {
		assertTrue(Looter.wants(Items.IRON_INGOT));
		assertTrue(Looter.wants(Items.COOKED_BEEF));
		assertTrue(Looter.wants(Items.DIAMOND_SWORD));
		assertTrue(Looter.wants(Items.HAY_BLOCK));
		assertTrue(Looter.wants(Items.WATER_BUCKET));

		assertFalse(Looter.wants(Items.POPPY), "flowers are not equipment");
		assertFalse(Looter.wants(Items.FEATHER), "feathers are not worth the space");
	}

	@Test
	@DisplayName("coal is worth more furnace time than a plank")
	void fuelOrdering() {
		assertTrue(Recipes.fuelValue(Items.COAL) > Recipes.fuelValue(Items.OAK_PLANKS));
		assertEquals(0, Recipes.fuelValue(Items.IRON_INGOT), "metal is not fuel");
	}

	@Test
	@DisplayName("raw meat is edible but does not count as a stocked meal")
	void rawMeatIsNotAMeal() {
		HunterInventory carrying = new HunterInventory();
		carrying.add(Items.BEEF, 8);

		assertEquals(0, Progression.mealsOnHand(carrying),
				"eight raw steaks are not a full larder");
		assertTrue(Recipes.FOOD.contains(Items.BEEF),
				"it should still eat raw beef rather than starve holding it");

		carrying.add(Items.COOKED_BEEF, 2);
		assertEquals(2, Progression.mealsOnHand(carrying), "only the cooked ones count");
	}

	@Test
	@DisplayName("fish cooks, so looted salmon is not left to rot")
	void fishCooks() {
		assertEquals(Items.COOKED_COD, Recipes.smeltResult(Items.COD));
		assertEquals(Items.COOKED_SALMON, Recipes.smeltResult(Items.SALMON));
	}

	@Test
	@DisplayName("the bucket rungs are skipped where water evaporates")
	void netherSkipsTheBucket() {
		HunterInventory empty = new HunterInventory();

		assertEquals(new Progression.Task.Craft(Recipes.BUCKET),
				Progression.bucketStep(empty, true),
				"in the overworld it makes the bucket first");
		assertNull(Progression.bucketStep(empty, false),
				"a bucket of water is worth nothing where water evaporates");

		HunterInventory withBucket = new HunterInventory();
		withBucket.add(Items.BUCKET, 1);
		assertTrue(Progression.bucketStep(withBucket, true) instanceof Progression.Task.FetchWater,
				"an empty bucket means going to find water");
		assertNull(Progression.bucketStep(withBucket, false),
				"and it must never go looking for water it can never carry");

		HunterInventory full = new HunterInventory();
		full.add(Items.WATER_BUCKET, 1);
		assertNull(Progression.bucketStep(full, true), "a full bucket ends the errand");
	}

	@Test
	@DisplayName("spare iron becomes the rest of the set rather than sitting there")
	void spareIronIsUsed() {
		HunterInventory carrying = new HunterInventory();
		carrying.add(Items.IRON_INGOT, 7);
		carrying.add(Items.STICK, 4);

		assertEquals(new Progression.Task.Craft(Recipes.IRON_LEGGINGS),
				Progression.spare(carrying), "seven spare ingots is a pair of leggings");
	}

	@Test
	@DisplayName("diamonds it stumbled on get used, and outrank leftover iron")
	void diamondsAreUsed() {
		HunterInventory carrying = new HunterInventory();
		carrying.add(Items.IRON_INGOT, 7);
		carrying.add(Items.DIAMOND, 2);
		carrying.add(Items.STICK, 4);

		assertEquals(new Progression.Task.Craft(Recipes.DIAMOND_SWORD),
				Progression.spare(carrying), "two diamonds is a better sword than any iron");
	}

	@Test
	@DisplayName("with nothing spare there is nothing left to do but hunt")
	void nothingSpareMeansHunt() {
		assertTrue(Progression.spare(new HunterInventory()) instanceof Progression.Task.Hunt,
				"an empty pack is the end of the ladder");
	}

	@Test
	@DisplayName("leftover flint and iron becomes a way to cook dinner where it stands")
	void flintAndSteelFromLeftovers() {
		HunterInventory carrying = new HunterInventory();
		carrying.add(Items.FLINT, 1);
		carrying.add(Items.IRON_INGOT, 1);

		assertEquals(new Progression.Task.Craft(Recipes.FLINT_AND_STEEL),
				Progression.spare(carrying),
				"one flint and one ingot is the cheapest cooked meat in the game");
	}

	private static int ironCost(CraftRecipe recipe) {
		return recipe.needs().stream()
				.filter(need -> need.item() == Items.IRON_INGOT)
				.mapToInt(CraftRecipe.Need::count)
				.sum();
	}
}
