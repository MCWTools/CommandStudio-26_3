package khoa.studio.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;

/**
 * Typed editors for the top level NBT of an entity, derived from the game: the sample entity's own NBT decides which keys
 * exist and what type each has (byte 0/1 = tick box, other numbers = number box keeping their exact tag type, string = text,
 * {id,count} compound = item picker). Nothing is listed by hand, so every entity type and mod entity gets its own fields.
 */
public final class EntityFields {
	public enum Kind {
		BOOL, NUMBER, TEXT, ITEM
	}

	/** {@code def} is the default tag from the sample entity. */
	public record Field(String key, Kind kind, Tag def) {
	}

	/** Keys the Main / Equipment tabs already edit. */
	private static final Set<String> HANDLED = Set.of("CustomName", "Tags", "equipment", "Passengers", "Silent", "NoAI", "Invulnerable", "Glowing", "NoGravity", "PersistenceRequired", "CustomNameVisible");

	private EntityFields() {
	}

	public static List<Field> list(String entityId) {
		List<Field> out = new ArrayList<>();
		String sample = EntitySerializer.defaultNbt(entityId);
		if (sample == null) {
			return out;
		}
		try {
			CompoundTag tag = TagParser.parseCompoundFully(sample);
			if (isItemEntity(entityId) && !tag.contains("Item")) {
				CompoundTag stone = new CompoundTag(); // an item entity holds an item stack that an empty sample omits
				stone.putString("id", "minecraft:stone");
				stone.putInt("count", 1);
				tag.put("Item", stone);
			}
			tag.keySet().stream().sorted().forEach(key -> {
				Tag value = tag.get(key);
				if (HANDLED.contains(key) || value == null) {
					return;
				}
				if (value instanceof ByteTag b && (b.byteValue() == 0 || b.byteValue() == 1)) {
					out.add(new Field(key, Kind.BOOL, value));
				} else if (value instanceof NumericTag) {
					out.add(new Field(key, Kind.NUMBER, value));
				} else if (value instanceof StringTag) {
					out.add(new Field(key, Kind.TEXT, value));
				} else if (value instanceof CompoundTag c && c.contains("id") && c.contains("count")) {
					out.add(new Field(key, Kind.ITEM, value));
				}
			});
		} catch (Exception e) {
			// the sample could not be parsed back: no typed fields, the NBT tab still works
		}
		return out;
	}

	private static boolean isItemEntity(String entityId) {
		return "minecraft:item".equals(entityId) || "item".equals(entityId);
	}

	/** The override stored in the model NBT, or null when the key is not set there. */
	public static Tag current(String modelNbt, String key) {
		CompoundTag nbt = parse(modelNbt);
		return nbt == null ? null : nbt.get(key);
	}

	/** Text shown in the editor for a tag (numbers without type suffix, strings unquoted). */
	public static String display(Field f, Tag tag) {
		if (tag == null) {
			tag = f.def();
		}
		return switch (tag) {
			case StringTag s -> s.value();
			case FloatTag n -> Float.toString(n.floatValue());
			case DoubleTag n -> Double.toString(n.doubleValue());
			case NumericTag n -> Long.toString(n.longValue());
			default -> tag.toString();
		};
	}

	/** Builds a tag of the same type as the default from typed text, or null when the text is not valid for that type. */
	public static Tag parseValue(Field f, String text) {
		String t = text.trim();
		try {
			return switch (f.def()) {
				case StringTag s -> StringTag.valueOf(text);
				case ByteTag b -> ByteTag.valueOf(Byte.parseByte(t));
				case ShortTag s -> ShortTag.valueOf(Short.parseShort(t));
				case IntTag i -> IntTag.valueOf(Integer.parseInt(t));
				case LongTag l -> LongTag.valueOf(Long.parseLong(t));
				case FloatTag fl -> FloatTag.valueOf(Float.parseFloat(t));
				case DoubleTag d -> DoubleTag.valueOf(Double.parseDouble(t));
				default -> null;
			};
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Writes (or removes, when {@code tag} is null) one key in the model NBT. Returns false when the model NBT is not valid SNBT. */
	public static String set(String modelNbt, String key, Tag tag) {
		CompoundTag nbt = modelNbt == null || modelNbt.isBlank() ? new CompoundTag() : parse(modelNbt);
		if (nbt == null) {
			return null;
		}
		if (tag == null) {
			nbt.remove(key);
		} else {
			nbt.put(key, tag);
		}
		return nbt.isEmpty() ? "" : nbt.toString();
	}

	private static CompoundTag parse(String snbt) {
		if (snbt == null || snbt.isBlank()) {
			return new CompoundTag();
		}
		try {
			return TagParser.parseCompoundFully(snbt);
		} catch (Exception e) {
			return null;
		}
	}
}
