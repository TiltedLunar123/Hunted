package dev.tiltedlunar.hunted.installer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer.
 *
 * <p>The installer has exactly one hard requirement: run on whatever Java a
 * player already has, from a single file they double click. That rules out
 * shipping Gson, and this is the entire reason to hand roll a parser instead.
 *
 * <p>It matters that this is correct rather than clever, because it rewrites
 * {@code launcher_profiles.json}, a file that belongs to the player and that
 * holds every other install they own. Object key order is preserved so a
 * rewrite is a minimal diff rather than a reshuffle.
 *
 * <p>Values map to {@link Map}, {@link List}, {@link String}, {@link Double},
 * {@link Boolean} and null.
 */
public final class Json {

	private final String source;
	private int index;

	private Json(String source) {
		this.source = source;
	}

	/** Parses a document. Throws {@link IllegalArgumentException} on bad input. */
	public static Object parse(String text) {
		Json parser = new Json(text);
		parser.skipWhitespace();
		Object value = parser.readValue();
		parser.skipWhitespace();
		if (parser.index < parser.source.length()) {
			throw new IllegalArgumentException("Trailing content at offset " + parser.index);
		}
		return value;
	}

	/** Parses a document expected to be an object. */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> parseObject(String text) {
		Object value = parse(text);
		if (!(value instanceof Map)) {
			throw new IllegalArgumentException("Expected a JSON object at the top level");
		}
		return (Map<String, Object>) value;
	}

	public static Map<String, Object> object() {
		return new LinkedHashMap<>();
	}

	// -----------------------------------------------------------------
	// Reading
	// -----------------------------------------------------------------

	private Object readValue() {
		if (index >= source.length()) {
			throw new IllegalArgumentException("Unexpected end of input");
		}
		char c = source.charAt(index);
		return switch (c) {
			case '{' -> readObject();
			case '[' -> readArray();
			case '"' -> readString();
			case 't', 'f' -> readBoolean();
			case 'n' -> readNull();
			default -> readNumber();
		};
	}

	private Map<String, Object> readObject() {
		Map<String, Object> result = new LinkedHashMap<>();
		expect('{');
		skipWhitespace();
		if (peek() == '}') {
			index++;
			return result;
		}
		while (true) {
			skipWhitespace();
			String key = readString();
			skipWhitespace();
			expect(':');
			skipWhitespace();
			result.put(key, readValue());
			skipWhitespace();
			char c = next();
			if (c == '}') {
				return result;
			}
			if (c != ',') {
				throw new IllegalArgumentException("Expected , or } at offset " + (index - 1));
			}
		}
	}

	private List<Object> readArray() {
		List<Object> result = new ArrayList<>();
		expect('[');
		skipWhitespace();
		if (peek() == ']') {
			index++;
			return result;
		}
		while (true) {
			skipWhitespace();
			result.add(readValue());
			skipWhitespace();
			char c = next();
			if (c == ']') {
				return result;
			}
			if (c != ',') {
				throw new IllegalArgumentException("Expected , or ] at offset " + (index - 1));
			}
		}
	}

	private String readString() {
		expect('"');
		StringBuilder builder = new StringBuilder();
		while (true) {
			char c = next();
			if (c == '"') {
				return builder.toString();
			}
			if (c != '\\') {
				builder.append(c);
				continue;
			}
			char escape = next();
			switch (escape) {
				case '"' -> builder.append('"');
				case '\\' -> builder.append('\\');
				case '/' -> builder.append('/');
				case 'b' -> builder.append('\b');
				case 'f' -> builder.append('\f');
				case 'n' -> builder.append('\n');
				case 'r' -> builder.append('\r');
				case 't' -> builder.append('\t');
				case 'u' -> {
					if (index + 4 > source.length()) {
						throw new IllegalArgumentException("Truncated unicode escape");
					}
					builder.append((char) Integer.parseInt(
							source.substring(index, index + 4), 16));
					index += 4;
				}
				default -> throw new IllegalArgumentException("Bad escape \\" + escape);
			}
		}
	}

