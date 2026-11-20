package khoa.studio.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import khoa.api.gui.KhoaBannerEditor;
import khoa.api.gui.KhoaEffectPicker;
import khoa.api.gui.KhoaTrimEditor;
import khoa.studio.component.ComponentKinds;
import khoa.studio.give.GiveModel;
import khoa.studio.serialization.ItemText;

/**
 * Item specific editors of the Give / item entity GUI. Which tools an item gets is read from the game (its default components and
 * tags), never from a list: a potion-like item has potion_contents, a banner or shield has banner_patterns, armor is in the
 * trimmable armor tag. Each tool edits one component of the {@link GiveModel} and writes it back as SNBT.
 */
public final class ItemTools {
	public enum Tool {
		EFFECTS("Effects"), BANNER("Banner"), TRIM("Trim");

		public final String label;

		Tool(String label) {
			this.label = label;
		}
	}

	private ItemTools() {
	}

	public static List<Tool> forItem(String itemId) {
		List<Tool> out = new ArrayList<>();
		Identifier id = Identifier.tryParse(itemId);
		Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
		if (item == null) {
			return out;
		}
		ItemStack stack = new ItemStack(item);
		if (stack.has(DataComponents.POTION_CONTENTS)) {
			out.add(Tool.EFFECTS);
		}
		if (stack.has(DataComponents.BANNER_PATTERNS)) {
			out.add(Tool.BANNER);
		}
		if (stack.is(ItemTags.TRIMMABLE_ARMOR)) {
			out.add(Tool.TRIM);
		}
		return out;
	}

	private static GiveModel.Comp find(GiveModel m, String key) {
		for (GiveModel.Comp c : m.components) {
			if (c.key.equals(key) || c.key.equals("minecraft:" + key)) {
				return c;
			}
		}
		return null;
	}

	private static GiveModel.Comp ensure(GiveModel m, String key, String start) {
		GiveModel.Comp c = find(m, key);
		if (c == null) {
			String def = ItemText.defaultValue(m.item, "minecraft:" + key);
			c = new GiveModel.Comp("minecraft:" + key, def == null ? start : def);
			m.components.add(c);
		}
		return c;
	}

	private static CompoundTag compound(String snbt) {
		try {
			return snbt == null || snbt.isBlank() ? new CompoundTag() : TagParser.parseCompoundFully(snbt);
		} catch (Exception e) {
			return new CompoundTag();
		}
	}

	private static ListTag list(String snbt) {
		try {
			return snbt == null || snbt.isBlank() ? new ListTag() : TagParser.parseCompoundFully("{a:" + snbt + "}").getListOrEmpty("a");
		} catch (Exception e) {
			return new ListTag();
		}
	}

	/** Opens the tool; {@code changed} runs after the model was edited (the caller refreshes its widgets). */
	public static void open(Tool tool, Screen parent, GiveModel m, Runnable changed) {
		switch (tool) {
			case EFFECTS -> {
				GiveModel.Comp c = find(m, "potion_contents");
				CompoundTag tag = compound(c == null ? null : c.value);
				List<CompoundTag> effects = KhoaEffectPicker.fromList(tag.getListOrEmpty("custom_effects"));
				net.minecraft.client.Minecraft.getInstance().gui.setScreen(new KhoaEffectPicker(parent, effects, result -> {
					GiveModel.Comp comp = ensure(m, "potion_contents", "{}");
					CompoundTag t = compound(comp.value);
					if (result.isEmpty()) {
						t.remove("custom_effects");
					} else {
						ListTag l = new ListTag();
						l.addAll(result);
						t.put("custom_effects", l);
					}
					comp.value = t.toString();
					changed.run();
				}));
			}
			case BANNER -> {
				boolean bannerItem = m.item.endsWith("_banner");
				GiveModel.Comp base = find(m, "base_color");
				String color = bannerItem ? m.item.substring(m.item.indexOf(':') + 1, m.item.length() - "_banner".length()) : base == null ? "white" : ComponentKinds.unquote(base.value);
				GiveModel.Comp c = find(m, "banner_patterns");
				net.minecraft.client.Minecraft.getInstance().gui.setScreen(new KhoaBannerEditor(parent, color, list(c == null ? null : c.value), result -> {
					if (bannerItem) {
						m.item = "minecraft:" + result.baseColor() + "_banner";
					} else {
						ensure(m, "base_color", ComponentKinds.quote(result.baseColor())).value = ComponentKinds.quote(result.baseColor());
					}
					ensure(m, "banner_patterns", "[]").value = result.layers();
					changed.run();
				}));
			}
			case TRIM -> {
				GiveModel.Comp c = find(m, "trim");
				CompoundTag t = compound(c == null ? null : c.value);
				net.minecraft.client.Minecraft.getInstance().gui.setScreen(new KhoaTrimEditor(parent, t.getStringOr("pattern", null), t.getStringOr("material", null), snbt -> {
					ensure(m, "trim", snbt).value = snbt;
					changed.run();
				}));
			}
		}
	}
}
