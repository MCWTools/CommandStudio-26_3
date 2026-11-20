package khoa.studio.gui;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import khoa.api.picker.ItemPickerOptions;
import khoa.api.picker.KhoaItemPicker;
import khoa.studio.component.ComponentKinds;
import khoa.studio.component.ComponentKinds.Kind;
import khoa.studio.component.EnchantData;
import khoa.studio.give.GiveModel;
import khoa.studio.give.GiveSerializer;
import khoa.studio.persistence.StudioState;
import khoa.studio.serialization.ItemText;
import khoa.studio.serialization.ValidationResult;

/**
 * Command Studio main screen: generator sidebar on the left, the Give generator on the right, and the shared output
 * panel (multiline output, Run, Copy, Reset) at the bottom. The GUI only edits {@link GiveModel}; the command text is
 * always produced by {@link GiveSerializer}.
 */
public final class CommandStudioScreen extends Screen {
	private static final int SIDE_W = 110;
	private static final int ROW_H = 22;
	private static final int RED = 0xFFFF5555;
	private static final int DIM = 0xFFAAAAAA;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GREEN = 0xFF55FF77;

	private record Btn(String id, int x, int y, int w, int h, String label) {
		boolean hit(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}


	private final GiveModel model;
	/** Set when the screen edits one item for another generator (item entity, thrown item): Run becomes "Use item". */
	private final java.util.function.Consumer<GiveModel> embeddedDone;
	private final Screen embeddedParent;
	private final List<Btn> buttons = new ArrayList<>();
	private final List<EditBox> compBoxes = new ArrayList<>();

	private EditBox countBox;
	private EditBox targetBox;
	private MultiLineEditBox textBox;
	private MultiLineEditBox outputBox;
	private boolean syncing;
	private String textError;
	private String outputText = "";
	private String outputError;
	private ItemStack shownStack = ItemStack.EMPTY;
	private String status = "";
	private long statusUntil;
	private long resetArmedUntil;
	private int compScroll;
	private double sideScroll;

	private int fx;
	private int wx;
	private int sideW;
	private int ww;
	private int compTop;
	private int compBottom;
	private int outTop;

	public CommandStudioScreen() {
		super(Component.literal("Command Studio"));
		this.model = StudioState.get().give;
		this.embeddedDone = null;
		this.embeddedParent = null;
	}

	/** Editor for one item of another generator: the same item picker, components and item tools, on a private model. */
	public CommandStudioScreen(Screen parent, GiveModel model, java.util.function.Consumer<GiveModel> onDone) {
		super(Component.literal("Item"));
		this.model = model;
		this.embeddedDone = onDone;
		this.embeddedParent = parent;
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(embeddedParent);
	}

	// ------------------------------------------------------------------ layout

	@Override
	protected void init() {
		int frameW = Math.min(this.width - 16, 780);
		fx = (this.width - frameW) / 2;
		sideW = embeddedDone != null || this.width < 520 ? 0 : SIDE_W; // no room for the generator list on a small window
		wx = fx + (sideW > 0 ? sideW + 8 : 0);
		ww = frameW - (wx - fx);
		outTop = this.height - 80;
		compTop = 118;
		compBottom = outTop - 6;

		countBox = new EditBox(this.font, wx + 44, 42, 54, 18, Component.literal("Count"));
		countBox.setMaxLength(6);
		countBox.setValue(String.valueOf(model.count));
		countBox.setResponder(v -> {
			try {
				model.count = Integer.parseInt(v.trim());
			} catch (NumberFormatException e) {
				model.count = 0; // invalid: the serializer reports it, the text stays as typed
			}
			refresh();
		});
		this.addRenderableWidget(countBox);

		targetBox = new EditBox(this.font, wx + 184, 42, Math.max(50, Math.min(150, ww - 184)), 18, Component.literal("Target"));
		targetBox.setMaxLength(256);
		targetBox.setValue(model.target);
		targetBox.setResponder(v -> {
			model.target = v;
			refresh();
		});
		this.addRenderableWidget(targetBox);

		textBox = MultiLineEditBox.builder().setPlaceholder(Component.literal("minecraft:diamond_sword[...]")).build(this.font, ww, 26, Component.literal("Item text"));
		textBox.setX(wx);
		textBox.setY(72);
		textBox.setCharacterLimit(60000);
		syncing = true;
		textBox.setValue(model.itemText());
		syncing = false;
		textBox.setValueListener(this::onItemTextEdited);
		this.addRenderableWidget(textBox);

		outputBox = MultiLineEditBox.builder().build(this.font, ww, 28, Component.literal("Output"));
		outputBox.setX(wx);
		outputBox.setY(outTop + 10);
		outputBox.setCharacterLimit(200000);
		outputBox.setValueListener(v -> {
			if (!v.equals(outputText)) { // read only: typing is undone
				outputBox.setValue(outputText);
			}
		});
		this.addRenderableWidget(outputBox);

		rebuildCompBoxes();
		refresh();
	}

