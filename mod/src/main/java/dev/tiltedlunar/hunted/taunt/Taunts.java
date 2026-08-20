package dev.tiltedlunar.hunted.taunt;

import java.util.List;

import net.minecraft.util.RandomSource;

/**
 * What the hunter says, and when.
 *
 * <p>The voice is deliberately flat. Something that shouts threats reads as a
 * cartoon. Something that states what is about to happen, in the tone you
 * would use for the weather, is far worse to be chased by. Nothing here has an
 * exclamation mark, and nothing is funny on purpose.
 *
 * <p>Lines stay under 55 characters so they fit one line of chat, and every
 * category holds twelve so a long chase does not start repeating audibly.
 */
public enum Taunts {

	/** Just created, and it already knows where you are. */
	SPAWN(
			"I exist now.",
			"I have your location.",
			"You are already known.",
			"I am born pointed at you.",
			"Your place arrives with me.",
			"I do not need to look first.",
			"The first fact I hold is you.",
			"You are the reason I am here.",
			"Nothing else comes before this.",
			"Distance does not matter yet.",
			"I start with you.",
			"You are known to me the moment I first come to exist."
	),

	/** Chopping or mining, in no hurry at all. */
	GATHERING(
			"I keep cutting.",
			"The ore comes slowly.",
			"I work without hurry.",
			"Each block goes in.",
			"There is no rush in this.",
			"This vein is useful.",
			"I cut until I have enough.",
			"The forest feeds my pack.",
			"I fill my hands before I move.",
			"Nothing here is wasted.",
			"Stone stacks beside me.",
			"I take from the ground all I need and then I come."
	),

	/** Making something to kill you with. */
	CRAFTING(
			"I make this.",
			"The blade is almost ready.",
			"Iron becomes a problem for you.",
			"I fit the plates now.",
			"I craft in silence.",
			"Armour first. Then I come.",
			"I finish this before I leave.",
			"This metal has a purpose.",
			"The anvil holds for me.",
			"A weapon takes shape.",
			"I sit at this table and I make something meant for you.",
			"This edge is for you."
	),

	/** The trail went cold and it is looking. */
	SEARCHING(
			"You are quiet.",
			"The trail goes thin.",
			"I scan the empty dark.",
			"This way or the other.",
			"I turn until it lines up.",
			"Not gone. Just out of sight.",
			"I search the same dirt twice.",
			"I reacquire you.",
			"I cast around for you.",
			"Wrong ridge. I correct.",
			"I do not see you now but I still have the hunt.",
			"The scent thins here."
	),

	/** It has seen you and it is running. */
	CLOSING(
			"There you are.",
			"I have you again.",
			"I close on you now.",
			"You are in the open.",
			"I run because I can.",
			"I choose speed.",
			"You look back too late.",
			"Straight at you.",
			"I have you in view.",
			"You are too slow now.",
			"I see you clearly now and I do not slow at all.",
			"The bank does not save you."
	),

	/** Mid fight. */
	COMBAT(
			"This one lands.",
			"I swing at your side.",
			"You are in range.",
			"Again. Same place.",
			"I do not miss by much.",
			"Stay right there.",
			"My reach is enough.",
			"You flinch. I follow.",
			"I strike again.",
			"I keep the pressure on you.",
			"I hit you here and I hit you there and I stay.",
			"The blow is true."
	),

	/** You are blocking and it is about to break your guard. */
	SHIELD_BREAK(
			"Your guard thins.",
			"I beat on that rim.",
			"The board splits under this.",
			"You cannot hold this.",
			"I work at the break.",
			"Raise it. I remain.",
			"The block costs you more than me.",
			"There is almost no shield.",
			"I aim where you brace.",
			"Keep blocking. I persist.",
			"You put that shield up and I take it apart for you.",
			"Your arms shake."
	),

	/** You are nearly dead. */
	TARGET_LOW(
			"You are emptying.",
			"You have almost nothing left.",
			"One heart remains.",
			"Stay on your feet. Briefly.",
			"Your breath is loud now.",
			"There is little colour in you.",
			"This is nearly the finish.",
			"I do not need to rush this.",
			"You redden the grass.",
			"Your knees go.",
			"You stand on the last of you and I simply wait.",
			"You are almost gone."
	),

	/** It is hurt and stepping back, for now. */
	WITHDRAW(
			"I step back.",
			"This is a pause.",
			"I am damaged. I am not finished.",
			"I take space on purpose.",
			"You buy a moment. Not more.",
			"I go to put myself together.",
			"Do not follow me now.",
			"Hurt is not the same as ended.",
			"I leave this spot on purpose.",
			"Count this as delay.",
			"I fall back now so that I remain able to end you.",
			"I keep the rest of me."
	),

	/** You took a portal. It followed. */
	DIMENSION(
			"I follow through.",
			"You change worlds. I follow.",
			"The portal does not drop me.",
			"I am on the other side too.",
			"This place holds you yet.",
			"I cross as you cross.",
			"You bring me here.",
			"The threshold does not save you.",
			"I arrive after you. Close.",
			"Wherever this opens, I come.",
			"You go through here and I go through right after you.",
			"Same air. Same task."
	),

	/** It just killed you. */
	KILL(
			"That is done.",
			"You are motionless now.",
			"Your body is the proof.",
			"I stop because you stop.",
			"The chase is over.",
			"You go down and I stand.",
			"The work is finished.",
			"There is no surprise in this.",
			"I take what you drop.",
			"You lie where I put you and I do not stay long.",
			"You occupy no more space.",
			"I know the shape of this."
	),

	/** Ambient, while it walks toward you. */
	IDLE(
			"I keep walking.",
			"I walk toward you.",
			"The path is long. Fine.",
			"You have hours. I have more.",
			"I do not get lost.",
			"Forward is enough.",
			"Night does not stop me.",
			"I measure the land by walking.",
			"You move. I adjust.",
			"There is no other task.",
			"I come across this world at a pace that does not tire.",
			"The sun moves. So do I."
	);

	private final List<String> lines;

	Taunts(String... lines) {
		this.lines = List.of(lines);
	}

	/**
	 * Picks a line, avoiding the one used last.
	 *
	 * @param avoid the previous line, so the same one does not land twice
	 */
	public String pick(RandomSource random, String avoid) {
		if (lines.isEmpty()) {
			return "";
		}
		if (lines.size() == 1) {
			return lines.get(0);
		}
		for (int attempt = 0; attempt < 6; attempt++) {
			String candidate = lines.get(random.nextInt(lines.size()));
			if (!candidate.equals(avoid)) {
				return candidate;
			}
		}
		return lines.get(random.nextInt(lines.size()));
	}
}
