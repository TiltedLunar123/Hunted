package dev.tiltedlunar.hunted.installer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Works out where Minecraft lives on this machine. */
public final class MinecraftPaths {

	private MinecraftPaths() {
	}

	/**
	 * The most likely Minecraft directory, or null when none of the usual
	 * places exist and the player will have to point at it themselves.
	 */
	public static Path detect() {
		for (Path candidate : candidates()) {
			if (looksValid(candidate)) {
				return candidate;
			}
		}
		return null;
	}

	/** Every standard location for this operating system, best guess first. */
	public static List<Path> candidates() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String home = System.getProperty("user.home", ".");
		List<Path> found = new ArrayList<>();

		if (os.contains("win")) {
			String appData = System.getenv("APPDATA");
			if (appData != null && !appData.isBlank()) {
				found.add(Paths.get(appData, ".minecraft"));
			}
			found.add(Paths.get(home, "AppData", "Roaming", ".minecraft"));
		} else if (os.contains("mac")) {
			found.add(Paths.get(home, "Library", "Application Support", "minecraft"));
		} else {
			found.add(Paths.get(home, ".minecraft"));
			// Flatpak and Snap installs put it somewhere less obvious.
			found.add(Paths.get(home, ".var", "app", "com.mojang.Minecraft", ".minecraft"));
			found.add(Paths.get(home, "snap", "minecraft-launcher", "common", ".minecraft"));
		}

		found.add(Paths.get(home, ".minecraft"));
		return found;
	}

	/**
	 * Whether a directory really is a Minecraft install.
	 *
	 * <p>Checks for the launcher profile file rather than just the folder,
	 * because an empty {@code .minecraft} is common and installing into one
	 * produces a profile the launcher never shows.
	 */
	public static boolean looksValid(Path directory) {
		return directory != null
				&& Files.isDirectory(directory)
				&& Files.isRegularFile(directory.resolve("launcher_profiles.json"));
	}

	/** Where the mod and its own saves live, kept away from the player's other worlds. */
	public static Path gameDirectory(Path minecraftDirectory) {
		return minecraftDirectory.resolve("hunted");
	}
}
