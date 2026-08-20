package dev.tiltedlunar.hunted.survival;

import java.util.List;

import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.AABB;

import dev.tiltedlunar.hunted.hunter.HunterEntity;
import dev.tiltedlunar.hunted.path.LevelWorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * Runs the hunter's economy: gather, craft, smelt, equip.
 *
 * <p>Survival mode is the version of this mod that people actually talk about.
 * The hunter spawns with nothing, and everything it eventually kills you with,
 * it had to go and get. Watching it chop its first tree while you sprint away
 * is a very different feeling from watching a fully geared mob spawn behind
 * you, and it earns the tension the rest of the mod is trading on.
 *
 * <p>Priorities are simple and deliberately legible: if the quarry is close
 * enough to fight, fight. Otherwise, climb one rung of {@link Progression}.
 */
public final class SurvivalBrain {

	/** How close a block has to be before the hunter can work on it. */
	private static final double REACH = 4.5D;

	/** Ticks a craft takes. Long enough to read on screen, short enough to matter. */
	private static final int CRAFT_TICKS = 25;

	/** Ticks to smelt one item, matching a vanilla furnace. */
	private static final int SMELT_TICKS = 200;

	/** Drop everything and fight once the quarry is this close. */
	private static final double INTERRUPT_RANGE = 12.0D;

	/** How far to look for something edible on the hoof. */
	private static final double FOOD_SEARCH_RANGE = 24.0D;

	/** Ticks between swings while butchering, matching the attack cooldown. */
	private static final int BUTCHER_INTERVAL = 13;

	/** What the brain wants the rest of the entity to do this tick. */
	public record Directive(boolean busy, BlockPos goal) {
		static final Directive HUNT = new Directive(false, null);

		static Directive working() {
			return new Directive(true, null);
		}

		static Directive travel(BlockPos to) {
			return new Directive(true, to);
		}
	}

	private final HunterInventory carrying = new HunterInventory();
	private final ResourceScanner scanner = new ResourceScanner();
	private final Looter looter = new Looter();

	private Progression.Focus focus = Progression.Focus.NONE;
	private BlockPos worksite;
	private float mineProgress;
	private float mineRequired;
	private int craftTicks;
	private int smeltTicks;
	private int smeltRemaining;
	private int butcherTicks;

	public HunterInventory carrying() {
		return carrying;
	}

	/** A label for the status command. */
	public String describe() {
		return Progression.describe(Progression.next(carrying, focus));
	}

	/**
	 * One tick of economy.
	 *
	 * @param distanceToQuarry how far the target is, so a close player can
	 *                         interrupt whatever the hunter is doing
	 */
	public Directive tick(HunterEntity hunter, ServerLevel level, double distanceToQuarry,
			Progression.Focus focus) {
		if (distanceToQuarry < INTERRUPT_RANGE) {
			abandonWork(level, hunter);
			return Directive.HUNT;
		}

		this.focus = focus;

		// Eating comes before everything, because a hunter that heals does not
		// have to start the whole ladder again after dying.
		if (hunter.tryEat()) {
			return Directive.working();
		}

		// A chest within reach beats any amount of chopping, so it is checked
		// before the ladder rather than as a rung of it.
		Directive looting = loot(hunter, level);
		if (looting != null) {
			return looting;
		}

		Progression.Task task = Progression.next(carrying, focus,
				dev.tiltedlunar.hunted.hunter.Clutch.waterWorksAt(level, hunter.blockPosition()));
		return switch (task) {
			case Progression.Task.Hunt ignored -> Directive.HUNT;
			case Progression.Task.Craft craft -> craft(hunter, level, craft.recipe());
			case Progression.Task.Smelt smelt -> smelt(hunter, level, smelt);
			case Progression.Task.Gather gather -> gather(hunter, level, gather);
			case Progression.Task.GatherBlock gather -> gatherBlock(hunter, level, gather);
			case Progression.Task.FetchWater ignored -> fetchWater(hunter, level);
			case Progression.Task.HuntFood ignored -> huntFood(hunter, level);
		};
	}

