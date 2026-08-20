package dev.tiltedlunar.hunted.tactics;

/**
 * Decides what to do, from what the hunter can see.
 *
 * <p>This is the layer that makes the difference between a mob and an opponent.
 * Without it the hunter runs a fixed script: gather until equipped, then walk at
 * you. That script is wrong in both directions. It gathers wood while you stand
 * there at two hearts holding nothing, and it charges you in full diamond
 * because the checklist said it was ready.
 *
 * <p>Everything below is one comparison, threat against readiness, with a short
 * list of cases that override it. The overrides are the interesting part: a
 * defenceless target is worth attacking regardless of your own gear, and a
 * dying one is worth finishing even at a disadvantage, because the alternative
 * is letting them eat and come back whole.
 *
 * <p>Pure and static, so every rule below is covered by tests rather than by
 * watching a mob and hoping.
 */
public final class Tactics {

	/** An opening further away than this will close before the hunter arrives. */
	private static final double RUSH_RANGE = 20.0D;

	/** How far behind on gear the hunter tolerates before going shopping. */
	private static final double GEAR_DEFICIT = 3.5D;

	/** A hunter this far ahead does not need an axe to deal with a shield. */
	private static final double SHIELD_TOLERANCE = 2.0D;

	/** Close enough that incoming damage is coming from this fight. */
	private static final double DEFEND_RANGE = 10.0D;

	/** How much each nearby friend devalues a target. */
	private static final double COMPANION_PENALTY = 3.5D;

	/** How much better a new target must look before the hunter turns around. */
	private static final double COMMITMENT_BONUS = 4.0D;

	/** Players this close to a candidate count as backing them up. */
	public static final double COMPANION_RANGE = 16.0D;

	private Tactics() {
	}

	/**
	 * A decision, with the reason attached.
	 *
	 * <p>The reason is not decoration. It goes into {@code /hunted status}, so
	 * when the hunter is doing something that looks wrong you can see what it
	 * thought it was doing.
	 */
	public record Plan(Tactic tactic, String reason) {
	}

	/**
	 * Picks a tactic.
	 *
	 * @param quarry         what the hunter believes about its target
	 * @param self           what the hunter has
	 * @param canGearUp      whether gathering is available, which survival mode decides
	 * @param retreatAllowed whether this tier is willing to break off at all
	 */
	public static Plan choose(Appraisal quarry, Readiness self,
			boolean canGearUp, boolean retreatAllowed) {
		double advantage = self.score() - quarry.threat();

		// About to be destroyed. This one ignores tier bravado entirely, because
		// standing your ground is a personality and standing there until you are
		// dead is just a bug. The only thing that outranks it is a target who
		// will die first.
		if (self.dying() && !quarry.nearlyDead()) {
			return new Plan(Tactic.WITHDRAW, "taking damage too fast, breaking off");
		}

		// Nearly dead. Gear stops mattering; letting them heal is the only way
		// to lose from here.
		if (quarry.nearlyDead()) {
			return new Plan(Tactic.PRESS, "target is nearly down, finishing it");
		}

		// Nothing to be afraid of. No amount of shopping improves on attacking
		// someone who cannot hit back.
		if (quarry.defenceless()) {
			return new Plan(Tactic.RUSH, "target is unarmed and unarmoured");
		}

		// A window, close enough to reach before it shuts.
		if (quarry.opening().actionable() && quarry.distance() <= RUSH_RANGE) {
			return new Plan(Tactic.RUSH, "caught them " + describe(quarry.opening()));
		}

		// The early flinch, for the tiers that flinch. Higher tiers fall past
		// this and tighten up instead.
		if (self.hurt() && retreatAllowed) {
			return new Plan(Tactic.WITHDRAW, "hurt, breaking off");
		}

		// Losing the exchange but not dying yet. Stop trading, put the shield
		// up, and wait for them to make a mistake instead of making one.
		if (self.pressured() && advantage < 0.0D && quarry.distance() <= DEFEND_RANGE) {
			return new Plan(Tactic.DEFEND, "losing the trade, going defensive");
		}

		// A shield beats a sword. An axe beats a shield.
		if (quarry.shield() && !self.hasAxe() && canGearUp && advantage < SHIELD_TOLERANCE) {
			return new Plan(Tactic.COUNTER_SHIELD, "they have a shield, going to make an axe");
		}

		// Meaningfully outgunned, and able to do something about it.
		if (advantage < -GEAR_DEFICIT && canGearUp) {
			return new Plan(Tactic.GEAR_UP, "outgeared, equipping first");
		}

		return new Plan(Tactic.ENGAGE, advantage >= 0.0D
				? "even or ahead, engaging"
				: "behind on gear but committing");
	}

	private static String describe(Opening opening) {
		return switch (opening) {
			case MINING -> "mid swing";
			case EATING -> "eating";
			case FALLING -> "in the air";
			case SWIMMING -> "in water";
			case UNARMED -> "empty handed";
			case DISTRACTED -> "looking away";
			case NONE -> "off guard";
		};
	}

	/**
	 * Scores a candidate for target selection, higher being a better target.
	 *
	 * <p>Used when more than one player is in play, and it is the part of the
	 * mod that most changes how a group experience feels. The hunter does not
	 * take whoever is nearest. It takes whoever is easiest, which means it will
	 * walk past someone in full iron to reach the one who fell in a hole.
	 *
	 * <p>Two terms matter more than the obvious ones.
	 *
	 * <p><b>Company.</b> A player standing with two armed friends is a far worse
	 * target than the same player alone, because attacking them starts a three
	 * on one. Every nearby companion pushes a candidate down the list, so the
	 * hunter naturally works the edges of a group and picks off stragglers
	 * rather than charging the middle of it.
	 *
	 * <p><b>Commitment.</b> The current target gets a bonus. Without it the
	 * hunter re-scores every few seconds, finds a candidate better by a tenth
	 * of a point, and spends the whole game turning around. The bonus is the
	 * margin by which a new target has to be genuinely better before switching
	 * is worth the walk.
	 *
	 * @param companions huntable players near this candidate, not counting them
	 * @param current    whether this is the target the hunter is already on
	 */
	public static double priority(Appraisal candidate, int companions, boolean current) {
		double weakness = 20.0D - candidate.threat();
		double closeness = 40.0D / (10.0D + candidate.distance());
		double finishable = candidate.nearlyDead() ? 8.0D : 0.0D;
		double exposed = candidate.opening().actionable() ? 4.0D : 0.0D;
		double isolation = -COMPANION_PENALTY * companions;
		double commitment = current ? COMMITMENT_BONUS : 0.0D;

		return weakness + closeness + finishable + exposed + isolation + commitment;
	}

	/** Scores a lone candidate the hunter is not already chasing. */
	public static double priority(Appraisal candidate) {
		return priority(candidate, 0, false);
	}
}
