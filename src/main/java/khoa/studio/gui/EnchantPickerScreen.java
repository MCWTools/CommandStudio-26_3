package khoa.studio.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * Picker for the enchantment components: a text list of every enchantment of the running game (no icons) on the left,
 * the enchantments already added with their level on the right. Double click on the left adds one (asks for the level
 * in {@link EnchantLevelScreen}); double click on the right edits the level; X removes. Done hands the map back.
 */
public final class EnchantPickerScreen extends Screen {
	private static final int ROW_H = 16;
	private static final int BAR_H = 20;

	private record Entry(String id, String name, int max, String search) {
	}

	private final Screen parent;
	private final Consumer<Map<String, Integer>> onDone;
	private final Map<String, Integer> levels;
	private final List<Entry> all = new ArrayList<>();
	private List<Entry> left = List.of();
	private final List<String> right = new ArrayList<>();
	private EditBox search;
	private String query = "";
	private int selected = -1;
	private double scrollLeft;
	private double scrollRight;

	private int frameX;
	private int frameW;
	private int panelW;
	private int panelTop;
	private int panelBottom;
	private int barY;

	public EnchantPickerScreen(Screen parent, Map<String, Integer> initial, Consumer<Map<String, Integer>> onDone) {
		super(Component.literal("Enchantments"));
		this.parent = parent;
		this.onDone = onDone;
		this.levels = new LinkedHashMap<>(initial);
		Registry<Enchantment> registry = net.minecraft.client.Minecraft.getInstance().level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
		registry.listElements().forEach(holder -> {
			Enchantment e = holder.value();
			String id = registry.getKey(e).toString();
			String name = e.description().getString();
			all.add(new Entry(id, name, e.getMaxLevel(), (name + " " + id).toLowerCase(Locale.ROOT)));
		});
		all.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
	}

	@Override
	protected void init() {
		frameW = Math.min(this.width - 24, 640);
		frameX = (this.width - frameW) / 2;
		panelW = (frameW - 10) / 2;
		panelTop = 46;
		barY = this.height - 28;
		panelBottom = barY - 6;
		search = new EditBox(this.font, frameX, 22, frameW, 18, Component.literal("Search"));
		search.setHint(Component.literal("Search enchantments..."));
		search.setValue(query);
		search.setResponder(v -> {
			query = v.trim().toLowerCase(Locale.ROOT);
			scrollLeft = 0;
			rebuild();
		});
		this.addRenderableWidget(search);
		rebuild();
	}

	private void rebuild() {
		List<Entry> out = new ArrayList<>();
		for (Entry e : all) {
			if (!levels.containsKey(e.id()) && (query.isEmpty() || e.search().contains(query))) {
				out.add(e);
			}
		}
		left = out;
		right.clear();
		right.addAll(levels.keySet());
		scrollLeft = Math.max(0, Math.min(scrollLeft, maxScroll(left.size())));
		scrollRight = Math.max(0, Math.min(scrollRight, maxScroll(right.size())));
	}

	private double maxScroll(int rows) {
		return Math.max(0, rows * ROW_H - (panelBottom - panelTop - 18));
	}

	private Entry byId(String id) {
		for (Entry e : all) {
			if (e.id().equals(id)) {
				return e;
			}
		}
		return new Entry(id, id, 1, id);
	}

	private int rowAt(boolean rightSide, double mx, double my) {
		int px = rightSide ? frameX + panelW + 10 : frameX;
		if (mx < px || mx >= px + panelW || my < panelTop + 18 || my >= panelBottom) {
			return -1;
		}
		int index = (int) ((my - (panelTop + 18) + (rightSide ? scrollRight : scrollLeft)) / ROW_H);
		return index < (rightSide ? right.size() : left.size()) ? index : -1;
	}

