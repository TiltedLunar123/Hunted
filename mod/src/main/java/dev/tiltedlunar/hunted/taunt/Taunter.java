package dev.tiltedlunar.hunted.taunt;

import dev.tiltedlunar.hunted.HuntedConfig;
import dev.tiltedlunar.hunted.tactics.Tactic;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;

/**
 * Decides when the hunter speaks.
 *
 * <p>Cadence matters far more than the writing does. A line that lands the
 * moment something changes is unsettling. The same line on a timer is noise,
 * and a player mutes it within a minute, so the rules here are strict about
 * staying quiet.
 *
 * <ul>
 *   <li>It speaks on <em>change</em>, not on a schedule. Deciding to rush you
 *       is worth a line; continuing to rush you is not.</li>
 *   <li>A hard floor between any two lines, whatever happened.</li>
 *   <li>An ambient line only after a long silence, so a quiet chase is mostly
 *       quiet.</li>
 *   <li>It never repeats the line it just used.</li>
 * </ul>
 */
public final class Taunter {

	/** Hard floor between any two lines. Eight seconds. */
	private static final int FLOOR = 160;

	/** Silence required before an ambient line. Forty seconds. */
	private static final int AMBIENT_GAP = 800;

	/** Health fraction below which the target counts as nearly finished. */
	private static final float LOW_HEALTH = 0.3f;

	private String lastLine = "";
	private int cooldown;
	private int silence;
	private Tactic lastTactic;
	private boolean announcedLowHealth;

	/**
	 * Runs one tick of commentary.
	 *
	 * @param tactic  the hunter's current decision
	 * @param working what the economy is doing, or null when it is not gathering
	 */
	public void tick(ServerPlayer target, RandomSource random, Tactic tactic,
			String working, float targetHealthFraction) {
		if (!HuntedConfig.get().taunts() || target == null) {
			return;
		}

		if (cooldown > 0) {
			cooldown--;
		}
		silence++;

		// The target dropping low is worth interrupting almost anything for.
		if (targetHealthFraction <= LOW_HEALTH && !announcedLowHealth) {
			announcedLowHealth = true;
			if (speak(target, random, Taunts.TARGET_LOW)) {
				return;
			}
		}
		if (targetHealthFraction > LOW_HEALTH + 0.15f) {
			announcedLowHealth = false;
		}

		Taunts category = categoryFor(tactic, working);

		if (tactic != lastTactic) {
			lastTactic = tactic;
			if (speak(target, random, category)) {
				return;
			}
		}

		if (silence >= AMBIENT_GAP) {
			speak(target, random, category == Taunts.COMBAT ? Taunts.IDLE : category);
		}
	}

	/** Says something regardless of the tactic, still respecting the floor. */
	public boolean speak(ServerPlayer target, RandomSource random, Taunts category) {
		if (!HuntedConfig.get().taunts() || target == null || cooldown > 0) {
			return false;
		}

		String line = category.pick(random, lastLine);
		if (line.isEmpty()) {
			return false;
		}

		lastLine = line;
		cooldown = FLOOR;
		silence = 0;

		target.sendSystemMessage(Component.empty()
				.append(Component.literal("[Hunter] ").withStyle(ChatFormatting.DARK_RED))
				.append(Component.literal(line).withStyle(ChatFormatting.GRAY)));
		return true;
	}

	/** Bypasses the cooldown. Used for the moments that should always land. */
	public void announce(ServerPlayer target, RandomSource random, Taunts category) {
		cooldown = 0;
		speak(target, random, category);
	}

	/** Maps the current decision onto something to say about it. */
	private Taunts categoryFor(Tactic tactic, String working) {
		if (tactic.isEconomy()) {
			return working != null && working.startsWith("crafting")
					? Taunts.CRAFTING
					: Taunts.GATHERING;
		}
		return switch (tactic) {
			case RUSH, PRESS -> Taunts.CLOSING;
			case ENGAGE -> Taunts.COMBAT;
			case DEFEND, WITHDRAW -> Taunts.WITHDRAW;
			case COUNTER_SHIELD, GEAR_UP -> Taunts.GATHERING;
		};
	}

	/** Called when the hunter has no idea where its target is. */
	public void lostTrail(ServerPlayer target, RandomSource random) {
		if (lastTactic != null) {
			lastTactic = null;
		}
		speak(target, random, Taunts.SEARCHING);
	}

	public void reset() {
		lastTactic = null;
		announcedLowHealth = false;
	}
}
