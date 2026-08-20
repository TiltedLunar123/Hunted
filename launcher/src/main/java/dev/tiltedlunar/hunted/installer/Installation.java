package dev.tiltedlunar.hunted.installer;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Everything the Install button actually does.
 *
 * <p>Kept separate from the window so the whole install is testable and so a
 * failure has somewhere honest to report from. The steps are the same ones a
 * player would otherwise do by hand: fetch a Fabric version, register it with
 * the launcher, and drop two jars in a mods folder.
 *
 * <p>The install is deliberately self contained. It creates its own game
 * directory under {@code .minecraft/hunted} rather than using the shared one,
 * so it cannot collide with an existing modded setup, and uninstalling is
 * deleting one folder and one profile.
 */
public final class Installation {

	/** Where Fabric publishes ready made launcher version files. */
	public static final String FABRIC_META = "https://meta.fabricmc.net";

	/** Modrinth, for the Fabric API jar that the mod depends on. */
	public static final String MODRINTH = "https://api.modrinth.com";

	private static final String FABRIC_PROFILE_PATH = "/v2/versions/loader/%s/%s/profile/json";

	private static final String MODRINTH_PATH =
			"/v2/project/fabric-api/version"
					+ "?game_versions=%%5B%%22%s%%22%%5D&loaders=%%5B%%22fabric%%22%%5D";

	/** The profile key written into launcher_profiles.json. */
	private static final String PROFILE_KEY = "hunted-mod";

