package dev.tiltedlunar.hunted.hunter;

import java.util.List;
import java.util.Set;

import dev.tiltedlunar.hunted.HuntedConfig;
import dev.tiltedlunar.hunted.path.LevelWorldView;
import dev.tiltedlunar.hunted.path.PathProfile;
import dev.tiltedlunar.hunted.path.PathSearch;
import dev.tiltedlunar.hunted.path.PathStep;
import dev.tiltedlunar.hunted.path.PosCodec;
import dev.tiltedlunar.hunted.survival.Progression;
import dev.tiltedlunar.hunted.survival.SurvivalBrain;
import dev.tiltedlunar.hunted.tactics.Appraisal;
import dev.tiltedlunar.hunted.tactics.Interception;
import dev.tiltedlunar.hunted.tactics.MotionTracker;
import dev.tiltedlunar.hunted.tactics.Readiness;
import dev.tiltedlunar.hunted.tactics.Scout;
import dev.tiltedlunar.hunted.tactics.Tactic;
import dev.tiltedlunar.hunted.tactics.Tactics;
import dev.tiltedlunar.hunted.taunt.Taunter;
import dev.tiltedlunar.hunted.taunt.Taunts;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * The hunter.
 *
 * <p>It has one job: reach the player. Everything here serves that, and the
 * pieces are deliberately separable. {@link TargetTracker} decides what it
 * knows, {@link PathSearch} decides where to go, {@link PathFollower} decides
 * how to move, and this class decides which of those gets attention this tick.
 *
 * <p>Two design notes worth knowing before changing anything.
 *
 * <p>Vanilla navigation is not used at all. Minecraft's own pathfinder gives up
 * at 32 blocks and refuses to consider breaking or placing anything, which
 * rules out every interesting behaviour this mod exists for.
 *
 * <p>The search is sliced across ticks rather than run to completion. A hunter
 * that thinks for 200 milliseconds once is a server hitch; a hunter that thinks
 * for 3 milliseconds twenty times is invisible.
 */
public class HunterEntity extends PathfinderMob implements Enemy {

