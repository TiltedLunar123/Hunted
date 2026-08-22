package dev.tiltedlunar.hunted.tactics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tactical layer decides whether the hunter fights, shops, or runs. Getting
 * it wrong is not a crash, it is a mob that farms wood while you stand in front
 * of it at two hearts, so the rules are pinned here rather than eyeballed.
 */
class TacticsTest {

	private static final Readiness EMPTY_HANDED = Readiness.empty();

	private static final double SAFE = Double.POSITIVE_INFINITY;

	private static final Readiness IRON_ARMED =
			new Readiness(0, WeaponClass.IRON, false, 1.0D, false, SAFE);

	private static final Readiness IRON_WITH_AXE =
			new Readiness(0, WeaponClass.IRON, false, 1.0D, true, SAFE);

	private static Appraisal geared(double distance) {
		return new Appraisal(distance, 20, WeaponClass.IRON, true, 1.0D, true, Opening.NONE);
	}

	/** Geared, but with nothing in the off hand, so the shield rule stays out of it. */
	private static Appraisal gearedNoShield(double distance) {
		return new Appraisal(distance, 20, WeaponClass.IRON, false, 1.0D, true, Opening.NONE);
	}

	private static Appraisal helpless(double distance) {
		return new Appraisal(distance, 0, WeaponClass.NONE, false, 1.0D, false, Opening.NONE);
	}

	private static Tactic decide(Appraisal quarry, Readiness self, boolean canGearUp) {
		return Tactics.choose(quarry, self, canGearUp, true).tactic();
	}

	/** A hunter holding a stone sword and wearing nothing. */
	private static Readiness armed() {
		return new Readiness(0, WeaponClass.STONE, false, 1.0D, false,
				Double.POSITIVE_INFINITY);
	}

	@Test
	@DisplayName("attacks a defenceless target once it has something to attack with")
	void rushesTheDefenceless() {
		// With a weapon in hand there is nothing to gain by shopping first.
		assertEquals(Tactic.RUSH, decide(helpless(15.0D), armed(), true),
				"a target that cannot hit back is worth attacking immediately");

		// With nothing at all it fetches a sword rather than trading punches,
		// because fists against fists is a fight the runner can walk out of.
		assertEquals(Tactic.GEAR_UP, decide(helpless(15.0D), EMPTY_HANDED, true),
				"an empty handed hunter should arm itself first");

		// Unless there is nowhere to shop, in which case fists it is.
		assertEquals(Tactic.RUSH, decide(helpless(15.0D), EMPTY_HANDED, false),
				"with no economy available it has to charge anyway");
	}

	@Test
	@DisplayName("goes shopping when badly outgeared and able to")
	void gearsUpWhenOutclassed() {
		assertEquals(Tactic.GEAR_UP, decide(gearedNoShield(30.0D), EMPTY_HANDED, true));
	}

	@Test
	@DisplayName("a shield outranks a general gear deficit, because an axe answers it")
	void shieldTakesPriorityOverGeneralGearing() {
		// Both rules apply here. Going for the axe is the better call, and the
		// ladder gathers the same wood and stone on the way to it either way.
		assertEquals(Tactic.COUNTER_SHIELD, decide(geared(30.0D), EMPTY_HANDED, true));
	}

	@Test
	@DisplayName("commits anyway when it has no way to improve its odds")
	void engagesWhenGearingUpIsNotAnOption() {
		// Survival mode off. There is no shopping trip available, so standing
		// around comparing gear forever would be worse than attacking.
		assertEquals(Tactic.ENGAGE, decide(geared(30.0D), EMPTY_HANDED, false));
	}

	@Test
	@DisplayName("makes an axe when the target is hiding behind a shield")
	void countersAShield() {
		Appraisal shielded =
				new Appraisal(12.0D, 0, WeaponClass.WOOD, true, 1.0D, true, Opening.NONE);
		assertEquals(Tactic.COUNTER_SHIELD, decide(shielded, IRON_ARMED, true));
	}

	@Test
	@DisplayName("does not bother with an axe when it already has one")
	void skipsTheAxeTripWhenArmed() {
		Appraisal shielded =
				new Appraisal(12.0D, 0, WeaponClass.WOOD, true, 1.0D, true, Opening.NONE);
		assertNotEquals(Tactic.COUNTER_SHIELD, decide(shielded, IRON_WITH_AXE, true));
	}

