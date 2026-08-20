package dev.tiltedlunar.hunted.installer;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The installer rewrites {@code launcher_profiles.json}, which holds every
 * Minecraft install the player owns. Getting this wrong loses their data, so it
 * gets tested harder than its size suggests.
 */
class JsonTest {

	@Test
	@DisplayName("round trips a launcher profiles document without losing anything")
	void roundTripsLauncherProfiles() {
		String original = """
				{
				  "profiles": {
				    "abc123": {
				      "created": "2026-01-01T00:00:00.000Z",
				      "icon": "Furnace",
				      "lastVersionId": "1.21.11",
				      "name": "My World",
				      "type": "custom"
				    }
				  },
				  "settings": { "enableSnapshots": false, "profileSorting": "ByLastPlayed" },
				  "version": 6
				}
				""";

		Map<String, Object> parsed = Json.parseObject(original);
		String written = Json.write(parsed);
		Map<String, Object> reparsed = Json.parseObject(written);

		assertEquals(parsed, reparsed, "a write then read must preserve the document");

		@SuppressWarnings("unchecked")
		Map<String, Object> profiles = (Map<String, Object>) reparsed.get("profiles");
		@SuppressWarnings("unchecked")
		Map<String, Object> profile = (Map<String, Object>) profiles.get("abc123");
		assertEquals("My World", profile.get("name"));
		assertEquals("1.21.11", profile.get("lastVersionId"));
	}

	@Test
	@DisplayName("keeps object key order so a rewrite is a small diff")
	void preservesKeyOrder() {
		Map<String, Object> parsed = Json.parseObject("{\"z\":1,\"a\":2,\"m\":3}");
		assertEquals(List.of("z", "a", "m"), List.copyOf(parsed.keySet()));
		assertTrue(Json.write(parsed).indexOf("\"z\"") < Json.write(parsed).indexOf("\"a\""));
	}

	@Test
	@DisplayName("whole numbers do not gain a decimal point")
	void writesWholeNumbersCleanly() {
		Map<String, Object> doc = Json.object();
		doc.put("version", 6.0d);
		doc.put("ratio", 1.5d);
		String written = Json.write(doc);
		assertTrue(written.contains("\"version\": 6"), "expected a bare 6 in: " + written);
		assertTrue(written.contains("\"ratio\": 1.5"), "expected 1.5 in: " + written);
	}

	@Test
	@DisplayName("handles escapes and unicode in both directions")
	void handlesEscapes() {
		String text = "line\nbreak \"quoted\" back\\slash tab\there \u00e9";
		Map<String, Object> doc = Json.object();
		doc.put("value", text);

		Map<String, Object> reparsed = Json.parseObject(Json.write(doc));
		assertEquals(text, reparsed.get("value"));

		assertEquals("\u00e9", Json.parse("\"\\u00e9\""));
	}

	@Test
	@DisplayName("reads the primitive types")
	void readsPrimitives() {
		assertNull(Json.parse("null"));
		assertEquals(Boolean.TRUE, Json.parse("true"));
		assertEquals(Boolean.FALSE, Json.parse("false"));
		assertInstanceOf(Double.class, Json.parse("-12.5e2"));
		assertEquals(List.of(), Json.parse("[]"));
		assertEquals(Map.of(), Json.parse("{}"));
	}

	@Test
	@DisplayName("nested arrays and objects survive a round trip")
	void handlesNesting() {
		String source = "{\"a\":[1,{\"b\":[true,null,\"x\"]}]}";
		assertEquals(Json.parse(source), Json.parse(Json.write(Json.parse(source))));
	}

	@Test
	@DisplayName("rejects malformed input rather than guessing")
	void rejectsBadInput() {
		assertThrows(IllegalArgumentException.class, () -> Json.parse("{"));
		assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\" 1}"));
		assertThrows(IllegalArgumentException.class, () -> Json.parse("[1,]"));
		assertThrows(IllegalArgumentException.class, () -> Json.parse("{} extra"));
		assertThrows(IllegalArgumentException.class, () -> Json.parse("\"unterminated"));
		assertThrows(IllegalArgumentException.class, () -> Json.parseObject("[1,2]"));
	}
}
