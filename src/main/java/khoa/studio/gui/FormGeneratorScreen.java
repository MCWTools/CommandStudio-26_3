package khoa.studio.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import khoa.api.gui.KhoaListPicker;
import khoa.studio.form.FormDef;
import khoa.studio.form.Forms;
import khoa.studio.persistence.StudioState;
import khoa.studio.run.CommandCheck;

/**
 * One screen for every simple generator (Setblock, Fill, Particle, Effect, Tellraw, ...): the fields of its {@link FormDef}
 * (text boxes, choice buttons, registry pickers, tick boxes), a live command, and the same Run / Copy / Reset / Close row as the other
 * generators. The command is checked by the game's own command parser; the values are saved in the Command Studio config.
 */
public final class FormGeneratorScreen extends Screen {
	private static final int WHITE = 0xFFFFFFFF;
	private static final int DIM = 0xFFAAAAAA;
	private static final int RED = 0xFFFF5555;
	private static final int GREEN = 0xFF55FF77;
	private static final int ROW = 24;

	private record Btn(String id, int x, int y, int w, int h) {
		boolean hit(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private final FormDef def;
	private final Map<String, String> values;
	private final List<Btn> buttons = new ArrayList<>();
	private final Map<String, EditBox> boxes = new LinkedHashMap<>();
	private MultiLineEditBox outputBox;
	private String outputText = "";
	private String outputError;
	private String status = "";
	private long statusUntil;
	private long resetArmedUntil;
	private int scroll;
	private boolean pendingRebuild;

	private int fx;
	private int wx;
	private int sideW;
	private int ww;
	private int outTop;

	private FormGeneratorScreen(FormDef def) {
		super(Component.literal("Command Studio - " + def.label()));
		this.def = def;
		this.values = StudioState.get().forms.computeIfAbsent(def.id(), k -> new LinkedHashMap<>());
		for (FormDef.Field f : def.fields()) {
			values.putIfAbsent(f.key(), f.def());
		}
	}

	public static void open(String id) {
		FormDef def = Forms.get(id);
		if (def != null) {
			Minecraft.getInstance().gui.setScreen(new FormGeneratorScreen(def));
		}
	}

	// ------------------------------------------------------------------ layout

	private List<FormDef.Field> visibleFields() {
		List<FormDef.Field> out = new ArrayList<>();
		for (FormDef.Field f : def.fields()) {
			if (f.visible(values)) {
				out.add(f);
			}
		}
		return out;
	}

	private int fieldTop() {
		return 40;
	}

	private int visibleRows() {
		return Math.max(1, (outTop - 6 - fieldTop()) / ROW);
	}

	private int labelW() {
		return Math.max(70, Math.min(190, ww / 3));
	}

	@Override
	protected void init() {
		int frameW = Math.min(this.width - 16, 780);
		fx = (this.width - frameW) / 2;
		sideW = this.width < 520 ? 0 : Sidebar.WIDTH;
		wx = fx + (sideW > 0 ? sideW + 8 : 0);
		ww = frameW - (wx - fx);
		outTop = this.height - 80;

		boxes.clear();
		for (FormDef.Field f : def.fields()) {
			if (f.type() != FormDef.Type.TEXT) {
				continue;
			}
			EditBox b = new EditBox(this.font, 0, 0, 100, 18, Component.literal(f.label()));
			b.setMaxLength(32000);
			b.setValue(values.getOrDefault(f.key(), f.def()));
			b.setResponder(v -> {
				values.put(f.key(), v);
				refresh();
			});
			b.visible = false;
			this.addRenderableWidget(b);
			boxes.put(f.key(), b);
		}
		outputBox = MultiLineEditBox.builder().build(this.font, ww, 28, Component.literal("Output"));
		outputBox.setX(wx);
		outputBox.setY(outTop + 10);
		outputBox.setCharacterLimit(200000);
		outputBox.setValueListener(v -> {
			if (!v.equals(outputText)) {
				outputBox.setValue(outputText);
			}
		});
		this.addRenderableWidget(outputBox);
		refresh();
	}

	private void refresh() {
		String command;
		try {
			command = def.build().apply(values);
		} catch (RuntimeException e) {
			command = "";
			outputError = "Cannot build the command: " + e.getMessage();
			outputText = "";
			return;
		}
		outputError = CommandCheck.validate(command);
		outputText = outputError == null ? command : "";
		if (outputBox != null && !outputBox.getValue().equals(outputText)) {
			outputBox.setValue(outputText);
		}
		StudioState.markDirty();
	}

	private void say(String text) {
		status = text;
		statusUntil = System.currentTimeMillis() + 3000;
	}

	// ------------------------------------------------------------------ actions

	private FormDef.Field field(String key) {
		for (FormDef.Field f : def.fields()) {
			if (f.key().equals(key)) {
				return f;
			}
		}
		return null;
	}

	private void cycle(FormDef.Field f) {
		List<String> options = f.options();
		int i = options.indexOf(values.getOrDefault(f.key(), f.def()));
		values.put(f.key(), options.get((i + 1) % options.size()));
		refresh();
		pendingRebuild = true; // other fields may appear or disappear
	}

	private void pickRegistry(FormDef.Field f) {
		List<String[]> rows = f.registry().get();
		if (rows.isEmpty()) {
			say("Join a world to list " + f.label());
			return;
		}
		KhoaListPicker.open(this, "Pick: " + f.label(), rows, key -> {
			values.put(f.key(), key);
			refresh();
			pendingRebuild = true;
		});
	}

	@Override
	public void tick() {
		if (pendingRebuild) {
			pendingRebuild = false;
			this.rebuildWidgets();
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		if (sideW > 0 && Sidebar.click(event.x(), event.y(), fx, this.height, def.id())) {
			return true;
		}
		for (Btn b : buttons) {
			if (!b.hit(event.x(), event.y())) {
				continue;
			}
			String id = b.id();
			if (id.startsWith("c:")) {
				cycle(field(id.substring(2)));
			} else if (id.startsWith("r:")) {
				pickRegistry(field(id.substring(2)));
			} else if (id.startsWith("b:")) {
				String key = id.substring(2);
				values.put(key, values.get(key).equals("true") ? "false" : "true");
				refresh();
				pendingRebuild = true;
			} else {
				switch (id) {
					case "copy" -> {
						if (outputText.isEmpty()) {
							say("Nothing to copy");
						} else {
							this.minecraft.keyboardHandler.setClipboard(outputText);
							say("Copied");
						}
					}
					case "run" -> {
						if (outputError != null) {
							say("Not run: " + outputError);
						} else {
							RunFlow.run(this, outputText, this::say);
						}
					}
					case "reset" -> {
						long now = System.currentTimeMillis();
						if (now > resetArmedUntil) {
							resetArmedUntil = now + 3000;
							say("Click Reset again to clear this generator");
						} else {
							resetArmedUntil = 0;
							for (FormDef.Field f : def.fields()) {
								values.put(f.key(), f.def());
							}
							pendingRebuild = true;
						}
					}
					case "close" -> onClose();
					case "gen" -> Sidebar.openNext(def.id());
					default -> {
					}
				}
			}
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double sx, double sy) {
		if (sideW > 0 && Sidebar.scrolled(x, y, fx, this.height, sy)) {
			return true;
		}
		scroll = (int) Math.max(0, Math.min(visibleFields().size() - visibleRows(), scroll - Math.signum(sy)));
		return true;
	}

	@Override
	public void removed() {
		StudioState.get().lastGenerator = def.id();
		StudioState.saveNow();
	}

	// ------------------------------------------------------------------ render

	private void button(GuiGraphicsExtractor g, String id, int x, int y, int w, int h, String label, int mx, int my, boolean enabled) {
		Btn b = new Btn(id, x, y, w, h);
		if (enabled) {
			buttons.add(b);
		}
		boolean hover = enabled && b.hit(mx, my);
		g.fill(x, y, x + w, y + h, hover ? 0xC0505050 : 0xA0202020);
		g.fill(x, y, x + w, y + 1, 0xFF777777);
		g.fill(x, y + h - 1, x + w, y + h, 0xFF777777);
		g.fill(x, y, x + 1, y + h, 0xFF777777);
		g.fill(x + w - 1, y, x + w, y + h, 0xFF777777);
		g.centeredText(this.font, this.font.plainSubstrByWidth(label, w - 6), x + w / 2, y + (h - 8) / 2, enabled ? WHITE : DIM);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		buttons.clear();
		g.text(this.font, "Command Studio", wx, 8, WHITE);
		if (sideW > 0) {
			Sidebar.render(g, this.font, fx, this.height, def.id(), mouseX, mouseY);
		} else {
			button(g, "gen", wx + ww - 70, 4, 70, 14, Sidebar.otherLabel(def.id()), mouseX, mouseY, true);
		}
		g.text(this.font, this.font.plainSubstrByWidth(def.label(), ww), wx, 24, DIM);

		for (EditBox b : boxes.values()) {
			b.visible = false;
		}
		List<FormDef.Field> visible = visibleFields();
		int rows = visibleRows();
		scroll = Math.max(0, Math.min(scroll, Math.max(0, visible.size() - rows)));
		int labelW = labelW();
		int cx = wx + labelW + 6;
		int cw = Math.max(80, ww - labelW - 6 - 6);
		for (int i = scroll; i < visible.size() && i < scroll + rows; i++) {
			FormDef.Field f = visible.get(i);
			int y = fieldTop() + (i - scroll) * ROW;
			g.text(this.font, this.font.plainSubstrByWidth(f.label(), labelW - 4), wx, y + 5, DIM);
			String value = values.getOrDefault(f.key(), f.def());
			switch (f.type()) {
				case TEXT -> {
					EditBox b = boxes.get(f.key());
					b.setX(cx);
					b.setY(y);
					b.setWidth(cw);
					b.visible = true;
				}
				case CHOICE -> button(g, "c:" + f.key(), cx, y, Math.min(cw, 200), 18, "< " + value + " >", mouseX, mouseY, true);
				case BOOL -> button(g, "b:" + f.key(), cx, y, 90, 18, (value.equals("true") ? "[x] " : "[ ] ") + value, mouseX, mouseY, true);
				case REGISTRY -> button(g, "r:" + f.key(), cx, y, cw, 18, value + "  ...", mouseX, mouseY, true);
			}
		}
		if (visible.size() > rows) {
			g.text(this.font, "scroll for more (" + visible.size() + " fields)", wx + ww - 120, 24, DIM);
		}

		g.text(this.font, "Output", wx, outTop, DIM);
		if (outputError != null) {
			g.text(this.font, this.font.plainSubstrByWidth("Error: " + outputError, ww - 40), wx + 42, outTop, RED);
		}
		int by = this.height - 26;
		int bw = Math.max(40, Math.min(70, (ww - 18) / 4));
		button(g, "run", wx, by, bw, 20, "Run", mouseX, mouseY, outputError == null);
		button(g, "copy", wx + (bw + 6), by, bw, 20, "Copy", mouseX, mouseY, outputError == null);
		button(g, "reset", wx + 2 * (bw + 6), by, bw, 20, System.currentTimeMillis() < resetArmedUntil ? "Sure?" : "Reset", mouseX, mouseY, true);
		button(g, "close", wx + ww - bw, by, bw, 20, "Close", mouseX, mouseY, true);
		int statusX = wx + 3 * (bw + 6) + 6;
		int statusW = wx + ww - bw - 6 - statusX;
		if (System.currentTimeMillis() < statusUntil && statusW > 30) {
			g.text(this.font, this.font.plainSubstrByWidth(status, statusW), statusX, by + 6, GREEN);
		}
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

	// --- hooks for StudioSelfTest ---
	String id() {
		return def.id();
	}

	String outputText() {
		return outputText;
	}

	String outputError() {
		return outputError;
	}

	String status() {
		return status;
	}

	double[] btn(String id) {
		for (Btn b : buttons) {
			if (b.id().equals(id)) {
				return new double[] { b.x() + b.w() / 2.0, b.y() + b.h() / 2.0 };
			}
		}
		return null;
	}

	EditBox box(String key) {
		return boxes.get(key);
	}

	String value(String key) {
		return values.get(key);
	}

	int sideX() {
		return fx;
	}
}