	private void askLevel(Entry e, int initial) {
		this.minecraft.gui.setScreen(new EnchantLevelScreen(this, e.name(), e.max(), initial, level -> {
			levels.put(e.id(), level);
		}));
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		double mx = event.x();
		double my = event.y();
		if (my >= barY && my < barY + BAR_H) {
			if (mx >= frameX && mx < frameX + 70) {
				onClose();
				return true;
			}
			if (mx >= frameX + frameW - 90 && mx < frameX + frameW) {
				onDone.accept(new LinkedHashMap<>(levels)); // the caller stores the result first, then shows its screen again
				this.minecraft.gui.setScreen(parent);
				return true;
			}
		}
		int l = rowAt(false, mx, my);
		if (l >= 0) {
			selected = l;
			if (doubleClick) {
				askLevel(left.get(l), left.get(l).max());
			}
			return true;
		}
		int r = rowAt(true, mx, my);
		if (r >= 0) {
			String id = right.get(r);
			int px = frameX + panelW + 10;
			if (mx >= px + panelW - 18) {
				levels.remove(id); // the X
				rebuild();
			} else if (doubleClick) {
				askLevel(byId(id), levels.get(id));
			}
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double sx, double sy) {
		if (y >= panelTop && y < panelBottom) {
			if (x >= frameX + panelW + 10) {
				scrollRight = Math.max(0, Math.min(scrollRight - sy * 16, maxScroll(right.size())));
			} else {
				scrollLeft = Math.max(0, Math.min(scrollLeft - sy * 16, maxScroll(left.size())));
			}
			return true;
		}
		return super.mouseScrolled(x, y, sx, sy);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}

	// --- hooks for StudioSelfTest ---
	EditBox searchBox() {
		return search;
	}

	int leftCount() {
		return left.size();
	}

	Integer levelOf(String id) {
		return levels.get(id);
	}

	double[] leftRowCenter(int i) {
		return new double[] { frameX + panelW / 2.0, panelTop + 18 - scrollLeft + i * ROW_H + ROW_H / 2.0 };
	}

	double[] rightRowCenter(int i) {
		return new double[] { frameX + panelW + 10 + panelW / 2.0, panelTop + 18 - scrollRight + i * ROW_H + ROW_H / 2.0 };
	}

	double[] doneCenter() {
		return new double[] { frameX + frameW - 45.0, barY + BAR_H / 2.0 };
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		g.text(this.font, "Enchantments", frameX, 8, 0xFFFFFFFF);
		renderPanel(g, false, mouseX, mouseY);
		renderPanel(g, true, mouseX, mouseY);
		g.fill(frameX, barY, frameX + 70, barY + BAR_H, 0xA0202020);
		g.centeredText(this.font, "< Back", frameX + 35, barY + 6, 0xFFFFFFFF);
		g.fill(frameX + frameW - 90, barY, frameX + frameW, barY + BAR_H, 0xA02E6E9E);
		g.centeredText(this.font, "Done (" + levels.size() + ")", frameX + frameW - 45, barY + 6, 0xFFFFFFFF);
		g.text(this.font, this.font.plainSubstrByWidth("Double click: add / edit level   X: remove   Level 1-255", Math.max(0, frameW - 80 - 96)), frameX + 80, barY + 6, 0xFF888888);
		super.extractRenderState(g, mouseX, mouseY, partialTick);
	}

	private void renderPanel(GuiGraphicsExtractor g, boolean rightSide, int mouseX, int mouseY) {
		int px = rightSide ? frameX + panelW + 10 : frameX;
		g.fill(px, panelTop, px + panelW, panelBottom, 0xA0101010);
		int count = rightSide ? right.size() : left.size();
		g.text(this.font, (rightSide ? "Added (" : "Available (") + count + ")", px + 5, panelTop + 4, 0xFFFFFFFF);
		double scroll = rightSide ? scrollRight : scrollLeft;
		g.enableScissor(px, panelTop + 16, px + panelW, panelBottom);
		for (int i = 0; i < count; i++) {
			int y = panelTop + 18 - (int) scroll + i * ROW_H;
			if (y + ROW_H < panelTop + 16 || y > panelBottom) {
				continue;
			}
			boolean hover = mouseX >= px && mouseX < px + panelW && mouseY >= y && mouseY < y + ROW_H && mouseY >= panelTop + 18 && mouseY < panelBottom;
			boolean sel = !rightSide && i == selected;
			g.fill(px + 2, y, px + panelW - 2, y + ROW_H - 1, sel ? 0xA02E6E9E : (hover ? 0xB0383838 : 0x60202020));
			if (rightSide) {
				String id = right.get(i);
				Entry e = byId(id);
				int level = levels.get(id);
				g.text(this.font, this.font.plainSubstrByWidth(e.name(), panelW - 120), px + 6, y + 4, 0xFFFFFFFF);
				boolean high = level > e.max();
				String lv = "Lv " + level + (high ? " (max " + e.max() + ")" : "");
				g.text(this.font, lv, px + panelW - 22 - this.font.width(lv), y + 4, high ? 0xFFFFDD55 : 0xFF55FF77);
				g.text(this.font, "X", px + panelW - 12, y + 4, 0xFFFF6655);
			} else {
				Entry e = left.get(i);
				g.text(this.font, this.font.plainSubstrByWidth(e.name(), panelW - 70), px + 6, y + 4, 0xFFFFFFFF);
				String max = "max " + e.max();
				g.text(this.font, max, px + panelW - 8 - this.font.width(max), y + 4, 0xFF888888);
			}
		}
		g.disableScissor();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, this.width, this.height, 0xC0101010);
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
