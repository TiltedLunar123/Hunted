package dev.tiltedlunar.hunted.path;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathSearchTest {

	//                              mine   bridge doors  parkour sprint fire   water  speed  fall
	private static final PathProfile WALKER =
			new PathProfile(false, false, false, false, true, false, false, 1.0D, 4);
	private static final PathProfile MINER =
			new PathProfile(true, false, false, false, true, false, false, 1.0D, 4);
	private static final PathProfile BUILDER =
			new PathProfile(true, true, true, false, true, false, false, 1.0D, 20);
	private static final PathProfile ATHLETE =
			new PathProfile(false, false, false, true, true, false, false, 1.0D, 4);
	/** Carries a water bucket, so long drops stop being a problem. */
	private static final PathProfile CLUTCHER =
			new PathProfile(false, false, false, false, true, false, true, 1.0D, 4);

	private static PathSearch search(WorldView view, long start, long goal, PathProfile profile) {
		return new PathSearch(view, start, goal, 0.5D, profile, 200_000);
	}

	private static List<PathStep> solve(WorldView view, long start, long goal,
			PathProfile profile, PathSearch.Status expected) {
		PathSearch search = search(view, start, goal, profile);
		PathSearch.Status status = PathSearch.Status.RUNNING;
		for (int i = 0; i < 400 && status == PathSearch.Status.RUNNING; i++) {
			status = search.advance(1_000);
		}
		assertSame(expected, status, "unexpected search outcome");
		return search.path();
	}

	@Test
	@DisplayName("position packing round trips across the full coordinate range")
	void positionPackingRoundTrips() {
		int[][] samples = {
				{0, 0, 0}, {1, 2, 3}, {-1, -2, -3},
				{30_000_000, 2_000, 30_000_000},
				{-30_000_000, -2_048, -30_000_000},
				{1_234_567, 319, -7_654_321}
		};
		for (int[] s : samples) {
			long packed = PosCodec.pack(s[0], s[1], s[2]);
			assertEquals(s[0], PosCodec.x(packed), "x");
			assertEquals(s[1], PosCodec.y(packed), "y");
			assertEquals(s[2], PosCodec.z(packed), "z");
		}
	}

	@Test
	@DisplayName("walks a straight line across flat ground")
	void walksStraightLine() {
		GridWorld world = GridWorld.flat(16, 3, 15.0f);
		long start = PosCodec.pack(1, 1, 1);
		long goal = PosCodec.pack(9, 1, 1);

		List<PathStep> path = solve(world, start, goal, WALKER, PathSearch.Status.SUCCESS);

		assertFalse(path.isEmpty(), "expected a path");
		PathStep last = path.get(path.size() - 1);
		assertEquals(9, last.x());
		assertEquals(1, last.z());
		// Eight blocks of travel should not need more than eight moves.
		assertTrue(path.size() <= 8, "path was longer than necessary: " + path.size());
	}

	@Test
	@DisplayName("routes around a wall when it cannot mine")
	void routesAroundWallWithoutMining() {
		// A wall along x = 4 with a gap at the far edge (z = 7).
		GridWorld world = GridWorld.of(15.0f,
				"""
				SSSSSSSS
				SSSSSSSS
				SSSSSSSS
				SSSSSSSS
				SSSSSSSS
				SSSSSSSS
				SSSSSSSS
				SSSSSSSS
				""",
				"""
				....S...
				....S...
				....S...
				....S...
				....S...
				....S...
				....S...
				........
				""",
				"""
				....S...
				....S...
				....S...
				....S...
				....S...
				....S...
				....S...
				........
				"""
		);

		long start = PosCodec.pack(1, 1, 1);
		long goal = PosCodec.pack(6, 1, 1);

		List<PathStep> path = solve(world, start, goal, WALKER, PathSearch.Status.SUCCESS);

		assertFalse(path.isEmpty());
		// It has to detour to z = 7 to get past the wall, so every step must
		// avoid the wall column entirely.
		for (PathStep step : path) {
			boolean throughWall = step.x() == 4 && step.z() != 7;
			assertFalse(throughWall, "walked through the wall at " + step);
		}
		assertTrue(path.size() >= 12, "detour was implausibly short: " + path.size());
	}

	@Test
	@DisplayName("mines through a wall when going around costs more")
	void minesThroughWallWhenCheaper() {
		GridWorld world = GridWorld.of(4.0f,
				"""
				SSSSSSSS
				SSSSSSSS
				SSSSSSSS
				SSSSSSSS
				SSSSSSSS
				SSSSSSSS
				SSSSSSSS
				SSSSSSSS
				""",
				"""
				....S...
				....S...
				....S...
				....S...
				....S...
				....S...
				....S...
				........
				""",
				"""
				....S...
				....S...
				....S...
				....S...
				....S...
				....S...
				....S...
				........
				"""
		);

		long start = PosCodec.pack(1, 1, 1);
		long goal = PosCodec.pack(6, 1, 1);

		List<PathStep> path = solve(world, start, goal, MINER, PathSearch.Status.SUCCESS);

		boolean brokeThrough = path.stream().anyMatch(s -> s.x() == 4 && s.z() == 1);
		assertTrue(brokeThrough, "expected the miner to tunnel straight through the wall");
		assertTrue(path.size() <= 8, "tunnelling path should be short: " + path.size());
	}

	@Test
	@DisplayName("climbs a step and drops off the other side")
	void climbsAndDescends() {
		GridWorld world = GridWorld.of(15.0f,
				"""
				SSSSSS
				SSSSSS
				SSSSSS
				""",
				"""
				..SS..
				..SS..
				..SS..
				""",
				"""
				......
				......
				......
				""",
				"""
				......
				......
				......
				"""
		);

		long start = PosCodec.pack(0, 1, 1);
		long goal = PosCodec.pack(5, 1, 1);

		List<PathStep> path = solve(world, start, goal, WALKER, PathSearch.Status.SUCCESS);

		assertTrue(path.stream().anyMatch(s -> s.kind() == MoveKind.ASCEND),
				"expected an ascend onto the ledge");
		assertTrue(path.stream().anyMatch(
						s -> s.kind() == MoveKind.DESCEND || s.kind() == MoveKind.FALL),
				"expected a descent off the ledge");
		assertEquals(5, path.get(path.size() - 1).x());
	}

	@Test
	@DisplayName("bridges across a void it cannot walk around")
	void bridgesAcrossGap() {
		// A canyon at x = 3 and x = 4 that runs the full width of the map.
		GridWorld world = GridWorld.of(15.0f,
				"""
				SSS..SSS
				SSS..SSS
				SSS..SSS
				""",
				"""
				........
				........
				........
				""",
				"""
				........
				........
				........
				"""
		);

		long start = PosCodec.pack(1, 1, 1);
		long goal = PosCodec.pack(6, 1, 1);

		List<PathStep> walker = solve(world, start, goal, WALKER, PathSearch.Status.PARTIAL);
		assertTrue(walker.stream().noneMatch(s -> s.x() >= 5),
				"a walker with no blocks should never reach the far side");

		List<PathStep> builder = solve(world, start, goal, BUILDER, PathSearch.Status.SUCCESS);
		assertTrue(builder.stream().anyMatch(s -> s.kind() == MoveKind.BRIDGE),
				"expected the builder to bridge the canyon");
		assertEquals(6, builder.get(builder.size() - 1).x());
	}

	@Test
	@DisplayName("towers up out of a pit, one placed block standing on the last")
	void pillarsOutOfAPit() {
		// A shaft four deep at x = 1. The only way out is straight up, which
		// means pillaring off blocks that do not exist until this route places
		// them. Checking the world for support allowed exactly one, and the
		// hunter sat at the bottom of every hole it ever fell into.
		GridWorld world = GridWorld.of(15.0f,
				"""
				SSSSS
				SSSSS
				SSSSS
				""",
				"""
				S.SSS
				S.SSS
				S.SSS
				""",
				"""
				S.SSS
				S.SSS
				S.SSS
				""",
				"""
				S.SSS
				S.SSS
				S.SSS
				""",
				"""
				.....
				.....
				.....
				""",
				"""
				.....
				.....
				.....
				"""
		);

		long start = PosCodec.pack(1, 1, 1);
		long goal = PosCodec.pack(3, 5, 1);

		List<PathStep> walker = solve(world, start, goal, WALKER, PathSearch.Status.PARTIAL);
		assertTrue(walker.stream().noneMatch(s -> s.y() >= 5),
				"with no blocks to place there is no way out of the shaft");

		List<PathStep> builder = solve(world, start, goal, BUILDER, PathSearch.Status.SUCCESS);
		long towers = builder.stream().filter(s -> s.kind() == MoveKind.PILLAR).count();
		assertTrue(towers >= 2,
				"one pillar is not a tower, and one is all it could ever manage: got " + towers);
		assertEquals(5, builder.get(builder.size() - 1).y());
	}

	@Test
	@DisplayName("sprint jumps a three block gap that a walker cannot cross")
	void parkoursAcrossGap() {
		// Three blocks of air at x = 3, 4, 5.
		GridWorld world = GridWorld.of(15.0f,
				"""
				SSS...SSS
				SSS...SSS
				SSS...SSS
				""",
				"""
				.........
				.........
				.........
				""",
				"""
				.........
				.........
				.........
				"""
		);

		long start = PosCodec.pack(1, 1, 1);
		long goal = PosCodec.pack(7, 1, 1);

		List<PathStep> athlete = solve(world, start, goal, ATHLETE, PathSearch.Status.SUCCESS);
		assertTrue(athlete.stream().anyMatch(s -> s.kind() == MoveKind.PARKOUR),
				"expected a running jump across the gap");
		assertEquals(7, athlete.get(athlete.size() - 1).x());

		List<PathStep> walker = solve(world, start, goal, WALKER, PathSearch.Status.PARTIAL);
		assertTrue(walker.stream().noneMatch(s -> s.x() >= 6),
				"a hunter that cannot jump should never reach the far side");
	}

	@Test
	@DisplayName("refuses a gap wider than a sprint jump can clear")
	void refusesImpossibleJump() {
		// Five blocks of air. No amount of running makes this one.
		GridWorld world = GridWorld.of(15.0f,
				"""
				SSS.....SS
				SSS.....SS
				SSS.....SS
				""",
				"""
				..........
				..........
				..........
				""",
				"""
				..........
				..........
				..........
				"""
		);

		long start = PosCodec.pack(1, 1, 1);
		long goal = PosCodec.pack(8, 1, 1);

		List<PathStep> path = solve(world, start, goal, ATHLETE, PathSearch.Status.PARTIAL);
		assertTrue(path.stream().noneMatch(s -> s.x() >= 8),
				"the planner talked itself into a jump it cannot land");
	}

	@Test
	@DisplayName("a water bucket turns a lethal drop into a shortcut")
	void waterBucketMakesLongFallsViable() {
		// A pillar at x = 0 and 1 rising eight blocks above open ground.
		String ground = """
				SSSSSSSS
				SSSSSSSS
				SSSSSSSS
				""";
		String pillar = """
				SS......
				SS......
				SS......
				""";
		String air = """
				........
				........
				........
				""";

		GridWorld world = GridWorld.of(15.0f,
				ground, pillar, pillar, pillar, pillar, pillar, pillar, pillar, pillar, air);

		long start = PosCodec.pack(1, 9, 1);
		long goal = PosCodec.pack(5, 1, 1);

		// Eight blocks is far past what anything survives unaided, so a hunter
		// without a bucket is stuck on top of the pillar.
		List<PathStep> stranded = solve(world, start, goal, WALKER, PathSearch.Status.PARTIAL);
		assertTrue(stranded.stream().noneMatch(s -> s.y() <= 2),
				"a walker should not talk itself into an eight block drop");

		List<PathStep> clutched = solve(world, start, goal, CLUTCHER, PathSearch.Status.SUCCESS);
		assertTrue(clutched.stream().anyMatch(s -> s.kind() == MoveKind.FALL),
				"expected it to simply step off the edge");
		assertEquals(5, clutched.get(clutched.size() - 1).x());
	}

	@Test
	@DisplayName("refuses to drop into a lava pit even though there is stone under it")
	void neverFallsThroughLava() {
		// A pit at x = 3 and 4 with lava in it. Lava is passable to a falling
		// body, so a naive search reads the stone beneath it as a landing and
		// routes straight through the lava to reach it.
		GridWorld world = GridWorld.of(15.0f,
				"""
				SSSSSSSS
				SSSSSSSS
				SSSSSSSS
				""",
				"""
				SSSLLSSS
				SSSLLSSS
				SSSLLSSS
				""",
				"""
				SSS..SSS
				SSS..SSS
				SSS..SSS
				""",
				"""
				........
				........
				........
				"""
		);

		long start = PosCodec.pack(1, 3, 1);
		long goal = PosCodec.pack(6, 3, 1);

		List<PathStep> path = solve(world, start, goal, WALKER, PathSearch.Status.PARTIAL);

		for (PathStep step : path) {
			boolean inThePit = (step.x() == 3 || step.x() == 4) && step.y() <= 2;
			assertFalse(inThePit, "planned a step into the lava pit: " + step);
		}
	}

	@Test
	@DisplayName("a water bucket does not tempt it into a lava pit")
	void bucketDoesNotOverrideLava() {
		GridWorld world = GridWorld.of(15.0f,
				"""
				SSSSSSSS
				SSSSSSSS
				SSSSSSSS
				""",
				"""
				SSSLLSSS
				SSSLLSSS
				SSSLLSSS
				""",
				"""
				SSS..SSS
				SSS..SSS
				SSS..SSS
				""",
				"""
				........
				........
				........
				"""
		);

		List<PathStep> path = solve(world, PosCodec.pack(1, 3, 1), PosCodec.pack(6, 3, 1),
				CLUTCHER, PathSearch.Status.PARTIAL);

		for (PathStep step : path) {
			boolean inThePit = (step.x() == 3 || step.x() == 4) && step.y() <= 2;
			assertFalse(inThePit, "water does not help with lava: " + step);
		}
	}

	@Test
	@DisplayName("avoids lava when a dry route exists")
	void avoidsLava() {
		GridWorld world = GridWorld.of(15.0f,
				"""
				SSSSSSS
				SSSSSSS
				SSSSSSS
				""",
				"""
				...L...
				.......
				...L...
				""",
				"""
				.......
				.......
				.......
				"""
		);

		long start = PosCodec.pack(0, 1, 0);
		long goal = PosCodec.pack(6, 1, 0);

		List<PathStep> path = solve(world, start, goal, WALKER, PathSearch.Status.SUCCESS);

		assertTrue(path.stream().noneMatch(s -> s.x() == 3 && s.y() == 1 && s.z() != 1),
				"walked into lava despite a dry detour being available");
	}

	@Test
	@DisplayName("reports failure when sealed inside bedrock")
	void failsWhenFullyEnclosed() {
		GridWorld world = GridWorld.of(15.0f,
				"""
				###
				###
				###
				""",
				"""
				###
				#.#
				###
				""",
				"""
				###
				#.#
				###
				""",
				"""
				###
				###
				###
				"""
		);

		long start = PosCodec.pack(1, 1, 1);
		long goal = PosCodec.pack(40, 1, 40);

		List<PathStep> path = solve(world, start, goal, BUILDER, PathSearch.Status.FAILED);
		assertTrue(path.isEmpty(), "a sealed hunter has nowhere to go");
	}

	@Test
	@DisplayName("returns partial progress when the node budget runs out")
	void returnsPartialProgressOnBudgetExhaustion() {
		GridWorld world = GridWorld.flat(64, 3, 15.0f);
		long start = PosCodec.pack(1, 1, 1);
		long goal = PosCodec.pack(60, 1, 60);

		PathSearch search = new PathSearch(world, start, goal, 0.5D, WALKER, 60);
		PathSearch.Status status = PathSearch.Status.RUNNING;
		for (int i = 0; i < 50 && status == PathSearch.Status.RUNNING; i++) {
			status = search.advance(16);
		}

		assertSame(PathSearch.Status.PARTIAL, status);
		List<PathStep> path = search.path();
		assertFalse(path.isEmpty(), "a partial search should still hand back progress");

		PathStep last = path.get(path.size() - 1);
		double before = PosCodec.distance(start, goal);
		double after = PosCodec.distance(last.pos(), goal);
		assertTrue(after < before, "partial path should close distance to the goal");
	}

	@Test
	@DisplayName("advancing a finished search is a no-op")
	void finishedSearchIsStable() {
		GridWorld world = GridWorld.flat(16, 3, 15.0f);
		long start = PosCodec.pack(1, 1, 1);
		long goal = PosCodec.pack(5, 1, 1);

		PathSearch search = search(world, start, goal, WALKER);
		while (search.advance(500) == PathSearch.Status.RUNNING) {
			// Run to completion.
		}
		assertSame(PathSearch.Status.SUCCESS, search.status());

		int expandedBefore = search.expanded();
		List<PathStep> pathBefore = search.path();
		assertSame(PathSearch.Status.SUCCESS, search.advance(500));
		assertEquals(expandedBefore, search.expanded(), "a finished search must not keep working");
		assertEquals(pathBefore, search.path(), "a finished path must be stable");
	}

	@Test
	@DisplayName("a closer goal is never more expensive than a distant one")
	void nearerGoalsAreCheaper() {
		GridWorld world = GridWorld.flat(32, 3, 15.0f);
		long start = PosCodec.pack(1, 1, 1);

		List<PathStep> near = solve(world, start, PosCodec.pack(5, 1, 1),
				WALKER, PathSearch.Status.SUCCESS);
		List<PathStep> far = solve(world, start, PosCodec.pack(20, 1, 1),
				WALKER, PathSearch.Status.SUCCESS);

		assertTrue(near.size() < far.size(), "nearer goal produced a longer path");
		assertNotEquals(0, far.size());
	}
}
