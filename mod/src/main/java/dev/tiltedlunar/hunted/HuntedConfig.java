package dev.tiltedlunar.hunted;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import dev.tiltedlunar.hunted.hunter.HunterTier;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Server side settings, stored as {@code config/hunted.json}.
 *
 * <p>Kept small on purpose. Every option here is one a player might plausibly
 * want to change mid game, and each maps to a subcommand so nobody has to alt
 * tab into a text editor to turn griefing off.
 */
public final class HuntedConfig {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "hunted.json";

	private static HuntedConfig instance;

	// Serialised fields. Names are part of the file format, so renaming one is
	// a breaking change for anybody's existing config.
	private String defaultTier = HunterTier.RIVAL.id();
	private boolean allowTerrainDamage = true;
	private boolean crossDimensions = true;
	private boolean announceSpawn = true;
	private boolean glowing = false;
	private boolean survivalStart = true;
	private boolean taunts = true;
	private int maxHuntersPerPlayer = 1;
	private int spawnDistance = 48;

	/** The live config, loading it from disk on first use. */
	public static HuntedConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	/**
	 * Reads the config, falling back to defaults for anything missing or
	 * malformed. A broken config file must never stop the mod from loading, so
	 * every failure here degrades to defaults and logs.
	 */
	public static HuntedConfig load() {
		Path file = path();
		if (!Files.exists(file)) {
			HuntedConfig fresh = new HuntedConfig();
			fresh.save();
			return fresh;
		}

		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			HuntedConfig parsed = GSON.fromJson(reader, HuntedConfig.class);
			if (parsed == null) {
				Hunted.LOG.warn("{} was empty, using defaults.", FILE_NAME);
				return new HuntedConfig();
			}
			parsed.clamp();
			return parsed;
		} catch (IOException | JsonSyntaxException e) {
			Hunted.LOG.warn("Could not read {}, using defaults: {}", FILE_NAME, e.getMessage());
			return new HuntedConfig();
		}
	}

	public void save() {
		clamp();
		Path file = path();
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			Hunted.LOG.warn("Could not write {}: {}", FILE_NAME, e.getMessage());
		}
	}

	/** Pulls hand edited values back into ranges the game can actually honour. */
	private void clamp() {
		maxHuntersPerPlayer = Math.max(1, Math.min(16, maxHuntersPerPlayer));
		spawnDistance = Math.max(8, Math.min(256, spawnDistance));
		if (HunterTier.byIdOrDefault(defaultTier, null) == null) {
			defaultTier = HunterTier.RIVAL.id();
		}
	}

	public HunterTier defaultTier() {
		return HunterTier.byIdOrDefault(defaultTier, HunterTier.RIVAL);
	}

	public void setDefaultTier(HunterTier tier) {
		this.defaultTier = tier.id();
		save();
	}

	/** Whether hunters may break and place blocks. */
	public boolean allowTerrainDamage() {
		return allowTerrainDamage;
	}

	public void setAllowTerrainDamage(boolean value) {
		this.allowTerrainDamage = value;
		save();
	}

	/** Whether hunters chase their quarry into the Nether and the End. */
	public boolean crossDimensions() {
		return crossDimensions;
	}

	public void setCrossDimensions(boolean value) {
		this.crossDimensions = value;
		save();
	}

	public boolean announceSpawn() {
		return announceSpawn;
	}

	public void setAnnounceSpawn(boolean value) {
		this.announceSpawn = value;
		save();
	}

	/** Outlines hunters through walls. Useful for demos, ruinous for tension. */
	public boolean glowing() {
		return glowing;
	}

	public void setGlowing(boolean value) {
		this.glowing = value;
		save();
	}

	/**
	 * Whether hunters spawn empty handed and have to gather, craft and smelt
	 * their own equipment before they come for you.
	 *
	 * <p>On by default, because handing a hunter free diamond gear is a cheat
	 * like any other. Turn it off if you want an immediate fight rather than a
	 * hunter that spends its first few minutes in a forest.
	 */
	public boolean survivalStart() {
		return survivalStart;
	}

	public void setSurvivalStart(boolean value) {
		this.survivalStart = value;
		save();
	}

	/**
	 * Whether the hunter talks to its target.
	 *
	 * <p>It speaks on a change of plan rather than on a timer, with a hard
	 * floor between lines, so a quiet chase stays mostly quiet.
	 */
	public boolean taunts() {
		return taunts;
	}

	public void setTaunts(boolean value) {
		this.taunts = value;
		save();
	}

	public int maxHuntersPerPlayer() {
		return maxHuntersPerPlayer;
	}

	public void setMaxHuntersPerPlayer(int value) {
		this.maxHuntersPerPlayer = value;
		save();
	}

	public int spawnDistance() {
		return spawnDistance;
	}

	public void setSpawnDistance(int value) {
		this.spawnDistance = value;
		save();
	}
}
