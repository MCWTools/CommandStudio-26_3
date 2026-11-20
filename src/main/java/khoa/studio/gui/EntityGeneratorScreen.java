package khoa.studio.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;

import khoa.studio.entity.EntityFields;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import khoa.api.picker.ItemPickerOptions;
import khoa.api.picker.KhoaItemPicker;
import khoa.studio.entity.EntityModel;
import khoa.studio.entity.EntitySerializer;
import khoa.studio.persistence.StudioState;
import khoa.studio.serialization.ItemText;
import khoa.studio.serialization.ValidationResult;

/**
 * Entity generator (/summon): entity selector (live registry), position, name, tags, flags, equipment slots (item picker,
 * creative picker or item text) and an NBT tab that can load the game's complete NBT for the chosen type.
 */
public final class EntityGeneratorScreen extends Screen {
	private static final int SIDE_W = 110;
	private static final int RED = 0xFFFF5555;
	private static final int DIM = 0xFFAAAAAA;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GREEN = 0xFF55FF77;
	private static final String[] TABS = { "Main", "Equipment", "NBT", "Fields" };

	private record Btn(String id, int x, int y, int w, int h) {
		boolean hit(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private final EntityModel model = StudioState.get().entity;
	private final List<Btn> buttons = new ArrayList<>();
	private final List<EditBox> slotBoxes = new ArrayList<>();
	private final List<EditBox> countBoxes = new ArrayList<>();
	private EditBox posBox;
	private EditBox nameBox;
	private EditBox tagsBox;
	private MultiLineEditBox nbtBox;
	private List<EntityFields.Field> fields = List.of();
	private final List<EditBox> fieldBoxes = new ArrayList<>();
	private int fieldScroll;
	private static final int FIELD_ROW = 22;
	private MultiLineEditBox outputBox;
	private String outputText = "";
	private String outputError;
	private String status = "";
	private long statusUntil;
	private long resetArmedUntil;
	private boolean pendingRebuild;

	private int fx;
	private int wx;
	private int sideW;
	private int ww;
	private int outTop;
	private int eqStep = 24;

	public EntityGeneratorScreen() {
		super(Component.literal("Command Studio - Entity"));
	}

	@Override
	protected void init() {
		int frameW = Math.min(this.width - 16, 780);
		fx = (this.width - frameW) / 2;
		sideW = this.width < 520 ? 0 : SIDE_W; // no room for the generator list on a small window
		wx = fx + (sideW > 0 ? sideW + 8 : 0);
		ww = frameW - (wx - fx);
		outTop = this.height - 80;
		eqStep = Math.max(17, Math.min(24, (outTop - 4 - 58) / 6));

		posBox = box(wx + 70, 64, 200, model.pos, v -> model.pos = v);
		nameBox = box(wx + 70, 84, 260, model.name, v -> model.name = v);
		tagsBox = box(wx + 70, 104, 260, model.tags, v -> model.tags = v);
		slotBoxes.clear();
		countBoxes.clear();
		for (int i = 0; i < EntityModel.SLOTS.length; i++) {
			String slot = EntityModel.SLOTS[i];
			slotBoxes.add(box(wx + 70, 58 + i * eqStep, Math.max(100, ww - 70 - 194), model.slot(slot), v -> {
				model.equipment.put(slot, v);
			}));
			EditBox cb = box(wx + 70 + Math.max(100, ww - 70 - 194) + 4, 58 + i * eqStep, 36, String.valueOf(model.count(slot)), v -> {
				try {
					model.equipmentCount.put(slot, Integer.parseInt(v.trim()));
				} catch (NumberFormatException e) {
					model.equipmentCount.put(slot, 0); // invalid: the serializer reports it
				}
			});
			cb.setMaxLength(3);
			countBoxes.add(cb);
		}
		nbtBox = MultiLineEditBox.builder().setPlaceholder(Component.literal("{...} extra NBT, or press Load default NBT")).build(this.font, ww, Math.max(30, outTop - 86), Component.literal("NBT"));
		nbtBox.setX(wx);
		nbtBox.setY(80);
		nbtBox.setCharacterLimit(500000);
		nbtBox.setValue(model.nbt);
		nbtBox.setValueListener(v -> {
			model.nbt = v;
			refresh();
		});
		this.addRenderableWidget(nbtBox);

		buildFieldBoxes();

		outputBox = MultiLineEditBox.builder().build(this.font, ww, 28, Component.literal("Output"));
		outputBox.setX(wx);
		outputBox.setY(outTop + 10);
		outputBox.setCharacterLimit(500000);
		outputBox.setValueListener(v -> {
			if (!v.equals(outputText)) {
				outputBox.setValue(outputText);
			}
		});
		this.addRenderableWidget(outputBox);
		refresh();
	}

	private EditBox box(int x, int y, int w, String value, java.util.function.Consumer<String> setter) {
		EditBox b = new EditBox(this.font, x, y, w, 18, Component.empty());
		b.setMaxLength(32000);
		b.setValue(value);
		b.setResponder(v -> {
			setter.accept(v);
			refresh();
		});
		this.addRenderableWidget(b);
		return b;
	}

	private void refresh() {
		ValidationResult r = EntitySerializer.serialize(model);
		outputText = r.valid() ? r.output() : "";
		outputError = r.error();
		if (outputBox != null && !outputBox.getValue().equals(outputText)) {
			outputBox.setValue(outputText);
		}
		StudioState.markDirty();
	}

	private void say(String text) {
		status = text;
		statusUntil = System.currentTimeMillis() + 3000;
	}

	private static String format(ItemStack stack) {
		StringBuilder sb = new StringBuilder(ItemText.idOf(stack));
		Map<String, String> comps = ItemText.components(stack);
		if (!comps.isEmpty()) {
			sb.append('[');
			boolean first = true;
			for (Map.Entry<String, String> e : comps.entrySet()) {
				if (!first) {
					sb.append(',');
				}
				first = false;
				sb.append(e.getKey().startsWith("!") ? e.getKey() : e.getKey() + "=" + e.getValue());
			}
			sb.append(']');
		}
		return sb.toString();
	}

	// ------------------------------------------------------------------ actions

	private void pickEntity() {
		List<String[]> rows = new ArrayList<>();
		for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
			if (type.canSummon()) {
				rows.add(new String[] { BuiltInRegistries.ENTITY_TYPE.getKey(type).toString(), type.getDescription().getString() });
			}
		}
		this.minecraft.gui.setScreen(new ComponentSelectorScreen(this, "Pick an entity (" + rows.size() + ")", rows, id -> {
			if (!id.equals(model.entity) && !model.nbt.isBlank()) {
				model.nbt = ""; // the NBT belongs to the old type
				say("Entity changed: NBT cleared");
			}
			model.entity = id;
			pendingRebuild = true;
		}));
	}