	// -----------------------------------------------------------------
	// Gathering
	// -----------------------------------------------------------------

	private Directive gather(HunterEntity hunter, ServerLevel level,
			Progression.Task.Gather task) {
		BlockPos target = scanner.scan(level, hunter.blockPosition(), task.target(), 2_500);

		if (target == null) {
			if (!scanner.exhausted()) {
				return Directive.working();
			}
			// Nothing in sight. Ore lives at a known depth, so go there;
			// anything else means walking until the scenery changes.
			scanner.clear();
			return Directive.travel(task.preferredY() == Integer.MIN_VALUE
					? wander(hunter)
					: new BlockPos(hunter.getBlockX(), task.preferredY(), hunter.getBlockZ()));
		}

		if (hunter.distanceToSqr(Vec3.atCenterOf(target)) > REACH * REACH) {
			return Directive.travel(standingSpotFor(level, target));
		}

		return mine(hunter, level, target, task);
	}

	/**
	 * Looks for one particular block, and gives up quickly if there is none.
	 *
	 * <p>Unlike {@link #gather}, running out is not a reason to go wandering.
	 * There is no hay bale over the next hill unless there is a farm over the
	 * next hill, so a failed sweep falls through to the other way of getting
	 * the same thing rather than walking in hope.
	 */
	private Directive gatherBlock(HunterEntity hunter, ServerLevel level,
			Progression.Task.GatherBlock task) {
		BlockPos target = scanner.scanForBlock(level, hunter.blockPosition(), task.target(), 2_500);

		if (target == null) {
			if (!scanner.exhausted()) {
				return Directive.working();
			}
			scanner.clear();
			return switch (task.orElse()) {
				case Progression.Task.HuntFood ignored -> huntFood(hunter, level);
				case Progression.Task.Craft craft -> craft(hunter, level, craft.recipe());
				default -> Directive.HUNT;
			};
		}

		if (hunter.distanceToSqr(Vec3.atCenterOf(target)) > REACH * REACH) {
			return Directive.travel(standingSpotFor(level, target));
		}

		BlockState state = level.getBlockState(target);
		if (!state.is(task.target())) {
			resetSite(level, hunter);
			return Directive.working();
		}
		equipBestToolFor(hunter, state);
		return breakBlock(hunter, level, target, state);
	}

	/** Chews through one block, then banks whatever it dropped. */
	private Directive mine(HunterEntity hunter, ServerLevel level, BlockPos target,
			Progression.Task.Gather task) {
		BlockState state = level.getBlockState(target);
		if (!state.is(task.target())) {
			resetSite(level, hunter);
			return Directive.working();
		}

		equipBestToolFor(hunter, state);
		return breakBlock(hunter, level, target, state);
	}

	/**
	 * Chews through the block in front of it, one tick at a time.
	 *
	 * <p>Assumes the caller has already picked the tool and confirmed this is
	 * the right block. Shared by every kind of gathering, so a hay bale and an
	 * iron ore break with the same timing rules.
	 */
	private Directive breakBlock(HunterEntity hunter, ServerLevel level, BlockPos target,
			BlockState state) {
		// Punching iron ore with a wooden pickaxe destroys it and yields
		// nothing, so the ladder would never advance. Skip anything the
		// current tool cannot actually harvest and look elsewhere.
		if (!ToolLore.canHarvest(level, target, hunter.getMainHandItem())) {
			looter.remember(target);
			scanner.clear();
			return Directive.working();
		}

		if (!target.equals(worksite)) {
			resetSite(level, hunter);
			worksite = target.immutable();
			float ticks = LevelWorldView.breakTicks(level, target, hunter.getMainHandItem());
			if (!Float.isFinite(ticks)) {
				scanner.clear();
				return Directive.working();
			}
			mineRequired = Math.max(1.0f,
					(float) (ticks / Math.max(0.05D, hunter.tier().miningSpeed())));
			mineProgress = 0.0f;
		}

		hunter.getLookControl().setLookAt(Vec3.atCenterOf(target));
		hunter.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
		hunter.zza = 0.0f;

		mineProgress++;
		level.destroyBlockProgress(hunter.getId(), worksite,
				(int) Math.min(9.0f, mineProgress / mineRequired * 10.0f));

		if (mineProgress >= mineRequired) {
			collect(hunter, level, target, state);
			resetSite(level, hunter);
			scanner.clear();
		}
		return Directive.working();
	}

