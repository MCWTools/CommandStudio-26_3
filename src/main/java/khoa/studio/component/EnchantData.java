package khoa.studio.component;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;

/** Enchantment component value <-> map id -> level. The SNBT shape is whatever the game's own codec accepts: {@code {"minecraft:sharpness":5}}. */
public final class EnchantData {
	public static final int MAX_LEVEL = 255; // the /give limit

	private EnchantData() {
	}

	public static Map<String, Integer> parse(String snbt) {
		Map<String, Integer> out = new LinkedHashMap<>();
		if (snbt == null || snbt.isBlank()) {
			return out;
		}
		try {
			CompoundTag tag = TagParser.parseCompoundFully(snbt);
			for (String key : tag.keySet()) {
				out.put(key, tag.getIntOr(key, 1));
			}
		} catch (Exception e) {
			// unreadable value: start from an empty list, the raw text stays in the item text box until the user picks something
		}
		return out;
	}

	public static String toSnbt(Map<String, Integer> levels) {
		CompoundTag tag = new CompoundTag();
		levels.forEach(tag::putInt);
		return tag.toString();
	}
}