	private Boolean readBoolean() {
		if (source.startsWith("true", index)) {
			index += 4;
			return Boolean.TRUE;
		}
		if (source.startsWith("false", index)) {
			index += 5;
			return Boolean.FALSE;
		}
		throw new IllegalArgumentException("Bad literal at offset " + index);
	}

	private Object readNull() {
		if (source.startsWith("null", index)) {
			index += 4;
			return null;
		}
		throw new IllegalArgumentException("Bad literal at offset " + index);
	}

	private Double readNumber() {
		int start = index;
		while (index < source.length() && "+-0123456789.eE".indexOf(source.charAt(index)) >= 0) {
			index++;
		}
		if (start == index) {
			throw new IllegalArgumentException("Expected a value at offset " + start);
		}
		try {
			return Double.valueOf(source.substring(start, index));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Bad number at offset " + start, e);
		}
	}

	private void skipWhitespace() {
		while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
			index++;
		}
	}

	private char peek() {
		if (index >= source.length()) {
			throw new IllegalArgumentException("Unexpected end of input");
		}
		return source.charAt(index);
	}

	private char next() {
		char c = peek();
		index++;
		return c;
	}

	private void expect(char expected) {
		char actual = next();
		if (actual != expected) {
			throw new IllegalArgumentException(
					"Expected " + expected + " but found " + actual + " at offset " + (index - 1));
		}
	}

	// -----------------------------------------------------------------
	// Writing
	// -----------------------------------------------------------------

	/** Serialises a value, indented so the result stays human editable. */
	public static String write(Object value) {
		StringBuilder builder = new StringBuilder();
		writeValue(builder, value, 0);
		return builder.toString();
	}

	// Written as an instanceof chain rather than a pattern switch so the
	// installer can still compile for Java 17 and run on whatever JRE a player
	// happens to have lying around.
	private static void writeValue(StringBuilder out, Object value, int depth) {
		if (value == null) {
			out.append("null");
		} else if (value instanceof Map) {
			writeObject(out, (Map<?, ?>) value, depth);
		} else if (value instanceof List) {
			writeArray(out, (List<?>) value, depth);
		} else if (value instanceof String) {
			writeString(out, (String) value);
		} else if (value instanceof Boolean) {
			out.append(value.toString());
		} else if (value instanceof Number) {
			out.append(formatNumber((Number) value));
		} else {
			writeString(out, value.toString());
		}
	}

	private static void writeObject(StringBuilder out, Map<?, ?> map, int depth) {
		if (map.isEmpty()) {
			out.append("{}");
			return;
		}
		out.append("{\n");
		int remaining = map.size();
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			indent(out, depth + 1);
			writeString(out, String.valueOf(entry.getKey()));
			out.append(": ");
			writeValue(out, entry.getValue(), depth + 1);
			if (--remaining > 0) {
				out.append(',');
			}
			out.append('\n');
		}
		indent(out, depth);
		out.append('}');
	}

	private static void writeArray(StringBuilder out, List<?> list, int depth) {
		if (list.isEmpty()) {
			out.append("[]");
			return;
		}
		out.append("[\n");
		for (int i = 0; i < list.size(); i++) {
			indent(out, depth + 1);
			writeValue(out, list.get(i), depth + 1);
			if (i < list.size() - 1) {
				out.append(',');
			}
			out.append('\n');
		}
		indent(out, depth);
		out.append(']');
	}

	private static void writeString(StringBuilder out, String text) {
		out.append('"');
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				case '\b' -> out.append("\\b");
				case '\f' -> out.append("\\f");
				default -> {
					if (c < 0x20) {
						out.append(String.format("\\u%04x", (int) c));
					} else {
						out.append(c);
					}
				}
			}
		}
		out.append('"');
	}

	/** Writes whole numbers without a trailing {@code .0}. */
	private static String formatNumber(Number number) {
		double d = number.doubleValue();
		if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
			return Long.toString((long) d);
		}
		return Double.toString(d);
	}

	private static void indent(StringBuilder out, int depth) {
		out.append("  ".repeat(depth));
	}
}