	private static final EntityDataAccessor<Integer> DATA_TIER =
			SynchedEntityData.defineId(HunterEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> DATA_ACTIVITY =
			SynchedEntityData.defineId(HunterEntity.class, EntityDataSerializers.INT);

	/** Ticks between forced re-plans even when nothing looks wrong. */
	private static final int REPLAN_INTERVAL = 60;

	/** Re-plan early if the quarry has moved further than this from the path end. */
	private static final double GOAL_DRIFT = 8.0D;

	/** Ticks stuck at a crossing point before a high tier forces its way through. */
	private static final int PHASE_DELAY = 100;

	/**
	 * Converts the movement speed attribute into blocks per tick.
	 *
	 * <p>A player at the default 0.1 walks 4.317 blocks a second, which is
	 * 0.2159 a tick, so the attribute scales by roughly this. Sprinting is
	 * already folded in, because setting the sprint flag adds its own
	 * modifier to the same attribute.
	 */
	private static final double SPEED_TO_BLOCKS_PER_TICK = 2.16D;

	/** Below this the target is close enough that leading gains nothing. */
	private static final double LEAD_MIN_DISTANCE = 8.0D;

	/** Inside this range an unseen approach is worth the loss of speed. */
	private static final double SNEAK_RANGE = 22.0D;

	/**
	 * How far out to notice something worth backing away from.
	 *
	 * <p>Wide enough to cover a warden's preferred distance, because noticing
	 * one only at eight blocks is noticing it far too late.
	 */
	private static final double AVOID_RANGE = 18.0D;

	/**
	 * How far it will chase something that hit it first.
	 *
	 * <p>Generous enough to close on the skeleton that is shooting it, short
	 * enough that it does not abandon the hunt to follow a zombie across a field.
	 */
	private static final double RETALIATE_RANGE = 8.0D;

	/** Close enough that a hostile is physically blocking the way through. */
	private static final double IN_THE_WAY_RANGE = 3.0D;

	/**
	 * Distance the hunter wants before it will settle down and gather.
	 *
	 * <p>Two thresholds rather than one, because a single line produces a
	 * hunter that paces along it: anything worth walking to is usually towards
	 * the quarry, so stepping over the line to reach it immediately puts it
	 * back under. It breaks off when the quarry gets inside {@link #CROWDED}
	 * and does not settle again until it has this much room.
	 */
	private static final double WORKING_ROOM = 16.0D;

	/** Quarry this close and there is no room to work at all. */
	private static final double CROWDED = 12.5D;

	/**
	 * Ticks of no progress at all before the watchdog intervenes.
	 *
	 * <p>Fifteen seconds, which has to clear the slowest thing the hunter does
	 * standing still: a furnace cycle is two hundred ticks, and a log punched
	 * by hand is another two hundred. Setting this under that turned the
	 * watchdog into the very bug it exists to catch, tearing up the hunter's
	 * mining progress every five seconds so no tree was ever felled.
	 */
	private static final int PATIENCE = 300;

	/**
	 * Ticks of gathering that changes nothing before it gives up and hunts.
	 *
	 * <p>Twenty seconds. Long enough to walk to a tree, fell it and carry the
	 * wood back; short enough that a player never watches it potter about for
	 * a minute while they stand there waiting to be hunted.
	 */
	private static final int ECONOMY_PATIENCE = 400;

	/** Ticks spent hunting before it is willing to go shopping again. */
	private static final int ECONOMY_REST = 600;

	/**
	 * How far the hunter has to get from where it was to count as going
	 * somewhere. Anything tighter than this is pacing, not travelling.
	 */
	private static final double WANDER_RADIUS = 3.0D;

	/** Ticks the watchdog keeps walking once it has decided to shove. */
	private static final int NUDGE_TICKS = 30;

	/**
	 * How long a break or a place stays on the books when deciding whether the
	 * hunter is churning. Two seconds, which is longer than the gap between
	 * one and the next in every churn seen so far, and shorter than any real
	 * stretch of work that only ever digs or only ever builds.
	 */
	private static final int CHURN_WINDOW = 40;

	/**
	 * How long the hunter keeps doing without one of its tools after a rescue.
	 *
	 * <p>Ten seconds. Long enough for the awkward route to be planned and most
	 * of the way walked, short enough that a hunter which has genuinely got
	 * past the problem is not still handicapped by it a minute later.
	 */
	private static final int IMPROVISE_TICKS = 200;

	/**
	 * Ticks of walking straight at the target when the planner cannot find any
	 * route at all. Three seconds, then it is worth asking the planner again in
	 * case blundering forwards has changed the picture.
	 */
	private static final int CHARGE_TICKS = 60;

	/** Close enough to the last sighting to start casting around for a trail. */
	private static final double SEARCH_ARRIVE = 4.0D;

	/** Ticks spent on each leg of a search before trying somewhere else. */
	private static final int SEARCH_HOLD = 70;

	/** How far apart successive search points start out. */
	private static final int SEARCH_STEP = 9;

	/** The search never wanders further than this from the last sighting. */
	private static final int SEARCH_MAX = 40;

	/** Radians turned between one search leg and the next. Not a neat fraction
	 *  of a circle, so the sweep does not retrace the same few points. */
	private static final double SEARCH_TURN = 2.399963D;

	/** Untouched for this long before it will stop to eat. */
	public static final int OUT_OF_COMBAT_TICKS = 200;

	/** Health per tick while digesting. Matches a player's natural regeneration. */
	private static final float FOOD_REGEN_PER_TICK = 0.0125f;

	/** Ticks of healing bought per point of nutrition. */
	private static final int TICKS_PER_NUTRITION = 60;

	private final TargetTracker tracker = new TargetTracker();
	private final PathFollower follower = new PathFollower();
	private final CombatController combat = new CombatController();
	private final SurvivalBrain survival = new SurvivalBrain();
	private final DamageClock damageClock = new DamageClock();
	private final Taunter taunter = new Taunter();
	private final MotionTracker motion = new MotionTracker();
	private final Clutch clutch = new Clutch();

	private boolean survivalMode;
	private Tactics.Plan plan = new Tactics.Plan(Tactic.ENGAGE, "no target yet");
	private PathSearch search;
	private long searchGoal;
	private int ticksSincePlan;
	private int crossingTicks;
	private BlockPos crossingPoint;
	private BlockPos lastFix;
	private int ticksSinceSample;
	private int digestTicks;
	private int blocksBroken;
	private int blocksPlaced;
	private BlockPos searchSpot;
	private int searchTicks;
	private int searchLeg;
	private boolean backingOff;
	private net.minecraft.world.phys.Vec3 lastSeenAt;
	private int lastBroken;
	private int lastPlaced;
	private int brokeRecently;
	private int laidRecently;
	private int stalledFor;
	private float nudgeYaw;
	private int workPulse;
	private int lastPulse;
	private int lastCarried = -1;
	private int lastPack = -1;
	private int economyDry;
	private int improviseFor;
	private int improviseMode;
	private int chargeFor;

	/**
	 * How far around itself to look for a portal.
	 *
	 * <p>Kept deliberately small, and much shorter vertically than
	 * horizontally. This is a brute force sweep of every block in the box, so
	 * the cost is the product of all three: at 12 by 6 it is about eight
	 * thousand lookups, where 24 in every direction would have been a hundred
	 * and eighteen thousand, twice a second, on the server thread.
	 */
	private static final int PORTAL_SEARCH = 12;

	/** How far up and down to look. Portals are tall, not deep. */
	private static final int PORTAL_SEARCH_HEIGHT = 6;

	/** Ticks between portal sweeps. Portals do not move. */
	private static final int PORTAL_SCAN_INTERVAL = 40;

	private BlockPos knownPortal;
	private int portalCheckTicks;

	/** The planner's cached terrain, kept so digging can invalidate it. */
	private LevelWorldView searchView;

	/**
	 * Blocks the hunter will never spend on a bridge or a wall.
	 *
	 * <p>All of these are placeable and solid, and all of them are something
	 * the ladder is still counting on. Paving a ravine with the only furnace
	 * means never smelting the iron on the other side of it.
	 */
	private static final Set<Item> TOO_USEFUL_TO_SPEND = Set.of(
			Items.FURNACE, Items.SMOKER, Items.CRAFTING_TABLE, Items.CHEST,
			Items.HAY_BLOCK, Items.IRON_ORE, Items.DEEPSLATE_IRON_ORE,
			Items.RAW_IRON_BLOCK, Items.IRON_BLOCK, Items.DIAMOND_ORE,
			Items.DEEPSLATE_DIAMOND_ORE, Items.DIAMOND_BLOCK, Items.COAL_ORE,
			Items.DEEPSLATE_COAL_ORE, Items.COAL_BLOCK);

	/** Whoever it was chasing when it last had someone, so it can still talk. */
	private ServerPlayer lastQuarry;
	private boolean spawnAnnounced;
	private boolean searchAnnounced;
	private boolean dimensionAnnounced;

	@SuppressWarnings({"rawtypes", "unchecked"})
	public HunterEntity(EntityType<? extends HunterEntity> type, Level level) {
		super(type, level);
		setPersistenceRequired();
		this.xpReward = 20;
		// See the class comment on IdleMoveControl. Without this the hunter
		// cannot move at all, and jumps have to go through the jump control
		// rather than setJumping for exactly the same reason: vanilla ticks
		// both of them after this mod has had its say, and both of them write
		// over whatever it decided.
		this.moveControl = new IdleMoveControl(this);
	}

	/**
	 * A move control that does nothing, because this mob steers itself.
	 *
	 * <p>Vanilla runs {@code moveControl.tick()} immediately <em>after</em>
	 * {@code customServerAiStep}, and the default control, having no vanilla
	 * navigation target to follow, sits in its WAIT state and calls
	 * {@code setZza(0)} every tick. That silently erased the movement input the
	 * follower had just written, one line after it was written, so the hunter
	 * planned a route, reported that it was moving, and then stood perfectly
	 * still forever. Replacing the control is what lets the path actually
	 * become motion.
	 *
	 * <p>Raw on purpose. {@code MoveControl} gained a type parameter in 26.2
	 * and does not have one in 26.1.x, so naming the parameter compiles against
	 * one version and not the other. Nothing here touches the field that
	 * parameter types, so the raw form is the one that works everywhere.
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static final class IdleMoveControl
			extends net.minecraft.world.entity.ai.control.MoveControl {

		IdleMoveControl(HunterEntity mob) {
			super(mob);
		}

		@Override
		public void tick() {
			// Deliberately empty. PathFollower owns zza and xxa.
		}
	}

	/**
	 * Baseline attributes, set to a player's exactly.
	 *
	 * <p>Twenty health, player walking speed, one point of base attack damage
	 * with the weapon supplying the rest, and a 0.6 step height so it has to
	 * jump up a full block like everyone else. Unfair tiers raise these on top
	 * at spawn time, and say so.
	 */
	public static AttributeSupplier.Builder createAttributes() {
		return PathfinderMob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, HunterTier.PLAYER_HEALTH)
				.add(Attributes.MOVEMENT_SPEED, HunterTier.PLAYER_SPEED)
				.add(Attributes.ATTACK_DAMAGE, HunterTier.PLAYER_ATTACK)
				.add(Attributes.FOLLOW_RANGE, 192.0D)
				.add(Attributes.STEP_HEIGHT, 0.6D);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(DATA_TIER, HunterTier.RIVAL.ordinal());
		builder.define(DATA_ACTIVITY, PathFollower.State.IDLE.ordinal());
	}

	@Override
	protected void registerGoals() {
		// Only buoyancy. Everything else is driven by the follower, and a
		// vanilla goal fighting it for the movement inputs would produce a mob
		// that twitches instead of walking.
		this.goalSelector.addGoal(0, new FloatGoal(this));
	}

	// -----------------------------------------------------------------
	// Tier
	// -----------------------------------------------------------------

	public HunterTier tier() {
		int ordinal = this.entityData.get(DATA_TIER);
		HunterTier[] all = HunterTier.values();
		return all[Math.floorMod(ordinal, all.length)];
	}

	/** Sets the tier and re-applies every stat and piece of gear that depends on it. */
	public void setTier(HunterTier tier) {
		this.entityData.set(DATA_TIER, tier.ordinal());
		applyTier(tier, true);
	}

	/**
	 * Re-applies the tier without healing.
	 *
	 * <p>Used when loading a saved hunter. Filling its health back up on every
	 * chunk reload would mean a wounded one only has to be walked away from to
	 * be cured, which is a strange way to lose a chase.
	 */
	private void restoreTier(HunterTier tier) {
		this.entityData.set(DATA_TIER, tier.ordinal());
		applyTier(tier, false);
	}