	/** Uses the real loot tables, so silk touch style edge cases behave. */
	private void collect(HunterEntity hunter, ServerLevel level, BlockPos pos, BlockState state) {
		List<ItemStack> drops = Block.getDrops(state, level, pos,
				level.getBlockEntity(pos), hunter, hunter.getMainHandItem());
		for (ItemStack drop : drops) {
			carrying.add(drop);
		}
		level.destroyBlock(pos, false, hunter);
		hunter.onBrokeBlock(pos);
	}

	// -----------------------------------------------------------------
	// Water, food and chests
	// -----------------------------------------------------------------

	/** Walks to a water source and fills the bucket. */
	private Directive fetchWater(HunterEntity hunter, ServerLevel level) {
		if (!carrying.has(Items.BUCKET, 1)) {
			return Directive.working();
		}

		BlockPos water = scanner.scanForWater(level, hunter.blockPosition(), 2_500);
		if (water == null) {
			if (!scanner.exhausted()) {
				return Directive.working();
			}
			scanner.clear();
			return Directive.travel(wander(hunter));
		}

		if (hunter.distanceToSqr(Vec3.atCenterOf(water)) > REACH * REACH) {
			return Directive.travel(standingSpotFor(level, water));
		}

		hunter.getLookControl().setLookAt(Vec3.atCenterOf(water));
		if (carrying.consume(Items.BUCKET, 1)) {
			// Take the source with it, the way filling a bucket does. An ocean
			// refills the hole from its neighbours within a tick; a one block
			// puddle is used up, which is correct and is what the clutch code
			// already does when it picks its own water back up.
			//
			// Unless terrain edits are switched off, in which case the bucket
			// still fills but the world is left exactly as it was. A server
			// that said mobs may not touch its builds meant this too.
			if (hunter.canModifyTerrain()) {
				level.setBlockAndUpdate(water, net.minecraft.world.level.block.Blocks.AIR
						.defaultBlockState());
			}
			carrying.add(Items.WATER_BUCKET, 1);
			level.playSound(null, water, SoundEvents.BUCKET_FILL,
					hunter.getSoundSource(), 1.0f, 1.0f);
		}
		scanner.clear();
		return Directive.working();
	}

	/**
	 * Finds an animal and turns it into a meal.
	 *
	 * <p>With flint and steel in hand it lights the animal first, because a
	 * creature that dies on fire drops its meat already cooked. That skips the
	 * furnace, the fuel and the wait, and it is the same trick a speedrunner
	 * uses for the same reason.
	 */
	private Directive huntFood(HunterEntity hunter, ServerLevel level) {
		AABB search = hunter.getBoundingBox().inflate(FOOD_SEARCH_RANGE);
		Animal prey = level.getEntitiesOfClass(Animal.class, search,
						animal -> animal.isAlive() && !animal.isBaby()).stream()
				.min((a, b) -> Double.compare(hunter.distanceToSqr(a), hunter.distanceToSqr(b)))
				.orElse(null);

		if (prey == null) {
			return Directive.travel(wander(hunter));
		}

		if (hunter.distanceToSqr(prey) > REACH * REACH) {
			return Directive.travel(prey.blockPosition());
		}

		hunter.getLookControl().setLookAt(prey, 30.0f, 30.0f);
		hunter.zza = 0.0f;

		if (carrying.has(Items.FLINT_AND_STEEL, 1) && prey.getRemainingFireTicks() <= 0) {
			prey.igniteForTicks(100);
			level.playSound(null, prey.blockPosition(), SoundEvents.FLINTANDSTEEL_USE,
					hunter.getSoundSource(), 1.0f, 1.0f);
			return Directive.working();
		}

		if (butcherTicks++ < BUTCHER_INTERVAL) {
			return Directive.working();
		}
		butcherTicks = 0;

		boolean cooked = prey.getRemainingFireTicks() > 0;
		hunter.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
		hunter.doHurtTarget(level, prey);

		if (!prey.isAlive()) {
			Item meat = meatFrom(prey, cooked);
			if (meat != null) {
				carrying.add(meat, 2);
			}
		}
		return Directive.working();
	}