	private static boolean usesBox(GiveModel.Comp comp) {
		if (comp.removal()) {
			return false;
		}
		Kind kind = ComponentKinds.of(comp.key);
		return kind == Kind.INTEGER || kind == Kind.DECIMAL || kind == Kind.TEXT || kind == Kind.RAW;
	}

	/** One entry per component: a text box for number / text / raw values, null for the kinds that have their own control. */
	private void rebuildCompBoxes() {
		for (EditBox box : compBoxes) {
			if (box != null) {
				this.removeWidget(box);
			}
		}
		compBoxes.clear();
		for (GiveModel.Comp comp : model.components) {
			if (!usesBox(comp)) {
				compBoxes.add(null);
				continue;
			}
			Kind kind = ComponentKinds.of(comp.key);
			EditBox box = new EditBox(this.font, 0, 0, Math.max(80, ww - 300), 18, Component.literal(comp.key));
			box.setMaxLength(32000);
			box.setHint(Component.literal(ComponentKinds.hint(kind)));
			box.setValue(kind == Kind.TEXT ? ComponentKinds.unquote(comp.value) : comp.value);
			box.setResponder(v -> {
				comp.value = kind == Kind.TEXT ? (v.isEmpty() ? "" : ComponentKinds.quote(v)) : v;
				syncText();
				refresh();
			});
			this.addRenderableWidget(box);
			compBoxes.add(box);
		}
	}

	// ------------------------------------------------------------------ model <-> widgets

	/** The user typed in the ItemStack text: parse it with the game's parser; on error keep the text and the last valid model. */
	private void onItemTextEdited(String text) {
		if (syncing) {
			return;
		}
		try {
			ItemStack stack = ItemText.parse(text);
			if (stack.isEmpty()) {
				throw new IllegalArgumentException("Empty item");
			}
			model.setFrom(stack, false);
			textError = null;
			rebuildCompBoxes();
		} catch (Exception e) {
			textError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
		}
		refresh();
	}

	/** Model -> text box (after a component edit or a pick). */
	private void syncText() {
		syncing = true;
		textBox.setValue(model.itemText());
		syncing = false;
		textError = null;
	}

	/** Recomputes the output from the model and queues a save. */
	private void refresh() {
		ValidationResult result = GiveSerializer.serialize(model);
		outputText = result.valid() ? result.output() : "";
		outputError = result.error();
		if (outputBox != null && !outputBox.getValue().equals(outputText)) {
			outputBox.setValue(outputText);
		}
		try {
			shownStack = ItemText.parse(model.itemText());
		} catch (Exception e) {
			shownStack = ItemStack.EMPTY;
		}
		StudioState.markDirty();
	}

	/** After a structural change (new item, new component): rebuild texts and boxes from the model. */
	private void modelReplaced() {
		syncText();
		countBox.setValue(String.valueOf(model.count));
		rebuildCompBoxes();
		compScroll = 0;
		refresh();
	}

	private void say(String text) {
		status = text;
		statusUntil = System.currentTimeMillis() + 3000;
	}

	// ------------------------------------------------------------------ actions

	private void pickItem() {
		KhoaItemPicker.open(this, new ItemPickerOptions().title("Give: pick an item").anyItem().countEditing(true).onPickOne(stack -> {
			if (stack.getComponentsPatch().isEmpty()) {
				// a plain item: only the item changes, the components the user added stay
				model.item = ItemText.idOf(stack);
				model.count = Math.max(1, stack.getCount());
			} else {
				model.setFrom(stack, true); // an item that carries its own data (saved item, enchanted book) replaces the whole stack
			}
			pendingModelReplaced = true;
		}));
	}


	private boolean pendingModelReplaced;

	private static String summary(java.util.Map<String, Integer> levels) {
		StringBuilder sb = new StringBuilder();
		levels.forEach((id, level) -> sb.append(sb.length() == 0 ? "" : ", ").append(id.substring(id.indexOf(':') + 1)).append(' ').append(level));
		return sb.toString();
	}

