package dev.tiltedlunar.hunted.survival;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * What the hunter is carrying.
 *
 * <p>Deliberately not a real container. The hunter has no UI, no stack limits
 * and no slots, and modelling those would buy nothing except bugs. All the
 * progression logic needs to ask is "how many of these do I have", so that is
 * the entire interface.
 */
public final class HunterInventory {

	private final Map<Item, Integer> counts = new LinkedHashMap<>();

	public int count(Item item) {
		return counts.getOrDefault(item, 0);
	}

	/** Total held across every item carrying this tag. */
	public int countTag(TagKey<Item> tag) {
		int total = 0;
		for (Map.Entry<Item, Integer> entry : counts.entrySet()) {
			if (BuiltInRegistries.ITEM.wrapAsHolder(entry.getKey()).is(tag)) {
				total += entry.getValue();
			}
		}
		return total;
	}

	public boolean has(Item item, int amount) {
		return count(item) >= amount;
	}

	public boolean hasTag(TagKey<Item> tag, int amount) {
		return countTag(tag) >= amount;
	}

	public void add(Item item, int amount) {
		if (amount <= 0) {
			return;
		}
		counts.merge(item, amount, Integer::sum);
	}

	public void add(ItemStack stack) {
		if (!stack.isEmpty()) {
			add(stack.getItem(), stack.getCount());
		}
	}

	/**
	 * Removes items, returning false and changing nothing if there are not
	 * enough. Callers rely on this being all or nothing.
	 */
	public boolean consume(Item item, int amount) {
		int held = count(item);
		if (held < amount) {
			return false;
		}
		if (held == amount) {
			counts.remove(item);
		} else {
			counts.put(item, held - amount);
		}
		return true;
	}

	/** Removes {@code amount} spread across whatever satisfies the tag. */
	public boolean consumeTag(TagKey<Item> tag, int amount) {
		if (!hasTag(tag, amount)) {
			return false;
		}
		int remaining = amount;
		Map<Item, Integer> snapshot = new LinkedHashMap<>(counts);
		for (Map.Entry<Item, Integer> entry : snapshot.entrySet()) {
			if (remaining <= 0) {
				break;
			}
			if (!BuiltInRegistries.ITEM.wrapAsHolder(entry.getKey()).is(tag)) {
				continue;
			}
			int take = Math.min(remaining, entry.getValue());
			consume(entry.getKey(), take);
			remaining -= take;
		}
		return remaining <= 0;
	}

	public boolean isEmpty() {
		return counts.isEmpty();
	}

	/**
	 * A cheap number that changes whenever the contents change.
	 *
	 * <p>For telling whether a stretch of gathering actually produced anything.
	 * Comparing the whole map every tick would work too, this is just less
	 * rubbish per tick, and a collision only ever costs one late decision.
	 */
	public int fingerprint() {
		int hash = 17;
		for (Map.Entry<Item, Integer> entry : counts.entrySet()) {
			hash += System.identityHashCode(entry.getKey()) * 31 + entry.getValue();
		}
		return hash;
	}

	public Map<Item, Integer> view() {
		return Map.copyOf(counts);
	}

	public void clear() {
		counts.clear();
	}

	public void save(ValueOutput out) {
		StringBuilder builder = new StringBuilder();
		counts.forEach((item, amount) -> {
			if (!builder.isEmpty()) {
				builder.append(';');
			}
			builder.append(BuiltInRegistries.ITEM.getKey(item)).append('=').append(amount);
		});
		out.putString("Carrying", builder.toString());
	}

	public void load(ValueInput in) {
		counts.clear();
		String raw = in.getStringOr("Carrying", "");
		if (raw.isBlank()) {
			return;
		}
		Map<Item, Integer> parsed = new HashMap<>();
		for (String entry : raw.split(";")) {
			int split = entry.lastIndexOf('=');
			if (split <= 0) {
				continue;
			}
			Identifier id = Identifier.tryParse(entry.substring(0, split));
			if (id == null) {
				continue;
			}
			try {
				int amount = Integer.parseInt(entry.substring(split + 1));
				BuiltInRegistries.ITEM.getOptional(id)
						.ifPresent(item -> parsed.merge(item, amount, Integer::sum));
			} catch (NumberFormatException ignored) {
				// A corrupt entry loses one item type, not the whole save.
			}
		}
		counts.putAll(parsed);
	}
}