	/** What this animal is worth, raw or already cooked. */
	private Item meatFrom(Animal prey, boolean cooked) {
		String kind = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
				.getKey(prey.getType()).getPath();
		switch (kind) {
			case "cow":
			case "mooshroom":
				return cooked ? Items.COOKED_BEEF : Items.BEEF;
			case "pig":
				return cooked ? Items.COOKED_PORKCHOP : Items.PORKCHOP;
			case "sheep":
				return cooked ? Items.COOKED_MUTTON : Items.MUTTON;
			case "chicken":
				return cooked ? Items.COOKED_CHICKEN : Items.CHICKEN;
			default:
				return null;
		}
	}

	/**
	 * Opens any chest it happens to walk past.
	 *
	 * @return a directive when it is dealing with a container, or null to carry
	 *         on with the ladder
	 */
	private Directive loot(HunterEntity hunter, ServerLevel level) {
		BlockPos container = looter.find(level, hunter.blockPosition(), 1_200);
		if (container == null) {
			return null;
		}

		if (!looter.inReach(hunter, container)) {
			return Directive.travel(standingSpotFor(level, container));
		}

		hunter.getLookControl().setLookAt(Vec3.atCenterOf(container));
		looter.take(level, container, carrying);
		equipFromInventory(hunter);
		return Directive.working();
	}

	// -----------------------------------------------------------------
	// Crafting
	// -----------------------------------------------------------------

	private Directive craft(HunterEntity hunter, ServerLevel level, CraftRecipe recipe) {
		if (!recipe.canCraft(carrying)) {
			// The ladder is a pure function of the pack, so it will ask for this
			// same impossible craft again next tick, and the tick after that.
			// Standing still waiting for that to change is how a hunter ends up
			// frozen in a field forever. Go back to hunting instead and let the
			// next thing it picks up move the ladder on.
			return Directive.HUNT;
		}

		if (recipe.needsTable()) {
			BlockPos table = findNearby(level, hunter, Blocks.CRAFTING_TABLE);
			if (table == null) {
				if (!carrying.has(Items.CRAFTING_TABLE, 1)) {
					return Directive.working();
				}
				BlockPos spot = freeSpotNear(level, hunter);
				if (spot == null) {
					return Directive.travel(wander(hunter));
				}
				level.setBlockAndUpdate(spot, Blocks.CRAFTING_TABLE.defaultBlockState());
				carrying.consume(Items.CRAFTING_TABLE, 1);
				hunter.onPlacedBlock(spot);
				return Directive.working();
			}
			hunter.getLookControl().setLookAt(Vec3.atCenterOf(table));
		}

		hunter.zza = 0.0f;
		if (craftTicks++ < CRAFT_TICKS) {
			return Directive.working();
		}
		craftTicks = 0;

		if (recipe.craft(carrying)) {
			level.playSound(null, hunter.blockPosition(), SoundEvents.WOOD_PLACE,
					hunter.getSoundSource(), 0.7f, 1.4f);
			equipFromInventory(hunter);
		}
		return Directive.working();
	}

	// -----------------------------------------------------------------
	// Smelting
	// -----------------------------------------------------------------

