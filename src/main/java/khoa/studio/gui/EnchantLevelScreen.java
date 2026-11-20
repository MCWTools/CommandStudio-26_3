package khoa.studio.gui;

import java.util.function.IntConsumer;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import khoa.studio.component.EnchantData;

/**
 * Small dialog that asks for one enchantment level: 1 up to 255 (the /give limit); 0, negatives and text are refused.
 * Above the enchantment's normal max level it only shows a notice, it is not an error.
 */
public final class EnchantLevelScreen extends Screen {
	private static final int W = 240;
	private static final int H = 96;

	private final Screen parent;
	private final String name;
	private final int normalMax;
	private final IntConsumer onLevel;
	private EditBox box;
	private String initial;

	public EnchantLevelScreen(Screen parent, String name, int normalMax, int initialLevel, IntConsumer onLevel) {
		super(Component.literal("Enchantment level"));
		this.parent = parent;
		this.name = name;
		this.normalMax = normalMax;
		this.onLevel = onLevel;
		this.initial = String.valueOf(initialLevel);
	}

	@Override
	protected void init() {
		int x = (this.width - W) / 2;
		int y = (this.height - H) / 2;
		String keep = box == null ? initial : box.getValue();
		box = new EditBox(this.font, x + 70, y + 28, 60, 18, Component.literal("Level"));
		box.setMaxLength(5);
		box.setValue(keep);
		box.setResponder(v -> {
			String digits = v.replaceAll("[^0-9]", ""); // no minus sign, no text
			if (!digits.equals(v)) {
				box.setValue(digits);
			}
		});
		this.addRenderableWidget(box);
		this.setInitialFocus(box);
	}

	/** The level typed so far, or -1 when it is not a valid 1..255 number. */
	int level() {
		try {
			int v = Integer.parseInt(box.getValue().trim());
			return v >= 1 && v <= EnchantData.MAX_LEVEL ? v : -1;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/** Red error text, or null when the level is fine. */
	String error() {
		String t = box.getValue().trim();
		if (t.isEmpty()) {
			return "Enter a level";
		}
		try {
			int v = Integer.parseInt(t);
			if (v < 1) {
				return "Level must be at least 1";
			}
			if (v > EnchantData.MAX_LEVEL) {
				return "Maximum is " + EnchantData.MAX_LEVEL + " (the /give limit)";
			}
			return null;
		} catch (NumberFormatException e) {
			return "Not a number";
		}
	}

	/** Yellow notice (not an error): the level is above the normal maximum. */
	String notice() {
		int v = level();
		return v > normalMax ? "Higher than the normal max level (" + normalMax + "). Allowed." : null;
	}

	private void confirm() {
		int v = level();
		if (v >= 1) {
			onLevel.accept(v); // the caller stores it first, then its screen is shown again
			this.minecraft.gui.setScreen(parent);
		}
	}

	double[] okCenter() {
		return new double[] { (this.width - W) / 2.0 + W - 90 + 40, (this.height - H) / 2.0 + H - 26 + 10 };
	}

	EditBox levelBox() {
		return box;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		int x = (this.width - W) / 2;
		int y = (this.height - H) / 2;
		double mx = event.x();
		double my = event.y();
		if (my >= y + H - 26 && my < y + H - 6) {
			if (mx >= x + W - 90 && mx < x + W - 10) {
				confirm();
				return true;
			}
			if (mx >= x + 10 && mx < x + 90) {
				onClose();
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
			confirm();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		int x = (this.width - W) / 2;
		int y = (this.height - H) / 2;
		g.fill(x, y, x + W, y + H, 0xF0101010);
		g.fill(x, y, x + W, y + 1, 0xFF777777);
		g.fill(x, y + H - 1, x + W, y + H, 0xFF777777);
		g.fill(x, y, x + 1, y + H, 0xFF777777);
		g.fill(x + W - 1, y, x + W, y + H, 0xFF777777);
		g.text(this.font, this.font.plainSubstrByWidth(name, W - 16), x + 8, y + 8, 0xFFFFFFFF);
		g.text(this.font, "Level", x + 8, y + 33, 0xFFAAAAAA);
		g.text(this.font, this.font.plainSubstrByWidth("1-" + normalMax + " normal, up to " + EnchantData.MAX_LEVEL, W - 144), x + 136, y + 33, 0xFF888888);
		String error = error();
		String notice = notice();
		if (error != null) {
			g.text(this.font, this.font.plainSubstrByWidth(error, W - 16), x + 8, y + 54, 0xFFFF5555);
		} else if (notice != null) {
			g.text(this.font, this.font.plainSubstrByWidth(notice, W - 16), x + 8, y + 54, 0xFFFFDD55);
		}
		boolean ok = error == null;
		g.fill(x + 10, y + H - 26, x + 90, y + H - 6, 0xA0202020);
		g.centeredText(this.font, "Cancel", x + 50, y + H - 20, 0xFFFFFFFF);
		g.fill(x + W - 90, y + H - 26, x + W - 10, y + H - 6, ok ? 0xA02E6E9E : 0x70101010);
		g.centeredText(this.font, "OK", x + W - 50, y + H - 20, ok ? 0xFFFFFFFF : 0xFF777777);
		super.extractRenderState(g, mouseX, mouseY, partialTick);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, this.width, this.height, 0xA0101010);
	}

	@Override
	public void extractTransparentBackground(GuiGraphicsExtractor graphics) {
	}

	@Override
	protected void extractBlurredBackground(GuiGraphicsExtractor graphics) {
	}

	@Override
	protected void extractMenuBackground(GuiGraphicsExtractor graphics) {
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