	@Test
	@DisplayName("finishes a nearly dead target even while outgeared")
	void pressesTheAdvantageOnALowTarget() {
		Appraisal dying =
				new Appraisal(8.0D, 20, WeaponClass.NETHERITE, true, 0.15D, true, Opening.NONE);
		assertEquals(Tactic.PRESS, decide(dying, EMPTY_HANDED, true),
				"letting a dying player walk away to eat is how a won fight is lost");
	}

	@Test
	@DisplayName("breaks off when hurt, unless the target is about to die")
	void withdrawsWhenHurt() {
		Readiness bloodied = new Readiness(0, WeaponClass.IRON, false, 0.2D, false, SAFE);

		assertEquals(Tactic.WITHDRAW,
				Tactics.choose(geared(6.0D), bloodied, true, true).tactic());

		Appraisal dying =
				new Appraisal(6.0D, 20, WeaponClass.IRON, true, 0.1D, true, Opening.NONE);
		assertEquals(Tactic.PRESS,
				Tactics.choose(dying, bloodied, true, true).tactic(),
				"a dying target outranks its own health");
	}

	@Test
	@DisplayName("the top tiers never retreat")
	void refusesToRetreatWhenNotAllowed() {
		Readiness bloodied = new Readiness(0, WeaponClass.IRON, false, 0.2D, false, SAFE);
		assertNotEquals(Tactic.WITHDRAW,
				Tactics.choose(geared(6.0D), bloodied, true, false).tactic());
	}

	@Test
	@DisplayName("takes an opening that is close enough to reach")
	void takesNearbyOpenings() {
		Appraisal eating =
				new Appraisal(12.0D, 20, WeaponClass.IRON, false, 1.0D, true, Opening.EATING);
		assertEquals(Tactic.RUSH, decide(eating, EMPTY_HANDED, true));
	}

	@Test
	@DisplayName("ignores an opening it cannot reach in time")
	void ignoresDistantOpenings() {
		Appraisal eatingFarAway =
				new Appraisal(60.0D, 20, WeaponClass.IRON, false, 1.0D, true, Opening.EATING);
		assertNotEquals(Tactic.RUSH, decide(eatingFarAway, EMPTY_HANDED, true),
				"the window would shut long before it arrived");
	}

	@Test
	@DisplayName("a wounded target is scored as less dangerous, but only when visible")
	void healthOnlyCountsWhenKnown() {
		Appraisal knownHurt =
				new Appraisal(10.0D, 20, WeaponClass.IRON, true, 0.5D, true, Opening.NONE);
		Appraisal unknownHurt =
				new Appraisal(10.0D, 20, WeaponClass.IRON, true, 0.5D, false, Opening.NONE);

		assertTrue(knownHurt.threat() < unknownHurt.threat(),
				"seeing that they are hurt should lower the threat");
		assertEquals(geared(10.0D).threat(), unknownHurt.threat(), 1.0e-9D,
				"a tier that cannot see health must assume full health");
	}

	@Test
	@DisplayName("an opening lowers effective threat without making them harmless")
	void openingsReduceThreat() {
		Appraisal ready = geared(10.0D);
		Appraisal caught =
				new Appraisal(10.0D, 20, WeaponClass.IRON, true, 1.0D, true, Opening.EATING);

		assertTrue(caught.threat() < ready.threat());
		assertTrue(caught.threat() > 0.0D, "still armoured, still holding a sword");
	}

	@Test
	@DisplayName("picks the softer of two targets rather than the nearer one")
	void prefersTheWeakerTarget() {
		Appraisal softButFar = helpless(35.0D);
		Appraisal gearedAndClose = geared(6.0D);

		assertTrue(Tactics.priority(softButFar) > Tactics.priority(gearedAndClose),
				"it should walk past the geared player to reach the easy one");
	}

	@Test
	@DisplayName("prefers the straggler over the same player standing with friends")
	void avoidsPlayersWithBackup() {
		Appraisal alone = helpless(20.0D);
		Appraisal inAGroup = helpless(20.0D);

		assertTrue(Tactics.priority(alone, 0, false) > Tactics.priority(inAGroup, 2, false),
				"two friends nearby should make an identical target much less attractive");
	}