	private Directive smelt(HunterEntity hunter, ServerLevel level,
			Progression.Task.Smelt task) {
		Item result = Recipes.smeltResult(task.input());
		if (result == null || !carrying.has(task.input(), 1)) {
			return Directive.working();
		}

		// Food goes in a smoker when there is one, because it cooks in half the
		// time. Ore has to use a furnace either way.
		boolean isFood = Recipes.RAW_FOOD.contains(task.input());
		BlockPos smoker = isFood ? findNearby(level, hunter, Blocks.SMOKER) : null;
		BlockPos furnace = smoker != null ? smoker : findNearby(level, hunter, Blocks.FURNACE);

		if (furnace == null) {
			Item toPlace = isFood && carrying.has(Items.SMOKER, 1) ? Items.SMOKER : Items.FURNACE;
			if (!carrying.has(toPlace, 1)) {
				return Directive.working();
			}
			BlockPos spot = freeSpotNear(level, hunter);
			if (spot == null) {
				return Directive.travel(wander(hunter));
			}
			level.setBlockAndUpdate(spot,
					net.minecraft.world.level.block.Block.byItem(toPlace).defaultBlockState());
			carrying.consume(toPlace, 1);
			hunter.onPlacedBlock(spot);
			return Directive.working();
		}

		int cookTime = smoker != null ? SMELT_TICKS / 2 : SMELT_TICKS;

		if (smeltRemaining <= 0 && !burnFuel()) {
			// No fuel. Planks are always available further down the ladder.
			if (carrying.hasTag(ItemTags.PLANKS, 1)) {
				carrying.consumeTag(ItemTags.PLANKS, 1);
				smeltRemaining = 1;
			} else {
				return Directive.working();
			}
		}

		hunter.getLookControl().setLookAt(Vec3.atCenterOf(furnace));
		hunter.zza = 0.0f;

		if (smeltTicks++ < cookTime) {
			return Directive.working();
		}
		smeltTicks = 0;
		smeltRemaining--;

		if (carrying.consume(task.input(), 1)) {
			carrying.add(result, 1);
			level.playSound(null, furnace, SoundEvents.FIRE_EXTINGUISH,
					hunter.getSoundSource(), 0.4f, 1.8f);
		}
		return Directive.working();
	}

	private boolean burnFuel() {
		for (Item candidate : List.of(Items.COAL, Items.CHARCOAL, Items.STICK)) {
			int value = Recipes.fuelValue(candidate);
			if (value > 0 && carrying.consume(candidate, 1)) {
				smeltRemaining = value;
				return true;
			}
		}
		return false;
	}

	// -----------------------------------------------------------------
	// Equipment
	// -----------------------------------------------------------------

	/**
	 * Moves the best thing carried into each slot.
	 *
	 * <p>Every tier is listed, not just iron, because the hunter can perfectly
	 * well end up in diamond off the back of a lucky chest and there is no
	 * reason for it to keep wearing the iron it made itself.
	 */
	public void equipFromInventory(HunterEntity hunter) {
		wear(hunter, EquipmentSlot.MAINHAND, ToolLore.bestWeapon(carrying));
		wear(hunter, EquipmentSlot.OFFHAND, carrying.has(Items.SHIELD, 1) ? Items.SHIELD : null);
		for (EquipmentSlot slot : ARMOUR_SLOTS) {
			wear(hunter, slot, ToolLore.bestArmour(carrying, slot));
		}
	}

	/**
	 * Puts an axe in hand against a raised shield, and the best weapon otherwise.
	 *
	 * <p>Without this the shield answer never completes. The hunter notices the
	 * shield, walks off to make an axe, comes back satisfied that it now owns
	 * one, and then swings the sword that is still in its hand. The axe sits in
	 * the inventory for the whole fight.
	 *
	 * @param shielded whether the target is currently hiding behind a shield
	 */
	public void equipForFight(HunterEntity hunter, boolean shielded) {
		Item wanted = shielded ? ToolLore.shieldBreaker(carrying) : null;
		if (wanted == null) {
			wanted = ToolLore.bestWeapon(carrying);
		}
		wear(hunter, EquipmentSlot.MAINHAND, wanted);
	}

	private static final EquipmentSlot[] ARMOUR_SLOTS = {
		EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
	};

