package dev.tiltedlunar.hunted.survival;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Finding chests and helping itself to what is in them.
 *
 * <p>Looting is how a real player skips the early game. Someone who finds a
 * village chest does not then go and chop six trees, and a hunter that ignored a
 * barrel full of iron while standing next to it would look exactly as stupid as
 * that sounds.
 *
 * <p>It only takes things it has a use for, which is the current rung of
 * {@link Progression} plus food, fuel and anything better than what it is
 * holding. Leaving the flower pots behind is not politeness, it is that carrying
 * junk would make the inventory checks lie about what it has.
 *
 * <p>Positions it has already emptied are remembered, because a hunter that
 * re-opens the same empty chest forever is a hunter that never comes for you.
 */
public final class Looter {

	/** Chest-like blocks worth opening. */
	private static final Set<net.minecraft.world.level.block.Block> CONTAINERS = Set.of(
			Blocks.CHEST,
			Blocks.TRAPPED_CHEST,
			Blocks.BARREL,
			Blocks.SHULKER_BOX,
			Blocks.DISPENSER,
			Blocks.DROPPER,
			Blocks.HOPPER);

	/** How close the hunter has to be to reach inside. */
	private static final double REACH = 4.5D;

	/** Stop bothering after this many, so it does not become a looting simulator. */
	private static final int MEMORY_LIMIT = 64;

	private final ResourceScanner scanner = new ResourceScanner();
	private final Set<Long> emptied = new HashSet<>();

	/** Looks for the nearest container it has not already been through. */
	public BlockPos find(Level level, BlockPos from, int budget) {
		BlockPos found = scanner.scanForContainer(level, from, CONTAINERS, emptied, budget);
		if (found == null && scanner.exhausted()) {
			scanner.clear();
		}
		return found;
	}

	/** Whether the hunter is close enough to reach into this container. */
	public boolean inReach(net.minecraft.world.entity.Entity hunter, BlockPos at) {
		return hunter.distanceToSqr(at.getX() + 0.5D, at.getY() + 0.5D, at.getZ() + 0.5D)
				<= REACH * REACH;
	}

	/**
	 * Empties the useful part of a container into the hunter's pack.
	 *
	 * @return how many item stacks were taken
	 */
	public int take(Level level, BlockPos at, HunterInventory carrying) {
		remember(at);

		BlockEntity entity = level.getBlockEntity(at);
		if (!(entity instanceof Container container)) {
			return 0;
		}

		int taken = 0;
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack stack = container.getItem(slot);
			if (stack.isEmpty() || !wants(stack.getItem())) {
				continue;
			}
			carrying.add(stack.copy());
			container.removeItemNoUpdate(slot);
			taken++;
		}

		if (taken > 0) {
			container.setChanged();
			level.playSound(null, at, SoundEvents.CHEST_OPEN,
					net.minecraft.sounds.SoundSource.BLOCKS, 0.7f, 1.0f);
		}
		return taken;
	}

	/**
	 * Whether an item is worth the space.
	 *
	 * <p>Anything on the ladder, anything edible, anything burnable, and any
	 * tool or armour. Everything else stays in the chest.
	 */
	public static boolean wants(Item item) {
		if (Recipes.FOOD.contains(item) || Recipes.RAW_FOOD.contains(item)) {
			return true;
		}
		if (Recipes.fuelValue(item) > 0) {
			return true;
		}
		if (item == Items.IRON_INGOT || item == Items.RAW_IRON || item == Items.DIAMOND
				|| item == Items.GOLD_INGOT || item == Items.STICK
				|| item == Items.BUCKET || item == Items.WATER_BUCKET
				|| item == Items.SHIELD || item == Items.CRAFTING_TABLE
				|| item == Items.FURNACE || item == Items.SMOKER
				|| item == Items.FLINT || item == Items.FLINT_AND_STEEL
				|| item == Items.HAY_BLOCK || item == Items.WHEAT) {
			return true;
		}

		String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath();
		return path.endsWith("_sword") || path.endsWith("_axe") || path.endsWith("_pickaxe")
				|| path.endsWith("_helmet") || path.endsWith("_chestplate")
				|| path.endsWith("_leggings") || path.endsWith("_boots")
				|| path.endsWith("_planks") || path.endsWith("_log")
				|| path.equals("cobblestone") || path.equals("dirt");
	}

	/** Marks a container as done with. */
	/**
	 * Forgets every container it has written off.
	 *
	 * <p>Only for the watchdog. A hunter that has stopped making progress may
	 * have skipped the one chest that would have unstuck it, and a second look
	 * costs nothing next to standing still.
	 */
	public void forgetAll() {
		emptied.clear();
	}

	public void remember(BlockPos at) {
		if (emptied.size() > MEMORY_LIMIT) {
			emptied.clear();
		}
		emptied.add(at.asLong());
		scanner.clear();
	}

	public void reset() {
		emptied.clear();
		scanner.clear();
	}
}