	private void pickSlot(String slot) {
		java.util.function.Consumer<ItemStack> done = stack -> {
			model.equipment.put(slot, format(stack));
			model.equipmentCount.put(slot, Math.max(1, stack.getCount()));
			pendingRebuild = true;
		};
		KhoaItemPicker.open(this, new ItemPickerOptions().title("Equipment " + slot).anyItem().onPickOne(done));
	}

	private void buildFieldBoxes() {
		for (EditBox b : fieldBoxes) {
			if (b != null) {
				this.removeWidget(b);
			}
		}
		fieldBoxes.clear();
		fields = this.minecraft != null && this.minecraft.level != null ? EntityFields.list(model.entity) : List.of();
		for (EntityFields.Field f : fields) {
			if (f.kind() != EntityFields.Kind.NUMBER && f.kind() != EntityFields.Kind.TEXT) {
				fieldBoxes.add(null);
				continue;
			}
			EditBox b = new EditBox(this.font, 0, 0, 100, 18, Component.literal(f.key()));
			b.setMaxLength(2000);
			b.setValue(EntityFields.display(f, EntityFields.current(model.nbt, f.key())));
			b.setResponder(v -> writeField(f, v));
			b.visible = false;
			this.addRenderableWidget(b);
			fieldBoxes.add(b);
		}
	}

	/** Typed text -> NBT tag of the type the game uses; text equal to the default removes the override. Invalid numbers are not written. */
	private void writeField(EntityFields.Field f, String text) {
		Tag tag = EntityFields.parseValue(f, text);
		if (tag == null) {
			return;
		}
		storeField(f.key(), tag.equals(f.def()) ? null : tag);
	}

	private void storeField(String key, Tag tag) {
		String next = EntityFields.set(model.nbt, key, tag);
		if (next == null) {
			say("The NBT text is not valid SNBT; fix it in the NBT tab first");
			return;
		}
		model.nbt = next;
		if (nbtBox != null && !nbtBox.getValue().equals(next)) {
			nbtBox.setValue(next);
		}
		refresh();
	}

