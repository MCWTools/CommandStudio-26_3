package khoa.studio.serialization;

import java.util.LinkedHashMap;
import java.util.Map;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.DataResult;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Item stack <-> command syntax ({@code minecraft:diamond_sword[minecraft:damage=5]}), done with Minecraft's own
 * {@link ItemParser} and the stack / component codecs, so the syntax follows the running version. Needs a world
 * (registries are bound on join).
 */
public final class ItemText {
	private ItemText() {
	}

	public static RegistryAccess access() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? null : mc.level.registryAccess();
	}

	/** Parses {@code id[components]} with the game's item argument parser. The count is not part of the text. */
	public static ItemStack parse(String text) throws CommandSyntaxException {
		RegistryAccess access = access();
		if (access == null) {
			throw new IllegalStateException("Join a world first (registries are not loaded)");
		}
		StringReader reader = new StringReader(text.trim());
		ItemInput input = new ItemParser(access).parse(reader);
		if (reader.canRead()) {
			throw new IllegalArgumentException("Unexpected text after the item: " + reader.getRemaining());
		}
		return input.createItemStack(1);
	}

	/** The stack's component changes as key -> SNBT, in the stack codec's order. A removed component is "!key" -> "". */
	public static Map<String, String> components(ItemStack stack) {
		Map<String, String> out = new LinkedHashMap<>();
		RegistryAccess access = access();
		if (access == null) {
			return out;
		}
		DataResult<Tag> encoded = ItemStack.CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, access), stack);
		Tag tag = encoded.result().orElse(null);
		if (tag instanceof CompoundTag compound && compound.get("components") instanceof CompoundTag comps) {
			for (String key : comps.keySet()) {
				Tag value = comps.get(key);
				out.put(key, key.startsWith("!") ? "" : String.valueOf(value));
			}
		}
		return out;
	}

	/** The default value of a component on a fresh stack of this item as SNBT, or null when the item has none. */
	public static String defaultValue(String itemId, String componentKey) {
		RegistryAccess access = access();
		Identifier item = Identifier.tryParse(itemId);
		Identifier key = Identifier.tryParse(componentKey);
		if (access == null || item == null || key == null) {
			return null;
		}
		Item i = BuiltInRegistries.ITEM.getValue(item);
		DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(key);
		if (i == null || type == null || type.isTransient()) {
			return null;
		}
		Object value = new ItemStack(i).get(type);
		return value == null ? null : encode(type, value, access);
	}

	/**
	 * Value a newly added component starts with when the item has no default for it: the kind's placeholder, or for structured
	 * components the game's own codec result for an empty map (so optional fields show up with their defaults and the user edits a
	 * valid object instead of guessing the shape). Empty when the codec needs required fields.
	 */
	public static String startValue(String componentKey) {
		khoa.studio.component.ComponentKinds.Kind kind = khoa.studio.component.ComponentKinds.of(componentKey);
		if (kind != khoa.studio.component.ComponentKinds.Kind.RAW) {
			return khoa.studio.component.ComponentKinds.placeholder(kind);
		}
		RegistryAccess access = access();
		Identifier key = Identifier.tryParse(componentKey);
		DataComponentType<?> type = key == null ? null : BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(key);
		if (access == null || type == null || type.isTransient()) {
			return "";
		}
		return sample(type, access);
	}

	private static <T> String sample(DataComponentType<T> type, RegistryAccess access) {
		RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, access);
		return type.codecOrThrow().parse(ops, new net.minecraft.nbt.CompoundTag()).result().flatMap(v -> type.codecOrThrow().encodeStart(ops, v).result()).map(String::valueOf).orElse("");
	}

	@SuppressWarnings("unchecked")
	private static <T> String encode(DataComponentType<T> type, Object value, RegistryAccess access) {
		DataResult<Tag> result = type.codecOrThrow().encodeStart(RegistryOps.create(NbtOps.INSTANCE, access), (T) value);
		return result.result().map(String::valueOf).orElse(null);
	}

	public static String idOf(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}
}
