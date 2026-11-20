package khoa.studio.give;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.world.item.ItemStack;

import khoa.studio.serialization.ItemText;

/**
 * State of the Give generator. Plain data (saved as JSON): the GUI edits this, the serializer reads it.
 * Components are kept as key + SNBT text so invalid input is preserved instead of being dropped.
 */
public final class GiveModel {
	/** One component change. Removed components have key "!minecraft:x" and no value. */
	public static final class Comp {
		public String key;
		public String value;
		public boolean expanded = true;

		public Comp(String key, String value) {
			this.key = key;
			this.value = value;
		}

		public boolean removal() {
			return key.startsWith("!");
		}

		/** Changed from the item's own default (a placeholder that was never edited does not count). */
		public boolean modified(String itemId) {
			String def = ItemText.defaultValue(itemId, key);
			if (def == null) {
				def = ItemText.startValue(key);
			}
			return !def.equals(value);
		}
	}

	public String item = "minecraft:diamond_sword";
	public int count = 1;
	public String target = "@p";
	public List<Comp> components = new ArrayList<>();

	/** The item part of the command: {@code id[key=value,...]} (no count). */
	public String itemText() {
		if (components.isEmpty()) {
			return item;
		}
		StringBuilder sb = new StringBuilder(item).append('[');
		for (int i = 0; i < components.size(); i++) {
			Comp c = components.get(i);
			if (i > 0) {
				sb.append(',');
			}
			sb.append(c.removal() ? c.key : c.key + "=" + c.value);
		}
		return sb.append(']').toString();
	}

	/** Replaces item and components with those of a stack (picker or parsed text). The count is only taken when {@code withCount}. */
	public void setFrom(ItemStack stack, boolean withCount) {
		item = ItemText.idOf(stack);
		components = new ArrayList<>();
		for (Map.Entry<String, String> e : ItemText.components(stack).entrySet()) {
			components.add(new Comp(e.getKey(), e.getValue()));
		}
		if (withCount) {
			count = Math.max(1, stack.getCount());
		}
	}

	public void reset() {
		item = "minecraft:diamond_sword";
		count = 1;
		target = "@p";
		components = new ArrayList<>();
	}
}