	private void applyTier(HunterTier tier, boolean heal) {
		// A visible name is what sneaking exists to take away. Without one the
		// crouched approach hides nothing, because there was nothing on screen
		// to hide. Vanilla stops drawing it the moment the hunter shifts.
		setCustomName(net.minecraft.network.chat.Component.literal(tier.displayName())
				.withStyle(net.minecraft.ChatFormatting.DARK_RED));
		setCustomNameVisible(true);

		setAttribute(Attributes.MAX_HEALTH, tier.maxHealth());
		setAttribute(Attributes.MOVEMENT_SPEED, tier.moveSpeed());
		setAttribute(Attributes.ATTACK_DAMAGE, tier.attackDamage());
		setAttribute(Attributes.FOLLOW_RANGE, tier.followRange());
		if (heal) {
			setHealth((float) tier.maxHealth());
		} else {
			// Max health may have moved, so keep the saved value in range.
			setHealth(Math.min(getHealth(), (float) tier.maxHealth()));
		}
		if (!survivalMode) {
			equipForTier(tier);
		}
	}

	/**
	 * Starts the hunter with nothing and makes it earn its gear.
	 *
	 * <p>Must be set before the tier is applied, since the tier is what would
	 * otherwise hand it a full kit.
	 */
	public void setSurvivalMode(boolean value) {
		this.survivalMode = value;
		if (value) {
			for (EquipmentSlot slot : EquipmentSlot.values()) {
				setItemSlot(slot, ItemStack.EMPTY);
			}
		}
	}

	public boolean survivalMode() {
		return survivalMode;
	}

	public SurvivalBrain survival() {
		return survival;
	}

	/** The current decision and why, for the status command. */
	public Tactics.Plan plan() {
		return plan;
	}

	/** How hard it is currently being hit, and how long that leaves it. */
	public DamageClock damageClock() {
		return damageClock;
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		float before = getHealth();
		boolean hurt = super.hurtServer(level, source, amount);
		if (hurt) {
			// Record what actually landed, after armour and shields, because
			// the raw number would have it fleeing from damage it shrugged off.
			damageClock.record(before - getHealth());
		}
		return hurt;
	}

	private void setAttribute(net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
			double value) {
		var instance = getAttribute(attribute);
		if (instance != null) {
			instance.setBaseValue(value);
		}
	}

	/**
	 * Gear is cosmetic in part and functional in part. The pickaxe genuinely
	 * changes how fast the hunter tunnels, because the planner prices routes
	 * using whatever is in its hand.
	 */
	private void equipForTier(HunterTier tier) {
		Item weapon = switch (tier) {
			case SCOUT -> Items.WOODEN_AXE;
			case STALKER -> Items.STONE_PICKAXE;
			case RIVAL -> Items.IRON_PICKAXE;
			case ENFORCER -> Items.DIAMOND_PICKAXE;
			case RELENTLESS -> Items.NETHERITE_PICKAXE;
		};
		setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(weapon));
		setDropChance(EquipmentSlot.MAINHAND, 0.0f);