	private void wear(HunterEntity hunter, EquipmentSlot slot, Item item) {
		if (item == null || hunter.getItemBySlot(slot).is(item)) {
			return;
		}
		hunter.setItemSlot(slot, new ItemStack(item));
		hunter.setDropChance(slot, 0.0f);
	}

	/**
	 * Swaps to the right tool before mining, which matters twice: it is faster,
	 * and stone only drops cobblestone when broken with a pickaxe.
	 */
	private void equipBestToolFor(HunterEntity hunter, BlockState state) {
		Item wanted;
		if (state.is(BlockTags.LOGS)) {
			wanted = ToolLore.bestAxe(carrying);
		} else if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
			wanted = ToolLore.bestShovel(carrying);
			if (wanted == null) {
				wanted = ToolLore.bestPickaxe(carrying);
			}
		} else {
			wanted = ToolLore.bestPickaxe(carrying);
		}
		if (wanted != null && !hunter.getMainHandItem().is(wanted)) {
			hunter.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(wanted));
			hunter.setDropChance(EquipmentSlot.MAINHAND, 0.0f);
		}
	}

	private Item firstHeld(Item... preference) {
		for (Item item : preference) {
			if (carrying.has(item, 1)) {
				return item;
			}
		}
		return null;
	}

	// -----------------------------------------------------------------
	// Helpers
	// -----------------------------------------------------------------

	private BlockPos findNearby(ServerLevel level, HunterEntity hunter, Block block) {
		BlockPos centre = hunter.blockPosition();
		for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-4, -2, -4),
				centre.offset(4, 2, 4))) {
			if (level.getBlockState(pos).is(block)) {
				return pos.immutable();
			}
		}
		return null;
	}

	/** An air block beside the hunter with something solid under it. */
	private BlockPos freeSpotNear(ServerLevel level, HunterEntity hunter) {
		BlockPos base = hunter.blockPosition();
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos candidate = base.relative(direction);
			if (level.getBlockState(candidate).canBeReplaced()
					&& level.getBlockState(candidate.below())
							.isFaceSturdy(level, candidate.below(), Direction.UP)) {
				return candidate;
			}
		}
		return null;
	}

	/** A spot beside a target block that the hunter can stand on to reach it. */
	private BlockPos standingSpotFor(ServerLevel level, BlockPos target) {
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos candidate = target.relative(direction);
			if (level.getBlockState(candidate).canBeReplaced()) {
				return candidate;
			}
		}
		return target.above();
	}

	/** Somewhere else. Used when the surroundings have nothing worth having. */
	private BlockPos wander(HunterEntity hunter) {
		double angle = hunter.getRandom().nextDouble() * Math.PI * 2.0D;
		return hunter.blockPosition().offset(
				(int) Math.round(Math.cos(angle) * 40.0D), 0,
				(int) Math.round(Math.sin(angle) * 40.0D));
	}

	private void resetSite(ServerLevel level, HunterEntity hunter) {
		if (worksite != null) {
			level.destroyBlockProgress(hunter.getId(), worksite, -1);
			worksite = null;
		}
		mineProgress = 0.0f;
		mineRequired = 0.0f;
	}

	private void abandonWork(ServerLevel level, HunterEntity hunter) {
		resetSite(level, hunter);
		craftTicks = 0;
	}

	/**
	 * Drops every piece of in-progress work and every remembered search.
	 *
	 * <p>For the watchdog. If the hunter has genuinely stopped making progress,
	 * the most likely reason is that it is fixated on a block, a container or a
	 * sweep that is no longer reachable, and the cheapest way out is to forget
	 * all of it and look again from where it is now standing.
	 */
	public void startOver(ServerLevel level, HunterEntity hunter) {
		abandonWork(level, hunter);
		scanner.clear();
		looter.forgetAll();
		smeltTicks = 0;
		butcherTicks = 0;
	}

	public void save(ValueOutput out) {
		carrying.save(out);
		out.putInt("SmeltRemaining", smeltRemaining);
	}

	public void load(ValueInput in) {
		carrying.load(in);
		smeltRemaining = in.getIntOr("SmeltRemaining", 0);
	}
}