	private void openEnchantPicker(int index) {
		GiveModel.Comp comp = model.components.get(index);
		this.minecraft.gui.setScreen(new EnchantPickerScreen(this, EnchantData.parse(comp.value), result -> {
			comp.value = EnchantData.toSnbt(result);
			pendingModelReplaced = true;
		}));
	}

	private int openEnchantFor = -1;

	private void addComponent() {
		this.minecraft.gui.setScreen(new ComponentSelectorScreen(this, key -> {
			if (model.components.stream().noneMatch(c -> c.key.equals(key))) {
				String def = ItemText.defaultValue(model.item, key);
				model.components.add(new GiveModel.Comp(key, def == null ? ItemText.startValue(key) : def));
				if (ComponentKinds.of(key) == Kind.ENCHANTMENTS) {
					openEnchantFor = model.components.size() - 1; // adding enchantments goes straight to the picker
				}
			}
			pendingModelReplaced = true;
		}));
	}

	private void copy() {
		if (outputText.isEmpty()) {
			say("Nothing to copy: " + (outputError == null ? "empty" : "fix the errors first"));
			return;
		}
		this.minecraft.keyboardHandler.setClipboard(outputText);
		say("Copied " + outputText.split("\n").length + " command(s)");
	}

	private void run() {
		ValidationResult result = GiveSerializer.serialize(model);
		if (!result.valid()) {
			say("Not run: " + result.error());
			return;
		}
		if (embeddedDone != null) {
			embeddedDone.accept(model);
			this.minecraft.gui.setScreen(embeddedParent);
			return;
		}
		RunFlow.run(this, result.output(), this::say);
	}

