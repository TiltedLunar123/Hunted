package dev.tiltedlunar.hunted.tactics;

/** What the hunter has decided to do about the person it is hunting. */
public enum Tactic {

	/** Close the distance now. There is a window and it will not last. */
	RUSH,

	/** They are nearly dead. Commit, and do not break off for anything. */
	PRESS,

	/** Fight normally, at range, with the usual caution. */
	ENGAGE,

	/**
	 * Losing the exchange but not yet in danger of dying. Shield up, stay at
	 * arm's length, and only swing on an opening rather than trading blows.
	 */
	DEFEND,

	/** They are behind a shield. Go and make an axe before trying again. */
	COUNTER_SHIELD,

	/** They outclass us. Go and get equipment first. */
	GEAR_UP,

	/** Too hurt to trade. Break off and recover. */
	WITHDRAW;

	/** Whether this tactic means fighting rather than shopping. */
	public boolean isCombat() {
		return this == RUSH || this == PRESS || this == ENGAGE || this == DEFEND;
	}

	/** Whether this tactic sends the hunter off to gather and craft. */
	public boolean isEconomy() {
		return this == GEAR_UP || this == COUNTER_SHIELD;
	}
}
