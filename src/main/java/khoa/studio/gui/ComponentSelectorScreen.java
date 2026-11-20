package khoa.studio.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;

/**
 * Searchable list of every item component type of the running game, read from the live component registry
 * (no hardcoded list). Types without a serializer (transient / network only) are not offered.
 */
public final class ComponentSelectorScreen extends Screen {
	private static final int ROW_H = 22;
	private record Row(String key, String label, String search) {
	}

	private final Screen parent;
	private final Consumer<String> onPick;
	private final List<Row> all = new ArrayList<>();
	private List<Row> view = List.of();
	private EditBox search;
	private String query = "";
	private double scroll;

	/** Generic list: rows are {key, label}. Used for the entity selector. */
	public ComponentSelectorScreen(Screen parent, String title, List<String[]> rows, Consumer<String> onPick) {
		super(Component.literal(title));
		this.parent = parent;
		this.onPick = onPick;
		for (String[] r : rows) {
			all.add(new Row(r[0], r[1], (r[1] + " " + r[0]).toLowerCase(Locale.ROOT)));
		}
		all.sort((a, b) -> a.label().compareToIgnoreCase(b.label()));
	}

	public ComponentSelectorScreen(Screen parent, Consumer<String> onPick) {
		super(Component.literal("Add component"));
		this.parent = parent;
		this.onPick = onPick;
		for (DataComponentType<?> type : BuiltInRegistries.DATA_COMPONENT_TYPE) {
			if (type.isTransient() || type.codec() == null) {
				continue;
			}
			String key = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type).toString();
			String path = key.substring(key.indexOf(':') + 1).replace('_', ' ');
			String label = Character.toUpperCase(path.charAt(0)) + path.substring(1);
			all.add(new Row(key, label, (label + " " + key).toLowerCase(Locale.ROOT)));
		}
		all.sort((a, b) -> a.key().compareTo(b.key()));
	}

	@Override
	protected void init() {
		search = new EditBox(this.font, this.width / 2 - 150, 22, 300, 18, Component.literal("Search"));
		search.setHint(Component.literal("Search components..."));
		search.setValue(query);
		search.setResponder(v -> {
			query = v.trim().toLowerCase(Locale.ROOT);
			scroll = 0;
			filter();
		});
		this.addRenderableWidget(search);
		filter();
	}

	private void filter() {
		List<Row> out = new ArrayList<>();
		for (Row r : all) {
			if (query.isEmpty() || r.search().contains(query)) {
				out.add(r);
			}
		}
		view = out;
		scroll = Math.max(0, Math.min(scroll, maxScroll()));
	}

	private int top() {
		return 46;
	}

	private int bottom() {
		return this.height - 30;
	}

	private double maxScroll() {
		return Math.max(0, view.size() * ROW_H - (bottom() - top()));
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
		if (my >= this.height - 24 && my < this.height - 4 && mx >= this.width / 2 - 35 && mx < this.width / 2 + 35) {
			onClose();
			return true;
		}
		int left = this.width / 2 - 150;
		if (mx >= left && mx < left + 300 && my >= top() && my < bottom()) {
			int index = (int) ((my - top() + scroll) / ROW_H);
			if (index >= 0 && index < view.size()) {
				onPick.accept(view.get(index).key()); // update the caller's model first, then show it again (its init reads the model)
				this.minecraft.gui.setScreen(parent);
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double sx, double sy) {
		scroll = Math.max(0, Math.min(scroll - sy * 22, maxScroll()));
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		return super.keyPressed(event);
	}

	// --- hooks for StudioSelfTest (the in-process GUI test) ---
	EditBox searchBox() {
		return search;
	}

	int viewSize() {
		return view.size();
	}

	int totalSize() {
		return all.size();
	}

	double[] rowCenter(int index) {
		return new double[] { this.width / 2.0, top() - scroll + index * ROW_H + ROW_H / 2.0 };
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		g.centeredText(this.font, "Add component  (" + view.size() + ")", this.width / 2, 8, 0xFFFFFFFF);
		int left = this.width / 2 - 150;
		g.fill(left, top(), left + 300, bottom(), 0xA0101010);
		g.enableScissor(left, top(), left + 300, bottom());
		for (int i = 0; i < view.size(); i++) {
			int y = top() - (int) scroll + i * ROW_H;
			if (y + ROW_H < top() || y > bottom()) {
				continue;
			}
			boolean hover = mouseX >= left && mouseX < left + 300 && mouseY >= y && mouseY < y + ROW_H && mouseY >= top() && mouseY < bottom();
			g.fill(left + 2, y + 1, left + 298, y + ROW_H - 1, hover ? 0xB0383838 : 0x90202020);
			g.text(this.font, this.font.plainSubstrByWidth(view.get(i).label(), Math.max(20, this.width - left * 2 - 12)), left + 6, y + 3, 0xFFFFFFFF);
			g.text(this.font, this.font.plainSubstrByWidth(view.get(i).key(), Math.max(20, this.width - left * 2 - 12)), left + 6, y + 12, 0xFF888888);
		}
		g.disableScissor();
		g.fill(this.width / 2 - 35, this.height - 24, this.width / 2 + 35, this.height - 4, 0xA0202020);
		g.centeredText(this.font, "< Back", this.width / 2, this.height - 18, 0xFFFFFFFF);
		super.extractRenderState(g, mouseX, mouseY, partialTick);
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