	private void toggleField(String key) {
		for (EntityFields.Field f : fields) {
			if (f.key().equals(key)) {
				Tag cur = EntityFields.current(model.nbt, key);
				boolean on = cur instanceof ByteTag b ? b.byteValue() != 0 : ((ByteTag) f.def()).byteValue() != 0;
				ByteTag next = ByteTag.valueOf(!on);
				storeField(key, next.equals(f.def()) ? null : next);
			}
		}
	}

	private void pickFieldItem(String key) {
		net.minecraft.core.RegistryAccess access = khoa.studio.serialization.ItemText.access();
		RegistryOps<net.minecraft.nbt.Tag> ops = RegistryOps.create(NbtOps.INSTANCE, access);
		khoa.studio.give.GiveModel item = new khoa.studio.give.GiveModel();
		net.minecraft.nbt.Tag now = khoa.studio.entity.EntityFields.current(model.nbt, key);
		if (now == null) {
			for (khoa.studio.entity.EntityFields.Field f : khoa.studio.entity.EntityFields.list(model.entity)) {
				if (f.key().equals(key)) {
					now = f.def();
				}
			}
		}
		if (now != null) {
			ItemStack.CODEC.parse(ops, now).result().ifPresent(st -> item.setFrom(st, true));
		}
		item.target = "@s";
		this.minecraft.gui.setScreen(new CommandStudioScreen(this, item, done -> {
			try {
				ItemStack stack = khoa.studio.serialization.ItemText.parse(done.itemText());
				stack.setCount(Math.max(1, done.count));
				ItemStack.CODEC.encodeStart(ops, stack).result().ifPresent(tag -> storeField(key, tag));
			} catch (Exception e) {
				say("Item not applied: " + e.getMessage());
			}
			pendingRebuild = true;
		}));
	}

	private void loadDefaultNbt() {
		String nbt = EntitySerializer.defaultNbt(model.entity);
		if (nbt == null) {
			say("Cannot create a sample of " + model.entity);
			return;
		}
		model.nbt = nbt;
		nbtBox.setValue(nbt);
		pendingRebuild = true;
		say("Loaded the full default NBT of " + model.entity);
	}

	private void run() {
		ValidationResult r = EntitySerializer.serialize(model);
		if (!r.valid()) {
			say("Not run: " + r.error());
			return;
		}
		RunFlow.run(this, r.output(), this::say);
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
		if (sideW > 0 && Sidebar.click(event.x(), event.y(), fx, this.height, "entity")) {
			return true;
		}
		for (Btn b : buttons) {
			if (!b.hit(event.x(), event.y())) {
				continue;
			}
			String id = b.id();
			if (id.startsWith("tab:")) {
				model.tab = Integer.parseInt(id.substring(4));
			} else if (id.startsWith("flag:")) {
				String f = id.substring(5);
				if (!model.flags.remove(f)) {
					model.flags.add(f);
				}
				refresh();
			} else if (id.startsWith("eq:")) {
				pickSlot(id.substring(3));
			} else if (id.startsWith("fb:")) {
				toggleField(id.substring(3));
			} else if (id.startsWith("fi:")) {
				pickFieldItem(id.substring(3));
			} else {
				switch (id) {
					case "entity" -> pickEntity();
					case "loadnbt" -> loadDefaultNbt();
					case "copy" -> {
						if (outputText.isEmpty()) {
							say("Nothing to copy");
						} else {
							this.minecraft.keyboardHandler.setClipboard(outputText);
							say("Copied");
						}
					}
					case "run" -> run();
					case "reset" -> {
						long now = System.currentTimeMillis();
						if (now > resetArmedUntil) {
							resetArmedUntil = now + 3000;
							say("Click Reset again to clear the Entity generator");
						} else {
							resetArmedUntil = 0;
							model.reset();
							pendingRebuild = true;
						}
					}
					case "close" -> onClose();
					case "gen" -> Sidebar.openNext("entity");
					default -> {
					}
				}
			}
			return true;
		}
		return false;
	}

	@Override
	public void tick() {
		if (pendingRebuild) {
			pendingRebuild = false;
			this.rebuildWidgets();
		}
	}

	@Override
	public void removed() {
		StudioState.get().lastGenerator = "entity";
		StudioState.saveNow();
	}

	// ------------------------------------------------------------------ render

