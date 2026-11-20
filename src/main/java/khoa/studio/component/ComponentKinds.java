package khoa.studio.component;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.Identifier;

/**
 * What kind of value a component takes, read from the game: every {@code DataComponents} field is declared as
 * {@code DataComponentType<T>}, and T decides the editor (boolean -> tick box, number -> number box, text -> text box,
 * ItemEnchantments -> enchantment picker). Anything else (or a modded component) falls back to raw SNBT. No id lists.
 */
public final class ComponentKinds {
	public enum Kind {
		BOOLEAN, UNIT, INTEGER, DECIMAL, TEXT, ENCHANTMENTS, RAW
	}

	private static Map<String, Kind> kinds;

	private ComponentKinds() {
	}

	public static synchronized Kind of(String key) {
		if (kinds == null) {
			kinds = build();
		}
		String k = key.startsWith("!") ? key.substring(1) : key;
		return kinds.getOrDefault(k.contains(":") ? k : "minecraft:" + k, Kind.RAW);
	}

	private static Map<String, Kind> build() {
		Map<String, Kind> out = new HashMap<>();
		for (Field f : DataComponents.class.getFields()) {
			if (!Modifier.isStatic(f.getModifiers()) || !DataComponentType.class.isAssignableFrom(f.getType())) {
				continue;
			}
			try {
				Identifier id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey((DataComponentType<?>) f.get(null));
				if (id != null && f.getGenericType() instanceof ParameterizedType p) {
					out.put(id.toString(), classify(rawClass(p.getActualTypeArguments()[0])));
				}
			} catch (IllegalAccessException e) {
				// not readable: stays RAW
			}
		}
		return out;
	}

	private static Class<?> rawClass(Type t) {
		if (t instanceof Class<?> c) {
			return c;
		}
		if (t instanceof ParameterizedType p && p.getRawType() instanceof Class<?> c) {
			return c;
		}
		return Object.class;
	}

	private static Kind classify(Class<?> c) {
		String name = c.getName();
		if (c == Boolean.class) {
			return Kind.BOOLEAN;
		}
		if (c == Integer.class || c == Long.class || c == Short.class || c == Byte.class) {
			return Kind.INTEGER;
		}
		if (c == Float.class || c == Double.class) {
			return Kind.DECIMAL;
		}
		if (c == String.class || c == Identifier.class || name.equals("net.minecraft.network.chat.Component")) {
			return Kind.TEXT;
		}
		if (name.equals("net.minecraft.util.Unit")) {
			return Kind.UNIT;
		}
		if (name.equals("net.minecraft.world.item.enchantment.ItemEnchantments")) {
			return Kind.ENCHANTMENTS;
		}
		return Kind.RAW;
	}

	/** The value a freshly added component starts with when the item has no default for it. It does not count as a user edit. */
	public static String placeholder(Kind kind) {
		return switch (kind) {
			case BOOLEAN -> "false";
			case UNIT, ENCHANTMENTS -> "{}";
			default -> "";
		};
	}

	public static String hint(Kind kind) {
		return switch (kind) {
			case INTEGER -> "whole number";
			case DECIMAL -> "number";
			case TEXT -> "text";
			default -> "SNBT value";
		};
	}

	/** Text -> SNBT string literal. */
	public static String quote(String text) {
		return StringTag.quoteAndEscape(text);
	}

	/** SNBT string literal -> text (unquoted values are returned as they are). */
	public static String unquote(String snbt) {
		String s = snbt.trim();
		if (s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '\'') && s.charAt(s.length() - 1) == s.charAt(0)) {
			return s.substring(1, s.length() - 1).replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\");
		}
		return snbt;
	}
}
