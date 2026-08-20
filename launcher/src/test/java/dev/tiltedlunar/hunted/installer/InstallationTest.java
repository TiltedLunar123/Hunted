package dev.tiltedlunar.hunted.installer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives a whole install against a local server.
 *
 * <p>The install touches a file the player owns and cannot afford to lose, so
 * the happy path is exercised end to end rather than assumed. The Fabric
 * version document is the real one, saved from the live service, so a change in
 * its shape shows up here.
 */
class InstallationTest {

	private static final String FABRIC_API_BYTES = "not really a jar, but it is the right shape";

	private HttpServer server;
	private String base;

	@TempDir
	Path temp;

	@BeforeEach
	void startServer() throws IOException {
		String profile = readFixture();

		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		base = "http://127.0.0.1:" + server.getAddress().getPort();

		server.createContext("/v2/versions/loader/", exchange ->
				respond(exchange, 200, profile.getBytes(StandardCharsets.UTF_8)));

		server.createContext("/v2/project/fabric-api/version", exchange -> {
			String listing = "[{\"version_number\":\"0.158.0+26.2\",\"files\":[{"
					+ "\"url\":\"" + base + "/files/fabric-api-0.158.0%2B26.2.jar\","
					+ "\"filename\":\"fabric-api-0.158.0+26.2.jar\"}]}]";
			respond(exchange, 200, listing.getBytes(StandardCharsets.UTF_8));
		});

		server.createContext("/files/", exchange ->
				respond(exchange, 200, FABRIC_API_BYTES.getBytes(StandardCharsets.UTF_8)));

		server.start();
	}

	@AfterEach
	void stopServer() {
		if (server != null) {
			server.stop(0);
		}
	}