		Item[] armour = switch (tier) {
			case SCOUT -> new Item[]{null, null, null, null};
			case STALKER -> new Item[]{Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE,
					Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS};
			case RIVAL -> new Item[]{Items.IRON_HELMET, Items.IRON_CHESTPLATE,
					Items.IRON_LEGGINGS, Items.IRON_BOOTS};
			case ENFORCER -> new Item[]{Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE,
					Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS};
			case RELENTLESS -> new Item[]{Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE,
					Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS};
		};
		EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST,
				EquipmentSlot.LEGS, EquipmentSlot.FEET};
		for (int i = 0; i < slots.length; i++) {
			setItemSlot(slots[i], armour[i] == null ? ItemStack.EMPTY : new ItemStack(armour[i]));
			setDropChance(slots[i], 0.0f);
		}
	}

	// -----------------------------------------------------------------
	// Brain
	// -----------------------------------------------------------------

	@Override
	protected void customServerAiStep(ServerLevel level) {
		super.customServerAiStep(level);

		HunterTier tier = tier();
		damageClock.tick();
		if (improviseFor > 0) {
			improviseFor--;
		}
		if (watchdog(level)) {
			return;
		}

		if (tier.regenPerTick() > 0.0D && getHealth() < getMaxHealth()) {
			heal((float) tier.regenPerTick());
		}

		// Healing comes from food, the way it does for a player. Free
		// regeneration would undo the point of making it gather at all.
		if (digestTicks > 0) {
			digestTicks--;
			if (getHealth() < getMaxHealth()) {
				heal(FOOD_REGEN_PER_TICK);
			}
		}

		// Eating is checked every tick, not only while gathering. The whole
		// point of running away hurt is to eat something once you are clear,
		// and the gathering brain does not run while withdrawing, so gating it
		// there meant a hunter that fled at low health stayed at low health
		// forever. tryEat already refuses unless it is hurt, carrying food and
		// has been left alone, so calling it here costs nothing.
		tryEat();

		ServerPlayer quarry = tracker.tick(this, tier);

		// A clutch is the one thing that outranks the hunt. Steering mid fall
		// walks the hunter off the column it just aimed its water at, and it
		// dies next to a full bucket it never got to use.
		//
		// The tracker runs first even so. A long fall is several seconds, and
		// freezing the last known position for all of it means landing and then
		// setting off towards wherever the target was standing when it jumped.
		if (clutch.tick(this, level)) {
			this.zza = 0.0f;
			this.xxa = 0.0f;
			setActivity(PathFollower.State.MOVING);
			return;
		}
		if (quarry == null) {
			idle();
			return;
		}

		setTarget(quarry);

		// First sight of anyone. Said once, and never again for this hunter.
		if (!spawnAnnounced) {
			spawnAnnounced = true;
			taunter.announce(quarry, getRandom(), Taunts.SPAWN);
		}
		lastQuarry = quarry;
		searchAnnounced = false;

		if (quarry.level() != level) {
			pursueAcrossDimensions(level, quarry, tier);
			return;
		}

		crossingPoint = quarry.blockPosition();
		crossingTicks = 0;
		dimensionAnnounced = false;
		sampleMotion();

		// Look at them, score them against itself, and decide. Everything below
		// follows from this one call, including whether to fight at all.
		Appraisal reading = Scout.appraise(this, quarry, tier);
		Readiness self = Scout.readiness(this);
		// Fair tiers break off when hurt. The unfair ones do not flinch, which
		// is most of what makes them read as machines rather than opponents.
		boolean retreatAllowed = tier.fair();
		plan = Tactics.choose(reading, self, survivalMode, retreatAllowed);

		taunter.tick(quarry, getRandom(), plan.tactic(),
				survivalMode ? survival.describe() : null,
				quarry.getMaxHealth() > 0.0f ? quarry.getHealth() / quarry.getMaxHealth() : 1.0f);

		if (plan.tactic().isEconomy()) {
			Progression.Focus focus = plan.tactic() == Tactic.COUNTER_SHIELD
					? Progression.Focus.SHIELD_BREAKER
					: Progression.Focus.NONE;

			// Deciding to go and make an axe means going somewhere to make it.
			// The economy refuses to work with the quarry inside twelve blocks,
			// and nothing here used to back off, so a hunter that chose to gear
			// up simply walked into melee and stayed there: too close to work,
			// still holding the wrong tool, repeating the decision forever.
			// Break off first, then shop.
			double room = distanceTo(quarry);
			if (room < CROWDED) {
				backingOff = true;
			} else if (room >= WORKING_ROOM) {
				backingOff = false;
			}
			// The give up clock starts once it is actually able to work.
			// Manoeuvring into position does not change the pack, so running it
			// during the withdrawal spent the whole twenty seconds before the
			// hunter had picked anything up: it sprinted away for the full
			// count and then turned round and charged with whatever it happened
			// to be holding, which read as the mod losing its nerve.
			if (backingOff) {
				backAwayFrom(quarry);
				return;
			}

			if (!economyGaveUp()) {
				SurvivalBrain.Directive directive =
						survival.tick(this, level, distanceTo(quarry), focus);
				if (directive.busy()) {
					if (directive.goal() == null) {
						this.zza = 0.0f;
						this.xxa = 0.0f;
						setActivity(PathFollower.State.MINING);
						return;
					}
					planTowards(level, tier, directive.goal());
					follow(level);
					return;
				}
			}
		}

		// Something else in the way can outrank the hunt for a moment. Standing
		// next to a lit creeper is worth interrupting anything for.
		if (handleBystanders(level)) {
			return;
		}

		setShiftKeyDown(shouldSneak(quarry, distanceTo(quarry)));

		// Pick the weapon for the fight that is actually about to happen, which
		// is the only place that knows whether their shield is up.
		if (survivalMode) {
			survival.equipForFight(this, reading.shield());
		}

		if (combat.tick(this, level, quarry, plan.tactic())) {
			setActivity(PathFollower.State.MOVING);
			return;
		}

		// Withdrawing means withdrawing. Combat only drives the retreat while
		// the quarry is inside engage range; one step past it the tick used to
		// fall through here and navigate straight back at the thing it had
		// just decided to run away from.
		if (plan.tactic() == Tactic.WITHDRAW) {
			backAwayFrom(quarry);
			return;
		}

		navigate(level, tier);
	}

	/**
	 * Deals with whatever else has wandered into the fight.
	 *
	 * <p>The hunter is not here to clear the map, so almost everything is
	 * walked past. Three things are not: something it should never be standing
	 * near, something that explodes, and something that is already hitting it.
	 * Walking to your death past a skeleton that is shooting you in the back is
	 * not single mindedness, it is a bug.
	 *
	 * @return true when a bystander is worth more attention than the target
	 */
	private boolean handleBystanders(ServerLevel level) {
		net.minecraft.world.entity.LivingEntity threat = nearestMob(level, AVOID_RANGE,
				other -> MobLore.isUrgent(other));

		if (threat != null && distanceTo(threat) <= MobLore.preferredRange(threat) + 2.0D) {
			// A creeper gets hit once on the way out. That is the difference
			// between backing off and being chased by it.
			if (MobLore.approach(threat) == MobLore.Approach.HIT_AND_RUN
					&& distanceTo(threat) < 3.2D) {
				combat.strikeOnce(this, level, threat);
			}
			backAwayFrom(threat);
			return true;
		}

		// A hunter that has decided to break off does not stop to fight a
		// zombie on the way out. That was the whole decision.
		if (plan.tactic() == Tactic.WITHDRAW || plan.tactic() == Tactic.DEFEND) {
			return false;
		}

		net.minecraft.world.entity.LivingEntity fight = pickFight(level);
		if (fight == null) {
			return false;
		}

		// Reuse the real combat code rather than a second, worse copy of it, so
		// a zombie gets the same crit timing and cooldown discipline a player does.
		if (survivalMode) {
			survival.equipForFight(this, false);
		}

		// Hand back whatever combat decided. Claiming the bystander was handled
		// when the fight code could not even reach it leaves the hunter standing
		// still, neither fighting the mob nor chasing anyone, for as long as the
		// mob stays where it is.
		if (!combat.tick(this, level, fight, Tactic.ENGAGE)) {
			return false;
		}
		setActivity(PathFollower.State.MOVING);
		return true;
	}

	/**
	 * The mob worth stopping for, or null to carry on hunting.
	 *
	 * <p>Only two things qualify. Something that has actually landed a hit
	 * recently, because ignoring it means taking that damage for free until it
	 * stops. And something classed as worth killing that is close enough to be
	 * physically in the way. Everything at a comfortable distance is somebody
	 * else's problem.
	 */
	private net.minecraft.world.entity.LivingEntity pickFight(ServerLevel level) {
		net.minecraft.world.entity.LivingEntity attacker = getLastHurtByMob();
		if (attacker != null && attacker.isAlive()
				&& MobLore.approach(attacker) == MobLore.Approach.KILL
				&& distanceTo(attacker) <= RETALIATE_RANGE
				&& hasLineOfSight(attacker)) {
			return attacker;
		}

		// Line of sight matters more here than anywhere else. Without it the
		// hunter stops for a zombie on the far side of a wall, swings at the
		// wall forever, and the chase quietly ends.
		return nearestMob(level, IN_THE_WAY_RANGE,
				other -> MobLore.approach(other) == MobLore.Approach.KILL
						&& hasLineOfSight(other));
	}

	private net.minecraft.world.entity.LivingEntity nearestMob(ServerLevel level, double range,
			java.util.function.Predicate<net.minecraft.world.entity.LivingEntity> wanted) {
		return level.getEntitiesOfClass(
						net.minecraft.world.entity.LivingEntity.class,
						getBoundingBox().inflate(range),
						other -> other != this && other.isAlive()
								&& !(other instanceof net.minecraft.world.entity.player.Player)
								&& wanted.test(other))
				.stream()
				.min((a, b) -> Double.compare(distanceToSqr(a), distanceToSqr(b)))
				.orElse(null);
	}

	/** Puts distance between the hunter and something it should not be near. */
	private void backAwayFrom(net.minecraft.world.entity.LivingEntity threat) {
		setShiftKeyDown(false);

		// For a creeper that is outside the blast; for a warden it is simply away.
		double dx = getX() - threat.getX();
		double dz = getZ() - threat.getZ();
		double length = Math.sqrt(dx * dx + dz * dz);
		if (length > 1.0e-4D) {
			float yaw = (float) (net.minecraft.util.Mth.atan2(dz / length, dx / length)
					* (180.0D / Math.PI)) - 90.0f;
			setYRot(yaw);
			yBodyRot = yaw;
			yHeadRot = yaw;
		}
		setSpeed((float) getAttributeValue(Attributes.MOVEMENT_SPEED));
		setSprinting(true);
		this.xxa = 0.0f;
		this.zza = 1.0f;
		setActivity(PathFollower.State.MOVING);
	}

	private void idle() {
		this.zza = 0.0f;
		this.xxa = 0.0f;
		setActivity(PathFollower.State.IDLE);

		setShiftKeyDown(false);

		// Losing the trail is the one quiet moment worth narrating, because from
		// the player's side it is indistinguishable from the thing having given
		// up. A line here tells them it has not.
		//
		// Only to someone still on the server. A player who logged out is not
		// there to read it, and holding the reference would keep them loaded.
		if (lastQuarry != null && (lastQuarry.isRemoved() || !lastQuarry.isAlive())) {
			lastQuarry = null;
		}
		if (lastQuarry != null && !searchAnnounced) {
			searchAnnounced = true;
			taunter.lostTrail(lastQuarry, getRandom());
		}
	}

	/**
	 * Walks to wherever the quarry was last seen in this dimension, on the
	 * assumption that a portal is what they left through. Tiers that can phase
	 * stop assuming and simply follow.
	 */
	private void pursueAcrossDimensions(ServerLevel level, ServerPlayer quarry, HunterTier tier) {
		if (!HuntedConfig.get().crossDimensions()) {
			idle();
			return;
		}

		if (crossingPoint == null) {
			crossingPoint = blockPosition();
		}

		// Once per crossing, not once per tick, or it would say this eighty
		// times a second for as long as the target stays in the Nether.
		if (!dimensionAnnounced) {
			dimensionAnnounced = true;
			taunter.announce(quarry, getRandom(), Taunts.DIMENSION);
		}

		boolean atCrossing = crossingPoint.distSqr(blockPosition()) < 9.0D;
		if (atCrossing) {
			crossingTicks++;
		}

		boolean canPhase = tier.canPhase();
		if (canPhase && crossingTicks > PHASE_DELAY) {
			phaseTo(quarry);
			return;
		}

		// Walk into the portal rather than at the patch of ground where the
		// target was standing when it vanished. Vanilla moves any entity that
		// stands in portal blocks long enough, so arriving is the whole job.
		BlockPos portal = findPortal(level);
		planTowards(level, tier, portal != null ? portal : crossingPoint);
		follow(level);
	}

	/**
	 * The nearest nether portal, or null if there is none in range.
	 *
	 * <p>Rescanned every second rather than every tick. A portal does not move,
	 * and a box search this size is not something to run eighty times a second.
	 */
	private BlockPos findPortal(ServerLevel level) {
		if (portalCheckTicks-- > 0) {
			return knownPortal;
		}
		portalCheckTicks = PORTAL_SCAN_INTERVAL;

		BlockPos from = blockPosition();
		BlockPos found = null;
		double best = Double.MAX_VALUE;
		for (BlockPos at : BlockPos.betweenClosed(
				from.offset(-PORTAL_SEARCH, -PORTAL_SEARCH_HEIGHT, -PORTAL_SEARCH),
				from.offset(PORTAL_SEARCH, PORTAL_SEARCH_HEIGHT, PORTAL_SEARCH))) {
			if (!level.getBlockState(at).is(Blocks.NETHER_PORTAL)) {
				continue;
			}
			double distance = at.distSqr(from);
			if (distance < best) {
				best = distance;
				found = at.immutable();
			}
		}
		knownPortal = found;
		return found;
	}

	/** Last resort dimension crossing for the tiers that have earned it. */
	private void phaseTo(ServerPlayer quarry) {
		ServerLevel destination = (ServerLevel) quarry.level();
		BlockPos at = quarry.blockPosition();

		level().playSound(null, blockPosition(), SoundEvents.ENDERMAN_TELEPORT,
				getSoundSource(), 1.0f, 0.6f);

		teleportTo(destination, at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D,
				Set.<Relative>of(), getYRot(), getXRot(), true);

		destination.playSound(null, at, SoundEvents.ENDERMAN_TELEPORT,
				getSoundSource(), 1.0f, 0.6f);

		crossingTicks = 0;
		resetSearch();
	}

	/** Plans toward the quarry's believed position and walks the result. */
	private void navigate(ServerLevel level, HunterTier tier) {
		BlockPos goal = tracker.hasFix() ? tracker.lastKnown() : null;
		if (goal == null) {
			idle();
			return;
		}

		// A cold trail means it knows where you were and not where you are. It
		// goes there and sweeps the area rather than standing still, which is
		// what losing someone actually looks like. Standing still is what a
		// broken mod looks like.
		BlockPos aim = tracker.cold() ? sweepAround(goal) : intercept(goal);

		// The planner came back empty. Walk at them until it is worth asking again.
		if (chargeFor > 0) {
			charge(level, aim);
			return;
		}

		// Let it finish the block it is already breaking. Handing the follower
		// a fresh path throws away the progress on the current block, and the
		// replan timer fires every sixty ticks, so anything that takes longer
		// than that to break could never be broken at all. Stone with the wrong
		// tool takes a hundred and fifty. The hunter would stand at a wall
		// swinging forever and never get through it.
		//
		// A target that has genuinely moved on still interrupts, because
		// tunnelling towards where someone used to be is its own kind of stuck.
		if (activity() == PathFollower.State.MINING && !goalMovedFar(aim)) {
			follow(level);
			return;
		}

		planTowards(level, tier, aim);
		follow(level);
	}

	/**
	 * Whether shopping has stopped being worth it.
	 *
	 * <p>Gathering is an optimisation. Killing you is the job. Every stall
	 * worth fixing so far has been the hunter deciding to go and fetch
	 * something and then failing to fetch it, so this puts a clock on that: if
	 * a stretch of gathering produces nothing the hunter did not already have,
	 * it stops trying and comes for you with whatever is in its hands.
	 *
	 * <p>Progress means the pack changed. Not the plan, not the position, not
	 * what the status line claims it is doing. Those all kept looking healthy
	 * while nothing happened.
	 *
	 * <p>It goes back to shopping after {@link #ECONOMY_REST}, because the
	 * world moves: the tree it could not reach may be behind it now, and a
	 * chest it walks past later is still worth opening.
	 */
	private boolean economyGaveUp() {
		int carried = survival.carrying().fingerprint();
		if (carried != lastCarried) {
			lastCarried = carried;
			economyDry = 0;
			return false;
		}

		if (economyDry > ECONOMY_PATIENCE + ECONOMY_REST) {
			economyDry = 0;
			return false;
		}

		economyDry++;
		return economyDry > ECONOMY_PATIENCE;
	}

	/** Whether there is anything in the pack it could bridge with. */
	private boolean hasBuildingBlock() {
		BlockState block = takeBuildingBlock();
		if (block == null) {
			return false;
		}
		survival.carrying().add(block.getBlock().asItem(), 1);
		return true;
	}

	/** Whether it has stopped trying to gather and is simply hunting. */
	public boolean shoppingAbandoned() {
		return economyDry > ECONOMY_PATIENCE;
	}

	/**
	 * Counts every swing, so the watchdog can tell work from paralysis.
	 *
	 * <p>Mining, chopping and fighting all come through here.
	 */
	@Override
	public void swing(net.minecraft.world.InteractionHand hand) {
		super.swing(hand);
		workPulse++;
	}

	/**
	 * Guarantees the hunter can never be quietly stuck forever.
	 *
	 * <p>Every stall found by actually playing this had the same shape: some
	 * piece of state pointing at something no longer reachable, a decision
	 * remade identically every tick, and a hunter standing perfectly still with
	 * a plan it was pleased with. Each one had a different cause and each was
	 * invisible from outside. This does not care about the cause.
	 *
	 * <p>Progress means any of four things: it moved, it broke a block, it
	 * placed one, or it is digesting a meal. None of those for
	 * {@link #PATIENCE} ticks and it tears up the plan: the search, the path,
	 * and everything the economy was in the middle of. If that does not help
	 * either, it shoves itself sideways, because the remaining explanation is
	 * usually geometry rather than intent.
	 */
	private boolean watchdog(ServerLevel level) {
		// Measured against an anchor rather than against last tick, because a
		// hunter shuffling between two adjacent blocks moves every single tick
		// and gets nowhere. Comparing consecutive ticks called that progress
		// and let it shuffle forever.
		boolean moved = lastSeenAt == null
				|| distanceToSqr(lastSeenAt.x, lastSeenAt.y, lastSeenAt.z)
						> WANDER_RADIUS * WANDER_RADIUS;
		// Swinging counts. Breaking a block only registers on the tick it
		// finally gives way, and everything before that looks identical to
		// standing still doing nothing.
		//
		// Breaking and placing together does not count. A hunter that puts a
		// block down and immediately takes it back up again is not building
		// anything, it is chewing a hole in the world on the spot.
		//
		// Measured over a window rather than within one tick. The two never
		// land on the same tick: breaking takes as long as the block is hard
		// and placing is instant, so they alternate a second apart. Asking
		// whether both happened on this tick meant the answer was always no,
		// and the check that was supposed to catch the worst stall in here
		// never once fired.
		int broke = blocksBroken - lastBroken;
		int laid = blocksPlaced - lastPlaced;
		if (broke != 0) {
			brokeRecently = CHURN_WINDOW;
		} else if (brokeRecently > 0) {
			brokeRecently--;
		}
		if (laid != 0) {
			laidRecently = CHURN_WINDOW;
		} else if (laidRecently > 0) {
			laidRecently--;
		}
		// A change in the pack counts too. Smelting is the one job that moves
		// nothing, breaks nothing, places nothing and swings at nothing: the
		// hunter stands at a furnace for two hundred ticks per item and every
		// other test here reads that as paralysis. One item fitted inside the
		// patience and a stack did not, so it would cook a single piece of ore
		// and then be shoved off the furnace, over and over.
		int carried = survival.carrying().fingerprint();
		boolean restocked = carried != lastPack;
		lastPack = carried;

		boolean churning = brokeRecently > 0 && laidRecently > 0;
		boolean worked = !churning
				&& (broke != 0 || laid != 0 || digestTicks > 0 || restocked
						|| workPulse != lastPulse);

		lastBroken = blocksBroken;
		lastPlaced = blocksPlaced;
		lastPulse = workPulse;

		if (moved || worked) {
			lastSeenAt = position();
			stalledFor = 0;
			return false;
		}

		stalledFor++;

		if (stalledFor == PATIENCE) {
			resetSearch();
			follower.setPath(level, getId(), List.of());
			survival.startOver(level, this);
			searchSpot = null;
			searchTicks = 0;
			// Tearing up the plan on its own is not enough, and never was. The
			// world has not changed, so the search runs again from the same
			// place to the same goal and hands back the identical route that
			// just failed, which is how a hunter comes to be rescued from the
			// same corner every fifteen seconds for the rest of the game.
			// Taking one of its tools away for a while forces a different
			// answer: with nothing to build with it has to tunnel, and with
			// nothing to dig with it has to climb.
			improviseMode++;
			improviseFor = IMPROVISE_TICKS;
			return false;
		}

		// Still nothing. Walk somewhere, anywhere, and let the planner start
		// again from wherever that leaves it. This has to own the whole tick
		// and return: setting the movement input and then letting the normal
		// code run would just have the next branch set it straight back to
		// zero, which is exactly how the hunter came to be stuck here.
		if (stalledFor >= PATIENCE * 2) {
			if (stalledFor >= PATIENCE * 2 + NUDGE_TICKS) {
				stalledFor = 0;
			}
			if (stalledFor == PATIENCE * 2) {
				nudgeYaw = wayOut(level);
			}
			setYRot(nudgeYaw);
			yBodyRot = nudgeYaw;
			yHeadRot = nudgeYaw;
			setSpeed((float) getAttributeValue(Attributes.MOVEMENT_SPEED));
			setSprinting(false);
			setShiftKeyDown(false);
			this.xxa = 0.0f;
			this.zza = 1.0f;
			getJumpControl().jump();
			setActivity(PathFollower.State.MOVING);
			return true;
		}
		return false;
	}

	/**
	 * A direction with ground under it, for the watchdog to shove towards.
	 *
	 * <p>Tries the eight compass directions in a random order and takes the
	 * first with something solid to stand on a couple of blocks along. Picking
	 * a pure random heading instead walks the hunter off ledges and, over a few
	 * shoves, wedges it into whatever corner it started nearest.
	 */
	private float wayOut(ServerLevel level) {
		int start = getRandom().nextInt(8);
		for (int i = 0; i < 8; i++) {
			double angle = ((start + i) % 8) * (Math.PI / 4.0D);
			int dx = (int) Math.round(Math.cos(angle) * 2.0D);
			int dz = (int) Math.round(Math.sin(angle) * 2.0D);
			BlockPos ahead = blockPosition().offset(dx, 0, dz);
			boolean floor = level.getBlockState(ahead.below())
					.isCollisionShapeFullBlock(level, ahead.below());
			boolean room = level.getBlockState(ahead).isAir()
					&& level.getBlockState(ahead.above()).isAir();
			if (floor && room) {
				return (float) (Math.toDegrees(Math.atan2(dz, dx))) - 90.0f;
			}
		}
		return getRandom().nextFloat() * 360.0f;
	}

	/**
	 * A point to search, near the last place the quarry was seen.
	 *
	 * <p>Walks to the spot itself first. Once it is standing there and has
	 * still found nothing, it starts casting around: a new point every few
	 * seconds, further out each time, until either it sees the player again or
	 * the search area is wide enough that it starts over close in. That is the
	 * difference between a hunter that lost you and a hunter that gave up.
	 */
	private BlockPos sweepAround(BlockPos anchor) {
		if (distanceToSqr(anchor.getX() + 0.5D, getY(), anchor.getZ() + 0.5D)
				> SEARCH_ARRIVE * SEARCH_ARRIVE) {
			return anchor;
		}

		if (searchTicks-- <= 0) {
			searchTicks = SEARCH_HOLD;
			searchLeg++;
			double angle = searchLeg * SEARCH_TURN;
			int reach = (int) Math.min(SEARCH_MAX, SEARCH_STEP * (1 + searchLeg / 4));
			searchSpot = anchor.offset(
					(int) Math.round(Math.cos(angle) * reach),
					0,
					(int) Math.round(Math.sin(angle) * reach));
		}
		return searchSpot == null ? anchor : searchSpot;
	}

	/** Whether the goal has drifted far enough to be worth abandoning a dig. */
	private boolean goalMovedFar(BlockPos goal) {
		if (search == null && searchGoal == 0L) {
			return true;
		}
		long key = PosCodec.pack(goal.getX(), goal.getY(), goal.getZ());
		return PosCodec.distance(searchGoal, key) > GOAL_DRIFT;
	}

	/**
	 * Turns the quarry's believed position into somewhere worth running to.
	 *
	 * <p>Steering at where someone currently is produces the tail chase that
	 * gives every pursuit mob away: it sits behind you at a fixed distance and
	 * never closes, because it is forever aiming at where you just were. This
	 * solves for where the two of you actually meet and heads there instead.
	 *
	 * <p>Only when the target has been moving predictably. Leading someone who
	 * is strafing in a doorway just sends the hunter confidently past them.
	 */
	private BlockPos intercept(BlockPos believed) {
		if (motion.confidence() <= 0.0D
				|| believed.distToCenterSqr(position()) < LEAD_MIN_DISTANCE * LEAD_MIN_DISTANCE) {
			return believed;
		}

		double speed = getAttributeValue(Attributes.MOVEMENT_SPEED) * SPEED_TO_BLOCKS_PER_TICK;
		Interception.Point aim = Interception.aim(
				new Interception.Point(getX(), getY(), getZ()),
				new Interception.Point(believed.getX(), believed.getY(), believed.getZ()),
				motion.velocity(),
				speed,
				motion.confidence());

		return BlockPos.containing(aim.x(), aim.y(), aim.z());
	}

	/** Feeds the tracker's latest fix into the motion estimate. */
	private void sampleMotion() {
		ticksSinceSample++;
		if (tracker.staleness() != 0) {
			return;
		}

		BlockPos fix = tracker.lastKnown();
		if (fix == null) {
			return;
		}
		if (lastFix != null && ticksSinceSample > 0) {
			motion.observe(
					fix.getX() - lastFix.getX(),
					fix.getY() - lastFix.getY(),
					fix.getZ() - lastFix.getZ(),
					ticksSinceSample);
		}
		lastFix = fix;
		ticksSinceSample = 0;
	}

	/**
	 * Keeps a search running toward {@code goal}, starting a fresh one whenever
	 * the current plan has gone stale.
	 */
	private void planTowards(ServerLevel level, HunterTier tier, BlockPos goal) {
		ticksSincePlan++;

		long goalKey = PosCodec.pack(goal.getX(), goal.getY(), goal.getZ());
		boolean drifted = search != null
				&& PosCodec.distance(searchGoal, goalKey) > GOAL_DRIFT;

		if (search == null || drifted || ticksSincePlan > REPLAN_INTERVAL) {
			startSearch(level, tier, goalKey);
		}

		if (search != null) {
			int slice = Math.max(200, tier.pathBudget() / 8);
			PathSearch.Status status = search.advance(slice);
			if (status != PathSearch.Status.RUNNING) {
				List<PathStep> steps = search.path();
				if (!steps.isEmpty()) {
					follower.setPath(level, getId(), steps);
					chargeFor = 0;
				} else {
					// No route at all. Keeping the old path means following
					// something already walked to the end, arriving, throwing
					// the plan away and asking again, which produces the same
					// nothing for as long as the geometry lasts. Head in the
					// right direction on foot instead and deal with whatever
					// is in the way when it is close enough to touch.
					chargeFor = CHARGE_TICKS;
				}
				search = null;
			}
		}
	}

	/**
	 * Walking at the target with no plan at all.
	 *
	 * <p>The bluntest thing the hunter can do, and the last thing it tries. A
	 * route that cannot be found from here may well be findable from twenty
	 * blocks nearer, and standing still while the planner says no is the one
	 * outcome that is always wrong.
	 */
	private void charge(ServerLevel level, BlockPos goal) {
		chargeFor--;
		setShiftKeyDown(false);

		double dx = goal.getX() + 0.5D - getX();
		double dz = goal.getZ() + 0.5D - getZ();
		if (dx * dx + dz * dz > 1.0e-6D) {
			float yaw = (float) (net.minecraft.util.Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0f;
			setYRot(yaw);
			yBodyRot = yaw;
			yHeadRot = yaw;
		}
		getLookControl().setLookAt(net.minecraft.world.phys.Vec3.atCenterOf(goal));
		setSpeed((float) getAttributeValue(Attributes.MOVEMENT_SPEED));
		setSprinting(tier().canSprint());
		this.xxa = 0.0f;
		this.zza = 1.0f;

		// Anything low enough to step over gets jumped rather than walked into.
		if (horizontalCollision || goal.getY() > getBlockY()) {
			getJumpControl().jump();
		}
		setActivity(PathFollower.State.MOVING);
	}

	private void startSearch(ServerLevel level, HunterTier tier, long goalKey) {
		BlockPos from = standingOn();
		LevelWorldView view = new LevelWorldView(level, getMainHandItem());
		PathProfile profile = withPermissions(PathProfile.fromTier(tier));
		searchView = view;

		search = new PathSearch(
				view,
				PosCodec.pack(from.getX(), from.getY(), from.getZ()),
				goalKey,
				1.5D,
				profile,
				tier.pathBudget());
		searchGoal = goalKey;
		ticksSincePlan = 0;
	}

	/**
	 * Where to plan from.
	 *
	 * <p>Not simply where the hunter is. Half of every jump is spent a block or
	 * two above the floor, and a route worked out from up there starts with a
	 * step the hunter cannot reach once it lands. It then jumps at that step,
	 * which puts it back in the air, which is where the next plan gets made,
	 * and the hunter bounces on the spot underneath a first step it can never
	 * arrive at. Planning from the floor it is going to land on instead costs
	 * a handful of block lookups and makes the route start where the feet do.
	 */
	private BlockPos standingOn() {
		BlockPos here = blockPosition();
		if (onGround()) {
			return here;
		}
		BlockPos.MutableBlockPos cursor = here.mutable();
		for (int drop = 0; drop < 4; drop++) {
			BlockPos below = cursor.below();
			if (level().getBlockState(below).isCollisionShapeFullBlock(level(), below)) {
				return cursor.immutable();
			}
			cursor.move(0, -1, 0);
		}
		return here;
	}

	/** Config can veto terrain edits regardless of what the tier allows. */
	private PathProfile withPermissions(PathProfile profile) {
		boolean allowed = canModifyTerrain();
		// Pricing a bridge it has nothing to build with produces a route it can
		// only ever stand at the near end of, replanned identically forever.
		boolean canPay = !survivalMode || hasBuildingBlock();
		// See the watchdog. After a rescue it does without one tool at a time,
		// alternating, so the next route cannot be the one that just failed.
		boolean improvising = improviseFor > 0;
		boolean digging = !improvising || improviseMode % 2 == 0;
		boolean building = !improvising || improviseMode % 2 == 1;
		return new PathProfile(
				profile.canMine() && allowed && digging,
				profile.canBridge() && allowed && canPay && building,
				profile.canOpenDoors(),
				profile.canParkour(),
				profile.sprint(),
				profile.fireImmune(),
				hasWaterBucket(),
				profile.miningSpeed(),
				profile.maxFall());
	}

	private void follow(ServerLevel level) {
		PathFollower.State state = follower.tick(this);
		setActivity(state);

		if (state == PathFollower.State.STUCK || state == PathFollower.State.ARRIVED) {
			resetSearch();
		}
		if (state == PathFollower.State.IDLE) {
			this.zza = 0.0f;
		}
	}

	private void resetSearch() {
		search = null;
		ticksSincePlan = REPLAN_INTERVAL;
	}

	private void setActivity(PathFollower.State state) {
		if (this.entityData.get(DATA_ACTIVITY) != state.ordinal()) {
			this.entityData.set(DATA_ACTIVITY, state.ordinal());
		}
	}

	/** What the hunter is doing. Synced so the client can label it. */
	public PathFollower.State activity() {
		PathFollower.State[] all = PathFollower.State.values();
		return all[Math.floorMod(this.entityData.get(DATA_ACTIVITY), all.length)];
	}

	// -----------------------------------------------------------------
	// Callbacks used by the follower
	// -----------------------------------------------------------------

	/**
	 * Whether the hunter is permitted to break or place anything at all.
	 *
	 * <p>Respects the vanilla mobGriefing rule as well as the mod's own switch,
	 * because a server that has already said "mobs do not touch my builds"
	 * should not have to say it twice.
	 */
	public boolean canModifyTerrain() {
		if (!HuntedConfig.get().allowTerrainDamage()) {
			return false;
		}
		return !(level() instanceof ServerLevel server)
				|| server.getGameRules().get(GameRules.MOB_GRIEFING);
	}

	/**
	 * Hands out one block to build with, or null when it has none.
	 *
	 * <p>In survival mode this comes out of what the hunter actually mined,
	 * which means a hunter that has not found any stone genuinely cannot
	 * bridge. Infinite blocks were the last quiet cheat in here.
	 */
	public BlockState takeBuildingBlock() {
		if (!survivalMode) {
			return Blocks.COBBLESTONE.defaultBlockState();
		}

		// Spend the least useful thing first, so it does not bridge away the
		// cobblestone it still needs for a pickaxe.
		for (Item candidate : List.of(Items.DIRT, Items.GRAVEL, Items.NETHERRACK,
				Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.STONE)) {
			if (survival.carrying().consume(candidate, 1)) {
				return net.minecraft.world.level.block.Block.byItem(candidate).defaultBlockState();
			}
		}

		// Nothing on the shortlist. Anything that is a solid full block will
		// hold weight just as well, so it will bridge with planks, wood, ore
		// blocks or whatever else it happens to be carrying. Anything the
		// ladder still needs is off limits, because walling yourself in with
		// your own furnace ends the run just as surely as falling would.
		for (Item candidate : List.copyOf(survival.carrying().view().keySet())) {
			if (TOO_USEFUL_TO_SPEND.contains(candidate)) {
				continue;
			}
			net.minecraft.world.level.block.Block block =
					net.minecraft.world.level.block.Block.byItem(candidate);
			if (block == Blocks.AIR) {
				continue;
			}
			BlockState state = block.defaultBlockState();
			boolean solidEnough = state.isCollisionShapeFullBlock(level(), blockPosition())
					&& state.getFluidState().isEmpty();
			if (solidEnough && survival.carrying().consume(candidate, 1)) {
				return state;
			}
		}
		return null;
	}

	/**
	 * Whether it is worth closing the last stretch crouched.
	 *
	 * <p>Sneaking hides the name tag and halves the speed, so it is only worth
	 * it when the target has not seen it yet and is close enough that arriving
	 * unannounced matters more than arriving quickly. Charging someone who is
	 * already looking at you gains nothing from crouching.
	 */
	private boolean shouldSneak(ServerPlayer quarry, double distance) {
		if (distance > SNEAK_RANGE || distance < 3.0D) {
			return false;
		}
		if (!plan.tactic().isCombat()) {
			return false;
		}
		return !quarry.hasLineOfSight(this);
	}

	/**
	 * Takes a water bucket out of the pack.
	 *
	 * @return false when it has none, which is a real constraint in survival
	 */
	public boolean takeWaterBucket() {
		if (!survivalMode) {
			return true;
		}
		return survival.carrying().consume(Items.WATER_BUCKET, 1);
	}

	/** Puts the bucket back after scooping the water up again. */
	public void returnWaterBucket() {
		if (survivalMode) {
			survival.carrying().add(Items.WATER_BUCKET, 1);
		}
	}

	/**
	 * Whether a water bucket is both carried and worth anything here.
	 *
	 * <p>The second half matters. In the Nether the water evaporates on
	 * contact, so a hunter that still believed it could clutch would walk off
	 * a cliff on the strength of a bucket that cannot save it.
	 */
	public boolean hasWaterBucket() {
		if (!Clutch.waterWorksAt(level(), blockPosition())) {
			return false;
		}
		return !survivalMode || survival.carrying().has(Items.WATER_BUCKET, 1);
	}

	/**
	 * Eats, if it is hurt, carrying something, and nothing is hitting it.
	 *
	 * @return true when it ate
	 */
	public boolean tryEat() {
		if (digestTicks > 0 || getHealth() >= getMaxHealth()) {
			return false;
		}
		if (!damageClock.calmFor(OUT_OF_COMBAT_TICKS)) {
			return false;
		}

		Item meal = dev.tiltedlunar.hunted.survival.Recipes.bestFood(survival.carrying());
		if (meal == null || !survival.carrying().consume(meal, 1)) {
			return false;
		}

		int nutrition = dev.tiltedlunar.hunted.survival.Recipes.nutrition(new ItemStack(meal));
		digestTicks = Math.max(60, nutrition * TICKS_PER_NUTRITION);
		level().playSound(null, blockPosition(), SoundEvents.GENERIC_EAT.value(),
				getSoundSource(), 0.8f, 1.0f);
		return true;
	}

	/** Commentary, so it can tell you what it is doing. */
	public Taunter taunter() {
		return taunter;
	}

	@Override
	public boolean doHurtTarget(ServerLevel level, net.minecraft.world.entity.Entity target) {
		boolean hit = super.doHurtTarget(level, target);
		if (hit && target instanceof ServerPlayer player && player.isDeadOrDying()) {
			taunter.announce(player, getRandom(), Taunts.KILL);
			taunter.reset();
		}
		return hit;
	}

	public void onBrokeBlock(BlockPos pos) {
		blocksBroken++;
		forgetTerrain();
	}

	public void onPlacedBlock(BlockPos pos) {
		blocksPlaced++;
		forgetTerrain();
	}

	/**
	 * Drops the planner's cached view of the world.
	 *
	 * <p>A search runs in slices across many ticks, and the hunter is digging
	 * and bridging the whole time. Without this it keeps planning against the
	 * terrain as it was when the search started, so it routes around walls it
	 * has already broken and steps into gaps it has already filled.
	 */
	private void forgetTerrain() {
		if (searchView != null) {
			searchView.invalidate();
		}
	}

	public int blocksBroken() {
		return blocksBroken;
	}

	public int blocksPlaced() {
		return blocksPlaced;
	}

	public TargetTracker tracker() {
		return tracker;
	}

	// -----------------------------------------------------------------
	// Entity plumbing
	// -----------------------------------------------------------------

	@Override
	public boolean removeWhenFarAway(double distance) {
		return false;
	}

	@Override
	public int getMaxHeadYRot() {
		return 30;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	protected SoundEvent getAmbientSound() {
		return SoundEvents.WARDEN_AMBIENT;
	}

	@Override
	protected SoundEvent getHurtSound(DamageSource source) {
		return SoundEvents.IRON_GOLEM_HURT;
	}

	@Override
	protected SoundEvent getDeathSound() {
		return SoundEvents.IRON_GOLEM_DEATH;
	}

	@Override
	public void addAdditionalSaveData(ValueOutput out) {
		super.addAdditionalSaveData(out);
		out.putString("Tier", tier().id());
		out.putBoolean("SurvivalMode", survivalMode);
		out.putInt("BlocksBroken", blocksBroken);
		out.putInt("BlocksPlaced", blocksPlaced);
		// Otherwise it introduces itself again every time the chunk reloads,
		// which is a good line exactly once.
		out.putBoolean("Announced", spawnAnnounced);
		survival.save(out);
		if (crossingPoint != null) {
			out.putLong("CrossingPoint", crossingPoint.asLong());
		}
		tracker.save(out);
	}

	@Override
	public void readAdditionalSaveData(ValueInput in) {
		super.readAdditionalSaveData(in);
		// Survival mode has to be restored before the tier, because the tier is
		// what decides whether to hand out a free set of gear.
		survivalMode = in.getBooleanOr("SurvivalMode", false);
		restoreTier(HunterTier.byIdOrDefault(in.getStringOr("Tier", ""), HunterTier.RIVAL));
		blocksBroken = in.getIntOr("BlocksBroken", 0);
		blocksPlaced = in.getIntOr("BlocksPlaced", 0);
		spawnAnnounced = in.getBooleanOr("Announced", false);
		survival.load(in);
		if (in.getLong("CrossingPoint").isPresent()) {
			crossingPoint = BlockPos.of(in.getLongOr("CrossingPoint", 0L));
		}
		tracker.load(in);
	}
}
