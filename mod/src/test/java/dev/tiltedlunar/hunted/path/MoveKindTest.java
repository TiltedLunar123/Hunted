package dev.tiltedlunar.hunted.path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the questions the follower asks about each kind of move.
 *
 * <p>Small, but worth having. Getting {@link MoveKind#descends()} wrong does
 * not throw or fail to compile, it just makes the follower tick past every step
 * of a shaft without breaking any of it, and the hunter ends up steering at a
 * block several metres under its feet.
 */
class MoveKindTest {

	@Test
	@DisplayName("only the moves that end lower down count as descending")
	void descendingMoves() {
		assertTrue(MoveKind.DESCEND.descends());
		assertTrue(MoveKind.FALL.descends());
		assertTrue(MoveKind.DIG_DOWN.descends());

		assertFalse(MoveKind.WALK.descends());
		assertFalse(MoveKind.ASCEND.descends(), "going up is not going down");
		assertFalse(MoveKind.PILLAR.descends());
		assertFalse(MoveKind.BRIDGE.descends());
		assertFalse(MoveKind.PARKOUR.descends());
		assertFalse(MoveKind.CLIMB.descends());
		assertFalse(MoveKind.SWIM.descends());
	}

	@Test
	@DisplayName("a parkour jump is the only move that has to be run at")
	void sprintingMoves() {
		assertTrue(MoveKind.PARKOUR.needsSprint());
		for (MoveKind kind : MoveKind.values()) {
			if (kind != MoveKind.PARKOUR) {
				assertFalse(kind.needsSprint(), kind + " should not need a run up");
			}
		}
	}

	@Test
	@DisplayName("jumping and descending never both apply to one move")
	void jumpAndDescendAreExclusive() {
		for (MoveKind kind : MoveKind.values()) {
			assertFalse(kind.needsJump() && kind.descends(),
					kind + " cannot both jump and drop");
		}
	}

	@Test
	@DisplayName("every kind is accounted for by exactly one of the three questions or none")
	void noKindIsForgotten() {
		int classified = 0;
		for (MoveKind kind : MoveKind.values()) {
			if (kind.needsJump() || kind.descends() || kind.needsSprint()) {
				classified++;
			}
		}
		// WALK, DIAGONAL, BRIDGE, SWIM and CLIMB are all plain moves.
		assertEquals(MoveKind.values().length - 5, classified,
				"a new move kind was added without deciding how the follower treats it");
	}
}
