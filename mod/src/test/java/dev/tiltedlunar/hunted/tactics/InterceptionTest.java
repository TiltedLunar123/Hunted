package dev.tiltedlunar.hunted.tactics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Interception is the difference between cutting someone off and trailing them
 * forever, and the maths is easy to get subtly wrong in a way that only shows
 * up as a hunter jogging into a wall. So it is pinned numerically.
 */
class InterceptionTest {

	private static final double TOLERANCE = 1.0e-6D;

	private static Interception.Point at(double x, double y, double z) {
		return new Interception.Point(x, y, z);
	}

	@Test
	@DisplayName("aims straight at a target that is not moving")
	void stationaryTargetNeedsNoLead() {
		Interception.Point target = at(10.0D, 0.0D, 0.0D);
		Interception.Point aim = Interception.aim(
				at(0.0D, 0.0D, 0.0D), target, at(0.0D, 0.0D, 0.0D), 0.25D, 1.0D);

		assertEquals(target, aim);
	}

	@Test
	@DisplayName("closes on a head on target at the combined speed")
	void headOnClosesAtCombinedSpeed() {
		// Ten blocks apart, target walking in at 0.2, hunter at 0.25.
		// Closing speed is 0.45, so they meet in 10 / 0.45 ticks.
		double time = Interception.solveTime(
				at(0.0D, 0.0D, 0.0D), at(10.0D, 0.0D, 0.0D), at(-0.2D, 0.0D, 0.0D), 0.25D);

		assertEquals(10.0D / 0.45D, time, 1.0e-4D);
	}

	@Test
	@DisplayName("leads a target crossing in front of it")
	void leadsACrossingTarget() {
		Interception.Point aim = Interception.aim(
				at(0.0D, 0.0D, 0.0D),
				at(10.0D, 0.0D, 0.0D),
				at(0.0D, 0.0D, 0.2D),
				0.25D,
				1.0D);

		// The solution is 66 ticks out, clamped to the 60 tick ceiling, so the
		// aim point sits 0.2 * 60 = 12 blocks along their heading.
		assertEquals(10.0D, aim.x(), TOLERANCE);
		assertEquals(12.0D, aim.z(), TOLERANCE);
	}

	@Test
	@DisplayName("gives up on leading a target it cannot catch")
	void refusesToLeadAnUncatchableTarget() {
		// Running directly away, faster than the hunter can move.
		Interception.Point target = at(10.0D, 0.0D, 0.0D);
		Interception.Point aim = Interception.aim(
				at(0.0D, 0.0D, 0.0D), target, at(0.3D, 0.0D, 0.0D), 0.25D, 1.0D);

		assertEquals(target, aim, "with no solution it should fall back to pure pursuit");
		assertTrue(Interception.solveTime(
						at(0.0D, 0.0D, 0.0D), target, at(0.3D, 0.0D, 0.0D), 0.25D) < 0.0D,
				"there is genuinely no interception here");
	}

	@Test
	@DisplayName("low confidence shortens the lead rather than removing it")
	void confidenceScalesTheLead() {
		Interception.Point hunter = at(0.0D, 0.0D, 0.0D);
		Interception.Point target = at(10.0D, 0.0D, 0.0D);
		Interception.Point velocity = at(0.0D, 0.0D, 0.2D);

		double sure = Interception.aim(hunter, target, velocity, 0.25D, 1.0D).z();
		double unsure = Interception.aim(hunter, target, velocity, 0.25D, 0.5D).z();

		assertEquals(sure / 2.0D, unsure, TOLERANCE);
		assertTrue(unsure > 0.0D, "a jinking target is still worth leading a little");
	}

	@Test
	@DisplayName("never aims further ahead than the ceiling allows")
	void clampsAbsurdLeads() {
		// Barely moving, so the solved interception time is enormous.
		Interception.Point aim = Interception.aim(
				at(0.0D, 0.0D, 0.0D),
				at(500.0D, 0.0D, 0.0D),
				at(0.0D, 0.0D, 0.03D),
				0.25D,
				1.0D);

		double lead = aim.z();
		assertTrue(lead <= 0.03D * Interception.MAX_LEAD_TICKS + TOLERANCE,
				"lead should be clamped, was " + lead);
	}

	@Test
	@DisplayName("a steady heading builds confidence and a reversal destroys it")
	void motionTrackerFollowsConsistency() {
		MotionTracker tracker = new MotionTracker();
		for (int i = 0; i < 40; i++) {
			tracker.observe(0.2D, 0.0D, 0.0D, 1);
		}

		assertTrue(tracker.confidence() > 0.8D,
				"forty ticks in a straight line should be trusted, was " + tracker.confidence());
		assertEquals(0.2D, tracker.horizontalSpeed(), 0.02D);

		double before = tracker.confidence();
		tracker.observe(-0.2D, 0.0D, 0.0D, 1);
		assertTrue(tracker.confidence() < before,
				"turning around should cost confidence immediately");
	}

	@Test
	@DisplayName("a tracker with no observations leads nothing")
	void freshTrackerIsNotTrusted() {
		MotionTracker tracker = new MotionTracker();
		assertEquals(0.0D, tracker.confidence(), TOLERANCE);
		assertEquals(0.0D, tracker.horizontalSpeed(), TOLERANCE);
	}
}