	private void resetCurrent() {
		long now = System.currentTimeMillis();
		if (now > resetArmedUntil) {
			resetArmedUntil = now + 3000;
			say("Click Reset again to clear the Give generator");
			return;
		}
		resetArmedUntil = 0;
		model.reset();
		modelReplaced();
		say("Give generator reset");
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		if (sideW > 0 && Sidebar.click(event.x(), event.y(), fx, this.height, "give")) {
			return true;
		}
		for (Btn b : buttons) {
			if (b.hit(event.x(), event.y())) {
				switch (b.id()) {
					case "pick" -> pickItem();
					case "add" -> addComponent();
					case "copy" -> copy();
					case "run" -> run();
					case "reset" -> resetCurrent();
					case "close" -> onClose();
					case "gen" -> Sidebar.openNext("give");
					default -> {
						if (b.id().startsWith("tool:")) {
							ItemTools.open(ItemTools.Tool.valueOf(b.id().substring(5)), this, model, () -> pendingModelReplaced = true);
						} else if (b.id().startsWith("bool:")) {
							GiveModel.Comp c = model.components.get(Integer.parseInt(b.id().substring(5)));
							c.value = c.value.equals("true") ? "false" : "true";
							syncText();
							refresh();
						} else if (b.id().startsWith("ench:")) {
							openEnchantPicker(Integer.parseInt(b.id().substring(5)));
						} else if (b.id().startsWith("rm:")) {
							model.components.remove(Integer.parseInt(b.id().substring(3)));
							modelReplaced();
						} else if (b.id().startsWith("def:")) {
							GiveModel.Comp c = model.components.get(Integer.parseInt(b.id().substring(4)));
							String def = ItemText.defaultValue(model.item, c.key);
							c.value = def == null ? ItemText.startValue(c.key) : def;
							modelReplaced();
						}
					}
				}
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double sx, double sy) {
		if (sideW > 0 && Sidebar.scrolled(x, y, fx, this.height, sy)) {
			return true;
		}
		if (x >= wx && y >= compTop && y < compBottom) {
			int visible = Math.max(1, (compBottom - compTop) / ROW_H);
			int max = Math.max(0, model.components.size() - visible);
			compScroll = (int) Math.max(0, Math.min(max, compScroll - Math.signum(sy)));
			return true;
		}
		return super.mouseScrolled(x, y, sx, sy);
	}

	@Override
	public void tick() {
		if (pendingModelReplaced) {
			pendingModelReplaced = false;
			modelReplaced();
		}
		if (openEnchantFor >= 0) {
			int index = openEnchantFor;
			openEnchantFor = -1;
			if (index < model.components.size()) {
				openEnchantPicker(index);
			}
		}
	}

	@Override
	public void removed() {
		if (embeddedDone != null) {
			return;
		}
		StudioState.get().lastGenerator = "give";
		StudioState.saveNow();
	}

	// ------------------------------------------------------------------ render

	private void button(GuiGraphicsExtractor g, String id, int x, int y, int w, int h, String label, int mx, int my, boolean enabled) {
		Btn b = new Btn(id, x, y, w, h, label);
		if (enabled) {
			buttons.add(b);
		}
		boolean hover = enabled && b.hit(mx, my);
		g.fill(x, y, x + w, y + h, hover ? 0xC0505050 : 0xA0202020);
		g.fill(x, y, x + w, y + 1, 0xFF777777);
		g.fill(x, y + h - 1, x + w, y + h, 0xFF777777);
		g.fill(x, y, x + 1, y + h, 0xFF777777);
		g.fill(x + w - 1, y, x + w, y + h, 0xFF777777);
		g.centeredText(this.font, label, x + w / 2, y + (h - 8) / 2, enabled ? WHITE : DIM);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		buttons.clear();
		g.text(this.font, "Command Studio", wx, 8, WHITE);

		if (sideW > 0) {
			Sidebar.render(g, this.font, fx, this.height, "give", mouseX, mouseY);
		} else if (embeddedDone == null) {
			button(g, "gen", wx + ww - 70, 4, 70, 14, Sidebar.otherLabel("give"), mouseX, mouseY, true);
		}

		// item row
		g.item(shownStack.isEmpty() ? new ItemStack(Items.BARRIER) : shownStack, wx, 20);
		int pickW = ww >= 300 ? 90 : 44;
		int nameW = Math.max(30, ww - pickW - 30);
		g.text(this.font, this.font.plainSubstrByWidth(shownStack.isEmpty() ? model.item : shownStack.getHoverName().getString(), nameW), wx + 22, 20, WHITE);
		g.text(this.font, this.font.plainSubstrByWidth(model.item, nameW), wx + 22, 30, DIM);
		button(g, "pick", wx + ww - pickW, 20, pickW, 18, ww >= 300 ? "Pick item" : "Pick", mouseX, mouseY, true);

		// count / target
		g.text(this.font, "Count", wx, 47, DIM);
		g.text(this.font, this.font.plainSubstrByWidth("Max " + GiveSerializer.maxStack(model), 44), wx + 104, 47, DIM);
		g.text(this.font, "Target", wx + 150, 47, DIM);

		// item text
		if (textError != null) {
			g.text(this.font, this.font.plainSubstrByWidth("Invalid: " + textError, ww), wx, 63, RED);
		} else {
			g.text(this.font, this.font.plainSubstrByWidth("ItemStack text (syncs with the components below)", ww), wx, 63, DIM);
		}

		// components
		g.text(this.font, "Components (" + model.components.size() + ")", wx, 104, WHITE);
		button(g, "add", wx + 110, 100, Math.min(110, Math.max(60, ww - 112)), 14, ww >= 230 ? "+ Add component" : "+ Add", mouseX, mouseY, true);
		int toolX = wx + 224;
		for (ItemTools.Tool tool : ItemTools.forItem(model.item)) {
			int tw = Math.min(70, wx + ww - toolX);
			if (tw < 40) {
				break;
			}
			button(g, "tool:" + tool.name(), toolX, 100, tw, 14, tool.label + "...", mouseX, mouseY, true);
			toolX += tw + 4;
		}
		renderComponents(g, mouseX, mouseY);

		// output panel
		g.text(this.font, "Output", wx, outTop, DIM);
		if (outputError != null) {
			g.text(this.font, this.font.plainSubstrByWidth("Error: " + outputError, ww - 40), wx + 42, outTop, RED);
		}
		int by = this.height - 26;
		int bw = Math.max(40, Math.min(70, (ww - 18) / 4));
		button(g, "run", wx, by, bw, 20, embeddedDone != null ? "Use" : "Run", mouseX, mouseY, outputError == null);
		button(g, "copy", wx + (bw + 6), by, bw, 20, "Copy", mouseX, mouseY, outputError == null);
		button(g, "reset", wx + 2 * (bw + 6), by, bw, 20, System.currentTimeMillis() < resetArmedUntil ? "Sure?" : "Reset", mouseX, mouseY, true);
		button(g, "close", wx + ww - bw, by, bw, 20, "Close", mouseX, mouseY, true);
		int statusX = wx + 3 * (bw + 6) + 6;
		int statusW = wx + ww - bw - 6 - statusX;
		if (System.currentTimeMillis() < statusUntil && statusW > 30) {
			g.text(this.font, this.font.plainSubstrByWidth(status, statusW), statusX, by + 6, GREEN);
		}

		super.extractRenderState(g, mouseX, mouseY, partialTick); // all the text boxes
	}

	private void renderComponents(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int visible = Math.max(1, (compBottom - compTop) / ROW_H);
		compScroll = Math.max(0, Math.min(compScroll, Math.max(0, model.components.size() - visible)));
		for (EditBox box : compBoxes) {
			if (box != null) {
				box.visible = false;
			}
		}
		g.fill(wx, compTop, wx + ww, compBottom, 0x70101010);
		if (model.components.isEmpty()) {
			g.text(this.font, this.font.plainSubstrByWidth("No components. Add one, or type them in the ItemStack text.", ww - 12), wx + 6, compTop + 6, DIM);
		}
		if (compBoxes.size() != model.components.size()) {
			pendingModelReplaced = true; // the model changed under us: rebuild next tick, draw nothing wrong now
			return;
		}
		int labelW = Math.max(60, Math.min(170, ww / 3));
		for (int i = compScroll; i < model.components.size() && i < compScroll + visible; i++) {
			GiveModel.Comp comp = model.components.get(i);
			int y = compTop + (i - compScroll) * ROW_H + 2;
			String error = GiveSerializer.validateComponent(model.item, comp);
			boolean modified = comp.modified(model.item);
			g.text(this.font, this.font.plainSubstrByWidth((modified ? "* " : "") + comp.key, labelW), wx + 4, y + 5, error != null ? RED : (modified ? GREEN : WHITE));
			Kind kind = ComponentKinds.of(comp.key);
			int vx = wx + labelW + 6;
			int vw = Math.max(60, ww - labelW - 6 - 78);
			if (comp.removal()) {
				g.text(this.font, this.font.plainSubstrByWidth("(removed from the item)", vw), vx + 2, y + 5, DIM);
			} else if (kind == Kind.BOOLEAN) {
				boolean on = comp.value.equals("true");
				button(g, "bool:" + i, vx, y, 90, 18, (on ? "[x] " : "[ ] ") + (on ? "true" : "false"), mouseX, mouseY, true);
			} else if (kind == Kind.UNIT) {
				g.text(this.font, this.font.plainSubstrByWidth("present (no value)", vw), vx + 2, y + 5, DIM);
			} else if (kind == Kind.ENCHANTMENTS) {
				java.util.Map<String, Integer> levels = EnchantData.parse(comp.value);
				button(g, "ench:" + i, vx, y, Math.min(130, vw), 18, vw >= 130 ? "Edit enchantments (" + levels.size() + ")" : "Enchants (" + levels.size() + ")", mouseX, mouseY, true);
				g.text(this.font, this.font.plainSubstrByWidth(summary(levels), Math.max(0, vw - 134)), vx + 134, y + 5, DIM);
			} else {
				EditBox box = compBoxes.get(i);
				box.setX(vx);
				box.setY(y);
				box.setWidth(vw);
				box.visible = true;
			}
			if (error != null && !comp.removal() && !(kind == Kind.BOOLEAN || kind == Kind.UNIT)) {
				g.text(this.font, this.font.plainSubstrByWidth(error, vw), vx + 2, y + 10, RED);
			}
			button(g, "def:" + i, wx + ww - 72, y, 34, 18, "Rst", mouseX, mouseY, true);
			button(g, "rm:" + i, wx + ww - 36, y, 32, 18, "X", mouseX, mouseY, true);
		}
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
	double[] btn(String id) {
		for (Btn b : buttons) {
			if (b.id().equals(id)) {
				return new double[] { b.x() + b.w() / 2.0, b.y() + b.h() / 2.0 };
			}
		}
		return null;
	}

	boolean rowVisible(int i) {
		int visible = Math.max(1, (compBottom - compTop) / ROW_H);
		return i >= compScroll && i < compScroll + visible;
	}

	/** A point inside the component list, for wheel events. */
	double[] compAreaPoint() {
		return new double[] { wx + 40, compTop + 10 };
	}

	int compScrollPos() {
		return compScroll;
	}

	EditBox compBox(int i) {
		return i < compBoxes.size() ? compBoxes.get(i) : null;
	}

	void setItemForTest(String id) {
		model.item = id;
		modelReplaced();
	}

	int sideX() {
		return fx;
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

	public static void open() {
		Minecraft.getInstance().gui.setScreen(new CommandStudioScreen());
	}
}
