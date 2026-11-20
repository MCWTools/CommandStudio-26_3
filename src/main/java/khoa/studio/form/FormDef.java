package khoa.studio.form;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/** Description of one simple generator: its fields and how they become a command. Choices come from the game's registries where one exists. */
public record FormDef(String id, String label, List<Field> fields, Function<Map<String, String>, String> build) {
	public enum Type {
		TEXT, CHOICE, REGISTRY, BOOL
	}

	/**
	 * @param options  fixed options of a CHOICE
	 * @param registry rows {id, label} of a REGISTRY, read from the running game
	 * @param showKey  only shown while the field {@code showKey} has one of {@code showValues} (null = always)
	 */
	public record Field(String key, String label, Type type, String def, List<String> options, Supplier<List<String[]>> registry, String showKey, Set<String> showValues) {
		public boolean visible(Map<String, String> values) {
			return showKey == null || showValues.contains(values.getOrDefault(showKey, ""));
		}

		public Field when(String key, String... any) {
			return new Field(this.key, label, type, def, options, registry, key, Set.of(any));
		}
	}

	public static Field text(String key, String label, String def) {
		return new Field(key, label, Type.TEXT, def, List.of(), null, null, Set.of());
	}

	public static Field choice(String key, String label, List<String> options) {
		return new Field(key, label, Type.CHOICE, options.get(0), options, null, null, Set.of());
	}

	public static Field registry(String key, String label, String def, Supplier<List<String[]>> rows) {
		return new Field(key, label, Type.REGISTRY, def, List.of(), rows, null, Set.of());
	}

	public static Field bool(String key, String label, boolean def) {
		return new Field(key, label, Type.BOOL, String.valueOf(def), List.of(), null, null, Set.of());
	}
}