	@Test
	@DisplayName("will still take a soft straggler over a hard loner")
	void weaknessStillOutranksIsolation() {
		Appraisal softWithOneFriend = helpless(20.0D);
		Appraisal gearedAndAlone = geared(20.0D);

		assertTrue(Tactics.priority(softWithOneFriend, 1, false)
						> Tactics.priority(gearedAndAlone, 0, false),
				"one companion should not outweigh a full set of diamond");
	}

	@Test
	@DisplayName("sticks with its current target unless another is clearly better")
	void doesNotFlipFlopBetweenSimilarTargets() {
		Appraisal current = helpless(20.0D);
		Appraisal marginallyBetter = helpless(19.0D);

		assertTrue(Tactics.priority(current, 0, true)
						> Tactics.priority(marginallyBetter, 0, false),
				"a metre closer is not a reason to turn around");

		Appraisal muchBetter =
				new Appraisal(6.0D, 0, WeaponClass.NONE, false, 0.15D, true, Opening.EATING);
		assertTrue(Tactics.priority(muchBetter, 0, false)
						> Tactics.priority(current, 0, true),
				"a dying unarmed player caught eating is worth switching for");
	}

	@Test
	@DisplayName("a target about to die outranks an equally soft healthy one")
	void prefersFinishableTargets() {
		Appraisal healthy =
				new Appraisal(10.0D, 0, WeaponClass.WOOD, false, 1.0D, true, Opening.NONE);
		Appraisal dying =
				new Appraisal(10.0D, 0, WeaponClass.WOOD, false, 0.2D, true, Opening.NONE);

		assertTrue(Tactics.priority(dying) > Tactics.priority(healthy));
	}

	@Test
	@DisplayName("runs when it is dying fast, even on a tier that never retreats")
	void fleesImminentDeathRegardlessOfTier() {
		// Half health, but losing it fast enough to be gone in two seconds.
		Readiness bleedingOut = new Readiness(0, WeaponClass.IRON, false, 0.5D, false, 40.0D);

		assertEquals(Tactic.WITHDRAW,
				Tactics.choose(gearedNoShield(4.0D), bleedingOut, true, false).tactic(),
				"imminent death should override tier bravado");
	}

	@Test
	@DisplayName("a healthy hunter taking no damage does not panic at low health")
	void doesNotPanicWithoutIncomingDamage() {
		// Two hearts left, but nothing has touched it in a long time.
		Readiness scarred = new Readiness(0, WeaponClass.IRON, false, 0.1D, false, SAFE);
		assertNotEquals(Tactic.WITHDRAW,
				Tactics.choose(gearedNoShield(30.0D), scarred, true, false).tactic(),
				"an unfair tier with no incoming damage should keep going");
	}

	@Test
	@DisplayName("goes defensive when losing the exchange but not yet dying")
	void defendsWhenLosingTheTrade() {
		// Being worn down over about seven seconds, and behind on gear.
		Readiness losing = new Readiness(0, WeaponClass.STONE, false, 0.7D, true, 140.0D);
		assertEquals(Tactic.DEFEND,
				Tactics.choose(gearedNoShield(4.0D), losing, false, false).tactic());
	}

	@Test
	@DisplayName("does not go defensive against someone far away")
	void ignoresPressureFromOutOfRange() {
		Readiness losing = new Readiness(0, WeaponClass.STONE, false, 0.7D, true, 140.0D);
		assertNotEquals(Tactic.DEFEND,
				Tactics.choose(gearedNoShield(40.0D), losing, false, false).tactic(),
				"damage from something else is not a reason to circle a distant player");
	}

	@Test
	@DisplayName("every decision carries a reason worth showing a player")
	void everyPlanExplainsItself() {
		Tactics.Plan plan = Tactics.choose(geared(30.0D), EMPTY_HANDED, true, true);
		assertFalse(plan.reason().isBlank(), "status output would be empty");
		assertTrue(plan.tactic().isEconomy(), "gearing up is an economy tactic");
		assertFalse(plan.tactic().isCombat());
	}
}