	private void button(GuiGraphicsExtractor g, String id, int x, int y, int w, int h, String label, int mx, int my, boolean enabled, boolean active) {
		Btn b = new Btn(id, x, y, w, h);
		if (enabled) {
			buttons.add(b);
		}
		boolean hover = enabled && b.hit(mx, my);
		g.fill(x, y, x + w, y + h, active ? 0xA02E6E9E : (hover ? 0xC0505050 : 0xA0202020));
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
			Sidebar.render(g, this.font, fx, this.height, "entity", mouseX, mouseY);
		} else {
			button(g, "gen", wx + ww - 70, 4, 70, 14, Sidebar.otherLabel("entity"), mouseX, mouseY, true, false);
		}

		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(net.minecraft.resources.Identifier.tryParse(model.entity)).orElse(null);
		int pickW = ww >= 300 ? 110 : 60;
		g.text(this.font, this.font.plainSubstrByWidth(type == null ? model.entity : type.getDescription().getString(), ww - pickW - 8), wx, 24, WHITE);
		g.text(this.font, this.font.plainSubstrByWidth(model.entity, ww - pickW - 8), wx, 34, DIM);
		button(g, "entity", wx + ww - pickW, 24, pickW, 18, ww >= 300 ? "Pick entity" : "Pick", mouseX, mouseY, true, false);
		int tabW = Math.max(40, Math.min(80, (ww - 8) / TABS.length - 4));
		for (int i = 0; i < TABS.length; i++) {
			button(g, "tab:" + i, wx + i * (tabW + 4), 46, tabW, 14, TABS[i], mouseX, mouseY, true, model.tab == i);
		}

		boolean main = model.tab == 0;
		boolean equip = model.tab == 1;
		boolean nbt = model.tab == 2;
		posBox.visible = main;
		nameBox.visible = main;
		tagsBox.visible = main;
		nbtBox.visible = nbt;
		boolean fieldsTab = model.tab == 3;
		for (EditBox b : fieldBoxes) {
			if (b != null) {
				b.visible = false;
			}
		}
		for (EditBox b : slotBoxes) {
			b.visible = equip;
		}
		for (EditBox b : countBoxes) {
			b.visible = equip;
		}
		if (main) {
			g.text(this.font, "Position", wx, 69, DIM);
			g.text(this.font, "Name", wx, 89, DIM);
			g.text(this.font, "Tags (a,b)", wx, 109, DIM);
			for (int i = 0; i < EntityModel.FLAGS.length; i++) {
				String f = EntityModel.FLAGS[i];
				boolean applies = EntitySerializer.flagApplies(model.entity, f);
				int colW = Math.max(60, Math.min(130, ww / 4));
				String flagLabel = this.font.plainSubstrByWidth((model.flags.contains(f) ? "[x] " : "[ ] ") + f, colW - 10);
				button(g, "flag:" + f, wx + (i % 4) * colW, 128 + (i / 4) * 18, colW - 6, 16, flagLabel, mouseX, mouseY, applies || model.flags.contains(f), model.flags.contains(f));
			}
		}
		if (equip && !EntitySerializer.isLiving(model.entity)) {
			for (EditBox b : slotBoxes) {
				b.visible = false;
			}
			for (EditBox b : countBoxes) {
				b.visible = false;
			}
			g.text(this.font, this.font.plainSubstrByWidth(model.entity + " has no equipment slots (only living entities do).", ww), wx, 66, DIM);
		} else if (equip) {
			for (int i = 0; i < EntityModel.SLOTS.length; i++) {
				String slot = EntityModel.SLOTS[i];
				int y = 58 + i * eqStep;
				g.text(this.font, slot, wx, y + 5, DIM);
				int bx = countBoxes.get(i).getX() + countBoxes.get(i).getWidth() + 4;
				button(g, "eq:" + slot, bx, y, 60, eqStep - 2, "Pick", mouseX, mouseY, true, false);
			}
		}
		if (nbt) {
			button(g, "loadnbt", wx, 62, 150, 16, "Load default NBT", mouseX, mouseY, true, false);
			g.text(this.font, this.font.plainSubstrByWidth("Full NBT of this type from the game; edit freely", Math.max(0, ww - 160)), wx + 156, 66, DIM);
		}

		if (fieldsTab) {
			renderFields(g, mouseX, mouseY);
		}

		g.text(this.font, "Output", wx, outTop, DIM);
		if (outputError != null) {
			g.text(this.font, this.font.plainSubstrByWidth("Error: " + outputError, ww - 40), wx + 42, outTop, RED);
		}
		int by = this.height - 26;
		int bw = Math.max(40, Math.min(70, (ww - 18) / 4));
		button(g, "run", wx, by, bw, 20, "Run", mouseX, mouseY, outputError == null, false);
		button(g, "copy", wx + (bw + 6), by, bw, 20, "Copy", mouseX, mouseY, outputError == null, false);
		button(g, "reset", wx + 2 * (bw + 6), by, bw, 20, System.currentTimeMillis() < resetArmedUntil ? "Sure?" : "Reset", mouseX, mouseY, true, false);
		button(g, "close", wx + ww - bw, by, bw, 20, "Close", mouseX, mouseY, true, false);
		int statusX = wx + 3 * (bw + 6) + 6;
		int statusW = wx + ww - bw - 6 - statusX;
		if (System.currentTimeMillis() < statusUntil && statusW > 30) {
			g.text(this.font, this.font.plainSubstrByWidth(status, statusW), statusX, by + 6, GREEN);
		}
		super.extractRenderState(g, mouseX, mouseY, partialTick);
	}

