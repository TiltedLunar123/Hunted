package dev.tiltedlunar.hunted.path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A resumable A* search over block positions, with block breaking and block
 * placing priced into the cost function.
 *
 * <p>Two properties matter more than raw speed here.
 *
 * <p>The first is that the search is <em>resumable</em>. {@link #advance(int)}
 * expands a bounded number of nodes and returns, so a long path can be spread
 * over several ticks instead of freezing the server for one enormous one. The
 * caller decides how much time the hunter deserves.
 *
 * <p>The second is that it is <em>best effort</em>. If the budget runs out
 * before the goal is reached, the path to the most promising node found so far
 * is still returned. A hunter that walks most of the way and re-plans beats a
 * hunter that stands still because the perfect route was too expensive to
 * prove. That single behaviour is what makes long chases feel relentless.
 *
 * <p>The heuristic deliberately sums horizontal and vertical estimates rather
 * than taking their maximum. That makes it mildly inadmissible, so the result
 * is not provably the cheapest path, but it cuts the explored node count by
 * roughly an order of magnitude on real terrain. For a mob chasing a player,
 * arriving soon beats arriving optimally.
 */
public final class PathSearch {

	/** State of a search in progress. */
	public enum Status {
		/** Budget remains and the goal has not been reached. */
		RUNNING,
		/** A complete route to the goal was found. */
		SUCCESS,
		/** No complete route, but partial progress toward the goal is available. */
		PARTIAL,
		/** The hunter cannot move at all from where it stands. */
		FAILED
	}

	private static final double INF = Double.POSITIVE_INFINITY;

	private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
	private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
	private static final double SQRT2 = Math.sqrt(2.0D);

	private final WorldView view;
	private final PathProfile profile;
	private final long start;
	private final long goal;
	private final double goalRadius;
	private final int maxNodes;

	private final Map<Long, Node> nodes = new HashMap<>();
	private final PriorityQueue<Entry> open = new PriorityQueue<>();

	private long bestPos;
	private double bestHeuristic;
	private long resolved = PosCodec.NONE;
	private int expanded;
	private Status status = Status.RUNNING;

	public PathSearch(WorldView view, long start, long goal, double goalRadius,
			PathProfile profile, int maxNodes) {
		this.view = view;
		this.profile = profile;
		this.start = start;
		this.goal = goal;
		this.goalRadius = goalRadius;
		this.maxNodes = maxNodes;

		this.bestPos = start;
		this.bestHeuristic = heuristic(start);

		Node root = new Node();
		root.g = 0.0D;
		root.f = bestHeuristic;
		root.parent = PosCodec.NONE;
		root.kind = MoveKind.WALK;
		nodes.put(start, root);

		open.add(new Entry(start, root.f));
	}

	/** Total nodes expanded so far, across every call to {@link #advance}. */
	public int expanded() {
		return expanded;
	}

	public Status status() {
		return status;
	}

	/**
	 * Expands up to {@code budget} nodes.
	 *
	 * @return the status after this slice of work
	 */
	public Status advance(int budget) {
		if (status != Status.RUNNING) {
			return status;
		}

		for (int i = 0; i < budget; i++) {
			Entry entry = open.poll();
			if (entry == null) {
				status = expanded > 1 ? Status.PARTIAL : Status.FAILED;
				return status;
			}

			Node node = nodes.get(entry.pos);
			if (node == null || node.closed || entry.f > node.f + 1.0e-6D) {
				continue;
			}
			node.closed = true;
			expanded++;

			if (PosCodec.distance(entry.pos, goal) <= goalRadius) {
				resolved = entry.pos;
				status = Status.SUCCESS;
				return status;
			}

			double h = heuristic(entry.pos);
			if (h < bestHeuristic) {
				bestHeuristic = h;
				bestPos = entry.pos;
			}

			expand(entry.pos, node.g);

			if (nodes.size() > maxNodes) {
				status = Status.PARTIAL;
				return status;
			}
		}

		return status;
	}

	/**
	 * The best route currently known, from the position after the start to the
	 * end of the path. Empty when the hunter has nowhere to go.
	 */
	public List<PathStep> path() {
		long end = status == Status.SUCCESS && resolved != PosCodec.NONE ? resolved : bestPos;
		if (end == start) {
			return List.of();
		}

		List<PathStep> steps = new ArrayList<>();
		long cursor = end;
		while (cursor != start) {
			Node node = nodes.get(cursor);
			if (node == null || node.parent == PosCodec.NONE) {
				break;
			}
			Node parent = nodes.get(node.parent);
			double cost = parent == null ? node.g : node.g - parent.g;
			steps.add(new PathStep(cursor, node.kind, cost));
			cursor = node.parent;

			// Defensive: a corrupt parent chain must not hang the server.
			if (steps.size() > 4_096) {
				break;
			}
		}
		Collections.reverse(steps);
		return steps;
	}

	// -----------------------------------------------------------------
	// Search internals
	// -----------------------------------------------------------------

	private void expand(long current, double g) {
		int cx = PosCodec.x(current);
		int cy = PosCodec.y(current);
		int cz = PosCodec.z(current);

		BlockClass here = view.classify(cx, cy, cz);
		boolean inWater = here == BlockClass.WATER;
		boolean onClimbable = here == BlockClass.CLIMBABLE;
		double step = profile.stepCost() * (inWater ? PathProfile.SWIM_MULTIPLIER : 1.0D);

		for (int[] dir : CARDINALS) {
			horizontal(current, g, cx, cy, cz, dir[0], dir[1], step, inWater);
			parkour(current, g, cx, cy, cz, dir[0], dir[1]);
		}

		for (int[] dir : DIAGONALS) {
			diagonal(current, g, cx, cy, cz, dir[0], dir[1], step);
		}

		pillar(current, g, cx, cy, cz);
		digDown(current, g, cx, cy, cz);

		if (onClimbable || inWater) {
			vertical(current, g, cx, cy, cz, onClimbable);
		}
	}

	/** Level step, step up, step down, fall, and bridging, in one direction. */
	private void horizontal(long current, double g, int cx, int cy, int cz,
			int dx, int dz, double step, boolean inWater) {
		int tx = cx + dx;
		int tz = cz + dz;

		double body = bodyCost(tx, cy, tz);
		if (body < INF) {
			BlockClass below = view.classify(tx, cy - 1, tz);

			if (below.standable()) {
				MoveKind kind = inWater ? MoveKind.SWIM : MoveKind.WALK;
				relax(current, PosCodec.pack(tx, cy, tz), g + step + body, kind);
			} else if (below == BlockClass.WATER || view.classify(tx, cy, tz) == BlockClass.WATER) {
				relax(current, PosCodec.pack(tx, cy, tz),
						g + profile.stepCost() * PathProfile.SWIM_MULTIPLIER + body, MoveKind.SWIM);
			} else {
				fall(current, g, tx, cy, tz, step, body);
				if (profile.canBridge()) {
					relax(current, PosCodec.pack(tx, cy, tz),
							g + step + body + PathProfile.PLACE, MoveKind.BRIDGE);
				}
			}
		}

		// Step up one block.
		double headroom = clearCost(cx, cy + 2, cz);
		if (headroom < INF) {
			BlockClass landing = view.classify(tx, cy, tz);
			if (landing.standable()) {
				double upper = bodyCost(tx, cy + 1, tz);
				if (upper < INF) {
					relax(current, PosCodec.pack(tx, cy + 1, tz),
							g + step + PathProfile.JUMP_PENALTY + headroom + upper, MoveKind.ASCEND);
				}
			}
		}
	}

	/** Walks the column below a horizontal step to find where the hunter lands. */
	private void fall(long current, double g, int tx, int topY, int tz, double step, double entry) {
		int y = topY - 1;
		int dropped = 1;
		while (y > view.minY() && dropped <= profile.effectiveMaxFall()) {
			BlockClass at = view.classify(tx, y, tz);

			if (at == BlockClass.WATER) {
				relax(current, PosCodec.pack(tx, y, tz),
						g + step + entry + dropped * PathProfile.FALL_PER_BLOCK, MoveKind.FALL);
				return;
			}
			// Lava counts as passable for the body, which means without this the
			// search would happily plan a drop straight through a lava lake and
			// call the stone underneath it a landing.
			if (at == BlockClass.LAVA && !profile.fireImmune()) {
				return;
			}
			if (!at.passable()) {
				return;
			}
			if (view.classify(tx, y - 1, tz).standable()) {
				// A water bucket makes the landing free, so the only cost left is
				// the second or so spent placing and scooping it back up.
				double damage;
				if (profile.waterClutch()) {
					damage = dropped > PathProfile.SAFE_FALL ? PathProfile.CLUTCH_COST : 0.0D;
				} else {
					damage = dropped > PathProfile.SAFE_FALL
							? (dropped - PathProfile.SAFE_FALL) * PathProfile.FALL_DAMAGE_PENALTY
							: 0.0D;
				}
				MoveKind kind = dropped == 1 ? MoveKind.DESCEND : MoveKind.FALL;
				relax(current, PosCodec.pack(tx, y, tz),
						g + step + entry + dropped * PathProfile.FALL_PER_BLOCK + damage, kind);
				return;
			}
			y--;
			dropped++;
		}
	}

	/**
	 * Sprint jump across a gap.
	 *
	 * <p>Only considered when the block immediately ahead has nothing to stand
	 * on. Without that guard every node would sprout three extra successors per
	 * direction on open ground, which triples the branching factor to buy
	 * nothing: on flat terrain walking is already cheaper than jumping.
	 *
	 * <p>Landings one block lower get an extra block of reach, because the
	 * extra airtime is real. The planner will not attempt anything beyond that,
	 * since jumps that need a speed effect or frame perfect timing are exactly
	 * the ones that end with a hunter at the bottom of a ravine.
	 */
	private void parkour(long current, double g, int cx, int cy, int cz, int dx, int dz) {
		if (!profile.canParkour()) {
			return;
		}
		// Something to jump over, and room overhead to jump at all.
		if (view.classify(cx + dx, cy - 1, cz + dz).standable()) {
			return;
		}
		if (!view.classify(cx, cy + 2, cz).passable()) {
			return;
		}

		int maxGap = Math.max(profile.parkourGap(0), profile.parkourGap(1));
		for (int gap = 1; gap <= maxGap; gap++) {
			// Everything between here and the landing must be open at body height.
			if (!isClearAir(cx + dx * gap, cy, cz + dz * gap)) {
				return;
			}

			int distance = gap + 1;
			int tx = cx + dx * distance;
			int tz = cz + dz * distance;

			for (int drop = 0; drop <= 1; drop++) {
				if (gap > profile.parkourGap(drop)) {
					continue;
				}
				int ty = cy - drop;
				if (!view.fits(tx, ty, tz) || !view.classify(tx, ty - 1, tz).standable()) {
					continue;
				}
				double cost = g + profile.stepCost() * distance
						+ PathProfile.JUMP_PENALTY + PathProfile.PARKOUR_RISK;
				relax(current, PosCodec.pack(tx, ty, tz), cost, MoveKind.PARKOUR);
			}
		}
	}

	/** True when a body fits here and there is nothing underfoot to land on. */
	private boolean isClearAir(int x, int y, int z) {
		return view.fits(x, y, z) && !view.classify(x, y - 1, z).standable();
	}

	/** Diagonal step, refusing to cut through the corner between two blocks. */
	private void diagonal(long current, double g, int cx, int cy, int cz,
			int dx, int dz, double step) {
		if (!view.fits(cx + dx, cy, cz) || !view.fits(cx, cy, cz + dz)) {
			return;
		}
		int tx = cx + dx;
		int tz = cz + dz;
		if (!view.classify(tx, cy - 1, tz).standable()) {
			return;
		}
		double body = bodyCost(tx, cy, tz);
		if (body < INF) {
			relax(current, PosCodec.pack(tx, cy, tz), g + step * SQRT2 + body, MoveKind.DIAGONAL);
		}
	}

	/** Jump straight up and drop a block underfoot to stand on. */
	private void pillar(long current, double g, int cx, int cy, int cz) {
		if (!profile.canBridge()) {
			return;
		}
		double head = clearCost(cx, cy + 1, cz);
		double above = clearCost(cx, cy + 2, cz);
		if (head >= INF || above >= INF) {
			return;
		}
		if (!supportsAPillar(current, cx, cy, cz)) {
			return;
		}
		relax(current, PosCodec.pack(cx, cy + 1, cz),
				g + PathProfile.PLACE + PathProfile.JUMP_PENALTY + head + above, MoveKind.PILLAR);
	}

	/**
	 * Whether the hunter has something to jump off to start or continue a tower.
	 *
	 * <p>Either the ground is real, or this route already put a block there. The
	 * second half is the important one and was missing: a tower is built one
	 * block at a time, each standing on the one below, and the world knows
	 * nothing about blocks a route only intends to place. Checking the world
	 * alone meant a hunter could pillar exactly once and never again, so any
	 * time it ended up below its target, at the foot of a cliff or under the
	 * ledge it had just fallen off, no route back up existed and it stood there
	 * until the trail went cold.
	 */
	private boolean supportsAPillar(long current, int cx, int cy, int cz) {
		if (view.classify(cx, cy - 1, cz).standable()) {
			return true;
		}
		Node node = nodes.get(current);
		return node != null && node.kind == MoveKind.PILLAR;
	}

	/** Mine the floor and drop into the hole. */
	private void digDown(long current, double g, int cx, int cy, int cz) {
		if (!profile.canMine()) {
			return;
		}
		double cost = breakCost(cx, cy - 1, cz);
		if (cost < INF) {
			relax(current, PosCodec.pack(cx, cy - 1, cz),
					g + cost + PathProfile.FALL_PER_BLOCK, MoveKind.DIG_DOWN);
		}
	}

	/** Movement along a ladder, or up and down through water. */
	private void vertical(long current, double g, int cx, int cy, int cz, boolean climbing) {
		double cost = climbing
				? PathProfile.CLIMB_ONE
				: profile.stepCost() * PathProfile.SWIM_MULTIPLIER;
		MoveKind kind = climbing ? MoveKind.CLIMB : MoveKind.SWIM;

		double up = bodyCost(cx, cy + 1, cz);
		if (up < INF) {
			relax(current, PosCodec.pack(cx, cy + 1, cz), g + cost + up, kind);
		}

		double down = clearCost(cx, cy - 1, cz);
		if (down < INF) {
			relax(current, PosCodec.pack(cx, cy - 1, cz), g + cost + down, kind);
		}
	}

	private void relax(long from, long to, double g, MoveKind kind) {
		if (PosCodec.y(to) < view.minY() || PosCodec.y(to) >= view.maxY()) {
			return;
		}
		Node existing = nodes.get(to);
		if (existing != null && g >= existing.g - 1.0e-6D) {
			return;
		}

		Node node = existing == null ? new Node() : existing;
		node.g = g;
		node.parent = from;
		node.kind = kind;
		node.closed = false;
		node.f = g + heuristic(to);
		if (existing == null) {
			nodes.put(to, node);
		}
		open.add(new Entry(to, node.f));
	}

	/** Cost of making a single block enterable, in ticks. */
	private double clearCost(int x, int y, int z) {
		if (!view.isLoaded(x, y, z)) {
			return INF;
		}
		BlockClass type = view.classify(x, y, z);

		if (type.passable()) {
			if (type == BlockClass.LAVA) {
				return profile.fireImmune() ? 0.0D : PathProfile.LAVA_PENALTY;
			}
			if (type == BlockClass.HARMFUL) {
				return profile.fireImmune() ? 0.0D : PathProfile.HARMFUL_PENALTY;
			}
			return 0.0D;
		}

		if (type == BlockClass.DOOR && profile.canOpenDoors()) {
			return PathProfile.DOOR_OPEN;
		}

		return breakCost(x, y, z);
	}

	/** Cost of mining a block out of the way, or infinity if that is not allowed. */
	private double breakCost(int x, int y, int z) {
		if (!profile.canMine() || !view.isLoaded(x, y, z)) {
			return INF;
		}
		if (!view.classify(x, y, z).breakable()) {
			return INF;
		}
		float ticks = view.breakTicks(x, y, z);
		if (!Float.isFinite(ticks)) {
			return INF;
		}
		return ticks / Math.max(0.05D, profile.miningSpeed());
	}

	/** Cost of clearing both blocks a standing body occupies. */
	private double bodyCost(int x, int y, int z) {
		double feet = clearCost(x, y, z);
		if (feet >= INF) {
			return INF;
		}
		double head = clearCost(x, y + 1, z);
		if (head >= INF) {
			return INF;
		}
		return feet + head;
	}

	private double heuristic(long pos) {
		double dx = Math.abs(PosCodec.x(pos) - PosCodec.x(goal));
		double dz = Math.abs(PosCodec.z(pos) - PosCodec.z(goal));
		double dy = PosCodec.y(goal) - PosCodec.y(pos);

		double lo = Math.min(dx, dz);
		double hi = Math.max(dx, dz);
		double horizontal = (hi - lo) + lo * SQRT2;

		double vertical = dy > 0
				? dy * (PathProfile.SPRINT_ONE + PathProfile.JUMP_PENALTY)
				: -dy * PathProfile.FALL_PER_BLOCK;

		return horizontal * PathProfile.SPRINT_ONE + vertical;
	}

	private static final class Node {
		long parent = PosCodec.NONE;
		double g;
		double f;
		MoveKind kind = MoveKind.WALK;
		boolean closed;
	}

	private record Entry(long pos, double f) implements Comparable<Entry> {
		@Override
		public int compareTo(Entry other) {
			return Double.compare(f, other.f);
		}
	}
}
