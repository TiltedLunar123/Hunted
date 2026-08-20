package dev.tiltedlunar.hunted.survival;

import java.util.List;

import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * One thing the hunter knows how to make.
 *
 * <p>Every entry in {@link Recipes} was read out of the game's own recipe data
 * rather than written from memory, because the counts are exactly the sort of
 * detail that quietly changes between versions and produces a bot that stands
 * in a forest forever waiting for a fourth plank it does not need.
 *
 * @param result     what comes out
 * @param yield      how many come out per craft
 * @param needs      what goes in
 * @param needsTable true when the pattern is wider than the two by two grid
 *                   every mob has access to without a crafting table
 */
public record CraftRecipe(Item result, int yield, List<Need> needs, boolean needsTable) {

	/**
	 * One input. Either a specific item or any item carrying a tag, never both.
	 *
	 * @param item  the exact item required, or null when {@code tag} is set
	 * @param tag   the tag any of whose items will do, or null when {@code item} is set
	 * @param count how many are consumed
	 */
	public record Need(Item item, TagKey<Item> tag, int count) {
		public static Need of(Item item, int count) {
			return new Need(item, null, count);
		}

		public static Need ofTag(TagKey<Item> tag, int count) {
			return new Need(null, tag, count);
		}

		public boolean satisfiedBy(HunterInventory inventory) {
			return tag != null
					? inventory.hasTag(tag, count)
					: inventory.has(item, count);
		}

		public boolean take(HunterInventory inventory) {
			return tag != null
					? inventory.consumeTag(tag, count)
					: inventory.consume(item, count);
		}
	}

	/** Whether every input is currently in hand. */
	public boolean canCraft(HunterInventory inventory) {
		for (Need need : needs) {
			if (!need.satisfiedBy(inventory)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Consumes the inputs and adds the output.
	 *
	 * @return false if the inputs were not all present, in which case nothing
	 *         was consumed
	 */
	public boolean craft(HunterInventory inventory) {
		if (!canCraft(inventory)) {
			return false;
		}
		for (Need need : needs) {
			need.take(inventory);
		}
		inventory.add(result, yield);
		return true;
	}
}