	private final String fabricMeta;
	private final String modrinth;

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(20))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	public Installation() {
		this(FABRIC_META, MODRINTH);
	}

	/**
	 * Lets the tests point the installer at a local server instead of the real
	 * internet, so the happy path is covered rather than assumed.
	 */
	Installation(String fabricMeta, String modrinth) {
		this.fabricMeta = fabricMeta;
		this.modrinth = modrinth;
	}

	/** Receives progress so the window can show it. */
	public interface Progress {
		void step(String message);

		void percent(int value);
	}

	/** Thrown for anything a player can act on, with a message worth showing them. */
	public static final class InstallException extends Exception {
		public InstallException(String message) {
			super(message);
		}

		public InstallException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	/**
	 * Installs the mod.
	 *
	 * @param minecraftDir the {@code .minecraft} directory
	 * @param mcVersion    the Minecraft version to build a profile for
	 * @param loaderVersion the Fabric loader version
	 * @return the version id registered with the launcher
	 */
	public String install(Path minecraftDir, String mcVersion, String loaderVersion,
			Progress progress) throws InstallException {
		if (!MinecraftPaths.looksValid(minecraftDir)) {
			throw new InstallException(
					"That folder does not look like a Minecraft installation. "
							+ "Expected to find launcher_profiles.json inside it.");
		}

		progress.step("Fetching Fabric " + loaderVersion + " for Minecraft " + mcVersion);
		progress.percent(10);
		String profileJson = fetchText(fabricMeta + String.format(FABRIC_PROFILE_PATH, mcVersion, loaderVersion),
				"Could not reach the Fabric version service. Check your connection.");

		Map<String, Object> versionDoc;
		String versionId;
		try {
			versionDoc = Json.parseObject(profileJson);
			versionId = String.valueOf(versionDoc.get("id"));
		} catch (RuntimeException e) {
			throw new InstallException("Fabric returned a version file this installer "
					+ "could not read.", e);
		}
		if (versionId == null || versionId.isBlank() || "null".equals(versionId)) {
			throw new InstallException("Fabric did not offer a build for Minecraft "
					+ mcVersion + ".");
		}

		progress.step("Registering version " + versionId);
		progress.percent(30);
		writeVersion(minecraftDir, versionId, profileJson);

		Path gameDir = MinecraftPaths.gameDirectory(minecraftDir);
		Path mods = gameDir.resolve("mods");
		try {
			Files.createDirectories(mods);
		} catch (IOException e) {
			throw new InstallException("Could not create " + mods + ".", e);
		}

		progress.step("Installing the mod");
		progress.percent(50);
		clearPreviousInstall(mods);
		Bundled.extractModTo(mods);

		progress.step("Downloading Fabric API");
		progress.percent(70);
		downloadFabricApi(mcVersion, mods);

		progress.step("Adding the launcher profile");
		progress.percent(90);
		writeLauncherProfile(minecraftDir, versionId, gameDir);

		progress.step("Done");
		progress.percent(100);
		return versionId;
	}

	// -----------------------------------------------------------------

	private void writeVersion(Path minecraftDir, String versionId, String json)
			throws InstallException {
		Path folder = minecraftDir.resolve("versions").resolve(versionId);
		try {
			Files.createDirectories(folder);
			Files.writeString(folder.resolve(versionId + ".json"), json, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new InstallException("Could not write the version file to " + folder + ".", e);
		}
	}

	/** Removes jars from a previous run so upgrades do not stack up. */
	private void clearPreviousInstall(Path mods) throws InstallException {
		if (!Files.isDirectory(mods)) {
			return;
		}
		try (Stream<Path> existing = Files.list(mods)) {
			for (Path file : existing.toList()) {
				String name = file.getFileName().toString();
				if (name.startsWith("hunted-") || name.startsWith("fabric-api-")) {
					Files.deleteIfExists(file);
				}
			}
		} catch (IOException e) {
			throw new InstallException("Could not clean the mods folder at " + mods + ".", e);
		}
	}

	private void downloadFabricApi(String mcVersion, Path mods) throws InstallException {
		String listing = fetchText(modrinth + String.format(MODRINTH_PATH, mcVersion),
				"Could not reach Modrinth to download Fabric API.");

		String url = null;
		String filename = null;
		try {
			Object parsed = Json.parse(listing);
			if (parsed instanceof List<?> versions && !versions.isEmpty()
					&& versions.get(0) instanceof Map<?, ?> newest
					&& newest.get("files") instanceof List<?> files && !files.isEmpty()
					&& files.get(0) instanceof Map<?, ?> file) {
				url = String.valueOf(file.get("url"));
				filename = String.valueOf(file.get("filename"));
			}
		} catch (RuntimeException e) {
			throw new InstallException("Modrinth returned something unexpected.", e);
		}

		if (url == null || filename == null || "null".equals(url)) {
			throw new InstallException("No Fabric API build exists for Minecraft "
					+ mcVersion + " yet.");
		}

		download(url, mods.resolve(filename));
	}

	private void writeLauncherProfile(Path minecraftDir, String versionId, Path gameDir)
			throws InstallException {
		Path file = minecraftDir.resolve("launcher_profiles.json");

		Map<String, Object> root;
		try {
			root = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new InstallException("Could not read " + file + ".", e);
		} catch (RuntimeException e) {
			throw new InstallException("launcher_profiles.json could not be parsed. "
					+ "It may be corrupt.", e);
		}

		// Never edit the player's file without leaving them a way back. Written
		// once and then left alone: overwriting it on every install would mean
		// the second run replaces the original with a copy that already has our
		// profile in it, and the way back is gone precisely when it is wanted.
		Path backup = file.resolveSibling("launcher_profiles.json.hunted-backup");
		if (!Files.exists(backup)) {
			try {
				Files.copy(file, backup);
			} catch (IOException e) {
				throw new InstallException("Could not back up launcher_profiles.json.", e);
			}
		}

		Object profilesValue = root.get("profiles");
		Map<String, Object> profiles;
		if (profilesValue instanceof Map<?, ?> existing) {
			@SuppressWarnings("unchecked")
			Map<String, Object> cast = (Map<String, Object>) existing;
			profiles = cast;
		} else {
			profiles = Json.object();
			root.put("profiles", profiles);
		}

		String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
		Map<String, Object> profile = Json.object();
		profile.put("name", "Hunted");
		profile.put("type", "custom");
		profile.put("created", now);
		profile.put("lastUsed", now);
		profile.put("lastVersionId", versionId);
		profile.put("gameDir", gameDir.toAbsolutePath().toString());
		profile.put("icon", "TNT");
		profiles.put(PROFILE_KEY, profile);

		try {
			Files.writeString(file, Json.write(root), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new InstallException("Could not write " + file + ".", e);
		}
	}

	// -----------------------------------------------------------------

	private String fetchText(String url, String failureMessage) throws InstallException {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(45))
					.header("User-Agent", "Hunted-Installer")
					.GET()
					.build();
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() / 100 != 2) {
				throw new InstallException(failureMessage
						+ " (server replied " + response.statusCode() + ")");
			}
			return response.body();
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			throw new InstallException(failureMessage, e);
		}
	}

	private void download(String url, Path target) throws InstallException {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofMinutes(3))
					.header("User-Agent", "Hunted-Installer")
					.GET()
					.build();
			HttpResponse<InputStream> response =
					http.send(request, HttpResponse.BodyHandlers.ofInputStream());
			if (response.statusCode() / 100 != 2) {
				throw new InstallException("Download failed for " + target.getFileName()
						+ " (server replied " + response.statusCode() + ")");
			}
			try (InputStream body = response.body()) {
				Files.copy(body, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			throw new InstallException("Could not download " + target.getFileName() + ".", e);
		}
	}

	/** Removes the profile and the mod folder created by {@link #install}. */
	public void uninstall(Path minecraftDir) throws InstallException {
		Path file = minecraftDir.resolve("launcher_profiles.json");
		try {
			Map<String, Object> root = Json.parseObject(
					Files.readString(file, StandardCharsets.UTF_8));
			if (root.get("profiles") instanceof Map<?, ?> profiles) {
				profiles.remove(PROFILE_KEY);
				Files.writeString(file, Json.write(root), StandardCharsets.UTF_8);
			}
		} catch (IOException | RuntimeException e) {
			throw new InstallException("Could not update launcher_profiles.json.", e);
		}
	}
}
