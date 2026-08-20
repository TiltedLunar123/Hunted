package dev.tiltedlunar.hunted.survival;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Which tool a block actually needs.
 *
 * <p>Breaking a block and <em>harvesting</em> it are different questions, and
 * confusing them produces a very specific, very stupid failure: the hunter
 * spends nine seconds punching an iron ore with a wooden pickaxe, watches it
 * vanish into nothing, and then starts on the next one. It would do that
 * forever, because its inventory never changes and the ladder never advances.
 *
 * <p>So the gathering code asks this first. Pathing does not, because for
 * getting through a wall it genuinely does not matter whether anything drops.
 */
public final class ToolLore {

	private ToolLore() {
	}

	/**
	 * Whether mining this block with this tool actually yields the item.
	 *
	 * <p>Delegates to the game rather than hard coding a table, so modded ores
	 * and any future retuning of vanilla harvest levels are handled without this
	 * class knowing about them.
	 */
	public static boolean canHarvest(Level level, BlockPos pos, ItemStack tool) {
		BlockState state = level.getBlockState(pos);
		if (state.isAir()) {
			return false;
		}
		if (state.getDestroySpeed(level, pos) < 0.0f) {
			return false;
		}
		return !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
	}

	/**
	 * The best pickaxe the hunter is carrying, or null if it has none.
	 *
	 * <p>Ordered by harvest level, because that is what decides whether the ore
	 * drops at all. Speed is a distant second consideration.
	 */
	public static net.minecraft.world.item.Item bestPickaxe(HunterInventory carrying) {
		return firstHeld(carrying, Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE,
				Items.IRON_PICKAXE, Items.STONE_PICKAXE, Items.GOLDEN_PICKAXE,
				Items.WOODEN_PICKAXE);
	}

	/** The best axe it has, for wood. */
	public static net.minecraft.world.item.Item bestAxe(HunterInventory carrying) {
		return firstHeld(carrying, Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE,
				Items.STONE_AXE, Items.GOLDEN_AXE, Items.WOODEN_AXE);
	}

	/** The best shovel it has, for dirt and gravel. */
	public static net.minecraft.world.item.Item bestShovel(HunterInventory carrying) {
		return firstHeld(carrying, Items.NETHERITE_SHOVEL, Items.DIAMOND_SHOVEL,
				Items.IRON_SHOVEL, Items.STONE_SHOVEL, Items.WOODEN_SHOVEL);
	}

	/** The hardest hitting weapon it has. */
	public static net.minecraft.world.item.Item bestWeapon(HunterInventory carrying) {
		return firstHeld(carrying, Items.NETHERITE_SWORD, Items.DIAMOND_SWORD,
				Items.IRON_SWORD, Items.STONE_SWORD, Items.NETHERITE_AXE, Items.DIAMOND_AXE,
				Items.IRON_AXE, Items.STONE_AXE, Items.GOLDEN_SWORD, Items.WOODEN_SWORD,
				Items.GOLDEN_AXE, Items.WOODEN_AXE, Items.NETHERITE_PICKAXE,
				Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE, Items.STONE_PICKAXE,
				Items.WOODEN_PICKAXE);
	}

	/**
	 * The best armour it has for one slot.
	 *
	 * <p>Ordered by protection rather than by material rarity, which is why gold
	 * sits below chain: gold armour looks valuable and protects like leather.
	 */
	public static net.minecraft.world.item.Item bestArmour(HunterInventory carrying,
			net.minecraft.world.entity.EquipmentSlot slot) {
		return switch (slot) {
			case HEAD -> firstHeld(carrying, Items.NETHERITE_HELMET, Items.DIAMOND_HELMET,
					Items.IRON_HELMET, Items.CHAINMAIL_HELMET, Items.GOLDEN_HELMET,
					Items.LEATHER_HELMET, Items.TURTLE_HELMET);
			case CHEST -> firstHeld(carrying, Items.NETHERITE_CHESTPLATE,
					Items.DIAMOND_CHESTPLATE, Items.IRON_CHESTPLATE,
					Items.CHAINMAIL_CHESTPLATE, Items.GOLDEN_CHESTPLATE,
					Items.LEATHER_CHESTPLATE);
			case LEGS -> firstHeld(carrying, Items.NETHERITE_LEGGINGS, Items.DIAMOND_LEGGINGS,
					Items.IRON_LEGGINGS, Items.CHAINMAIL_LEGGINGS, Items.GOLDEN_LEGGINGS,
					Items.LEATHER_LEGGINGS);
			case FEET -> firstHeld(carrying, Items.NETHERITE_BOOTS, Items.DIAMOND_BOOTS,
					Items.IRON_BOOTS, Items.CHAINMAIL_BOOTS, Items.GOLDEN_BOOTS,
					Items.LEATHER_BOOTS);
			default -> null;
		};
	}

	/**
	 * The best axe to swing at a shield, or null if it has none.
	 *
	 * <p>Separate from {@link #bestAxe} only in intent. Deciding to make an axe,
	 * making it, and then walking over with a sword still in hand is a complete
	 * waste of the detour, so the combat code asks for this by name.
	 */
	public static net.minecraft.world.item.Item shieldBreaker(HunterInventory carrying) {
		return bestAxe(carrying);
	}

	private static net.minecraft.world.item.Item firstHeld(HunterInventory carrying,
			net.minecraft.world.item.Item... preference) {
		for (net.minecraft.world.item.Item item : preference) {
			if (carrying.has(item, 1)) {
				return item;
			}
		}
		return null;
	}
}
