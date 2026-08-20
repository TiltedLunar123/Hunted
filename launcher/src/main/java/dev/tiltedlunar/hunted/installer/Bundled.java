package dev.tiltedlunar.hunted.installer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * The mod jar carried inside the installer.
 *
 * <p>Shipping the mod inside the installer is what makes the promise "download
 * one file, click once" true. The alternative is a second download that can
 * fail on its own, at which point the player has a launcher profile pointing at
 * an empty mods folder and no idea why nothing happens.
 */
public final class Bundled {

	private static final String MOD_RESOURCE = "/bundled/hunted.jar";
	private static final String INFO_RESOURCE = "/bundled/build.properties";

	private static final Properties INFO = load();

	private Bundled() {
	}

	private static Properties load() {
		Properties properties = new Properties();
		try (InputStream in = Bundled.class.getResourceAsStream(INFO_RESOURCE)) {
			if (in != null) {
				properties.load(in);
			}
		} catch (IOException ignored) {
			// Falls back to the defaults below.
		}
		return properties;
	}

	/** The mod version this installer carries. */
	public static String modVersion() {
		return INFO.getProperty("mod_version", "unknown");
	}

	/** The Minecraft version the bundled mod was built against. */
	public static String minecraftVersion() {
		return INFO.getProperty("minecraft_version", "26.2");
	}

	/** The Fabric loader version the mod was built against. */
	public static String loaderVersion() {
		return INFO.getProperty("loader_version", "0.19.3");
	}

	/**
	 * Every Minecraft version this build of the mod actually runs on.
	 *
	 * <p>Comes from the build rather than being guessed here, because the answer
	 * changes whenever Mojang changes the API and a stale list would offer
	 * players an install that cannot work.
	 */
	public static String[] supportedMinecraftVersions() {
		return INFO.getProperty("supported_versions", "26.2").split(",");
	}

	/** Writes the bundled mod jar into a mods folder. */
	public static void extractModTo(Path modsFolder) throws Installation.InstallException {
		try (InputStream in = Bundled.class.getResourceAsStream(MOD_RESOURCE)) {
			if (in == null) {
				throw new Installation.InstallException(
						"This installer was built without the mod inside it. "
								+ "Run gradlew build and use the jar from launcher/build/libs.");
			}
			Path target = modsFolder.resolve("hunted-" + modVersion() + ".jar");
			Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new Installation.InstallException(
					"Could not write the mod into " + modsFolder + ".", e);
		}
	}
}
