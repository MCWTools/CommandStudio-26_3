package khoa.studio.give;

import net.minecraft.world.item.ItemStack;

import khoa.studio.serialization.ItemText;
import khoa.studio.serialization.ValidationResult;

/** GiveModel -> {@code /give <target> <item>[components] <count>}. The item text is validated by the game's own item parser. */
public final class GiveSerializer {
	private GiveSerializer() {
	}

	public static ValidationResult serialize(GiveModel model) {
		if (model.target.isBlank()) {
			return ValidationResult.error("Target is empty");
		}
		if (model.count < 1) {
			return ValidationResult.error("Count must be at least 1");
		}
		ItemStack stack;
		try {
			stack = ItemText.parse(model.itemText());
		} catch (Exception e) {
			return ValidationResult.error(message(e));
		}
		if (stack.isEmpty()) {
			return ValidationResult.error("Empty item");
		}
		return ValidationResult.ok("/give " + model.target.trim() + " " + model.itemText() + " " + model.count);
	}

	/** Error of one component only: parses {@code id[key=value]} on its own. Null when fine. */
	public static String validateComponent(String itemId, GiveModel.Comp comp) {
		try {
			ItemText.parse(itemId + "[" + (comp.removal() ? comp.key : comp.key + "=" + comp.value) + "]");
			return null;
		} catch (Exception e) {
			return message(e);
		}
	}

	public static int maxStack(GiveModel model) {
		try {
			return Math.max(1, ItemText.parse(model.itemText()).getMaxStackSize());
		} catch (Exception e) {
			return 64;
		}
	}

	private static String message(Exception e) {
		String m = e.getMessage();
		return m == null ? e.getClass().getSimpleName() : m;
	}
}