	private int fieldTop() {
		return 64;
	}

	private int fieldVisibleRows() {
		return Math.max(1, (outTop - 6 - fieldTop()) / FIELD_ROW);
	}

	private void renderFields(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		if (fields.isEmpty()) {
			g.text(this.font, this.font.plainSubstrByWidth("No typed fields (join a world, or this entity has none). Use the NBT tab.", ww), wx, fieldTop() + 2, DIM);
			return;
		}
		int visible = fieldVisibleRows();
		fieldScroll = Math.max(0, Math.min(fieldScroll, Math.max(0, fields.size() - visible)));
		g.fill(wx, fieldTop() - 2, wx + ww, fieldTop() + visible * FIELD_ROW, 0x70101010);
		int labelW = Math.max(60, Math.min(190, ww / 3));
		for (int i = fieldScroll; i < fields.size() && i < fieldScroll + visible; i++) {
			EntityFields.Field f = fields.get(i);
			int y = fieldTop() + (i - fieldScroll) * FIELD_ROW;
			Tag cur = EntityFields.current(model.nbt, f.key());
			boolean changed = cur != null && !cur.equals(f.def());
			g.text(this.font, this.font.plainSubstrByWidth((changed ? "* " : "") + f.key(), labelW), wx + 4, y + 5, changed ? GREEN : WHITE);
			int vx = wx + labelW + 8;
			int vw = Math.max(60, ww - labelW - 14);
			switch (f.kind()) {
				case BOOL -> {
					boolean on = (cur instanceof ByteTag b ? b : (ByteTag) f.def()).byteValue() != 0;
					button(g, "fb:" + f.key(), vx, y, 90, 18, (on ? "[x] " : "[ ] ") + on, mouseX, mouseY, true, false);
				}
				case ITEM -> {
					String id = (cur instanceof CompoundTag c ? c : (CompoundTag) f.def()).getStringOr("id", "?");
					button(g, "fi:" + f.key(), vx, y, Math.min(90, vw), 18, "Pick item", mouseX, mouseY, true, false);
					g.text(this.font, this.font.plainSubstrByWidth(id, Math.max(0, vw - 94)), vx + 94, y + 5, DIM);
				}
				default -> {
					EditBox b = fieldBoxes.get(i);
					b.setX(vx);
					b.setY(y);
					b.setWidth(vw);
					b.visible = true;
					if (EntityFields.parseValue(f, b.getValue()) == null) {
						g.text(this.font, "invalid", vx + vw - 40, y + 5, RED);
					}
				}
			}
		}
	}

	@Override
	public boolean mouseScrolled(double x, double y, double sx, double sy) {
		if (sideW > 0 && Sidebar.scrolled(x, y, fx, this.height, sy)) {
			return true;
		}
		if (model.tab == 3 && !fields.isEmpty()) {
			fieldScroll = (int) Math.max(0, Math.min(fields.size() - fieldVisibleRows(), fieldScroll - Math.signum(sy)));
			return true;
		}
		return super.mouseScrolled(x, y, sx, sy);
	}

	// --- hooks for StudioSelfTest ---
	int fieldCount() {
		return fields.size();
	}

	int fieldIndex(EntityFields.Kind kind) {
		for (int i = 0; i < fields.size(); i++) {
			if (fields.get(i).kind() == kind) {
				return i;
			}
		}
		return -1;
	}

	String fieldKey(int i) {
		return fields.get(i).key();
	}

	boolean fieldVisible(int i) {
		return i >= fieldScroll && i < fieldScroll + fieldVisibleRows();
	}

	double[] fieldAreaPoint() {
		return new double[] { wx + 20, fieldTop() + 8 };
	}

	EditBox fieldBox(int i) {
		return fieldBoxes.get(i);
	}

	void openFieldItem(String key) {
		pickFieldItem(key);
	}

	String modelNbt() {
		return model.nbt;
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
		Minecraft.getInstance().gui.setScreen(new EntityGeneratorScreen());
	}
}