	private static String readFixture() throws IOException {
		try (InputStream in = InstallationTest.class
				.getResourceAsStream("/fabric-profile-26.2.json")) {
			assertNotNull(in, "missing the saved Fabric profile fixture");
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static void respond(com.sun.net.httpserver.HttpExchange exchange,
			int status, byte[] body) throws IOException {
		exchange.sendResponseHeaders(status, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

	private Path minecraftWithProfile() throws IOException {
		Path minecraft = temp.resolve(".minecraft");
		Files.createDirectories(minecraft.resolve("versions"));
		Files.writeString(minecraft.resolve("launcher_profiles.json"), """
				{
				  "profiles": {
				    "mine": {
				      "name": "My Survival World",
				      "type": "custom",
				      "lastVersionId": "1.21.11"
				    }
				  },
				  "settings": { "profileSorting": "ByLastPlayed" },
				  "version": 6
				}
				""", StandardCharsets.UTF_8);
		return minecraft;
	}

	private Installation installer() {
		return new Installation(base, base);
	}

	private static final Installation.Progress SILENT = new Installation.Progress() {
		@Override
		public void step(String message) {
		}

		@Override
		public void percent(int value) {
		}
	};

	@Test
	@DisplayName("a full install leaves the launcher ready to run the mod")
	void fullInstall() throws Exception {
		Path minecraft = minecraftWithProfile();

		String versionId = installer().install(minecraft, "26.2", "0.19.3", SILENT);
		assertEquals("fabric-loader-0.19.3-26.2", versionId);

		Path versionJson = minecraft.resolve("versions").resolve(versionId)
				.resolve(versionId + ".json");
		assertTrue(Files.isRegularFile(versionJson), "version file should exist");
		assertEquals("fabric-loader-0.19.3-26.2",
				Json.parseObject(Files.readString(versionJson)).get("id"));

		Path mods = minecraft.resolve("hunted").resolve("mods");
		assertTrue(Files.isDirectory(mods), "mods folder should exist");

		try (Stream<Path> jars = Files.list(mods)) {
			List<String> names = jars.map(p -> p.getFileName().toString()).sorted().toList();
			assertEquals(2, names.size(), "expected the mod and Fabric API, got " + names);
			assertTrue(names.stream().anyMatch(n -> n.startsWith("hunted-")), "mod jar missing");
			assertTrue(names.stream().anyMatch(n -> n.startsWith("fabric-api-")),
					"fabric api missing");
		}
	}

	@Test
	@DisplayName("installing does not disturb the player's existing profiles")
	void keepsExistingProfiles() throws Exception {
		Path minecraft = minecraftWithProfile();
		installer().install(minecraft, "26.2", "0.19.3", SILENT);

		Map<String, Object> root = Json.parseObject(
				Files.readString(minecraft.resolve("launcher_profiles.json")));
		@SuppressWarnings("unchecked")
		Map<String, Object> profiles = (Map<String, Object>) root.get("profiles");

		assertTrue(profiles.containsKey("mine"), "the existing profile was lost");
		@SuppressWarnings("unchecked")
		Map<String, Object> existing = (Map<String, Object>) profiles.get("mine");
		assertEquals("My Survival World", existing.get("name"));

		assertTrue(profiles.containsKey("hunted-mod"), "our profile was not added");
		@SuppressWarnings("unchecked")
		Map<String, Object> ours = (Map<String, Object>) profiles.get("hunted-mod");
		assertEquals("Hunted", ours.get("name"));
		assertEquals("fabric-loader-0.19.3-26.2", ours.get("lastVersionId"));
		assertTrue(String.valueOf(ours.get("gameDir")).endsWith("hunted"),
				"should use its own game directory");

		assertEquals(6.0, ((Number) root.get("version")).doubleValue(),
				"the file's own version field should survive");
	}

	@Test
	@DisplayName("a backup of launcher_profiles.json is written before any change")
	void writesBackup() throws Exception {
		Path minecraft = minecraftWithProfile();
		installer().install(minecraft, "26.2", "0.19.3", SILENT);

		Path backup = minecraft.resolve("launcher_profiles.json.hunted-backup");
		assertTrue(Files.isRegularFile(backup), "no backup was written");
		assertTrue(Files.readString(backup).contains("My Survival World"),
				"the backup should hold the original content");
		assertFalse(Files.readString(backup).contains("hunted-mod"),
				"the backup should predate our change");
	}

	@Test
	@DisplayName("installing twice replaces the old jars instead of stacking them")
	void reinstallIsClean() throws Exception {
		Path minecraft = minecraftWithProfile();
		installer().install(minecraft, "26.2", "0.19.3", SILENT);

		Path mods = minecraft.resolve("hunted").resolve("mods");
		Files.writeString(mods.resolve("hunted-0.9.0.jar"), "stale build");

		installer().install(minecraft, "26.2", "0.19.3", SILENT);

		try (Stream<Path> jars = Files.list(mods)) {
			List<String> names = jars.map(p -> p.getFileName().toString()).toList();
			assertEquals(2, names.size(), "old jars should be cleared, got " + names);
			assertFalse(names.contains("hunted-0.9.0.jar"), "the stale jar survived");
		}
	}

	@Test
	@DisplayName("refuses a folder that is not a Minecraft install")
	void rejectsWrongFolder() throws Exception {
		Path notMinecraft = temp.resolve("documents");
		Files.createDirectories(notMinecraft);

		Installation.InstallException thrown = assertThrows(Installation.InstallException.class,
				() -> installer().install(notMinecraft, "26.2", "0.19.3", SILENT));
		assertTrue(thrown.getMessage().contains("launcher_profiles.json"),
				"the message should say what was missing: " + thrown.getMessage());
	}

	@Test
	@DisplayName("uninstalling removes our profile and leaves the rest alone")
	void uninstallIsTargeted() throws Exception {
		Path minecraft = minecraftWithProfile();
		installer().install(minecraft, "26.2", "0.19.3", SILENT);
		installer().uninstall(minecraft);

		Map<String, Object> root = Json.parseObject(
				Files.readString(minecraft.resolve("launcher_profiles.json")));
		@SuppressWarnings("unchecked")
		Map<String, Object> profiles = (Map<String, Object>) root.get("profiles");

		assertFalse(profiles.containsKey("hunted-mod"), "our profile should be gone");
		assertTrue(profiles.containsKey("mine"), "the player's profile should remain");
	}
}
